"""
vehicle_orientation_tracker.py
------------------------------
Dedicated automotive orientation and ego-motion compensation module for monocular 3D perception:
  1. EgoMotionProvider: Ingests host vehicle speed and chassis yaw rate from CAN (/vehicle/telemetry)
     with O(log N) fast bisect interpolation (Zero dependency on radar).
  2. VehicleOrientationTracker:
     - Technique A: Converts camera-relative velocity into world coordinates using host vehicle CAN dynamics,
       completely eliminating lead-vehicle 180° closing-speed flips.
     - Technique B: Multi-frame motion state hysteresis (Moving >= 1.2 m/s vs Stopped <= 0.6 m/s) with
       zero-speed latching to prevent noise-driven spinning on stationary vehicles.
     - Technique C.1: Hard physical yaw-rate clamping (<= 0.8 rad/s / ~1.53°/frame at 30 FPS) with
       circular shortest-arc exponential smoothing (IIR).
     - Technique C.2: Road-parallel soft bimodal attractor (0° forward and 180° oncoming) for lane-keeping vehicles.
"""

import bisect
import csv
import json
import math
import os
from collections import deque
from typing import Dict, Any, Tuple, Optional, List


class EgoMotionProvider:
    """
    Provides fast O(log N) interpolated host vehicle dynamics (ground speed and yaw rate)
    derived directly from vehicle Powertrain CAN and chassis sensors (/vehicle/telemetry).
    Zero dependency on radar tracks.
    """

    def __init__(self, session_dir: str):
        self.session_dir = session_dir
        self.timestamps_ns: List[int] = []
        self.speeds_mps: List[float] = []
        self.yaw_rates_radps: List[float] = []
        self._load_telemetry()

    def _load_telemetry(self) -> None:
        """Loads telemetry from source MCAP or fallback GNSS CSV."""
        session_name = os.path.basename(os.path.normpath(self.session_dir))
        mcap_candidates = [
            os.path.join(self.session_dir, f"{session_name}.mcap"),
            os.path.join(self.session_dir, "session.mcap"),
        ]

        # 1. Primary: Load /vehicle/telemetry directly from source MCAP
        for mcap_path in mcap_candidates:
            if os.path.isfile(mcap_path):
                try:
                    from mcap.reader import make_reader
                    with open(mcap_path, "rb") as f:
                        reader = make_reader(f)
                        for schema, channel, message in reader.iter_messages(topics=["/vehicle/telemetry"]):
                            try:
                                d = json.loads(message.data.decode("utf-8"))
                                spd_kmph = float(d.get("speed_kmph", 0.0))
                                # Convert CAN SAE J670 yaw rate (+Right / clockwise)
                                # to ROS/ISO-8855 base_link frame (+Left / counter-clockwise, +Z Up)
                                yr_can = float(d.get("yaw_rate_radps", 0.0))
                                yr_base_link = -yr_can
                                self.timestamps_ns.append(message.log_time)
                                self.speeds_mps.append(spd_kmph / 3.6)
                                self.yaw_rates_radps.append(yr_base_link)
                            except Exception:
                                continue
                    if self.timestamps_ns:
                        self._smooth_yaw_rates()
                        return
                except Exception as e:
                    print(f"[EgoMotionProvider] Warning reading MCAP telemetry: {e}")

        # 2. Fallback: Load host speed from GNSS CSV (Zero Radar Dependency)
        gnss_csv = os.path.join(self.session_dir, "gnss", "gnss_fixes.csv")
        if os.path.isfile(gnss_csv):
            try:
                # Load time offset to align with master UTC nanoseconds
                time_offset_ns = 0
                meta_path = os.path.join(self.session_dir, "session_metadata.json")
                if os.path.isfile(meta_path):
                    with open(meta_path, "r", encoding="utf-8") as mf:
                        m_data = json.load(mf)
                        w_ms = int(m_data.get("startTimeWallMs", 0))
                        m_ns = int(m_data.get("startTimeMonotonicNs", 0))
                        if w_ms > 0 and m_ns > 0:
                            time_offset_ns = (w_ms * 1_000_000) - m_ns

                with open(gnss_csv, "r", encoding="utf-8") as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        raw_ns = int(row.get("elapsed_realtime_ns", 0))
                        spd = float(row.get("speed_mps", 0.0))
                        if raw_ns > 0:
                            utc_ts = raw_ns + time_offset_ns if raw_ns < 1e16 else raw_ns
                            self.timestamps_ns.append(utc_ts)
                            self.speeds_mps.append(spd)
                            self.yaw_rates_radps.append(0.0)
            except Exception as e:
                print(f"[EgoMotionProvider] Warning reading GNSS fixes: {e}")

    def _smooth_yaw_rates(self, window_size: int = 7) -> None:
        """
        Applies a zero-phase centered low-pass filter over raw CAN yaw rates.
        Attenuates quantization steps and high-frequency chassis vibration (>3 Hz)
        without introducing phase delay.
        """
        if len(self.yaw_rates_radps) < window_size:
            return

        weights = [1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0]
        half = len(weights) // 2

        smoothed = []
        n = len(self.yaw_rates_radps)
        for i in range(n):
            val = 0.0
            w_total = 0.0
            for j, w in enumerate(weights):
                idx = max(0, min(n - 1, i - half + j))
                val += w * self.yaw_rates_radps[idx]
                w_total += w
            smoothed.append(val / w_total)

        self.yaw_rates_radps = smoothed

    def get_ego_motion(self, timestamp_ns: int) -> Tuple[float, float]:
        """
        Interpolates host vehicle forward speed (m/s) and chassis yaw rate (rad/s)
        at the specified nanosecond timestamp via O(log N) binary search.

        Returns:
            Tuple of (speed_mps, yaw_rate_radps)
        """
        if not self.timestamps_ns:
            return 0.0, 0.0

        idx = bisect.bisect_left(self.timestamps_ns, timestamp_ns)
        if idx <= 0:
            return self.speeds_mps[0], self.yaw_rates_radps[0]
        if idx >= len(self.timestamps_ns):
            return self.speeds_mps[-1], self.yaw_rates_radps[-1]

        t0, t1 = self.timestamps_ns[idx - 1], self.timestamps_ns[idx]
        if t1 == t0:
            return self.speeds_mps[idx], self.yaw_rates_radps[idx]

        factor = (timestamp_ns - t0) / (t1 - t0)
        spd = self.speeds_mps[idx - 1] + factor * (self.speeds_mps[idx] - self.speeds_mps[idx - 1])
        yr = self.yaw_rates_radps[idx - 1] + factor * (self.yaw_rates_radps[idx] - self.yaw_rates_radps[idx - 1])
        return max(0.0, spd), yr


