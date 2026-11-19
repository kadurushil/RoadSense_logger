"""Tests for Stage 3: tools/CAN_Processing/completeness_auditor.py."""

from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np

from tools.CAN_Processing.completeness_auditor import (
    CanCompletenessError,
    audit_can_completeness,
)
from tools.CAN_Processing.mf4_loader import (
    CanLoadResult,
    ChunkHeaderInfo,
    DecodedCanMessage,
    load_can_session,
)

REPO_ROOT = Path(__file__).resolve().parents[3]


def _make_load_result(raw_ts: np.ndarray, warnings=None) -> CanLoadResult:
    return CanLoadResult(
        session_dir="/tmp/sess_test",
        dbc_path="tools/VCU.dbc",
        ingest_mode="all",
        chunks=[
            ChunkHeaderInfo(
                path="/tmp/sess_test/can/00000120_00000001.MF4",
                filename="00000120_00000001.MF4",
                size_bytes=1024,
                folder_id="00000120",
                split_seq=1,
                magic="UnFinMF ",
                is_finalized=False,
                hd_start_time_ns=int(raw_ts[0] * 1e9),
            )
        ],
        continuity_warnings=list(warnings or []),
        concat_hd_start_sec=float(raw_ts[0]),
        messages={
            "VCU_Vehicle_Info1": DecodedCanMessage(
                name="VCU_Vehicle_Info1",
                can_id=783,
                raw_can_sec=raw_ts,
                signals={"ETS_VCU_VehSpeed_Act_kmph": np.zeros_like(raw_ts)},
                field_types={"ETS_VCU_VehSpeed_Act_kmph": "double"},
            )
        },
    )


class TestCompletenessAuditor(unittest.TestCase):
    def test_complete_coverage_passes_strict(self) -> None:
        raw_ts = np.arange(1000.0, 1120.0, 0.1)
        lr = _make_load_result(raw_ts)
        meta = {"sessionId": "s_ok", "startTimeWallMs": 1_000_000, "stopTimeWallMs": 1_120_000}
        rep = audit_can_completeness(lr, session_meta=meta, aligned_span_utc_sec=(1000.0, 1120.0), strict_can=True)
        self.assertEqual(rep.status, "COMPLETE")
        self.assertEqual(rep.coverage_pct, 100.0)
        self.assertEqual(rep.warnings, [])

    def test_partial_tail_gap_warns_and_raises_under_strict(self) -> None:
        raw_ts = np.arange(1000.0, 1060.0, 0.1)
        lr = _make_load_result(raw_ts)
        meta = {"sessionId": "s_tail", "startTimeWallMs": 1_000_000, "stopTimeWallMs": 1_120_000}
        rep = audit_can_completeness(lr, session_meta=meta, aligned_span_utc_sec=(1000.0, 1060.0), strict_can=False)
        self.assertEqual(rep.status, "PARTIAL")
        self.assertAlmostEqual(rep.end_gap_sec or 0.0, 60.0, places=2)
        self.assertAlmostEqual(rep.coverage_pct, 50.0, places=1)
        with self.assertRaises(CanCompletenessError):
            audit_can_completeness(lr, session_meta=meta, aligned_span_utc_sec=(1000.0, 1060.0), strict_can=True)

    def test_empty_can_dir_returns_empty_status(self) -> None:
        lr = CanLoadResult(session_dir="/tmp/s_empty", dbc_path="tools/VCU.dbc", ingest_mode="all")
        meta = {"sessionId": "s_empty", "startTimeWallMs": 1_000_000, "stopTimeWallMs": 1_060_000}
        rep = audit_can_completeness(lr, session_meta=meta, strict_can=False)
        self.assertEqual(rep.status, "EMPTY")
        self.assertEqual(rep.coverage_pct, 0.0)

    def test_real_archived_partial_and_full_drives(self) -> None:
        s_partial = REPO_ROOT / "logs" / "session_20261113_101048"
        if (s_partial / "can").is_dir():
            lr_p = load_can_session(s_partial, can_mode="whitelist")
            rep_p = audit_can_completeness(lr_p, offset_estimate_sec=0.0)
            self.assertEqual(rep_p.status, "PARTIAL")
            self.assertLess(rep_p.coverage_pct, 30.0)

        s_full = REPO_ROOT / "logs" / "session_20261116_143131"
        if (s_full / "can").is_dir():
            lr_f = load_can_session(s_full, can_mode="whitelist")
            # Known empirical offset ~+30.94s on uncalibrated 143131
            rep_f = audit_can_completeness(lr_f, offset_estimate_sec=30.94)
            self.assertEqual(rep_f.status, "COMPLETE")
            self.assertEqual(rep_f.coverage_pct, 100.0)


if __name__ == "__main__":
    unittest.main()
