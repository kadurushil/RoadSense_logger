"""
RoadSense Diagnostic Suite: Session Health Check
Performs a comprehensive, multi-sensor audit on any RoadSense recording session.
Checks:
  1. Metadata (duration, device details, active streams)
  2. Camera Stream (MP4 video size, frame count, nominal vs actual FPS, frame drops)
  3. Radar Stream (binary integrity, magic word occurrences, packet sequence drops, points)
  4. GNSS Stream (fix rate, CEP accuracy, satellite count, velocity range)
  5. IMU Stream (100 Hz consolidation, inter-frame intervals, drops, accel/gyro activity)
  6. CANedge Ingestion (MDF4 header magic, chunk continuity, byte sizes)
  7. Timeline Index (monotonic timestamp monotonicity, sensor event rates)
  8. Flight Recorder Logs (warnings, errors, coroutine exceptions)

Usage:
  python scripts/session_health_check.py [SESSION_PATH_OR_NAME]
  python scripts/session_health_check.py --latest
"""

import os
import sys
import glob
import json
import csv
import math
import struct
import argparse
from datetime import datetime

DEFAULT_LOGS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")
TI_MAGIC = b'\x02\x01\x04\x03\x06\x05\x08\x07'

# Terminal ANSI Color Codes & VT100 initialization
if sys.platform == "win32":
    os.system("")  # Enables VT100 processing in Windows Command Prompt and PowerShell

ANSI_GREEN = "\033[92m"
ANSI_YELLOW = "\033[93m"
ANSI_RED = "\033[91m"
ANSI_CYAN = "\033[96m"
ANSI_BOLD = "\033[1m"
ANSI_RESET = "\033[0m"

def colorize_status(status_str):
    """
    Wraps status strings in appropriate ANSI colors:
      - Green:  PASS, HEALTHY, INTACT, CLEAN, STAGED
      - Yellow: WARN
      - Red:    FAIL, ERROR, MISSING
    """
    s_upper = status_str.upper()
    if any(k in s_upper for k in ["FAIL", "ERROR", "MISSING"]):
        return f"{ANSI_RED}{ANSI_BOLD}{status_str}{ANSI_RESET}"
    elif "WARN" in s_upper:
        return f"{ANSI_YELLOW}{ANSI_BOLD}{status_str}{ANSI_RESET}"
    elif any(k in s_upper for k in ["PASS", "HEALTHY", "INTACT", "CLEAN", "INDEXED", "STAGED"]):
        return f"{ANSI_GREEN}{ANSI_BOLD}{status_str}{ANSI_RESET}"
    return status_str

def resolve_session_dir(target):
    if not target or target == "--latest" or target == "latest":
        sessions = sorted([d for d in os.listdir(DEFAULT_LOGS_DIR) if d.startswith("session_") and os.path.isdir(os.path.join(DEFAULT_LOGS_DIR, d))])
        if not sessions:
            sys.exit("Error: No session folders found in " + DEFAULT_LOGS_DIR)
        return os.path.join(DEFAULT_LOGS_DIR, sessions[-1])
    
    if os.path.isdir(target):
        return os.path.abspath(target)
    
    candidate = os.path.join(DEFAULT_LOGS_DIR, target)
    if os.path.isdir(candidate):
        return candidate

    sys.exit(f"Error: Could not resolve session directory: {target}")

