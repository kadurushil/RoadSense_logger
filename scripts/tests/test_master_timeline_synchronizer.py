#!/usr/bin/env python3
"""
Unit tests for MasterTimelineSynchronizer.
"""

import os
import unittest
import tempfile
import json
import csv
from scripts.master_timeline_synchronizer import MasterTimelineSynchronizer


class TestMasterTimelineSynchronizer(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.TemporaryDirectory()
        self.session_dir = self.test_dir.name

        # Create session_metadata.json
        meta = {
            "sessionId": "test_session",
            "startTimeWallMs": 1700000000000,
            "startTimeMonotonicNs": 1000000000000
        }
        with open(os.path.join(self.session_dir, "session_metadata.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f)

        # Create session_timeline.csv with 3 preview frames, a 400ms gap, and 5 recording frames
        # Also include 5 radar frames interleaved
        timeline_rows = [
            # Pre-roll preview frames
            (1000000000000, "CAMERA", "FRAME", 100, "camera/camera_video.mp4", "exp=0;iso=0"),
            (1000033333333, "CAMERA", "FRAME", 101, "camera/camera_video.mp4", "exp=0;iso=0"),
            (1000066666666, "CAMERA", "FRAME", 102, "camera/camera_video.mp4", "exp=0;iso=0"),
            # 400ms reconfiguration gap
            # Recording frames
            (1000500000000, "CAMERA", "FRAME", 103, "camera/camera_video.mp4", "exp=0;iso=0"),
            (1000533333333, "CAMERA", "FRAME", 104, "camera/camera_video.mp4", "exp=0;iso=0"),
            (1000566666666, "CAMERA", "FRAME", 105, "camera/camera_video.mp4", "exp=0;iso=0"),
            (1000600000000, "CAMERA", "FRAME", 106, "camera/camera_video.mp4", "exp=0;iso=0"),
            (1000633333333, "CAMERA", "FRAME", 107, "camera/camera_video.mp4", "exp=0;iso=0"),
            # Radar frames (interleaved)
            (1000010000000, "RADAR", "FRAME", 501, "radar/radar_frames.bin", "tlvs=6"),
            (1000510000000, "RADAR", "FRAME", 502, "radar/radar_frames.bin", "tlvs=6"),
            (1000560000000, "RADAR", "FRAME", 503, "radar/radar_frames.bin", "tlvs=6"),
            (1000610000000, "RADAR", "FRAME", 504, "radar/radar_frames.bin", "tlvs=6"),
        ]

        with open(os.path.join(self.session_dir, "session_timeline.csv"), "w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["elapsed_realtime_ns", "sensor", "event", "sequence_id", "relative_path", "summary"])
            for r in timeline_rows:
                writer.writerow(r)

    def tearDown(self):
        self.test_dir.cleanup()

    def test_pre_roll_gap_detection(self):
        sync = MasterTimelineSynchronizer(self.session_dir)
        self.assertEqual(sync.pre_roll_count, 3)
        self.assertEqual(sync.video_start_seq_id, 103)
        self.assertEqual(sync.video_start_mono_ns, 1000500000000)
        self.assertEqual(len(sync.recording_camera_events), 5)

    def test_pts_shutter_mapping(self):
        sync = MasterTimelineSynchronizer(self.session_dir)
        # PTS 0.0s -> Frame 103
        t0 = sync.get_camera_shutter_mono_ns(0.0, 0)
        self.assertEqual(t0, 1000500000000)

        # PTS ~0.0333s -> Frame 104
        t1 = sync.get_camera_shutter_mono_ns(0.0333, 1)
        self.assertEqual(t1, 1000533333333)

        # PTS ~0.0666s -> Frame 105
        t2 = sync.get_camera_shutter_mono_ns(0.0666, 2)
        self.assertEqual(t2, 1000566666666)

    def test_camera_to_radar_matching(self):
        sync = MasterTimelineSynchronizer(self.session_dir)
        # Camera frame at 1000500000000 matches radar frame at 1000510000000 (Radar seq 502, rel_id 2)
        rel_id, abs_id, dt_ms = sync.match_camera_to_radar(1000500000000)
        self.assertEqual(abs_id, 502)
        self.assertEqual(rel_id, 2)
        self.assertAlmostEqual(dt_ms, 10.0, delta=0.5)

    def test_sync_frame_info(self):
        sync = MasterTimelineSynchronizer(self.session_dir)
        info = sync.get_sync_frame_info(0, 0.0, 1000500000000, 12.5)
        self.assertEqual(info["video_frame_index"], 0)
        self.assertEqual(info["camera_hw_frame_number"], 103)
        self.assertEqual(info["radar_frame_id_abs"], 502)
        self.assertEqual(info["speed_kmph"], 12.5)


if __name__ == "__main__":
    unittest.main()
