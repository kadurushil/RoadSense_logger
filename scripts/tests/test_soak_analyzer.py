import json
import os
import tempfile
import unittest

from scripts.soak_analyzer import (
    analyze_soak_run,
    compute_ols_slope_per_hour,
    evaluate_soak_telemetry,
    parse_battery,
    parse_gfxinfo,
    parse_meminfo,
    parse_thermalservice,
    parse_uiperf_window,
)

# Real SM-M325F dumpsys fixtures captured during Tier 3 planning
SM_M325F_MEMINFO_SAMPLE = """
Applications Memory Usage (in Kilobytes):
Uptime: 216730406 Realtime: 1131153707

** MEMINFO in pid 2044 [com.bajajauto.roadsense] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap    24156    24084        0        0    27800    31980    28439     3540
  Dalvik Heap     5349     5160        0        0    13608     8964     6723     2241
    GL mtrack    17788    17788        0        0    17788
        TOTAL   150756    63996    68940       21   241192    40944    35162     5781

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:    12716                          35444
         Native Heap:    24084                          27800
"""

SM_M325F_GFXINFO_SAMPLE = """
** Graphics info for pid 2044 [com.bajajauto.roadsense] **

Stats since: 210713530355708ns
Total frames rendered: 6454
Janky frames: 320 (4.96%)
50th percentile: 9ms
90th percentile: 14ms
95th percentile: 16ms
99th percentile: 24ms
Number Missed Vsync: 12
50th gpu percentile: 7ms
90th gpu percentile: 8ms
95th gpu percentile: 10ms
99th gpu percentile: 15ms
"""

SM_M325F_THERMAL_SAMPLE = """
Thermal Status: 1
Cached temperatures:
	Temperature{mValue=0.0, mType=2, mName=SUBBAT, mStatus=0}
	Temperature{mValue=34.2, mType=0, mName=AP, mStatus=0}
	Temperature{mValue=35.9, mType=5, mName=PA, mStatus=0}
	Temperature{mValue=37.0, mType=2, mName=BAT, mStatus=1}
	Temperature{mValue=37.4, mType=3, mName=SKIN, mStatus=1}
"""

SM_M325F_BATTERY_SAMPLE = """
Current Battery Service state:
  AC powered: false
  USB powered: true
  level: 85
  scale: 100
  voltage: 4110
  temperature: 270
"""


