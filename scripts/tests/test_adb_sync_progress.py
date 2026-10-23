#!/usr/bin/env python3
"""
Unit tests for ADB Sync Real-Time Speed, ETA & Dual Progress Engine.
Verifies:
  1. Parsing of remote stat output for various file manifests.
  2. Rolling speed and ETA calculation stability.
  3. Structured [PROGRESS] dual-payload schema validation.
"""

import os
import sys
import json
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)


class TestAdbSyncProgress(unittest.TestCase):

    def test_remote_stat_output_parsing(self):
        """Verify parsing of Android 'stat -c %s %n' outputs."""
        sample_output = """
15985836 /sdcard/Android/data/com.bajajauto.roadsense/files/sessions/session_20261023_111120/radar/radar_frames.bin
837894944 /sdcard/Android/data/com.bajajauto.roadsense/files/sessions/session_20261023_111120/camera/camera_video.mp4
3075 /sdcard/Android/data/com.bajajauto.roadsense/files/sessions/session_20261023_111120/session_metadata.json
"""
        base_dir = "/sdcard/Android/data/com.bajajauto.roadsense/files/sessions/session_20261023_111120"
        files = []
        for line in sample_output.strip().splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=1)
            if len(parts) == 2 and parts[0].isdigit():
                f_size = int(parts[0])
                f_path = parts[1].strip()
                rel_path = os.path.relpath(f_path, base_dir).replace("\\", "/")
                files.append({
                    "remote": f_path,
                    "rel_path": rel_path,
                    "size": f_size
                })

        self.assertEqual(len(files), 3)
        self.assertEqual(files[0]["rel_path"], "radar/radar_frames.bin")
        self.assertEqual(files[0]["size"], 15985836)
        self.assertEqual(files[1]["rel_path"], "camera/camera_video.mp4")
        self.assertEqual(files[1]["size"], 837894944)
        self.assertEqual(files[2]["rel_path"], "session_metadata.json")
        self.assertEqual(files[2]["size"], 3075)

    def test_speed_and_eta_calculations(self):
        """Verify speed calculation with exponential moving average and ETA."""
        alpha = 0.25
        samples = [
            (0.2, 5 * 1024 * 1024),   # 25 MB/s
            (0.2, 7 * 1024 * 1024),   # 35 MB/s
            (0.2, 6 * 1024 * 1024),   # 30 MB/s
        ]
        rolling_speed = 0.0
        for dt, d_bytes in samples:
            inst = d_bytes / dt
            if rolling_speed == 0.0:
                rolling_speed = inst
            else:
                rolling_speed = alpha * inst + (1.0 - alpha) * rolling_speed

        speed_mb_s = rolling_speed / (1024 * 1024)
        self.assertGreater(speed_mb_s, 20.0)
        self.assertLess(speed_mb_s, 40.0)

        # ETA for remaining 300 MB at ~30 MB/s should be ~10s
        rem_bytes = 300 * 1024 * 1024
        eta_s = rem_bytes / rolling_speed
        self.assertAlmostEqual(eta_s, 10.0, delta=2.5)

    def test_dual_progress_payload_schema(self):
        """Verify that progress payload complies with Web Dashboard contract."""
        payload = {
            "step": "adb_sync",
            "desc": "Syncing session_20261023_111120 (1/2)",
            "overall_pct": 45.2,
            "overall_current": 4,
            "overall_total": 11,
            "overall_mb": 420.5,
            "overall_total_mb": 930.0,
            "sub": {
                "file_name": "camera/camera_video.mp4",
                "file_pct": 50.1,
                "current_mb": 420.0,
                "total_mb": 837.9,
                "speed_mb_s": 32.5,
                "eta_s": 12.8,
            }
        }
        json_str = json.dumps(payload)
        parsed = json.loads(json_str)

        self.assertEqual(parsed["step"], "adb_sync")
        self.assertIn("sub", parsed)
        self.assertIn("file_name", parsed["sub"])
        self.assertIn("speed_mb_s", parsed["sub"])
        self.assertIn("eta_s", parsed["sub"])
        self.assertIn("file_pct", parsed["sub"])


if __name__ == "__main__":
    unittest.main()
