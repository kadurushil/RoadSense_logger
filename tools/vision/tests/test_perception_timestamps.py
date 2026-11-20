import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.vision.pipeline.timeline_loader import TimelineLoader


class TestPerceptionTimestamps(unittest.TestCase):
    def test_trailing_mp4_frames_extrapolate_without_wall_clock_jump(self):
        base_utc = 1_795_155_894_736_274_308
        base_mono = 679_819_107_749_000
        utc_list = [base_utc - 33_333_333, base_utc]
        mono_list = [base_mono - 33_333_333, base_mono]

        # Normal recorded index
        u0, m0 = TimelineLoader.extrapolate_frame_timestamps(1, utc_list, mono_list, fps=30.0)
        self.assertEqual(u0, base_utc)
        self.assertEqual(m0, base_mono)

        # Trailing 1 extra MP4 frame past session_timeline.csv must advance by ~33.33 ms, never jump to time.time()
        u_extra, m_extra = TimelineLoader.extrapolate_frame_timestamps(2, utc_list, mono_list, fps=30.0)
        self.assertEqual(u_extra - base_utc, 33_333_333)
        self.assertEqual(m_extra - base_mono, 33_333_333)


if __name__ == "__main__":
    unittest.main()
