#!/usr/bin/env python3
"""
RoadSense MCAP (Foxglove) Multimodal Converter Engine

Converts a RoadSense multi-sensor recording session directory into a standardized,
self-contained, Zstandard-compressed Foxglove MCAP (.mcap) file.

Supported Channels & Modalities:
  - /radar/points        : foxglove.PointCloud (3D Doppler-colored point cloud, radar_link)
  - /radar/tracks        : foxglove.SceneUpdate (3D oriented cuboids, velocity vectors, labels)
  - /radar/diagnostics   : roadsense.RadarDiagnostics (TLV 4 tracker stats, ego dynamics, road boundaries)
  - /camera/video        : foxglove.CompressedVideo (H.264 Annex B bitstream, camera_optical)
  - /camera/calib        : foxglove.CameraCalibration (Pinhole intrinsics K, D, R, P)
  - /gnss/fix            : foxglove.LocationFix (GPS satellite track, speed, bearing)
  - /vehicle/telemetry   : roadsense.VehicleTelemetry (Powertrain CAN / TLV 5 dynamics)
  - /tf                  : foxglove.FrameTransforms (6-DOF extrinsics base_link -> radar_link, camera_optical)
  - /diagnostics/logs    : foxglove.Log (Session flight recorder logs)

Attachments:
  - session_metadata.json
  - radar_camera_calib.json
"""

import os
import sys
import math
import glob
import json
import time
import struct
import argparse
from datetime import datetime, timezone

# Ensure local dependencies are present
try:
    from mcap_protobuf.writer import Writer as ProtobufWriter
    from mcap.writer import CompressionType
    from foxglove_schemas_protobuf.PointCloud_pb2 import PointCloud
    from foxglove_schemas_protobuf.PackedElementField_pb2 import PackedElementField
    from foxglove_schemas_protobuf.SceneUpdate_pb2 import SceneUpdate
    from foxglove_schemas_protobuf.LocationFix_pb2 import LocationFix
    from foxglove_schemas_protobuf.CameraCalibration_pb2 import CameraCalibration
    from foxglove_schemas_protobuf.FrameTransforms_pb2 import FrameTransforms
    from foxglove_schemas_protobuf.Log_pb2 import Log
    from foxglove_schemas_protobuf.Vector3_pb2 import Vector3
    from foxglove_schemas_protobuf.Quaternion_pb2 import Quaternion
    from foxglove_schemas_protobuf.Color_pb2 import Color
    from google.protobuf.timestamp_pb2 import Timestamp
except ImportError as e:
    print(f"[-] Missing Foxglove MCAP dependencies: {e}")
    print("[-] Please run: conda activate roadsense-mcap && pip install -r tools/requirements-mcap.txt")
    sys.exit(1)

# PyAV for H.264 video extraction
try:
    import av
    HAS_PYAV = True
except ImportError:
    HAS_PYAV = False

# RoadSense Framing Constants
ROAD_MAGIC = b"ROAD"
ROAD_HEADER_SIZE = 24  # >4sQQI (magic, monoNs, wallMs, packetLen)
TI_MAGIC = bytes([0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07])
TI_HEADER_SIZE = 40


def safe_float(val, default=0.0):
    if val is None:
        return default
    try:
        f = float(val)
        if math.isnan(f) or math.isinf(f):
            return default
        return round(f, 4)
    except (ValueError, TypeError):
        return default


def euler_to_quaternion(roll_rad, pitch_rad, yaw_rad):
    """Converts Euler angles (Roll-Pitch-Yaw / XYZ intrinsic) to Quaternion [x, y, z, w]."""
    cy = math.cos(yaw_rad * 0.5)
    sy = math.sin(yaw_rad * 0.5)
    cp = math.cos(pitch_rad * 0.5)
    sp = math.sin(pitch_rad * 0.5)
    cr = math.cos(roll_rad * 0.5)
    sr = math.sin(roll_rad * 0.5)

    w = cr * cp * cy + sr * sp * sy
    x = sr * cp * cy - cr * sp * sy
    y = cr * sp * cy + sr * cp * sy
    z = cr * cp * sy - sr * sp * cy
    return x, y, z, w


