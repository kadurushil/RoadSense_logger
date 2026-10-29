"""
test_mcap_vision_enrichment.py
-------------------------------
Unit tests for Foxglove MCAP vision sub-topics enrichment:
  1. /vision/2d_boxes (foxglove.ImageAnnotations)
  2. /vision/geo_tracks (foxglove.SceneUpdate with 3D cuboids, velocity arrows, footprints)
  3. /vision/trajectories (foxglove.SceneUpdate with 3D breadcrumb line strips)
  4. /vision/camera_frustum (foxglove.SceneUpdate with ground projection trapezoid)
  5. /vision/diagnostics (roadsense.VisionDiagnostics 3-tier State Matrix telemetry)
"""

from collections import deque
import json
import math
import os
import shutil
import tempfile
import unittest

from foxglove_schemas_protobuf.PointsAnnotation_pb2 import PointsAnnotation
from foxglove_schemas_protobuf.LinePrimitive_pb2 import LinePrimitive
from mcap.reader import make_reader

from tools.vision.mcap.mcap_scene_writer import (
    McapSceneWriter,
    TrajectoryTracker,
    VISION_DIAGNOSTICS_JSON_SCHEMA,
)
from tools.vision.mcap.inject_vision_tracks import write_standalone_vision_mcap


class TestMcapVisionEnrichment(unittest.TestCase):

    def setUp(self):
        self.sample_tracks = [
            {
                "track_id": 4991,
                "class_name": "car",
                "confidence": 0.88,
                "range_m": 28.05,
                "bbox_2d": [580.0, 270.0, 640.0, 320.0],
                "position_3d": {"x_fwd_m": 28.01, "y_left_m": 1.45, "z_up_m": 0.75},
                "dimensions_3d": {"length_m": 4.2, "width_m": 1.8, "height_m": 1.5},
                "orientation": {"x": 0.0, "y": 0.0, "z": 1.0, "w": 0.0},
                "yaw_rad": 3.1415,
                "yaw_deg": 180.0,
                "speed_mps": 11.2,
                "vx_mps": -10.6,
                "vy_mps": 0.15,
                "is_moving": True,
                "basin": "oncoming",
            },
            {
                "track_id": 767,
                "class_name": "traffic_sign",
                "confidence": 0.92,
                "range_m": 14.50,
                "bbox_2d": [200.0, 150.0, 240.0, 210.0],
                "position_3d": {"x_fwd_m": 14.45, "y_left_m": 3.20, "z_up_m": 1.80},
                "dimensions_3d": {"length_m": 0.4, "width_m": 0.8, "height_m": 0.8},
                "orientation": {"x": 0.0, "y": 0.0, "z": 0.0, "w": 1.0},
                "yaw_rad": 0.0,
                "yaw_deg": 0.0,
                "speed_mps": 0.0,
                "vx_mps": 0.0,
                "vy_mps": 0.0,
                "is_moving": False,
                "basin": "none",
            }
        ]
        self.ts = 1792750280123456789

    def test_image_annotations_generation(self):
        ia = McapSceneWriter.create_image_annotations(self.ts, self.sample_tracks)
        self.assertEqual(len(ia.points), 2, "Must create 2 2D bounding boxes")
        self.assertEqual(len(ia.texts), 2, "Must create 2 text labels")
        self.assertEqual(len(ia.circles), 2, "Must create 2 contact point circles")

        # Verify Box 1 (Car)
        box1 = ia.points[0]
        self.assertEqual(box1.type, PointsAnnotation.Type.LINE_LOOP)
        self.assertEqual(len(box1.points), 4, "Rectangle must have 4 vertices")
        self.assertAlmostEqual(box1.points[0].x, 580.0)
        self.assertAlmostEqual(box1.points[0].y, 270.0)
        self.assertAlmostEqual(box1.points[2].x, 640.0)
        self.assertAlmostEqual(box1.points[2].y, 320.0)

        # Verify Contact Dot (Bottom Center)
        dot1 = ia.circles[0]
        self.assertAlmostEqual(dot1.position.x, (580.0 + 640.0) / 2.0)
        self.assertAlmostEqual(dot1.position.y, 320.0)
        self.assertAlmostEqual(dot1.diameter, 8.0)
        self.assertAlmostEqual(dot1.fill_color.g, 1.0)  # Neon green

        # Verify Label
        lbl1 = ia.texts[0]
        self.assertIn("ID #4991", lbl1.text)
        self.assertIn("CAR", lbl1.text)
        self.assertIn("28.1m", lbl1.text)

    def test_image_annotations_generation_rotated_180(self):
        ia = McapSceneWriter.create_image_annotations(
            self.ts,
            self.sample_tracks,
            image_width=1280,
            image_height=720,
            video_rotation_deg=180,
        )
        self.assertEqual(len(ia.points), 2)
        self.assertEqual(len(ia.texts), 2)
        self.assertEqual(len(ia.circles), 2)

        # Inverted box 1 (Car): [580, 270, 640, 320]
        # rx1 = 1280 - 640 = 640, ry1 = 720 - 320 = 400
        # rx2 = 1280 - 580 = 700, ry2 = 720 - 270 = 450
        box1 = ia.points[0]
        self.assertEqual(box1.type, PointsAnnotation.Type.LINE_LOOP)
        self.assertAlmostEqual(box1.points[0].x, 640.0)
        self.assertAlmostEqual(box1.points[0].y, 400.0)
        self.assertAlmostEqual(box1.points[2].x, 700.0)
        self.assertAlmostEqual(box1.points[2].y, 450.0)

        # Contact patch dot: bottom center in upright (610, 320) -> (1280-610, 720-320) = (670, 400)
        dot1 = ia.circles[0]
        self.assertAlmostEqual(dot1.position.x, 670.0)
        self.assertAlmostEqual(dot1.position.y, 400.0)

        # Label: top-left in upright (580, 266) -> (1280-580, 720-266) = (700, 454)
        lbl1 = ia.texts[0]
        self.assertAlmostEqual(lbl1.position.x, 700.0)
        self.assertAlmostEqual(lbl1.position.y, 454.0)

        # Verify that Foxglove's 180° rotation recovers the exact upright bounding box & contact patch
        disp_x1 = 1280.0 - box1.points[2].x  # 1280 - 700 = 580
        disp_y1 = 720.0 - box1.points[2].y   # 720 - 450 = 270
        disp_x2 = 1280.0 - box1.points[0].x  # 1280 - 640 = 640
        disp_y2 = 720.0 - box1.points[0].y   # 720 - 400 = 320
        self.assertAlmostEqual(disp_x1, 580.0)
        self.assertAlmostEqual(disp_y1, 270.0)
        self.assertAlmostEqual(disp_x2, 640.0)
        self.assertAlmostEqual(disp_y2, 320.0)

        disp_dot_x = 1280.0 - dot1.position.x
        disp_dot_y = 720.0 - dot1.position.y
        self.assertAlmostEqual(disp_dot_x, 610.0)
        self.assertAlmostEqual(disp_dot_y, 320.0)  # Exactly on bottom edge of vehicle!

    def test_scene_update_velocity_arrows_and_footprints(self):
        su = McapSceneWriter.create_scene_update(self.ts, self.sample_tracks)
        entity = su.entities[0]

        # 2 Cuboids
        self.assertEqual(len(entity.cubes), 2)
        # 1 Velocity Arrow (only for moving Track 4991, not stationary Track 767)
        self.assertEqual(len(entity.arrows), 1)
        arrow = entity.arrows[0]
        self.assertAlmostEqual(arrow.pose.position.x, 28.01)
        self.assertAlmostEqual(arrow.pose.position.y, 1.45)
        self.assertGreater(arrow.shaft_length, 0.5)
        self.assertAlmostEqual(arrow.color.r, 1.0)
        self.assertAlmostEqual(arrow.color.g, 0.7)  # Amber arrow

        # 2 Ground Footprints
        self.assertEqual(len(entity.lines), 2)
        fp1 = entity.lines[0]
        self.assertEqual(fp1.type, LinePrimitive.Type.LINE_LOOP)
        self.assertEqual(len(fp1.points), 4)
        for pt in fp1.points:
            self.assertAlmostEqual(pt.z, 0.02)  # Anchored on ground

    def test_trajectories_update(self):
        history = {
            4991: deque([(28.0, 1.45, 0.08), (27.0, 1.45, 0.08), (26.0, 1.45, 0.08)]),
            767: deque([(14.45, 3.20, 0.08)]),  # Only 1 point, should be skipped
        }
        tu = McapSceneWriter.create_trajectories_update(self.ts, history)
        entity = tu.entities[0]
        self.assertEqual(len(entity.lines), 1, "Only tracks with >=2 points get a trajectory line")
        line = entity.lines[0]
        self.assertEqual(line.type, LinePrimitive.Type.LINE_STRIP)
        self.assertEqual(len(line.points), 3)

    def test_trajectory_tracker_pruning_and_subsampling(self):
        tracker = TrajectoryTracker(max_age_seconds=15.0, min_interval_seconds=0.10)
        base_ts = 1_000_000_000_000  # 1000s in ns

        # Frame 1: Track 101 appears at (10, 0)
        h = tracker.update(base_ts, [{"track_id": 101, "position_3d": {"x_fwd_m": 10.0, "y_left_m": 0.0}}])
        self.assertIn(101, h)
        self.assertEqual(len(h[101]), 1)

        # Frame 2: 33ms later (same track, below 100ms interval -> should NOT append new point)
        h = tracker.update(base_ts + 33_333_333, [{"track_id": 101, "position_3d": {"x_fwd_m": 10.5, "y_left_m": 0.0}}])
        self.assertEqual(len(h[101]), 1, "Sub-100ms frame should not spam trajectory points")

        # Frame 4: 100ms later -> should append second point
        h = tracker.update(base_ts + 100_000_000, [{"track_id": 101, "position_3d": {"x_fwd_m": 11.0, "y_left_m": 0.0}}])
        self.assertEqual(len(h[101]), 2)

        # Frame 5: Track 202 appears at t = base_ts + 1s
        h = tracker.update(base_ts + 1_000_000_000, [
            {"track_id": 101, "position_3d": {"x_fwd_m": 12.0, "y_left_m": 0.0}},
            {"track_id": 202, "position_3d": {"x_fwd_m": 25.0, "y_left_m": -2.0}},
        ])
        self.assertIn(101, h)
        self.assertIn(202, h)

        # Track 101 stops being detected. Track 202 continues for 10 seconds (t = base_ts + 11s)
        # Track 101 age is 10s <= 15s -> still retained
        h = tracker.update(base_ts + 11_000_000_000, [{"track_id": 202, "position_3d": {"x_fwd_m": 30.0, "y_left_m": -2.0}}])
        self.assertIn(101, h, "Track unseen for 10s should remain visible (within 15s window)")
        self.assertIn(202, h)

        # Advance to t = base_ts + 17s (> 15s since Track 101 was seen)
        # Track 101 must now be pruned!
        h = tracker.update(base_ts + 17_000_000_000, [{"track_id": 202, "position_3d": {"x_fwd_m": 35.0, "y_left_m": -2.0}}])
        self.assertNotIn(101, h, "Track unseen for >15s must be pruned to prevent MCAP bloat")
        self.assertIn(202, h)

    def test_camera_frustum_update(self):
        fu = McapSceneWriter.create_camera_frustum_update(self.ts)
        entity = fu.entities[0]
        self.assertEqual(len(entity.lines), 2, "Frustum must contain ground trapezoid and corner rays")

    def test_diagnostics_message_and_state_matrix(self):
        diag = McapSceneWriter.create_diagnostics_message(
            timestamp_ns=self.ts,
            frame_idx=100,
            tracks=self.sample_tracks,
            ego_speed_mps=12.5,
            ego_yaw_rate_can_radps=-0.035,
            ego_yaw_rate_smoothed_radps=-0.032,
            turning_model_active=True,
            deadband_scale=0.95,
        )

        # Tier 1: Ego Dynamics
        self.assertEqual(diag["frame_index"], 100)
        self.assertAlmostEqual(diag["ego_speed_kmh"], 45.0)
        self.assertTrue(diag["ego_turning_model_active"])
        self.assertAlmostEqual(diag["ego_deadband_scale"], 0.95)
        self.assertEqual(diag["track_count"], 2)
        self.assertEqual(diag["moving_track_count"], 1)

        # Tier 2: Lead Obstacle Summary (Car 4991 is closest moving vehicle in path)
        lead = diag["lead_obstacle"]
        self.assertIsNotNone(lead)
        self.assertEqual(lead["track_id"], 767)  # Closest obstacle is 767 at 14.5m
        self.assertAlmostEqual(lead["range_m"], 14.50)

        # Tier 3: Per-Track State Matrix
        track1 = diag["tracks"][0]
        self.assertEqual(track1["track_id"], 4991)
        self.assertEqual(track1["basin"], "oncoming")

        sm = track1["state_matrix"]
        self.assertIn("state_vector", sm)
        self.assertAlmostEqual(sm["state_vector"]["x_m"], 28.01)
        self.assertAlmostEqual(sm["state_vector"]["vx_mps"], -10.6)
        self.assertAlmostEqual(sm["state_vector"]["yaw_deg"], 180.0)

        self.assertIn("covariance_diag", sm)
        self.assertGreater(sm["covariance_diag"]["sigma_x_m"], 0.0)
        self.assertGreater(sm["covariance_diag"]["sigma_yaw_deg"], 0.0)

        self.assertIn("covariance_matrix", sm)
        self.assertEqual(len(sm["covariance_matrix"]), 6, "Must be 6x6 covariance matrix")
        self.assertEqual(len(sm["covariance_matrix"][0]), 6)

    def test_standalone_mcap_generation(self):
        temp_dir = tempfile.mkdtemp(prefix="roadsense_test_mcap_")
        try:
            json_path = os.path.join(temp_dir, "test_session_vision_tracks.json")
            mcap_path = os.path.join(temp_dir, "test_session_vision_standalone.mcap")

            data = {
                "session_name": "test_session",
                "time_offset_ns": 0,
                "frames": [
                    {
                        "frame_index": 0,
                        "timestamp_utc_ns": self.ts,
                        "ego_speed_mps": 10.0,
                        "ego_yaw_rate_radps": 0.02,
                        "tracks": self.sample_tracks,
                    },
                    {
                        "frame_index": 1,
                        "timestamp_utc_ns": self.ts + 100_000_000,
                        "ego_speed_mps": 10.2,
                        "ego_yaw_rate_radps": 0.019,
                        "tracks": self.sample_tracks,
                    }
                ]
            }
            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(data, f)

            write_standalone_vision_mcap(json_path, mcap_path)
            self.assertTrue(os.path.isfile(mcap_path))

            # Verify contents using mcap reader
            with open(mcap_path, "rb") as mf:
                reader = make_reader(mf)
                summary = reader.get_summary()
                topics = {c.topic for c in summary.channels.values()}

                self.assertIn("/vision/geo_tracks", topics)
                self.assertIn("/vision/2d_boxes", topics)
                self.assertIn("/vision/trajectories", topics)
                self.assertIn("/vision/camera_frustum", topics)
                self.assertIn("/vision/diagnostics", topics)

                # Verify attachments
                att_names = [a.name for a in reader.iter_attachments()]
                self.assertIn("RoadSense_Cockpit_Layout.json", att_names)
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
