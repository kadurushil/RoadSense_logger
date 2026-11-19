"""Tests for Stage 5: tools/CAN_Processing/proto_builder.py."""

from __future__ import annotations

import io
import unittest
from pathlib import Path

import numpy as np
from mcap.reader import make_reader
from mcap.writer import CompressionType
from mcap_protobuf.decoder import DecoderFactory
from mcap_protobuf.writer import Writer as ProtobufWriter

from tools.CAN_Processing.mf4_loader import DecodedCanMessage, load_can_session
from tools.CAN_Processing.proto_builder import build_can_protobuf_classes

REPO_ROOT = Path(__file__).resolve().parents[3]


class TestProtoBuilder(unittest.TestCase):
    def test_mixed_types_and_mcap_round_trip(self) -> None:
        msgs = {
            "VCU_Vehicle_Info1": DecodedCanMessage(
                name="VCU_Vehicle_Info1",
                can_id=783,
                raw_can_sec=np.array([1000.0, 1000.1]),
                signals={
                    "ETS_VCU_VehSpeed_Act_kmph": np.array([0.0, 32.5], dtype=np.float64),
                    "ETS_VCU_VehStateMachineID_Act_ID": np.array(["Idle", "Drive_ECO"], dtype=object),
                },
                field_types={
                    "ETS_VCU_VehSpeed_Act_kmph": "double",
                    "ETS_VCU_VehStateMachineID_Act_ID": "string",
                },
            ),
            "RADAR_Debug_Info": DecodedCanMessage(
                name="RADAR_Debug_Info",
                can_id=213,
                raw_can_sec=np.array([1000.0]),
                signals={"FrameNumber_Act": np.array([1234], dtype=np.int64)},
                field_types={"FrameNumber_Act": "int64"},
            ),
        }

        # Calling twice verifies isolated DescriptorPool (no duplicate-proto errors)
        for _ in range(2):
            cls_map, audit_cls = build_can_protobuf_classes(msgs)

        buf = io.BytesIO()
        writer = ProtobufWriter(buf, compression=CompressionType.ZSTD)
        vcu_msg = cls_map["VCU_Vehicle_Info1"](
            ETS_VCU_VehSpeed_Act_kmph=32.5,
            ETS_VCU_VehStateMachineID_Act_ID="Drive_ECO",
            raw_can_ts_ns=1_000_100_000_000,
            applied_offset_ms=657.0,
        )
        writer.write_message("/can/VCU_Vehicle_Info1", vcu_msg, log_time=1_000_757_000_000)

        audit_msg = audit_cls(
            primary_tier="TIER1_RADAR_FRAME_ID",
            completeness_status="COMPLETE",
            coverage_pct=100.0,
            global_median_offset_ms=657.0,
            estimated_drift_ppm=-451.0,
            residual_before_ms=220.0,
            residual_after_ms=38.9,
            matched_pairs=5957,
        )
        writer.write_message("/can/sync_audit", audit_msg, log_time=1_000_757_000_000)
        writer.finish()

        buf.seek(0)
        reader = make_reader(buf, decoder_factories=[DecoderFactory()])
        topics = {}
        for _, ch, _, dec in reader.iter_decoded_messages():
            topics[ch.topic] = dec
        self.assertEqual(topics["/can/VCU_Vehicle_Info1"].ETS_VCU_VehSpeed_Act_kmph, 32.5)
        self.assertEqual(topics["/can/VCU_Vehicle_Info1"].ETS_VCU_VehStateMachineID_Act_ID, "Drive_ECO")
        self.assertEqual(topics["/can/sync_audit"].matched_pairs, 5957)

    def test_build_all_real_dbc_messages_in_session(self) -> None:
        sess = REPO_ROOT / "logs" / "session_20261116_143131"
        if not (sess / "can").is_dir():
            self.skipTest("Archived session logs/session_20261116_143131 not available")
        lr = load_can_session(sess, can_mode="all")
        cls_map, _ = build_can_protobuf_classes(lr.messages)
        self.assertEqual(len(cls_map), len(lr.messages))


if __name__ == "__main__":
    unittest.main()
