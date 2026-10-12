"""
box_dimensions.py
-----------------
Computes 3D bounding box dimensions for detected obstacles.
Supports:
  1. Option A (Class Priors): Standard automotive geometry (nuScenes/KITTI conventions).
  2. Option B (Camera-Derived Dimensions): Optical width and height calculated from
     bounding box pixel dimensions and solved metric distance.
"""

from dataclasses import dataclass
from typing import Dict, Any, Tuple


# Standard automotive priors (Length_fwd, Width_lat, Height_vert) in meters
CLASS_PRIORS: Dict[str, Tuple[float, float, float]] = {
    "car": (4.2, 1.8, 1.5),
    "truck": (8.5, 2.5, 3.2),
    "bus": (10.0, 2.6, 3.4),
    "motorcycle": (1.9, 0.8, 1.3),
    "bicycle": (1.6, 0.6, 1.2),
    "person": (0.6, 0.6, 1.7),
}
DEFAULT_PRIOR: Tuple[float, float, float] = (1.5, 1.5, 1.5)


@dataclass
class BoxDimensions:
    """Calculates 3D bounding box extent and center elevation."""

    @staticmethod
    def get_class_prior_dimensions(class_name: str) -> Tuple[float, float, float]:
        """
        Returns (length_m, width_m, height_m) based on standard automotive priors.
        """
        return CLASS_PRIORS.get(class_name.lower(), DEFAULT_PRIOR)

    @staticmethod
    def get_optical_dimensions(
        class_name: str,
        w_px: float,
        h_px: float,
        range_m: float,
        fx: float,
        fy: float,
    ) -> Tuple[float, float, float]:
        """
        Option B: Calculates physical width and height optically from pixel span,
        estimating length from vehicle aspect ratio or class priors.

        Args:
            class_name: Detected object class.
            w_px: 2D bounding box pixel width (x2 - x1).
            h_px: 2D bounding box pixel height (y2 - y1).
            range_m: Solved distance ahead in meters.
            fx: Horizontal focal length in pixels.
            fy: Vertical focal length in pixels.

        Returns:
            Tuple of (length_m, width_m, height_m).
        """
        if range_m <= 0.1:
            return CLASS_PRIORS.get(class_name.lower(), DEFAULT_PRIOR)

        # Optical width and height
        width_m = (w_px / fx) * range_m
        height_m = (h_px / fy) * range_m

        # Sanity clamps based on vehicle physical limits
        width_m = max(0.4, min(width_m, 4.0))
        height_m = max(0.5, min(height_m, 4.5))

        # Estimate longitudinal length
        cls = class_name.lower()
        if cls in ("car", "truck", "bus"):
            # Typical vehicle aspect ratio: Length is ~2.0 to 2.4x the width
            prior_len, _, _ = CLASS_PRIORS.get(cls, (4.2, 1.8, 1.5))
            length_m = max(1.5, min(width_m * 2.2, prior_len * 1.3))
        elif cls in ("motorcycle", "bicycle"):
            length_m = 1.8
        elif cls == "person":
            length_m = 0.6
        else:
            length_m = width_m

        return (round(length_m, 2), round(width_m, 2), round(height_m, 2))

    @classmethod
    def compute_3d_box(
        cls,
        x_fwd_m: float,
        y_left_m: float,
        class_name: str,
        w_px: float,
        h_px: float,
        fx: float,
        fy: float,
        use_optical_sizing: bool = True,
    ) -> Dict[str, Any]:
        """
        Assembles full 3D bounding box center and extent on the ground plane.
        Elevates the box center by height / 2 so the box sits ON the road.
        """
        if use_optical_sizing:
            length_m, width_m, height_m = cls.get_optical_dimensions(
                class_name, w_px, h_px, x_fwd_m, fx, fy
            )
        else:
            length_m, width_m, height_m = cls.get_class_prior_dimensions(class_name)

        # Center elevation sits at half the vehicle height above asphalt
        z_center_m = round(height_m / 2.0, 2)

        return {
            "center": {
                "x_fwd_m": round(x_fwd_m, 3),   # ISO frame: +X is forward
                "y_left_m": round(y_left_m, 3), # ISO frame: +Y is left
                "z_up_m": z_center_m,           # ISO frame: +Z is up
            },
            "size": {
                "length_m": length_m,
                "width_m": width_m,
                "height_m": height_m,
            },
            "label_z_m": round(height_m + 0.35, 2),
            "class_name": class_name,
        }


if __name__ == "__main__":
    print("[BoxDimensions Self-Test]")
    # Car detected at 15m with 180px width, 120px height in 720p (fx=947)
    box = BoxDimensions.compute_3d_box(
        x_lat_m=0.5,
        y_fwd_m=15.0,
        class_name="car",
        w_px=180.0,
        h_px=120.0,
        fx=947.0,
        fy=947.0,
        use_optical_sizing=True,
    )
    print(f"  Center: {box['center']}")
    print(f"  Size:   {box['size']}")
    assert box["center"]["z_up_m"] > 0, "Box center must be elevated above ground"
    print("  Status: PASSED")
