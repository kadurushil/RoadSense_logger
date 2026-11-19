"""Tests for Stage 6: tools/CAN_Processing/pipeline.py."""

from __future__ import annotations

import io
import json
import time
import unittest
from pathlib import Path

from mcap.reader import make_reader
from mcap.writer import CompressionType
from mcap_protobuf.decoder import DecoderFactory
from mcap_protobuf.writer import Writer as ProtobufWriter

from tools.CAN_Processing.pipeline import prepare_can_bundle, write_can_bundle_to_mcap

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


if __name__ == "__main__":
    unittest.main()
