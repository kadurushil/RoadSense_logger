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

    def test_technique_turning_model_stationary_track(self):
        """
        Tests that when host vehicle turns right (clockwise, negative yaw rate),
        a stationary parked obstacle's heading in base_link frame rotates in the opposite
        direction (counter-clockwise, positive angle) to remain pinned to the road/curb.
        """
        track_id = 101
        dt_ns = 33_333_333  # 30 FPS (~0.0333s)
        dt_s = dt_ns / 1e9
        t_ns = 1_700_000_000_000_000_000
        x = 12.0
        y = 2.0

        # Latch initial parked heading at 0.0 rad (aligned with road)
        self.tracker.latched_yaw[track_id] = 0.0
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

        self.assertFalse(res["is_moving"])
        initial_latched_yaw = res["yaw_rad"]

        # Host car executes a right turn: yaw_rate = -0.2 rad/s (~ -11.5 deg/s)
        # As ego turns right, stationary target's relative position in base_link rotates counter-clockwise
        ego_yr = -0.2
        d_theta = ego_yr * dt_s
        num_frames = 15  # 0.5 seconds
        for i in range(num_frames):
            rot_angle = -d_theta
            x_new = x * math.cos(rot_angle) - y * math.sin(rot_angle)
            y_new = x * math.sin(rot_angle) + y * math.cos(rot_angle)
            x, y = x_new, y_new
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=ego_yr,
            )
            t_ns += dt_ns

        total_dt = num_frames * dt_s
        expected_rotation = - (ego_yr * total_dt)  # - (-0.2 * 0.5) = +0.1 rad
        self.assertFalse(res["is_moving"])
        self.assertAlmostEqual(res["yaw_rad"], initial_latched_yaw + expected_rotation, delta=0.01,
                               msg="Parked vehicle heading in base_link must rotate opposite to ego yaw rate")

    def test_technique_turning_model_imu_deadband(self):
        """
        Tests that high-frequency chassis/engine vibration below the IMU deadband (< 0.015 rad/s)
        does not cause stationary vehicle headings to drift.
        """
        track_id = 102
        dt_ns = 33_333_333
        t_ns = 1_700_000_000_000_000_000
        x = 10.0
        y = -1.5

        # Initialize stationary with road alignment
        self.tracker.latched_yaw[track_id] = 0.0
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

        # Apply tiny chassis vibration (+0.008 rad/s, below 0.015 rad/s deadband) at engine idle
        for i in range(20):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=0.0,
                ego_yaw_rate_radps=0.008,
            )
            t_ns += dt_ns

        # Latched yaw must remain strictly unchanged
        self.assertFalse(res["is_moving"])
        self.assertAlmostEqual(res["yaw_rad"], 0.0, delta=1e-4)

    def test_oncoming_vehicle_basin_disambiguation(self):
        """
        Tests Track 4991 case: An oncoming vehicle traveling towards us at 5.5 km/h (-1.5 m/s)
        with host car driving at 18 km/h (+5.0 m/s).
        Even if initialized near 0° due to entry jitter, the tracker must snap to the Oncoming
        basin (~180°) and NOT crawl diagonally across the circle.
        """
        track_id = 4991
        dt_ns = 33_333_333  # 30 FPS
        t_ns = 1_700_000_000_000_000_000
        ego_spd = 5.0
        target_world_spd = -1.5  # Oncoming at 1.5 m/s forward
        x = 35.0
        y = -1.0

        yaws = []
        # Run for 30 frames (~1.0s)
        for i in range(30):
            res = self.tracker.update(
                track_id=track_id,
                x_fwd_m=x,
                y_left_m=y,
                timestamp_ns=t_ns,
                ego_speed_mps=ego_spd,
                ego_yaw_rate_radps=0.0,
            )
            t_ns += dt_ns
            rel_speed = target_world_spd - ego_spd  # -6.5 m/s
            x += rel_speed * (dt_ns / 1e9)
            if res.get("yaw_rad") is not None:
                yaws.append(math.degrees(res["yaw_rad"]))

        # Must be classified moving
        self.assertTrue(res["is_moving"])
        # Final heading must face oncoming (~180° or -180°), NOT diagonal (e.g. -20° to -40°)
        final_yaw_deg = abs(yaws[-1])
        self.assertGreater(final_yaw_deg, 150.0,
                           f"Expected oncoming heading > 150°, got {final_yaw_deg:.1f}°. Diagonal failure occurred!")

    def test_decoupled_rate_clamping_allows_real_turns(self):
        """
        Tests that when a vehicle executes a legitimate 45° turn, the rate limiter
        allows rotation at the full physical rate limit (max_yaw_rate * dt),
        and is NOT throttled down by smoothing_alpha.
        """
        track_id = 205
        dt_ns = 33_333_333  # 30 FPS (~0.0333s)
        t_ns = 1_700_000_000_000_000_000
        x = 20.0
        y = 0.0

        # Initialize moving straight at 10 m/s
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

        yaw_start = res["yaw_rad"]

        # Sudden turn to 45° (vy = vx = 7.07 m/s)
        dt_s = dt_ns / 1e9
        res = self.tracker.update(
            track_id=track_id,
            x_fwd_m=x + 7.07 * dt_s,
            y_left_m=y + 7.07 * dt_s,
            timestamp_ns=t_ns,
            ego_speed_mps=0.0,
            ego_yaw_rate_radps=0.0,
        )
        yaw_next = res["yaw_rad"]

        # Delta must be close to max_delta = max_yaw_rate * dt
        # In the old double-dampening code, it would be alpha * max_delta = 0.25 * max_delta
        angular_step = abs(yaw_next - yaw_start)
        max_delta = self.tracker.max_yaw_rate * dt_s
        self.assertAlmostEqual(angular_step, max_delta, delta=0.005,
                               msg=f"Expected slew rate near full max_delta ({max_delta:.4f} rad), got {angular_step:.4f} rad")


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

    def test_ego_motion_provider_yaw_rate_smoothing(self):
        """
        Tests that zero-phase centered window smoothing attenuates high-frequency noise
        without introducing phase delay on turn peaks.
        """
        provider = EgoMotionProvider.__new__(EgoMotionProvider)
        provider.session_dir = "mock"
        provider.timestamps_ns = [i * 33_333_333 for i in range(25)]
        provider.speeds_mps = [10.0] * 25

        # Signal: clean pulse with single peak at index 8 + alternating high-frequency noise (+- 0.02 rad/s)
        base_signal = [0.0]*5 + [0.1, 0.2, 0.3, 0.4, 0.3, 0.2, 0.1] + [0.0]*13
        noise = [0.02 if i % 2 == 0 else -0.02 for i in range(25)]
        raw_noisy = [b + n for b, n in zip(base_signal, noise)]

        provider.yaw_rates_radps = list(raw_noisy)
        provider._smooth_yaw_rates(window_size=7)

        # 1. Attenuation of straight-line high-frequency noise variance
        straight_noise = [0.02 if i % 2 == 0 else -0.02 for i in range(25)]
        provider.yaw_rates_radps = list(straight_noise)
        provider._smooth_yaw_rates(window_size=7)
        raw_noise_var = sum(n**2 for n in straight_noise) / len(straight_noise)
        smooth_noise_var = sum(s**2 for s in provider.yaw_rates_radps) / len(provider.yaw_rates_radps)
        self.assertLess(smooth_noise_var, raw_noise_var * 0.1, "Smoothing filter must reduce noise variance by at least 90%")

        # 2. Peak must remain aligned (zero phase lag) on turn pulse
        provider.yaw_rates_radps = list(raw_noisy)
        provider._smooth_yaw_rates(window_size=7)
        peak_idx_raw = base_signal.index(max(base_signal))
        peak_idx_smooth = provider.yaw_rates_radps.index(max(provider.yaw_rates_radps))
        self.assertEqual(peak_idx_smooth, peak_idx_raw, "Zero-phase filter must preserve peak timing exactly")

    def test_soft_deadband_continuity(self):
        """
        Tests that soft-knee deadband in VehicleOrientationTracker eliminates
        step discontinuities as yaw rate crosses the deadband boundary.
        """
        tracker = VehicleOrientationTracker(
            imu_yaw_deadband_inner_radps=0.010,
            imu_yaw_deadband_outer_radps=0.025,
        )
        t_ns = 1_700_000_000_000_000_000
        dt_ns = 33_333_333

        # Sweep yaw rate across deadband from 0.00 to 0.035 rad/s in small steps
        yws = []
        for i in range(36):
            yr = i * 0.001  # 0.000 to 0.035
            # For a stationary track, yaw change per frame is -filtered_yr * frame_dt
            tracker.prev_timestamp_ns[999] = t_ns - dt_ns
            tracker.latched_yaw[999] = 0.0
            res = tracker.update(999, 10.0, 0.0, t_ns, 0.0, yr)
            # Extracted yaw rotation
            yws.append(res["yaw_rad"])
            t_ns += dt_ns

        # Verify no abrupt step jump: consecutive differences must be small and smooth
        diffs = [abs(yws[i+1] - yws[i]) for i in range(len(yws)-1)]
        max_jump = max(diffs)
        # Without soft-knee deadband, crossing 0.015 rad/s would cause a jump of 0.015 * 0.0333 ~ 0.0005 rad
        # With soft-knee ramp, max step per 0.001 yr increment is < 0.0001 rad
        self.assertLess(max_jump, 0.00015, f"Step jump {max_jump:.6f} exceeded smooth continuity bound")


if __name__ == "__main__":
    unittest.main()
