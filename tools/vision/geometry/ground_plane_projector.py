"""
ground_plane_projector.py
-------------------------
Implements closed-form ray-to-ground intersection (Modality 1: Pure Geometry).
Transforms 2D bounding box tire-contact pixel coordinates (cx, y2) into 3D
metric ground coordinates (X_v, Y_v, Z_v) on the road surface (Z_v = 0).
"""

import json
import os
import math
from dataclasses import dataclass
from typing import Optional, Tuple, Dict, Any
import numpy as np

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
    width: int = 1920
    height: int = 1080

    @classmethod
    def from_fov(cls, width: int = 1920, height: int = 1080, hfov_deg: float = 65.7) -> "PinholeIntrinsics":
        """Calculates focal lengths from horizontal FOV and image dimensions."""
        fx = (width / 2.0) / math.tan(math.radians(hfov_deg / 2.0))
        fy = fx  # Square pixel assumption
        cx = width / 2.0
        cy = height / 2.0
        return cls(fx=fx, fy=fy, cx=cx, cy=cy, width=width, height=height)

    @classmethod
    def from_calib_json(cls, calib_path: str, width: int = 1920, height: int = 1080) -> "PinholeIntrinsics":
        """
        Loads calibrated camera intrinsics from radar_camera_calib.json.
        Scales intrinsics if target width/height differs from recording stream resolution.
        """
        if os.path.isfile(calib_path):
            try:
                with open(calib_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                ref_w = int(data.get("streamWidth", 1920))
                ref_h = int(data.get("streamHeight", 1080))
                hfov = float(data.get("hfovDeg", 65.7))
                default_fx = (ref_w / 2.0) / math.tan(math.radians(hfov / 2.0))
                raw_fx = float(data.get("fx", default_fx))
                if (ref_w == 1920 and raw_fx < 1100.0) or (ref_w == 1280 and raw_fx < 750.0):
                    base_fx = default_fx
                    base_fy = default_fx
                    base_cx = ref_w / 2.0
                    base_cy = ref_h / 2.0
                else:
                    base_fx = raw_fx
                    base_fy = float(data.get("fy", default_fx))
                    base_cx = float(data.get("cx", ref_w / 2.0))
                    base_cy = float(data.get("cy", ref_h / 2.0))

                scale_x = width / float(ref_w)
                scale_y = height / float(ref_h)
                return cls(
                    fx=base_fx * scale_x,
                    fy=base_fy * scale_y,
                    cx=base_cx * scale_x,
                    cy=base_cy * scale_y,
                    width=width,
                    height=height,
                )
            except Exception:
                pass
        return cls.from_fov(width=width, height=height, hfov_deg=65.7)


class GroundPlaneProjector:
    """
    Projects 2D image coordinates to 3D vehicle coordinates matching RoadSense SpatialProjectionEngine.
    """

    def __init__(
        self,
        extrinsics: CameraExtrinsics,
        intrinsics: PinholeIntrinsics,
        video_rotation_deg: int = 0,
    ):
        self.extrinsics = extrinsics
        self.intrinsics = intrinsics
        self.video_rotation_deg = video_rotation_deg

        # Compute camera optical transformation matrix R matching RoadSense SpatialProjectionEngine
        pitch = math.radians(extrinsics.pitch_deg)
        yaw = math.radians(getattr(extrinsics, "camera_yaw_deg", extrinsics.yaw_deg))
        roll = math.radians((extrinsics.roll_deg + video_rotation_deg) % 360.0)

        R0 = np.array([
            [ 0, -1,  0],
            [ 0,  0, -1],
            [ 1,  0,  0]
        ], dtype=float)

        # Yaw pan around camera Y-axis (down)
        Ry = np.array([
            [ math.cos(yaw), 0, -math.sin(yaw)],
            [ 0,             1,  0],
            [ math.sin(yaw), 0,  math.cos(yaw)]
        ], dtype=float)

        # Pitch tilt around camera X-axis (right: positive tilts downward toward asphalt)
        Rx = np.array([
            [ 1, 0,                0],
            [ 0, math.cos(pitch), -math.sin(pitch)],
            [ 0, math.sin(pitch),  math.cos(pitch)]
        ], dtype=float)

        # Roll around camera optical Z-axis (forward)
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
        # Map raw sensor coordinates if non-upright
        if self.video_rotation_deg == 180:
            u_raw = float(self.intrinsics.width) - u_center
            v_raw = float(self.intrinsics.height) - v_bottom
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
    # Self-test: 1080p camera at standard mount parameters (7.0° pitch down, 1.45m height)
    ext = CameraExtrinsics(camera_height_m=1.45, pitch_deg=7.0, yaw_deg=0.0)
    intr = PinholeIntrinsics.from_fov(width=1920, height=1080, hfov_deg=65.7)
    projector = GroundPlaneProjector(ext, intr, video_rotation_deg=0)

    # Car tire contact patch at pixel (u=960, v=800)
    res = projector.project_bottom_center_to_ground(u_center=960.0, v_bottom=800.0)
    print("[GroundPlaneProjector Self-Test]")
    if res:
        print(f"  Pixel (960, 800) -> Forward X: {res['x_fwd_m']}m, Left Y: {res['y_left_m']}m, Range: {res['range_m']}m")
        assert res["x_fwd_m"] > 0, "Forward distance must be positive"
        assert abs(res["y_left_m"]) < 0.1, "Centered pixel must have ~0 lateral offset"
        print("  Status: PASSED")
    else:
        print("  Status: FAILED (Horizon reject)")
