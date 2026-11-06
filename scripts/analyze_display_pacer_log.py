#!/usr/bin/env python3
"""
analyze_display_pacer_log.py - Diagnostic Analyzer for RoadSense Display Pacer & UI Stutter

Parses Android Logcat or session_debug.log to analyze:
1. Physical USB Arrival Intervals (Δt_arrival) from the CP2105 transceiver.
2. RadarDisplayPacer Dispatch Intervals (Δt_emit) & Timer Wakeup Jitter.
3. Dropped Frame Forensics (frames pruned due to queue saturation).
4. Actual Canvas Render Intervals (Δt_draw) on both BEV Scope and SBS Viewfinder.
5. Display VSYNC Cadence Alignment (analyzing frame pacing against 90 Hz / 60 Hz refresh grids).

Usage:
    python scripts/analyze_display_pacer_log.py --file <path_to_log>
    python scripts/analyze_display_pacer_log.py --adb  (pulls directly from connected device)
"""

import sys
import os
import re
import argparse
import subprocess
import statistics

# Regex patterns for RoadSense diagnostic tags
RE_ENQUEUE = re.compile(r'\[ENQUEUE\]\s+seq=#(?P<seq>\d+)\s+\|\s+deltaArrMs=(?P<delta>[\d\.]+)\s+\|\s+qDepth=(?P<qdepth>\d+)')
RE_DROP = re.compile(r'\[DROP\]\s+seq=#(?P<seq>\d+)\s+\|\s+qDepth=(?P<qdepth>\d+)\s+\|\s+totalDrops=(?P<total>\d+)')
RE_EMIT = re.compile(r'\[EMIT\]\s+seq=#(?P<seq>\d+)\s+\|\s+deltaEmitMs=(?P<delta>[\d\.]+)\s+\|\s+jitterMs=(?P<jitter>[-\d\.]+)\s+\|\s+qDepth=(?P<qdepth>\d+)\s+\|\s+totalDrops=(?P<total>\d+)')
RE_STARVATION = re.compile(r'\[STARVATION\]\s+queue empty at tick\s+\|\s+holdSeq=#(?P<seq>[-\d]+)')
RE_DRAW_BEV = re.compile(r'\[DRAW_BEV\]\s+seq=#(?P<seq>[-\d]+)\s+\|\s+deltaDrawMs=(?P<delta>[\d\.]+)\s+\|\s+durationMs=(?P<dur>[\d\.]+)')
RE_DRAW_OVERLAY = re.compile(r'\[DRAW_OVERLAY\]\s+seq=#(?P<seq>[-\d]+)\s+\|\s+deltaDrawMs=(?P<delta>[\d\.]+)\s+\|\s+durationMs=(?P<dur>[\d\.]+)')

