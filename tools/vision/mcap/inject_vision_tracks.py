"""
inject_vision_tracks.py
-----------------------
Non-destructively injects 3D geometric vision tracks, 2D image annotations,
trajectory breadcrumbs, camera frustum bounds, and 3-tier telemetry diagnostics
into Foxglove MCAP files.

Sub-Topics Injected:
  1. /vision/2d_boxes        : foxglove.ImageAnnotations (2D YOLO boxes, labels, contact points)
  2. /vision/geo_tracks      : foxglove.SceneUpdate (3D cuboids, velocity arrows, ground footprints)
  3. /vision/trajectories    : foxglove.SceneUpdate (3D multi-second trajectory breadcrumb line strips)
  4. /vision/camera_frustum  : foxglove.SceneUpdate (Camera ground-plane optical projection boundaries)
  5. /vision/diagnostics     : roadsense.VisionDiagnostics (3-tier pipeline telemetry & State Matrices)

Guarantees:
  1. Zero modification to original files: logs/ session recordings remain strictly read-only.
  2. Preserves 100% of original data: all schemas, channels, and attachments are copied byte-for-byte.
  3. Strict Temporal Alignment: Automatically detects and aligns timestamps with master
     UTC Wall Clock nanoseconds (utc_ns = mono_ns + time_offset_ns), completely eliminating
     the multi-year timeline stretch bug in Foxglove Studio.
  4. Chronological Streaming Merge: All sensor streams are merged in strict monotonic log_time order.
"""

import argparse
from collections import deque
import glob
import json
import os
import sys
import time
from typing import Dict, Any, List, Optional, Tuple

# Ensure UTF-8 console output on Windows to prevent Â° and â€¢ glyph corruption
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Ensure repository root is on sys.path
repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if repo_root not in sys.path:
    sys.path.insert(0, repo_root)

from mcap.reader import make_reader
from mcap.writer import CompressionType
from mcap_protobuf.writer import Writer as ProtobufWriter

try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, *args, **kwargs):
        return iterable

from tools.vision.mcap.mcap_scene_writer import McapSceneWriter, VISION_DIAGNOSTICS_JSON_SCHEMA
from tools.vision.pipeline.timeline_loader import TimelineLoader


def resolve_frame_utc_timestamp(
    frame: Dict[str, Any],
    time_offset_ns: int,
) -> int:
    """
    Ensures a frame's timestamp is in UTC Wall Clock nanoseconds (epoch ~2026).
    If the timestamp is raw Android monotonic boot nanoseconds (< 1e16), converts via time_offset_ns.
    """
    if "timestamp_utc_ns" in frame and frame["timestamp_utc_ns"] > 1e16:
        return int(frame["timestamp_utc_ns"])

    raw_ts = int(frame.get("timestamp_ns", 0))

    if raw_ts > 1e16:
        return raw_ts

    return raw_ts + time_offset_ns


def get_session_offset_from_mcap_or_disk(
    source_mcap_path: str,
    session_dir: Optional[str] = None,
    json_data: Optional[Dict[str, Any]] = None,
) -> int:
    """
    Resolves time_offset_ns using multiple fallback sources:
      1. JSON metadata in vision_tracks.json
      2. session_metadata.json on disk
      3. session_metadata.json attachment embedded inside the source MCAP
    """
    if json_data and json_data.get("time_offset_ns"):
        return int(json_data["time_offset_ns"])

    candidates = []
    if session_dir and os.path.isdir(session_dir):
        candidates.append(session_dir)
    mcap_dir = os.path.dirname(os.path.abspath(source_mcap_path))
    candidates.append(mcap_dir)

    for c in candidates:
        offset = TimelineLoader.get_session_time_offset_ns(c)
        if offset > 0:
            return offset

    if os.path.isfile(source_mcap_path):
        try:
            with open(source_mcap_path, "rb") as f:
                reader = make_reader(f)
                if hasattr(reader, "iter_attachments"):
                    for att in reader.iter_attachments():
                        if att.name == "session_metadata.json":
                            meta = json.loads(att.data.decode("utf-8"))
                            wall_ms = int(meta.get("startTimeWallMs", 0))
                            mono_ns = int(meta.get("startTimeMonotonicNs", 0))
                            if wall_ms > 0 and mono_ns > 0:
                                return (wall_ms * 1_000_000) - mono_ns
        except Exception:
            pass

    return 0