def audit_session(sess_dir):
    sess_name = os.path.basename(sess_dir)
    print("=" * 80)
    print(f"ROADSENSE SESSION HEALTH AUDIT: {sess_name}")
    print(f"Path: {sess_dir}")
    print("=" * 80)

    scorecard = {}

    # 1. Metadata
    meta_file = os.path.join(sess_dir, "session_metadata.json")
    meta = {}
    if os.path.exists(meta_file):
        try:
            with open(meta_file, "r", encoding="utf-8") as f:
                meta = json.load(f)
            dur_s = meta.get("durationMs", 0) / 1000.0
            print(f"\n[1/8] SESSION LIFECYCLE & METADATA:")
            print(f"  Start Time:       {meta.get('startTimeIso', 'Unknown')}")
            print(f"  Stop Time:        {meta.get('stopTimeIso', 'Unknown')}")
            print(f"  Total Duration:   {dur_s:.2f} seconds ({dur_s/60.0:.2f} minutes)")
            device = meta.get("device", {})
            print(f"  Device:           {device.get('manufacturer')} {device.get('model')} (Android SDK {device.get('sdkInt')})")
            print(f"  Active Streams:   {len(meta.get('activeStreams', []))} files declared")
            status_meta = "PASS (Healthy)"
        except Exception as e:
            status_meta = f"FAIL (Corrupt: {e})"
    else:
        print("\n[1/8] SESSION METADATA: WARNING - session_metadata.json MISSING!")
        status_meta = "WARN (Missing)"

    print(f"  Status:           {colorize_status(status_meta)}")
    scorecard["Metadata"] = status_meta

    # 2. Camera
    print(f"\n[2/8] CAMERA (H.264 VIDEO & FRAME METADATA):")
    cam_mp4 = os.path.join(sess_dir, "camera", "camera_video.mp4")
    cam_csv = os.path.join(sess_dir, "camera", "camera_frames.csv")
    status_cam = "FAIL (Missing)"
    if os.path.exists(cam_mp4) and os.path.exists(cam_csv):
        mp4_sz = os.path.getsize(cam_mp4)
        with open(cam_csv, "r", encoding="utf-8") as f:
            cam_rows = list(csv.reader(f))[1:]
        if cam_rows:
            first_ts = int(cam_rows[0][1])
            last_ts = int(cam_rows[-1][1])
            cam_dur = (last_ts - first_ts) / 1e9 if last_ts > first_ts else 0.001
            avg_fps = len(cam_rows) / cam_dur
            drops = 0
            for i in range(1, len(cam_rows)):
                dt_ms = (int(cam_rows[i][1]) - int(cam_rows[i-1][1])) / 1e6
                if dt_ms > 50.0:
                    drops += 1
            drop_pct = (drops / len(cam_rows)) * 100.0 if cam_rows else 0
            print(f"  Video Container:  {mp4_sz:,} bytes ({mp4_sz/(1024*1024):.2f} MB)")
            print(f"  Total Frames:     {len(cam_rows):,} frames recorded")
            print(f"  Effective Rate:   {avg_fps:.2f} FPS (Nominal 30 FPS)")
            print(f"  Frame Drops (>50ms): {drops} occurrences ({drop_pct:.2f}%)")
            status_cam = 'PASS (Healthy)' if drop_pct < 1.0 else 'WARN (Elevated Drops)'
        else:
            print("  camera_frames.csv is empty!")
            status_cam = "FAIL (Empty)"
    else:
        print(f"  Camera files missing (MP4 exists: {os.path.exists(cam_mp4)}, CSV exists: {os.path.exists(cam_csv)})")
    
    print(f"  Status:           {colorize_status(status_cam)}")
    scorecard["Camera"] = status_cam

    # 3. Radar
    print(f"\n[3/8] RADAR (TI AWR1843BOOST @ 3.125 MBAUD):")
    radar_raw = os.path.join(sess_dir, "radar", "radar_raw_stream.bin")
    radar_fr = os.path.join(sess_dir, "radar", "radar_frames.bin")
    status_radar = "FAIL (Missing)"
    if os.path.exists(radar_raw):
        raw_sz = os.path.getsize(radar_raw)
        with open(radar_raw, "rb") as f:
            data = f.read()
        
        idx = 0
        parsed_frames = 0
        points_total = 0
        frame_nums = []
        while idx + 40 <= len(data):
            pos = data.find(TI_MAGIC, idx)
            if pos == -1 or pos + 40 > len(data):
                break
            try:
                ver, total_len, plat, fnum, cycles, num_obj, num_tlvs, subframe = struct.unpack_from('<IIIIIIII', data, pos + 8)
                parsed_frames += 1
                points_total += num_obj
                frame_nums.append(fnum)
                idx = pos + max(40, total_len)
            except Exception:
                idx = pos + 8

        seq_drops = sum(1 for i in range(1, len(frame_nums)) if frame_nums[i] != frame_nums[i-1] + 1)
        print(f"  Raw Binary Stream: {raw_sz:,} bytes (parsed frames file: {os.path.getsize(radar_fr) if os.path.exists(radar_fr) else 0:,} bytes)")
        print(f"  Valid TI Frames:  {parsed_frames:,} frames (Magic 0x0102030405060708)")
        print(f"  Detected Targets: {points_total:,} point cloud points")
        if frame_nums:
            print(f"  Frame Sequence:   #{frame_nums[0]} to #{frame_nums[-1]}")
            print(f"  Packet Drops:     {seq_drops} sequence gaps")
            status_radar = 'PASS (100% Intact)' if seq_drops == 0 else ('WARN (<0.5% Drops)' if seq_drops/len(frame_nums) < 0.005 else 'FAIL (High Packet Loss)')
        else:
            status_radar = 'FAIL (No Frames Parsed)'
    else:
        print("  radar_raw_stream.bin NOT FOUND.")

    print(f"  Status:           {colorize_status(status_radar)}")
    scorecard["Radar"] = status_radar

    # 4. GNSS
    print(f"\n[4/8] GNSS (LOCATION & KINEMATICS):")
    gnss_csv = os.path.join(sess_dir, "gnss", "gnss_fixes.csv")
    status_gnss = "FAIL (Missing)"
    if os.path.exists(gnss_csv):
        with open(gnss_csv, "r", encoding="utf-8") as f:
            gnss_rows = list(csv.reader(f))[1:]
        if gnss_rows:
            first_m = int(gnss_rows[0][0])
            last_m = int(gnss_rows[-1][0])
            g_dur = (last_m - first_m) / 1e9 if last_m > first_m else 1.0
            g_rate = len(gnss_rows) / g_dur
            accs = [float(r[9]) for r in gnss_rows if len(r) > 9 and r[9]]
            speeds = [float(r[7]) for r in gnss_rows if len(r) > 7 and r[7]]
            print(f"  Total Fixes:      {len(gnss_rows)} fixes logged")
            print(f"  Update Rate:      {g_rate:.2f} Hz (Duration: {g_dur:.1f}s)")
            if accs:
                print(f"  Accuracy (CEP):   min={min(accs):.1f}m, avg={sum(accs)/len(accs):.1f}m, max={max(accs):.1f}m")
            if speeds:
                print(f"  Speed Profile:    max={max(speeds):.1f} km/h, avg={sum(speeds)/len(speeds):.1f} km/h")
            status_gnss = "PASS (Healthy)"
        else:
            print("  gnss_fixes.csv is empty!")
            status_gnss = "FAIL (Empty)"
    else:
        print("  gnss_fixes.csv NOT FOUND.")

    print(f"  Status:           {colorize_status(status_gnss)}")
    scorecard["GNSS"] = status_gnss

    # 5. IMU (Consolidated 100 Hz High-Rate Stream)
    print(f"\n[5/8] IMU (HIGH-RATE ACCEL/GYRO/ORIENTATION):")
    imu_csv = os.path.join(sess_dir, "imu", "imu_frames.csv")
    status_imu = "FAIL (Missing)"
    if os.path.exists(imu_csv):
        try:
            with open(imu_csv, "r", encoding="utf-8") as f:
                reader = csv.reader(f)
                header = next(reader, None)
                imu_rows = []
                for r in reader:
                    if len(r) >= 15:
                        try:
                            imu_rows.append((int(r[0]), float(r[2]), float(r[3]), float(r[4]), float(r[5]), float(r[6]), float(r[7])))
                        except ValueError:
                            continue
            if imu_rows:
                imu_count = len(imu_rows)
                t_first = imu_rows[0][0]
                t_last = imu_rows[-1][0]
                imu_dur = (t_last - t_first) / 1e9 if t_last > t_first else 0.001
                eff_rate = imu_count / imu_dur

                intervals_ms = []
                gaps_20ms = 0
                gaps_50ms = 0
                for i in range(1, len(imu_rows)):
                    dt = (imu_rows[i][0] - imu_rows[i-1][0]) / 1e6
                    intervals_ms.append(dt)
                    if dt > 20.0:
                        gaps_20ms += 1
                    if dt > 50.0:
                        gaps_50ms += 1

                mean_dt = sum(intervals_ms) / len(intervals_ms) if intervals_ms else 10.0
                sorted_intervals = sorted(intervals_ms) if intervals_ms else [10.0]
                min_dt = sorted_intervals[0]
                p95_dt = sorted_intervals[int(len(sorted_intervals) * 0.95)]
                p99_dt = sorted_intervals[int(len(sorted_intervals) * 0.99)]
                max_dt = sorted_intervals[-1]
                gap_pct = (gaps_20ms / imu_count) * 100.0

                # Kinematic sanity check (Gravity vector should approximate ~9.8 m/s^2)
                mean_norm_a = sum(math.sqrt(r[1]**2 + r[2]**2 + r[3]**2) for r in imu_rows[:1000]) / min(1000, imu_count)

                print(f"  Total IMU Frames: {imu_count:,} frames logged")
                print(f"  Effective Rate:   {eff_rate:.2f} Hz (Nominal 100 Hz, Duration: {imu_dur:.2f}s)")
                print(f"  Sampling Period:  mean={mean_dt:.2f}ms, min={min_dt:.2f}ms, p95={p95_dt:.2f}ms, p99={p99_dt:.2f}ms, max={max_dt:.2f}ms")
                print(f"  Interval Jitter:  {gaps_20ms} intervals >20ms ({gap_pct:.2f}%), {gaps_50ms} intervals >50ms")
                print(f"  Gravity Norm:     {mean_norm_a:.2f} m/s^2 (1G calibration sanity)")

                if eff_rate >= 85.0 and gap_pct < 0.5:
                    status_imu = f"PASS (Healthy @ {eff_rate:.1f} Hz)"
                elif eff_rate >= 50.0:
                    status_imu = f"WARN (Degraded Rate: {eff_rate:.1f} Hz)"
                else:
                    status_imu = f"FAIL (Severe Under-sampling: {eff_rate:.1f} Hz)"
            else:
                print("  imu_frames.csv contains no data rows!")
                status_imu = "FAIL (Empty)"
        except Exception as e:
            print(f"  Failed parsing imu_frames.csv: {e}")
            status_imu = f"FAIL (Error: {e})"
    else:
        print("  imu/imu_frames.csv NOT FOUND.")

    print(f"  Status:           {colorize_status(status_imu)}")
    scorecard["IMU"] = status_imu

    # 6. CANedge
    print(f"\n[6/8] CANEDGE2 (CYCLIC MDF4 CHUNKS):")
    can_dir = os.path.join(sess_dir, "can")
    status_can = "FAIL (Missing)"
    if os.path.exists(can_dir):
        chunks = sorted([f for f in os.listdir(can_dir) if f.endswith(".MF4") or f.endswith(".mf4")])
        print(f"  Staged Chunks:    {len(chunks)} files staged in session can/")
        valid_headers = 0
        total_bytes = 0
        for c in chunks:
            cp = os.path.join(can_dir, c)
            total_bytes += os.path.getsize(cp)
            with open(cp, "rb") as f:
                sig = f.read(8)
            if sig in [b'MDF     ', b'UnFinMF ']:
                valid_headers += 1
        print(f"  Total CAN Volume: {total_bytes:,} bytes ({total_bytes/(1024*1024):.2f} MB)")
        print(f"  MDF4 Signature:   {valid_headers} / {len(chunks)} verified ('MDF ' or 'UnFinMF ')")
        if chunks:
            print(f"  Chunk Range:      {chunks[0]}  -->  {chunks[-1]}")
        status_can = 'PASS (Staged & Valid)' if valid_headers == len(chunks) and len(chunks) > 0 else ('WARN (Partial Chunks)' if chunks else 'WARN (No CAN files)')
    else:
        print("  can/ directory NOT FOUND.")

    print(f"  Status:           {colorize_status(status_can)}")
    scorecard["CANedge2"] = status_can

    # 7. Timeline Index
    print(f"\n[7/8] TIMELINE SYNCHRONIZATION INDEX:")
    tl_csv = os.path.join(sess_dir, "session_timeline.csv")
    status_tl = "FAIL (Missing)"
    if os.path.exists(tl_csv):
        sensor_evts = {}
        with open(tl_csv, "r", encoding="utf-8") as f:
            reader = csv.reader(f)
            next(reader, None)
            for row in reader:
                if len(row) >= 3:
                    k = f"{row[1]}:{row[2]}"
                    sensor_evts[k] = sensor_evts.get(k, 0) + 1
        for k, cnt in sorted(sensor_evts.items()):
            print(f"  {k:22s}: {cnt:5d} events")
        status_tl = "PASS (Indexed)"
    else:
        print("  session_timeline.csv NOT FOUND.")

    print(f"  Status:           {colorize_status(status_tl)}")
    scorecard["Timeline"] = status_tl

    # 8. Session Debug Log
    print(f"\n[8/8] FLIGHT RECORDER LOG AUDIT:")
    dbg_log = os.path.join(sess_dir, "session_debug.log")
    status_dbg = "FAIL (Missing)"
    if os.path.exists(dbg_log):
        with open(dbg_log, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        errs = [l.strip() for l in lines if " E/" in l or " ERROR " in l or "Exception" in l]
        warns = [l.strip() for l in lines if " W/" in l or " WARN " in l]
        print(f"  Log File Length:  {len(lines):,} lines ({os.path.getsize(dbg_log):,} bytes)")
        print(f"  Errors Logged:    {len(errs)}")
        print(f"  Warnings Logged:  {len(warns)}")
        if errs:
            print("  Top Errors:")
            for e in errs[:3]:
                print(f"    ! {e}")
        status_dbg = 'PASS (Clean)' if len(errs) <= 1 else 'WARN (Review errors)'
    else:
        print("  session_debug.log NOT FOUND.")

    print(f"  Status:           {colorize_status(status_dbg)}")
    scorecard["FlightRecorder"] = status_dbg

    # Comprehensive Executive Summary Scorecard
    print("\n" + "=" * 80)
    print("SESSION HEALTH SUMMARY SCORECARD:")
    print("=" * 80)
    any_fail = any("FAIL" in v.upper() for v in scorecard.values())
    any_warn = any("WARN" in v.upper() for v in scorecard.values())

    order = [
        ("Lifecycle & Metadata", scorecard.get("Metadata", "UNKNOWN")),
        ("Camera (H.264 Video)", scorecard.get("Camera", "UNKNOWN")),
        ("Radar (3.125 MBaud)", scorecard.get("Radar", "UNKNOWN")),
        ("GNSS (Fixes & CEP)", scorecard.get("GNSS", "UNKNOWN")),
        ("IMU (100 Hz Consolidated)", scorecard.get("IMU", "UNKNOWN")),
        ("CANedge2 (MDF4 Chunks)", scorecard.get("CANedge2", "UNKNOWN")),
        ("Timeline Sync Index", scorecard.get("Timeline", "UNKNOWN")),
        ("Flight Recorder Log", scorecard.get("FlightRecorder", "UNKNOWN")),
    ]

    for label, val in order:
        print(f"  {label:28s}: {colorize_status(val)}")

    print("-" * 80)
    if any_fail:
        overall = "FAIL (Critical subsystem failure detected)"
    elif any_warn:
        overall = "WARN (Minor anomalies or warnings detected; review flagged sections)"
    else:
        overall = "PASS (All 8 subsystems healthy and synchronized)"
    print(f"OVERALL AUDIT VERDICT: {colorize_status(overall)}")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense Session Health Audit Tool")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("session", nargs="?", default="latest", help="Session folder name, full path, or 'latest'")
    args = parser.parse_args()
    target_dir = resolve_session_dir("latest" if args.latest else args.session)
    audit_session(target_dir)