def compute_camera_optical_tf(pitch_deg, yaw_deg, roll_deg, setback, lateral, height):
    """
    Computes exact ROS TF FrameTransform quaternion and translation from base_link to camera_optical
    matching RoadSense SpatialProjectionEngine.kt.
    """
    pitch = math.radians(pitch_deg)
    yaw = math.radians(yaw_deg)
    roll = math.radians(roll_deg)

    # Base optical transform (base_link FLU -> camera_optical RDF)
    # X_c = -Y_b (right), Y_c = -Z_b (down), Z_c = X_b (forward)
    import numpy as np
    R0 = np.array([
        [ 0, -1,  0],
        [ 0,  0, -1],
        [ 1,  0,  0]
    ], dtype=float)

    # Extrinsic rotations in camera optical frame
    # Yaw (pan around camera Y axis, pointing down)
    Ry = np.array([
        [ math.cos(yaw), 0, -math.sin(yaw)],
        [ 0,             1,  0],
        [ math.sin(yaw), 0,  math.cos(yaw)]
    ], dtype=float)

    # Pitch (tilt around camera X axis, pointing right)
    Rx = np.array([
        [ 1, 0,                0],
        [ 0, math.cos(pitch),  math.sin(pitch)],
        [ 0, -math.sin(pitch), math.cos(pitch)]
    ], dtype=float)

    # Roll (roll around camera Z axis, pointing forward)
    Rz = np.array([
        [ math.cos(roll), math.sin(roll), 0],
        [-math.sin(roll), math.cos(roll), 0],
        [ 0,              0,              1]
    ], dtype=float)

    R_ext = Rz @ Rx @ Ry
    R_total = R_ext @ R0
    R_tf = R_total.T

    tr = R_tf[0, 0] + R_tf[1, 1] + R_tf[2, 2]
    if tr > 0:
        S = math.sqrt(tr + 1.0) * 2
        qx = (R_tf[2, 1] - R_tf[1, 2]) / S
        qy = (R_tf[0, 2] - R_tf[2, 0]) / S
        qz = (R_tf[1, 0] - R_tf[0, 1]) / S
        qw = 0.25 * S
    elif (R_tf[0, 0] > R_tf[1, 1]) and (R_tf[0, 0] > R_tf[2, 2]):
        S = math.sqrt(1.0 + R_tf[0, 0] - R_tf[1, 1] - R_tf[2, 2]) * 2
        qx = 0.25 * S
        qy = (R_tf[0, 1] + R_tf[1, 0]) / S
        qz = (R_tf[0, 2] + R_tf[2, 0]) / S
        qw = (R_tf[2, 1] - R_tf[1, 2]) / S
    elif R_tf[1, 1] > R_tf[2, 2]:
        S = math.sqrt(1.0 + R_tf[1, 1] - R_tf[0, 0] - R_tf[2, 2]) * 2
        qx = (R_tf[0, 1] + R_tf[1, 0]) / S
        qy = 0.25 * S
        qz = (R_tf[1, 2] + R_tf[2, 1]) / S
        qw = (R_tf[0, 2] - R_tf[2, 0]) / S
    else:
        S = math.sqrt(1.0 + R_tf[2, 2] - R_tf[0, 0] - R_tf[1, 1]) * 2
        qx = (R_tf[0, 2] + R_tf[2, 0]) / S
        qy = (R_tf[1, 2] + R_tf[2, 1]) / S
        qz = 0.25 * S
        qw = (R_tf[1, 0] - R_tf[0, 1]) / S

    norm = math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw)
    quat = (float(qx / norm), float(qy / norm), float(qz / norm), float(qw / norm))
    trans = (-setback, -lateral, height)
    return quat, trans


