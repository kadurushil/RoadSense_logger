#!/usr/bin/env python3
"""
RoadSense Bench Soak Certification Analyzer & HTML Report Generator
===================================================================

Evaluates telemetry collected by `scripts/run_soak_test.py` (`soak_telemetry.jsonl`)
and optional post-soak session audit / MCAP parity results into:
  - `soak_report.json`  : Machine-readable certification scorecard & trends
  - `soak_report.html`  : Zero-dependency dark-mode interactive SVG chart report
  - ANSI Terminal Table : Pass / Warn / Fail verdict per reliability gate

Can be called programmatically by `run_soak_test.py` or re-run offline:
  python scripts/soak_analyzer.py logs/soak_runs/soak_20261126_103000
  python scripts/soak_analyzer.py --latest
"""

import argparse
import html as html_lib
import json
import math
import os
import re
import statistics
import sys
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

DEFAULT_SOAK_ROOT = os.path.join(REPO_ROOT, "logs", "soak_runs")

if sys.platform == "win32":
    os.system("")

ANSI_GREEN = "\033[92m"
ANSI_YELLOW = "\033[93m"
ANSI_RED = "\033[91m"
ANSI_CYAN = "\033[96m"
ANSI_BOLD = "\033[1m"
ANSI_DIM = "\033[2m"
ANSI_RESET = "\033[0m"

THERMAL_STATUS_NAMES = {
    0: "NOMINAL",
    1: "LIGHT",
    2: "MODERATE",
    3: "SEVERE",
    4: "CRITICAL",
    5: "EMERGENCY",
    6: "SHUTDOWN",
}


def colorize_verdict(verdict: str) -> str:
    v = verdict.upper()
    if "FAIL" in v or "ERROR" in v:
        return f"{ANSI_RED}{ANSI_BOLD}{verdict}{ANSI_RESET}"
    if "WARN" in v:
        return f"{ANSI_YELLOW}{ANSI_BOLD}{verdict}{ANSI_RESET}"
    if "PASS" in v or "CERTIFIED" in v:
        return f"{ANSI_GREEN}{ANSI_BOLD}{verdict}{ANSI_RESET}"
    return f"{ANSI_CYAN}{verdict}{ANSI_RESET}"


# ---------------------------------------------------------------------------
# Hardware dumpsys & logcat parsers (verified against SM-M325F Android output)
# ---------------------------------------------------------------------------

def parse_meminfo(text: str) -> Dict[str, Optional[float]]:
    """
    Parses `adb shell dumpsys meminfo com.bajajauto.roadsense` output.
    Returns values in Megabytes (MB).
    """
    res: Dict[str, Optional[float]] = {
        "java_heap_mb": None,
        "native_heap_mb": None,
        "gl_mtrack_mb": None,
        "total_pss_mb": None,
        "total_rss_mb": None,
    }
    if not text:
        return res

    # App Summary section (most accurate for Java Heap / Native Heap PSS)
    m_java = re.search(r"^\s*Java Heap:\s*(\d+)", text, re.MULTILINE)
    if m_java:
        res["java_heap_mb"] = round(int(m_java.group(1)) / 1024.0, 2)

    m_native_sum = re.search(r"^\s*Native Heap:\s*(\d+)", text, re.MULTILINE)
    if m_native_sum:
        res["native_heap_mb"] = round(int(m_native_sum.group(1)) / 1024.0, 2)

    # Table fallbacks + GL mtrack + TOTAL row
    m_gl = re.search(r"^\s*GL mtrack\s+(\d+)", text, re.MULTILINE)
    if m_gl:
        res["gl_mtrack_mb"] = round(int(m_gl.group(1)) / 1024.0, 2)

    if res["native_heap_mb"] is None:
        m_nat = re.search(r"^\s*Native Heap\s+(\d+)", text, re.MULTILINE)
        if m_nat:
            res["native_heap_mb"] = round(int(m_nat.group(1)) / 1024.0, 2)

    if res["java_heap_mb"] is None:
        m_dal = re.search(r"^\s*Dalvik Heap\s+(\d+)", text, re.MULTILINE)
        if m_dal:
            res["java_heap_mb"] = round(int(m_dal.group(1)) / 1024.0, 2)

    # TOTAL row: TOTAL  PssTotal  PrivDirty  PrivClean  SwapPss  RssTotal ...
    m_tot = re.search(r"^\s*TOTAL\s+(\d+)\s+\d+\s+\d+\s+\d+\s+(\d+)", text, re.MULTILINE)
    if m_tot:
        res["total_pss_mb"] = round(int(m_tot.group(1)) / 1024.0, 2)
        res["total_rss_mb"] = round(int(m_tot.group(2)) / 1024.0, 2)
    else:
        m_tot_pss = re.search(r"TOTAL PSS:\s*(\d+)", text)
        if m_tot_pss:
            res["total_pss_mb"] = round(int(m_tot_pss.group(1)) / 1024.0, 2)
        m_tot_rss = re.search(r"TOTAL RSS:\s*(\d+)", text)
        if m_tot_rss:
            res["total_rss_mb"] = round(int(m_tot_rss.group(1)) / 1024.0, 2)

    return res


def parse_gfxinfo(text: str) -> Dict[str, Optional[float]]:
    """
    Parses `adb shell dumpsys gfxinfo com.bajajauto.roadsense` output.
    """
    res: Dict[str, Optional[float]] = {
        "total_frames": None,
        "janky_frames": None,
        "janky_pct": None,
        "p50_ms": None,
        "p90_ms": None,
        "p95_ms": None,
        "p99_ms": None,
        "gpu_p50_ms": None,
        "gpu_p95_ms": None,
        "missed_vsync": None,
    }
    if not text:
        return res

    def _int(pattern: str) -> Optional[int]:
        m = re.search(pattern, text, re.MULTILINE)
        return int(m.group(1)) if m else None

    res["total_frames"] = _int(r"^Total frames rendered:\s*(\d+)")
    m_jank = re.search(r"^Janky frames:\s*(\d+)\s*\(([0-9.]+)%\)", text, re.MULTILINE)
    if m_jank:
        res["janky_frames"] = int(m_jank.group(1))
        res["janky_pct"] = float(m_jank.group(2))

    res["p50_ms"] = _int(r"^50th percentile:\s*(\d+)ms")
    res["p90_ms"] = _int(r"^90th percentile:\s*(\d+)ms")
    res["p95_ms"] = _int(r"^95th percentile:\s*(\d+)ms")
    res["p99_ms"] = _int(r"^99th percentile:\s*(\d+)ms")
    res["gpu_p50_ms"] = _int(r"^50th gpu percentile:\s*(\d+)ms")
    res["gpu_p95_ms"] = _int(r"^95th gpu percentile:\s*(\d+)ms")
    res["missed_vsync"] = _int(r"^Number Missed Vsync:\s*(\d+)")
    return res


