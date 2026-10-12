"""
camera_extrinsics.py
--------------------
Loads and manages 6-DOF camera extrinsic mounting parameters relative to the
vehicle ground plane and radar frame.
"""

import json
import math
import os
from dataclasses import dataclass
from typing import Dict, Any, Optional


@dataclass
class CameraExtrinsics:
    """
    Represents camera mounting position and orientation.
    
    Attributes:
        camera_height_m: Height of the camera lens above the asphalt surface (meters).
        pitch_deg: Downward pitch angle toward the asphalt (degrees, positive = downward).
        yaw_deg: Horizontal yaw angle (degrees, positive = clockwise / right).
        roll_deg: Roll angle around optical axis (degrees).
        setback_m: Longitudinal setback behind radar / bumper origin (meters).
        lateral_offset_m: Lateral offset from vehicle center line (meters, positive = right).
    """
    camera_height_m: float = 1.45
    pitch_deg: float = 4.143
    yaw_deg: float = -1.896
    roll_deg: float = 0.0
    setback_m: float = 0.30
    lateral_offset_m: float = 0.0

    @classmethod
    def from_calib_json(cls, calib_path: str) -> "CameraExtrinsics":
        """
        Parses camera extrinsics from RoadSense 'radar_camera_calib.json'.
        
        Args:
            calib_path: Path to radar_camera_calib.json.
            
        Returns:
            CameraExtrinsics instance with resolved camera height and angles.
        """
        if not os.path.isfile(calib_path):
            raise FileNotFoundError(f"Calibration file not found at: {calib_path}")

        with open(calib_path, "r", encoding="utf-8") as f:
            data: Dict[str, Any] = json.load(f)

        radar_height = float(data.get("radarHeightM", 0.95))
        height_offset = float(data.get("heightOffsetM", 0.50))
        cam_height = radar_height + height_offset

        pitch = float(data.get("pitchDeg", 4.143)) + float(data.get("nudgePitchDeg", 0.0))
        yaw = float(data.get("yawDeg", -1.896)) + float(data.get("nudgeYawDeg", 0.0))
        roll = float(data.get("rollDeg", 0.0))

        setback = float(data.get("setbackM", 0.30))
        lateral = float(data.get("lateralOffsetM", 0.0))

        return cls(
            camera_height_m=cam_height,
            pitch_deg=pitch,
            yaw_deg=yaw,
            roll_deg=roll,
            setback_m=setback,
            lateral_offset_m=lateral,
        )

    @property
    def pitch_rad(self) -> float:
        """Downward pitch in radians."""
        return math.radians(self.pitch_deg)

    @property
    def yaw_rad(self) -> float:
        """Horizontal yaw in radians."""
        return math.radians(self.yaw_deg)

    @property
    def roll_rad(self) -> float:
        """Roll in radians."""
        return math.radians(self.roll_deg)


if __name__ == "__main__":
    # Self-test: default extrinsics validation
    ext = CameraExtrinsics()
    print("[CameraExtrinsics Self-Test]")
    print(f"  Camera Height:  {ext.camera_height_m:.2f} m")
    print(f"  Pitch:          {ext.pitch_deg:.3f} deg ({ext.pitch_rad:.4f} rad)")
    print(f"  Yaw:            {ext.yaw_deg:.3f} deg ({ext.yaw_rad:.4f} rad)")
    print(f"  Setback:        {ext.setback_m:.2f} m")
    assert ext.camera_height_m > 0.5, "Camera height must be positive"
    print("  Status: PASSED")
