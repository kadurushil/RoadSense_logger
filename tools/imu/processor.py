"""
RoadSense IMU Session Processor
===============================
High-level stream processor for high-rate IMU telemetry.
Coordinates:
  1. Reading raw CSV data from 'imu/imu_frames.csv'.
  2. Reading synchronized GNSS fixes from 'gnss/gnss_fixes.csv' (when available).
  3. Running 6-DOF mounting extrinsics spatial transformation via ImuFrameTransformer.
  4. Executing real-time GNSS-IMU Extended Kalman Filter via GnssImuHeadingFilter.
  5. Generating dual structured sample streams:
       - raw_sample: For '/imu/data' (raw phone frame + camera optical attitude)
       - vehicle_sample: For '/imu/vehicle' & '/imu/pose' (ISO 8855 FLU body frame, zero pitch/roll, drift-free heading)
"""

import csv
import math
import os
from typing import Dict, Generator, List, Optional, Tuple, Any

from .heading_filter import GnssImuHeadingFilter
from .transforms import ImuFrameTransformer


class ImuSessionProcessor:
    """
    Synchronous generator processing recorded IMU frames with optional GNSS course fusion.
    """

    def __init__(self, calib_params: Optional[Dict[str, Any]] = None):
        self.calib_params = calib_params or {}
        self.transformer = ImuFrameTransformer(self.calib_params)

    @staticmethod
    def load_gnss_fixes(gnss_csv_path: Optional[str]) -> List[Dict[str, Any]]:
        """Loads and parses gnss_fixes.csv into a list sorted by monotonic timestamp."""
        fixes = []
        if not gnss_csv_path or not os.path.isfile(gnss_csv_path):
            return fixes

        with open(gnss_csv_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    mono_ns = int(row["elapsed_realtime_ns"])
                    speed = float(row["speed_mps"]) if row.get("speed_mps") else 0.0
                    bearing = float(row["bearing_deg"]) if row.get("bearing_deg") else 0.0
                    accuracy = float(row["accuracy_m"]) if row.get("accuracy_m") else 0.0
                    provider = row.get("provider", "gps").strip().lower()
                    fixes.append({
                        "mono_ns": mono_ns,
                        "speed": speed,
                        "bearing": bearing,
                        "accuracy": accuracy,
                        "provider": provider
                    })
                except (ValueError, KeyError):
                    continue

        fixes.sort(key=lambda x: x["mono_ns"])
        return fixes

    def process_stream(
        self,
        imu_csv_path: str,
        gnss_csv_path: Optional[str] = None
    ) -> Generator[Tuple[Dict[str, Any], Dict[str, Any]], None, None]:
        """
        Processes imu_frames.csv yielding (raw_sample, vehicle_sample) for each frame.
        """
        if not os.path.isfile(imu_csv_path):
            return

        gnss_fixes = self.load_gnss_fixes(gnss_csv_path)

        # 1. Determine initial conditions from GNSS if available
        init_heading_deg = 0.0
        init_bias_deg_s = 0.0

        for fix in gnss_fixes:
            if fix["provider"] == "gps" and fix["speed"] > 1.8:
                init_heading_deg = fix["bearing"]
                break

        heading_filter = GnssImuHeadingFilter(
            init_heading_deg=init_heading_deg,
            init_bias_deg_s=init_bias_deg_s
        )

        gps_idx = 0
        total_gnss = len(gnss_fixes)
        last_mono_ns = None
        current_speed = 0.0
        is_stationary = True

        with open(imu_csv_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    mono_ns = int(row["elapsed_realtime_ns"])
                    wall_ms = int(row["wall_time_ms"]) if row.get("wall_time_ms") else None
                    ax = float(row["ax"])
                    ay = float(row["ay"])
                    az = float(row["az"])
                    gx = float(row["gx"])
                    gy = float(row["gy"])
                    gz = float(row["gz"])
                    qx = float(row["qx"])
                    qy = float(row["qy"])
                    qz = float(row["qz"])
                    qw = float(row["qw"])
                    lin_ax = float(row.get("lin_ax", 0.0))
                    lin_ay = float(row.get("lin_ay", 0.0))
                    lin_az = float(row.get("lin_az", 0.0))
                except (ValueError, KeyError):
                    continue

                # Compute time delta dt
                if last_mono_ns is not None:
                    dt = (mono_ns - last_mono_ns) / 1e9
                else:
                    dt = 0.01
                last_mono_ns = mono_ns

                # --- 1. Raw Phone Frame Attitude (for /imu/data) ---
                raw_roll_deg, raw_pitch_deg, raw_yaw_deg = self.transformer.euler_from_quaternion(qx, qy, qz, qw)

                raw_sample = {
                    "mono_ns": mono_ns,
                    "wall_ms": wall_ms,
                    "acceleration": {"x": ax, "y": ay, "z": az},
                    "angular_velocity": {"x": gx, "y": gy, "z": gz},
                    "linear_acceleration": {"x": lin_ax, "y": lin_ay, "z": lin_az},
                    "orientation": {"x": qx, "y": qy, "z": qz, "w": qw},
                    "ax": ax, "ay": ay, "az": az,
                    "gx": gx, "gy": gy, "gz": gz,
                    "lin_ax": lin_ax, "lin_ay": lin_ay, "lin_az": lin_az,
                    "qx": qx, "qy": qy, "qz": qz, "qw": qw,
                    "roll_deg": round(raw_roll_deg, 2),
                    "pitch_deg": round(raw_pitch_deg, 2),
                    "yaw_deg": round(raw_yaw_deg, 2)
                }

                # --- 2. Vehicle Body Frame Transformation (base_link FLU) ---
                v_ax, v_ay, v_az = self.transformer.transform_vector(ax, ay, az)
                v_gx, v_gy, v_gz = self.transformer.transform_vector(gx, gy, gz)
                v_lin_ax, v_lin_ay, v_lin_az = self.transformer.transform_vector(lin_ax, lin_ay, lin_az)

                # Compensated pitch and roll from phone orientation
                v_pitch_deg, v_roll_deg, _, _ = self.transformer.compute_vehicle_attitude_from_phone_quat(
                    qx, qy, qz, qw
                )

                # --- 3. Consume Pending GNSS Fixes for Heading EKF ---
                while gps_idx < total_gnss and gnss_fixes[gps_idx]["mono_ns"] <= mono_ns:
                    fix = gnss_fixes[gps_idx]
                    current_speed = fix["speed"]
                    if fix["provider"] == "gps":
                        if current_speed > 1.5:
                            heading_filter.update_gps(fix["bearing"], current_speed)
                            is_stationary = False
                        elif current_speed < 0.3:
                            is_stationary = True
                    gps_idx += 1

                # If no GNSS is available, infer stationary state from low IMU dynamics
                if total_gnss == 0:
                    lin_accel_mag = math.sqrt(lin_ax * lin_ax + lin_ay * lin_ay + lin_az * lin_az)
                    gyro_mag = math.sqrt(gx * gx + gy * gy + gz * gz)
                    is_stationary = (lin_accel_mag < 0.12 and gyro_mag < 0.05)

                # Execute ZUPT if stationary
                if is_stationary:
                    heading_filter.update_zupt(v_gz)

                # Execute High-Rate EKF Prediction
                heading_filter.predict(v_gz, dt)

                fused_heading_deg = heading_filter.get_heading_deg()

                # Synthesize normalized vehicle world quaternion from (v_roll, v_pitch, fused_heading)
                # ISO 8855 FLU: Yaw around Z, Pitch around Y, Roll around X
                vq_x, vq_y, vq_z, vq_w = self.transformer.quaternion_from_euler(
                    roll_rad=math.radians(v_roll_deg),
                    pitch_rad=math.radians(v_pitch_deg),
                    yaw_rad=math.radians(fused_heading_deg)
                )

                vehicle_sample = {
                    "mono_ns": mono_ns,
                    "wall_ms": wall_ms,
                    "acceleration": {"x": round(v_ax, 5), "y": round(v_ay, 5), "z": round(v_az, 5)},
                    "angular_velocity": {"x": round(v_gx, 5), "y": round(v_gy, 5), "z": round(v_gz, 5)},
                    "linear_acceleration": {"x": round(v_lin_ax, 5), "y": round(v_lin_ay, 5), "z": round(v_lin_az, 5)},
                    "orientation": {"x": round(vq_x, 5), "y": round(vq_y, 5), "z": round(vq_z, 5), "w": round(vq_w, 5)},
                    "ax": round(v_ax, 5),
                    "ay": round(v_ay, 5),
                    "az": round(v_az, 5),
                    "gx": round(v_gx, 5),
                    "gy": round(v_gy, 5),
                    "gz": round(v_gz, 5),
                    "lin_ax": round(v_lin_ax, 5),
                    "lin_ay": round(v_lin_ay, 5),
                    "lin_az": round(v_lin_az, 5),
                    "qx": round(vq_x, 5),
                    "qy": round(vq_y, 5),
                    "qz": round(vq_z, 5),
                    "qw": round(vq_w, 5),
                    "roll_deg": round(v_roll_deg, 2),
                    "pitch_deg": round(v_pitch_deg, 2),
                    "yaw_deg": round(fused_heading_deg, 2)
                }

                yield raw_sample, vehicle_sample

