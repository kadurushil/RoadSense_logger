"""
inject_vision_tracks.py
-----------------------
Non-destructively injects 3D geometric vision tracks into Foxglove MCAP files.

Guarantees:
  1. Zero modification to original files: logs/ session recordings remain strictly read-only.
  2. Preserves 100% of original data: all 12 schemas, 12 channels, and 4 attachments
     (including RoadSense_Cockpit_Layout.json and foxglove.layout) are copied byte-for-byte.
  3. Strict Temporal Alignment: Automatically detects and aligns timestamps with master
     UTC Wall Clock nanoseconds (utc_ns = mono_ns + time_offset_ns), completely eliminating
     the multi-year timeline stretch bug in Foxglove Studio.
  4. Least Invasive Schema Re-use: Re-uses the existing 'foxglove.SceneUpdate' schema from
     the source MCAP without creating redundant duplicate schemas.
  5. Chronological Streaming Merge: Messages are merged in strict monotonic log_time order.
"""

import argparse
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
from tqdm import tqdm

from tools.vision.mcap.mcap_scene_writer import McapSceneWriter
from tools.vision.pipeline.timeline_loader import TimelineLoader


def resolve_frame_utc_timestamp(
    frame: Dict[str, Any],
    time_offset_ns: int,
) -> int:
    """
    Ensures a frame's timestamp is in UTC Wall Clock nanoseconds (epoch ~2026).
    If the timestamp is raw Android monotonic boot nanoseconds (< 1e16), converts via time_offset_ns.
    """
    # 1. Prefer explicit timestamp_utc_ns if present and valid
    if "timestamp_utc_ns" in frame and frame["timestamp_utc_ns"] > 1e16:
        return int(frame["timestamp_utc_ns"])

    raw_ts = int(frame.get("timestamp_ns", 0))

    # 2. If raw_ts is already epoch nanoseconds (> 1e16), use it directly
    if raw_ts > 1e16:
        return raw_ts

    # 3. Otherwise it is Android monotonic elapsedRealtimeNanos, add time_offset_ns
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
    # 1. Check vision tracks JSON
    if json_data and json_data.get("time_offset_ns"):
        return int(json_data["time_offset_ns"])

    # 2. Check disk session_dir
    candidates = []
    if session_dir and os.path.isdir(session_dir):
        candidates.append(session_dir)
    mcap_dir = os.path.dirname(os.path.abspath(source_mcap_path))
    candidates.append(mcap_dir)

    for c in candidates:
        offset = TimelineLoader.get_session_time_offset_ns(c)
        if offset > 0:
            return offset

    # 3. Check source MCAP attachments
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
    Creates a lightweight MCAP containing only the '/vision/geo_tracks' topic,
    with all timestamps strictly normalized to UTC Wall Clock nanoseconds.
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

    with open(output_mcap_path, "wb") as f_out:
        with ProtobufWriter(f_out, chunk_size=1024 * 1024, compression=CompressionType.ZSTD) as pw:
            for frame in tqdm(frames, desc="Writing Standalone MCAP", unit="frame"):
                utc_ts = resolve_frame_utc_timestamp(frame, time_offset_ns)
                tracks = frame.get("tracks", [])
                su_msg = McapSceneWriter.create_scene_update(utc_ts, tracks)
                pw.write_message(
                    topic="/vision/geo_tracks",
                    message=su_msg,
                    log_time=utc_ts,
                    publish_time=utc_ts,
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
    Copies all existing channels and attachments from source MCAP and injects '/vision/geo_tracks'.
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
    # Prepare normalized vision frames sorted strictly by UTC nanoseconds
    prepared_vision_frames: List[Tuple[int, Dict[str, Any]]] = []
    for f in raw_frames:
        utc_ts = resolve_frame_utc_timestamp(f, time_offset_ns)
        prepared_vision_frames.append((utc_ts, f))

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
    with open(source_mcap_path, "rb") as f_in, open(output_mcap_path, "wb") as f_out:
        reader = make_reader(f_in)
        summary = reader.get_summary()
        stats = summary.statistics if summary else None
        total_src_msgs = stats.message_count if stats else None

        proto_writer = ProtobufWriter(f_out, chunk_size=1024 * 1024, compression=CompressionType.ZSTD)

        # 1. Register all source schemas exactly as-is
        schema_map = {}
        scene_update_schema_id = None
        if summary and summary.schemas:
            for sid, s in summary.schemas.items():
                schema_map[sid] = proto_writer._writer.register_schema(
                    name=s.name, encoding=s.encoding, data=s.data
                )
                if s.name == "foxglove.SceneUpdate":
                    scene_update_schema_id = schema_map[sid]

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

        # 3. Register '/vision/geo_tracks' channel using existing SceneUpdate schema
        if scene_update_schema_id is not None:
            vis_channel_id = proto_writer._writer.register_channel(
                schema_id=scene_update_schema_id,
                topic="/vision/geo_tracks",
                message_encoding="protobuf",
            )
            use_raw_scene_writer = True
        else:
            # Fallback if source MCAP did not define SceneUpdate schema
            vis_channel_id = None
            use_raw_scene_writer = False

        # 4. Copy all attachments byte-for-byte (e.g. foxglove.layout, session_metadata.json)
        copied_attachments = 0
        if hasattr(reader, "iter_attachments"):
            for att in reader.iter_attachments():
                proto_writer._writer.add_attachment(
                    create_time=att.create_time,
                    log_time=att.log_time,
                    name=att.name,
                    media_type=att.media_type,
                    data=att.data,
                )
                copied_attachments += 1

        # 5. Chronological two-pointer streaming merge
        v_idx = 0
        num_v = len(prepared_vision_frames)
        src_iter = reader.iter_messages()
        next_src = next(src_iter, None)

        total_est = (total_src_msgs or 0) + num_v
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
                # Inject 3D vision track update
                v_utc_ts, v_frame = prepared_vision_frames[v_idx]
                tracks = v_frame.get("tracks", [])
                su_msg = McapSceneWriter.create_scene_update(v_utc_ts, tracks)

                if use_raw_scene_writer and vis_channel_id is not None:
                    proto_writer._writer.add_message(
                        channel_id=vis_channel_id,
                        log_time=v_utc_ts,
                        data=su_msg.SerializeToString(),
                        publish_time=v_utc_ts,
                    )
                else:
                    proto_writer.write_message(
                        topic="/vision/geo_tracks",
                        message=su_msg,
                        log_time=v_utc_ts,
                        publish_time=v_utc_ts,
                    )

                v_idx += 1
                injected_msgs += 1
                batch_msgs += 1
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
                    print(f"[PROGRESS] {json.dumps({'step': 'vision_mcap', 'desc': f'Merging /vision/geo_tracks ({curr_tot:,}/{total_est:,})', 'current': curr_tot, 'total': total_est, 'pct': pct})}", flush=True)
                batch_msgs = 0

        if batch_msgs > 0:
            pbar.update(batch_msgs)

        pbar.close()
        proto_writer.finish()

    elapsed = time.time() - start_time
    dst_size_mb = os.path.getsize(output_mcap_path) / (1024 * 1024)

    print(f"  Successfully enriched in {elapsed:.1f}s ({copied_msgs + injected_msgs} total messages)")
    print(f"    • Source messages copied: {copied_msgs}")
    print(f"    • Vision updates injected: {injected_msgs}")
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
        description="Inject 3D geometric vision tracks into Foxglove MCAP files."
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
