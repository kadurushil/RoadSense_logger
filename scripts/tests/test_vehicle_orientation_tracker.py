"""
test_vehicle_orientation_tracker.py
-----------------------------------
Unit tests for VehicleOrientationTracker and EgoMotionProvider:
  1. Technique A: Ego-motion compensation prevents 180° heading flips during closing-speed situations.
  2. Technique B: Motion state hysteresis (Moving vs Stopped) and zero-speed heading latching.
  3. Technique C.1: Hard physical yaw-rate clamping (<= 0.8 rad/s / ~1.53°/frame at 30 FPS).
  4. Technique C.2: Road-parallel soft lane alignment attractor.
  5. EgoMotionProvider: Fast O(log N) bisect interpolation over telemetry timeline.
"""

import math
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.vision.geometry.vehicle_orientation_tracker import (
    VehicleOrientationTracker,
    EgoMotionProvider,
)


class TestVehicleOrientationTracker(unittest.TestCase):
    def setUp(self):
        self.tracker = VehicleOrientationTracker(
            min_history_frames=3,
            max_history_frames=8,
            min_time_span_s=0.05,
            moving_speed_threshold_mps=1.2,
            stopped_speed_threshold_mps=0.6,
            max_yaw_rate_rad_s=0.8,
            smoothing_alpha=0.25,
            lane_attractor_beta=0.15,
        )

    def test_technique_a_ego_motion_compensation_prevents_180deg_flip(self):
        """
        Tests that when host vehicle accelerates past the lead vehicle speed
        (causing camera-relative distance to decrease, i.e., closing in),
        ego-motion compensation maintains a true forward heading (~0 rad)
        and prevents the 180° front-to-rear flip.
        """
        track_id = 42
        dt_ns = 33_333_333  # ~30 FPS (33.33 ms)
        t_ns = 1_700_000_000_000_000_000

        # Phase 1: Ego moving at 10 m/s, Lead vehicle moving at 12 m/s forward
        # Lead vehicle is pulling away: relative speed = +2 m/s
        lead_x = 20.0
        lead_y = 0.2
        ego_speed = 10.0
        lead_world_speed = 12.0

        for i in range(10):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=lead_x,
                y_left_m=lead_y,
                timestamp_ns=t_ns,
                ego_speed_mps=ego_speed,
                ego_yaw_rate_radps=0.0,
            )
            # Advance time and positions
            t_ns += dt_ns
            rel_speed = lead_world_speed - ego_speed  # +2.0 m/s
            lead_x += rel_speed * (dt_ns / 1e9)

        self.assertTrue(res["is_moving"])
        self.assertAlmostEqual(res["speed_mps"], 12.0, delta=0.5)
        self.assertAlmostEqual(res["yaw_rad"], 0.0, delta=0.1)

        # Phase 2: Ego accelerates to 16 m/s, closing in on 12 m/s lead vehicle
        # Relative speed is now NEGATIVE (-4 m/s).
        # Without ego compensation, relative velocity points backward (180° flip).
        # With ego compensation, world velocity is -4 + 16 = +12 m/s forward!
        ego_speed = 16.0
        for i in range(15):
            rel_speed = lead_world_speed - ego_speed  # -4.0 m/s
            lead_x += rel_speed * (dt_ns / 1e9)
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=lead_x,
                y_left_m=lead_y,
                timestamp_ns=t_ns,
                ego_speed_mps=ego_speed,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns

        # Verify heading DID NOT FLIP to 180° (pi rad)
        self.assertTrue(res["is_moving"])
        self.assertAlmostEqual(res["speed_mps"], 12.0, delta=0.5)
        self.assertAlmostEqual(res["yaw_rad"], 0.0, delta=0.1,
                               msg=f"Expected yaw ~0 rad forward, got {res['yaw_rad']} rad. 180° flip occurred!")

    def test_technique_b_zero_speed_latching_and_hysteresis(self):
        """
        Tests multi-frame hysteresis state transitions and zero-speed heading latching.
        """
        track_id = 99
        dt_ns = 33_333_333  # 30 FPS
        t_ns = 1_700_000_000_000_000_000
        x = 15.0
        y = -1.0

        # Step 1: Vehicle driving at 5 m/s forward (declaring moving requires >= 3 frames)
        for i in range(6):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns
            x += 5.0 * (dt_ns / 1e9)

        self.assertTrue(res["is_moving"])
        initial_latched_yaw = res["yaw_rad"]
        self.assertIsNotNone(initial_latched_yaw)
        self.assertAlmostEqual(initial_latched_yaw, 0.0, delta=0.1)

        # Step 2: Vehicle comes to a stop (x position stops advancing)
        # The 8-frame sliding window gradually clears moving samples (7 frames to reach 0.0 m/s),
        # then 5 consecutive frames below stopped_threshold (0.6 m/s) transition state to stopped.
        # Advance 7 frames to clear the moving window
        for i in range(7):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns
            self.assertTrue(res["is_moving"])

        # Next 4 frames have speed == 0.0 m/s (< 0.6 m/s), debounce count reaches 4 (still moving)
        for i in range(4):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns
            self.assertTrue(res["is_moving"], f"Frame {i} should remain moving before 5 stopped debounce frames")

        # 5th stopped frame transitions state to stopped
        res = self.tracker.update(
            track_id=track_id,
            x_fwd_m=x,
            y_left_m=y,
            timestamp_ns=t_ns,
            ego_speed_mps=0.0,
            ego_yaw_rate_radps=0.0,
        )
        t_ns += dt_ns
        self.assertFalse(res["is_moving"])

        # Step 3: Stationary sensor noise (slight random lateral/depth jitter)
        # Must latch heading and NOT evaluate atan2(noise_y, noise_x)
        res = self.tracker.update(
            track_id=track_id,
            x_fwd_m=x + 0.02,
            y_left_m=y - 0.03,
            timestamp_ns=t_ns,
            ego_speed_mps=0.0,
            ego_yaw_rate_radps=0.0,
        )
        self.assertFalse(res["is_moving"])
        self.assertEqual(res["yaw_rad"], initial_latched_yaw)

    def test_technique_c1_physical_yaw_rate_clamping(self):
        """
        Tests that an abrupt 90° heading change between frames is clamped
        by the maximum physical vehicle yaw rate limit (0.8 rad/s).
        """
        track_id = 7
        dt_ns = 33_333_333  # 30 FPS (~0.0333 s)
        t_ns = 1_700_000_000_000_000_000
        x = 25.0
        y = 0.0

        # Initialize moving straight forward at 10 m/s
        for i in range(5):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns
            x += 10.0 * (dt_ns / 1e9)

        yaw_before = res["yaw_rad"]
        self.assertAlmostEqual(yaw_before, 0.0, delta=0.05)

        # Single frame with sharp lateral jump (simulating 90° turn or edge jitter)
        y += 10.0 * (dt_ns / 1e9)  # Sudden 45° - 90° direction shift
        res = self.tracker.update(
            track_id=track_id,
            x_fwd_m=x,
            y_left_m=y,
            timestamp_ns=t_ns,
            ego_speed_mps=0.0,
            ego_yaw_rate_radps=0.0,
        )
        yaw_after = res["yaw_rad"]

        # Delta must be strictly bounded by max_yaw_rate * dt
        # With max_yaw_rate=0.8 rad/s and dt ~ 0.033s, max angular step is ~0.027 rad (< 1.6°)
        frame_dt = dt_ns / 1e9
        max_allowed_delta = 0.8 * frame_dt + 1e-4
        actual_delta = abs(yaw_after - yaw_before)
        self.assertLessEqual(actual_delta, max_allowed_delta,
                             f"Angular step {math.degrees(actual_delta):.2f}° exceeded limit of {math.degrees(max_allowed_delta):.2f}°")

    def test_technique_c2_lane_attractor(self):
        """
        Tests that a vehicle traveling almost parallel to the road axis (small yaw angle < 15°)
        with low lateral velocity is softly attracted toward 0.0 rad.
        """
        track_id = 55
        dt_ns = 33_333_333
        t_ns = 1_700_000_000_000_000_000
        x = 20.0
        y = 0.0

        # Trajectory with a tiny 4° drift
        drift_angle = math.radians(4.0)
        speed = 10.0
        vx = speed * math.cos(drift_angle)
        vy = speed * math.sin(drift_angle)

        for i in range(10):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns
            x += vx * (dt_ns / 1e9)
            y += vy * (dt_ns / 1e9)

        # Due to lane attractor (beta=0.15), smoothed yaw should be pulled closer to 0 than raw drift_angle
        self.assertLess(abs(res["yaw_rad"]), drift_angle)


