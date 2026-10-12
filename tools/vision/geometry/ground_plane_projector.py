"""
ground_plane_projector.py
-------------------------
Implements closed-form ray-to-ground intersection (Modality 1: Pure Geometry).
Transforms 2D bounding box tire-contact pixel coordinates (cx, y2) into 3D
metric ground coordinates (X_v, Y_v, Z_v) on the road surface (Z_v = 0).
"""

import math
from dataclasses import dataclass
from typing import Optional, Tuple, Dict, Any

try:
    from .camera_extrinsics import CameraExtrinsics
except ImportError:
    from camera_extrinsics import CameraExtrinsics


@dataclass
class PinholeIntrinsics:
    """Camera intrinsic parameters."""
    fx: float
    fy: float
    cx: float
    cy: float

    @classmethod
    def from_fov(cls, width: int, height: int, hfov_deg: float = 68.0) -> "PinholeIntrinsics":
        """Calculates focal lengths from horizontal FOV and image dimensions."""
        fx = (width / 2.0) / math.tan(math.radians(hfov_deg / 2.0))
        fy = fx  # Square pixel assumption
        cx = width / 2.0
        cy = height / 2.0
        return cls(fx=fx, fy=fy, cx=cx, cy=cy)


import numpy as np


class GroundPlaneProjector:
    """
    Projects 2D image coordinates to 3D vehicle coordinates matching RoadSense SpatialProjectionEngine.
    """

    def __init__(
        self,
        extrinsics: CameraExtrinsics,
        intrinsics: PinholeIntrinsics,
        video_rotation_deg: int = 180,
    ):
        self.extrinsics = extrinsics
        self.intrinsics = intrinsics
        self.video_rotation_deg = video_rotation_deg

        # Compute exact camera optical transformation matrix R matching convert_session_to_mcap.py
        pitch = math.radians(extrinsics.pitch_deg)
        yaw = math.radians(extrinsics.yaw_deg)
        roll = math.radians((extrinsics.roll_deg + video_rotation_deg) % 360.0)

        R0 = np.array([
            [ 0, -1,  0],
            [ 0,  0, -1],
            [ 1,  0,  0]
        ], dtype=float)

        Ry = np.array([
            [ math.cos(yaw), 0, -math.sin(yaw)],
            [ 0,             1,  0],
            [ math.sin(yaw), 0,  math.cos(yaw)]
        ], dtype=float)

        Rx = np.array([
            [ 1, 0,                0],
            [ 0, math.cos(pitch),  math.sin(pitch)],
            [ 0, -math.sin(pitch), math.cos(pitch)]
        ], dtype=float)

        Rz = np.array([
            [ math.cos(roll), math.sin(roll), 0],
            [-math.sin(roll), math.cos(roll), 0],
            [ 0,              0,              1]
        ], dtype=float)

        # R_total transforms base_link to camera_optical
        # Its transpose R_cam_to_base transforms camera_optical rays to base_link
        self.R_cam_to_base = (Rz @ Rx @ Ry @ R0).T
        self.cam_pos_base = np.array([
            -extrinsics.setback_m,
            -extrinsics.lateral_offset_m,
            extrinsics.camera_height_m
        ], dtype=float)

    def project_bottom_center_to_ground(
        self, u_center: float, v_bottom: float
    ) -> Optional[Dict[str, float]]:
        """
        Projects the bottom-center of a 2D bounding box (tire contact patch)
        onto the asphalt road plane (Z = 0) in base_link coordinates.

        Args:
            u_center: Horizontal center of the bounding box (pixels, in upright image).
            v_bottom: Vertical bottom edge of the bounding box (pixels, y2 in upright image).

        Returns:
            Dictionary with {x_fwd_m, y_left_m, z_elev_m, range_m, valid} or None if above horizon.
        """
        # In upright image (from auto-rotated 180° video), map to raw sensor coordinates
        if self.video_rotation_deg == 180:
            u_raw = 1280.0 - u_center
            v_raw = 720.0 - v_bottom
        else:
            u_raw = u_center
            v_raw = v_bottom

        # Optical ray direction in camera optical frame [X_c, Y_c, 1.0]
        ray_x = (u_raw - self.intrinsics.cx) / self.intrinsics.fx
        ray_y = (v_raw - self.intrinsics.cy) / self.intrinsics.fy
        ray_cam = np.array([ray_x, ray_y, 1.0], dtype=float)

        # Ray direction in base_link frame
        ray_base = self.R_cam_to_base @ ray_cam

        # Ray must point downward toward asphalt (ray_base[2] < 0)
        if ray_base[2] >= -1e-4:
            return None

        # Intersect with ground plane Z_base = 0: cam_pos_base[2] + s * ray_base[2] = 0
        s = -self.cam_pos_base[2] / ray_base[2]
        if s <= 0:
            return None

        p_ground = self.cam_pos_base + s * ray_base
        if p_ground[0] <= 0:  # Must be in front of the vehicle
            return None

        range_m = float(np.hypot(p_ground[0], p_ground[1]))
        return {
            "x_fwd_m": round(float(p_ground[0]), 3),
            "y_left_m": round(float(p_ground[1]), 3),
            "z_elev_m": 0.0,
            "range_m": round(range_m, 3),
            "valid": True,
        }


if __name__ == "__main__":
    # Self-test: 720p camera at standard mount parameters
    ext = CameraExtrinsics(camera_height_m=1.45, pitch_deg=4.143, yaw_deg=-1.896)
    intr = PinholeIntrinsics.from_fov(width=1280, height=720, hfov_deg=68.0)
    projector = GroundPlaneProjector(ext, intr)

    # Car tire contact patch at pixel (u=640, v=480)
    res = projector.project_bottom_center_to_ground(u_center=640.0, v_bottom=480.0)
    print("[GroundPlaneProjector Self-Test]")
    if res:
        print(f"  Pixel (640, 480) -> Forward Y: {res['y_fwd_m']}m, Lateral X: {res['x_lat_m']}m")
        assert res["y_fwd_m"] > 0, "Forward distance must be positive"
        print("  Status: PASSED")
    else:
        print("  Status: FAILED (Horizon reject)")
