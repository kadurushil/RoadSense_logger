"""Tests for Stage 6: tools/CAN_Processing/pipeline.py."""

from __future__ import annotations

import io
import json
import time
import unittest
from pathlib import Path

import numpy as np

from mcap.reader import make_reader
from mcap.writer import CompressionType
from mcap_protobuf.decoder import DecoderFactory
from mcap_protobuf.writer import Writer as ProtobufWriter

from tools.CAN_Processing.completeness_auditor import CompletenessReport
from tools.CAN_Processing.mf4_loader import CanLoadResult, DecodedCanMessage
from tools.CAN_Processing.pipeline import CanSessionBundle, prepare_can_bundle, write_can_bundle_to_mcap
from tools.CAN_Processing.time_aligner import CanAlignmentResult

REPO_ROOT = Path(__file__).resolve().parents[3]


class TestPipeline(unittest.TestCase):
    def test_end_to_end_real_radar_plus_can_bundle_and_mcap(self) -> None:
        sess = REPO_ROOT / "logs" / "session_20261116_143131"
        if not (sess / "can").is_dir() or not (sess / "radar" / "radar_frames.bin").is_file():
            self.skipTest("Archived session logs/session_20261116_143131 not available")

        bundle = prepare_can_bundle(sess, can_mode="whitelist")
        self.assertEqual(bundle.alignment.primary_tier, "TIER1_RADAR_FRAME_ID")
        self.assertEqual(bundle.completeness.status, "COMPLETE")
        self.assertGreater(bundle.alignment.total_matched_pairs, 5000)

        counts = {}
        buf = io.BytesIO()
        writer = ProtobufWriter(buf, chunk_size=1024 * 1024, compression=CompressionType.ZSTD)
        t0 = time.time()
        written = write_can_bundle_to_mcap(
            writer,
            bundle,
            count_msg=lambda t: counts.__setitem__(t, counts.get(t, 0) + 1),
        )
        writer.finish()
        elapsed = time.time() - t0
        self.assertGreater(written, 100_000)
        self.assertIn("/can/VCU_Vehicle_Info1", counts)
        self.assertIn("/can/RADAR_Debug_Info", counts)
        self.assertIn("/can/sync_audit", counts)

        buf.seek(0)
        reader = make_reader(buf, decoder_factories=[DecoderFactory()])
        attachments = {att.name: json.loads(att.data.decode("utf-8")) for att in reader.iter_attachments()}
        self.assertIn("can_sync_audit.json", attachments)
        self.assertEqual(attachments["can_sync_audit.json"]["alignment"]["primary_tier"], "TIER1_RADAR_FRAME_ID")
        print(f"\n    [Benchmark whitelist 143131] written={written:,} msgs ({buf.getbuffer().nbytes/1e6:.2f} MB) in {elapsed:.2f}s")

    def test_overlapping_non_monotonic_timestamps_sorted_safely_in_mcap(self) -> None:
        raw_ts = np.array([1002.0, 1001.0, 1003.0], dtype=float)
        spd = np.array([20.0, 10.0, 30.0], dtype=float)
        lr = CanLoadResult(
            session_dir="/tmp/sess_sort",
            dbc_path="tools/VCU.dbc",
            ingest_mode="whitelist",
            chunks=[],
            concat_hd_start_sec=1001.0,
            messages={
                "VCU_Vehicle_Info1": DecodedCanMessage(
                    name="VCU_Vehicle_Info1",
                    can_id=783,
                    raw_can_sec=raw_ts,
                    signals={"ETS_VCU_VehSpeed_Act_kmph": spd},
                    field_types={"ETS_VCU_VehSpeed_Act_kmph": "double"},
                )
            },
        )
        # Need >=1 chunk so has_data is True
        from tools.CAN_Processing.mf4_loader import ChunkHeaderInfo
        lr.chunks.append(
            ChunkHeaderInfo("/tmp/0001.MF4", "0001.MF4", 1024, "0001", 1, "MDF     ", True, int(1001e9))
        )
        utc_ns = (raw_ts * 1e9).astype("int64")
        bundle = CanSessionBundle(
            session_dir="/tmp/sess_sort",
            load_result=lr,
            alignment=CanAlignmentResult("TIER3_METADATA", 45.0, 30.0, np.array([1002.0]), np.array([0.0])),
            completeness=CompletenessReport("s_sort", "COMPLETE", 1, 1024, 1, 3, 1001.0, 1003.0, 2.0, 1001.0, 1003.0, 2.0, 0.0, 0.0, 100.0),
            aligned_utc_ns_by_msg={"VCU_Vehicle_Info1": utc_ns},
            audit_dict={},
        )
        buf = io.BytesIO()
        writer = ProtobufWriter(buf, compression=CompressionType.ZSTD)
        write_can_bundle_to_mcap(writer, bundle)
        writer.finish()
        buf.seek(0)
        reader = make_reader(buf, decoder_factories=[DecoderFactory()])
        out_pairs = [(msg.log_time, dec.ETS_VCU_VehSpeed_Act_kmph) for _, ch, msg, dec in reader.iter_decoded_messages() if ch.topic == "/can/VCU_Vehicle_Info1"]
        self.assertEqual([p[1] for p in out_pairs], [10.0, 20.0, 30.0])
        self.assertTrue(out_pairs[0][0] <= out_pairs[1][0] <= out_pairs[2][0])

    def test_mcap_clips_excessive_pre_roll_and_post_roll_beyond_10s(self) -> None:
        from tools.CAN_Processing.mf4_loader import ChunkHeaderInfo

        # Drive runs [1000.0, 1050.0] while CAN chunk covers [910.0, 1130.0] (-90s lead, +80s tail)
        raw_ts = np.array([910.0, 985.0, 990.0, 1000.0, 1025.0, 1050.0, 1060.0, 1060.1, 1130.0], dtype=float)
        spd = np.arange(len(raw_ts), dtype=float)
        lr = CanLoadResult(
            session_dir="/tmp/sess_clip",
            dbc_path="tools/VCU.dbc",
            ingest_mode="whitelist",
            chunks=[ChunkHeaderInfo("/tmp/0001.MF4", "0001.MF4", 1024, "0001", 1, "MDF     ", True, int(910e9))],
            concat_hd_start_sec=910.0,
            messages={
                "VCU_Vehicle_Info1": DecodedCanMessage(
                    name="VCU_Vehicle_Info1",
                    can_id=783,
                    raw_can_sec=raw_ts,
                    signals={"ETS_VCU_VehSpeed_Act_kmph": spd},
                    field_types={"ETS_VCU_VehSpeed_Act_kmph": "double"},
                )
            },
        )
        utc_ns = np.round(raw_ts * 1_000_000_000.0).astype(np.int64)
        bundle = CanSessionBundle(
            session_dir="/tmp/sess_clip",
            load_result=lr,
            alignment=CanAlignmentResult("TIER2_IMU_GYRO", 240.0, 90.0, np.array([1025.0]), np.array([0.0])),
            completeness=CompletenessReport(
                "s_clip", "COMPLETE", 1, 1024, 1, len(raw_ts), 1000.0, 1050.0, 50.0, 910.0, 1130.0, 220.0, 0.0, 0.0, 100.0
            ),
            aligned_utc_ns_by_msg={"VCU_Vehicle_Info1": utc_ns},
            audit_dict={},
        )

        buf = io.BytesIO()
        writer = ProtobufWriter(buf, compression=CompressionType.ZSTD)
        write_can_bundle_to_mcap(writer, bundle)
        writer.finish()
        buf.seek(0)

        reader = make_reader(buf, decoder_factories=[DecoderFactory()])
        can_ts_s = [
            msg.log_time / 1e9
            for _, ch, msg, _ in reader.iter_decoded_messages()
            if ch.topic == "/can/VCU_Vehicle_Info1"
        ]
        # Kept strictly within [1000 - 10, 1050 + 10] = [990.0, 1060.0]
        self.assertEqual(can_ts_s, [990.0, 1000.0, 1025.0, 1050.0, 1060.0])
        summary_stats = reader.get_summary().statistics
        self.assertGreaterEqual(summary_stats.message_start_time / 1e9, 990.0)
        self.assertLessEqual(summary_stats.message_end_time / 1e9, 1060.0)

    def test_real_session_104518_clips_78s_pre_roll_to_10s(self) -> None:
        sess = REPO_ROOT / "logs" / "session_20261119_104518"
        if not (sess / "can").is_dir():
            self.skipTest("Archived session logs/session_20261119_104518 not available")

        bundle = prepare_can_bundle(sess, can_mode="whitelist")
        self.assertEqual(bundle.alignment.primary_tier, "TIER2_IMU_GYRO")
        buf = io.BytesIO()
        writer = ProtobufWriter(buf, chunk_size=1024 * 1024, compression=CompressionType.ZSTD)
        write_can_bundle_to_mcap(writer, bundle)
        writer.finish()
        buf.seek(0)
        reader = make_reader(buf)
        stats = reader.get_summary().statistics
        lead_before_drive_s = (stats.message_start_time / 1e9) - bundle.completeness.drive_start_utc_sec
        self.assertGreaterEqual(lead_before_drive_s, -10.05)
        self.assertLess(abs(lead_before_drive_s + 10.0), 0.5)


if __name__ == "__main__":
    unittest.main()
