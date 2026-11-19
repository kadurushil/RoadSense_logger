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


if __name__ == "__main__":
    unittest.main()
