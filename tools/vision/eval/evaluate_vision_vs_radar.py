"""
evaluate_vision_vs_radar.py
---------------------------
CLI tool for programmatic evaluation of 3D geometric vision tracks against
mmWave radar ground truth from RoadSense MCAP recordings.
Outputs structured summary reports and diagnostic accuracy scatter plots.
"""

import argparse
import bisect
import json
import os
import sys
import time
from typing import Dict, Any, List, Optional

# Ensure repository root is on sys.path
repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if repo_root not in sys.path:
    sys.path.insert(0, repo_root)

from mcap.reader import make_reader
from foxglove_schemas_protobuf.SceneUpdate_pb2 import SceneUpdate

from tools.vision.eval.spatial_associator import SpatialAssociator
from tools.vision.eval.error_metrics import ErrorMetricsCalculator


def load_radar_tracks_from_mcap(mcap_path: str) -> List[Dict[str, Any]]:
    """
    Reads all radar frames and tracks from '/radar/tracks' in the session MCAP.
    """
    frames = []
    with open(mcap_path, "rb") as f:
        reader = make_reader(f)
        for schema, channel, message in reader.iter_messages(topics=["/radar/tracks"]):
            su = SceneUpdate()
            su.ParseFromString(message.data)
            frame_tracks = []
            for entity in su.entities:
                for idx, cube in enumerate(entity.cubes):
                    frame_tracks.append({
                        "track_id": idx + 1,
                        "x_fwd_m": cube.pose.position.x,
                        "y_left_m": cube.pose.position.y,
                        "z_up_m": cube.pose.position.z,
                        "length_m": cube.size.x,
                        "width_m": cube.size.y,
                        "height_m": cube.size.z,
                    })
            frames.append({
                "timestamp_ns": message.log_time,
                "tracks": frame_tracks,
            })
    return frames


