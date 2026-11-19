"""Tests for Stage 2: tools/CAN_Processing/mf4_loader.py."""

from __future__ import annotations

import os
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np

from tools.CAN_Processing.mf4_loader import (
    ChunkHeaderInfo,
    _clean_signal_name,
    _normalize_column,
    inspect_mf4_header,
    load_can_session,
    parse_chunk_folder_and_seq,
)

REPO_ROOT = Path(__file__).resolve().parents[3]


def _build_synthetic_mf4_header(start_time_ns: int, finalized: bool = False) -> bytes:
    magic = b"MDF     " if finalized else b"UnFinMF "
    idblock = magic + b"\x00" * (64 - len(magic))
    link_count = 4
    hd_header = b"##HD" + b"\x00\x00\x00\x00" + struct.pack("<QQ", 104, link_count)
    links = b"\x00" * (link_count * 8)
    ts = struct.pack("<Q", start_time_ns)
    return idblock + hd_header + links + ts + b"\x00" * 32


class TestMf4Loader(unittest.TestCase):
    def test_parse_chunk_folder_and_seq(self) -> None:
        self.assertEqual(parse_chunk_folder_and_seq("00000120_00000004.MF4"), ("00000120", 4))
        self.assertEqual(parse_chunk_folder_and_seq("00000009.MF4"), ("", 9))
        self.assertEqual(parse_chunk_folder_and_seq("notes.txt"), ("", None))

    def test_inspect_mf4_header_reads_exact_hd_timestamp(self) -> None:
        expected_ns = 1_795_070_717_000_000_000
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "00000123_00000001.MF4"
            p.write_bytes(_build_synthetic_mf4_header(expected_ns, finalized=False))
            info = inspect_mf4_header(p)
            self.assertEqual(info.folder_id, "00000123")
            self.assertEqual(info.split_seq, 1)
            self.assertFalse(info.is_finalized)
            self.assertEqual(info.hd_start_time_ns, expected_ns)

    def test_clean_signal_and_normalize_column(self) -> None:
        self.assertEqual(_clean_signal_name("CAN1.MCU_Speed_Info.CRCST_174", "MCU_Speed_Info"), "CRCST_174")
        arr_bytes, ftype = _normalize_column(np.array([b"Drive_ECO", b"Idle"], dtype=object))
        self.assertEqual(ftype, "string")
        self.assertEqual(list(arr_bytes), ["Drive_ECO", "Idle"])

        arr_float, ftype_f = _normalize_column(np.array([1.5, float("nan")], dtype=np.float64))
        self.assertEqual(ftype_f, "double")
        self.assertEqual(list(arr_float), [1.5, 0.0])

    def test_missing_can_dir_returns_empty_result(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            res = load_can_session(tmp)
            self.assertFalse(res.has_data)
            self.assertEqual(res.chunks, [])
            self.assertEqual(res.messages, {})

    def test_real_session_all_vs_whitelist_non_destructive(self) -> None:
        sess = REPO_ROOT / "logs" / "session_20261116_143131"
        if not (sess / "can").is_dir():
            self.skipTest("Archived session logs/session_20261116_143131 not available")

        res_all = load_can_session(sess, can_mode="all")
        self.assertTrue(res_all.has_data)
        self.assertEqual(len(res_all.chunks), 11)
        self.assertEqual(res_all.continuity_warnings, [])
        self.assertIn("RADAR_Debug_Info", res_all.messages)
        self.assertIn("FrameNumber_Act", res_all.messages["RADAR_Debug_Info"].signals)
        self.assertGreater(res_all.messages["RADAR_Debug_Info"].row_count, 5000)
        # First chunk starts +1094s after HDBLOCK boot time -> time_from_zero=False must preserve real epoch
        r_dbg = res_all.messages["RADAR_Debug_Info"]
        self.assertGreater(float(r_dbg.raw_can_sec[0]), 1794819590.0)
        self.assertLess(float(r_dbg.raw_can_sec[-1]), 1794820270.0)

        res_wl = load_can_session(sess, can_mode="whitelist")
        self.assertLess(len(res_wl.messages), len(res_all.messages))
        self.assertIn("RADAR_Debug_Info", res_wl.messages)
        self.assertIn("VCU_Vehicle_Info1", res_wl.messages)

    def test_multi_boot_concat_preserves_power_cycle_timestamps(self) -> None:
        sess = REPO_ROOT / "logs" / "session_20261119_105126"
        if not (sess / "can").is_dir():
            self.skipTest("Archived session logs/session_20261119_105126 not available")
        res = load_can_session(sess, can_mode="whitelist")
        self.assertIn("VCU_Vehicle_Info1", res.messages)
        vcu = res.messages["VCU_Vehicle_Info1"]
        self.assertAlmostEqual(float(vcu.raw_can_sec[0]), 1795065600.085, delta=1.0)
        self.assertAlmostEqual(float(vcu.raw_can_sec[-1]), 1795065779.945, delta=1.0)

    def test_corrupt_chunk_is_quarantined_without_losing_valid_chunks(self) -> None:
        valid_src = REPO_ROOT / "logs" / "session_20261119_121554" / "can" / "00000123_00000001.MF4"
        if not valid_src.is_file():
            self.skipTest("Archived session logs/session_20261119_121554 not available")
        with tempfile.TemporaryDirectory() as tmp:
            can_dir = Path(tmp) / "can"
            can_dir.mkdir(parents=True)
            (can_dir / "00000123_00000001.MF4").write_bytes(valid_src.read_bytes())
            # Second chunk has valid HDBLOCK header followed by garbage body bytes
            (can_dir / "00000123_00000002.MF4").write_bytes(
                _build_synthetic_mf4_header(1_795_070_760_000_000_000) + b"\xff" * 256
            )
            res = load_can_session(tmp, can_mode="whitelist")
            self.assertTrue(res.has_data)
            self.assertEqual(len(res.chunks), 1)
            self.assertTrue(any("Quarantined" in w for w in res.continuity_warnings))


if __name__ == "__main__":
    unittest.main()