def parse_radar_stream(radar_bin_path):
    """
    Generator yielding radar frames parsed from radar_frames.bin with nanosecond timestamps.
    Handles adaptive 12B/10B point clouds, 28B/20B/14B tracks, and TLVs 4-6.
    """
    if not os.path.isfile(radar_bin_path):
        return

    with open(radar_bin_path, "rb") as f:
        data = f.read()

    total_len = len(data)
    offset = 0

    while offset + ROAD_HEADER_SIZE <= total_len:
        if data[offset : offset + 4] != ROAD_MAGIC:
            next_sync = data.find(ROAD_MAGIC, offset + 1)
            if next_sync == -1:
                break
            offset = next_sync
            if offset + ROAD_HEADER_SIZE > total_len:
                break

        _, mono_ns, wall_ms, packet_len = struct.unpack_from(">4sQQI", data, offset)
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

        frame_data = {
            "mono_ns": mono_ns,
            "wall_ms": wall_ms,
            "hw_frame_num": frame_num,
            "subframe": subframe,
            "points": [],
            "tracks": [],
            "diags": None,
            "can_inputs": None
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

            # --- TLV 1, 2, 3: Array TLVs (<HH: num_objs, q_format) ---
            if tlv_type in (1, 2, 3):
                num_objs, q_format = struct.unpack_from("<HH", tlv_data, 0)
                if q_format > 31:
                    q_format = 15
                inv_q = 1.0 / (1 << q_format)
                payload_size = len(tlv_data) - 4

                # TLV 1: Point Cloud (12B Extended / 10B Legacy)
                if tlv_type == 1:
                    stride = 12 if ((num_objs > 0 and (payload_size // num_objs) >= 12) or (payload_size % 12 == 0 and payload_size > 0)) else 10
                    count = min(num_objs, payload_size // stride) if num_objs > 0 else (payload_size // stride)
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
                        snr_db = (peak_raw / 512.0) * 6.0206
                        frame_data["points"].append((
                            safe_float(y_raw * inv_q),       # Forward distance (ROS +X)
                            safe_float(-x_raw * inv_q),      # Left lateral offset (ROS +Y)
                            safe_float(z_raw * inv_q),       # Up elevation (ROS +Z)
                            safe_float(doppler_raw * inv_q), # Radial Doppler velocity (m/s)
                            safe_float(snr_db)               # SNR (dB)
                        ))

                # TLV 3: EKF Tracked Objects (28B v2.2 / 20B / 14B / 12B)
                elif tlv_type == 3:
                    calc_stride = (payload_size // num_objs) if num_objs > 0 else 0
                    stride = 28 if calc_stride >= 28 else (20 if calc_stride >= 20 else (14 if calc_stride >= 14 else (
                        28 if (payload_size % 28 == 0 and payload_size > 0) else 14
                    )))
                    count = min(num_objs, payload_size // stride) if num_objs > 0 else (payload_size // stride)
                    for i in range(count):
                        toff = 4 + i * stride
                        if stride >= 28:
                            x_raw, y_raw, vx_raw, vy_raw, maj_raw, min_raw, ori_raw, tid, status, cid, tti_raw, risk, is_stat, ttc_cat, conf, res = struct.unpack_from(
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
                        elif stride >= 20:
                            x_raw, y_raw, vx_raw, vy_raw, maj_raw, min_raw, ori_raw, tid, status, res = struct.unpack_from(
                                "<hhhhhhhHHH", tlv_data, toff
                            )
                            x_val, y_val, vx_val, vy_val = x_raw * inv_q, y_raw * inv_q, vx_raw * inv_q, vy_raw * inv_q
                            maj_val, min_val = maj_raw * inv_q, min_raw * inv_q
                            ori_deg, tti_sec, risk_val = ori_raw * 0.1, 100.0, 0
                        else:
                            x_raw, y_raw, vx_raw, vy_raw, xs_raw, ys_raw, tid = struct.unpack_from(
                                "<hhhhhhH", tlv_data, toff
                            )
                            x_val, y_val, vx_val, vy_val = x_raw * inv_q, y_raw * inv_q, vx_raw * inv_q, vy_raw * inv_q
                            maj_val, min_val, ori_deg, status, tti_sec, risk_val = xs_raw * inv_q, ys_raw * inv_q, 0.0, 3, 100.0, 0

                        if status == 0 or status == 5 or (x_val == 0.0 and y_val == 0.0 and vx_val == 0.0 and vy_val == 0.0):
                            continue

                        frame_data["tracks"].append({
                            "tid": int(tid),
                            "status": int(status),
                            "x_fwd": safe_float(y_val),      # Forward distance in vehicle frame (m)
                            "y_left": safe_float(-x_val),    # Left lateral offset in vehicle frame (m)
                            "vx_fwd": safe_float(vy_val),    # Forward velocity (m/s)
                            "vy_left": safe_float(-vx_val),  # Lateral velocity (m/s)
                            "length": max(safe_float(min_val), 1.2), # Length along vehicle longitudinal axis
                            "width": max(safe_float(maj_val), 0.8),  # Width along vehicle lateral axis
                            "height": 1.5,
                            "heading_deg": safe_float(ori_deg),
                            "tti": safe_float(tti_sec),
                            "risk": risk_val
                        })

            # TLV 4: Tracker Diagnostics (48 Bytes)
            elif tlv_type == 4 and len(tlv_data) >= 48:
                d = struct.unpack_from("<HBb10fHBB", tlv_data, 0)
                frame_data["diags"] = {
                    "num_inliers": int(d[0]),
                    "ransac_ok": bool(d[1]),
                    "motion_state": int(d[2]),
                    "filtered_vx_iir": safe_float(d[3]),
                    "filtered_vy_iir": safe_float(d[4]),
                    "ego_vy": safe_float(d[5]),
                    "ego_vx": safe_float(d[6]),
                    "ego_ax": safe_float(d[7]),
                    "ego_ay": safe_float(d[8]),
                    "ego_yaw_rate": safe_float(d[9]),
                    "road_boundary_left_x": safe_float(d[10]),
                    "road_boundary_right_x": safe_float(d[11]),
                    "tracker_proc_time_us": int(d[13])
                }

            # TLV 5: Powertrain & Vehicle CAN Inputs (56 Bytes)
            elif tlv_type == 5 and len(tlv_data) >= 56:
                c = struct.unpack_from("<fffffffffffIBBbBB3s", tlv_data, 0)
                frame_data["can_inputs"] = {
                    "speed_kmph": safe_float(c[0]),
                    "yaw_rate_radps": safe_float(c[1]),
                    "pitch_rate_radps": safe_float(c[2]),
                    "roll_rate_radps": safe_float(c[3]),
                    "accel_x_mps2": safe_float(c[4]),
                    "accel_y_mps2": safe_float(c[5]),
                    "accel_avg_mps2": safe_float(c[6])
                }

        yield frame_data


def parse_gnss_csv(gnss_csv_path):
    """Generator yielding GNSS fixes from gnss_fixes.csv with nanosecond timestamps."""
    if not os.path.isfile(gnss_csv_path):
        return
    import csv
    with open(gnss_csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                mono_ns = int(row["elapsed_realtime_ns"])
                lat = float(row["latitude"])
                lon = float(row["longitude"])
                alt = float(row["altitude_m"]) if row.get("altitude_m") else 0.0
                speed = float(row["speed_mps"]) if row.get("speed_mps") else 0.0
                bearing = float(row["bearing_deg"]) if row.get("bearing_deg") else 0.0
                yield {
                    "mono_ns": mono_ns,
                    "lat": lat,
                    "lon": lon,
                    "alt": alt,
                    "speed": speed,
                    "bearing": bearing
                }
            except (ValueError, KeyError):
                continue


def parse_camera_video_frames(video_mp4_path, frames_csv_path, flip_video=False):
    """
    Generator yielding Annex B H.264 video frame packets from camera_video.mp4,
    synchronized with exact shutter monotonic timestamps from camera_frames.csv.
    If flip_video is True, frames are physically rotated 180° (vflip + hflip) and re-encoded
    so the raw bitstream is upright in all external players.
    """
    if not HAS_PYAV or not os.path.isfile(video_mp4_path) or not os.path.isfile(frames_csv_path):
        return

    import csv
    shutter_timestamps = []
    with open(frames_csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                shutter_timestamps.append(int(row["shutter_monotonic_ns"]))
            except (ValueError, KeyError):
                continue

    if not shutter_timestamps:
        return

    try:
        container = av.open(video_mp4_path)
        stream = container.streams.video[0]

        if flip_video:
            # Physical 180° flip (hflip + vflip) and fast re-encode
            import io
            out_buf = io.BytesIO()
            out_container = av.open(out_buf, mode="w", format="h264")
            out_stream = out_container.add_stream("libx264", rate=30)
            out_stream.width = stream.width
            out_stream.height = stream.height
            out_stream.pix_fmt = "yuv420p"
            out_stream.options = {"preset": "ultrafast", "tune": "zerolatency"}

            graph = av.filter.Graph()
            src = graph.add_buffer(template=stream)
            hflip = graph.add("hflip")
            vflip = graph.add("vflip")
            sink = graph.add("buffersink")
            src.link_to(hflip)
            hflip.link_to(vflip)
            vflip.link_to(sink)
            graph.configure()

            frame_idx = 0
            for frame in container.decode(video=0):
                graph.push(frame)
                out_frame = graph.pull()
                packets = out_stream.encode(out_frame)
                for p in packets:
                    raw_bytes = bytes(p)
                    if frame_idx < len(shutter_timestamps):
                        mono_ns = shutter_timestamps[frame_idx]
                    else:
                        mono_ns = shutter_timestamps[-1] + (frame_idx - len(shutter_timestamps) + 1) * 33_333_333

                    yield {
                        "mono_ns": mono_ns,
                        "data": raw_bytes,
                        "is_keyframe": p.is_keyframe,
                        "width": stream.width,
                        "height": stream.height
                    }
                    frame_idx += 1

            # Flush encoder
            for p in out_stream.encode(None):
                raw_bytes = bytes(p)
                yield {
                    "mono_ns": shutter_timestamps[-1],
                    "data": raw_bytes,
                    "is_keyframe": p.is_keyframe,
                    "width": stream.width,
                    "height": stream.height
                }
        else:
            # Zero-copy fast Annex B bitstream extraction
            bsf = av.BitStreamFilterContext("h264_mp4toannexb", stream)
            frame_idx = 0
            for packet in container.demux(stream):
                if packet.dts is None:
                    continue
                annexb_packets = bsf.filter(packet)
                for p in annexb_packets:
                    raw_bytes = bytes(p)
                    if frame_idx < len(shutter_timestamps):
                        mono_ns = shutter_timestamps[frame_idx]
                    else:
                        mono_ns = shutter_timestamps[-1] + (frame_idx - len(shutter_timestamps) + 1) * 33_333_333

                    yield {
                        "mono_ns": mono_ns,
                        "data": raw_bytes,
                        "is_keyframe": p.is_keyframe,
                        "width": stream.width,
                        "height": stream.height
                    }
                    frame_idx += 1
        container.close()
    except Exception as e:
        print(f"[-] Video processing warning: {e}")


def parse_flight_recorder_logs(session_log_path, start_wall_ms, start_iso_str=None):
    """
    Generator yielding diagnostic flight recorder logs from session_debug.log.
    Computes elapsed time relative to session start to guarantee timezone independence.
    Filters out pre-session breadcrumbs recorded before recording started.
    """
    if not os.path.isfile(session_log_path):
        return

    import re
    log_pattern = re.compile(r"^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\]\s*\[(\w+)\s*\]\s*\[(\w+)\]\s*(.*)$")
    level_map = {
        "DEBUG": Log.Level.DEBUG,
        "INFO": Log.Level.INFO,
        "WARN": Log.Level.WARNING,
        "WARNING": Log.Level.WARNING,
        "ERROR": Log.Level.ERROR,
        "FATAL": Log.Level.FATAL
    }

    start_dt = None
    if start_iso_str:
        try:
            clean_str = start_iso_str[:23].replace("T", " ")
            start_dt = datetime.strptime(clean_str, "%Y-%m-%d %H:%M:%S.%f")
        except Exception:
            start_dt = None

    with open(session_log_path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            m = log_pattern.match(line.strip())
            if not m:
                continue
            dt_str, lvl_str, tag, msg = m.groups()
            try:
                dt = datetime.strptime(dt_str, "%Y-%m-%d %H:%M:%S.%f")
                if start_dt:
                    delta_sec = (dt - start_dt).total_seconds()
                    # Filter out pre-session breadcrumbs recorded minutes before recording started
                    if delta_sec < -2.0:
                        continue
                    utc_ns = (start_wall_ms * 1_000_000) + int(delta_sec * 1_000_000_000)
                else:
                    utc_ns = int(dt.timestamp() * 1_000_000_000)

                lvl = level_map.get(lvl_str.strip().upper(), Log.Level.INFO)
                yield {
                    "utc_ns": utc_ns,
                    "level": lvl,
                    "tag": tag,
                    "message": msg
                }
            except Exception:
                continue


def convert_session_to_mcap(session_dir, output_path=None, include_video=True, flip_video=False):
    """
    Main conversion entrypoint. Ingests all session data and compiles a unified Foxglove MCAP.
    If flip_video is True, physically rotates video frames 180° during conversion.
    """
    session_dir = os.path.abspath(session_dir)
    session_name = os.path.basename(session_dir.rstrip("\\/"))

    if not output_path:
        output_path = os.path.join(session_dir, f"{session_name}.mcap")

    print(f"\n========================================================")
    print(f" RoadSense -> Foxglove MCAP Conversion Engine")
    print(f" Session: {session_name}")
    print(f" Source:  {session_dir}")
    print(f" Output:  {output_path}")
    print(f"========================================================")

    # 1. Load Session Metadata & Establish UTC Reference Epoch
    meta_path = os.path.join(session_dir, "session_metadata.json")
    start_wall_ms = 0
    start_mono_ns = 0
    start_iso_str = ""
    if os.path.isfile(meta_path):
        try:
            with open(meta_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
                start_wall_ms = meta.get("startTimeWallMs", 0)
                start_mono_ns = meta.get("startTimeMonotonicNs", 0)
                start_iso_str = meta.get("startTimeIso", "")
        except Exception as e:
            print(f"[-] Could not read session metadata: {e}")

    # Fallback to current time if metadata is missing
    if start_wall_ms == 0 or start_mono_ns == 0:
        start_wall_ms = int(time.time() * 1000)
        start_mono_ns = 0

    # Nanosecond conversion factor between monotonic clock and UTC wall clock
    time_offset_ns = (start_wall_ms * 1_000_000) - start_mono_ns

    # 2. Load Extrinsic Calibration (or fallback defaults)
    calib_file = os.path.join(session_dir, "radar_camera_calib.json")
    if not os.path.isfile(calib_file):
        # Check global app directory
        alt_calib = os.path.join(os.path.dirname(session_dir), "..", "files", "calibration", "radar_camera_calib.json")
        if os.path.isfile(alt_calib):
            calib_file = alt_calib

    calib_params = {
        "pitchDeg": 7.0,
        "yawDeg": -1.0,
        "rollDeg": 0.0,
        "setbackM": 0.30,
        "heightOffsetM": 0.50,
        "lateralOffsetM": 0.00,
        "radarHeightM": 0.95
    }
    if os.path.isfile(calib_file):
        try:
            with open(calib_file, "r", encoding="utf-8") as f:
                cdata = json.load(f)
                calib_params.update(cdata)
                print(f"[+] Loaded calibration: Pitch={calib_params['pitchDeg']}°, Yaw={calib_params['yawDeg']}°, Height={calib_params['radarHeightM']}m")
        except Exception as e:
            print(f"[-] Using default calibration: {e}")

    # 3. Locate Modality Stream Files
    radar_bin = os.path.join(session_dir, "radar", "radar_frames.bin")
    if not os.path.isfile(radar_bin):
        radar_bin = os.path.join(session_dir, "radar_frames.bin")

    gnss_csv = os.path.join(session_dir, "gnss", "gnss_fixes.csv")
    if not os.path.isfile(gnss_csv):
        gnss_csv = os.path.join(session_dir, "gnss_fixes.csv")

    video_mp4 = os.path.join(session_dir, "camera", "camera_video.mp4")
    if not os.path.isfile(video_mp4):
        video_mp4 = os.path.join(session_dir, "camera_video.mp4")

    frames_csv = os.path.join(session_dir, "camera", "camera_frames.csv")
    if not os.path.isfile(frames_csv):
        frames_csv = os.path.join(session_dir, "camera_frames.csv")

    session_log = os.path.join(session_dir, "session_debug.log")

    # 4. Open MCAP Writer with Zstandard Compression
    temp_output = output_path + ".tmp"
    message_counts = {}

    def count_msg(topic):
        message_counts[topic] = message_counts.get(topic, 0) + 1

    t_start = time.time()
    with open(temp_output, "wb") as f_out:
        writer = ProtobufWriter(f_out, chunk_size=1024 * 1024, compression=CompressionType.ZSTD)

        # Register JSON Telemetry Schemas
        telemetry_schema_id = writer._writer.register_schema(
            name="roadsense.VehicleTelemetry",
            encoding="jsonschema",
            data=json.dumps({
                "type": "object",
                "properties": {
                    "speed_kmph": {"type": "number"},
                    "yaw_rate_radps": {"type": "number"},
                    "pitch_rate_radps": {"type": "number"},
                    "roll_rate_radps": {"type": "number"},
                    "accel_x_mps2": {"type": "number"},
                    "accel_y_mps2": {"type": "number"},
                    "accel_avg_mps2": {"type": "number"}
                }
            }).encode("utf-8")
        )
        telemetry_chan_id = writer._writer.register_channel(
            topic="/vehicle/telemetry",
            message_encoding="json",
            schema_id=telemetry_schema_id
        )

        diags_schema_id = writer._writer.register_schema(
            name="roadsense.RadarDiagnostics",
            encoding="jsonschema",
            data=json.dumps({
                "type": "object",
                "properties": {
                    "num_inliers": {"type": "integer"},
                    "ransac_ok": {"type": "boolean"},
                    "motion_state": {"type": "integer"},
                    "filtered_vx_iir": {"type": "number"},
                    "filtered_vy_iir": {"type": "number"},
                    "ego_vy": {"type": "number"},
                    "ego_vx": {"type": "number"},
                    "ego_ax": {"type": "number"},
                    "ego_ay": {"type": "number"},
                    "ego_yaw_rate": {"type": "number"},
                    "road_boundary_left_x": {"type": "number"},
                    "road_boundary_right_x": {"type": "number"},
                    "tracker_proc_time_us": {"type": "integer"}
                }
            }).encode("utf-8")
        )
        diags_chan_id = writer._writer.register_channel(
            topic="/radar/diagnostics",
            message_encoding="json",
            schema_id=diags_schema_id
        )

        # 5. Emit Static Transforms & Camera Calibration
        base_sec = start_wall_ms // 1000
        base_nano = (start_wall_ms % 1000) * 1_000_000

        # FrameTransforms: base_link -> radar_link and base_link -> camera_optical
        tf_msg = FrameTransforms()
        
        # Transform 1: base_link -> radar_link
        tf_radar = tf_msg.transforms.add()
        tf_radar.timestamp.seconds = base_sec
        tf_radar.timestamp.nanos = base_nano
        tf_radar.parent_frame_id = "base_link"
        tf_radar.child_frame_id = "radar_link"
        tf_radar.translation.x = 0.0
        tf_radar.translation.y = 0.0
        tf_radar.translation.z = calib_params["radarHeightM"]
        tf_radar.rotation.w = 1.0 # Aligned with vehicle coordinate frame

        # Transform 2: base_link -> camera_optical
        # In ROS/Foxglove: camera_optical frame has +Z pointing into the scene, +X pointing right, +Y pointing down.
        # Windshield camera is elevated by heightOffsetM, set back by setbackM, and rotated by pitch and yaw.
        tf_cam = tf_msg.transforms.add()
        tf_cam.timestamp.seconds = base_sec
        tf_cam.timestamp.nanos = base_nano
        tf_cam.parent_frame_id = "base_link"
        tf_cam.child_frame_id = "camera_optical"

        cam_quat, cam_trans = compute_camera_optical_tf(
            pitch_deg=calib_params.get("pitchDeg", 7.0),
            yaw_deg=calib_params.get("yawDeg", -1.0),
            roll_deg=calib_params.get("rollDeg", 0.0),
            setback=calib_params.get("setbackM", 0.30),
            lateral=calib_params.get("lateralOffsetM", 0.00),
            height=calib_params.get("radarHeightM", 0.95) + calib_params.get("heightOffsetM", 0.50)
        )
        tf_cam.translation.x = cam_trans[0]
        tf_cam.translation.y = cam_trans[1]
        tf_cam.translation.z = cam_trans[2]
        tf_cam.rotation.x = cam_quat[0]
        tf_cam.rotation.y = cam_quat[1]
        tf_cam.rotation.z = cam_quat[2]
        tf_cam.rotation.w = cam_quat[3]

        writer.write_message("/tf", tf_msg, log_time=start_wall_ms * 1_000_000)
        count_msg("/tf")

        # Camera Calibration (1280x720 720p HD)
        cal_msg = CameraCalibration()
        cal_msg.timestamp.seconds = base_sec
        cal_msg.timestamp.nanos = base_nano
        cal_msg.frame_id = "camera_optical"
        cal_msg.width = 1280
        cal_msg.height = 720
        cal_msg.distortion_model = "plumb_bob"
        cal_msg.D.extend([0.0, 0.0, 0.0, 0.0, 0.0])
        # Camera intrinsics calculated for ~68 deg HFOV at 1280x720
        fx = 948.8
        fy = 948.8
        cx = 640.0
        cy = 360.0
        cal_msg.K.extend([fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0])
        cal_msg.R.extend([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0])
        cal_msg.P.extend([fx, 0.0, cx, 0.0, 0.0, fy, cy, 0.0, 0.0, 0.0, 1.0, 0.0])

        writer.write_message("/camera/calib", cal_msg, log_time=start_wall_ms * 1_000_000)
        count_msg("/camera/calib")

        # 6. Stream & Transcode Multimodal Sensors
        print("[+] Processing and writing sensor streams...")

        # Setup PointCloud Fields Descriptor once
        point_fields = [
            PackedElementField(name="x", offset=0, type=PackedElementField.NumericType.FLOAT32),
            PackedElementField(name="y", offset=4, type=PackedElementField.NumericType.FLOAT32),
            PackedElementField(name="z", offset=8, type=PackedElementField.NumericType.FLOAT32),
            PackedElementField(name="velocity", offset=12, type=PackedElementField.NumericType.FLOAT32),
            PackedElementField(name="snr", offset=16, type=PackedElementField.NumericType.FLOAT32)
        ]

        # Ingest Radar Frames
        if os.path.isfile(radar_bin):
            print(f"    - Ingesting Radar Point Clouds & EKF Tracks: {radar_bin}")
            for r_frame in parse_radar_stream(radar_bin):
                utc_ns = r_frame["mono_ns"] + time_offset_ns
                sec = utc_ns // 1_000_000_000
                nano = utc_ns % 1_000_000_000

                # 6.1 Point Cloud
                pts = r_frame["points"]
                if pts:
                    pc_msg = PointCloud()
                    pc_msg.timestamp.seconds = sec
                    pc_msg.timestamp.nanos = nano
                    pc_msg.frame_id = "radar_link"
                    pc_msg.point_stride = 20
                    pc_msg.fields.extend(point_fields)
                    # Pack binary float32 array
                    buf = bytearray(len(pts) * 20)
                    struct.pack_into(f"<{len(pts)*5}f", buf, 0, *[val for pt in pts for val in pt])
                    pc_msg.data = bytes(buf)

                    writer.write_message("/radar/points", pc_msg, log_time=utc_ns)
                    count_msg("/radar/points")

                # 6.2 3D Scene Update (EKF Tracks)
                tracks = r_frame["tracks"]
                if tracks:
                    su_msg = SceneUpdate()
                    entity = su_msg.entities.add()
                    entity.id = f"radar_tracks_{r_frame['hw_frame_num']}"
                    entity.frame_id = "base_link"
                    entity.timestamp.seconds = sec
                    entity.timestamp.nanos = nano
                    entity.lifetime.nanos = 100_000_000 # 100 ms persistence

                    for t in tracks:
                        # 3D Bounding Box
                        cube = entity.cubes.add()
                        cube.pose.position.x = t["x_fwd"]
                        cube.pose.position.y = t["y_left"]
                        cube.pose.position.z = calib_params["radarHeightM"]
                        
                        heading_rad = math.radians(t["heading_deg"])
                        qx, qy, qz, qw = euler_to_quaternion(0.0, 0.0, heading_rad)
                        cube.pose.orientation.x = qx
                        cube.pose.orientation.y = qy
                        cube.pose.orientation.z = qz
                        cube.pose.orientation.w = qw

                        cube.size.x = t["length"]
                        cube.size.y = t["width"]
                        cube.size.z = t["height"]

                        # Color by risk / alert level
                        if t["risk"] >= 3:
                            cube.color.r = 1.0; cube.color.g = 0.1; cube.color.b = 0.1; cube.color.a = 0.75 # Red (High Risk)
                        elif t["risk"] == 2:
                            cube.color.r = 1.0; cube.color.g = 0.6; cube.color.b = 0.0; cube.color.a = 0.75 # Orange (Warning)
                        elif t["risk"] == 1:
                            cube.color.r = 1.0; cube.color.g = 0.9; cube.color.b = 0.0; cube.color.a = 0.75 # Yellow (Caution)
                        else:
                            cube.color.r = 0.2; cube.color.g = 0.8; cube.color.b = 0.3; cube.color.a = 0.65 # Green (Normal)

                        # Velocity Vector Arrow
                        speed_mag = math.hypot(t["vx_fwd"], t["vy_left"])
                        if speed_mag > 0.5:
                            arrow = entity.arrows.add()
                            arrow.pose.position.x = t["x_fwd"]
                            arrow.pose.position.y = t["y_left"]
                            arrow.pose.position.z = calib_params["radarHeightM"] + 0.1

                            vel_yaw = math.atan2(t["vy_left"], t["vx_fwd"])
                            aqx, aqy, aqz, aqw = euler_to_quaternion(0.0, 0.0, vel_yaw)
                            arrow.pose.orientation.x = aqx
                            arrow.pose.orientation.y = aqy
                            arrow.pose.orientation.z = aqz
                            arrow.pose.orientation.w = aqw

                            arrow.shaft_length = min(speed_mag * 0.5, 5.0)
                            arrow.shaft_diameter = 0.12
                            arrow.head_length = 0.35
                            arrow.head_diameter = 0.25
                            arrow.color.r = 0.1; arrow.color.g = 0.9; arrow.color.b = 1.0; arrow.color.a = 0.9

                        # Floating Label
                        txt = entity.texts.add()
                        txt.pose.position.x = t["x_fwd"]
                        txt.pose.position.y = t["y_left"]
                        txt.pose.position.z = calib_params["radarHeightM"] + 1.2
                        txt.text = f"ID #{t['tid']} | {t['vx_fwd']:.1f} m/s | TTI {t['tti']:.1f}s"
                        txt.font_size = 0.45
                        txt.billboard = True
                        txt.color.r = 1.0; txt.color.g = 1.0; txt.color.b = 1.0; txt.color.a = 1.0

                    writer.write_message("/radar/tracks", su_msg, log_time=utc_ns)
                    count_msg("/radar/tracks")

                # 6.3 Vehicle Powertrain CAN Inputs (TLV 5)
                if r_frame["can_inputs"]:
                    writer._writer.add_message(
                        channel_id=telemetry_chan_id,
                        log_time=utc_ns,
                        data=json.dumps(r_frame["can_inputs"]).encode("utf-8"),
                        publish_time=utc_ns
                    )
                    count_msg("/vehicle/telemetry")

                # 6.4 Radar Tracker Diagnostics (TLV 4)
                if r_frame["diags"]:
                    writer._writer.add_message(
                        channel_id=diags_chan_id,
                        log_time=utc_ns,
                        data=json.dumps(r_frame["diags"]).encode("utf-8"),
                        publish_time=utc_ns
                    )
                    count_msg("/radar/diagnostics")

        # Ingest GNSS Satellite Fixes
        if os.path.isfile(gnss_csv):
            print(f"    - Ingesting GNSS Fixes: {gnss_csv}")
            for fix in parse_gnss_csv(gnss_csv):
                utc_ns = fix["mono_ns"] + time_offset_ns
                loc_msg = LocationFix()
                loc_msg.timestamp.seconds = utc_ns // 1_000_000_000
                loc_msg.timestamp.nanos = utc_ns % 1_000_000_000
                loc_msg.frame_id = "base_link"
                loc_msg.latitude = fix["lat"]
                loc_msg.longitude = fix["lon"]
                loc_msg.altitude = fix["alt"]
                loc_msg.heading = fix["bearing"]
                loc_msg.velocity.x = fix["speed"] * math.cos(math.radians(fix["bearing"]))
                loc_msg.velocity.y = fix["speed"] * math.sin(math.radians(fix["bearing"]))
                loc_msg.velocity.z = 0.0

                writer.write_message("/gnss/fix", loc_msg, log_time=utc_ns)
                count_msg("/gnss/fix")

        # Ingest Camera Video Bitstream (H.264 Annex B)
        if include_video and os.path.isfile(video_mp4) and os.path.isfile(frames_csv):
            if flip_video:
                print(f"    - Demuxing & Rotating (180° Inversion) H.264 Video Stream: {video_mp4}")
            else:
                print(f"    - Demuxing H.264 Video Stream: {video_mp4}")
            from foxglove_schemas_protobuf.CompressedVideo_pb2 import CompressedVideo
            for v_frame in parse_camera_video_frames(video_mp4, frames_csv, flip_video=flip_video):
                utc_ns = v_frame["mono_ns"] + time_offset_ns
                vid_msg = CompressedVideo()
                vid_msg.timestamp.seconds = utc_ns // 1_000_000_000
                vid_msg.timestamp.nanos = utc_ns % 1_000_000_000
                vid_msg.frame_id = "camera_optical"
                vid_msg.data = v_frame["data"]
                vid_msg.format = "h264"

                writer.write_message("/camera/video", vid_msg, log_time=utc_ns)
                count_msg("/camera/video")

        # Ingest Diagnostic Logs
        if os.path.isfile(session_log):
            print(f"    - Ingesting Diagnostic Flight Recorder Logs: {session_log}")
            for log_entry in parse_flight_recorder_logs(session_log, start_wall_ms, start_iso_str):
                log_msg = Log()
                log_msg.timestamp.seconds = log_entry["utc_ns"] // 1_000_000_000
                log_msg.timestamp.nanos = log_entry["utc_ns"] % 1_000_000_000
                log_msg.level = log_entry["level"]
                log_msg.name = log_entry["tag"]
                log_msg.message = log_entry["message"]

                writer.write_message("/diagnostics/logs", log_msg, log_time=log_entry["utc_ns"])
                count_msg("/diagnostics/logs")

        # 7. Add Native MCAP Attachments
        if os.path.isfile(meta_path):
            with open(meta_path, "rb") as mf:
                writer._writer.add_attachment(
                    create_time=start_wall_ms * 1_000_000,
                    log_time=start_wall_ms * 1_000_000,
                    name="session_metadata.json",
                    media_type="application/json",
                    data=mf.read()
                )
            print("    - Embedded attachment: session_metadata.json")

        if os.path.isfile(calib_file):
            with open(calib_file, "rb") as cf:
                writer._writer.add_attachment(
                    create_time=start_wall_ms * 1_000_000,
                    log_time=start_wall_ms * 1_000_000,
                    name="radar_camera_calib.json",
                    media_type="application/json",
                    data=cf.read()
                )
            print("    - Embedded attachment: radar_camera_calib.json")

        # 8. Flush Summary Indexes & Close
        print("[+] Finalizing chunk indexes, summary statistics, and footer...")
        writer.finish()

    # Atomically move temp output to final destination
    if os.path.exists(output_path):
        os.remove(output_path)
    os.rename(temp_output, output_path)

    elapsed = time.time() - t_start
    size_mb = os.path.getsize(output_path) / (1024 * 1024)

    print(f"\n[+] MCAP Conversion Complete! ({elapsed:.2f}s, {size_mb:.2f} MB)")
    print(f"    Summary of Written Channels:")
    for topic, count in sorted(message_counts.items()):
        print(f"      * {topic:<24}: {count:,} messages")
    print(f"    Saved to: {output_path}\n")
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Convert RoadSense recording sessions to Foxglove MCAP format.")
    parser.add_argument("session_dir", help="Path to recording session directory (e.g. logs/session_20260922_120145)")
    parser.add_argument("--output", "-o", help="Optional output .mcap file path")
    parser.add_argument("--no-video", action="store_true", help="Exclude video stream for ultra-compact MCAP")
    parser.add_argument("--flip-video", action="store_true", help="Physically rotate video 180° during conversion so raw stream is upright everywhere")

    args = parser.parse_args()
    if not os.path.isdir(args.session_dir):
        print(f"[-] Session directory not found: {args.session_dir}")
        sys.exit(1)

    convert_session_to_mcap(
        session_dir=args.session_dir,
        output_path=args.output,
        include_video=not args.no_video,
        flip_video=args.flip_video
    )


if __name__ == "__main__":
    main()
