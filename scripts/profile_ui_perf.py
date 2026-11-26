#!/usr/bin/env python3
"""
RoadSense Lightweight ADB UI Performance Profiler (Plan 113)
==============================================================

Fast 30-second A/B iteration harness for Android UI Frame Pacing & Recomposition attribution:
  1. Auto-arms `DebugToggles.uiPerfProfilingLogs` via debug ADB broadcast (restores on exit)
  2. Clears logcat & resets `dumpsys gfxinfo`, streams `UiPerf` + `RecompAudit` + `DisplayPacer`
  3. Pulls latest `app_system.log` and active/latest `session_debug.log` from device storage
  4. Computes steady-state percentiles (P50, P95, animP95, drawP95, gpuP95), inter-jank period
     histograms (~200ms vs ~300ms spikes), and per-composable `[RECOMP_5S]` counts
  5. Supports `--compare <before_dir> <after_dir>` across both `logs/ui_profiles/*` and `logs/soak_runs/*`

Usage:
  python scripts/profile_ui_perf.py --label radar_opt
  python scripts/profile_ui_perf.py --duration 45 --label radar_after
  python scripts/profile_ui_perf.py --compare logs/soak_runs/soak_20261126_111051 logs/ui_profiles/profile_radar_after_...
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
from datetime import datetime
from typing import Any, Dict, List, Optional

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from scripts.run_soak_test import (
    PACKAGE_NAME,
    adb_run,
    find_adb,
    list_remote_sessions,
    resolve_device_serial,
)
from scripts.soak_analyzer import parse_gfxinfo, parse_uiperf_window

DEFAULT_PROFILES_ROOT = os.path.join(REPO_ROOT, "logs", "ui_profiles")
DEVICE_FILES_DIR = f"/sdcard/Android/data/{PACKAGE_NAME}/files"
ACTION_SET_UI_PROFILING = f"{PACKAGE_NAME}.action.SET_UI_PROFILING"


def _set_ui_profiling_toggle(adb_path: str, serial: str, enabled: bool) -> None:
    adb_run(
        adb_path,
        [
            "shell",
            "am",
            "broadcast",
            "-a",
            ACTION_SET_UI_PROFILING,
            "-p",
            PACKAGE_NAME,
            "--ez",
            "enabled",
            "true" if enabled else "false",
        ],
        serial=serial,
        timeout=5,
    )


def _pull_latest_device_logs(adb_path: str, serial: str, out_dir: str) -> Dict[str, Optional[str]]:
    pulled: Dict[str, Optional[str]] = {"app_system_log": None, "session_debug_log": None}

    rc_app, out_app, _ = adb_run(
        adb_path,
        ["shell", f"ls -1t {DEVICE_FILES_DIR}/app_logs 2>/dev/null"],
        serial=serial,
        timeout=5,
    )
    if rc_app == 0:
        runs = [l.strip() for l in out_app.splitlines() if l.strip().startswith("app_run_")]
        if runs:
            remote_app_log = f"{DEVICE_FILES_DIR}/app_logs/{runs[0]}/app_system.log"
            local_app_log = os.path.join(out_dir, "app_system.log")
            rc_pull, _, _ = adb_run(adb_path, ["pull", remote_app_log, local_app_log], serial=serial, timeout=15)
            if rc_pull == 0 and os.path.isfile(local_app_log):
                pulled["app_system_log"] = local_app_log

    sessions = list_remote_sessions(adb_path, serial)
    if sessions:
        latest_sess = sessions[-1]
        remote_sess_log = f"{DEVICE_FILES_DIR}/sessions/{latest_sess}/session_debug.log"
        local_sess_log = os.path.join(out_dir, "session_debug.log")
        rc_pull, _, _ = adb_run(adb_path, ["pull", remote_sess_log, local_sess_log], serial=serial, timeout=15)
        if rc_pull == 0 and os.path.isfile(local_sess_log):
            pulled["session_debug_log"] = local_sess_log

    return pulled


def _parse_jank_line(line: str) -> Optional[Dict[str, float]]:
    if "[JANK_FRAME]" not in (line or ""):
        return None
    m = re.search(
        r"(\d\d:\d\d:\d\d\.\d+).*total=([0-9.]+)ms\s*\|\s*anim=([0-9.]+)ms\s*\|\s*layout=([0-9.]+)ms\s*\|\s*draw=([0-9.]+)ms\s*\|\s*sync=([0-9.]+)ms\s*\|\s*gpu=([0-9.]+)ms\s*\|\s*unk=([0-9.]+)ms\s*\|\s*swap=([0-9.]+)ms",
        line,
    )
    if not m:
        return None
    hh, mm, ss = m.group(1).split(":")
    ts_sec = int(hh) * 3600 + int(mm) * 60 + float(ss)
    return {
        "ts_sec": ts_sec,
        "total_ms": float(m.group(2)),
        "anim_ms": float(m.group(3)),
        "layout_ms": float(m.group(4)),
        "draw_ms": float(m.group(5)),
        "sync_ms": float(m.group(6)),
        "gpu_ms": float(m.group(7)),
        "unk_ms": float(m.group(8)),
        "swap_ms": float(m.group(9)),
    }


def _parse_recomp_line(line: str) -> Optional[Dict[str, int]]:
    if "[RECOMP_5S]" not in (line or ""):
        return None
    idx = line.find("[RECOMP_5S]")
    body = line[idx + len("[RECOMP_5S]") :].strip()
    out: Dict[str, int] = {}
    for pair in body.split("|"):
        pair = pair.strip()
        if "=" in pair:
            k, v = pair.split("=", 1)
            k = k.strip()
            v = v.strip()
            if v.isdigit():
                out[k] = int(v)
    return out or None


def analyze_profile_dir(target_dir: str) -> Dict[str, Any]:
    """
    Parses `ui_logcat.log` (or `soak_logcat.log`) + optional `gfxinfo.txt` in `target_dir`
    into structured Frame Pacing, Phase Breakdown, Period Histogram, and RecompAudit metrics.
    """
    log_path = None
    for cand in ("ui_logcat.log", "soak_logcat.log"):
        p = os.path.join(target_dir, cand)
        if os.path.isfile(p):
            log_path = p
            break
    if log_path is None:
        raise FileNotFoundError(f"No ui_logcat.log or soak_logcat.log found in {target_dir}")

    windows: List[Dict[str, float]] = []
    janks: List[Dict[str, float]] = []
    recomp_windows: List[Dict[str, int]] = []
    display_pacer_drops = 0

    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            w = parse_uiperf_window(line)
            if w is not None:
                windows.append(w)
                continue
            j = _parse_jank_line(line)
            if j is not None:
                janks.append(j)
                continue
            r = _parse_recomp_line(line)
            if r is not None:
                recomp_windows.append(r)
                continue
            if "DisplayPacer" in line and ("[DROP]" in line or "[BLOAT_TRIM]" in line):
                display_pacer_drops += 1

    # Skip first warmup window when >= 4 windows exist so steady-state reflects settled UI
    steady_windows = windows[1:] if len(windows) >= 4 else windows

    def _med(vals: List[float]) -> Optional[float]:
        return round(statistics.median(vals), 2) if vals else None

    # Handle wrap-around midnight if needed for dt calculations
    dts_ms: List[float] = []
    for i in range(1, len(janks)):
        dt = (janks[i]["ts_sec"] - janks[i - 1]["ts_sec"]) * 1000.0
        if dt < 0:
            dt += 86400_000.0
        dts_ms.append(round(dt, 1))

    period_buckets = {"<35ms": 0, "35-70ms": 0, "70-150ms": 0, "150-240ms": 0, "240-360ms": 0, ">360ms": 0}
    for d in dts_ms:
        if d < 35:
            period_buckets["<35ms"] += 1
        elif d < 70:
            period_buckets["35-70ms"] += 1
        elif d < 150:
            period_buckets["70-150ms"] += 1
        elif d < 240:
            period_buckets["150-240ms"] += 1
        elif d < 360:
            period_buckets["240-360ms"] += 1
        else:
            period_buckets[">360ms"] += 1

    hi_draw = [j for j in janks if j["draw_ms"] >= 6.0]
    hi_draw_period_ms: Optional[float] = None
    if len(hi_draw) >= 4:
        hd_dts = []
        for i in range(1, len(hi_draw)):
            dt = (hi_draw[i]["ts_sec"] - hi_draw[i - 1]["ts_sec"]) * 1000.0
            if dt < 0:
                dt += 86400_000.0
            hd_dts.append(dt)
        hi_draw_period_ms = round(statistics.median(hd_dts), 1)

    recomp_summary: Dict[str, Dict[str, float]] = {}
    if recomp_windows:
        all_keys = sorted({k for rw in recomp_windows for k in rw.keys()})
        steady_recomp = recomp_windows[1:] if len(recomp_windows) >= 4 else recomp_windows
        for k in all_keys:
            vals = [rw[k] for rw in steady_recomp if k in rw]
            if vals:
                recomp_summary[k] = {
                    "avg_per_5s": round(sum(vals) / len(vals), 1),
                    "max_per_5s": max(vals),
                }

    gfx_summary = {}
    gfx_path = os.path.join(target_dir, "gfxinfo.txt")
    if os.path.isfile(gfx_path):
        with open(gfx_path, "r", encoding="utf-8", errors="replace") as gf:
            gfx_summary = parse_gfxinfo(gf.read())

    p95_med = _med([w["p95_ms"] for w in steady_windows])
    verdict = "PASS" if (p95_med is not None and p95_med <= 16.67) else ("WARN" if (p95_med is not None and p95_med <= 22.0) else "FAIL")

    summary = {
        "dir": os.path.abspath(target_dir),
        "verdict": verdict,
        "window_count": len(windows),
        "steady_window_count": len(steady_windows),
        "p50_median_ms": _med([w["p50_ms"] for w in steady_windows]),
        "p95_median_ms": p95_med,
        "p95_max_ms": round(max((w["p95_ms"] for w in windows), default=0.0), 2) if windows else None,
        "jank_pct_median": _med([w["jank_pct"] for w in steady_windows]),
        "anim_p50_median_ms": _med([w["anim_p50_ms"] for w in steady_windows if "anim_p50_ms" in w]),
        "anim_p95_median_ms": _med([w["anim_p95_ms"] for w in steady_windows if "anim_p95_ms" in w]),
        "draw_p50_median_ms": _med([w["draw_p50_ms"] for w in steady_windows if "draw_p50_ms" in w]),
        "draw_p95_median_ms": _med([w["draw_p95_ms"] for w in steady_windows if "draw_p95_ms" in w]),
        "gpu_p95_median_ms": _med([w["gpu_p95_ms"] for w in steady_windows if "gpu_p95_ms" in w]),
        "swap_p95_median_ms": _med([w["swap_p95_ms"] for w in steady_windows if "swap_p95_ms" in w]),
        "jank_frames_logged": len(janks),
        "high_draw_jank_count": len(hi_draw),
        "high_draw_period_median_ms": hi_draw_period_ms,
        "jank_anim_median_ms": _med([j["anim_ms"] for j in janks]),
        "jank_draw_median_ms": _med([j["draw_ms"] for j in janks]),
        "period_buckets": period_buckets,
        "recomp_summary": recomp_summary,
        "display_pacer_drops": display_pacer_drops,
        "gfxinfo": gfx_summary,
    }
    return summary


def print_profile_report(summary: Dict[str, Any]) -> None:
    print("\n" + "=" * 80)
    print(f"ROADSENSE UI PERFORMANCE PROFILE SUMMARY  [{summary['verdict']}]")
    print("=" * 80)
    print(f"  Directory         : {summary['dir']}")
    print(f"  5s Windows        : {summary['window_count']} total ({summary['steady_window_count']} steady-state)")
    print(f"  Total P50 / P95   : {summary['p50_median_ms']} ms  /  {summary['p95_median_ms']} ms  (Max Window P95: {summary['p95_max_ms']} ms)")
    print(f"  Jank % (Median)   : {summary['jank_pct_median']}%")
    print(f"  Anim P50 / P95    : {summary['anim_p50_median_ms']} ms  /  {summary['anim_p95_median_ms']} ms")
    print(f"  Draw P50 / P95    : {summary['draw_p50_median_ms']} ms  /  {summary['draw_p95_median_ms']} ms")
    print(f"  GPU / Swap P95    : {summary['gpu_p95_median_ms']} ms  /  {summary['swap_p95_median_ms']} ms")
    print(f"  DisplayPacer Drops: {summary['display_pacer_drops']}")
    if summary["jank_frames_logged"] > 0:
        print(f"  [JANK_FRAME] >24ms: {summary['jank_frames_logged']} frames (hi-draw={summary['high_draw_jank_count']}, median period={summary['high_draw_period_median_ms']} ms)")
        print(f"  Inter-Jank Periods: {summary['period_buckets']}")
    if summary["recomp_summary"]:
        print("-" * 80)
        print("  RecompAudit (Steady-State Mean / Max per 5s Window):")
        for tag, stats in summary["recomp_summary"].items():
            print(f"    {tag:20s}: avg={stats['avg_per_5s']:5.1f} / 5s  (max={stats['max_per_5s']})")
    print("=" * 80)


def compare_profiles(before_dir: str, after_dir: str) -> None:
    a = analyze_profile_dir(before_dir)
    b = analyze_profile_dir(after_dir)

    print("\n" + "=" * 88)
    print("ROADSENSE UI PERFORMANCE A/B COMPARISON")
    print("=" * 88)
    print(f"  Baseline : {os.path.basename(a['dir'])}")
    print(f"  After    : {os.path.basename(b['dir'])}")
    print("-" * 88)
    print(f"  {'Metric':<28s} {'Baseline':>14s} {'After':>14s} {'Delta':>18s}")
    print("-" * 88)

    rows = [
        ("Total P50 (ms)", a["p50_median_ms"], b["p50_median_ms"], "ms"),
        ("Total P95 (ms) [<=16.67]", a["p95_median_ms"], b["p95_median_ms"], "ms"),
        ("Max Window P95 (ms)", a["p95_max_ms"], b["p95_max_ms"], "ms"),
        ("Jank % (Median)", a["jank_pct_median"], b["jank_pct_median"], "%"),
        ("Anim P95 (ms)", a["anim_p95_median_ms"], b["anim_p95_median_ms"], "ms"),
        ("Draw P95 (ms)", a["draw_p95_median_ms"], b["draw_p95_median_ms"], "ms"),
        ("GPU P95 (ms)", a["gpu_p95_median_ms"], b["gpu_p95_median_ms"], "ms"),
        ("DisplayPacer Drops", float(a["display_pacer_drops"]), float(b["display_pacer_drops"]), ""),
    ]
    for label, va, vb, unit in rows:
        sa = f"{va:.2f}{unit}" if va is not None else "N/A"
        sb = f"{vb:.2f}{unit}" if vb is not None else "N/A"
        if va is not None and vb is not None:
            diff = vb - va
            pct = (diff / va * 100.0) if va != 0 else 0.0
            sd = f"{diff:+.2f}{unit} ({pct:+.0f}%)"
        else:
            sd = "-"
        print(f"  {label:<28s} {sa:>14s} {sb:>14s} {sd:>18s}")
    print("=" * 88)


def capture_live_profile(args: argparse.Namespace) -> int:
    adb_path = find_adb()
    if not adb_path:
        print("[!] Could not locate adb.exe.")
        return 1

    serial = resolve_device_serial(adb_path, args.serial)
    rc_pid, pid_out, _ = adb_run(adb_path, ["shell", f"pidof {PACKAGE_NAME}"], serial=serial, timeout=5)
    if rc_pid != 0 or not pid_out.strip():
        print(f"[!] {PACKAGE_NAME} is not running on {serial}. Open RoadSense first.")
        return 1
    pid = pid_out.strip().split()[0]

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_label = re.sub(r"[^A-Za-z0-9_\-]", "_", args.label or "run")
    out_dir = os.path.join(DEFAULT_PROFILES_ROOT, f"profile_{safe_label}_{stamp}")
    os.makedirs(out_dir, exist_ok=True)
    log_path = os.path.join(out_dir, "ui_logcat.log")

    print("=" * 80)
    print(f" ROADSENSE ADB UI PERFORMANCE PROFILER  (Duration={args.duration}s, PID={pid})")
    print("=" * 80)
    print(f"  ADB Target : {serial}")
    print(f"  Output Dir : {out_dir}")

    if not args.no_toggle:
        _set_ui_profiling_toggle(adb_path, serial, True)
        print("  Profiling  : Armed DebugToggles.uiPerfProfilingLogs=true via ADB")
    else:
        print("  Profiling  : --no-toggle mode (reading [WINDOW_5S] + gfxinfo passively)")

    adb_run(adb_path, ["logcat", "-c"], serial=serial, timeout=5)
    adb_run(adb_path, ["shell", f"dumpsys gfxinfo {PACKAGE_NAME} reset"], serial=serial, timeout=5)

    cmd = [
        adb_path,
        "-s",
        serial,
        "logcat",
        "-v",
        "time",
        "UiPerf:I",
        "RecompAudit:I",
        "DisplayPacer:W",
        "RadarBevPlot:D",
        "MainActivity:I",
        "*:S",
    ]

    log_proc = subprocess.Popen(
        cmd,
        stdout=open(log_path, "w", encoding="utf-8", errors="replace"),
        stderr=subprocess.STDOUT,
    )

    try:
        t0 = time.monotonic()
        while (time.monotonic() - t0) < args.duration:
            rem = int(max(0.0, args.duration - (time.monotonic() - t0)))
            print(f"\r[*] Capturing live UI telemetry... {rem:3d}s remaining ", end="", flush=True)
            time.sleep(min(2.0, max(0.2, args.duration - (time.monotonic() - t0))))
        print("\r[+] Capture window finished. Flushing buffers & pulling device logs...       ", flush=True)
    except KeyboardInterrupt:
        print("\n[!] Interrupted early — analyzing captured samples...")
    finally:
        try:
            log_proc.terminate()
            log_proc.wait(timeout=3)
        except Exception:
            log_proc.kill()
        if not args.no_toggle:
            _set_ui_profiling_toggle(adb_path, serial, False)

    _, gfx_out, _ = adb_run(adb_path, ["shell", f"dumpsys gfxinfo {PACKAGE_NAME}"], serial=serial, timeout=8)
    with open(os.path.join(out_dir, "gfxinfo.txt"), "w", encoding="utf-8") as gf:
        gf.write(gfx_out or "")

    pulled = _pull_latest_device_logs(adb_path, serial, out_dir)
    if pulled["app_system_log"]:
        print(f"  Pulled     : app_system.log")
    if pulled["session_debug_log"]:
        print(f"  Pulled     : session_debug.log")

    summary = analyze_profile_dir(out_dir)
    summary["pulled_logs"] = pulled
    with open(os.path.join(out_dir, "profile_summary.json"), "w", encoding="utf-8") as sf:
        json.dump(summary, sf, indent=2)

    print_profile_report(summary)

    if args.baseline:
        compare_profiles(args.baseline, out_dir)

    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="RoadSense 30s ADB UI Frame Pacing & Recomposition Profiler")
    parser.add_argument("--duration", type=float, default=30.0, help="Capture window in seconds (default: 30)")
    parser.add_argument("--label", type=str, default="radar", help="Run label for output directory")
    parser.add_argument("--serial", type=str, default=None, help="ADB serial or wireless IP:port")
    parser.add_argument("--no-toggle", action="store_true", help="Do not send debug broadcast (useful for Release APKs)")
    parser.add_argument("--baseline", type=str, default=None, help="Optional baseline profile or soak directory to compare against")
    parser.add_argument("--compare", nargs=2, metavar=("BEFORE_DIR", "AFTER_DIR"), help="Compare two existing profile/soak directories and exit")
    args = parser.parse_args()

    if args.compare:
        compare_profiles(args.compare[0], args.compare[1])
        return

    sys.exit(capture_live_profile(args))


if __name__ == "__main__":
    main()
