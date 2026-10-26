"""
kinematic_tracker.py
--------------------
Estimates vehicle ground velocity and heading yaw (ψ) from consecutive
tracked ground contact patches across video frames.

Purely monocular camera-based: Uses no radar signals to preserve independent
ground-truth validation integrity.
"""

import math
from collections import deque
from typing import Dict, Any, Tuple, Optional, List


class KinematicHeadingTracker:
    """
    Maintains a sliding temporal window of ground-plane positions per track ID
    and calculates instantaneous velocity and heading yaw.
    """

    def __init__(
        self,
        min_history_frames: int = 3,
        max_history_frames: int = 8,
        min_time_span_s: float = 0.08,
        moving_speed_threshold_mps: float = 0.6,  # ~2.2 km/h
        smoothing_alpha: float = 0.35,
    ):
        self.min_frames = min_history_frames
        self.max_frames = max_history_frames
        self.min_time_span_s = min_time_span_s
        self.moving_threshold = moving_speed_threshold_mps
        self.alpha = smoothing_alpha

        # track_id -> deque of (timestamp_ns, x_fwd_m, y_left_m)
        self.history: Dict[int, deque] = {}
        # track_id -> last smoothed yaw in radians
        self.smoothed_yaw: Dict[int, float] = {}
        # track_id -> last observed timestamp_ns
        self.last_seen_ns: Dict[int, int] = {}

    def update(
        self,
        track_id: int,
        x_fwd_m: float,
        y_left_m: float,
        timestamp_ns: int,
    ) -> Dict[str, Any]:
        """
        Updates tracking history for a track and calculates ground velocity and heading.

        Args:
            track_id: Persistent track ID from ByteTrack.
            x_fwd_m: Ground contact X (+Forward in ISO base_link).
            y_left_m: Ground contact Y (+Left in ISO base_link).
            timestamp_ns: Frame timestamp in nanoseconds.

        Returns:
            Dictionary with:
              - is_moving (bool)
              - speed_mps (float)
              - yaw_rad (Optional[float])
              - vx_mps (float)
              - vy_mps (float)
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

        hist = self.history[track_id]
        hist.append((timestamp_ns, float(x_fwd_m), float(y_left_m)))
        self.last_seen_ns[track_id] = timestamp_ns

        # Need at least min_frames to evaluate velocity
        if len(hist) < self.min_frames:
            return {
                "is_moving": False,
                "speed_mps": 0.0,
                "yaw_rad": None,
                "vx_mps": 0.0,
                "vy_mps": 0.0,
            }

        t_old, x_old, y_old = hist[0]
        t_new, x_new, y_new = hist[-1]
        dt_s = (t_new - t_old) / 1e9

        if dt_s < self.min_time_span_s:
            return {
                "is_moving": False,
                "speed_mps": 0.0,
                "yaw_rad": None,
                "vx_mps": 0.0,
                "vy_mps": 0.0,
            }

        # Finite difference velocity in ego vehicle frame
        vx = (x_new - x_old) / dt_s
        vy = (y_new - y_old) / dt_s
        speed = math.hypot(vx, vy)

        if speed >= self.moving_threshold:
            raw_yaw = math.atan2(vy, vx)

            # Circular exponential smoothing to prevent angle boundary flipping
            if track_id in self.smoothed_yaw:
                prev_yaw = self.smoothed_yaw[track_id]
                diff = (raw_yaw - prev_yaw + math.pi) % (2.0 * math.pi) - math.pi
                smooth_yaw = prev_yaw + self.alpha * diff
                # Normalize to [-pi, pi]
                smooth_yaw = (smooth_yaw + math.pi) % (2.0 * math.pi) - math.pi
            else:
                smooth_yaw = raw_yaw

            self.smoothed_yaw[track_id] = smooth_yaw

            return {
                "is_moving": True,
                "speed_mps": round(speed, 2),
                "yaw_rad": round(smooth_yaw, 4),
                "vx_mps": round(vx, 2),
                "vy_mps": round(vy, 2),
            }

        return {
            "is_moving": False,
            "speed_mps": round(speed, 2),
            "yaw_rad": None,
            "vx_mps": round(vx, 2),
            "vy_mps": round(vy, 2),
        }

    def cleanup(self, current_ts_ns: int, max_idle_ns: int = 3_000_000_000):
        """Prunes stale tracks that have not updated in max_idle_ns (default 3s)."""
        stale_ids = [
            tid for tid, last_t in self.last_seen_ns.items()
            if (current_ts_ns - last_t) > max_idle_ns
        ]
        for tid in stale_ids:
            self.history.pop(tid, None)
            self.smoothed_yaw.pop(tid, None)
            self.last_seen_ns.pop(tid, None)