class SoakAnalyzerTest(unittest.TestCase):
    def test_sm_m325f_dumpsys_and_uiperf_parsers(self):
        mem = parse_meminfo(SM_M325F_MEMINFO_SAMPLE)
        self.assertAlmostEqual(mem["java_heap_mb"], 12716 / 1024.0, places=1)
        self.assertAlmostEqual(mem["native_heap_mb"], 24084 / 1024.0, places=1)
        self.assertAlmostEqual(mem["gl_mtrack_mb"], 17788 / 1024.0, places=1)
        self.assertAlmostEqual(mem["total_pss_mb"], 150756 / 1024.0, places=1)
        self.assertAlmostEqual(mem["total_rss_mb"], 241192 / 1024.0, places=1)

        gfx = parse_gfxinfo(SM_M325F_GFXINFO_SAMPLE)
        self.assertEqual(gfx["total_frames"], 6454)
        self.assertEqual(gfx["janky_frames"], 320)
        self.assertAlmostEqual(gfx["janky_pct"], 4.96, places=2)
        self.assertEqual(gfx["p95_ms"], 16)
        self.assertEqual(gfx["gpu_p95_ms"], 10)
        self.assertEqual(gfx["missed_vsync"], 12)

        thm = parse_thermalservice(SM_M325F_THERMAL_SAMPLE)
        self.assertEqual(thm["thermal_status"], 1)
        self.assertEqual(thm["thermal_label"], "LIGHT")
        self.assertAlmostEqual(thm["temps_c"]["AP"], 34.2, places=1)
        self.assertAlmostEqual(thm["temps_c"]["SKIN"], 37.4, places=1)
        self.assertNotIn("SUBBAT", thm["temps_c"])

        bat = parse_battery(SM_M325F_BATTERY_SAMPLE)
        self.assertEqual(bat["level_pct"], 85)
        self.assertAlmostEqual(bat["temp_c"], 27.0, places=1)
        self.assertEqual(bat["voltage_mv"], 4110)
        self.assertTrue(bat["usb_powered"])

        ui = parse_uiperf_window(
            "11-25 15:12:10.000 I/UiPerf: [WINDOW_5S] frames=299 | jank=1 (0.3%) | p50=6.10ms | p95=10.45ms | max=17.20ms | drawP50=1.10ms | drawP95=2.40ms | gpuP95=4.30ms"
        )
        self.assertIsNotNone(ui)
        self.assertEqual(ui["frames"], 299.0)
        self.assertAlmostEqual(ui["p95_ms"], 10.45, places=2)
        self.assertAlmostEqual(ui["gpu_p95_ms"], 4.30, places=2)

    def test_ols_slope_excludes_initial_warmup_spike(self):
        # 30-min run: initial 2 min jump from 20 -> 50 MB, then completely flat at 50 MB
        pts = [(0.0, 20.0), (60.0, 45.0), (120.0, 50.0)]
        for t in range(180, 1801, 60):
            pts.append((float(t), 50.0))
        slope = compute_ols_slope_per_hour(pts, warmup_exclude_sec=120.0)
        self.assertAlmostEqual(slope, 0.0, places=2)

        # Synthetic 60 MB/hr steady leak
        leak_pts = [(float(t), 40.0 + (t / 3600.0) * 60.0) for t in range(0, 1801, 60)]
        leak_slope = compute_ols_slope_per_hour(leak_pts, warmup_exclude_sec=120.0)
        self.assertAlmostEqual(leak_slope, 60.0, places=1)

    def test_healthy_soak_passes_all_gates_and_generates_reports(self):
        samples = []
        ui_windows = []
        for i in range(61):  # 15 minutes at 15s interval
            t = float(i * 15)
            samples.append({
                "elapsed_sec": t,
                "pid": 2044,
                "session_size_mb": round(i * 1.2, 2),
                "mem": {"java_heap_mb": 22.0 + (i % 3) * 0.4, "native_heap_mb": 48.0, "gl_mtrack_mb": 18.0, "total_pss_mb": 185.0},
                "gfx": {"total_frames": i * 400, "p95_ms": 11},
                "thermal": {"thermal_status": 0, "thermal_label": "NOMINAL", "temps_c": {"AP": 35.0, "SKIN": 34.0}},
                "battery": {"level_pct": max(10, 90 - i // 10), "temp_c": 34.5},
            })
            ui_windows.append({"elapsed_sec": t, "p95_ms": 10.5, "p50_ms": 6.2})

        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "soak_telemetry.jsonl"), "w", encoding="utf-8") as f:
                for s in samples:
                    f.write(json.dumps(s) + "\n")
            with open(os.path.join(tmp, "soak_meta.json"), "w", encoding="utf-8") as f:
                json.dump({"model": "SM-M325F", "android_release": "13", "app_version": "1.0"}, f)
            with open(os.path.join(tmp, "soak_logcat_summary.json"), "w", encoding="utf-8") as f:
                json.dump({"uiperf_windows": ui_windows, "radar_frame_drops": 2, "radar_frame_salvaged": 1}, f)

            report = analyze_soak_run(
                soak_dir=tmp,
                post_soak_health={"status": "PASS", "overall": "PASS (8/8 domains healthy)"},
                post_soak_parity={"overall_status": "PASS", "overall_score": 100.0},
                write_html=True,
            )
            self.assertEqual(report["overall_verdict"], "PASS")
            self.assertTrue(os.path.isfile(os.path.join(tmp, "soak_report.json")))
            self.assertTrue(os.path.isfile(os.path.join(tmp, "soak_report.html")))
            with open(os.path.join(tmp, "soak_report.html"), "r", encoding="utf-8") as hf:
                html_text = hf.read()
            self.assertIn("VERDICT: PASS", html_text)
            self.assertIn("<svg", html_text)

    def test_memory_leak_pid_restart_and_severe_thermal_fail_gates(self):
        samples = []
        for i in range(40):
            t = float(i * 30)  # 20 min
            samples.append({
                "elapsed_sec": t,
                "pid": 2044 if i < 20 else 3100,  # OOM restart mid-soak
                "session_size_mb": float(i),
                "mem": {
                    "java_heap_mb": 30.0 + (t / 3600.0) * 50.0,    # 50 MB/hr leak -> FAIL
                    "native_heap_mb": 40.0 + (t / 3600.0) * 60.0,  # 60 MB/hr leak -> FAIL
                    "gl_mtrack_mb": 20.0,
                    "total_pss_mb": 200.0 + i * 5.0,
                },
                "gfx": {"total_frames": 1000, "p95_ms": 12},
                "thermal": {"thermal_status": 3 if i > 30 else 1, "thermal_label": "SEVERE", "temps_c": {"SKIN": 46.5}},
                "battery": {"level_pct": 80, "temp_c": 46.0 if i > 30 else 35.0},
            })

        report = evaluate_soak_telemetry(samples, logcat_summary={"fatal_crashes": 1})
        self.assertEqual(report["overall_verdict"], "FAIL")
        failed_gates = {g["name"] for g in report["gates"] if g["status"] == "FAIL"}
        self.assertIn("Process Stability", failed_gates)
        self.assertIn("Java Heap Trend", failed_gates)
        self.assertIn("Native Heap Trend", failed_gates)
        self.assertIn("Thermal Envelope", failed_gates)


if __name__ == "__main__":
    unittest.main()