class TestEgoMotionProvider(unittest.TestCase):
    def test_bisect_interpolation(self):
        """
        Tests O(log N) bisect interpolation over timestamps.
        """
        provider = EgoMotionProvider.__new__(EgoMotionProvider)
        provider.session_dir = "mock"
        provider.timestamps_ns = [1000, 2000, 3000, 4000]
        provider.speeds_mps = [10.0, 20.0, 20.0, 30.0]
        provider.yaw_rates_radps = [0.0, 0.1, 0.1, -0.2]

        # 1. Before first timestamp
        spd, yr = provider.get_ego_motion(500)
        self.assertEqual(spd, 10.0)
        self.assertEqual(yr, 0.0)

        # 2. After last timestamp
        spd, yr = provider.get_ego_motion(5000)
        self.assertEqual(spd, 30.0)
        self.assertEqual(yr, -0.2)

        # 3. Exact timestamp match
        spd, yr = provider.get_ego_motion(2000)
        self.assertEqual(spd, 20.0)
        self.assertEqual(yr, 0.1)

        # 4. In-between midpoint interpolation
        spd, yr = provider.get_ego_motion(1500)
        self.assertAlmostEqual(spd, 15.0)
        self.assertAlmostEqual(yr, 0.05)


if __name__ == "__main__":
    unittest.main()
