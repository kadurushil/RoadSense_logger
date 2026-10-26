"""
box_dimensions.py
-----------------
Computes 3D bounding box dimensions for detected obstacles.
Supports:
  1. Option A (Class Priors): Standard automotive geometry (nuScenes/KITTI conventions).
  2. Option B (Camera-Derived Dimensions): Optical width and height calculated from
     bounding box pixel dimensions and solved metric distance.
"""

import math
from dataclasses import dataclass
from typing import Dict, Any, Tuple, Optional


# Standard automotive priors (Length_fwd, Width_lat, Height_vert) in meters
CLASS_PRIORS: Dict[str, Tuple[float, float, float]] = {
    "car": (4.2, 1.8, 1.5),
    "truck": (8.5, 2.5, 3.2),
    "bus": (10.0, 2.6, 3.4),
    "motorcycle": (1.9, 0.8, 1.3),
    "bicycle": (1.6, 0.6, 1.2),
    "person": (0.6, 0.6, 1.7),
    # Compact & Indian vehicle priors
    "quadricycle": (2.75, 1.31, 1.65),  # Bajaj Qute
    "qute": (2.75, 1.31, 1.65),
    "autorickshaw": (2.62, 1.30, 1.71),
    "auto": (2.62, 1.30, 1.71),
}
DEFAULT_PRIOR: Tuple[float, float, float] = (1.5, 1.5, 1.5)


@dataclass
class BoxDimensions:
    """Calculates 3D bounding box extent, center elevation, and orientation."""

    @staticmethod
    def get_class_prior_dimensions(class_name: str) -> Tuple[float, float, float]:
        """
        Returns (length_m, width_m, height_m) based on standard automotive priors.
        """
        return CLASS_PRIORS.get(class_name.lower(), DEFAULT_PRIOR)

    @classmethod
    def resolve_effective_class(
        cls,
        class_name: str,
        span_m: float,
        height_m: float,
        range_m: float,
    ) -> str:
        """
        Guards against standard COCO YOLO misclassifications.
        For example, a Bajaj Qute or auto-rickshaw in front of the vehicle is often
        misclassified as a 'truck' or 'bus' due to its upright, boxy rear.
        If optical dimensions are compact, maps to quadricycle/car priors.
        """
        c = class_name.lower()
        if c in ("truck", "bus") and range_m < 30.0:
            if span_m < 1.9 and height_m < 2.2:
                if span_m < 1.45 and height_m > 1.40:
                    return "quadricycle"  # Bajaj Qute
                return "car"  # Compact car/hatchback
        return c

    @classmethod
    def get_optical_dimensions(
        cls,
        class_name: str,
        w_px: float,
        h_px: float,
        range_m: float,
        fx: float,
        fy: float,
        yaw_rad: Optional[float] = None,
    ) -> Tuple[float, float, float, str, bool]:
        """
        Option B: Calculates physical dimensions optically from pixel span,
        disambiguating vehicle width vs length based on aspect ratio and yaw angle.

        Returns:
            Tuple of (length_m, width_m, height_m, effective_class, is_broadside).
        """
        if range_m <= 0.1:
            prior_len, prior_w, prior_h = cls.get_class_prior_dimensions(class_name)
            return (prior_len, prior_w, prior_h, class_name.lower(), False)

        span_m = (w_px / fx) * range_m
        h_opt_m = (h_px / fy) * range_m

        eff_cls = cls.resolve_effective_class(class_name, span_m, h_opt_m, range_m)
        prior_len, prior_w, prior_h = cls.get_class_prior_dimensions(eff_cls)

        # Detect broadside aspect: When optical span across the image significantly exceeds
        # the vehicle's transverse width prior, the camera is viewing the side profile (length).
        is_broadside = False
        if eff_cls in ("motorcycle", "bicycle"):
            # Motorcycle width is ~0.8m, length is ~1.9m
            if span_m > 1.25:
                is_broadside = True
        elif eff_cls in ("car", "truck", "bus", "quadricycle", "qute", "autorickshaw", "auto"):
            # Car width is ~1.8m, length is ~4.2m. Broadside requires span approaching vehicle length (>2.7m).
            if span_m > max(prior_w * 1.5, 2.7):
                is_broadside = True

        # Sizing resolution
        is_side_aspect = is_broadside or (yaw_rad is not None and abs(math.sin(yaw_rad)) > abs(math.cos(yaw_rad)))
        if is_side_aspect:
            # Broadside/side view: span_m represents vehicle length
            length_m = max(prior_len * 0.75, min(span_m, prior_len * 1.35))
            width_m = prior_w
            height_m = max(0.5, min(h_opt_m, prior_h * 1.25))
        else:
            # Head-on / tail-on view: span_m represents vehicle width
            width_m = max(0.4, min(span_m, prior_w * 1.25))
            length_m = prior_len
            height_m = max(0.5, min(h_opt_m, prior_h * 1.25))

        return (
            round(length_m, 2),
            round(width_m, 2),
            round(height_m, 2),
            eff_cls,
            is_broadside,
        )

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
        x_contact_m: Optional[float] = None,
        yaw_rad: Optional[float] = None,
        is_moving: bool = False,
    ) -> Dict[str, Any]:
        """
        Assembles full 3D bounding box center, extent, and rotation quaternion on the ground plane.

        Coordinate Alignment & Orientation:
        1. x_contact_m represents the nearest ground contact surface of the vehicle.
        2. Heading yaw (ψ) is determined by kinematic velocity for moving vehicles,
           or by broadside aspect ratio disambiguation for stationary/parked vehicles.
        3. The depth offset from the near face to volumetric center is:
             d_depth = (length / 2) * |cos(ψ)| + (width / 2) * |sin(ψ)|
        4. Orientation quaternion q = [0, 0, sin(ψ/2), cos(ψ/2)] aligns the 3D cuboid
           directly with the vehicle's direction of travel in Foxglove 3D scenes.
        """
        contact_x = x_contact_m if x_contact_m is not None else x_fwd_m

        if use_optical_sizing:
            length_m, width_m, height_m, eff_cls, is_broadside = cls.get_optical_dimensions(
                class_name, w_px, h_px, contact_x, fx, fy, yaw_rad=yaw_rad
            )
        else:
            span_m = (w_px / fx) * contact_x if fx > 0 else 1.5
            h_opt_m = (h_px / fy) * contact_x if fy > 0 else 1.5
            eff_cls = cls.resolve_effective_class(class_name, span_m, h_opt_m, contact_x)
            length_m, width_m, height_m = cls.get_class_prior_dimensions(eff_cls)
            is_broadside = span_m > (width_m * 1.35)

        # Resolve final heading yaw (ψ in radians, [-pi, pi])
        if yaw_rad is not None:
            final_yaw = float(yaw_rad)
        elif is_broadside:
            # Parked or broadside vehicle on side of road: align along road curb (±90°)
            final_yaw = (math.pi / 2.0) if y_left_m >= 0.0 else (-math.pi / 2.0)
        else:
            # Head-on or tail-on vehicle: align with ego forward (0°)
            final_yaw = 0.0

        # Continuous depth to volumetric center from nearest visible contact surface
        d_depth = (length_m / 2.0) * abs(math.cos(final_yaw)) + (width_m / 2.0) * abs(math.sin(final_yaw))
        x_center_m = round(contact_x + d_depth, 3)
        y_center_m = round(y_left_m, 3)
        z_center_m = round(height_m / 2.0, 2)

        # Planar yaw rotation quaternion around vertical Z-axis [0, 0, sin(ψ/2), cos(ψ/2)]
        qz = math.sin(final_yaw / 2.0)
        qw = math.cos(final_yaw / 2.0)

        return {
            "center": {
                "x_fwd_m": x_center_m,           # ISO frame: +X is forward (Volumetric center)
                "y_left_m": y_center_m,          # ISO frame: +Y is left
                "z_up_m": z_center_m,           # ISO frame: +Z is up
            },
            "orientation": {
                "x": 0.0,
                "y": 0.0,
                "z": round(qz, 4),
                "w": round(qw, 4),
            },
            "yaw_rad": round(final_yaw, 4),
            "yaw_deg": round(math.degrees(final_yaw), 1),
            "contact_patch": {
                "x_fwd_m": round(contact_x, 3), # Tire contact patch on road surface
                "y_left_m": y_center_m,
                "z_up_m": 0.0,
            },
            "size": {
                "length_m": length_m,
                "width_m": width_m,
                "height_m": height_m,
            },
            "effective_class": eff_cls,
            "is_broadside": is_broadside,
            "label_z_m": round(height_m + 0.35, 2),
            "class_name": class_name,
        }


