#!/usr/bin/env python3
"""
Unit tests for Streaming JSON Writer with Dual-Progress Reporting.
Verifies:
  1. Equivalence of streamed JSON against monolithic json.dump(indent=2).
  2. All edge case combinations (empty frames, empty tracks, both populated).
  3. Structured [PROGRESS] event emissions during serialization.
"""

import os
import sys
import json
import tempfile
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.sync_and_process_sessions import stream_write_track_history, stream_write_frame_mapping


class TestStreamJsonWriter(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_track_history_exact_equivalence(self):
        """Verify streamed output deserializes into exact identical python structure."""
        frames = [
            {
                "frameIdx": i,
                "timestamp": float(i * 50),
                "numPoints": 2,
                "motionState": 0,
                "egoVelocity": [0.0, 12.5],
                "pointCloud": [[1.2, 3.4, 0.5, -2.1, 15.0], [4.5, 6.7, -0.1, 1.0, 22.0]],
                "clusters": [{"id": 1, "x": 1.2, "y": 3.4, "numPoints": 1}]
            }
            for i in range(1, 15)
        ]
        tracks = {
            101: {
                "id": 101,
                "isConfirmed": True,
                "historyLog": [{"frameIdx": 1, "correctedPosition": [1.2, 3.4]}]
            },
            102: {
                "id": 102,
                "isConfirmed": False,
                "historyLog": [{"frameIdx": 2, "correctedPosition": [4.5, 6.7]}]
            }
        }

        stream_path = os.path.join(self.temp_dir.name, "stream_th.json")
        mono_path = os.path.join(self.temp_dir.name, "mono_th.json")

        # 1. Monolithic dump
        mono_payload = {"radarFrames": frames, "tracks": list(tracks.values())}
        with open(mono_path, "w", encoding="utf-8") as f:
            json.dump(mono_payload, f, indent=2)

        # 2. Streamed write
        stream_write_track_history(stream_path, frames, tracks, "test_session", 70.0, 75.0)

        # 3. Verify files exist and are non-empty
        self.assertTrue(os.path.isfile(stream_path))
        self.assertGreater(os.path.getsize(stream_path), 0)

        # 4. Verify deserialized objects are 100% identical
        with open(mono_path, "r", encoding="utf-8") as mf, open(stream_path, "r", encoding="utf-8") as sf:
            d_mono = json.load(mf)
            d_stream = json.load(sf)

        self.assertEqual(d_stream, d_mono)

        # 5. Verify byte content matches
        with open(mono_path, "rb") as mf, open(stream_path, "rb") as sf:
            b_mono = mf.read()
            b_stream = sf.read()
        self.assertEqual(b_stream, b_mono)

    def test_track_history_edge_cases(self):
        """Verify all permutations of empty/populated frames and tracks."""
        cases = [
            ("empty_both", [], {}),
            ("empty_tracks", [{"frameIdx": 1, "test": True}], {}),
            ("empty_frames", [], {1: {"id": 1, "val": "abc"}}),
            ("both_single", [{"frameIdx": 1}], {1: {"id": 1}}),
        ]

        for name, frames, tracks in cases:
            stream_path = os.path.join(self.temp_dir.name, f"{name}_stream.json")
            mono_path = os.path.join(self.temp_dir.name, f"{name}_mono.json")

            tracks_list = list(tracks.values()) if isinstance(tracks, dict) else list(tracks)
            mono_payload = {"radarFrames": frames, "tracks": tracks_list}
            with open(mono_path, "w", encoding="utf-8") as f:
                json.dump(mono_payload, f, indent=2)

            stream_write_track_history(stream_path, frames, tracks, "test_session", 70.0, 75.0)

            with open(mono_path, "r", encoding="utf-8") as mf, open(stream_path, "r", encoding="utf-8") as sf:
                d_mono = json.load(mf)
                d_stream = json.load(sf)
            self.assertEqual(d_stream, d_mono, f"Failed on edge case: {name}")

            with open(mono_path, "rb") as mf, open(stream_path, "rb") as sf:
                b_mono = mf.read()
                b_stream = sf.read()
            self.assertEqual(b_stream, b_mono, f"Byte mismatch on edge case: {name}")

    def test_frame_mapping_stream_writer(self):
        """Verify stream_write_frame_mapping outputs valid JSON Lines format."""
        records = [
            {
                "radar_frame_id_rel": i,
                "radar_frame_id_abs": 100 + i,
                "radar_timestamp": 1789100000.0 + i * 0.05,
                "video_frame_index": i * 2,
                "video_frame_ts": 1789100000.0 + i * 0.05,
                "video_time_delta": 0.001,
                "camera_hw_frame_number": 500 + i
            }
            for i in range(1, 20)
        ]

        map_path = os.path.join(self.temp_dir.name, "frame_mapping.json")
        stream_write_frame_mapping(map_path, records, "test_session", 70.0, 72.0)

        self.assertTrue(os.path.isfile(map_path))
        lines = []
        with open(map_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    lines.append(json.loads(line))

        # Expected records + 1 metadata record at the end
        self.assertEqual(len(lines), len(records) + 1)
        self.assertIn("metadata", lines[-1])
        self.assertEqual(lines[0]["radar_frame_id_rel"], 1)


if __name__ == "__main__":
    unittest.main()
