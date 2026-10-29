#!/usr/bin/env python3
"""
RoadSense Log Sync & Visualizer Processing Pipeline

This script performs two automated tasks:
1. Sync: Connects via ADB to the connected Android device and pulls all RoadSense
   recording sessions from /sdcard/Android/data/com.bajajauto.roadsense/files/sessions/
   into the local logs/ directory.
2. Process: Iterates through each session directory and converts raw multi-sensor
   data into the standardized format required by the visualizer:
   - `track_history.json`: Unified radar frames (point cloud, clusters, hardware EKF tracks)
     and multi-frame track trajectory histories according to the schema in
     VISUALIZER_JSON_FORMAT_AND_CONVERSION_GUIDE.md.
   - `frame_mapping.json`: Nanosecond-accurate cross-sensor synchronization index
     mapping radar frames to MP4 camera frames.
   - Preserves `camera_video.mp4` for video playback.

Usage:
    python tools/sync_and_process_sessions.py [--sync-only] [--process-only] [--session <id>] [--logs-dir <path>]
"""

import os
import sys
import glob
import json
import math
import struct
import shutil
import argparse
import subprocess
import bisect

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)
import time
from datetime import datetime

# Default paths
DEFAULT_LOGS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")
DEVICE_SESSION_DIR = "/sdcard/Android/data/com.bajajauto.roadsense/files/sessions/"
DEVICE_APP_LOGS_DIR = "/sdcard/Android/data/com.bajajauto.roadsense/files/app_logs/"
DEVICE_CALIB_DIR = "/sdcard/Android/data/com.bajajauto.roadsense/files/calibration/"

# RoadSense binary framing constants
ROAD_MAGIC = b"ROAD"
ROAD_HEADER_SIZE = 24  # >4sQQI (magic, monoNs, wallMs, packetLen)
TI_MAGIC = bytes([0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07])
TI_HEADER_SIZE = 40

