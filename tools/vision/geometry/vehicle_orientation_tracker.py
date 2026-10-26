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
                                yr = float(d.get("yaw_rate_radps", 0.0))
                                self.timestamps_ns.append(message.log_time)
                                self.speeds_mps.append(spd_kmph / 3.6)
                                self.yaw_rates_radps.append(yr)
                            except Exception:
                                continue
                    if self.timestamps_ns:
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
    motion state hysteresis, physical yaw-rate clamping, and road lane prior.
    """

    def __init__(
        self,
        min_history_frames: int = 3,
        max_history_frames: int = 8,
        min_time_span_s: float = 0.08,
        moving_speed_threshold_mps: float = 1.2,   # ~4.3 km/h to declare moving
        stopped_speed_threshold_mps: float = 0.6,  # ~2.2 km/h to declare stopped
        max_yaw_rate_rad_s: float = 0.8,           # ~45.8°/s physical steering bound
        smoothing_alpha: float = 0.25,             # Circular IIR filter factor
        lane_attractor_beta: float = 0.15,         # Soft road-lane bimodal alignment
    ):
        self.min_frames = min_history_frames
        self.max_frames = max_history_frames
        self.min_time_span_s = min_time_span_s
        self.moving_threshold = moving_speed_threshold_mps
        self.stopped_threshold = stopped_speed_threshold_mps
        self.max_yaw_rate = max_yaw_rate_rad_s
        self.alpha = smoothing_alpha
        self.beta = lane_attractor_beta

        # Tracking state dicts keyed by track_id
        self.history: Dict[int, deque] = {}                  # track_id -> deque of (t_ns, x_m, y_m)
        self.is_moving: Dict[int, bool] = {}                 # track_id -> bool
        self.consecutive_moving: Dict[int, int] = {}         # track_id -> count of frames >= moving_threshold
        self.consecutive_stopped: Dict[int, int] = {}        # track_id -> count of frames <= stopped_threshold
        self.smoothed_yaw: Dict[int, float] = {}             # track_id -> float (radians)
        self.latched_yaw: Dict[int, float] = {}              # track_id -> float (radians)
        self.last_seen_ns: Dict[int, int] = {}               # track_id -> int

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

        if track_id not in self.history:
            self.history[track_id] = deque(maxlen=self.max_frames)
            self.is_moving[track_id] = False
            self.consecutive_moving[track_id] = 0
            self.consecutive_stopped[track_id] = 0

        hist = self.history[track_id]
        hist.append((timestamp_ns, float(x_fwd_m), float(y_left_m)))
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

        t_old, x_old, y_old = hist[0]
        t_new, x_new, y_new = hist[-1]
        dt_s = (t_new - t_old) / 1e9

        if dt_s < self.min_time_span_s:
            return {
                "is_moving": self.is_moving.get(track_id, False),
                "speed_mps": 0.0,
                "yaw_rad": self.latched_yaw.get(track_id),
                "vx_mps": 0.0,
                "vy_mps": 0.0,
            }

        # 1. Camera-relative finite difference velocity
        vx_rel = (x_new - x_old) / dt_s
        vy_rel = (y_new - y_old) / dt_s

        # 2. Technique A: Ego-Motion Compensation into World Frame
        # Host forward speed addition + chassis yaw rotation lever-arm tangential correction
        vx_world = vx_rel + ego_speed_mps - (ego_yaw_rate_radps * y_new)
        vy_world = vy_rel + (ego_yaw_rate_radps * x_new)
        speed_world = math.hypot(vx_world, vy_world)

        # 3. Technique B: Multi-Frame Motion State Hysteresis
        was_moving = self.is_moving[track_id]
        if not was_moving:
            if speed_world >= self.moving_threshold:
                self.consecutive_moving[track_id] += 1
                self.consecutive_stopped[track_id] = 0
                if self.consecutive_moving[track_id] >= 3:
                    self.is_moving[track_id] = True
            else:
                self.consecutive_moving[track_id] = 0
        else:
            if speed_world <= self.stopped_threshold:
                self.consecutive_stopped[track_id] += 1
                self.consecutive_moving[track_id] = 0
                if self.consecutive_stopped[track_id] >= 5:
                    self.is_moving[track_id] = False
            else:
                self.consecutive_stopped[track_id] = 0

        is_moving_now = self.is_moving[track_id]

        # 4. Heading Estimation & Stabilization
        if is_moving_now:
            raw_yaw = math.atan2(vy_world, vx_world)

            if track_id in self.smoothed_yaw:
                prev_yaw = self.smoothed_yaw[track_id]
                # Shortest circular arc difference [-pi, pi]
                diff = math.atan2(math.sin(raw_yaw - prev_yaw), math.cos(raw_yaw - prev_yaw))

                # Technique C.1: Hard Physical Yaw-Rate Clamping
                frame_dt = max(0.01, min(0.1, dt_s / max(len(hist) - 1, 1)))
                max_delta = self.max_yaw_rate * frame_dt
                clamped_diff = max(-max_delta, min(max_delta, diff))

                # Circular Low-Pass Filter (IIR)
                smooth_yaw = math.atan2(
                    math.sin(prev_yaw + self.alpha * clamped_diff),
                    math.cos(prev_yaw + self.alpha * clamped_diff),
                )
            else:
                smooth_yaw = raw_yaw

            # Technique C.2: Road Lane / Vanishing Point Bimodal Attractor
            # If target has low lateral velocity (< 0.35 m/s), attract toward road axis
            if abs(vy_world) < 0.35:
                # Forward lane-keeping attraction (around 0 rad)
                if abs(smooth_yaw) < math.radians(15.0):
                    smooth_yaw = (1.0 - self.beta) * smooth_yaw + self.beta * 0.0
                # Oncoming lane-keeping attraction (around +-pi rad)
                elif abs(smooth_yaw - math.pi) < math.radians(15.0):
                    smooth_yaw = math.atan2(
                        (1.0 - self.beta) * math.sin(smooth_yaw) + self.beta * 0.0,
                        (1.0 - self.beta) * math.cos(smooth_yaw) + self.beta * (-1.0),
                    )
                elif abs(smooth_yaw + math.pi) < math.radians(15.0):
                    smooth_yaw = math.atan2(
                        (1.0 - self.beta) * math.sin(smooth_yaw) + self.beta * 0.0,
                        (1.0 - self.beta) * math.cos(smooth_yaw) + self.beta * (-1.0),
                    )

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
