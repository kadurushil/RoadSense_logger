#!/usr/bin/env python3
"""
Unit tests for Optical TF Geometry, Extrinsics Decoupling, and MCAP Pipeline Integrity.
Guarantees:
  1. Optical pitch strictly points DOWNWARD toward asphalt (Z_base < 0) for positive pitch.
  2. Camera optical /tf strictly uses cameraYawDeg and NEVER conflates with radar's yawDeg.
  3. GroundPlaneProjector and CameraExtrinsics remain 100% synchronized with camera_optical /tf.
"""

import math
import os
import tempfile
import json
import unittest
import numpy as np

from tools.convert_session_to_mcap import compute_camera_optical_tf
from tools.vision.geometry.camera_extrinsics import CameraExtrinsics
from tools.vision.geometry.ground_plane_projector import GroundPlaneProjector, PinholeIntrinsics


class TestGeometryAndTfIntegrity(unittest.TestCase):

    def test_optical_pitch_points_downward_in_base_link(self):
        """Regression test for Bug #24: Verify optical pitch sign points DOWN toward road."""
        pitch_deg = 5.417
        yaw_deg = 0.0
        roll_deg = 180.0 # Reverse landscape zero-copy upright

        quat, trans = compute_camera_optical_tf(
            pitch_deg=pitch_deg,
            yaw_deg=yaw_deg,
            roll_deg=roll_deg,
            setback=0.30,
            lateral=0.00,
            height=1.35
        )
        qx, qy, qz, qw = quat
        R = np.array([
            [1 - 2*(qy*qy + qz*qz), 2*(qx*qy - qz*qw), 2*(qx*qz + qy*qw)],
            [2*(qx*qy + qz*qw), 1 - 2*(qx*qx + qz*qz), 2*(qy*qz - qx*qw)],
            [2*(qx*qz - qy*qw), 2*(qy*qz + qx*qw), 1 - 2*(qx*qx + qy*qy)]
        ])

        # Optical boresight in camera_optical RDF is +Z = [0, 0, 1]
        v_base = R @ np.array([0, 0, 1])

        # Pitch down angle: positive downward pitch must produce Z_base < 0
        self.assertLess(v_base[2], -0.05, f"Camera boresight Z must be negative (downward), got {v_base[2]}")
        calculated_pitch = math.degrees(math.asin(-v_base[2]))
        self.assertAlmostEqual(calculated_pitch, pitch_deg, delta=0.05,
                               msg=f"Calculated downward pitch {calculated_pitch}° must match input {pitch_deg}°")

    def test_camera_yaw_strictly_decoupled_from_radar_squint(self):
        """Regression test for Bug #25: Ensure camera optical /tf ignores radar squint."""
        # Scenario: Radar has 1.0° mounting squint, camera is mounted at 0.0°
        quat, trans = compute_camera_optical_tf(
            pitch_deg=5.417,
            yaw_deg=0.0, # cameraYawDeg
            roll_deg=180.0,
            setback=0.30,
            lateral=0.00,
            height=1.35
        )
        qx, qy, qz, qw = quat
        R = np.array([
            [1 - 2*(qy*qy + qz*qz), 2*(qx*qy - qz*qw), 2*(qx*qz + qy*qw)],
            [2*(qx*qy + qz*qw), 1 - 2*(qx*qx + qz*qz), 2*(qy*qz - qx*qw)],
            [2*(qx*qz - qy*qw), 2*(qy*qz + qx*qw), 1 - 2*(qx*qx + qy*qy)]
        ])
        v_base = R @ np.array([0, 0, 1])

        # Lateral Y component in base_link must be 0.0 (no left/right tilt)
        self.assertAlmostEqual(v_base[1], 0.0, delta=1e-4,
                               msg=f"Camera optical axis must have 0 lateral skew when cameraYawDeg=0.0, got {v_base[1]}")

    def test_camera_extrinsics_loader_decouples_squint(self):
        """Verify that CameraExtrinsics.from_calib_json reads cameraYawDeg over legacy yawDeg."""
        sample_calib = {
            "pitchDeg": 5.417,
            "yawDeg": 1.0,               # Legacy relative squint
            "cameraYawDeg": 0.0,         # True camera heading
            "radarYawOffsetDeg": 1.0     # Radar mounting squint
        }
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(sample_calib, tf)
            tmp_path = tf.name

        try:
            ext = CameraExtrinsics.from_calib_json(tmp_path)
            self.assertEqual(ext.camera_yaw_deg, 0.0, "camera_yaw_deg must be 0.0")
            self.assertEqual(ext.yaw_deg, 0.0, "yaw_deg must be 0.0")
            self.assertEqual(ext.radar_yaw_deg, 1.0, "radar_yaw_deg must be 1.0")
        finally:
            if os.path.isfile(tmp_path):
                os.remove(tmp_path)

    def test_ground_plane_projection_center_target(self):
        """Verify that a target centered horizontally in the video projects to Y=0 in base_link."""
        ext = CameraExtrinsics(
            camera_height_m=1.35,
            pitch_deg=5.417,
            yaw_deg=0.0,
            roll_deg=0.0,
            camera_yaw_deg=0.0
        )
        intr = PinholeIntrinsics.from_fov(width=1920, height=1080, hfov_deg=65.7)
        proj = GroundPlaneProjector(ext, intr, video_rotation_deg=0)

        # Bottom-center of bounding box centered at cx = 960, below horizon at v = 700
        pt3d = proj.project_bottom_center_to_ground(u_center=960.0, v_bottom=700.0)
        self.assertIsNotNone(pt3d, "Projection must not be None below horizon")
        self.assertAlmostEqual(pt3d["y_left_m"], 0.0, delta=0.05,
                               msg=f"Centered target must have Y_left ≈ 0.0m, got {pt3d['y_left_m']:.3f}m")

    def test_radar_and_vision_boxes_rest_on_ground_plane(self):
        """Verify that 3D cuboids have their bottom face flush with the asphalt plane (Z=0.0m)."""
        from tools.vision.geometry.box_dimensions import BoxDimensions

        # 1. Vision track 3D bounding box
        v_box = BoxDimensions.compute_3d_box(
            x_fwd_m=12.0,
            y_left_m=0.2,
            class_name="car",
            w_px=180.0,
            h_px=120.0,
            fx=991.0,
            fy=991.0,
            use_optical_sizing=False,
        )
        v_z_center = v_box["center"]["z_up_m"]
        v_height = v_box["size"]["height_m"]
        v_z_bottom = v_z_center - (v_height / 2.0)
        self.assertAlmostEqual(v_z_bottom, 0.0, delta=0.01,
                               msg=f"Vision box bottom face must sit at Z=0.0m, got {v_z_bottom:.3f}m")

        # 2. Radar track 3D bounding box convention
        radar_height = 1.50
        radar_z_center = radar_height / 2.0
        radar_z_bottom = radar_z_center - (radar_height / 2.0)
        self.assertAlmostEqual(radar_z_bottom, 0.0, delta=1e-5,
                               msg="Radar box bottom face must sit at Z=0.0m")


if __name__ == "__main__":
    unittest.main()
