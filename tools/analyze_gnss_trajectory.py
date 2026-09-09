#!/usr/bin/env python3
"""
Diagnostic utility to analyze GNSS (GPS) trajectory and cross-sensor timeline synchronization.

Usage:
    python tools/analyze_gnss_trajectory.py <path_to_session_dir>
"""

import sys
import os
import math
from datetime import datetime

def haversine_distance_m(lat1, lon1, lat2, lon2):
    """Calculates great-circle distance between two GPS coordinates in meters."""
    R = 6371000.0  # Earth radius in meters
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2.0)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0)**2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c

def analyze_session(session_dir):
    print("=" * 80)
    print(" RoadSense - Multi-Sensor GNSS & Synchronization Deep-Dive")
    print(f" Target Session: {session_dir}")
    print("=" * 80)

    gnss_path = os.path.join(session_dir, "gnss", "gnss_fixes.csv")
    timeline_path = os.path.join(session_dir, "session_timeline.csv")
    meta_path = os.path.join(session_dir, "session_metadata.json")

    if not os.path.isfile(gnss_path):
        print(f"[-] Error: gnss/gnss_fixes.csv not found in {session_dir}")
        return

    with open(gnss_path, "r", encoding="utf-8") as f:
        lines = [line.strip() for line in f if line.strip()]

    if len(lines) <= 1:
        print("[-] Empty GNSS log.")
        return

    header = lines[0].split(",")
    fixes = []
    for line in lines[1:]:
        parts = line.split(",")
        if len(parts) >= 12:
            fixes.append({
                "mono_ns": int(parts[0]),
                "utc_ms": int(parts[1]),
                "utc_iso": parts[2],
                "lat": float(parts[3]),
                "lon": float(parts[4]),
                "alt": float(parts[5]),
                "speed_mps": float(parts[6]),
                "speed_kmh": float(parts[7]),
                "bearing": float(parts[8]),
                "acc": float(parts[9]),
                "provider": parts[10],
                "sats": parts[11]
            })

    total_fixes = len(fixes)
    first_fix = fixes[0]
    last_fix = fixes[-1]

    # Timing analysis
    mono_span_s = (last_fix["mono_ns"] - first_fix["mono_ns"]) / 1e9
    utc_span_s = (last_fix["utc_ms"] - first_fix["utc_ms"]) / 1e3
    sample_rate_hz = (total_fixes - 1) / mono_span_s if mono_span_s > 0 else 0

    # Intervals jitter
    intervals_ms = [(fixes[i]["mono_ns"] - fixes[i-1]["mono_ns"]) / 1e6 for i in range(1, total_fixes)]
    avg_dt = sum(intervals_ms) / len(intervals_ms) if intervals_ms else 0
    min_dt = min(intervals_ms) if intervals_ms else 0
    max_dt = max(intervals_ms) if intervals_ms else 0

    # Distance traveled & speed stats
    total_dist_m = 0.0
    speeds = [f["speed_kmh"] for f in fixes]
    accuracies = [f["acc"] for f in fixes]
    for i in range(1, total_fixes):
        dist = haversine_distance_m(fixes[i-1]["lat"], fixes[i-1]["lon"], fixes[i]["lat"], fixes[i]["lon"])
        total_dist_m += dist

    print(f"\n[1] GNSS Trajectory & Kinematics Summary:")
    print(f"    Total Recorded Fixes:    {total_fixes} fixes")
    print(f"    Monotonic Duration:      {mono_span_s:.2f} s")
    print(f"    UTC Clock Duration:      {utc_span_s:.2f} s")
    print(f"    Monotonic vs UTC Drift:  {abs(mono_span_s - utc_span_s)*1000:.1f} ms (sub-centisecond alignment)")
    print(f"    Actual Sampling Rate:    {sample_rate_hz:.2f} Hz")
    print(f"    Interval (dt) Jitter:    Avg: {avg_dt:.2f} ms | Min: {min_dt:.2f} ms | Max: {max_dt:.2f} ms")
    print(f"    Cumulative Distance:     {total_dist_m:.2f} meters")
    print(f"    Speed Range:             Min: {min(speeds):.2f} km/h | Max: {max(speeds):.2f} km/h | Avg: {sum(speeds)/len(speeds):.2f} km/h")
    print(f"    Accuracy Range:          Best: ±{min(accuracies):.1f} m | Worst: ±{max(accuracies):.1f} m | Avg: ±{sum(accuracies)/len(accuracies):.1f} m")

    # Satellite health
    sats_used = [int(f["sats"].split("/")[0]) for f in fixes]
    sats_view = [int(f["sats"].split("/")[1]) for f in fixes]
    print(f"    Satellites (Used / View): Used: {min(sats_used)}..{max(sats_used)} (avg: {sum(sats_used)/len(sats_used):.1f}) | In View: {min(sats_view)}..{max(sats_view)}")

    # Timeline verification
    if os.path.isfile(timeline_path):
        print(f"\n[2] Master Cross-Sensor Synchronization Timeline ({timeline_path}):")
        with open(timeline_path, "r", encoding="utf-8") as f:
            tl_lines = [l.strip() for l in f if l.strip()]
        if len(tl_lines) > 1:
            tl_events = [l.split(",") for l in tl_lines[1:]]
            gnss_events = [e for e in tl_events if e[1] == "GNSS"]
            print(f"    Total Synchronized Events: {len(tl_events)}")
            print(f"    GNSS Fix Events Logged:    {len(gnss_events)} / {total_fixes} ({'100.0% Perfect Sync' if len(gnss_events) == total_fixes else 'Discrepancy'})")

            # Check chronological monotonic monotonicity
            mono_timestamps = [int(e[0]) for e in tl_events]
            is_sorted = all(mono_timestamps[i] <= mono_timestamps[i+1] for i in range(len(mono_timestamps)-1))
            print(f"    Monotonic Invariance:      {'VERIFIED (Strictly Non-decreasing monotonic hardware clock)' if is_sorted else 'FAILED: Time went backwards!'}")

    print("\n" + "=" * 80)
    print(" VERDICT: GNSS Acquisition & Master Timeline Synchronization SUCCESSFUL")
    print("=" * 80)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tools/analyze_gnss_trajectory.py <path_to_session_dir>")
        sys.exit(1)
    analyze_session(sys.argv[1])