ADB_PATH = r"C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def pull_adb_logs() -> str:
    print("[*] Checking connected ADB devices...")
    cmd = [ADB_PATH, "devices"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    lines = [l.strip() for l in res.stdout.strip().splitlines() if l.strip()]
    devices = [l.split()[0] for l in lines[1:] if "device" in l]

    if not devices:
        print("[-] Error: No ADB device detected. Connect your phone via USB with USB debugging enabled.")
        sys.exit(1)

    device_id = devices[0]
    print(f"[+] Found device: {device_id}")

    print("[*] Pulling logcat buffer for DisplayPacer & Canvas diagnostics...")
    cmd_logcat = [ADB_PATH, "-s", device_id, "logcat", "-d", "-s", "DisplayPacer:D", "RadarBevPlot:D", "ViewfinderOverlay:D"]
    res_logcat = subprocess.run(cmd_logcat, capture_output=True, text=True)
    return res_logcat.stdout

def print_stats(name: str, values: list[float], unit: str = "ms"):
    if not values:
        print(f"  {name:28s}: No samples")
        return
    mean = statistics.mean(values)
    std = statistics.stdev(values) if len(values) > 1 else 0.0
    vmin = min(values)
    vmax = max(values)
    print(f"  {name:28s}: Mean = {mean:6.2f}{unit} | StdDev = {std:6.2f}{unit} | Min = {vmin:6.2f}{unit} | Max = {vmax:6.2f}{unit} (N={len(values)})")

def analyze_log_content(text: str, refresh_rate_hz: float = 90.0):
    enqueues = []
    emits = []
    drops = []
    starvations = []
    draw_bevs = []
    draw_overlays = []

    for line in text.splitlines():
        m_enq = RE_ENQUEUE.search(line)
        if m_enq:
            enqueues.append({
                'seq': int(m_enq.group('seq')),
                'deltaArrMs': float(m_enq.group('delta')),
                'qDepth': int(m_enq.group('qdepth'))
            })
            continue

        m_drop = RE_DROP.search(line)
        if m_drop:
            drops.append({
                'seq': int(m_drop.group('seq')),
                'qDepth': int(m_drop.group('qdepth')),
                'totalDrops': int(m_drop.group('total'))
            })
            continue

        m_emit = RE_EMIT.search(line)
        if m_emit:
            emits.append({
                'seq': int(m_emit.group('seq')),
                'deltaEmitMs': float(m_emit.group('delta')),
                'jitterMs': float(m_emit.group('jitter')),
                'qDepth': int(m_emit.group('qdepth')),
                'totalDrops': int(m_emit.group('total'))
            })
            continue

        m_starv = RE_STARVATION.search(line)
        if m_starv:
            starvations.append({
                'holdSeq': int(m_starv.group('seq'))
            })
            continue

        m_bev = RE_DRAW_BEV.search(line)
        if m_bev:
            draw_bevs.append({
                'seq': int(m_bev.group('seq')),
                'deltaDrawMs': float(m_bev.group('delta')),
                'durMs': float(m_bev.group('dur'))
            })
            continue

        m_ovl = RE_DRAW_OVERLAY.search(line)
        if m_ovl:
            draw_overlays.append({
                'seq': int(m_ovl.group('seq')),
                'deltaDrawMs': float(m_ovl.group('delta')),
                'durMs': float(m_ovl.group('dur'))
            })
            continue

    print("=" * 80)
    print(" ROADSENSE DISPLAY PACER & UI TIMING FORENSIC REPORT")
    print("=" * 80)
    print(f"Target Display Refresh Rate: {refresh_rate_hz:.1f} Hz (Period = {1000.0/refresh_rate_hz:.2f} ms per VSYNC)")
    print("-" * 80)

    # 1. USB Arrival Stats
    arr_deltas = [e['deltaArrMs'] for e in enqueues if e['deltaArrMs'] > 0]
    print(f"\n[1] PHYSICAL USB ARRIVAL TIMING (CP2105 -> Android Worker):")
    print_stats("USB Arrival Interval", arr_deltas)
    if arr_deltas:
        bimodal_70 = sum(1 for d in arr_deltas if 65.0 <= d <= 80.0)
        bimodal_28 = sum(1 for d in arr_deltas if 20.0 <= d <= 35.0)
        print(f"  Bimodal Delay Clusters     : 70ms Delay = {bimodal_70} ({bimodal_70/len(arr_deltas)*100:.1f}%) | 28ms Burst = {bimodal_28} ({bimodal_28/len(arr_deltas)*100:.1f}%)")

    # 2. Dropped Frames Forensic
    print(f"\n[2] DROPPED FRAMES FORENSIC (Queue Saturation Pruning):")
    print(f"  Total Frames Dropped       : {len(drops)}")
    if drops:
        drop_pct = (len(drops) / (len(enqueues) or 1)) * 100.0
        print(f"  Drop Ratio                 : {drop_pct:.2f}% of incoming radar frames were discarded!")
        sample_str = ', '.join(f"#{d['seq']}" for d in drops[:10])
        suffix = ' ...' if len(drops) > 10 else ''
        print(f"  Sample Dropped Sequences   : {sample_str}{suffix}")
    else:
        print("  Clean Zero Drops           : No frames were discarded by the pacer queue depth limit.")

    # 3. Pacer Dispatch Stats
    emit_deltas = [e['deltaEmitMs'] for e in emits if e['deltaEmitMs'] > 0]
    timer_jitters = [e['jitterMs'] for e in emits]
    print(f"\n[3] RADAR DISPLAY PACER CADENCE (Pacer Output -> StateFlow):")
    print_stats("Pacer Emission Interval", emit_deltas)
    print_stats("Timer Wakeup Jitter", timer_jitters)
    print(f"  Queue Starvation Ticks     : {len(starvations)} ticks where queue ran dry")

    # 4. Canvas Draw Call Timing
    bev_deltas = [b['deltaDrawMs'] for b in draw_bevs if b['deltaDrawMs'] > 0]
    bev_durs = [b['durMs'] for b in draw_bevs]
    print(f"\n[4] RADAR BEV SCOPE CANVAS (Radar Tab):")
    print_stats("Canvas Draw Interval", bev_deltas)
    print_stats("Draw Execution Duration", bev_durs)

    ovl_deltas = [o['deltaDrawMs'] for o in draw_overlays if o['deltaDrawMs'] > 0]
    ovl_durs = [o['durMs'] for o in draw_overlays]
    print(f"\n[5] SBS VIEWFINDER OVERLAY CANVAS (SBS Tab):")
    print_stats("Canvas Draw Interval", ovl_deltas)
    print_stats("Draw Execution Duration", ovl_durs)

    # 5. VSYNC Frame Analysis (90 Hz Grid)
    vsync_ms = 1000.0 / refresh_rate_hz
    print(f"\n[6] VSYNC CADENCE ALIGNMENT ({refresh_rate_hz:.0f} Hz Grid):")
    if bev_deltas:
        bev_vsync_counts = [round(d / vsync_ms) for d in bev_deltas]
        distribution = {}
        for c in bev_vsync_counts:
            distribution[c] = distribution.get(c, 0) + 1
        print("  Radar Tab VSYNC Frame Hold Distribution:")
        for cycle in sorted(distribution.keys()):
            cnt = distribution[cycle]
            dur_ms = cycle * vsync_ms
            pct = (cnt / len(bev_vsync_counts)) * 100.0
            print(f"    Hold {cycle:2d} VSYNCs ({dur_ms:5.1f} ms): {cnt:4d} frames ({pct:5.1f}%)")

    if ovl_deltas:
        ovl_vsync_counts = [round(d / vsync_ms) for d in ovl_deltas]
        distribution = {}
        for c in ovl_vsync_counts:
            distribution[c] = distribution.get(c, 0) + 1
        print("  SBS Tab VSYNC Frame Hold Distribution:")
        for cycle in sorted(distribution.keys()):
            cnt = distribution[cycle]
            dur_ms = cycle * vsync_ms
            pct = (cnt / len(ovl_vsync_counts)) * 100.0
            print(f"    Hold {cycle:2d} VSYNCs ({dur_ms:5.1f} ms): {cnt:4d} frames ({pct:5.1f}%)")

    print("\n" + "=" * 80)
    print(" SUMMARY DIAGNOSIS")
    print("=" * 80)
    if drops:
        print(f"[!] ROOT CAUSE IDENTIFIED: Pacer queue depth (maxQueueDepth={drops[0]['qDepth']}) is too shallow!")
        print(f"    {len(drops)} radar frames were dropped, cutting the effective frame rate and causing visible skips.")
    if timer_jitters and (max(map(abs, timer_jitters)) > 15.0):
        print(f"[!] MAIN THREAD CONTENTION IDENTIFIED: Timer wakeup jitter reached {max(map(abs, timer_jitters)):.1f} ms!")
        print(f"    The pacer was stalled by UI measurement/layout on Dispatchers.Main.")
    if not drops and (not timer_jitters or max(map(abs, timer_jitters)) <= 15.0):
        print("[+] PACER FUNCTIONING NORMALLY. Check VSYNC distribution for beat frequency judder.")
    print("=" * 80)

def main():
    parser = argparse.ArgumentParser(description="Analyze RoadSense Display Pacer logs")
    parser.add_argument("--file", "-f", help="Path to exported log file (session_debug.log or logcat.txt)")
    parser.add_argument("--adb", action="store_true", help="Dump logcat directly from connected ADB device")
    parser.add_argument("--refresh-rate", type=float, default=90.0, help="Display refresh rate in Hz (default: 90.0)")
    args = parser.parse_args()

    if args.file:
        if not os.path.exists(args.file):
            print(f"[-] File not found: {args.file}")
            sys.exit(1)
        with open(args.file, "rb") as f:
            raw = f.read()
        if raw.startswith(b'\xff\xfe') or raw.startswith(b'\xfe\xff'):
            content = raw.decode('utf-16', errors='replace')
        else:
            try:
                content = raw.decode('utf-8')
            except UnicodeDecodeError:
                content = raw.decode('latin1', errors='replace')
    elif args.adb:
        content = pull_adb_logs()
    else:
        # Check if an ADB device is connected
        try:
            content = pull_adb_logs()
        except SystemExit:
            print("[!] Please specify --file <path> or connect an ADB device.")
            sys.exit(1)

    analyze_log_content(content, refresh_rate_hz=args.refresh_rate)

if __name__ == "__main__":
    main()