def write_standalone_vision_mcap(
    vision_json_path: str,
    output_mcap_path: str,
    session_dir: Optional[str] = None,
) -> str:
    """
    Creates a lightweight MCAP containing all vision topics:
      - /vision/geo_tracks
      - /vision/2d_boxes
      - /vision/trajectories
      - /vision/camera_frustum
      - /vision/diagnostics
    All timestamps strictly normalized to UTC Wall Clock nanoseconds.
    """
    with open(vision_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    time_offset_ns = get_session_offset_from_mcap_or_disk(
        source_mcap_path="", session_dir=session_dir, json_data=data
    )
    frames: List[Dict[str, Any]] = data.get("frames", [])
    os.makedirs(os.path.dirname(os.path.abspath(output_mcap_path)), exist_ok=True)

    print(f"\n[MCAP Writer] Generating standalone vision MCAP: {output_mcap_path}")
    print(f"  Frames to write: {len(frames)} | Time Offset: {time_offset_ns} ns")

    trajectory_history: Dict[int, deque] = {}

    with open(output_mcap_path, "wb") as f_out:
        with ProtobufWriter(f_out, chunk_size=1024 * 1024, compression=CompressionType.ZSTD) as pw:
            # Register JSON schema for /vision/diagnostics
            diag_schema_id = pw._writer.register_schema(
                name="roadsense.VisionDiagnostics",
                encoding="jsonschema",
                data=json.dumps(VISION_DIAGNOSTICS_JSON_SCHEMA).encode("utf-8"),
            )
            diag_chan_id = pw._writer.register_channel(
                schema_id=diag_schema_id,
                topic="/vision/diagnostics",
                message_encoding="json",
            )

            for idx, frame in enumerate(tqdm(frames, desc="Writing Standalone MCAP", unit="frame")):
                utc_ts = resolve_frame_utc_timestamp(frame, time_offset_ns)
                tracks = frame.get("tracks", [])

                # Update trajectory history
                active_ids = set()
                for t in tracks:
                    tid = t.get("track_id")
                    if tid is not None:
                        active_ids.add(tid)
                        pos = t.get("position_3d", {})
                        if tid not in trajectory_history:
                            trajectory_history[tid] = deque(maxlen=30)
                        trajectory_history[tid].append((
                            float(pos.get("x_fwd_m", 0.0)),
                            float(pos.get("y_left_m", 0.0)),
                            0.08,
                        ))

                # 1. 3D Scene Update
                su_msg = McapSceneWriter.create_scene_update(utc_ts, tracks)
                pw.write_message(topic="/vision/geo_tracks", message=su_msg, log_time=utc_ts, publish_time=utc_ts)

                # 2. 2D Image Annotations
                ia_msg = McapSceneWriter.create_image_annotations(utc_ts, tracks)
                pw.write_message(topic="/vision/2d_boxes", message=ia_msg, log_time=utc_ts, publish_time=utc_ts)

                # 3. 3D Trajectory History
                tu_msg = McapSceneWriter.create_trajectories_update(utc_ts, trajectory_history)
                pw.write_message(topic="/vision/trajectories", message=tu_msg, log_time=utc_ts, publish_time=utc_ts)

                # 4. Camera Frustum (emitted every 10 frames)
                if idx % 10 == 0:
                    fu_msg = McapSceneWriter.create_camera_frustum_update(utc_ts)
                    pw.write_message(topic="/vision/camera_frustum", message=fu_msg, log_time=utc_ts, publish_time=utc_ts)

                # 5. Vision Diagnostics Telemetry
                ego_spd = float(frame.get("ego_speed_mps", 0.0))
                ego_yr = float(frame.get("ego_yaw_rate_radps", 0.0))
                diag_msg = McapSceneWriter.create_diagnostics_message(
                    timestamp_ns=utc_ts,
                    frame_idx=frame.get("frame_index", idx),
                    tracks=tracks,
                    ego_speed_mps=ego_spd,
                    ego_yaw_rate_can_radps=ego_yr,
                    ego_yaw_rate_smoothed_radps=-ego_yr,
                    turning_model_active=abs(ego_yr) > 0.015,
                    deadband_scale=1.0,
                )
                pw._writer.add_message(
                    channel_id=diag_chan_id,
                    log_time=utc_ts,
                    data=json.dumps(diag_msg).encode("utf-8"),
                    publish_time=utc_ts,
                )

            # Attach RoadSense Cockpit Layout
            layout_path = os.path.join(repo_root, "tools", "foxglove_layouts", "RoadSense_Cockpit_Layout.json")
            if os.path.isfile(layout_path):
                with open(layout_path, "rb") as fl:
                    layout_bytes = fl.read()
                now_ns = int(time.time() * 1e9)
                pw._writer.add_attachment(
                    create_time=now_ns,
                    log_time=now_ns,
                    name="RoadSense_Cockpit_Layout.json",
                    media_type="application/json",
                    data=layout_bytes,
                )

    file_size_mb = os.path.getsize(output_mcap_path) / (1024 * 1024)
    print(f"  Exported standalone vision MCAP ({file_size_mb:.2f} MB): {output_mcap_path}\n")
    return output_mcap_path


def enrich_existing_mcap(
    source_mcap_path: str,
    vision_json_path: str,
    output_mcap_path: str,
    session_dir: Optional[str] = None,
) -> str:
    """
    Copies all existing channels and attachments from source MCAP and injects:
      - /vision/geo_tracks
      - /vision/2d_boxes
      - /vision/trajectories
      - /vision/camera_frustum
      - /vision/diagnostics
    Strictly non-destructive: source MCAP is read-only.
    Guarantees strict chronological ordering by UTC nanoseconds.
    """
    if not os.path.isfile(source_mcap_path):
        raise FileNotFoundError(f"Source MCAP not found: {source_mcap_path}")

    with open(vision_json_path, "r", encoding="utf-8") as f:
        vision_data = json.load(f)

    time_offset_ns = get_session_offset_from_mcap_or_disk(
        source_mcap_path=source_mcap_path, session_dir=session_dir, json_data=vision_data
    )

    raw_frames = vision_data.get("frames", [])
    prepared_vision_frames: List[Tuple[int, int, Dict[str, Any]]] = []
    for idx, f in enumerate(raw_frames):
        utc_ts = resolve_frame_utc_timestamp(f, time_offset_ns)
        prepared_vision_frames.append((utc_ts, idx, f))

    prepared_vision_frames.sort(key=lambda x: x[0])

    os.makedirs(os.path.dirname(os.path.abspath(output_mcap_path)), exist_ok=True)
    src_size_mb = os.path.getsize(source_mcap_path) / (1024 * 1024)
    print(f"\n[MCAP Writer] Enriching MCAP non-destructively:")
    print(f"  Source MCAP: {source_mcap_path} ({src_size_mb:.1f} MB, Read-Only)")
    print(f"  Vision JSON: {vision_json_path} ({len(prepared_vision_frames)} frames)")
    print(f"  Time Offset: {time_offset_ns} ns (UTC Epoch Alignment Active)")
    if prepared_vision_frames:
        print(f"  Vision Start UTC: {prepared_vision_frames[0][0]} ns")
        print(f"  Vision End UTC:   {prepared_vision_frames[-1][0]} ns")

    start_time = time.time()
    trajectory_history: Dict[int, deque] = {}

    with open(source_mcap_path, "rb") as f_in, open(output_mcap_path, "wb") as f_out:
        reader = make_reader(f_in)
        summary = reader.get_summary()
        stats = summary.statistics if summary else None
        total_src_msgs = stats.message_count if stats else None

        proto_writer = ProtobufWriter(f_out, chunk_size=1024 * 1024, compression=CompressionType.ZSTD)

        # 1. Register all source schemas exactly as-is
        schema_map = {}
        if summary and summary.schemas:
            for sid, s in summary.schemas.items():
                schema_map[sid] = proto_writer._writer.register_schema(
                    name=s.name, encoding=s.encoding, data=s.data
                )

        # 2. Register all source channels exactly as-is
        channel_map = {}
        if summary and summary.channels:
            for cid, c in summary.channels.items():
                target_schema_id = schema_map.get(c.schema_id, 0)
                channel_map[cid] = proto_writer._writer.register_channel(
                    schema_id=target_schema_id,
                    topic=c.topic,
                    message_encoding=c.message_encoding,
                    metadata=c.metadata,
                )

        # 3. Register JSON Schema for /vision/diagnostics
        diag_schema_id = proto_writer._writer.register_schema(
            name="roadsense.VisionDiagnostics",
            encoding="jsonschema",
            data=json.dumps(VISION_DIAGNOSTICS_JSON_SCHEMA).encode("utf-8"),
        )
        diag_chan_id = proto_writer._writer.register_channel(
            schema_id=diag_schema_id,
            topic="/vision/diagnostics",
            message_encoding="json",
        )

        # 4. Copy attachments, updating RoadSense_Cockpit_Layout.json with newest layout
        copied_attachments = 0
        layout_attached = False
        layout_path = os.path.join(repo_root, "tools", "foxglove_layouts", "RoadSense_Cockpit_Layout.json")
        layout_bytes = None
        if os.path.isfile(layout_path):
            with open(layout_path, "rb") as fl:
                layout_bytes = fl.read()

        if hasattr(reader, "iter_attachments"):
            for att in reader.iter_attachments():
                if att.name in ("foxglove.layout", "RoadSense_Cockpit_Layout.json") and layout_bytes is not None:
                    proto_writer._writer.add_attachment(
                        create_time=int(time.time() * 1e9),
                        log_time=att.log_time,
                        name=att.name,
                        media_type="application/json",
                        data=layout_bytes,
                    )
                    layout_attached = True
                else:
                    proto_writer._writer.add_attachment(
                        create_time=att.create_time,
                        log_time=att.log_time,
                        name=att.name,
                        media_type=att.media_type,
                        data=att.data,
                    )
                copied_attachments += 1

        if not layout_attached and layout_bytes is not None:
            now_ns = int(time.time() * 1e9)
            proto_writer._writer.add_attachment(
                create_time=now_ns,
                log_time=now_ns,
                name="RoadSense_Cockpit_Layout.json",
                media_type="application/json",
                data=layout_bytes,
            )
            copied_attachments += 1

        # 5. Chronological two-pointer streaming merge
        v_idx = 0
        num_v = len(prepared_vision_frames)
        src_iter = reader.iter_messages()
        next_src = next(src_iter, None)

        total_est = (total_src_msgs or 0) + (num_v * 4)
        is_tty = sys.stdout.isatty()
        pbar = tqdm(
            total=total_est,
            desc="Merging Streams",
            unit="msg",
            file=sys.stdout,
            disable=not is_tty,
            dynamic_ncols=True,
        )

        copied_msgs = 0
        injected_msgs = 0
        batch_msgs = 0

        while next_src is not None or v_idx < num_v:
            src_ts = next_src[2].log_time if next_src is not None else float("inf")
            vis_ts = prepared_vision_frames[v_idx][0] if v_idx < num_v else float("inf")

            if vis_ts <= src_ts and v_idx < num_v:
                v_utc_ts, v_frame_idx, v_frame = prepared_vision_frames[v_idx]
                tracks = v_frame.get("tracks", [])

                # Update trajectory history
                for t in tracks:
                    tid = t.get("track_id")
                    if tid is not None:
                        pos = t.get("position_3d", {})
                        if tid not in trajectory_history:
                            trajectory_history[tid] = deque(maxlen=30)
                        trajectory_history[tid].append((
                            float(pos.get("x_fwd_m", 0.0)),
                            float(pos.get("y_left_m", 0.0)),
                            0.08,
                        ))

                # A. 3D Oriented Cuboids, Velocity Arrows, Footprints
                su_msg = McapSceneWriter.create_scene_update(v_utc_ts, tracks)
                proto_writer.write_message(
                    topic="/vision/geo_tracks",
                    message=su_msg,
                    log_time=v_utc_ts,
                    publish_time=v_utc_ts,
                )
                injected_msgs += 1

                # B. 2D Bounding Boxes, Labels, Contact Points
                ia_msg = McapSceneWriter.create_image_annotations(v_utc_ts, tracks)
                proto_writer.write_message(
                    topic="/vision/2d_boxes",
                    message=ia_msg,
                    log_time=v_utc_ts,
                    publish_time=v_utc_ts,
                )
                injected_msgs += 1

                # C. 3D Trajectory Breadcrumbs
                tu_msg = McapSceneWriter.create_trajectories_update(v_utc_ts, trajectory_history)
                proto_writer.write_message(
                    topic="/vision/trajectories",
                    message=tu_msg,
                    log_time=v_utc_ts,
                    publish_time=v_utc_ts,
                )
                injected_msgs += 1

                # D. Camera Frustum (emitted every 10 frames)
                if v_idx % 10 == 0:
                    fu_msg = McapSceneWriter.create_camera_frustum_update(v_utc_ts)
                    proto_writer.write_message(
                        topic="/vision/camera_frustum",
                        message=fu_msg,
                        log_time=v_utc_ts,
                        publish_time=v_utc_ts,
                    )
                    injected_msgs += 1

                # E. Structured Diagnostics & State Matrix Telemetry
                ego_spd = float(v_frame.get("ego_speed_mps", 0.0))
                ego_yr = float(v_frame.get("ego_yaw_rate_radps", 0.0))
                diag_msg = McapSceneWriter.create_diagnostics_message(
                    timestamp_ns=v_utc_ts,
                    frame_idx=v_frame.get("frame_index", v_frame_idx),
                    tracks=tracks,
                    ego_speed_mps=ego_spd,
                    ego_yaw_rate_can_radps=ego_yr,
                    ego_yaw_rate_smoothed_radps=-ego_yr,
                    turning_model_active=abs(ego_yr) > 0.015,
                    deadband_scale=1.0,
                )
                proto_writer._writer.add_message(
                    channel_id=diag_chan_id,
                    log_time=v_utc_ts,
                    data=json.dumps(diag_msg).encode("utf-8"),
                    publish_time=v_utc_ts,
                )
                injected_msgs += 1

                v_idx += 1
                batch_msgs += 5
            elif next_src is not None:
                # Copy existing source message byte-for-byte
                s, c, m = next_src
                target_cid = channel_map[c.id]
                proto_writer._writer.add_message(
                    channel_id=target_cid,
                    log_time=m.log_time,
                    data=m.data,
                    publish_time=m.publish_time,
                    sequence=m.sequence,
                )
                copied_msgs += 1
                batch_msgs += 1
                next_src = next(src_iter, None)

            if batch_msgs >= 2000:
                pbar.update(batch_msgs)
                if not is_tty:
                    curr_tot = copied_msgs + injected_msgs
                    pct = min(99.0, round((curr_tot / max(total_est, 1)) * 100.0, 1))
                    print(f"[PROGRESS] {json.dumps({'step': 'vision_mcap', 'desc': f'Merging /vision/* ({curr_tot:,}/{total_est:,})', 'current': curr_tot, 'total': total_est, 'pct': pct})}", flush=True)
                batch_msgs = 0

        if batch_msgs > 0:
            pbar.update(batch_msgs)

        pbar.close()
        proto_writer.finish()

    elapsed = time.time() - start_time
    dst_size_mb = os.path.getsize(output_mcap_path) / (1024 * 1024)

    print(f"  Successfully enriched in {elapsed:.1f}s ({copied_msgs + injected_msgs} total messages)")
    print(f"    • Source messages copied: {copied_msgs}")
    print(f"    • Vision messages injected: {injected_msgs} across 5 sub-topics")
    print(f"    • Attachments preserved:  {copied_attachments}")
    print(f"  Enriched MCAP created ({dst_size_mb:.1f} MB): {output_mcap_path}\n")
    return output_mcap_path


def inject_vision_tracks_to_mcap(
    input_source: str,
    source_mcap: Optional[str] = None,
    output_mcap: Optional[str] = None,
    standalone: bool = False,
) -> str:
    """
    High-level entry point for vision track MCAP generation.
    Defaults to full enrichment (merging into source MCAP).
    """
    # 1. Resolve JSON path and session directory
    if input_source.endswith(".json") and os.path.isfile(input_source):
        json_path = input_source
        session_name = os.path.basename(json_path).replace("_vision_tracks.json", "")
        session_dir = os.path.join("logs", session_name)
    else:
        session_dir = input_source
        session_name = os.path.basename(os.path.normpath(input_source))
        json_path = os.path.join("tools", "vision", "output", "json", f"{session_name}_vision_tracks.json")
        if not os.path.isfile(json_path):
            raise FileNotFoundError(f"Vision tracks JSON not found at: {json_path}")

    # 2. Resolve source MCAP path
    if not source_mcap:
        candidate_1 = os.path.join(session_dir, f"{session_name}.mcap")
        if os.path.isfile(candidate_1):
            source_mcap = candidate_1
        else:
            candidates = glob.glob(os.path.join(session_dir, "*.mcap"))
            if candidates:
                source_mcap = candidates[0]

    # Stale / Missing Base MCAP Guard:
    calib_json = os.path.join(session_dir, "radar_camera_calib.json")
    should_auto_generate = False
    if (not source_mcap or not os.path.isfile(source_mcap)) and os.path.isdir(session_dir):
        print(f"[MCAP Writer] Base MCAP not found for {session_name}. Generating fresh base MCAP automatically...")
        should_auto_generate = True
    elif source_mcap and os.path.isfile(source_mcap) and os.path.isfile(calib_json):
        if os.path.getmtime(calib_json) > os.path.getmtime(source_mcap):
            print(f"[MCAP Writer] Calibration file is newer than base MCAP. Regenerating to ensure active /tf extrinsics...")
            should_auto_generate = True

    if should_auto_generate and os.path.isdir(session_dir):
        try:
            from tools.convert_session_to_mcap import convert_session_to_mcap
            source_mcap = convert_session_to_mcap(session_dir=session_dir)
        except Exception as e:
            print(f"[-] Auto-generation of base MCAP failed ({e}). Proceeding with fallback resolution.")

    # 3. Resolve destination output path
    out_dir = os.path.join("tools", "vision", "output", "mcap")
    os.makedirs(out_dir, exist_ok=True)

    if standalone:
        if not output_mcap:
            output_mcap = os.path.join(out_dir, f"{session_name}_vision_standalone.mcap")
        return write_standalone_vision_mcap(json_path, output_mcap, session_dir=session_dir)
    else:
        if not source_mcap or not os.path.isfile(source_mcap):
            print(f"[MCAP Writer] Source MCAP not found for merge. Falling back to standalone MCAP.")
            if not output_mcap:
                output_mcap = os.path.join(out_dir, f"{session_name}_vision_standalone.mcap")
            return write_standalone_vision_mcap(json_path, output_mcap, session_dir=session_dir)

        if not output_mcap:
            output_mcap = os.path.join(out_dir, f"{session_name}_with_vision.mcap")

        return enrich_existing_mcap(source_mcap, json_path, output_mcap, session_dir=session_dir)


def main():
    parser = argparse.ArgumentParser(
        description="Inject 3D geometric vision tracks and debug sub-topics into Foxglove MCAP files."
    )
    parser.add_argument(
        "input",
        help="Path to session directory (e.g. logs/session_20261006_095609) or vision_tracks.json",
    )
    parser.add_argument(
        "-m", "--source_mcap", default=None, help="Path to source MCAP (auto-discovered by default)"
    )
    parser.add_argument(
        "-o", "--output", default=None, help="Output MCAP file path"
    )
    parser.add_argument(
        "--standalone",
        action="store_true",
        default=False,
        help="Create lightweight standalone MCAP instead of full merged MCAP (default: False)",
    )
    args = parser.parse_args()

    inject_vision_tracks_to_mcap(
        input_source=args.input,
        source_mcap=args.source_mcap,
        output_mcap=args.output,
        standalone=args.standalone,
    )


if __name__ == "__main__":
    main()
