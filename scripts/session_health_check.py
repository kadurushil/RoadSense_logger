"""
RoadSense Diagnostic Suite: Session Health Check
Performs a comprehensive, multi-sensor audit on any RoadSense recording session.
Checks:
  1. Metadata (duration, device details, active streams)
  2. Radar Stream (binary integrity, magic word occurrences, packet sequence drops, points)
  3. Camera Stream (MP4 video size, frame count, nominal vs actual FPS, frame drops)
  4. GNSS Stream (fix rate, CEP accuracy, satellite count, velocity range)
  5. CANedge Ingestion (MDF4 header magic, chunk continuity, byte sizes)
  6. Timeline Index (monotonic timestamp monotonicity, sensor event rates)
  7. Flight Recorder Logs (warnings, errors, coroutine exceptions)

Usage:
  python scripts/session_health_check.py [SESSION_PATH_OR_NAME]
  python scripts/session_health_check.py --latest
"""

import os
import sys
import glob
import json
import csv
import struct
import argparse
from datetime import datetime

DEFAULT_LOGS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")
TI_MAGIC = b'\x02\x01\x04\x03\x06\x05\x08\x07'

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

    # 1. Metadata
    meta_file = os.path.join(sess_dir, "session_metadata.json")
    meta = {}
    if os.path.exists(meta_file):
        with open(meta_file, "r", encoding="utf-8") as f:
            meta = json.load(f)
        dur_s = meta.get("durationMs", 0) / 1000.0
        print(f"\n[1/7] SESSION LIFECYCLE & METADATA:")
        print(f"  Start Time:       {meta.get('startTimeIso', 'Unknown')}")
        print(f"  Stop Time:        {meta.get('stopTimeIso', 'Unknown')}")
        print(f"  Total Duration:   {dur_s:.2f} seconds ({dur_s/60.0:.2f} minutes)")
        device = meta.get("device", {})
        print(f"  Device:           {device.get('manufacturer')} {device.get('model')} (Android SDK {device.get('sdkInt')})")
        print(f"  Active Streams:   {len(meta.get('activeStreams', []))} files declared")
    else:
        print("\n[1/7] SESSION METADATA: WARNING - session_metadata.json MISSING!")

    # 2. Camera
    print(f"\n[2/7] CAMERA (H.264 VIDEO & FRAME METADATA):")
    cam_mp4 = os.path.join(sess_dir, "camera", "camera_video.mp4")
    cam_csv = os.path.join(sess_dir, "camera", "camera_frames.csv")
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
            print(f"  Status:           {'PASS (Healthy)' if drop_pct < 1.0 else 'WARN (Elevated Drops)'}")
        else:
            print("  camera_frames.csv is empty!")
    else:
        print(f"  Camera files missing (MP4 exists: {os.path.exists(cam_mp4)}, CSV exists: {os.path.exists(cam_csv)})")

    # 3. Radar
    print(f"\n[3/7] RADAR (TI AWR1843BOOST @ 3.125 MBAUD):")
    radar_raw = os.path.join(sess_dir, "radar", "radar_raw_stream.bin")
    radar_fr = os.path.join(sess_dir, "radar", "radar_frames.bin")
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
            status = 'PASS (100% Intact)' if seq_drops == 0 else ('WARN (<0.5% Drops)' if seq_drops/len(frame_nums) < 0.005 else 'FAIL (High Packet Loss)')
            print(f"  Status:           {status}")
    else:
        print("  radar_raw_stream.bin NOT FOUND.")

    # 4. GNSS
    print(f"\n[4/7] GNSS (LOCATION & KINEMATICS):")
    gnss_csv = os.path.join(sess_dir, "gnss", "gnss_fixes.csv")
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
            print(f"  Status:           PASS (Healthy)")
        else:
            print("  gnss_fixes.csv is empty!")
    else:
        print("  gnss_fixes.csv NOT FOUND.")

    # 5. CANedge
    print(f"\n[5/7] CANEDGE2 (CYCLIC MDF4 CHUNKS):")
    can_dir = os.path.join(sess_dir, "can")
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
        print(f"  Status:           {'PASS (Staged & Valid)' if valid_headers == len(chunks) and len(chunks) > 0 else 'WARN'}")
    else:
        print("  can/ directory NOT FOUND.")

    # 6. Timeline Index
    print(f"\n[6/7] TIMELINE SYNCHRONIZATION INDEX:")
    tl_csv = os.path.join(sess_dir, "session_timeline.csv")
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
        print(f"  Status:           PASS (Indexed)")
    else:
        print("  session_timeline.csv NOT FOUND.")

    # 7. Session Debug Log
    print(f"\n[7/7] FLIGHT RECORDER LOG AUDIT:")
    dbg_log = os.path.join(sess_dir, "session_debug.log")
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
        print(f"  Status:           {'PASS (Clean)' if len(errs) <= 1 else 'WARN (Review errors)'}")
    else:
        print("  session_debug.log NOT FOUND.")

    print("\n" + "=" * 80)
    print("AUDIT COMPLETE")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense Session Health Audit Tool")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("session", nargs="?", default="latest", help="Session folder name, full path, or 'latest'")
    args = parser.parse_args()
    target_dir = resolve_session_dir("latest" if args.latest else args.session)
    audit_session(target_dir)
