"""Tests for Stage 4: tools/CAN_Processing/time_aligner.py."""

from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np

from tools.CAN_Processing.mf4_loader import (
    CanLoadResult,
    ChunkHeaderInfo,
    DecodedCanMessage,
    load_can_session,
)
from tools.CAN_Processing.time_aligner import align_can_timeline, match_radar_frame_id_pairs

REPO_ROOT = Path(__file__).resolve().parents[3]


def _make_radar_debug_load_result(can_ts: np.ndarray, frame_ids: np.ndarray) -> CanLoadResult:
    return CanLoadResult(
        session_dir="/tmp/sess_align",
        dbc_path="tools/VCU.dbc",
        ingest_mode="all",
        chunks=[
            ChunkHeaderInfo(
                path="/tmp/sess_align/can/00000120_00000001.MF4",
                filename="00000120_00000001.MF4",
                size_bytes=1024,
                folder_id="00000120",
                split_seq=1,
                magic="UnFinMF ",
                is_finalized=False,
                hd_start_time_ns=int(can_ts[0] * 1e9),
            )
        ],
        concat_hd_start_sec=float(can_ts[0]),
        messages={
            "RADAR_Debug_Info": DecodedCanMessage(
                name="RADAR_Debug_Info",
                can_id=213,
                raw_can_sec=can_ts,
                signals={"FrameNumber_Act": frame_ids.astype(np.int64)},
                field_types={"FrameNumber_Act": "int64"},
            )
        },
    )


class TestTimeAligner(unittest.TestCase):
    def test_wraparound_drift_and_mid_drive_jump_recovery(self) -> None:
        # 20 Hz frames for 200 seconds crossing 16-bit wraparound 65530 -> 3993
        n = 4000
        dt = 0.05
        t_raw = 1_795_000_000.0 + np.arange(n) * dt
        ids = (65530 + np.arange(n)) & 0xFFFF

        # Simulate +31.0s initial offset, -460 ppm drift, and a mid-drive +0.5s jump at sample 2000
        true_offset = 31.0 - 460e-6 * (t_raw - t_raw[0])
        true_offset[2000:] += 0.500
        uart_utc = t_raw + true_offset

        lr = _make_radar_debug_load_result(t_raw, ids)
        res = align_can_timeline(
            lr,
            session_meta={"startTimeWallMs": int(uart_utc[0] * 1000), "startTimeMonotonicNs": 1},
            time_offset_ns=0,
            radar_anchors=(uart_utc, ids),
            window_sec=45.0,
            step_sec=30.0,
        )
        self.assertEqual(res.primary_tier, "TIER1_RADAR_FRAME_ID")
        self.assertGreaterEqual(res.total_matched_pairs, 3900)
        self.assertGreaterEqual(len(res.windows), 5)
        self.assertIsNotNone(res.residual_p95_p05_ms_before)
        self.assertIsNotNone(res.residual_p95_p05_ms_after)
        self.assertLess(res.residual_p95_p05_ms_after or 999.0, 200.0)
        self.assertLess(res.residual_p95_p05_ms_after or 999.0, (res.residual_p95_p05_ms_before or 999.0) * 0.5)
        # First vs last window tracks both drift and step jump
        self.assertAlmostEqual(res.windows[0].offset_sec, 31.0, delta=0.05)
        self.assertAlmostEqual(res.windows[-1].offset_sec, 31.41, delta=0.05)

    def test_tier3_fallback_without_radar(self) -> None:
        t_raw = np.arange(1_795_000_000.0, 1_795_000_060.0, 0.1)
        lr = _make_radar_debug_load_result(t_raw, np.arange(len(t_raw)))
        res = align_can_timeline(
            lr,
            session_meta={"startTimeWallMs": 1_795_000_000_000, "startTimeMonotonicNs": 1, "can": {"clockOffsetMs": 657}},
            time_offset_ns=0,
            radar_anchors=(np.empty(0), np.empty(0, dtype=np.int64)),
        )
        self.assertEqual(res.primary_tier, "TIER3_METADATA")
        self.assertAlmostEqual(res.global_median_offset_sec, 0.657, places=4)

    def test_real_radar_plus_can_drives_tier1_drift_correction(self) -> None:
        for sess_name, expected_med_s in [
            ("session_20261116_143131", 30.94),
            ("session_20261117_143826", 31.42),
        ]:
            sess = REPO_ROOT / "logs" / sess_name
            if not (sess / "can").is_dir() or not (sess / "radar" / "radar_frames.bin").is_file():
                continue
            lr = load_can_session(sess, can_mode="whitelist")
            res = align_can_timeline(lr, window_sec=45.0, step_sec=30.0)
            self.assertEqual(res.primary_tier, "TIER1_RADAR_FRAME_ID")
            self.assertGreater(res.total_matched_pairs, 3500)
            self.assertAlmostEqual(res.global_median_offset_sec, expected_med_s, delta=0.15)
            # Rolling piecewise windows must keep residual spread within 1 radar frame (< 50 ms)
            self.assertLess(res.residual_p95_p05_ms_after or 999.0, 50.0)


if __name__ == "__main__":
    unittest.main()