def evaluate_session(
    session_dir: str,
    vision_json_path: Optional[str] = None,
    output_dir: Optional[str] = None,
    max_gate_m: float = 3.5,
) -> Dict[str, Any]:
    """
    Executes automated spatio-temporal benchmarking between vision tracks and radar tracks.
    """
    session_name = os.path.basename(os.path.normpath(session_dir))
    mcap_path = os.path.join(session_dir, f"{session_name}.mcap")
    if not os.path.isfile(mcap_path):
        # Look for any .mcap in session_dir
        import glob
        mcaps = glob.glob(os.path.join(session_dir, "*.mcap"))
        if mcaps:
            mcap_path = mcaps[0]
        else:
            raise FileNotFoundError(f"Session MCAP not found in: {session_dir}")

    if not vision_json_path:
        vision_json_path = os.path.join(
            "tools", "vision", "output", "json", f"{session_name}_vision_tracks.json"
        )
    if not os.path.isfile(vision_json_path):
        raise FileNotFoundError(f"Vision tracks JSON not found at: {vision_json_path}")

    print(f"\n[RoadSense Evaluation] Benchmarking Vision vs. Radar Ground Truth")
    print(f"  Session: {session_name}")
    print(f"  MCAP Source: {mcap_path}")
    print(f"  Vision JSON: {vision_json_path}")

    # 1. Load data
    print("  Loading radar tracks from MCAP...")
    radar_frames = load_radar_tracks_from_mcap(mcap_path)
    radar_ts = [rf["timestamp_ns"] for rf in radar_frames]

    with open(vision_json_path, "r", encoding="utf-8") as f:
        vision_data = json.load(f)
    vision_frames = vision_data.get("frames", [])

    # Resolve time offset for UTC normalization
    time_offset_ns = int(vision_data.get("time_offset_ns", 0))
    if time_offset_ns == 0:
        from tools.vision.pipeline.timeline_loader import TimelineLoader
        time_offset_ns = TimelineLoader.get_session_time_offset_ns(session_dir)

    print(f"  Loaded {len(radar_frames)} radar frames and {len(vision_frames)} vision frames (Time Offset: {time_offset_ns} ns).")

    # 2. Spatio-temporal association
    associator = SpatialAssociator(max_distance_gate_m=max_gate_m)
    all_matched = []
    total_v_tracks = sum(len(vf.get("tracks", [])) for vf in vision_frames)
    total_r_tracks = sum(len(rf.get("tracks", [])) for rf in radar_frames)

    for vf in vision_frames:
        if "timestamp_utc_ns" in vf and vf["timestamp_utc_ns"] > 1e16:
            v_ts = int(vf["timestamp_utc_ns"])
        else:
            raw_ts = int(vf.get("timestamp_ns", 0))
            v_ts = raw_ts if raw_ts > 1e16 else (raw_ts + time_offset_ns)

        v_tracks = vf.get("tracks", [])
        if not v_tracks or not radar_ts:
            continue

        # Find closest radar frame in time via bisect
        idx = bisect.bisect_left(radar_ts, v_ts)
        candidates = []
        if idx < len(radar_ts):
            candidates.append(idx)
        if idx > 0:
            candidates.append(idx - 1)

        best_rf = None
        best_dt_ms = 9999.0
        for c in candidates:
            dt_ms = abs(v_ts - radar_ts[c]) / 1e6
            if dt_ms < best_dt_ms:
                best_dt_ms = dt_ms
                best_rf = radar_frames[c]

        if best_rf and best_dt_ms <= 50.0:
            res = associator.associate_frame(v_tracks, best_rf["tracks"], time_delta_ms=best_dt_ms)
            all_matched.extend(res["matched_pairs"])

    # 3. Compute error metrics
    stats = ErrorMetricsCalculator.compute_summary_statistics(
        all_matched, total_v_tracks, total_r_tracks
    )

    # 4. Save report
    out_dir = output_dir or os.path.join("tools", "vision", "output", "reports")
    os.makedirs(out_dir, exist_ok=True)
    report_path = os.path.join(out_dir, f"{session_name}_benchmark_report.json")

    report_payload = {
        "session_name": session_name,
        "evaluation_timestamp": time.time(),
        "summary": stats,
        "matched_pairs": all_matched,
    }

    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report_payload, f, indent=2)

    # Terminal summary
    print("\n" + "=" * 60)
    print(f"  BENCHMARK RESULTS: {session_name}")
    print("=" * 60)
    print(f"  Total Vision Tracks:   {total_v_tracks}")
    print(f"  Total Radar Tracks:    {total_r_tracks}")
    print(f"  Matched Track Pairs:   {stats['matched_count']} ({stats['match_rate_pct']}% match rate)")
    ov = stats.get("overall", {})
    if ov:
        print(f"  Longitudinal Fwd MAE:  {ov.get('longitudinal_fwd_mae_m')} meters")
        print(f"  Longitudinal Fwd RMSE: {ov.get('longitudinal_fwd_rmse_m')} meters")
        print(f"  Lateral Left MAE:      {ov.get('lateral_left_mae_m')} meters")
        print(f"  Total Euclidean MAE:   {ov.get('total_euclidean_mae_m')} meters")
        print("-" * 60)
        print("  RANGE-BINNED ACCURACY:")
        for b_name, b_data in stats.get("range_bins", {}).items():
            if b_data.get("count", 0) > 0:
                print(f"    • {b_name:15}: N={b_data['count']:3d} | MAE={b_data['fwd_mae_m']}m | RMSE={b_data['fwd_rmse_m']}m")
    print("=" * 60)
    print(f"  Report exported to: {report_path}\n")

    return report_payload


def main():
    parser = argparse.ArgumentParser(description="Evaluate 3D vision tracks against radar ground truth")
    parser.add_argument("session", help="Path to session directory (e.g. logs/session_20261006_095609)")
    parser.add_argument("-v", "--vision_json", default=None, help="Path to vision tracks JSON")
    parser.add_argument("-o", "--output_dir", default=None, help="Output directory for reports")
    parser.add_argument("-g", "--gate_m", type=float, default=3.5, help="Max distance gate in meters")
    args = parser.parse_args()

    evaluate_session(
        session_dir=args.session,
        vision_json_path=args.vision_json,
        output_dir=args.output_dir,
        max_gate_m=args.gate_m,
    )


if __name__ == "__main__":
    main()
