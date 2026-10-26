"""
test_vision_box_alignment.py
----------------------------
Unit tests verifying 3D bounding box volumetric center offset and
near-surface alignment with tire contact patches.
"""

import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.vision.geometry.box_dimensions import BoxDimensions, CLASS_PRIORS

try:
    import cv2
    HAS_OPENCV = True
except ImportError:
    HAS_OPENCV = False


class TestVisionBoxAlignment(unittest.TestCase):
    def test_center_offset_from_contact_patch_optical(self):
        # Head-on car with contact patch at 15m, 114px width (~1.8m physical width), 95px height in 720p (fx=947)
        contact_x = 15.0
        y_left = 0.5
        box = BoxDimensions.compute_3d_box(
            x_fwd_m=contact_x,
            y_left_m=y_left,
            class_name="car",
            w_px=114.0,
            h_px=95.0,
            fx=947.0,
            fy=947.0,
            use_optical_sizing=True,
        )

        size = box["size"]
        center = box["center"]
        contact = box["contact_patch"]

        # 1. Contact patch should equal input contact distance
        self.assertAlmostEqual(contact["x_fwd_m"], contact_x, places=3)
        self.assertAlmostEqual(contact["y_left_m"], y_left, places=3)
        self.assertAlmostEqual(contact["z_up_m"], 0.0, places=3)

        # 2. Volumetric center must be offset forward by +L/2
        expected_center_x = contact_x + (size["length_m"] / 2.0)
        self.assertAlmostEqual(center["x_fwd_m"], expected_center_x, places=3)

        # 3. Near face of the 3D box must land EXACTLY on the contact patch
        near_face_x = center["x_fwd_m"] - (size["length_m"] / 2.0)
        self.assertAlmostEqual(near_face_x, contact_x, places=3)

        # 4. Bottom of the 3D box must sit on asphalt (Z = 0)
        bottom_face_z = center["z_up_m"] - (size["height_m"] / 2.0)
        self.assertAlmostEqual(bottom_face_z, 0.0, places=2)

        # 5. Head-on car must have identity yaw (0°)
        self.assertAlmostEqual(box["yaw_deg"], 0.0, places=1)
        self.assertEqual(box["orientation"]["w"], 1.0)

    def test_center_offset_with_class_priors(self):
        contact_x = 20.0
        y_left = -1.2
        box = BoxDimensions.compute_3d_box(
            x_fwd_m=contact_x,
            y_left_m=y_left,
            class_name="truck",
            w_px=118.0,
            h_px=150.0,
            fx=947.0,
            fy=947.0,
            use_optical_sizing=False,
        )

        prior_len, prior_w, prior_h = CLASS_PRIORS["truck"]
        self.assertEqual(box["size"]["length_m"], prior_len)
        expected_center_x = contact_x + (prior_len / 2.0)
        self.assertAlmostEqual(box["center"]["x_fwd_m"], expected_center_x, places=3)

    def test_broadside_motorcycle_parked_on_curb(self):
        # Motorcycle at 12.6m, 150px wide (span ~2.0m) parked on left curb (y=2.5m)
        box = BoxDimensions.compute_3d_box(
            x_fwd_m=12.6,
            y_left_m=2.5,
            class_name="motorcycle",
            w_px=150.0,
            h_px=98.0,
            fx=947.0,
            fy=947.0,
            use_optical_sizing=True,
        )

        self.assertTrue(box["is_broadside"])
        # Width must be handlebar width (0.8m), NOT 2.0m!
        self.assertEqual(box["size"]["width_m"], 0.8)
        # Length captures the broadside span
        self.assertGreaterEqual(box["size"]["length_m"], 1.9)
        # Must orient 90° along curb
        self.assertEqual(box["yaw_deg"], 90.0)
        self.assertAlmostEqual(box["orientation"]["z"], 0.7071, places=3)
        self.assertAlmostEqual(box["orientation"]["w"], 0.7071, places=3)
        # Near side panel lands on contact patch
        near_x = box["center"]["x_fwd_m"] - (box["size"]["width_m"] / 2.0)
        self.assertAlmostEqual(near_x, 12.6, places=3)

    def test_compact_vehicle_sanity_guard_qute(self):
        # Bajaj Qute at 2.7m misclassified as truck
        box = BoxDimensions.compute_3d_box(
            x_fwd_m=2.7,
            y_left_m=0.1,
            class_name="truck",
            w_px=450.0,
            h_px=550.0,
            fx=947.0,
            fy=947.0,
            use_optical_sizing=True,
        )

        self.assertIn(box["effective_class"], ("quadricycle", "car"))
        self.assertLess(box["size"]["length_m"], 4.5, "Qute length must be compact, not 8.5m truck")
        self.assertLess(box["size"]["width_m"], 1.6)

    def test_kinematic_heading_tracker(self):
        from tools.vision.geometry.kinematic_tracker import KinematicHeadingTracker
        tracker = KinematicHeadingTracker()

        # Simulate vehicle turning left across 4 frames
        # Moving at ~5 m/s (+X) and +2 m/s (+Y)
        t_base = 1_000_000_000_000_000
        # Frame 1
        res1 = tracker.update(track_id=10, x_fwd_m=20.0, y_left_m=0.0, timestamp_ns=t_base)
        self.assertFalse(res1["is_moving"])
        # Frame 2
        res2 = tracker.update(track_id=10, x_fwd_m=20.5, y_left_m=0.2, timestamp_ns=t_base + 100_000_000)
        self.assertFalse(res2["is_moving"])
        # Frame 3
        res3 = tracker.update(track_id=10, x_fwd_m=21.0, y_left_m=0.4, timestamp_ns=t_base + 200_000_000)
        self.assertTrue(res3["is_moving"])
        self.assertIsNotNone(res3["yaw_rad"])
        # Heading should be positive (turning left, vy > 0)
        self.assertGreater(res3["yaw_rad"], 0.0)

    def test_turning_vehicle_3d_box_orientation(self):
        # Car at 10m turning at 45° (0.7854 rad)
        import math
        yaw_45 = math.radians(45.0)
        box = BoxDimensions.compute_3d_box(
            x_fwd_m=10.0,
            y_left_m=-1.0,
            class_name="car",
            w_px=114.0,
            h_px=95.0,
            fx=947.0,
            fy=947.0,
            use_optical_sizing=False,
            yaw_rad=yaw_45,
            is_moving=True,
        )

        self.assertAlmostEqual(box["yaw_deg"], 45.0, places=1)
        # Expected quaternion for 45°: z = sin(22.5°) = 0.3827, w = cos(22.5°) = 0.9239
        self.assertAlmostEqual(box["orientation"]["z"], math.sin(math.radians(22.5)), places=3)
        self.assertAlmostEqual(box["orientation"]["w"], math.cos(math.radians(22.5)), places=3)
        # Depth offset: (L/2)*cos(45°) + (W/2)*sin(45°)
        prior_l, prior_w, _ = CLASS_PRIORS["car"]
        expected_d = (prior_l / 2.0) * math.cos(yaw_45) + (prior_w / 2.0) * math.sin(yaw_45)
        self.assertAlmostEqual(box["center"]["x_fwd_m"], 10.0 + expected_d, places=3)


    @unittest.skipUnless(HAS_OPENCV, "OpenCV (cv2) not available in this Python environment")
    def test_resolve_2d_detections_path_auto(self):
        from tools.vision.pipeline.perception_runner import PerceptionRunner
        session_dir = "logs/session_20261023_111120"
        # Auto resolution should find tools/vision/output/json/session_20261023_111120_vision_tracks.json
        resolved = PerceptionRunner.resolve_2d_detections_path(session_dir, "auto")
        self.assertTrue(os.path.isfile(resolved))
        self.assertTrue("session_20261023_111120_vision_tracks.json" in resolved)

        # When pointing to non-existent baseline_yolo_tracking.json, it should gracefully fallback to existing tracks
        fallback = PerceptionRunner.resolve_2d_detections_path(
            session_dir, "logs/session_20261023_111120/baseline_yolo_tracking.json"
        )
        self.assertTrue(os.path.isfile(fallback))
        self.assertTrue("session_20261023_111120_vision_tracks.json" in fallback)

    @unittest.skipUnless(HAS_OPENCV, "OpenCV (cv2) not available in this Python environment")
    def test_resolve_2d_detections_path_not_found(self):
        from tools.vision.pipeline.perception_runner import PerceptionRunner
        with self.assertRaises(FileNotFoundError) as ctx:
            PerceptionRunner.resolve_2d_detections_path("logs/non_existent_session_999999", "custom.json")
        self.assertIn("No pre-existing 2D detections JSON found", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()

