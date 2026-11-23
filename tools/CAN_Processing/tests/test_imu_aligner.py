"""Tests for Tier-2 IMU Gyro-Yaw alignment (`tools/CAN_Processing/imu_aligner.py`)."""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

import numpy as np

from tools.CAN_Processing.completeness_auditor import _read_session_metadata
from tools.CAN_Processing.imu_aligner import (
    PhoneImuYawSeries,
    align_can_with_imu_tier2,
    extract_gravity_projected_yaw,
    fft_cross_correlate_lag,
)
from tools.CAN_Processing.mf4_loader import CanLoadResult, DecodedCanMessage, load_can_config, load_can_session
from tools.CAN_Processing.time_aligner import align_can_timeline

REPO_ROOT = Path(__file__).resolve().parents[3]


class TestImuAligner(unittest.TestCase):
    def test_synthetic_tilted_shaky_mount_with_drift_and_stale_metadata(self) -> None:
        # 20-min drive with true +0.75s offset, -150 ppm drift, tilted & vibrating mount,
        # and DELIBERATELY stale metadata clockOffsetMs (+58,000 ms) -> dual-anchor recovers +0.75s!
        dur_s = 1200.0
        t_utc_base = 1_795_154_000.0
        true_offset0 = 0.750
        true_drift_ppm = -150.0

        rng = np.random.default_rng(42)
        turns: list[tuple[float, float, float]] = []
        t_cur = 28.0
        while t_cur < dur_s - 30.0:
            amp = float(rng.choice([-1.0, 1.0]) * rng.uniform(0.28, 0.62))
            width = float(rng.uniform(1.6, 3.2))
            turns.append((t_cur, amp, width))
            t_cur += float(rng.uniform(24.0, 58.0))

        t_phone = np.arange(t_utc_base, t_utc_base + dur_s, 0.005)
        true_yaw_p = np.zeros_like(t_phone)
        for center, amp, width in turns:
            true_yaw_p += amp * np.exp(-0.5 * ((t_phone - (t_utc_base + center)) / width) ** 2)

        tilt_rad = np.deg2rad(20.0)
        g_vec = np.array([-9.81 * np.cos(tilt_rad), 0.0, 9.81 * np.sin(tilt_rad)])
        g_hat = g_vec / np.linalg.norm(g_vec)
        accel_xyz = g_vec + rng.normal(0.0, 2.5, size=(len(t_phone), 3))
        rattle = 0.8 * np.sin(2 * np.pi * 18.0 * (t_phone - t_utc_base))
        gyro_xyz = np.outer(true_yaw_p, g_hat) + rattle[:, None] * np.array([0.2, 0.9, 0.3])

        proj_yaw, est_g = extract_gravity_projected_yaw(accel_xyz, gyro_xyz)
        self.assertGreater(float(np.dot(est_g, g_hat)), 0.99)
        phone_series = PhoneImuYawSeries(utc_sec=t_phone, yaw_radps=proj_yaw, g_hat=est_g)

        t_can_utc = np.arange(t_utc_base - 5.0, t_utc_base + dur_s + 5.0, 0.01)
        can_yaw = np.zeros_like(t_can_utc)
        for center, amp, width in turns:
            can_yaw += -amp * np.exp(-0.5 * ((t_can_utc - (t_utc_base + center)) / width) ** 2)
        offsets = true_offset0 + (true_drift_ppm * 1e-6) * (t_can_utc - (t_utc_base + dur_s * 0.5))
        t_can_raw = t_can_utc - offsets

        can_msg = DecodedCanMessage(
            name="TEL_IMU_Z_Axis_Raw",
            can_id=0x100,
            raw_can_sec=t_can_raw,
            signals={"CNS_TEL_GYRO_Z_RAW": can_yaw * 1000.0},
            field_types={"CNS_TEL_GYRO_Z_RAW": "double"},
        )
        load_res = CanLoadResult(
            session_dir="synthetic",
            dbc_path="tools/VCU.dbc",
            ingest_mode="whitelist",
            messages={"TEL_IMU_Z_Axis_Raw": can_msg},
        )
        meta = {
            "startTimeWallMs": int(t_utc_base * 1000),
            "startTimeMonotonicNs": 1_000_000_000,
            "can": {"clockOffsetMs": 58000},
        }

        res = align_can_with_imu_tier2(load_res, session_meta=meta, phone_imu=phone_series)
        self.assertIsNotNone(res)
        assert res is not None
        self.assertEqual(res.primary_tier, "TIER2_IMU_GYRO")
        self.assertLess(abs(res.global_median_offset_sec - true_offset0) * 1000.0, 20.0)
        self.assertLess(abs(res.estimated_drift_ppm - true_drift_ppm), 10.0)

    def test_stationary_bench_capture_returns_none_so_tier3_handles_fallback(self) -> None:
        t = np.arange(1_795_000_000.0, 1_795_000_180.0, 0.01)
        rng = np.random.default_rng(7)
        phone_series = PhoneImuYawSeries(
            utc_sec=t,
            yaw_radps=rng.normal(0.0, 0.002, size=len(t)),
            g_hat=np.array([0.0, 0.0, 1.0]),
        )
        can_msg = DecodedCanMessage(
            name="VCU_IMU_Processed_Angular_Msg",
            can_id=0x200,
            raw_can_sec=t - 1.5,
            signals={"ETS_VCU_imuProc_yawRate": rng.normal(0.0, 0.002, size=len(t))},
            field_types={"ETS_VCU_imuProc_yawRate": "double"},
        )
        load_res = CanLoadResult(
            session_dir="bench",
            dbc_path="tools/VCU.dbc",
            ingest_mode="whitelist",
            messages={"VCU_IMU_Processed_Angular_Msg": can_msg},
        )
        meta = {"can": {"clockOffsetMs": 1500}}
        res = align_can_with_imu_tier2(load_res, session_meta=meta, phone_imu=phone_series)
        self.assertIsNone(res)

    def test_fft_cross_correlate_handles_large_lag(self) -> None:
        dt = 0.02
        t = np.arange(0.0, 400.0, dt)
        sig_ref = np.exp(-0.5 * ((t - 150.0) / 1.5) ** 2) - 0.8 * np.exp(-0.5 * ((t - 260.0) / 2.0) ** 2)
        t_can = t - 47.34
        sig_can = np.interp(t_can, t, sig_ref)
        lag_s, corr, snr = fft_cross_correlate_lag(sig_ref, sig_can, dt_s=dt, max_lag_s=80.0)
        self.assertGreater(corr, 0.99)
        self.assertGreater(snr, 8.0)
        self.assertAlmostEqual(lag_s, -47.34, delta=0.03)

    def test_real_no_radar_sessions_and_tier1_gt_cross_check(self) -> None:
        cfg = load_can_config()
        # 1. Radar+CAN capture with Tier 1 forced off -> Tier 2 must match Tier 1 GT (+0.6350s, -146.8 ppm)
        s_radar = REPO_ROOT / "logs" / "session_20261120_112453"
        if s_radar.is_dir():
            lr = load_can_session(s_radar, config=cfg, can_mode="whitelist")
            meta = _read_session_metadata(s_radar)
            res = align_can_timeline(lr, session_meta=meta, radar_anchors=(np.empty(0), np.empty(0, dtype=np.int64)))
            self.assertEqual(res.primary_tier, "TIER2_IMU_GYRO")
            self.assertLess(abs(res.global_median_offset_sec - 0.6350) * 1000.0, 40.0)
            self.assertLess(abs(res.estimated_drift_ppm - (-146.8)), 10.0)

        # 2. Genuine no-radar capture with stale +57.6s metadata -> Tier 2 recovers ~+0.6s cleanly
        s_noradar = REPO_ROOT / "logs" / "session_20261119_104518"
        if s_noradar.is_dir():
            lr2 = load_can_session(s_noradar, config=cfg, can_mode="whitelist")
            res2 = align_can_timeline(lr2)
            self.assertEqual(res2.primary_tier, "TIER2_IMU_GYRO")
            self.assertLess(abs(res2.global_median_offset_sec - 0.6), 0.3)

        # 3. Genuine short no-radar capture (119s) -> Tier 2 locks cleanly
        s_short = REPO_ROOT / "logs" / "session_20261119_105126"
        if s_short.is_dir():
            lr3 = load_can_session(s_short, config=cfg, can_mode="whitelist")
            res3 = align_can_timeline(lr3)
            self.assertEqual(res3.primary_tier, "TIER2_IMU_GYRO")

        # 4. Genuine stationary no-radar bench capture -> safely falls back to Tier 3
        s_bench = REPO_ROOT / "logs" / "session_20261119_121554"
        if s_bench.is_dir():
            lr4 = load_can_session(s_bench, config=cfg, can_mode="whitelist")
            res4 = align_can_timeline(lr4)
            self.assertEqual(res4.primary_tier, "TIER3_METADATA")


if __name__ == "__main__":
    unittest.main()