class VehicleOrientationTracker:
    """
    Stabilizes 3D vehicle orientation using CAN ego-motion compensation,
    kinematic turning model, motion state hysteresis, heading basin disambiguation,
    physical yaw-rate clamping, and road lane prior.
    """

    def __init__(
        self,
        min_history_frames: int = 3,
        max_history_frames: int = 12,              # ~0.4s temporal baseline for robust secant velocity
        min_time_span_s: float = 0.08,
        moving_speed_threshold_mps: float = 1.2,   # ~4.3 km/h to declare moving
        stopped_speed_threshold_mps: float = 0.6,  # ~2.2 km/h to declare stopped
        max_yaw_rate_rad_s: float = 1.0,           # ~57.3°/s physical steering bound
        smoothing_alpha: float = 0.25,             # Circular IIR filter factor
        lane_attractor_beta: float = 0.15,         # Soft road-lane bimodal alignment
        imu_yaw_deadband_radps: float = 0.015,     # legacy threshold
        imu_yaw_deadband_inner_radps: float = 0.010,  # ~0.57°/s noise floor below which yr is 0
        imu_yaw_deadband_outer_radps: float = 0.025,  # ~1.43°/s full transition threshold
    ):
        self.min_frames = min_history_frames
        self.max_frames = max_history_frames
        self.min_time_span_s = min_time_span_s
        self.moving_threshold = moving_speed_threshold_mps
        self.stopped_threshold = stopped_speed_threshold_mps
        self.max_yaw_rate = max_yaw_rate_rad_s
        self.alpha = smoothing_alpha
        self.beta = lane_attractor_beta
        self.imu_yaw_deadband = imu_yaw_deadband_radps
        self.deadband_inner = imu_yaw_deadband_inner_radps
        self.deadband_outer = imu_yaw_deadband_outer_radps

        # Tracking state dicts keyed by track_id
        self.history: Dict[int, deque] = {}                  # track_id -> deque of (t_ns, x_m, y_m, ego_spd)
        self.is_moving: Dict[int, bool] = {}                 # track_id -> bool
        self.consecutive_moving: Dict[int, int] = {}         # track_id -> count of frames >= moving_threshold
        self.consecutive_stopped: Dict[int, int] = {}        # track_id -> count of frames <= stopped_threshold
        self.smoothed_yaw: Dict[int, float] = {}             # track_id -> float (radians)
        self.latched_yaw: Dict[int, float] = {}              # track_id -> float (radians)
        self.last_seen_ns: Dict[int, int] = {}               # track_id -> int
        self.prev_timestamp_ns: Dict[int, int] = {}          # track_id -> int (previous frame timestamp)
        self.basin: Dict[int, Optional[str]] = {}            # track_id -> 'forward', 'oncoming', 'cross'
        self.basin_debounce: Dict[int, int] = {}             # track_id -> count of candidate frames

    def update(
        self,
        track_id: int,
        x_fwd_m: float,
        y_left_m: float,
        timestamp_ns: int,
        ego_speed_mps: float = 0.0,
        ego_yaw_rate_radps: float = 0.0,
    ) -> Dict[str, Any]:
        """
        Updates tracking history for an obstacle and computes stabilized heading and world speed.

        Args:
            track_id: Persistent track ID from ByteTrack.
            x_fwd_m: Camera ground contact X (+Forward in ISO base_link).
            y_left_m: Camera ground contact Y (+Left in ISO base_link).
            timestamp_ns: Frame timestamp in nanoseconds.
            ego_speed_mps: Host vehicle forward ground speed in m/s (from CAN /vehicle/telemetry).
            ego_yaw_rate_radps: Host vehicle chassis yaw rate in rad/s (from CAN /vehicle/telemetry).

        Returns:
            Dictionary with:
              - is_moving (bool)
              - speed_mps (float): True world speed in m/s.
              - yaw_rad (Optional[float]): Stabilized heading in radians [-pi, pi].
              - vx_mps (float): Target forward velocity in world frame.
              - vy_mps (float): Target lateral velocity in world frame.
        """
        if track_id is None or track_id <= 0:
            return {
                "is_moving": False,
                "speed_mps": 0.0,
                "yaw_rad": None,
                "vx_mps": 0.0,
                "vy_mps": 0.0,
            }

        # 1. Delta time and Soft-Knee IMU deadband / physical bound filtering
        prev_t = self.prev_timestamp_ns.get(track_id)
        frame_dt = (timestamp_ns - prev_t) / 1e9 if prev_t else 0.033
        self.prev_timestamp_ns[track_id] = timestamp_ns
        frame_dt = max(0.01, min(0.1, frame_dt))

        # Soft-knee deadband: smooth ramp between deadband_inner and deadband_outer
        # to eliminate abrupt step jumps when signal crosses deadband
        abs_yr = abs(ego_yaw_rate_radps)
        effective_inner = min(self.deadband_inner, self.imu_yaw_deadband)
        effective_outer = max(self.deadband_outer, self.imu_yaw_deadband * 1.5)

        if abs_yr <= effective_inner:
            filtered_ego_yaw_rate = 0.0
        elif abs_yr >= effective_outer:
            filtered_ego_yaw_rate = max(-1.0, min(1.0, ego_yaw_rate_radps))
        else:
            factor = (abs_yr - effective_inner) / (effective_outer - effective_inner)
            clamped_yr = max(-1.0, min(1.0, ego_yaw_rate_radps))
            filtered_ego_yaw_rate = factor * clamped_yr

        # 2. Kinematic Turning Model: Propagate existing headings in base_link
        # When host vehicle turns by Δθ_ego = ω_ego * Δt, external objects in base_link
        # rotate in the opposite direction by -Δθ_ego.
        ego_rot = filtered_ego_yaw_rate * frame_dt
        if track_id in self.smoothed_yaw:
            self.smoothed_yaw[track_id] = math.atan2(
                math.sin(self.smoothed_yaw[track_id] - ego_rot),
                math.cos(self.smoothed_yaw[track_id] - ego_rot),
            )
        if track_id in self.latched_yaw:
            self.latched_yaw[track_id] = math.atan2(
                math.sin(self.latched_yaw[track_id] - ego_rot),
                math.cos(self.latched_yaw[track_id] - ego_rot),
            )

        if track_id not in self.history:
            self.history[track_id] = deque(maxlen=self.max_frames)
            self.is_moving[track_id] = False
            self.consecutive_moving[track_id] = 0
            self.consecutive_stopped[track_id] = 0
            self.basin[track_id] = None
            self.basin_debounce[track_id] = 0

        hist = self.history[track_id]
        hist.append((timestamp_ns, float(x_fwd_m), float(y_left_m), float(ego_speed_mps), filtered_ego_yaw_rate))
        self.last_seen_ns[track_id] = timestamp_ns

        # Require minimum frames for robust finite difference
        if len(hist) < self.min_frames:
            return {
                "is_moving": self.is_moving.get(track_id, False),
                "speed_mps": 0.0,
                "yaw_rad": self.latched_yaw.get(track_id),
                "vx_mps": 0.0,
                "vy_mps": 0.0,
            }

        t_old, x_old, y_old, _, _ = hist[0]
        t_new, x_new, y_new, _, _ = hist[-1]
        dt_s = (t_new - t_old) / 1e9

        if dt_s < self.min_time_span_s:
            return {
                "is_moving": self.is_moving.get(track_id, False),
                "speed_mps": 0.0,
                "yaw_rad": self.latched_yaw.get(track_id),
                "vx_mps": 0.0,
                "vy_mps": 0.0,
            }

        # 3. Multi-frame secant velocity with window-averaged ego speed, yaw rate, and position
        avg_ego_spd = sum(item[3] for item in hist) / len(hist)
        avg_ego_yr = sum(item[4] for item in hist) / len(hist)
        avg_x = sum(item[1] for item in hist) / len(hist)
        avg_y = sum(item[2] for item in hist) / len(hist)

        vx_rel = (x_new - x_old) / dt_s
        vy_rel = (y_new - y_old) / dt_s

        # Ego-motion compensation into world frame
        vx_world = vx_rel + avg_ego_spd - (avg_ego_yr * avg_y)
        vy_world = vy_rel + (avg_ego_yr * avg_x)
        speed_world = math.hypot(vx_world, vy_world)

        # 4. Multi-Frame Motion State Hysteresis
        # An obstacle closing rapidly in camera frame (vx_rel < -1.5 m/s) is unequivocally moving
        is_approaching = (vx_rel < -1.5) and (x_new < x_old)
        was_moving = self.is_moving[track_id]
        if not was_moving:
            if speed_world >= self.moving_threshold or is_approaching:
                self.consecutive_moving[track_id] += 1
                self.consecutive_stopped[track_id] = 0
                if self.consecutive_moving[track_id] >= 3:
                    self.is_moving[track_id] = True
            else:
                self.consecutive_moving[track_id] = 0
        else:
            if speed_world <= self.stopped_threshold and not is_approaching:
                self.consecutive_stopped[track_id] += 1
                self.consecutive_moving[track_id] = 0
                if self.consecutive_stopped[track_id] >= 5:
                    self.is_moving[track_id] = False
            else:
                self.consecutive_stopped[track_id] = 0

        is_moving_now = self.is_moving[track_id]

        # 5. Heading Estimation, Basin Disambiguation & Decoupled Rate Clamping
        if is_moving_now:
            raw_yaw = math.atan2(vy_world, vx_world)

            # Heading Basin Hysteresis: Oncoming vs Forward
            is_oncoming_candidate = (vx_world < -0.5)
            curr_basin = self.basin.get(track_id)

            if curr_basin is None:
                curr_basin = "oncoming" if is_oncoming_candidate else "forward"
                self.basin[track_id] = curr_basin
                self.basin_debounce[track_id] = 0
                if is_oncoming_candidate:
                    smooth_yaw = raw_yaw if abs(raw_yaw) > math.radians(90.0) else (-math.pi if vy_world <= 0 else math.pi)
                else:
                    smooth_yaw = raw_yaw
            else:
                if is_oncoming_candidate:
                    if curr_basin != "oncoming":
                        self.basin_debounce[track_id] += 1
                        if self.basin_debounce[track_id] >= 4:
                            curr_basin = "oncoming"
                            self.basin[track_id] = curr_basin
                            self.basin_debounce[track_id] = 0
                    else:
                        self.basin_debounce[track_id] = 0
                else:
                    if curr_basin != "forward":
                        self.basin_debounce[track_id] += 1
                        if self.basin_debounce[track_id] >= 8:  # 8 frames of positive forward motion to exit oncoming
                            curr_basin = "forward"
                            self.basin[track_id] = curr_basin
                            self.basin_debounce[track_id] = 0
                    else:
                        self.basin_debounce[track_id] = 0

            # In oncoming basin, project raw_yaw into oncoming hemisphere (|yaw| > 90°) to reject 1-pixel pitch noise
            if curr_basin == "oncoming":
                if abs(raw_yaw) < math.radians(90.0):
                    raw_yaw = math.atan2(vy_world, -abs(vx_world) if abs(vx_world) > 0.5 else -1.0)

            if track_id in self.smoothed_yaw:
                prev_yaw = self.smoothed_yaw[track_id]
                diff = math.atan2(math.sin(raw_yaw - prev_yaw), math.cos(raw_yaw - prev_yaw))

                # Instant 180° mode flip (disambiguation):
                # If target is confirmed oncoming and heading is >110° away (trapped in forward basin),
                # snap directly across 180° rather than crawling at 0.38°/frame!
                if curr_basin == "oncoming" and abs(prev_yaw) < math.radians(70.0) and abs(diff) > math.radians(110.0):
                    smooth_yaw = raw_yaw
                else:
                    # Decoupled Rate Limiter:
                    # Small jitter (|diff| <= max_delta): smooth with IIR (alpha)
                    # Legitimate vehicle turn (|diff| > max_delta): slew at full physical rate max_delta
                    max_delta = self.max_yaw_rate * frame_dt
                    if abs(diff) <= max_delta:
                        step = self.alpha * diff
                    else:
                        step = math.copysign(max_delta, diff)
                    smooth_yaw = math.atan2(math.sin(prev_yaw + step), math.cos(prev_yaw + step))
            else:
                smooth_yaw = raw_yaw

            # Road Lane / Vanishing Point Bimodal Attractor
            # If target has low lateral velocity (< 0.35 m/s), attract toward road axis
            if abs(vy_world) < 0.35:
                # Forward lane-keeping attraction (around 0 rad)
                if abs(smooth_yaw) < math.radians(15.0):
                    smooth_yaw = (1.0 - self.beta) * smooth_yaw + self.beta * 0.0
                # Oncoming lane-keeping attraction (around +-pi rad)
                elif abs(abs(smooth_yaw) - math.pi) < math.radians(15.0):
                    target_pi = math.pi if smooth_yaw > 0 else -math.pi
                    smooth_yaw = (1.0 - self.beta) * smooth_yaw + self.beta * target_pi

            # Normalize to [-pi, pi]
            smooth_yaw = math.atan2(math.sin(smooth_yaw), math.cos(smooth_yaw))

            self.smoothed_yaw[track_id] = smooth_yaw
            self.latched_yaw[track_id] = smooth_yaw

            return {
                "is_moving": True,
                "speed_mps": round(speed_world, 2),
                "yaw_rad": round(smooth_yaw, 4),
                "vx_mps": round(vx_world, 2),
                "vy_mps": round(vy_world, 2),
            }

        # Stopped / Stationary State: Latch last known heading
        return {
            "is_moving": False,
            "speed_mps": round(speed_world, 2),
            "yaw_rad": self.latched_yaw.get(track_id),
            "vx_mps": round(vx_world, 2),
            "vy_mps": round(vy_world, 2),
        }

    def cleanup(self, current_ts_ns: int, max_idle_ns: int = 3_000_000_000):
        """Prunes stale tracks that have not updated in max_idle_ns (default 3s)."""
        stale_ids = [
            tid for tid, last_t in self.last_seen_ns.items()
            if (current_ts_ns - last_t) > max_idle_ns
        ]
        for tid in stale_ids:
            self.history.pop(tid, None)
            self.is_moving.pop(tid, None)
            self.consecutive_moving.pop(tid, None)
            self.consecutive_stopped.pop(tid, None)
            self.smoothed_yaw.pop(tid, None)
            self.latched_yaw.pop(tid, None)
            self.last_seen_ns.pop(tid, None)
            self.prev_timestamp_ns.pop(tid, None)
            self.basin.pop(tid, None)
            self.basin_debounce.pop(tid, None)

