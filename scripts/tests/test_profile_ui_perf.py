import os
import tempfile
import unittest

from scripts.profile_ui_perf import analyze_profile_dir
from scripts.soak_analyzer import parse_uiperf_window


class ProfileUiPerfTest(unittest.TestCase):
    def test_parse_uiperf_window_extracts_anim_and_draw_percentiles(self):
        line = (
            "11-26 14:20:00.123 I/UiPerf  (1234): [WINDOW_5S] frames=110 | jank=2 (1.8%) | "
            "p50=11.20ms | p95=15.40ms | max=19.80ms | animP50=0.15ms | animP95=0.80ms | "
            "drawP50=1.05ms | drawP95=2.40ms | layoutP95=0.22ms | syncP95=0.35ms | "
            "gpuP95=5.10ms | unkP95=1.20ms | swapP95=3.10ms"
        )
        parsed = parse_uiperf_window(line)
        self.assertIsNotNone(parsed)
        self.assertAlmostEqual(parsed["anim_p50_ms"], 0.15)
        self.assertAlmostEqual(parsed["anim_p95_ms"], 0.80)
        self.assertAlmostEqual(parsed["draw_p50_ms"], 1.05)
        self.assertAlmostEqual(parsed["draw_p95_ms"], 2.40)
        self.assertAlmostEqual(parsed["p95_ms"], 15.40)

    def test_analyze_profile_dir_summarizes_windows_janks_and_recomp(self):
        sample_log = "\n".join([
            "11-26 14:20:00.100 I/UiPerf  (1): [WINDOW_5S] frames=100 | jank=40 (40.0%) | p50=16.00ms | p95=45.00ms | max=70.00ms | animP50=0.20ms | animP95=12.00ms | drawP50=1.20ms | drawP95=8.50ms | gpuP95=7.50ms",
            "11-26 14:20:05.100 I/UiPerf  (1): [WINDOW_5S] frames=110 | jank=1 (0.9%) | p50=10.50ms | p95=14.80ms | max=17.10ms | animP50=0.10ms | animP95=0.45ms | drawP50=0.95ms | drawP95=2.20ms | gpuP95=4.90ms",
            "11-26 14:20:10.100 I/UiPerf  (1): [WINDOW_5S] frames=112 | jank=2 (1.8%) | p50=10.80ms | p95=15.20ms | max=18.20ms | animP50=0.12ms | animP95=0.50ms | drawP50=1.00ms | drawP95=2.40ms | gpuP95=5.10ms",
            "11-26 14:20:15.100 I/UiPerf  (1): [WINDOW_5S] frames=108 | jank=1 (0.9%) | p50=10.20ms | p95=14.90ms | max=16.90ms | animP50=0.10ms | animP95=0.40ms | drawP50=0.90ms | drawP95=2.10ms | gpuP95=4.80ms",
            "11-26 14:20:15.105 I/RecompAudit(1): [RECOMP_5S] LandscapeBar=0 | LandscapePill=0 | RadarCard=0 | RadarHwCard=0",
            "11-26 14:20:15.200 W/UiPerf  (1): [JANK_FRAME] total=28.00ms | anim=1.00ms | layout=0.20ms | draw=8.00ms | sync=0.30ms | gpu=6.00ms | unk=1.00ms | swap=3.00ms | input=0.00ms | drops=0",
            "11-26 14:20:15.500 W/UiPerf  (1): [JANK_FRAME] total=31.00ms | anim=1.50ms | layout=0.20ms | draw=9.00ms | sync=0.30ms | gpu=6.50ms | unk=1.00ms | swap=3.10ms | input=0.00ms | drops=0",
        ])
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "ui_logcat.log"), "w", encoding="utf-8") as f:
                f.write(sample_log)
            summary = analyze_profile_dir(tmp)
            self.assertEqual(summary["verdict"], "PASS")
            self.assertAlmostEqual(summary["p95_median_ms"], 14.90)
            self.assertAlmostEqual(summary["anim_p95_median_ms"], 0.45)
            self.assertAlmostEqual(summary["draw_p95_median_ms"], 2.20)
            self.assertEqual(summary["recomp_summary"]["LandscapeBar"]["avg_per_5s"], 0.0)
            self.assertEqual(summary["period_buckets"]["240-360ms"], 1)


if __name__ == "__main__":
    unittest.main()