def find_adb():
    """Locates the adb executable on the system or in Android SDK platform-tools."""
    adb = shutil.which("adb")
    if adb:
        return adb
    candidates = [
        os.path.expanduser(r"~\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
        r"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe"
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None

def safe_float(val, default=0.0, round_digits=4):
    """Safely converts a number to float without producing NaN or Inf in JSON."""
    if val is None:
        return default
    try:
        f = float(val)
        if math.isnan(f) or math.isinf(f):
            return default
        if round_digits is not None:
            return round(f, round_digits)
        return f
    except (ValueError, TypeError):
        return default

def get_remote_session_files(adb_path, device_id, remote_session_dir):
    """
    Queries files and byte sizes on the Android device inside remote_session_dir.
    Returns list of dicts: [{'remote': full_path, 'rel_path': rel_path, 'size': int_bytes}]
    """
    files = []
    base_dir = remote_session_dir.rstrip("/")
    
    # 1. Primary fast method: find + stat with + (fastest multi-argument execution)
    cmd = [adb_path, "-s", device_id, "shell", f"find {base_dir} -type f -exec stat -c '%s %n' {{}} +"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip():
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=1)
            if len(parts) == 2 and parts[0].isdigit():
                f_size = int(parts[0])
                f_path = parts[1].strip()
                rel_path = os.path.relpath(f_path, base_dir).replace("\\", "/")
                files.append({
                    "remote": f_path,
                    "rel_path": rel_path,
                    "size": f_size
                })
        if files:
            return files

    # 2. Fallback method: stat with semicolon (for older toybox versions)
    cmd = [adb_path, "-s", device_id, "shell", f"find {base_dir} -type f -exec stat -c '%s %n' {{}} \\;"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip():
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=1)
            if len(parts) == 2 and parts[0].isdigit():
                f_size = int(parts[0])
                f_path = parts[1].strip()
                rel_path = os.path.relpath(f_path, base_dir).replace("\\", "/")
                files.append({
                    "remote": f_path,
                    "rel_path": rel_path,
                    "size": f_size
                })
        if files:
            return files

    # 3. Fallback method: ls -lR recursion
    cmd = [adb_path, "-s", device_id, "shell", f"ls -lR {base_dir}"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    curr_dir = base_dir
    for line in res.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.endswith(":") and "/" in line:
            curr_dir = line[:-1]
            continue
        parts = line.split()
        if len(parts) >= 8 and parts[0].startswith("-"):
            try:
                for idx, p in enumerate(parts[3:6], start=3):
                    if p.isdigit() and int(p) > 0:
                        f_size = int(p)
                        fname = " ".join(parts[idx+3:]) if len(parts) > idx+3 else parts[-1]
                        f_path = f"{curr_dir}/{fname}"
                        rel_path = os.path.relpath(f_path, base_dir).replace("\\", "/")
                        files.append({
                            "remote": f_path,
                            "rel_path": rel_path,
                            "size": f_size
                        })
                        break
            except Exception:
                pass
    return files


def pull_file_with_progress(
    adb_path,
    device_id,
    remote_file,
    local_file,
    file_size,
    file_rel_path,
    file_idx,
    total_files,
    session_name,
    overall_bytes_start,
    total_sync_bytes,
):
    """
    Pulls a single file from device via ADB with real-time disk growth monitoring.
    Emits structured [PROGRESS] JSON events and prints in-place terminal updates.
    Returns (success: bool, bytes_transferred: int)
    """
    os.makedirs(os.path.dirname(local_file), exist_ok=True)

    # 1. Fast skip if file is already completely transferred
    if os.path.isfile(local_file) and file_size > 0:
        if os.path.getsize(local_file) == file_size:
            return True, file_size

    # Remove partial file if size does not match
    if os.path.isfile(local_file):
        try:
            os.remove(local_file)
        except Exception:
            pass

    # 2. For tiny files (size <= 32 KB), pull quickly synchronously
    if file_size <= 32768:
        cmd = [adb_path, "-s", device_id, "pull", remote_file, local_file]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0:
            actual_size = os.path.getsize(local_file) if os.path.isfile(local_file) else file_size
            return True, actual_size
        else:
            return False, 0

    # 3. For larger files, pull asynchronously with real-time disk sampling
    cmd = [adb_path, "-s", device_id, "pull", remote_file, local_file]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    t_start = time.time()
    last_sample_t = t_start
    last_sample_bytes = 0
    rolling_speed = 0.0
    alpha = 0.25  # Exponential smoothing factor for speed

    while p.poll() is None:
        time.sleep(0.1)
        now = time.time()
        curr_bytes = os.path.getsize(local_file) if os.path.isfile(local_file) else 0

        dt = now - last_sample_t
        if dt >= 0.2:
            inst_speed = max(0.0, (curr_bytes - last_sample_bytes) / dt)
            if rolling_speed == 0.0:
                rolling_speed = inst_speed
            else:
                rolling_speed = alpha * inst_speed + (1.0 - alpha) * rolling_speed
            last_sample_t = now
            last_sample_bytes = curr_bytes

        file_pct = min(100.0, (curr_bytes / max(file_size, 1)) * 100.0)
        eta_s = (file_size - curr_bytes) / max(rolling_speed, 1.0) if (file_size > curr_bytes and rolling_speed > 0) else 0.0
        overall_bytes_curr = overall_bytes_start + curr_bytes
        overall_pct = min(100.0, (overall_bytes_curr / max(total_sync_bytes, 1)) * 100.0)

        # Emit [PROGRESS] JSON line for Web Dashboard SSE
        progress_payload = {
            "step": "adb_sync",
            "desc": f"Syncing {session_name} ({file_idx}/{total_files})",
            "overall_pct": round(overall_pct, 1),
            "overall_current": file_idx,
            "overall_total": total_files,
            "overall_mb": round(overall_bytes_curr / (1024 * 1024), 1),
            "overall_total_mb": round(total_sync_bytes / (1024 * 1024), 1),
            "sub": {
                "file_name": file_rel_path,
                "file_pct": round(file_pct, 1),
                "current_mb": round(curr_bytes / (1024 * 1024), 1),
                "total_mb": round(file_size / (1024 * 1024), 1),
                "speed_mb_s": round(rolling_speed / (1024 * 1024), 1),
                "eta_s": round(eta_s, 1),
            }
        }
        print(f"[PROGRESS] {json.dumps(progress_payload)}", flush=True)

        # Live terminal carriage-return update (ONLY when running directly in an interactive TTY)
        if sys.stdout.isatty():
            speed_str = f"{rolling_speed / (1024 * 1024):.1f} MB/s" if rolling_speed > 0 else "-- MB/s"
            eta_str = f"ETA: {int(eta_s)}s" if eta_s > 0 else ""
            bar_len = 16
            filled = int(bar_len * (file_pct / 100.0))
            bar = "=" * filled + (">" if filled < bar_len else "") + " " * max(0, bar_len - filled - (1 if filled < bar_len else 0))
            terminal_line = f"\r    [{bar}] {file_pct:5.1f}% | {curr_bytes/(1024*1024):6.1f}/{file_size/(1024*1024):.1f} MB | {speed_str:<9} | {eta_str:<8} | {file_rel_path}"
            sys.stdout.write(terminal_line[:115])
            sys.stdout.flush()

    p.wait()
    success = (p.returncode == 0)
    actual_size = os.path.getsize(local_file) if os.path.isfile(local_file) else 0

    if success:
        if sys.stdout.isatty():
            sys.stdout.write(f"\r    [================] 100.0% | {actual_size/(1024*1024):6.1f}/{file_size/(1024*1024):.1f} MB | {file_rel_path} (Done)\n")
            sys.stdout.flush()
        else:
            print(f"    - [{file_rel_path}] Synced ({actual_size/(1024*1024):.1f} MB)", flush=True)
        return True, actual_size
    else:
        err_msg = p.stderr.read() if p.stderr else "Unknown error"
        if sys.stdout.isatty():
            sys.stdout.write(f"\r    [-] Failed to pull {file_rel_path}: {err_msg.strip()}\n")
            sys.stdout.flush()
        else:
            print(f"    [-] Failed to pull {file_rel_path}: {err_msg.strip()}", flush=True)
        return False, 0


def sync_sessions_from_device(adb_path, local_logs_dir):
    """Pulls all session folders from Android device via ADB with dual progress tracking."""
    os.makedirs(local_logs_dir, exist_ok=True)
    print(f"\n[+] Checking ADB connection...")
    try:
        res = subprocess.run([adb_path, "devices"], capture_output=True, text=True, check=True)
        devices = [line.split()[0] for line in res.stdout.strip().splitlines()[1:] if "\tdevice" in line]
        if not devices:
            print("[-] No authorized Android device detected. Skipping device sync.")
            return False
        device_id = devices[0]
        print(f"[+] Found device: {device_id}")

        # List session folders on device
        list_cmd = [adb_path, "-s", device_id, "shell", f"ls -1 {DEVICE_SESSION_DIR}"]
        list_res = subprocess.run(list_cmd, capture_output=True, text=True)
        remote_sessions = [s.strip() for s in list_res.stdout.splitlines() if s.strip().startswith("session_")]

        if not remote_sessions:
            print(f"[-] No sessions found at {DEVICE_SESSION_DIR}")
            return False

        # 1. Identify which sessions need syncing
        sessions_to_sync = []
        for s_name in remote_sessions:
            local_path = os.path.join(local_logs_dir, s_name)
            meta_file = os.path.join(local_path, "session_metadata.json")
            if os.path.isdir(local_path) and os.path.isfile(meta_file):
                # Verify video file is present and non-zero
                v_file = os.path.join(local_path, "camera", "camera_video.mp4")
                if not os.path.isfile(v_file) or os.path.getsize(v_file) > 1024:
                    # Session exists and has video or no video required
                    continue
            sessions_to_sync.append(s_name)

        if not sessions_to_sync:
            print(f"[+] All {len(remote_sessions)} sessions are already synced locally.")
        else:
            print(f"[+] Found {len(sessions_to_sync)} session(s) to sync out of {len(remote_sessions)} on device.")

            # 2. Query file lists and sizes for all sessions to sync
            print("[+] Querying remote file manifests and byte counts...")
            session_file_map = {}
            total_sync_bytes = 0
            total_sync_files = 0

            for s_name in sessions_to_sync:
                remote_path = f"{DEVICE_SESSION_DIR}{s_name}"
                s_files = get_remote_session_files(adb_path, device_id, remote_path)
                session_file_map[s_name] = s_files
                for f in s_files:
                    local_f = os.path.join(local_logs_dir, s_name, f["rel_path"])
                    if not (os.path.isfile(local_f) and os.path.getsize(local_f) == f["size"] and f["size"] > 0):
                        total_sync_bytes += f["size"]
                        total_sync_files += 1

            total_mb = total_sync_bytes / (1024 * 1024)
            print(f"[+] Total transfer payload: {total_sync_files} files ({total_mb:.1f} MB)")

            # 3. Pull files with live speed, ETA, and dual progress reporting
            overall_bytes_done = 0
            overall_file_idx = 0

            for s_idx, s_name in enumerate(sessions_to_sync, start=1):
                s_files = session_file_map.get(s_name, [])
                local_session_dir = os.path.join(local_logs_dir, s_name)
                os.makedirs(local_session_dir, exist_ok=True)
                print(f"\n  [{s_idx}/{len(sessions_to_sync)}] Syncing session: {s_name}")

                for f_info in s_files:
                    f_rel = f_info["rel_path"]
                    f_size = f_info["size"]
                    f_remote = f_info["remote"]
                    f_local = os.path.join(local_session_dir, f_rel)

                    overall_file_idx += 1
                    ok, bytes_pulled = pull_file_with_progress(
                        adb_path=adb_path,
                        device_id=device_id,
                        remote_file=f_remote,
                        local_file=f_local,
                        file_size=f_size,
                        file_rel_path=f_rel,
                        file_idx=overall_file_idx,
                        total_files=max(total_sync_files, 1),
                        session_name=s_name,
                        overall_bytes_start=overall_bytes_done,
                        total_sync_bytes=max(total_sync_bytes, 1),
                    )
                    if ok:
                        overall_bytes_done += bytes_pulled

        # 4. Pull continuous app-wide diagnostics logs (app_logs/)
        try:
            local_app_logs_dir = os.path.join(local_logs_dir, "app_logs")
            os.makedirs(local_app_logs_dir, exist_ok=True)
            print(f"\n[+] Syncing continuous diagnostics logs from {DEVICE_APP_LOGS_DIR}...")
            pull_app_logs_cmd = [adb_path, "-s", device_id, "pull", DEVICE_APP_LOGS_DIR, local_logs_dir]
            subprocess.run(pull_app_logs_cmd, capture_output=True, text=True)
        except Exception as e:
            print(f"[-] Note: could not pull app_logs: {e}")

        # 5. Pull global active camera-radar calibration profile (calibration/)
        local_calib_dir = os.path.join(local_logs_dir, "calibration")
        os.makedirs(local_calib_dir, exist_ok=True)
        try:
            print(f"[+] Syncing active device calibration profile from {DEVICE_CALIB_DIR}...")
            pull_calib_cmd = [adb_path, "-s", device_id, "pull", DEVICE_CALIB_DIR, local_logs_dir]
            subprocess.run(pull_calib_cmd, capture_output=True, text=True)
        except Exception as e:
            print(f"[-] Note: could not pull calibration: {e}")

        # 6. Ensure every session has a calibration JSON: If missing, backfill from global device backup
        global_calib_file = os.path.join(local_calib_dir, "radar_camera_calib.json")
        for s_name in remote_sessions:
            s_dir = os.path.join(local_logs_dir, s_name)
            s_calib = os.path.join(s_dir, "radar_camera_calib.json")
            if os.path.isdir(s_dir) and not os.path.isfile(s_calib) and os.path.isfile(global_calib_file):
                try:
                    shutil.copy2(global_calib_file, s_calib)
                    print(f"    - [{s_name}] Backfilled missing calibration from phone local storage backup.")
                except Exception as e:
                    print(f"[-] Could not backfill calibration for {s_name}: {e}")

        # 7. Broadcast completion progress
        done_payload = {
            "step": "done",
            "desc": "Device sync complete!",
            "overall_pct": 100,
            "overall_current": None,
            "overall_total": None,
            "sub": None,
        }
        print(f"[PROGRESS] {json.dumps(done_payload)}", flush=True)

        print("[+] Device sync complete!")
        return True
    except Exception as e:
        print(f"[-] ADB sync error: {e}")
        return False

def load_camera_frames_csv(csv_path):
    """Parses camera_frames.csv returning list of frames with nanosecond monotonic timestamps."""
    frames = []
    if not os.path.isfile(csv_path):
        return frames
    try:
        import csv
        with open(csv_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                frames.append({
                    "frame_number": int(row["frame_number"]),
                    "mono_ns": int(row["shutter_monotonic_ns"]),
                    "utc_ms": int(row["utc_time_ms"]) if "utc_time_ms" in row else 0
                })
    except Exception as e:
        print(f"    [!] Error reading camera_frames.csv: {e}")
    return frames

def load_gnss_fixes_csv(csv_path):
    """Parses gnss_fixes.csv returning list of fixes with nanosecond monotonic timestamps."""
    fixes = []
    if not os.path.isfile(csv_path):
        return fixes
    try:
        import csv
        with open(csv_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                fixes.append({
                    "mono_ns": int(row["elapsed_realtime_ns"]),
                    "lat": float(row.get("latitude", 0.0)),
                    "lon": float(row.get("longitude", 0.0)),
                    "speed_kmh": float(row.get("speed_kmh", 0.0)),
                    "bearing": float(row.get("bearing_deg", 0.0))
                })
    except Exception as e:
        print(f"    [!] Error reading gnss_fixes.csv: {e}")
    return fixes

def get_mp4_frame_count(mp4_path):
    """
    Reads sample_count from stsz box of MP4 file using pure Python.
    Returns integer total video frame count, or None if unavailable.
    """
    if not os.path.isfile(mp4_path):
        return None
    try:
        with open(mp4_path, "rb") as f:
            data = f.read()
        idx = 0
        last_count = None
        while True:
            idx = data.find(b"stsz", idx)
            if idx == -1:
                break
            if idx >= 4 and idx + 16 <= len(data):
                sample_size, sample_count = struct.unpack(">II", data[idx+8:idx+16])
                if sample_size == 0 and 0 < sample_count < 10000000:
                    last_count = sample_count
            idx += 4
        return last_count
    except Exception:
        return None

def find_nearest_camera_frame(cam_frames, radar_mono_ns, max_video_frames=None, preroll_offset=0):
    """
    Binary searches nearest camera frame by monotonic timestamp.
    Returns (video_frame_index, video_time_delta_sec, video_frame_ts_sec, camera_hw_frame_num).
    video_frame_index is strictly a 0-based relative index into the MP4 video container.
    """
    if not cam_frames:
        return None, None, None, None

    low = 0
    high = len(cam_frames) - 1
    while low <= high:
        mid = (low + high) // 2
        if cam_frames[mid]["mono_ns"] < radar_mono_ns:
            low = mid + 1
        elif cam_frames[mid]["mono_ns"] > radar_mono_ns:
            high = mid - 1
        else:
            low = mid
            break

    candidates = [c for c in [low - 1, low, low + 1] if 0 <= c < len(cam_frames)]
    best_idx = min(candidates, key=lambda c: abs(cam_frames[c]["mono_ns"] - radar_mono_ns))
    matched = cam_frames[best_idx]
    
    delta_sec = (matched["mono_ns"] - radar_mono_ns) / 1e9
    
    # 0-based relative index into MP4 container
    v_frame_idx = max(0, best_idx - preroll_offset)
    if max_video_frames is not None and max_video_frames > 0:
        v_frame_idx = min(v_frame_idx, max_video_frames - 1)
        
    return v_frame_idx, delta_sec, matched["mono_ns"], matched.get("frame_number", matched.get("seq_id", 0))

def parse_radar_frames_bin(radar_bin_path):
    """
    Reads RoadSense framed binary stream (radar_frames.bin) and decodes all TLVs.
    Returns list of parsed frame dictionaries.
    """
    frames = []
    if not os.path.isfile(radar_bin_path):
        return frames

    with open(radar_bin_path, "rb") as f:
        data = f.read()

    offset = 0
    total_len = len(data)

    while offset + ROAD_HEADER_SIZE <= total_len:
        magic, mono_ns, wall_ms, packet_len = struct.unpack_from(">4sQQI", data, offset)
        if magic != ROAD_MAGIC:
            resync = data.find(ROAD_MAGIC, offset + 1)
            if resync == -1:
                break
            offset = resync
            continue

        frame_data_offset = offset + ROAD_HEADER_SIZE
        if frame_data_offset + packet_len > total_len:
            break

        radar_bytes = data[frame_data_offset : frame_data_offset + packet_len]
        offset = frame_data_offset + packet_len

        if len(radar_bytes) < TI_HEADER_SIZE or radar_bytes[:8] != TI_MAGIC:
            continue

        _, version, total_packet_len, platform, frame_num, cpu_cycles, num_det, num_tlvs, subframe = struct.unpack_from(
            "<8sIIIIIIII", radar_bytes, 0
        )

        frame_dict = {
            "hw_frame_num": frame_num,
            "mono_ns": mono_ns,
            "wall_ms": wall_ms,
            "cpu_cycles": cpu_cycles,
            "subframe": subframe,
            "num_detected_obj": num_det,
            "pointCloud": [],
            "clusters": [],
            "tracks": [],
            "diags": None,
            "can_inputs": None,
            "adas_can": None
        }

        tlv_offset = TI_HEADER_SIZE
        for _ in range(num_tlvs):
            if tlv_offset + 8 > len(radar_bytes):
                break
            tlv_type, tlv_len = struct.unpack_from("<II", radar_bytes, tlv_offset)
            tlv_offset += 8
            tlv_data = radar_bytes[tlv_offset : tlv_offset + tlv_len]
            tlv_offset += tlv_len

            if len(tlv_data) < 4:
                continue

            # --- TLV 1, 2, 3: Array TLVs with 4-byte Descriptor (<HH: num_objs, q_format) ---
            if tlv_type in (1, 2, 3):
                num_objs, q_format = struct.unpack_from("<HH", tlv_data, 0)
                if q_format > 31:
                    q_format = 15
                inv_q = 1.0 / (1 << q_format)
                payload_size = len(tlv_data) - 4

                # --- TLV 1: Detected Point Cloud (12B Extended / 10B Legacy) ---
                if tlv_type == 1:
                    if num_objs > 0:
                        calc_stride = payload_size // num_objs
                        stride = 12 if calc_stride >= 12 else 10
                    else:
                        stride = 12 if (payload_size % 12 == 0 and payload_size > 0) else 10

                    count = min(num_objs, payload_size // stride)
                    for i in range(count):
                        poff = 4 + i * stride
                        if stride >= 12:
                            doppler_raw, peak_raw, x_raw, y_raw, z_raw, cluster_id, is_outlier = struct.unpack_from(
                                "<hHhhhBB", tlv_data, poff
                            )
                        else:
                            doppler_raw, peak_raw, x_raw, y_raw, z_raw = struct.unpack_from(
                                "<hHhhh", tlv_data, poff
                            )
                            cluster_id = 0
                            is_outlier = 0

                        snr_db = (peak_raw / 512.0) * 6.0206
                        frame_dict["pointCloud"].append({
                            "x": safe_float(x_raw * inv_q),
                            "y": safe_float(y_raw * inv_q),
                            "z": safe_float(z_raw * inv_q),
                            "velocity": safe_float(doppler_raw * inv_q),
                            "snr": safe_float(snr_db),
                            "noise": 0.0,
                            "pointId": i,
                            "clusterNumber": int(cluster_id),
                            "isOutlier": bool(is_outlier)
                        })

                # --- TLV 2: Target Clusters (16B Extended / 10B / 8B Legacy) ---
                elif tlv_type == 2:
                    if num_objs > 0:
                        calc_stride = payload_size // num_objs
                        stride = 16 if calc_stride >= 16 else (10 if calc_stride >= 10 else 8)
                    else:
                        stride = 16 if (payload_size % 16 == 0 and payload_size > 0) else (
                            10 if (payload_size % 10 == 0 and payload_size > 0) else 8
                        )

                    count = min(num_objs, payload_size // stride)
                    for i in range(count):
                        coff = 4 + i * stride
                        if stride >= 16:
                            x_raw, y_raw, xs_raw, ys_raw, cid, num_pts, is_out, is_stat, is_dead, _ = struct.unpack_from(
                                "<hhhhHHBBBB", tlv_data, coff
                            )
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = 0.0
                            vy_val = 0.0
                            is_outlier_bool = bool(is_out)
                            is_stat_bool = bool(is_stat)
                        elif stride >= 10:
                            x_raw, y_raw, xs_raw, ys_raw, cid = struct.unpack_from("<hhhhH", tlv_data, coff)
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = xs_raw * inv_q
                            vy_val = ys_raw * inv_q
                            is_outlier_bool = False
                            is_stat_bool = False
                        else:
                            x_raw, y_raw, xs_raw, ys_raw = struct.unpack_from("<hhhh", tlv_data, coff)
                            cid = i + 1
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = 0.0
                            vy_val = 0.0
                            is_outlier_bool = False
                            is_stat_bool = False

                        dist = math.hypot(x_val, y_val)
                        rad_speed = (x_val * vx_val + y_val * vy_val) / dist if dist > 0 and (vx_val != 0.0 or vy_val != 0.0) else 0.0
                        azimuth = math.degrees(math.atan2(x_val, y_val)) if dist > 0 else 0.0

                        frame_dict["clusters"].append({
                            "id": int(cid),
                            "x": safe_float(x_val),
                            "y": safe_float(y_val),
                            "vx": safe_float(vx_val),
                            "vy": safe_float(vy_val),
                            "radialSpeed": safe_float(rad_speed),
                            "azimuth": safe_float(azimuth),
                            "isOutlier": is_outlier_bool,
                            "isStationaryInBox": is_stat_bool
                        })

                # --- TLV 3: EKF Tracked Objects (28B v2.2 / 20B / 14B / 12B) ---
                elif tlv_type == 3:
                    if num_objs > 0:
                        calc_stride = payload_size // num_objs
                        stride = 28 if calc_stride >= 28 else (20 if calc_stride >= 20 else (14 if calc_stride >= 14 else 12))
                    else:
                        stride = 28 if (payload_size % 28 == 0 and payload_size > 0) else (
                            20 if (payload_size % 20 == 0 and payload_size > 0) else (
                                14 if (payload_size % 14 == 0 and payload_size > 0) else 12
                            )
                        )

                    count = min(num_objs, payload_size // stride)
                    for i in range(count):
                        toff = 4 + i * stride
                        if stride >= 28:
                            x_raw, y_raw, vx_raw, vy_raw, maj_raw, min_raw, ori_raw, tid, status, cluster_id, tti_raw, risk, is_stat, ttc_cat, conf, res = struct.unpack_from(
                                "<hhhhhhhHHHhBBBBH", tlv_data, toff
                            )
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = vx_raw * inv_q
                            vy_val = vy_raw * inv_q
                            maj_val = maj_raw * inv_q
                            min_val = min_raw * inv_q
                            ori_deg = ori_raw * 0.1
                            tti_sec = (tti_raw * 0.01) if tti_raw >= 0 else 100.0
                            risk_val = int(risk)
                            is_stat_bool = bool(is_stat)
                            aux = 0.0
                        elif stride >= 20:
                            x_raw, y_raw, vx_raw, vy_raw, maj_raw, min_raw, ori_raw, tid, status, res = struct.unpack_from(
                                "<hhhhhhhHHH", tlv_data, toff
                            )
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = vx_raw * inv_q
                            vy_val = vy_raw * inv_q
                            maj_val = maj_raw * inv_q
                            min_val = min_raw * inv_q
                            ori_deg = ori_raw * 0.1
                            tti_sec = 100.0
                            risk_val = 0
                            is_stat_bool = False
                            aux = 0.0
                        elif stride >= 14:
                            x_raw, y_raw, vx_raw, vy_raw, xs_raw, ys_raw, tid = struct.unpack_from(
                                "<hhhhhhH", tlv_data, toff
                            )
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = vx_raw * inv_q
                            vy_val = vy_raw * inv_q
                            # xs_raw is cluster lateral extent (width), ys_raw is longitudinal cluster extent (length)
                            maj_val = (ys_raw * inv_q) * 0.5
                            min_val = (xs_raw * inv_q) * 0.5
                            ori_deg = 0.0
                            status = 3
                            tti_sec = 100.0
                            risk_val = 0
                            is_stat_bool = False
                            aux = 0.0
                        else:
                            x_raw, y_raw, vx_raw, vy_raw, xs_raw, ys_raw = struct.unpack_from(
                                "<hhhhhh", tlv_data, toff
                            )
                            x_val = x_raw * inv_q
                            y_val = y_raw * inv_q
                            vx_val = vx_raw * inv_q
                            vy_val = vy_raw * inv_q
                            maj_val = (ys_raw * inv_q) * 0.5
                            min_val = (xs_raw * inv_q) * 0.5
                            ori_deg = 0.0
                            tid = i + 1
                            status = 3
                            tti_sec = 100.0
                            risk_val = 0
                            is_stat_bool = False
                            aux = 0.0

                        # Filter out inactive / free (0) or dead (5) or zero-vector track slots
                        if status == 0 or status == 5 or (x_val == 0.0 and y_val == 0.0 and vx_val == 0.0 and vy_val == 0.0):
                            continue

                        frame_dict["tracks"].append({
                            "tid": int(tid),
                            "status": int(status),
                            "x": safe_float(x_val),
                            "y": safe_float(y_val),
                            "vx": safe_float(vx_val),
                            "vy": safe_float(vy_val),
                            "majorSize": safe_float(maj_val),
                            "minorSize": safe_float(min_val),
                            "xSize": safe_float(maj_val),
                            "ySize": safe_float(min_val),
                            "orientation": safe_float(ori_deg),
                            "tti": safe_float(tti_sec),
                            "risk": risk_val,
                            "isStationary": is_stat_bool,
                            "aux": safe_float(aux)
                        })

            # --- TLV 4: Tracker Diagnostics (48 Bytes) ---
            elif tlv_type == 4 and len(tlv_data) >= 48:
                d = struct.unpack_from("<HBb10fHBB", tlv_data, 0)
                frame_dict["diags"] = {
                    "num_inliers": int(d[0]),
                    "ransac_ok": bool(d[1]),
                    "motion_state": int(d[2]),
                    "filtered_vx_iir": float(d[3]),
                    "filtered_vy_iir": float(d[4]),
                    "ego_vy": float(d[5]),
                    "ego_vx": float(d[6]),
                    "ego_ax": float(d[7]),
                    "ego_ay": float(d[8]),
                    "ego_yaw_rate": float(d[9]),
                    "road_boundary_left_x": float(d[10]),
                    "road_boundary_right_x": float(d[11]),
                    "ax_dynamics": float(d[12]),
                    "tracker_proc_time_us": int(d[13]),
                    "imu_stuck": bool(d[14])
                }

            # --- TLV 5: Vehicle CAN Inputs (56 Bytes) ---
            elif tlv_type == 5 and len(tlv_data) >= 56:
                c = struct.unpack_from("<fffffffffffIBBbBB3s", tlv_data, 0)
                frame_dict["can_inputs"] = {
                    "speed_kmph": float(c[0]),
                    "yaw_rate_radps": float(c[1]),
                    "pitch_rate_radps": float(c[2]),
                    "roll_rate_radps": float(c[3]),
                    "accel_x_mps2": float(c[4]),
                    "accel_y_mps2": float(c[5]),
                    "accel_avg_mps2": float(c[6]),
                    "road_grade_deg": float(c[7]),
                    "shaft_torque_nm": float(c[8]),
                    "roll_cf_deg": float(c[9]),
                    "yaw_cf_deg": float(c[10]),
                    "timestamp_ms": int(c[11]),
                    "gear": int(c[12]),
                    "brake_status": int(c[13]),
                    "motion_state": int(c[14]),
                    "is_vcu_can_valid": bool(c[15]),
                    "imu_stuck_flag": bool(c[16]),
                    "accel_pedal_pct": 0.0
                }

            # --- TLV 6: Vehicle Safety ADAS Outputs (24 Bytes) ---
            elif tlv_type == 6 and len(tlv_data) >= 24:
                fcw = tlv_data[0:8]
                bsd = tlv_data[8:16]
                acc = tlv_data[16:24]
                fcw_stage = fcw[0] & 0x03
                fcw_trk_id = (fcw[1] << 8) | fcw[2]
                fcw_ttc = fcw[3] * 0.1
                fcw_ty = fcw[4] * 0.5
                fcw_tx = fcw[5] * 0.2 - 25.6
                fcw_tvy = fcw[6] * 0.5 - 64.0
                fcw_tvx = fcw[7] * 0.2 - 25.6

                bsd_l = bool(bsd[0] & 1)
                bsd_r = bool(bsd[1] & 1)
                lca = bsd[2]
                app_ttc = bsd[3] * 0.1 if bsd[3] != 255 else 25.5

                acc_id = (acc[0] << 8) | acc[1]
                acc_target_y = acc[2] * 0.5
                acc_target_x = acc[3] * 0.2 - 25.6
                acc_target_vy = acc[4] * 0.5 - 64.0
                acc_tti = acc[5] * 0.1
                acc_v_safe = acc[6] * 0.1
                acc_a_ref = acc[7] * 0.02 - 2.56

                frame_dict["adas_can"] = {
                    "poi_id": int(acc_id),
                    "acc_dist": safe_float(acc_target_y),
                    "acc_target_x": safe_float(acc_target_x),
                    "acc_rel_v": safe_float(acc_target_vy),
                    "acc_rel_a": safe_float(acc_a_ref),
                    "acc_status": 1 if acc_id > 0 else 0,
                    "tti": safe_float(acc_tti),
                    "v_safe_mps": safe_float(acc_v_safe),
                    "aeb_risk": 0,
                    "fcw_stage": int(fcw_stage),
                    "fcw_track_id": int(fcw_trk_id),
                    "fcw_target_x": safe_float(fcw_tx),
                    "fcw_target_y": safe_float(fcw_ty),
                    "fcw_target_vx": safe_float(fcw_tvx),
                    "fcw_target_vy": safe_float(fcw_tvy),
                    "fcw_ttc": safe_float(fcw_ttc),
                    "brake_prefill_req": bool(fcw_stage >= 2),
                    "target_confidence": 0.0,
                    "bsd_left_active": bsd_l,
                    "bsd_right_active": bsd_r,
                    "lca_warning_level": int(lca),
                    "approach_ttc": safe_float(app_ttc),
                    "corridor_width": 3.75,
                    "sensor_blindness": 0
                }

        frames.append(frame_dict)

    return frames


def stream_write_frame_mapping(
    mapping_path: str,
    mapping_records: list,
    session_name: str,
    overall_start_pct: float = 70.0,
    overall_end_pct: float = 72.0,
):
    """
    Writes frame_mapping.json as JSON Lines (.jsonl format) with real-time throughput and progress updates.
    """
    os.makedirs(os.path.dirname(mapping_path), exist_ok=True)
    total_recs = len(mapping_records)
    t_start = time.time()
    last_sample_t = t_start
    last_sample_bytes = 0
    rolling_speed = 0.0
    alpha = 0.25

    with open(mapping_path, "w", encoding="utf-8") as mf:
        for idx, rec in enumerate(mapping_records, start=1):
            mf.write(json.dumps(rec) + "\n")

            if idx % 1000 == 0 or idx == total_recs:
                now = time.time()
                dt = now - last_sample_t
                curr_bytes = mf.tell()
                if dt >= 0.15:
                    inst_speed = max(0.0, (curr_bytes - last_sample_bytes) / dt)
                    rolling_speed = inst_speed if rolling_speed == 0.0 else (alpha * inst_speed + (1.0 - alpha) * rolling_speed)
                    last_sample_t = now
                    last_sample_bytes = curr_bytes

                file_pct = (idx / max(total_recs, 1)) * 100.0
                est_total_bytes = (curr_bytes / max(idx, 1)) * total_recs
                eta_s = (est_total_bytes - curr_bytes) / max(rolling_speed, 1.0) if (est_total_bytes > curr_bytes and rolling_speed > 0) else 0.0
                overall_pct = overall_start_pct + (idx / max(total_recs, 1)) * (overall_end_pct - overall_start_pct)

                progress_payload = {
                    "step": "sync_save",
                    "desc": f"[{session_name}] Writing frame_mapping.json ({idx:,}/{total_recs:,})",
                    "overall_pct": round(overall_pct, 1),
                    "sub": {
                        "file_name": "frame_mapping.json",
                        "file_pct": round(file_pct, 1),
                        "current_mb": round(curr_bytes / (1024 * 1024), 2),
                        "total_mb": round(est_total_bytes / (1024 * 1024), 2),
                        "speed_mb_s": round(rolling_speed / (1024 * 1024), 1),
                        "eta_s": round(eta_s, 1),
                    }
                }
                print(f"[PROGRESS] {json.dumps(progress_payload)}", flush=True)

                if sys.stdout.isatty():
                    speed_str = f"{rolling_speed / (1024 * 1024):.1f} MB/s" if rolling_speed > 0 else "-- MB/s"
                    eta_str = f"ETA: {int(eta_s)}s" if eta_s > 0 else ""
                    bar_len = 16
                    filled = int(bar_len * (file_pct / 100.0))
                    bar = "=" * filled + (">" if filled < bar_len else "") + " " * max(0, bar_len - filled - (1 if filled < bar_len else 0))
                    terminal_line = f"\r    [{bar}] {file_pct:5.1f}% | {curr_bytes/(1024*1024):6.2f}/{est_total_bytes/(1024*1024):6.2f} MB | {speed_str:<9} | {eta_str:<8} | frame_mapping.json"
                    sys.stdout.write(terminal_line[:115])
                    sys.stdout.flush()

        if mapping_records and len(mapping_records) > 1:
            rec0 = mapping_records[0]
            recN = mapping_records[-1]
            dur = recN["radar_timestamp"] - rec0["radar_timestamp"]
            total_vid_frames = recN["video_frame_index"] - rec0["video_frame_index"]
            avg_fps = (total_vid_frames / dur) if dur > 0 else 30.0
            meta_record = {
                "metadata": {
                    "average_fps": float(avg_fps),
                    "duration_sec": float(dur),
                    "total_frames": int(total_vid_frames)
                }
            }
            mf.write(json.dumps(meta_record) + "\n")

    if sys.stdout.isatty():
        sys.stdout.write("\n")
        sys.stdout.flush()
    final_size = os.path.getsize(mapping_path)
    print(f"    [+] Generated: {mapping_path} ({len(mapping_records):,} sync records, {final_size / (1024 * 1024):.1f} MB)")


def stream_write_track_history(
    track_history_path: str,
    viz_radar_frames: list,
    tracks_trajectory_map,
    session_name: str,
    overall_start_pct: float = 72.0,
    overall_end_pct: float = 75.0,
):
    """
    Writes track_history.json as streaming chunked JSON with indent=2, emitting live
    percentage, MB/s throughput, and ETA updates to eliminate console/UI freezes.
    """
    os.makedirs(os.path.dirname(track_history_path), exist_ok=True)
    tracks_list = list(tracks_trajectory_map.values()) if isinstance(tracks_trajectory_map, dict) else list(tracks_trajectory_map)
    n_frames = len(viz_radar_frames)
    n_tracks = len(tracks_list)
    total_items = n_frames + n_tracks

    t_start = time.time()
    last_sample_t = t_start
    last_sample_bytes = 0
    rolling_speed = 0.0
    alpha = 0.25
    curr_item = 0

    with open(track_history_path, "w", encoding="utf-8") as tf:
        tf.write('{\n  "radarFrames": [')
        if n_frames > 0:
            tf.write("\n")
            for idx, frame in enumerate(viz_radar_frames, start=1):
                curr_item += 1
                s = json.dumps(frame, indent=2)
                indented = "    " + s.replace("\n", "\n    ")
                if idx < n_frames:
                    tf.write(indented + ",\n")
                else:
                    tf.write(indented + "\n")

                if idx % 250 == 0 or idx == n_frames:
                    now = time.time()
                    dt = now - last_sample_t
                    curr_bytes = tf.tell()
                    if dt >= 0.15:
                        inst_speed = max(0.0, (curr_bytes - last_sample_bytes) / dt)
                        rolling_speed = inst_speed if rolling_speed == 0.0 else (alpha * inst_speed + (1.0 - alpha) * rolling_speed)
                        last_sample_t = now
                        last_sample_bytes = curr_bytes

                    file_pct = (curr_item / max(total_items, 1)) * 100.0
                    est_total_bytes = (curr_bytes / max(curr_item, 1)) * max(total_items, 1)
                    eta_s = (est_total_bytes - curr_bytes) / max(rolling_speed, 1.0) if (est_total_bytes > curr_bytes and rolling_speed > 0) else 0.0
                    overall_pct = overall_start_pct + (curr_item / max(total_items, 1)) * (overall_end_pct - overall_start_pct)

                    progress_payload = {
                        "step": "sync_save",
                        "desc": f"[{session_name}] Writing track_history.json ({curr_item:,}/{total_items:,})",
                        "overall_pct": round(overall_pct, 1),
                        "current": curr_item,
                        "total": total_items,
                        "sub": {
                            "file_name": "track_history.json",
                            "file_pct": round(file_pct, 1),
                            "current_mb": round(curr_bytes / (1024 * 1024), 1),
                            "total_mb": round(est_total_bytes / (1024 * 1024), 1),
                            "speed_mb_s": round(rolling_speed / (1024 * 1024), 1),
                            "eta_s": round(eta_s, 1),
                        }
                    }
                    print(f"[PROGRESS] {json.dumps(progress_payload)}", flush=True)

                    if sys.stdout.isatty():
                        speed_str = f"{rolling_speed / (1024 * 1024):.1f} MB/s" if rolling_speed > 0 else "-- MB/s"
                        eta_str = f"ETA: {int(eta_s)}s" if eta_s > 0 else ""
                        bar_len = 16
                        filled = int(bar_len * (file_pct / 100.0))
                        bar = "=" * filled + (">" if filled < bar_len else "") + " " * max(0, bar_len - filled - (1 if filled < bar_len else 0))
                        terminal_line = f"\r    [{bar}] {file_pct:5.1f}% | {curr_bytes/(1024*1024):6.1f}/{est_total_bytes/(1024*1024):6.1f} MB | {speed_str:<9} | {eta_str:<8} | track_history.json"
                        sys.stdout.write(terminal_line[:115])
                        sys.stdout.flush()

            tf.write("  ],\n")
        else:
            tf.write("],\n")

        tf.write('  "tracks": [')
        if n_tracks > 0:
            tf.write("\n")
            for idx, trk in enumerate(tracks_list, start=1):
                curr_item += 1
                s = json.dumps(trk, indent=2)
                indented = "    " + s.replace("\n", "\n    ")
                if idx < n_tracks:
                    tf.write(indented + ",\n")
                else:
                    tf.write(indented + "\n")

                if idx % 100 == 0 or idx == n_tracks:
                    now = time.time()
                    dt = now - last_sample_t
                    curr_bytes = tf.tell()
                    if dt >= 0.15:
                        inst_speed = max(0.0, (curr_bytes - last_sample_bytes) / dt)
                        rolling_speed = inst_speed if rolling_speed == 0.0 else (alpha * inst_speed + (1.0 - alpha) * rolling_speed)
                        last_sample_t = now
                        last_sample_bytes = curr_bytes

                    file_pct = (curr_item / max(total_items, 1)) * 100.0
                    est_total_bytes = (curr_bytes / max(curr_item, 1)) * max(total_items, 1)
                    eta_s = (est_total_bytes - curr_bytes) / max(rolling_speed, 1.0) if (est_total_bytes > curr_bytes and rolling_speed > 0) else 0.0
                    overall_pct = overall_start_pct + (curr_item / max(total_items, 1)) * (overall_end_pct - overall_start_pct)

                    progress_payload = {
                        "step": "sync_save",
                        "desc": f"[{session_name}] Writing track_history.json ({curr_item:,}/{total_items:,})",
                        "overall_pct": round(overall_pct, 1),
                        "current": curr_item,
                        "total": total_items,
                        "sub": {
                            "file_name": "track_history.json",
                            "file_pct": round(file_pct, 1),
                            "current_mb": round(curr_bytes / (1024 * 1024), 1),
                            "total_mb": round(est_total_bytes / (1024 * 1024), 1),
                            "speed_mb_s": round(rolling_speed / (1024 * 1024), 1),
                            "eta_s": round(eta_s, 1),
                        }
                    }
                    print(f"[PROGRESS] {json.dumps(progress_payload)}", flush=True)

                    if sys.stdout.isatty():
                        speed_str = f"{rolling_speed / (1024 * 1024):.1f} MB/s" if rolling_speed > 0 else "-- MB/s"
                        eta_str = f"ETA: {int(eta_s)}s" if eta_s > 0 else ""
                        bar_len = 16
                        filled = int(bar_len * (file_pct / 100.0))
                        bar = "=" * filled + (">" if filled < bar_len else "") + " " * max(0, bar_len - filled - (1 if filled < bar_len else 0))
                        terminal_line = f"\r    [{bar}] {file_pct:5.1f}% | {curr_bytes/(1024*1024):6.1f}/{est_total_bytes/(1024*1024):6.1f} MB | {speed_str:<9} | {eta_str:<8} | track_history.json"
                        sys.stdout.write(terminal_line[:115])
                        sys.stdout.flush()

            tf.write("  ]\n}")
        else:
            tf.write("]\n}")

    if sys.stdout.isatty():
        sys.stdout.write("\n")
        sys.stdout.flush()
    final_size = os.path.getsize(track_history_path)
    print(f"    [+] Generated: {track_history_path} ({n_frames:,} frames, {n_tracks:,} unique tracks, {final_size / (1024 * 1024):.1f} MB)")


def process_session(session_dir, force=False, export_mcap=False, flip_video=False, hwaccel="auto"):
    """
    Converts raw session logs in `session_dir` into:
    1. track_history.json
    2. frame_mapping.json
    3. [Optional] Foxglove .mcap container

    If files already exist and are newer than radar_frames.bin, skips processing unless force=True.
    """
    track_history_path = os.path.join(session_dir, "track_history.json")
    mapping_path = os.path.join(session_dir, "frame_mapping.json")
    session_name = os.path.basename(session_dir.rstrip("\\/"))
    mcap_path = os.path.join(session_dir, f"{session_name}.mcap")
    radar_bin = os.path.join(session_dir, "radar", "radar_frames.bin")

    if not os.path.isfile(radar_bin):
        print(f"\n[-] Skipping {os.path.basename(session_dir)}: radar_frames.bin not found.")
        return False

    # Smart skip check: verify if already generated and up-to-date
    mcap_ready = (not export_mcap) or os.path.isfile(mcap_path)
    if not force and os.path.isfile(track_history_path) and os.path.isfile(mapping_path) and mcap_ready:
        radar_mtime = os.path.getmtime(radar_bin)
        th_mtime = os.path.getmtime(track_history_path)
        map_mtime = os.path.getmtime(mapping_path)
        mcap_mtime = os.path.getmtime(mcap_path) if (export_mcap and os.path.isfile(mcap_path)) else th_mtime

        if th_mtime > radar_mtime and map_mtime > radar_mtime and mcap_mtime > radar_mtime:
            print(f"\n[+] Skipping {os.path.basename(session_dir)}: all outputs are up to date.")
            return True
        if th_mtime >= radar_mtime and map_mtime >= radar_mtime:
            print(f"\n[=] Skipping {os.path.basename(session_dir)}: Already processed and up-to-date (use --force to reprocess).")
            return True

    print(f"\n[+] Processing Session: {os.path.basename(session_dir)}")
    print(f"[PROGRESS] {json.dumps({'step': 'sync_start', 'desc': f'[{session_name}] Decoding radar frames...', 'pct': 10})}", flush=True)
    camera_csv = os.path.join(session_dir, "camera", "camera_frames.csv")
    camera_mp4 = os.path.join(session_dir, "camera", "camera_video.mp4")
    gnss_csv = os.path.join(session_dir, "gnss", "gnss_fixes.csv")

    radar_frames = parse_radar_frames_bin(radar_bin)
    if not radar_frames:
        print(f"    [-] No valid radar frames could be decoded. Skipping.")
        return False
    print(f"    [+] Decoded {len(radar_frames):,} radar frames from binary stream.")

    cam_frames = load_camera_frames_csv(camera_csv)
    gnss_fixes = load_gnss_fixes_csv(gnss_csv)
    mp4_frame_count = get_mp4_frame_count(camera_mp4)
    if mp4_frame_count:
        print(f"    [+] Loaded {len(cam_frames):,} camera frames ({mp4_frame_count:,} frames in MP4) and {len(gnss_fixes):,} GNSS fixes.")
    else:
        print(f"    [+] Loaded {len(cam_frames):,} camera frames and {len(gnss_fixes):,} GNSS fixes.")

    meta_path = os.path.join(session_dir, "session_metadata.json")
    start_wall_ms = None
    start_mono_ns_meta = None
    if os.path.isfile(meta_path):
        try:
            with open(meta_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
                start_wall_ms = meta.get("startTimeWallMs")
                start_mono_ns_meta = meta.get("startTimeMonotonicNs")
        except Exception:
            pass

    ref_mono_ns = start_mono_ns_meta if start_mono_ns_meta else radar_frames[0]["mono_ns"]
    ref_wall_s = (start_wall_ms / 1000.0) if start_wall_ms else 0.0

    def mono_to_epoch_s(m_ns):
        if ref_wall_s > 0:
            return ref_wall_s + (m_ns - ref_mono_ns) / 1e9
        return m_ns / 1e9

    sync_master = None
    try:
        from scripts.master_timeline_synchronizer import MasterTimelineSynchronizer
        sync_master = MasterTimelineSynchronizer(session_dir)
        print(f"    [+] MasterTimelineSynchronizer active ({len(sync_master.recording_camera_events)} recording frames, {sync_master.pre_roll_count} pre-roll frames skipped).")
    except Exception as e:
        print(f"    [!] MasterTimelineSynchronizer not available ({e}).")

    # Inferred pre-roll fallback if MasterTimelineSynchronizer is unavailable
    inferred_preroll = 0
    if not sync_master and cam_frames:
        scan_limit = min(60, len(cam_frames))
        for i in range(1, scan_limit):
            dt_ms = (cam_frames[i]["mono_ns"] - cam_frames[i - 1]["mono_ns"]) / 1e6
            if dt_ms >= 200.0:
                inferred_preroll = i
                break
        if inferred_preroll > 0:
            print(f"    [+] Inferred pre-roll boundary: {inferred_preroll} preview frames skipped.")

    # Pre-index GNSS timestamps for fast O(log M) binary search
    gnss_mono_times = [g["mono_ns"] for g in gnss_fixes] if gnss_fixes else []

    mapping_records = []
    viz_radar_frames = []
    tracks_trajectory_map = {}

    start_mono_ns = radar_frames[0]["mono_ns"]

    for rel_idx, rf in enumerate(radar_frames, start=1):
        if rel_idx % 500 == 0:
            pct = round(15.0 + (rel_idx / len(radar_frames)) * 55.0, 1)
            print(f"[PROGRESS] {json.dumps({'step': 'sync_align', 'desc': f'[{session_name}] Aligning frame #{rel_idx:,} / {len(radar_frames):,}', 'current': rel_idx, 'total': len(radar_frames), 'pct': pct})}", flush=True)
        hw_fn = rf["hw_frame_num"]
        mono_ns = rf["mono_ns"]
        timestamp_ms = (mono_ns - start_mono_ns) / 1e6

        if sync_master and sync_master.recording_camera_events:
            v_frame_idx, v_time_delta, v_cam_mono_ns, cam_hw_fn = sync_master.match_radar_to_camera(
                mono_ns, max_video_frames=mp4_frame_count
            )
        else:
            v_frame_idx, v_time_delta, v_cam_mono_ns, cam_hw_fn = find_nearest_camera_frame(
                cam_frames, mono_ns, max_video_frames=mp4_frame_count, preroll_offset=inferred_preroll
            )

        if v_frame_idx is not None:
            mapping_records.append({
                "radar_frame_id_rel": rel_idx,
                "radar_frame_id_abs": hw_fn,
                "radar_timestamp": safe_float(mono_to_epoch_s(mono_ns), round_digits=None),
                "video_frame_index": v_frame_idx,
                "video_frame_ts": safe_float(mono_to_epoch_s(v_cam_mono_ns), round_digits=None),
                "video_time_delta": safe_float(v_time_delta, round_digits=None),
                "camera_hw_frame_number": cam_hw_fn
            })

        # Fallback ego speed from GNSS via fast binary search
        ego_speed_kmh = 0.0
        if gnss_mono_times:
            pos = bisect.bisect_left(gnss_mono_times, mono_ns)
            candidates = [p for p in (pos - 1, pos) if 0 <= p < len(gnss_mono_times)]
            if candidates:
                best_g = min(candidates, key=lambda p: abs(gnss_mono_times[p] - mono_ns))
                if abs(gnss_mono_times[best_g] - mono_ns) < 2e9:
                    ego_speed_kmh = gnss_fixes[best_g]["speed_kmh"]
        ego_vy_mps = safe_float(ego_speed_kmh / 3.6)

        # TLV 4 Tracker Diagnostics
        diags = rf.get("diags")
        motion_state = diags["motion_state"] if diags else 0
        left_barrier = diags["road_boundary_left_x"] if diags else -10.0
        right_barrier = diags["road_boundary_right_x"] if diags else 10.0
        t_track_ms = (diags["tracker_proc_time_us"] / 1000.0) if diags else 0.0
        ego_vx_mps = diags["ego_vx"] if diags else 0.0
        if diags and abs(diags.get("ego_vy", 0.0)) > 0.01:
            ego_vy_mps = diags["ego_vy"]

        # TLV 5 Vehicle CAN Inputs
        can_in = rf.get("can_inputs")
        if can_in:
            veh_speed_kmph = can_in["speed_kmph"]
            accel_pedal = can_in.get("accel_pedal_pct", 0.0)
            torque_nm = can_in.get("shaft_torque_nm", 0.0)
            gear = can_in.get("gear", 0)
            road_grade = can_in.get("road_grade_deg", 0.0)
            accel_avg = can_in.get("accel_avg_mps2", 0.0)
            if motion_state == 0 and can_in.get("motion_state", 0) != 0:
                motion_state = can_in["motion_state"]
        else:
            veh_speed_kmph = safe_float(ego_speed_kmh)
            accel_pedal = 0.0
            torque_nm = 0.0
            gear = 0
            road_grade = 0.0
            accel_avg = 0.0

        sensor_stats = [{
            "frame_number": hw_fn,
            "cpu_cycles": rf["cpu_cycles"],
            "subframe": rf["subframe"]
        }]

        # TLV 6 ADAS CAN Outputs
        adas_can = rf.get("adas_can")
        if adas_can:
            adas_data = [adas_can]
        else:
            adas_data = [{
                "poi_id": 0,
                "acc_dist": 0.0,
                "acc_target_x": 0.0,
                "acc_rel_v": 0.0,
                "acc_rel_a": 0.0,
                "acc_status": 0,
                "tti": 10.23,
                "v_safe_mps": 0.0,
                "aeb_risk": 0,
                "fcw_stage": 0,
                "fcw_track_id": 0,
                "fcw_target_x": 0.0,
                "fcw_target_y": 0.0,
                "fcw_target_vx": 0.0,
                "fcw_target_vy": 0.0,
                "fcw_ttc": 10.23,
                "brake_prefill_req": False,
                "target_confidence": 0.0,
                "bsd_left_active": False,
                "bsd_right_active": False,
                "lca_warning_level": 0,
                "approach_ttc": 10.23,
                "corridor_width": 3.75,
                "sensor_blindness": 0
            }]

        perf_stats = [{
            "t_pre_ms": 0.0,
            "t_track_ms": safe_float(t_track_ms),
            "t_total_ms": safe_float(t_track_ms)
        }]

        viz_frame = {
            "frameIdx": rel_idx,
            "timestamp": safe_float(timestamp_ms),
            "numPoints": len(rf["pointCloud"]),
            "motionState": motion_state,
            "egoVelocity": [safe_float(ego_vx_mps), safe_float(ego_vy_mps)],
            "canVehSpeed_kmph": safe_float(veh_speed_kmph),
            "AccelPedal_Act_perc": safe_float(accel_pedal),
            "shaftTorque_Nm": safe_float(torque_nm),
            "engagedGear": gear,
            "roadGrade_Deg": safe_float(road_grade),
            "acceleration_avg": safe_float(accel_avg),
            "sensorStats": sensor_stats,
            "video_frame_index": v_frame_idx,
            "video_time_delta": v_time_delta,
            "filtered_barrier_x": [safe_float(left_barrier), safe_float(right_barrier)],
            "adas": adas_data,
            "performance_stats": perf_stats,
            "pointCloud": rf["pointCloud"],
            "clusters": rf["clusters"]
        }
        viz_radar_frames.append(viz_frame)

        for trk in rf["tracks"]:
            tid = trk["tid"]
            x = trk["x"]
            y = trk["y"]
            vx = trk["vx"]
            vy = trk["vy"]
            maj_rad = float(trk.get("major_size", trk.get("majorSize", trk.get("xSize", 2.0))))
            min_rad = float(trk.get("minor_size", trk.get("minorSize", trk.get("ySize", 1.2))))
            orientation = trk.get("orientation", 0.0)
            risk = trk.get("risk", 0)
            tti = trk.get("tti", 100.0)
            is_stat = trk.get("isStationary", False)

            # Aligned 100% with Gitea Source of Truth (convert_gtrack_to_track_history_v2.py line 614 & 655):
            # "objectExtentRadii": [maj, min_dim]
            object_extent_radii = [
                safe_float(maj_rad if maj_rad > 0 else 2.0),
                safe_float(min_rad if min_rad > 0 else 0.9)
            ]

            fsm_state = trk["status"]
            if fsm_state not in [1, 2, 3, 4]:
                fsm_state = 3

            history_entry = {
                "frameIdx": rel_idx,
                "state": fsm_state,
                "correctedPosition": [x, y],
                "predictedPosition": [x, y],
                "correctedVelocity": [vx, vy],
                "predictedVelocity": [vx, vy],
                "accel": [0.0, trk["aux"]],
                "omega": 0.0,
                "modelProbabilities": [1.0, 0.0, 0.0],
                "ttc": 100.0,
                "risk": risk,
                "tti": safe_float(tti),
                "isStationary": is_stat,
                "covarianceP": [[0.0] * 7 for _ in range(7)],
                "ellipseRadii": [0.0, 0.0],
                "ellipseAngle": 0.0,
                "objectExtentRadii": object_extent_radii,
                "objectExtentAngle": safe_float(orientation)
            }

            if tid not in tracks_trajectory_map:
                tracks_trajectory_map[tid] = {
                    "id": tid,
                    "isConfirmed": (fsm_state >= 3),
                    "historyLog": []
                }
            elif fsm_state >= 3:
                tracks_trajectory_map[tid]["isConfirmed"] = True

            tracks_trajectory_map[tid]["historyLog"].append(history_entry)

    # Determine progress ranges based on export_mcap
    if export_mcap:
        map_start, map_end = 70.0, 71.5
        th_start, th_end = 71.5, 75.0
    else:
        map_start, map_end = 70.0, 73.0
        th_start, th_end = 73.0, 99.0

    # Write `frame_mapping.json` as JSON Lines (.jsonl format) with real-time throughput and progress updates
    stream_write_frame_mapping(
        mapping_path,
        mapping_records,
        session_name=session_name,
        overall_start_pct=map_start,
        overall_end_pct=map_end,
    )

    # Write `track_history.json` with streaming chunked JSON writer and real-time progress updates
    stream_write_track_history(
        track_history_path,
        viz_radar_frames,
        tracks_trajectory_map,
        session_name=session_name,
        overall_start_pct=th_start,
        overall_end_pct=th_end,
    )

    if os.path.isfile(camera_mp4):
        print(f"    [+] Camera MP4 verified: {camera_mp4}")
    else:
        print(f"    [!] Note: camera_video.mp4 is missing in {session_dir}/camera")

    # Optional MCAP export
    if export_mcap:
        try:
            print(f"[PROGRESS] {json.dumps({'step': 'mcap_start', 'desc': f'[{session_name}] Launching Foxglove MCAP export...', 'pct': 75})}", flush=True)
            script_dir = os.path.dirname(os.path.abspath(__file__))
            if script_dir not in sys.path:
                sys.path.insert(0, script_dir)
            from convert_session_to_mcap import convert_session_to_mcap
            convert_session_to_mcap(session_dir, flip_video=flip_video, hwaccel=hwaccel)
        except Exception as e:
            print(f"    [!] Error generating MCAP: {e}")
    else:
        print(f"[PROGRESS] {json.dumps({'step': 'done', 'desc': f'[{session_name}] Visualizer processing complete', 'pct': 100, 'overall_pct': 100})}", flush=True)

    return True

def main():
    parser = argparse.ArgumentParser(description="RoadSense Automated Sync & Visualizer Processing Pipeline")
    parser.add_argument("--logs-dir", default=DEFAULT_LOGS_DIR, help="Local directory where sessions are stored")
    parser.add_argument("--sync-only", action="store_true", help="Only sync from device, do not process")
    parser.add_argument("--process-only", action="store_true", help="Only process existing local sessions")
    parser.add_argument("--session", default=None, help="Process a single specific session ID (e.g. session_20260910_093816)")
    parser.add_argument("--force", action="store_true", help="Force re-processing even if session is already up-to-date")
    parser.add_argument("--force-all", action="store_true", help="Force re-processing and re-generating visualizer files for ALL sessions")
    parser.add_argument("--mcap", action="store_true", help="Automatically generate Foxglove .mcap file alongside visualizer JSON")
    parser.add_argument("--flip-video", action="store_true", help="Physically rotate video 180° during MCAP conversion")
    parser.add_argument("--hwaccel", choices=["auto", "nvenc", "cpu"], default="auto", help="Hardware acceleration mode for video re-encoding (default: auto)")
    args = parser.parse_args()

    if args.force_all:
        args.force = True

    print("=" * 80)
    print(" RoadSense - Automated Session Sync & Visualizer Processing Pipeline")
    print("=" * 80)

    # Step 1: Sync from device
    if not args.process_only:
        adb = find_adb()
        if adb:
            sync_sessions_from_device(adb, args.logs_dir)
        else:
            print("[-] adb executable not found on path or Android SDK. Skipping auto-sync.")

    if args.sync_only:
        print("\n[+] Sync-only complete.")
        return

    # Step 2: Discover and process sessions
    session_dirs = []
    if args.session:
        target = os.path.join(args.logs_dir, args.session)
        if os.path.isdir(target):
            session_dirs.append(target)
        else:
            print(f"[-] Specified session directory not found: {target}")
            sys.exit(1)
    else:
        for item in sorted(os.listdir(args.logs_dir)):
            full_p = os.path.join(args.logs_dir, item)
            if os.path.isdir(full_p) and item.startswith("session_"):
                session_dirs.append(full_p)

    if not session_dirs:
        print(f"[-] No session folders found in {args.logs_dir}")
        return

    print(f"\n[+] Found {len(session_dirs)} sessions to process.")
    success_count = 0
    for s_dir in session_dirs:
        try:
            if process_session(s_dir, force=args.force, export_mcap=args.mcap, flip_video=args.flip_video, hwaccel=args.hwaccel):
                success_count += 1
        except Exception as e:
            print(f"    [!] Error processing {os.path.basename(s_dir)}: {e}")
            import traceback
            traceback.print_exc()

    print("\n" + "=" * 80)
    print(f" Pipeline Finished: Successfully processed {success_count}/{len(session_dirs)} sessions.")
    print("=" * 80)

if __name__ == "__main__":
    main()