def parse_thermalservice(text: str) -> Dict[str, Any]:
    """
    Parses `adb shell dumpsys thermalservice` output.
    """
    status = 0
    m_stat = re.search(r"Thermal Status:\s*(\d+)", text or "")
    if m_stat:
        status = int(m_stat.group(1))

    temps_c: Dict[str, float] = {}
    for m in re.finditer(r"Temperature\{mValue=([0-9.\-]+),\s*mType=\d+,\s*mName=([A-Za-z0-9_\-]+)", text or ""):
        val = float(m.group(1))
        name = m.group(2).upper()
        if 0.1 < val < 130.0:
            temps_c[name] = round(val, 2)

    return {
        "thermal_status": status,
        "thermal_label": THERMAL_STATUS_NAMES.get(status, f"STATUS_{status}"),
        "temps_c": temps_c,
    }


def parse_battery(text: str) -> Dict[str, Any]:
    """
    Parses `adb shell dumpsys battery` output.
    """
    res: Dict[str, Any] = {
        "level_pct": None,
        "temp_c": None,
        "voltage_mv": None,
        "usb_powered": None,
        "ac_powered": None,
    }
    if not text:
        return res

    m_lvl = re.search(r"^\s*level:\s*(\d+)", text, re.MULTILINE)
    if m_lvl:
        res["level_pct"] = int(m_lvl.group(1))

    m_temp = re.search(r"^\s*temperature:\s*(\d+)", text, re.MULTILINE)
    if m_temp:
        res["temp_c"] = round(int(m_temp.group(1)) / 10.0, 1)

    m_volt = re.search(r"^\s*voltage:\s*(\d+)", text, re.MULTILINE)
    if m_volt:
        res["voltage_mv"] = int(m_volt.group(1))

    m_usb = re.search(r"^\s*USB powered:\s*(true|false)", text, re.MULTILINE | re.IGNORECASE)
    if m_usb:
        res["usb_powered"] = m_usb.group(1).lower() == "true"

    m_ac = re.search(r"^\s*AC powered:\s*(true|false)", text, re.MULTILINE | re.IGNORECASE)
    if m_ac:
        res["ac_powered"] = m_ac.group(1).lower() == "true"

    return res


def parse_uiperf_window(line: str) -> Optional[Dict[str, float]]:
    """
    Parses RoadSense UiFrameMetricsProfiler `[WINDOW_5S]` lines from logcat.
    Example:
      [WINDOW_5S] frames=298 | jank=2 (0.7%) | p50=6.12ms | p95=11.40ms | max=18.20ms | drawP50=... | gpuP95=4.10ms
    """
    if "[WINDOW_5S]" not in (line or ""):
        return None
    m = re.search(
        r"frames=(\d+)\s*\|\s*jank=(\d+)\s*\(([0-9.]+)%\)\s*\|\s*p50=([0-9.]+)ms\s*\|\s*p95=([0-9.]+)ms\s*\|\s*max=([0-9.]+)ms",
        line,
    )
    if not m:
        return None
    out: Dict[str, float] = {
        "frames": float(int(m.group(1))),
        "jank_count": float(int(m.group(2))),
        "jank_pct": float(m.group(3)),
        "p50_ms": float(m.group(4)),
        "p95_ms": float(m.group(5)),
        "max_ms": float(m.group(6)),
    }
    m_anim50 = re.search(r"animP50=([0-9.]+)ms", line)
    if m_anim50:
        out["anim_p50_ms"] = float(m_anim50.group(1))
    m_anim95 = re.search(r"animP95=([0-9.]+)ms", line)
    if m_anim95:
        out["anim_p95_ms"] = float(m_anim95.group(1))
    m_draw50 = re.search(r"drawP50=([0-9.]+)ms", line)
    if m_draw50:
        out["draw_p50_ms"] = float(m_draw50.group(1))
    m_draw = re.search(r"drawP95=([0-9.]+)ms", line)
    if m_draw:
        out["draw_p95_ms"] = float(m_draw.group(1))
    m_layout = re.search(r"layoutP95=([0-9.]+)ms", line)
    if m_layout:
        out["layout_p95_ms"] = float(m_layout.group(1))
    m_gpu = re.search(r"gpuP95=([0-9.]+)ms", line)
    if m_gpu:
        out["gpu_p95_ms"] = float(m_gpu.group(1))
    m_unk = re.search(r"unkP95=([0-9.]+)ms", line)
    if m_unk:
        out["unk_p95_ms"] = float(m_unk.group(1))
    m_swap = re.search(r"swapP95=([0-9.]+)ms", line)
    if m_swap:
        out["swap_p95_ms"] = float(m_swap.group(1))
    return out


# ---------------------------------------------------------------------------
# Statistical helpers & Certification Gates
# ---------------------------------------------------------------------------

def compute_ols_slope_per_hour(
    points: List[Tuple[float, float]],
    warmup_exclude_sec: float = 120.0,
) -> Optional[float]:
    """
    Computes Ordinary Least Squares (OLS) linear slope in units/hour over `(elapsed_sec, value)`
    samples, excluding initial warmup transient (`warmup_exclude_sec`, automatically capped at
    20% of total span for short runs).
    """
    if len(points) < 2:
        return None
    t_min = points[0][0]
    t_max = points[-1][0]
    span = t_max - t_min
    if span <= 1.0:
        return 0.0

    effective_warmup = min(warmup_exclude_sec, span * 0.20)
    filtered = [(t, v) for (t, v) in points if (t - t_min) >= effective_warmup]
    if len(filtered) < 2:
        filtered = points

    n = len(filtered)
    mean_t = sum(t for t, _ in filtered) / n
    mean_v = sum(v for _, v in filtered) / n
    num = sum((t - mean_t) * (v - mean_v) for t, v in filtered)
    den = sum((t - mean_t) ** 2 for t, _ in filtered)
    if den <= 1e-9:
        return 0.0

    slope_per_sec = num / den
    return round(slope_per_sec * 3600.0, 2)