if __name__ == "__main__":
    print("[BoxDimensions Self-Test]")
    # 1. Car head-on at 15m (1.8m width corresponds to ~114px at 15m with fx=947)
    box_car = BoxDimensions.compute_3d_box(
        x_fwd_m=15.0,
        y_left_m=0.5,
        class_name="car",
        w_px=114.0,
        h_px=95.0,
        fx=947.0,
        fy=947.0,
        use_optical_sizing=True,
    )
    print(f"  Car Head-On: Center={box_car['center']}, Yaw={box_car['yaw_deg']}°, Quat={box_car['orientation']}")
    assert abs(box_car["center"]["x_fwd_m"] - (15.0 + box_car["size"]["length_m"] / 2.0)) < 1e-3

    # 2. Parked broadside motorcycle on left curb (like #3513)
    box_moto = BoxDimensions.compute_3d_box(
        x_fwd_m=12.6,
        y_left_m=2.5,
        class_name="motorcycle",
        w_px=150.0,
        h_px=98.0,
        fx=947.0,
        fy=947.0,
        use_optical_sizing=True,
    )
    print(f"  Parked Moto: Size={box_moto['size']}, Yaw={box_moto['yaw_deg']}°, Quat={box_moto['orientation']}")
    assert box_moto["is_broadside"] is True
    assert box_moto["size"]["width_m"] == 0.8, "Broadside motorcycle width must be handlebar width (0.8m)"
    assert box_moto["yaw_deg"] == 90.0, "Broadside motorcycle on left curb must orient at 90°"

    # 3. Compact vehicle sanity guard (Bajaj Qute misclassified as truck at 2.7m)
    box_qute = BoxDimensions.compute_3d_box(
        x_fwd_m=2.7,
        y_left_m=0.1,
        class_name="truck",
        w_px=450.0,
        h_px=550.0,
        fx=947.0,
        fy=947.0,
        use_optical_sizing=True,
    )
    print(f"  Qute Guard: EffectiveClass={box_qute['effective_class']}, Size={box_qute['size']}")
    assert box_qute["effective_class"] in ("quadricycle", "car"), "Compact truck detection must resolve to compact prior"
    assert box_qute["size"]["length_m"] < 4.5, "Qute length must be compact, not 8.5m truck"

    print("  Status: ALL PASSED")

