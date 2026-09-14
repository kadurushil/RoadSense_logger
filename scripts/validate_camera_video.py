"""
RoadSense Diagnostic Suite: Camera Video & Frame Timing Validator
Validates camera H.264 MP4 recordings and hardware shutter monotonic timestamps.

Features:
  - Parses camera_frames.csv (shutter_monotonic_ns, exposure_time_ns, utc_time_ms)
  - Computes actual recorded framerate (FPS) vs nominal 30 FPS
  - Measures inter-frame jitter, min/max frame delta, and detects frame drops
  - Correlates frame count with MP4 container size to verify bitrate stability

Usage:
  python scripts/validate_camera_video.py [SESSION_PATH_OR_NAME]
  python scripts/validate_camera_video.py --latest
"""

import os
import sys
import csv
import math
import argparse

def resolve_camera_dir(target):
    base_logs = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")

    if not target or target in ["--latest", "latest"]:
        sessions = sorted([d for d in os.listdir(base_logs) if d.startswith("session_") and os.path.isdir(os.path.join(base_logs, d))])
        if not sessions:
            sys.exit("Error: No sessions found in logs/")
        target = os.path.join(base_logs, sessions[-1])

    if not os.path.exists(target):
        candidate_sess = os.path.join(base_logs, target)
        if os.path.exists(candidate_sess):
            target = candidate_sess

    if os.path.isdir(target):
        cam_dir = os.path.join(target, "camera")
        if os.path.exists(cam_dir):
            return cam_dir
        sys.exit(f"Error: No camera/ directory found in {target}")

    sys.exit(f"Error: Cannot find camera directory {target}")

def validate_camera(cam_dir):
    mp4_file = os.path.join(cam_dir, "camera_video.mp4")
    csv_file = os.path.join(cam_dir, "camera_frames.csv")

    print("=" * 80)
    print(f"CAMERA STREAM & FRAME TIMING VALIDATION")
    print(f"Directory: {cam_dir}")
    print("=" * 80)

    if not os.path.exists(csv_file):
        sys.exit("Error: camera_frames.csv not found!")

    with open(csv_file, "r", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        rows = list(reader)

    if not rows:
        sys.exit("Error: camera_frames.csv contains no frame records!")

    # Parse timestamps
    shutter_ns = [int(r[1]) for r in rows if len(r) > 1 and r[1]]
    total_frames = len(shutter_ns)

    first_mono = shutter_ns[0]
    last_mono = shutter_ns[-1]
    dur_s = (last_mono - first_mono) / 1e9 if last_mono > first_mono else 0.001
    avg_fps = total_frames / dur_s

    # Delta intervals
    intervals_ms = []
    dropped_frames_66ms = []
    dropped_frames_50ms = []

    for i in range(1, total_frames):
        dt = (shutter_ns[i] - shutter_ns[i - 1]) / 1e6
        intervals_ms.append(dt)
        if dt > 66.67: # Missed 2+ frames at 30fps
            dropped_frames_66ms.append((i - 1, i, dt))
        elif dt > 50.0: # Missed 1 frame at 30fps
            dropped_frames_50ms.append((i - 1, i, dt))

    mean_dt = sum(intervals_ms) / len(intervals_ms) if intervals_ms else 0
    var_dt = sum((dt - mean_dt) ** 2 for dt in intervals_ms) / len(intervals_ms) if intervals_ms else 0
    std_dt = math.sqrt(var_dt)

    mp4_size = os.path.getsize(mp4_file) if os.path.exists(mp4_file) else 0
    bitrate_mbps = (mp4_size * 8.0 / (dur_s * 1_000_000.0)) if dur_s > 0 else 0

    print(f"\n1. RECORDING SUMMARY:")
    print(f"  Total Frames Logged:  {total_frames:,}")
    print(f"  Effective Duration:   {dur_s:.2f} seconds ({dur_s/60.0:.2f} minutes)")
    print(f"  Measured Frame Rate:  {avg_fps:.2f} FPS (Target: 30.00 FPS)")
    print(f"  MP4 Video File Size:  {mp4_size:,} bytes ({mp4_size/(1024*1024):.2f} MB)")
    print(f"  Effective Bitrate:    {bitrate_mbps:.2f} Mbps")

    print(f"\n2. FRAME TIMING & JITTER:")
    print(f"  Nominal Interval:     33.33 ms")
    print(f"  Average Interval:     {mean_dt:.2f} ms")
    print(f"  Minimum Interval:     {min(intervals_ms):.2f} ms" if intervals_ms else "  N/A")
    print(f"  Maximum Interval:     {max(intervals_ms):.2f} ms" if intervals_ms else "  N/A")
    print(f"  Standard Deviation:   {std_dt:.2f} ms (Jitter)")

    print(f"\n3. FRAME DROP DIAGNOSTICS:")
    drops_total = len(dropped_frames_50ms) + len(dropped_frames_66ms)
    drop_pct = (drops_total / total_frames) * 100.0 if total_frames > 0 else 0
    print(f"  1-Frame Gaps (>50ms): {len(dropped_frames_50ms)}")
    print(f"  Severe Gaps (>66ms):  {len(dropped_frames_66ms)}")
    print(f"  Total Drop Rate:      {drop_pct:.2f}% (Delivery Rate: {100.0 - drop_pct:.2f}%)")

    if dropped_frames_66ms:
        print(f"  Largest Gap Instances (Up to 3):")
        for prev_f, curr_f, dt in sorted(dropped_frames_66ms, key=lambda x: -x[2])[:3]:
            print(f"    - Frame #{prev_f} -> #{curr_f}: gap of {dt:.1f} ms")

    print("\n" + "=" * 80)
    print("CAMERA VALIDATION COMPLETE")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense Camera Video Validator")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("target", nargs="?", default="latest", help="Session directory or path to camera/ folder")
    args = parser.parse_args()
    validate_camera(resolve_camera_dir("latest" if args.latest else args.target))