def _worst_verdict(verdicts: List[str]) -> str:
    if any(v == "FAIL" for v in verdicts):
        return "FAIL"
    if any(v == "WARN" for v in verdicts):
        return "WARN"
    return "PASS"


def evaluate_soak_telemetry(
    samples: List[Dict[str, Any]],
    logcat_summary: Optional[Dict[str, Any]] = None,
    post_soak_health: Optional[Dict[str, Any]] = None,
    post_soak_parity: Optional[Dict[str, Any]] = None,
    device_meta: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    Evaluates a list of periodic soak telemetry records + logcat counters + optional
    post-soak session audit & MCAP parity reports against RoadSense certification gates.
    """
    logcat_summary = logcat_summary or {}
    device_meta = device_meta or {}

    if not samples:
        return {
            "overall_verdict": "FAIL",
            "reason": "No telemetry samples collected",
            "duration_sec": 0.0,
            "sample_count": 0,
            "device": device_meta,
            "metrics": {},
            "gates": [
                {"name": "Telemetry Collection", "status": "FAIL", "detail": "0 samples recorded"}
            ],
            "post_soak_health": post_soak_health,
            "post_soak_parity": post_soak_parity,
        }

    duration_sec = max(0.0, float(samples[-1].get("elapsed_sec", 0.0)) - float(samples[0].get("elapsed_sec", 0.0)))

    # 1. Process liveness & PID continuity
    pids = [s.get("pid") for s in samples if s.get("pid") is not None]
    distinct_pids = []
    for p in pids:
        if p not in distinct_pids:
            distinct_pids.append(p)
    pid_restarts = max(0, len(distinct_pids) - 1)
    missing_pid_ticks = sum(1 for s in samples if s.get("pid") is None)
    fatal_crashes = int(logcat_summary.get("fatal_crashes", 0))
    anr_count = int(logcat_summary.get("anr_count", 0))

    # 2. Memory trends
    java_pts: List[Tuple[float, float]] = []
    native_pts: List[Tuple[float, float]] = []
    pss_pts: List[Tuple[float, float]] = []
    gl_pts: List[Tuple[float, float]] = []
    for s in samples:
        t = float(s.get("elapsed_sec", 0.0))
        mem = s.get("mem") or {}
        if mem.get("java_heap_mb") is not None:
            java_pts.append((t, float(mem["java_heap_mb"])))
        if mem.get("native_heap_mb") is not None:
            native_pts.append((t, float(mem["native_heap_mb"])))
        if mem.get("total_pss_mb") is not None:
            pss_pts.append((t, float(mem["total_pss_mb"])))
        if mem.get("gl_mtrack_mb") is not None:
            gl_pts.append((t, float(mem["gl_mtrack_mb"])))

    java_slope = compute_ols_slope_per_hour(java_pts)
    native_slope = compute_ols_slope_per_hour(native_pts)
    pss_slope = compute_ols_slope_per_hour(pss_pts)
    peak_java_mb = max((v for _, v in java_pts), default=0.0)
    peak_native_mb = max((v for _, v in native_pts), default=0.0)
    peak_pss_mb = max((v for _, v in pss_pts), default=0.0)
    peak_gl_mb = max((v for _, v in gl_pts), default=0.0)

    # 3. UI Frame pacing (prefer live UiPerf [WINDOW_5S] series; fallback to dumpsys gfxinfo)
    uiperf_windows = logcat_summary.get("uiperf_windows") or []
    uiperf_p95s = [float(w["p95_ms"]) for w in uiperf_windows if w.get("p95_ms") is not None]
    gfx_p95s = [
        float(s["gfx"]["p95_ms"])
        for s in samples
        if (s.get("gfx") or {}).get("p95_ms") is not None and ((s.get("gfx") or {}).get("total_frames") or 0) > 0
    ]

    p95_source = "UiPerf [WINDOW_5S]" if uiperf_p95s else "dumpsys gfxinfo"
    p95_series = uiperf_p95s if uiperf_p95s else gfx_p95s
    median_p95_ms = round(statistics.median(p95_series), 2) if p95_series else None
    max_p95_ms = round(max(p95_series), 2) if p95_series else None

    p95_drift_ms: Optional[float] = None
    if len(p95_series) >= 6:
        third = max(1, len(p95_series) // 3)
        early_med = statistics.median(p95_series[:third])
        late_med = statistics.median(p95_series[-third:])
        p95_drift_ms = round(late_med - early_med, 2)

    # 4. Thermal & Battery
    thermal_statuses = [int((s.get("thermal") or {}).get("thermal_status", 0)) for s in samples]
    peak_thermal_status = max(thermal_statuses, default=0)
    peak_thermal_label = THERMAL_STATUS_NAMES.get(peak_thermal_status, f"STATUS_{peak_thermal_status}")
    moderate_or_higher_ticks = sum(1 for st in thermal_statuses if st >= 2)

    bat_temps = [float((s.get("battery") or {})["temp_c"]) for s in samples if (s.get("battery") or {}).get("temp_c") is not None]
    skin_temps = [
        float(((s.get("thermal") or {}).get("temps_c") or {})["SKIN"])
        for s in samples
        if "SKIN" in ((s.get("thermal") or {}).get("temps_c") or {})
    ]
    ap_temps = [
        float(((s.get("thermal") or {}).get("temps_c") or {})["AP"])
        for s in samples
        if "AP" in ((s.get("thermal") or {}).get("temps_c") or {})
    ]
    peak_bat_temp_c = max(bat_temps, default=0.0)
    peak_skin_temp_c = max(skin_temps, default=0.0)
    peak_ap_temp_c = max(ap_temps, default=0.0)

    bat_levels = [
        (float(s.get("elapsed_sec", 0.0)), float((s.get("battery") or {})["level_pct"]))
        for s in samples
        if (s.get("battery") or {}).get("level_pct") is not None
    ]
    bat_start_pct = bat_levels[0][1] if bat_levels else None
    bat_end_pct = bat_levels[-1][1] if bat_levels else None
    bat_drain_pct_per_hr = None
    if len(bat_levels) >= 2 and (bat_levels[-1][0] - bat_levels[0][0]) >= 30.0:
        dt_hr = (bat_levels[-1][0] - bat_levels[0][0]) / 3600.0
        bat_drain_pct_per_hr = round((bat_levels[0][1] - bat_levels[-1][1]) / dt_hr, 1)

    # 5. Disk & Session Growth
    disk_pts = [
        (float(s.get("elapsed_sec", 0.0)), float(s["session_size_mb"]))
        for s in samples
        if s.get("session_size_mb") is not None
    ]
    session_growth_mb = round(disk_pts[-1][1] - disk_pts[0][1], 2) if len(disk_pts) >= 2 else 0.0
    max_zero_growth_streak = 0
    cur_streak = 0
    for i in range(1, len(disk_pts)):
        if disk_pts[i][1] <= disk_pts[i - 1][1] + 0.001:
            cur_streak += 1
            max_zero_growth_streak = max(max_zero_growth_streak, cur_streak)
        else:
            cur_streak = 0

    # 6. Sensor & Queue logcat watermarks
    radar_drops = int(logcat_summary.get("radar_frame_drops", 0))
    radar_salvaged = int(logcat_summary.get("radar_frame_salvaged", 0))
    queue_drops = int(logcat_summary.get("queue_chunks_dropped", 0))
    usb_detaches = int(logcat_summary.get("usb_detaches", 0))
    usb_reconnects = int(logcat_summary.get("usb_reconnects", 0))
    recorder_errors = int(logcat_summary.get("recorder_errors", 0))
    display_pacer_drops = int(logcat_summary.get("display_pacer_drops", 0))

    # -----------------------------------------------------------------------
    # Evaluate Gates
    # -----------------------------------------------------------------------
    gates: List[Dict[str, str]] = []

    # Gate 1: Process Stability
    if fatal_crashes > 0 or anr_count > 0 or pid_restarts > 0 or missing_pid_ticks > 0:
        gates.append({
            "name": "Process Stability",
            "status": "FAIL",
            "detail": f"crashes={fatal_crashes}, ANRs={anr_count}, pidRestarts={pid_restarts}, missingPidTicks={missing_pid_ticks}",
        })
    else:
        gates.append({
            "name": "Process Stability",
            "status": "PASS",
            "detail": f"Constant PID={distinct_pids[0] if distinct_pids else 'N/A'}, 0 crashes, 0 ANRs",
        })

    is_short_smoke = duration_sec < 300.0

    # Gate 2: Java Heap Leak Slope
    if java_slope is None:
        gates.append({"name": "Java Heap Trend", "status": "WARN", "detail": "Insufficient meminfo samples"})
    elif java_slope > 30.0:
        if is_short_smoke and peak_java_mb <= 150.0:
            gates.append({"name": "Java Heap Trend", "status": "WARN", "detail": f"{java_slope:+.1f} MB/hr (short <5m run; verify on >=15m), peak={peak_java_mb:.1f} MB"})
        else:
            gates.append({"name": "Java Heap Trend", "status": "FAIL", "detail": f"{java_slope:+.1f} MB/hr (> 30 MB/hr), peak={peak_java_mb:.1f} MB"})
    elif java_slope > 15.0:
        gates.append({"name": "Java Heap Trend", "status": "WARN", "detail": f"{java_slope:+.1f} MB/hr (15-30 MB/hr), peak={peak_java_mb:.1f} MB"})
    else:
        gates.append({"name": "Java Heap Trend", "status": "PASS", "detail": f"{java_slope:+.1f} MB/hr, peak={peak_java_mb:.1f} MB"})

    # Gate 3: Native Heap Leak Slope
    if native_slope is None:
        gates.append({"name": "Native Heap Trend", "status": "WARN", "detail": "Insufficient meminfo samples"})
    elif native_slope > 40.0:
        if is_short_smoke and peak_native_mb <= 250.0:
            gates.append({"name": "Native Heap Trend", "status": "WARN", "detail": f"{native_slope:+.1f} MB/hr (short <5m run; verify on >=15m), peak={peak_native_mb:.1f} MB"})
        else:
            gates.append({"name": "Native Heap Trend", "status": "FAIL", "detail": f"{native_slope:+.1f} MB/hr (> 40 MB/hr), peak={peak_native_mb:.1f} MB"})
    elif native_slope > 20.0:
        gates.append({"name": "Native Heap Trend", "status": "WARN", "detail": f"{native_slope:+.1f} MB/hr (20-40 MB/hr), peak={peak_native_mb:.1f} MB"})
    else:
        gates.append({"name": "Native Heap Trend", "status": "PASS", "detail": f"{native_slope:+.1f} MB/hr, peak={peak_native_mb:.1f} MB"})

    # Gate 4: Peak Total PSS
    if peak_pss_mb > 1024.0:
        gates.append({"name": "Peak Memory (PSS)", "status": "FAIL", "detail": f"{peak_pss_mb:.1f} MB (> 1024 MB), GL={peak_gl_mb:.1f} MB"})
    elif peak_pss_mb > 750.0:
        gates.append({"name": "Peak Memory (PSS)", "status": "WARN", "detail": f"{peak_pss_mb:.1f} MB (750-1024 MB), GL={peak_gl_mb:.1f} MB"})
    else:
        gates.append({"name": "Peak Memory (PSS)", "status": "PASS", "detail": f"{peak_pss_mb:.1f} MB, slope={(pss_slope or 0.0):+.1f} MB/hr, GL={peak_gl_mb:.1f} MB"})

    # Gate 5: UI Frame Pacing
    if median_p95_ms is None:
        gates.append({"name": "UI Frame Pacing (P95)", "status": "PASS", "detail": "UI idle / no active redraw frames sampled"})
    else:
        drift_str = f", drift={p95_drift_ms:+.2f} ms" if p95_drift_ms is not None else ""
        if median_p95_ms > 22.0 or (p95_drift_ms is not None and p95_drift_ms > 8.0):
            gates.append({"name": "UI Frame Pacing (P95)", "status": "FAIL", "detail": f"med={median_p95_ms:.2f} ms, max={max_p95_ms:.2f} ms{drift_str} ({p95_source})"})
        elif median_p95_ms > 16.67 or (p95_drift_ms is not None and p95_drift_ms > 4.0):
            gates.append({"name": "UI Frame Pacing (P95)", "status": "WARN", "detail": f"med={median_p95_ms:.2f} ms, max={max_p95_ms:.2f} ms{drift_str} ({p95_source})"})
        else:
            gates.append({"name": "UI Frame Pacing (P95)", "status": "PASS", "detail": f"med={median_p95_ms:.2f} ms, max={max_p95_ms:.2f} ms{drift_str} ({p95_source})"})

    # Gate 6: Thermal Envelope
    if peak_thermal_status >= 3 or peak_bat_temp_c > 45.0:
        gates.append({
            "name": "Thermal Envelope",
            "status": "FAIL",
            "detail": f"peakStatus={peak_thermal_label} ({peak_thermal_status}), Bat={peak_bat_temp_c:.1f}C, Skin={peak_skin_temp_c:.1f}C, AP={peak_ap_temp_c:.1f}C",
        })
    elif peak_thermal_status == 2 or peak_bat_temp_c >= 42.0:
        gates.append({
            "name": "Thermal Envelope",
            "status": "WARN",
            "detail": f"peakStatus={peak_thermal_label} ({peak_thermal_status}), Bat={peak_bat_temp_c:.1f}C, Skin={peak_skin_temp_c:.1f}C ({moderate_or_higher_ticks} ticks >= MODERATE)",
        })
    else:
        gates.append({
            "name": "Thermal Envelope",
            "status": "PASS",
            "detail": f"peakStatus={peak_thermal_label} ({peak_thermal_status}), Bat={peak_bat_temp_c:.1f}C, Skin={peak_skin_temp_c:.1f}C, AP={peak_ap_temp_c:.1f}C",
        })

    # Gate 7: Sensor & Queue Watermarks
    if queue_drops > 0 or recorder_errors > 0 or radar_drops > 15:
        gates.append({
            "name": "Sensor & Queue Health",
            "status": "FAIL",
            "detail": f"queueDrops={queue_drops}, recorderErrs={recorder_errors}, radarDrops={radar_drops}, salvaged={radar_salvaged}, usbDetaches={usb_detaches}",
        })
    elif radar_drops >= 5 or usb_detaches > 0:
        gates.append({
            "name": "Sensor & Queue Health",
            "status": "WARN",
            "detail": f"radarDrops={radar_drops}, salvaged={radar_salvaged}, usbDetaches={usb_detaches} (reconnected={usb_reconnects}), pacerDrops={display_pacer_drops}",
        })
    else:
        gates.append({
            "name": "Sensor & Queue Health",
            "status": "PASS",
            "detail": f"0 queueDrops, 0 recorderErrs, radarDrops={radar_drops}, salvaged={radar_salvaged}, pacerDrops={display_pacer_drops}",
        })

    # Gate 8: Post-Soak 8-Domain Health Check (if chained)
    if post_soak_health:
        h_status = str(post_soak_health.get("status", "UNKNOWN")).upper()
        h_overall = str(post_soak_health.get("overall", h_status))
        g_stat = "PASS" if "PASS" in h_status else ("WARN" if "WARN" in h_status else "FAIL")
        gates.append({
            "name": "Post-Soak Session Health",
            "status": g_stat,
            "detail": h_overall,
        })

    # Gate 9: Post-Soak MCAP Conversion Parity (if chained)
    if post_soak_parity:
        p_status = str(post_soak_parity.get("overall_status", "UNKNOWN")).upper()
        p_score = post_soak_parity.get("overall_score")
        score_str = f" (score={p_score:.1f}/100)" if isinstance(p_score, (int, float)) else ""
        g_stat = "PASS" if p_status == "PASS" else ("WARN" if p_status == "WARN" else "FAIL")
        gates.append({
            "name": "Post-Soak MCAP Parity",
            "status": g_stat,
            "detail": f"{p_status}{score_str}",
        })

    overall = _worst_verdict([g["status"] for g in gates])

    return {
        "overall_verdict": overall,
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "duration_sec": round(duration_sec, 1),
        "duration_min": round(duration_sec / 60.0, 2),
        "sample_count": len(samples),
        "device": device_meta,
        "metrics": {
            "pid_list": distinct_pids,
            "pid_restarts": pid_restarts,
            "fatal_crashes": fatal_crashes,
            "anr_count": anr_count,
            "java_heap_slope_mb_per_hr": java_slope,
            "native_heap_slope_mb_per_hr": native_slope,
            "total_pss_slope_mb_per_hr": pss_slope,
            "peak_java_heap_mb": round(peak_java_mb, 2),
            "peak_native_heap_mb": round(peak_native_mb, 2),
            "peak_total_pss_mb": round(peak_pss_mb, 2),
            "peak_gl_mtrack_mb": round(peak_gl_mb, 2),
            "ui_p95_source": p95_source,
            "ui_median_p95_ms": median_p95_ms,
            "ui_max_p95_ms": max_p95_ms,
            "ui_p95_drift_ms": p95_drift_ms,
            "peak_thermal_status": peak_thermal_status,
            "peak_thermal_label": peak_thermal_label,
            "peak_battery_temp_c": round(peak_bat_temp_c, 1),
            "peak_skin_temp_c": round(peak_skin_temp_c, 1),
            "peak_ap_temp_c": round(peak_ap_temp_c, 1),
            "battery_start_pct": bat_start_pct,
            "battery_end_pct": bat_end_pct,
            "battery_drain_pct_per_hr": bat_drain_pct_per_hr,
            "session_growth_mb": session_growth_mb,
            "max_zero_growth_streak": max_zero_growth_streak,
            "radar_frame_drops": radar_drops,
            "radar_frame_salvaged": radar_salvaged,
            "queue_chunks_dropped": queue_drops,
            "usb_detaches": usb_detaches,
            "usb_reconnects": usb_reconnects,
            "recorder_errors": recorder_errors,
            "display_pacer_drops": display_pacer_drops,
        },
        "gates": gates,
        "post_soak_health": post_soak_health,
        "post_soak_parity": post_soak_parity,
    }


# ---------------------------------------------------------------------------
# Direct Python Post-Soak Audit + MCAP Parity Chain
# ---------------------------------------------------------------------------

def run_post_soak_session_audit(
    session_dir: str,
    run_parity: bool = True,
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """
    Directly calls `scripts.session_health_check.audit_session()` and
    `tools.verify_conversion_parity.verify_session_parity()` on a local pulled session
    directory, prints their terminal output, and returns structured dicts for embedding
    into `soak_report.json`.
    """
    health_dict: Optional[Dict[str, Any]] = None
    parity_dict: Optional[Dict[str, Any]] = None

    if not session_dir or not os.path.isdir(session_dir):
        print(f"[-] Post-soak audit skipped: session directory not found ({session_dir})")
        return health_dict, parity_dict

    print("\n" + "=" * 80)
    print(f"POST-SOAK AUTOMATED HEALTH AUDIT: {os.path.basename(session_dir)}")
    print("=" * 80)
    try:
        from scripts.session_health_check import audit_session

        scorecard, overall = audit_session(session_dir, quiet=False, save_json=True, optional_can=True)
        status = "PASS" if "PASS" in overall.upper() else ("WARN" if "WARN" in overall.upper() else "FAIL")
        health_dict = {
            "session": os.path.basename(session_dir),
            "status": status,
            "overall": overall,
            "scorecard": scorecard,
        }
    except Exception as e:
        print(f"[!] Post-soak session_health_check failed: {e}")
        health_dict = {
            "session": os.path.basename(session_dir),
            "status": "FAIL",
            "overall": f"FAIL ({e})",
            "scorecard": {},
        }

    if run_parity:
        print("\n" + "=" * 80)
        print(f"POST-SOAK AUTOMATED MCAP CONVERSION PARITY: {os.path.basename(session_dir)}")
        print("=" * 80)
        try:
            from tools.verify_conversion_parity import verify_session_parity

            p_report = verify_session_parity(session_dir, sample_count=100, check_all=False, tolerance=1e-3)
            p_report.print_terminal_summary()
            raw_dict = p_report.to_dict() if hasattr(p_report, "to_dict") else {}
            parity_dict = {
                "session": os.path.basename(session_dir),
                "overall_status": p_report.overall_status(),
                "overall_score": raw_dict.get("overall_score"),
                "categories": raw_dict.get("categories", {}),
            }
        except Exception as e:
            print(f"[!] Post-soak verify_conversion_parity failed: {e}")
            parity_dict = {
                "session": os.path.basename(session_dir),
                "overall_status": "FAIL",
                "error": str(e),
            }

    return health_dict, parity_dict


# ---------------------------------------------------------------------------
# Zero-Dependency Dark-Mode SVG HTML Report & ANSI Terminal Scorecard
# ---------------------------------------------------------------------------

def _render_svg_chart(
    title: str,
    unit: str,
    series: List[Tuple[str, str, List[Tuple[float, float]]]],
    threshold_line: Optional[Tuple[float, str, str]] = None,
) -> str:
    """Renders a crisp, self-contained inline SVG time-series chart."""
    w, h = 540, 190
    pad_l, pad_r, pad_t, pad_b = 48, 16, 28, 28
    plot_w = w - pad_l - pad_r
    plot_h = h - pad_t - pad_b

    all_t = [t for _, _, pts in series for t, _ in pts]
    all_v = [v for _, _, pts in series for _, v in pts]
    if threshold_line is not None:
        all_v.append(threshold_line[0])

    if not all_t or not all_v:
        return (
            f'<div class="chart-card"><h3>{html_lib.escape(title)}</h3>'
            f'<div class="empty">No data recorded</div></div>'
        )

    t_min, t_max = min(all_t), max(all_t)
    if t_max <= t_min:
        t_max = t_min + 1.0
    v_min = min(0.0, min(all_v))
    v_max = max(all_v) * 1.12
    if v_max <= v_min:
        v_max = v_min + 1.0

    def sx(t: float) -> float:
        return pad_l + ((t - t_min) / (t_max - t_min)) * plot_w

    def sy(v: float) -> float:
        return pad_t + plot_h - ((v - v_min) / (v_max - v_min)) * plot_h

    grid_lines = []
    for i in range(5):
        frac = i / 4.0
        val = v_min + frac * (v_max - v_min)
        y = sy(val)
        grid_lines.append(
            f'<line x1="{pad_l}" y1="{y:.1f}" x2="{w - pad_r}" y2="{y:.1f}" stroke="#1e293b" stroke-width="1"/>'
            f'<text x="{pad_l - 6}" y="{y + 3:.1f}" text-anchor="end" fill="#64748b" font-size="9" font-family="monospace">{val:.1f}</text>'
        )

    thresh_svg = ""
    if threshold_line is not None:
        ty = sy(threshold_line[0])
        thresh_svg = (
            f'<line x1="{pad_l}" y1="{ty:.1f}" x2="{w - pad_r}" y2="{ty:.1f}" '
            f'stroke="{threshold_line[1]}" stroke-dasharray="4,3" stroke-width="1.2"/>'
            f'<text x="{w - pad_r - 2}" y="{ty - 3:.1f}" text-anchor="end" fill="{threshold_line[1]}" '
            f'font-size="9" font-family="monospace">{html_lib.escape(threshold_line[2])}</text>'
        )

    polylines = []
    legend_items = []
    for label, color, pts in series:
        if not pts:
            continue
        coords = " ".join(f"{sx(t):.1f},{sy(v):.1f}" for t, v in pts)
        polylines.append(
            f'<polyline fill="none" stroke="{color}" stroke-width="2" points="{coords}"/>'
        )
        last_v = pts[-1][1]
        legend_items.append(
            f'<span class="leg"><span class="dot" style="background:{color}"></span>{html_lib.escape(label)} ({last_v:.1f} {html_lib.escape(unit)})</span>'
        )

    x_end_min = (t_max - t_min) / 60.0
    svg = (
        f'<div class="chart-card">'
        f'<div class="chart-hdr"><h3>{html_lib.escape(title)}</h3><div class="legend">{"".join(legend_items)}</div></div>'
        f'<svg viewBox="0 0 {w} {h}" width="100%" height="190">'
        f'{"".join(grid_lines)}'
        f'{thresh_svg}'
        f'{"".join(polylines)}'
        f'<text x="{pad_l}" y="{h - 6}" fill="#64748b" font-size="9" font-family="monospace">0.0 min</text>'
        f'<text x="{w - pad_r}" y="{h - 6}" text-anchor="end" fill="#64748b" font-size="9" font-family="monospace">{x_end_min:.1f} min</text>'
        f'</svg></div>'
    )
    return svg


def render_soak_html_report(
    report: Dict[str, Any],
    samples: List[Dict[str, Any]],
    logcat_summary: Optional[Dict[str, Any]] = None,
) -> str:
    """Generates a self-contained dark-mode HTML certification report."""
    logcat_summary = logcat_summary or {}
    verdict = report.get("overall_verdict", "UNKNOWN")
    verdict_cls = "pass" if verdict == "PASS" else ("warn" if verdict == "WARN" else "fail")
    metrics = report.get("metrics", {})
    device = report.get("device", {})

    java_pts = [(float(s["elapsed_sec"]), float(s["mem"]["java_heap_mb"])) for s in samples if (s.get("mem") or {}).get("java_heap_mb") is not None]
    nat_pts = [(float(s["elapsed_sec"]), float(s["mem"]["native_heap_mb"])) for s in samples if (s.get("mem") or {}).get("native_heap_mb") is not None]
    pss_pts = [(float(s["elapsed_sec"]), float(s["mem"]["total_pss_mb"])) for s in samples if (s.get("mem") or {}).get("total_pss_mb") is not None]

    bat_pts = [(float(s["elapsed_sec"]), float(s["battery"]["temp_c"])) for s in samples if (s.get("battery") or {}).get("temp_c") is not None]
    skin_pts = [(float(s["elapsed_sec"]), float(s["thermal"]["temps_c"]["SKIN"])) for s in samples if "SKIN" in ((s.get("thermal") or {}).get("temps_c") or {})]
    ap_pts = [(float(s["elapsed_sec"]), float(s["thermal"]["temps_c"]["AP"])) for s in samples if "AP" in ((s.get("thermal") or {}).get("temps_c") or {})]

    ui_windows = logcat_summary.get("uiperf_windows") or []
    p95_pts = [(float(w.get("elapsed_sec", i * 5.0)), float(w["p95_ms"])) for i, w in enumerate(ui_windows) if w.get("p95_ms") is not None]
    if not p95_pts:
        p95_pts = [(float(s["elapsed_sec"]), float(s["gfx"]["p95_ms"])) for s in samples if (s.get("gfx") or {}).get("p95_ms") is not None]

    disk_pts = [(float(s["elapsed_sec"]), float(s["session_size_mb"])) for s in samples if s.get("session_size_mb") is not None]
    lvl_pts = [(float(s["elapsed_sec"]), float(s["battery"]["level_pct"])) for s in samples if (s.get("battery") or {}).get("level_pct") is not None]

    charts_html = "\n".join([
        _render_svg_chart("Memory Footprint (MB)", "MB", [("Total PSS", "#38bdf8", pss_pts), ("Native Heap", "#f59e0b", nat_pts), ("Java Heap", "#22c55e", java_pts)]),
        _render_svg_chart("Thermal Profile (°C)", "°C", [("Battery", "#ef4444", bat_pts), ("Skin", "#f59e0b", skin_pts), ("AP SoC", "#a855f7", ap_pts)], threshold_line=(42.0, "#f59e0b", "42°C WARN")),
        _render_svg_chart("UI Frame Time P95 (ms)", "ms", [("P95 Frame Time", "#06b6d4", p95_pts)], threshold_line=(16.67, "#22c55e", "16.67ms (60Hz)")),
        _render_svg_chart("Session Disk Growth & Battery", "MB/%", [("Session MB", "#10b981", disk_pts), ("Battery %", "#60a5fa", lvl_pts)]),
    ])

    gate_rows = []
    for g in report.get("gates", []):
        st = g["status"]
        cls = "pass" if st == "PASS" else ("warn" if st == "WARN" else "fail")
        gate_rows.append(
            f'<tr><td>{html_lib.escape(g["name"])}</td>'
            f'<td><span class="badge {cls}">{html_lib.escape(st)}</span></td>'
            f'<td class="mono">{html_lib.escape(g["detail"])}</td></tr>'
        )

    dev_str = f'{device.get("model", "Android")} (Android {device.get("android_release", "?")}, RoadSense {device.get("app_version", "?")})'

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>RoadSense Soak Certification Report</title>
<style>
  body {{ margin:0; padding:20px; background:#0b0f17; color:#e2e8f0; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }}
  .hdr {{ display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid #1e293b; padding-bottom:14px; margin-bottom:18px; flex-wrap:wrap; gap:12px; }}
  .hdr h1 {{ margin:0; font-size:20px; color:#f8fafc; }}
  .sub {{ font-size:12px; color:#94a3b8; font-family:monospace; margin-top:4px; }}
  .badge {{ display:inline-block; padding:3px 10px; border-radius:6px; font-weight:700; font-family:monospace; font-size:12px; }}
  .badge.pass {{ background:#0d3319; color:#4ade80; border:1px solid #22c55e; }}
  .badge.warn {{ background:#3b2605; color:#fbbf24; border:1px solid #f59e0b; }}
  .badge.fail {{ background:#3f1315; color:#f87171; border:1px solid #ef4444; }}
  .kpi-grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:10px; margin-bottom:18px; }}
  .kpi {{ background:#131a24; border:1px solid #243041; border-radius:10px; padding:12px; }}
  .kpi .lbl {{ font-size:11px; color:#94a3b8; text-transform:uppercase; }}
  .kpi .val {{ font-size:18px; font-weight:700; color:#f8fafc; font-family:monospace; margin-top:4px; }}
  .charts {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(460px,1fr)); gap:14px; margin-bottom:20px; }}
  .chart-card {{ background:#131a24; border:1px solid #243041; border-radius:10px; padding:12px; }}
  .chart-hdr {{ display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; margin-bottom:6px; }}
  .chart-card h3 {{ margin:0; font-size:13px; color:#cbd5e1; }}
  .legend {{ display:flex; gap:10px; flex-wrap:wrap; font-size:11px; color:#94a3b8; font-family:monospace; }}
  .leg {{ display:inline-flex; align-items:center; gap:4px; }}
  .dot {{ width:8px; height:8px; border-radius:50%; display:inline-block; }}
  table {{ width:100%; border-collapse:collapse; background:#131a24; border:1px solid #243041; border-radius:10px; overflow:hidden; }}
  th, td {{ padding:10px 12px; text-align:left; border-bottom:1px solid #1e293b; font-size:12.5px; }}
  th {{ background:#0f1621; color:#94a3b8; text-transform:uppercase; font-size:11px; }}
  .mono {{ font-family:monospace; color:#cbd5e1; }}
  .empty {{ padding:30px; text-align:center; color:#64748b; font-family:monospace; }}
</style>
</head>
<body>
  <div class="hdr">
    <div>
      <h1>RoadSense Bench Soak Certification Report</h1>
      <div class="sub">{html_lib.escape(dev_str)} &bull; Duration: {report.get("duration_min", 0):.2f} min ({report.get("sample_count", 0)} samples) &bull; Generated: {html_lib.escape(report.get("generated_at", ""))}</div>
    </div>
    <span class="badge {verdict_cls}" style="font-size:15px; padding:6px 14px;">VERDICT: {html_lib.escape(verdict)}</span>
  </div>

  <div class="kpi-grid">
    <div class="kpi"><div class="lbl">Peak Total PSS</div><div class="val">{metrics.get("peak_total_pss_mb", 0):.1f} MB</div></div>
    <div class="kpi"><div class="lbl">Java / Native Slope</div><div class="val">{(metrics.get("java_heap_slope_mb_per_hr") or 0):+.1f} / {(metrics.get("native_heap_slope_mb_per_hr") or 0):+.1f} MB/h</div></div>
    <div class="kpi"><div class="lbl">UI Median P95</div><div class="val">{(f'{metrics["ui_median_p95_ms"]:.2f} ms' if metrics.get("ui_median_p95_ms") is not None else 'N/A')}</div></div>
    <div class="kpi"><div class="lbl">Peak Bat / Skin</div><div class="val">{metrics.get("peak_battery_temp_c", 0):.1f}&deg;C / {metrics.get("peak_skin_temp_c", 0):.1f}&deg;C</div></div>
    <div class="kpi"><div class="lbl">Session Growth</div><div class="val">{metrics.get("session_growth_mb", 0):.1f} MB</div></div>
    <div class="kpi"><div class="lbl">Crashes / PID Restarts</div><div class="val">{metrics.get("fatal_crashes", 0)} / {metrics.get("pid_restarts", 0)}</div></div>
  </div>

  <div class="charts">{charts_html}</div>

  <table>
    <thead><tr><th>Certification Gate</th><th>Verdict</th><th>Measured Evidence</th></tr></thead>
    <tbody>{"".join(gate_rows)}</tbody>
  </table>
</body>
</html>
"""


def print_terminal_scorecard(report: Dict[str, Any]) -> None:
    print("\n" + "=" * 88)
    print(f"{ANSI_BOLD}ROADSENSE BENCH SOAK CERTIFICATION SCORECARD{ANSI_RESET}")
    print("=" * 88)
    dev = report.get("device", {})
    print(f"  Device:     {dev.get('model', 'Unknown')} (Android {dev.get('android_release', '?')}, SDK {dev.get('sdk_int', '?')}, App {dev.get('app_version', '?')})")
    print(f"  Duration:   {report.get('duration_min', 0):.2f} min ({report.get('duration_sec', 0):.0f} s, {report.get('sample_count', 0)} polls)")
    print("-" * 88)
    print(f"  {'Certification Gate':<28s} {'Verdict':<10s} {'Measured Evidence'}")
    print("-" * 88)
    for g in report.get("gates", []):
        st_col = colorize_verdict(f"{g['status']:<6s}")
        print(f"  {g['name']:<28s} {st_col:<19s} {g['detail']}")
    print("=" * 88)
    print(f"  OVERALL SOAK VERDICT:  {colorize_verdict(report.get('overall_verdict', 'UNKNOWN'))}")
    print("=" * 88)


def analyze_soak_run(
    soak_dir: str,
    post_soak_health: Optional[Dict[str, Any]] = None,
    post_soak_parity: Optional[Dict[str, Any]] = None,
    write_html: bool = True,
) -> Dict[str, Any]:
    """
    Loads `soak_telemetry.jsonl` (+ optional `soak_meta.json` / `soak_logcat_summary.json`)
    from `soak_dir`, evaluates all gates, writes `soak_report.json` & `soak_report.html`,
    and prints the ANSI terminal scorecard.
    """
    telemetry_path = os.path.join(soak_dir, "soak_telemetry.jsonl")
    samples: List[Dict[str, Any]] = []
    if os.path.isfile(telemetry_path):
        with open(telemetry_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    samples.append(json.loads(line))
                except json.JSONDecodeError:
                    continue

    meta_path = os.path.join(soak_dir, "soak_meta.json")
    device_meta: Dict[str, Any] = {}
    if os.path.isfile(meta_path):
        with open(meta_path, "r", encoding="utf-8") as f:
            device_meta = json.load(f)

    logcat_sum_path = os.path.join(soak_dir, "soak_logcat_summary.json")
    logcat_summary: Dict[str, Any] = {}
    if os.path.isfile(logcat_sum_path):
        with open(logcat_sum_path, "r", encoding="utf-8") as f:
            logcat_summary = json.load(f)

    report = evaluate_soak_telemetry(
        samples=samples,
        logcat_summary=logcat_summary,
        post_soak_health=post_soak_health,
        post_soak_parity=post_soak_parity,
        device_meta=device_meta,
    )

    report_json_path = os.path.join(soak_dir, "soak_report.json")
    with open(report_json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print_terminal_scorecard(report)
    print(f"[+] Soak JSON report saved: {report_json_path}")

    if write_html:
        html_path = os.path.join(soak_dir, "soak_report.html")
        html_str = render_soak_html_report(report, samples, logcat_summary)
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(html_str)
        rel_html = os.path.relpath(html_path, REPO_ROOT).replace("\\", "/")
        print(f"[+] Interactive Soak HTML Report URL: {rel_html}")

    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze a saved RoadSense bench soak run.")
    parser.add_argument("soak_dir", nargs="?", default=None, help="Path to soak run folder under logs/soak_runs/")
    parser.add_argument("--latest", action="store_true", help="Analyze the latest soak run in logs/soak_runs/")
    parser.add_argument("--session-dir", default=None, help="Optional pulled session dir to chain health & parity audits")
    parser.add_argument("--no-parity", action="store_true", help="Skip MCAP parity when --session-dir is supplied")
    parser.add_argument("--no-html", action="store_true", help="Skip generating soak_report.html")
    args = parser.parse_args()

    target = args.soak_dir
    if args.latest or not target:
        if not os.path.isdir(DEFAULT_SOAK_ROOT):
            sys.exit(f"Error: No soak runs found in {DEFAULT_SOAK_ROOT}")
        runs = sorted(d for d in os.listdir(DEFAULT_SOAK_ROOT) if d.startswith("soak_"))
        if not runs:
            sys.exit(f"Error: No soak_* folders found in {DEFAULT_SOAK_ROOT}")
        target = os.path.join(DEFAULT_SOAK_ROOT, runs[-1])

    health_dict, parity_dict = (None, None)
    if args.session_dir:
        health_dict, parity_dict = run_post_soak_session_audit(args.session_dir, run_parity=not args.no_parity)

    report = analyze_soak_run(
        soak_dir=target,
        post_soak_health=health_dict,
        post_soak_parity=parity_dict,
        write_html=not args.no_html,
    )
    sys.exit(0 if report.get("overall_verdict") != "FAIL" else 1)


if __name__ == "__main__":
    main()
