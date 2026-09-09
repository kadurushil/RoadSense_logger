#!/usr/bin/env python3
"""
Diagnostic utility to inspect structured RoadSense recording sessions.
Parses session metadata and timestamped framed radar packets (radar_frames.bin).

Usage:
    python tools/inspect_session.py <path_to_session_dir_or_radar_frames.bin>
"""

import sys
import os
import struct
import json
from datetime import datetime

ROAD_MAGIC = b"ROAD"
ROAD_HEADER_SIZE = 24  # 4s (ROAD) + Q (monoNs) + Q (wallMs) + I (packetLen)

TI_MAGIC = bytes([0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07])
TI_HEADER_SIZE = 40

def inspect_session(target_path):
    session_dir = None
    frames_file = None
    metadata_file = None

    if os.path.isdir(target_path):
        session_dir = target_path
        metadata_file = os.path.join(session_dir, "session_metadata.json")
        frames_file = os.path.join(session_dir, "radar", "radar_frames.bin")
        if not os.path.isfile(frames_file):
            # Check direct dir
            frames_file = os.path.join(session_dir, "radar_frames.bin")
    elif os.path.isfile(target_path):
        frames_file = target_path
        parent = os.path.dirname(os.path.abspath(target_path))
        candidate_meta = os.path.join(os.path.dirname(parent), "session_metadata.json")
        if os.path.isfile(candidate_meta):
            metadata_file = candidate_meta
            session_dir = os.path.dirname(parent)
        elif os.path.isfile(os.path.join(parent, "session_metadata.json")):
            metadata_file = os.path.join(parent, "session_metadata.json")
            session_dir = parent

    print("=" * 75)
    print(" RoadSense - Multi-Sensor Session Inspector")
    print("=" * 75)

    if metadata_file and os.path.isfile(metadata_file):
        print(f"\n[+] Session Metadata ({metadata_file}):")
        try:
            with open(metadata_file, "r") as mf:
                meta = json.load(mf)
            print(f"    Session ID:       {meta.get('sessionId')}")
            print(f"    Start Time:       {meta.get('startTimeIso')}")
            print(f"    Stop Time:        {meta.get('stopTimeIso', 'Active / Unclosed')}")
            dur = meta.get('durationMs')
            if dur:
                print(f"    Duration:         {dur / 1000:.2f} s")
            dev = meta.get('device', {})
            print(f"    Device:           {dev.get('manufacturer', '')} {dev.get('model', '')} (API {dev.get('sdkInt', '')})")
            gnss_meta = meta.get('gnss', {})
            if gnss_meta:
                print(f"    GNSS Metadata:    {gnss_meta.get('sensor', 'GNSS')} | Total Fixes: {gnss_meta.get('totalFixes', 0):,}")
            rad = meta.get('radar', {})
            print(f"    Radar Metadata:   {rad.get('sensor', '')} @ {rad.get('baudRateDataPort', '')} baud")
            print(f"    Total Frames:     {rad.get('totalFrames', 0):,}")
            print(f"    Total Bytes:      {rad.get('totalBytes', 0):,} B")
            print(f"    Active Streams:   {', '.join(meta.get('activeStreams', []))}")
        except Exception as e:
            print(f"    [!] Error parsing metadata: {e}")
    else:
        print("\n[-] No session_metadata.json found in directory hierarchy.")

    # Inspect GNSS fixes if present
    gnss_csv = None
    if session_dir:
        candidate_gnss = os.path.join(session_dir, "gnss", "gnss_fixes.csv")
        if os.path.isfile(candidate_gnss):
            gnss_csv = candidate_gnss

    if gnss_csv:
        print(f"\n[+] GNSS Location Stream ({gnss_csv}):")
        try:
            with open(gnss_csv, "r", encoding="utf-8") as gf:
                lines = [line.strip() for line in gf if line.strip()]
            if len(lines) > 1:
                header = lines[0].split(",")
                fixes = [line.split(",") for line in lines[1:]]
                print(f"    Total GNSS Fixes: {len(fixes):,}")
                # Parse sample
                first_fix = fixes[0]
                last_fix = fixes[-1]
                print(f"    First Fix:        Time: {first_fix[2]} | Lat: {first_fix[3]}, Lon: {first_fix[4]} | Speed: {first_fix[7]} km/h | Acc: {first_fix[9]} m | Sats: {first_fix[11]}")
                print(f"    Last Fix:         Time: {last_fix[2]} | Lat: {last_fix[3]}, Lon: {last_fix[4]} | Speed: {last_fix[7]} km/h | Acc: {last_fix[9]} m | Sats: {last_fix[11]}")
                if len(fixes) > 1:
                    first_mono = int(first_fix[0])
                    last_mono = int(last_fix[0])
                    gnss_dur_s = (last_mono - first_mono) / 1e9
                    gnss_rate = (len(fixes) - 1) / gnss_dur_s if gnss_dur_s > 0 else 0
                    print(f"    GNSS Duration:    {gnss_dur_s:.2f} s (Average Rate: {gnss_rate:.2f} Hz)")
            else:
                print(f"    [!] GNSS file contains only header ({len(lines)} line)")
        except Exception as e:
            print(f"    [!] Error inspecting GNSS CSV: {e}")

    # Inspect Camera video and frame timestamps if present
    camera_csv = None
    camera_vid = None
    if session_dir:
        cand_vid = os.path.join(session_dir, "camera", "camera_video.mp4")
        cand_csv = os.path.join(session_dir, "camera", "camera_frames.csv")
        if os.path.isfile(cand_vid):
            camera_vid = cand_vid
        if os.path.isfile(cand_csv):
            camera_csv = cand_csv

    if camera_vid or camera_csv:
        print(f"\n[+] Camera Video & Frame Shutter Synchronization:")
        if camera_vid and os.path.isfile(camera_vid):
            v_size = os.path.getsize(camera_vid)
            print(f"    Video Container:  {camera_vid} ({v_size / (1024*1024):.2f} MB, {v_size:,} bytes)")
        if camera_csv and os.path.isfile(camera_csv):
            try:
                with open(camera_csv, "r", encoding="utf-8") as cf:
                    c_lines = [l.strip() for l in cf if l.strip()]
                if len(c_lines) > 1:
                    c_frames = [l.split(",") for l in c_lines[1:]]
                    print(f"    Total Video Frames Logged: {len(c_frames):,}")
                    first_f = c_frames[0]
                    last_f = c_frames[-1]
                    f_start_ns = int(first_f[1])
                    f_stop_ns = int(last_f[1])
                    f_dur_s = (f_stop_ns - f_start_ns) / 1e9
                    f_rate = (len(c_frames) - 1) / f_dur_s if f_dur_s > 0 else 0
                    print(f"    First Shutter:    Frame #{first_f[0]} | Mono: {f_start_ns} ns | Time: {first_f[5]}")
                    print(f"    Last Shutter:     Frame #{last_f[0]} | Mono: {f_stop_ns} ns | Time: {last_f[5]}")
                    print(f"    Camera Duration:  {f_dur_s:.2f} s (Average Effective Rate: {f_rate:.2f} FPS)")
            except Exception as e:
                print(f"    [!] Error inspecting camera frames CSV: {e}")

    # Inspect Master Cross-Sensor Timeline if present
    timeline_file = None
    if session_dir:
        candidate_tl = os.path.join(session_dir, "session_timeline.csv")
        if os.path.isfile(candidate_tl):
            timeline_file = candidate_tl

    if timeline_file:
        print(f"\n[+] Master Cross-Sensor Synchronization Timeline ({timeline_file}):")
        try:
            with open(timeline_file, "r", encoding="utf-8") as tf:
                tl_lines = [line.strip() for line in tf if line.strip()]
            if len(tl_lines) > 1:
                events = [line.split(",") for line in tl_lines[1:]]
                sensor_counts = {}
                for ev in events:
                    s = ev[1]
                    sensor_counts[s] = sensor_counts.get(s, 0) + 1
                print(f"    Total Synchronized Events: {len(events):,}")
                for s, count in sorted(sensor_counts.items()):
                    print(f"      - Sensor [{s:<8}]: {count:,} events")
                first_mono = int(events[0][0])
                last_mono = int(events[-1][0])
                print(f"    Timeline Span:             {(last_mono - first_mono)/1e9:.2f} s (Mono #{first_mono} -> #{last_mono})")
            else:
                print(f"    [!] Timeline contains only header")
        except Exception as e:
            print(f"    [!] Error inspecting timeline: {e}")

    if not frames_file or not os.path.isfile(frames_file):
        print(f"\n[-] Radar frames binary not present or requested path is not radar.")
        return

    file_size = os.path.getsize(frames_file)
    print(f"\n[+] Inspecting Framed Radar Binary: {frames_file}")
    print(f"    File Size: {file_size:,} bytes ({file_size / (1024*1024):.2f} MB)")

    with open(frames_file, "rb") as f:
        data = f.read()

    offset = 0
    frame_count = 0
    corrupt_headers = 0
    frame_numbers = []
    mono_timestamps = []
    wall_timestamps = []
    packet_lengths = []
    detected_objects_history = []
    tlv_counts = {}

    while offset + ROAD_HEADER_SIZE <= len(data):
        magic, mono_ns, wall_ms, packet_len = struct.unpack_from(">4sQQI", data, offset)
        if magic != ROAD_MAGIC:
            # Framing sync lost: attempt resync
            resync_idx = data.find(ROAD_MAGIC, offset + 1)
            corrupt_headers += 1
            if resync_idx == -1:
                break
            offset = resync_idx
            continue

        frame_data_offset = offset + ROAD_HEADER_SIZE
        if frame_data_offset + packet_len > len(data):
            print(f"    [!] Truncated final frame at byte {offset} (needed {packet_len} bytes, file ends)")
            break

        radar_bytes = data[frame_data_offset:frame_data_offset + packet_len]

        # Verify inner TI radar packet
        if len(radar_bytes) >= TI_HEADER_SIZE and radar_bytes[:8] == TI_MAGIC:
            _, version, total_len, platform, frame_num, cpu_cycles, num_det, num_tlvs, subframe = struct.unpack_from(
                "<8sIIIIIIII", radar_bytes, 0
            )
            frame_numbers.append(frame_num)
            mono_timestamps.append(mono_ns)
            wall_timestamps.append(wall_ms)
            packet_lengths.append(packet_len)
            detected_objects_history.append(num_det)

            # Scan TLVs
            tlv_offset = TI_HEADER_SIZE
            for _ in range(num_tlvs):
                if tlv_offset + 8 > len(radar_bytes):
                    break
                tlv_type, tlv_len = struct.unpack_from("<II", radar_bytes, tlv_offset)
                tlv_counts[tlv_type] = tlv_counts.get(tlv_type, 0) + 1
                tlv_offset += 8 + tlv_len

        frame_count += 1
        offset = frame_data_offset + packet_len

    print(f"\n[+] Total Valid Framed Packets Decoded: {frame_count:,}")
    if corrupt_headers > 0:
        print(f"    [!] Sync resync events: {corrupt_headers}")

    if frame_count == 0:
        print("[-] No valid framed packets found.")
        return

    # Frame continuity & rate analysis
    gaps = 0
    for i in range(1, len(frame_numbers)):
        diff = frame_numbers[i] - frame_numbers[i - 1]
        if diff != 1:
            gaps += 1

    dur_ns = mono_timestamps[-1] - mono_timestamps[0]
    dur_sec = dur_ns / 1e9
    avg_fps = (frame_count - 1) / dur_sec if dur_sec > 0 else 0

    intervals_ms = [(mono_timestamps[i] - mono_timestamps[i - 1]) / 1e6 for i in range(1, len(mono_timestamps))]
    min_dt = min(intervals_ms) if intervals_ms else 0
    max_dt = max(intervals_ms) if intervals_ms else 0
    avg_dt = sum(intervals_ms) / len(intervals_ms) if intervals_ms else 0

    print(f"\n--- Timing & Synchronization Analysis ---")
    print(f"    Host Monotonic Duration: {dur_sec:.2f} s")
    print(f"    Average Radar Rate:      {avg_fps:.2f} Hz")
    print(f"    Frame Interval (dt):     Avg: {avg_dt:.2f} ms | Min: {min_dt:.2f} ms | Max: {max_dt:.2f} ms")
    print(f"    Radar Frame Range:       #{frame_numbers[0]} -> #{frame_numbers[-1]}")
    print(f"    Packet Gaps / Drops:     {gaps} ({'100.0% Continuous' if gaps == 0 else f'{gaps} dropped frames'})")

    print(f"\n--- Decoded TLVs Across Session ---")
    tlv_names = {
        1: "Detected Points (Point Cloud)",
        2: "Target Clusters (Pre-Track Clusters)",
        3: "Active Tracks (Tracker Table)",
        4: "Parking Assist",
        6: "Stats Information",
        7: "Side Info (SNR / Noise)",
        8: "Cluster Information (Legacy)",
        1010: "3D Target List"
    }
    for t_type, count in sorted(tlv_counts.items()):
        name = tlv_names.get(t_type, "Custom / Unknown TLV")
        print(f"    TLV {t_type:<4} [{name}]: {count:,} occurrences")

    print(f"\n--- Sample Frames ---")
    sample_indices = [0, len(frame_numbers) // 2, len(frame_numbers) - 1]
    for idx in sample_indices:
        if idx < len(frame_numbers):
            dt_str = datetime.fromtimestamp(wall_timestamps[idx] / 1000.0).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
            print(f"    Frame #{frame_numbers[idx]:<6} | Time: {dt_str} | Mono: {mono_timestamps[idx]:<16} ns | Objects: {detected_objects_history[idx]:<3} | Size: {packet_lengths[idx]} B")

    print("\n" + "=" * 75)
    print(" Session Inspection Complete: Valid RoadSense Recording Format")
    print("=" * 75)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tools/inspect_session.py <path_to_session_dir_or_radar_frames.bin>")
        sys.exit(1)
    inspect_session(sys.argv[1])
