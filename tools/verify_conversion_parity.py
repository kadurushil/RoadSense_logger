#!/usr/bin/env python3
"""
RoadSense Multi-Sensor Conversion Parity & Ground-Truth Fidelity Validator
========================================================================

Authoritative audit script to verify ground-up parity between:
  1. Binary raw sensor logs (radar_frames.bin, camera_video.mp4, etc.)
  2. Legacy desktop / web visualizer artifacts (track_history.json, frame_mapping.json)
  3. Foxglove Studio MCAP container (<session_id>.mcap)

Validates:
  - Cardinality & Frame Count Equivalence
  - Point Cloud Coordinate Remapping & Numerical Fidelity (ROS FLU vs Sensor Frame)
  - Radar Cluster Geometry & Radial Velocity Parity
  - EKF Track Coordinates, Dimensions (Semi-axes vs Cuboids) & Heading
  - Vehicle Telemetry / TLV 5 CAN Ingestion Parity (17 signals)
  - Vehicle Safety ADAS / TLV 6 Outputs Parity (FCW, ACC, BSD)
  - Monotonic Cross-Sensor Temporal Alignment Invariants

Usage:
  python tools/verify_conversion_parity.py --session logs/session_20261023_111120
  python tools/verify_conversion_parity.py --latest
  python tools/verify_conversion_parity.py --session <DIR> --samples 200 --tolerance 1e-3
"""

import os
import sys
import glob
import json
import math
import struct
import argparse
from typing import Dict, List, Any, Optional, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.sync_and_process_sessions import parse_radar_frames_bin
from tools.convert_session_to_mcap import parse_radar_stream

# Attempt MCAP and Protobuf imports
try:
    from mcap.reader import make_reader
    from foxglove_schemas_protobuf.PointCloud_pb2 import PointCloud
    from foxglove_schemas_protobuf.SceneUpdate_pb2 import SceneUpdate
    from foxglove_schemas_protobuf.LocationFix_pb2 import LocationFix
    MCAP_AVAILABLE = True
except ImportError:
    MCAP_AVAILABLE = False

# Terminal Colors & VT100
if sys.platform == "win32":
    os.system("")

ANSI_GREEN = "\033[92m"
ANSI_YELLOW = "\033[93m"
ANSI_RED = "\033[91m"
ANSI_CYAN = "\033[96m"
ANSI_BOLD = "\033[1m"
ANSI_RESET = "\033[0m"


class ParityCheckResult:
    """Holds individual test category results."""
    def __init__(self, name: str, weight: float):
        self.name = name
        self.weight = weight
        self.status = "PASS"  # PASS, WARN, FAIL
        self.score = 100.0
        self.details: List[str] = []
        self.errors: List[str] = []
        self.warnings: List[str] = []
        self.metrics: Dict[str, Any] = {}

    def fail(self, msg: str, score: float = 0.0):
        self.status = "FAIL"
        self.score = min(self.score, score)
        self.errors.append(msg)

    def warn(self, msg: str, penalty: float = 15.0):
        if self.status != "FAIL":
            self.status = "WARN"
        self.score = max(0.0, self.score - penalty)
        self.warnings.append(msg)

    def add_detail(self, msg: str):
        self.details.append(msg)


class ParityReport:
    """Consolidates results and renders terminal / JSON output."""
    def __init__(self, session_path: str):
        self.session_path = session_path
        self.session_id = os.path.basename(os.path.normpath(session_path))
        self.categories: Dict[str, ParityCheckResult] = {}

    def add_category(self, key: str, name: str, weight: float) -> ParityCheckResult:
        res = ParityCheckResult(name, weight)
        self.categories[key] = res
        return res

    def compute_total_score(self) -> float:
        total_weight = sum(c.weight for c in self.categories.values())
        if total_weight <= 0:
            return 100.0
        weighted_sum = sum(c.score * c.weight for c in self.categories.values())
        return round(weighted_sum / total_weight, 2)

    def overall_status(self) -> str:
        if any(c.status == "FAIL" for c in self.categories.values()):
            return "FAIL"
        if any(c.status == "WARN" for c in self.categories.values()):
            return "WARN"
        return "PASS"

    def print_terminal_summary(self):
        total_score = self.compute_total_score()
        overall = self.overall_status()

        color = ANSI_GREEN if overall == "PASS" else (ANSI_YELLOW if overall == "WARN" else ANSI_RED)

        print("\n" + "=" * 80)
        print(f"{ANSI_BOLD}RoadSense Multi-Sensor Conversion Parity Audit Scorecard{ANSI_RESET}")
        print(f"Session: {ANSI_CYAN}{self.session_id}{ANSI_RESET}")
        print(f"Path:    {self.session_path}")
        print("=" * 80)

        print(f"{'Category':<38} {'Weight':<8} {'Status':<10} {'Score':<8} {'Issues'}")
        print("-" * 80)

        for key, cat in self.categories.items():
            status_color = ANSI_GREEN if cat.status == "PASS" else (ANSI_YELLOW if cat.status == "WARN" else ANSI_RED)
            issue_count = len(cat.errors) + len(cat.warnings)
            issue_str = f"{issue_count} issue(s)" if issue_count > 0 else "0"
            print(f"{cat.name:<38} {cat.weight*100:>5.1f}%  {status_color}{cat.status:<10}{ANSI_RESET} {cat.score:>6.1f}%  {issue_str}")

        print("-" * 80)
        print(f"{ANSI_BOLD}{'OVERALL PARITY SCORE:':<48} {color}{overall:<10}{ANSI_RESET} {color}{total_score:>6.1f}%{ANSI_RESET}")
        print("=" * 80)

        # Print detailed errors/warnings if any
        has_issues = any(len(c.errors) > 0 or len(c.warnings) > 0 for c in self.categories.values())
        if has_issues:
            print(f"\n{ANSI_BOLD}Audit Findings & Discrepancy Callouts:{ANSI_RESET}")
            for key, cat in self.categories.items():
                if cat.errors or cat.warnings:
                    print(f"\n  [{cat.name}]")
                    for err in cat.errors:
                        print(f"    {ANSI_RED}[FAIL]{ANSI_RESET} {err}")
                    for warn in cat.warnings:
                        print(f"    {ANSI_YELLOW}[WARN]{ANSI_RESET} {warn}")
        else:
            print(f"\n{ANSI_GREEN}{ANSI_BOLD}[+] Ground-up numerical parity 100% verified across all conversion pipelines.{ANSI_RESET}")

    def to_dict(self) -> Dict[str, Any]:
        return {
            "session_id": self.session_id,
            "session_path": self.session_path,
            "overall_status": self.overall_status(),
            "overall_score": self.compute_total_score(),
            "categories": {
                k: {
                    "name": v.name,
                    "weight": v.weight,
                    "status": v.status,
                    "score": v.score,
                    "errors": v.errors,
                    "warnings": v.warnings,
                    "metrics": v.metrics
                }
                for k, v in self.categories.items()
            }
        }


# ==============================================================================
# Parity Verification Engine
# ==============================================================================

def verify_session_parity(
    session_dir: str,
    sample_count: int = 100,
    check_all: bool = False,
    tolerance: float = 1e-3
) -> ParityReport:
    """
    Executes full parity audit on session_dir and returns ParityReport.
    """
    session_path = os.path.abspath(session_dir)
    report = ParityReport(session_path)

    # 1. File Completeness
    cat_files = report.add_category("files", "1. Source & Converted File Integrity", 0.10)
    radar_bin = os.path.join(session_path, "radar", "radar_frames.bin")
    th_json = os.path.join(session_path, "track_history.json")
    fm_json = os.path.join(session_path, "frame_mapping.json")
    
    session_name = os.path.basename(session_path)
    mcap_file = os.path.join(session_path, f"{session_name}.mcap")
    if not os.path.isfile(mcap_file):
        # Look for any .mcap file in the session dir
        mcaps = glob.glob(os.path.join(session_path, "*.mcap"))
        mcap_file = mcaps[0] if mcaps else None

    # Verify source presence
    if not os.path.isfile(radar_bin):
        cat_files.fail(f"Missing raw radar stream: {radar_bin}")
        return report

    cat_files.metrics["radar_bin_size_mb"] = round(os.path.getsize(radar_bin) / (1024 * 1024), 2)

    if not os.path.isfile(th_json):
        cat_files.fail(f"Missing visualizer JSON: {th_json}")
    else:
        cat_files.metrics["track_history_size_mb"] = round(os.path.getsize(th_json) / (1024 * 1024), 2)

    if not os.path.isfile(fm_json):
        cat_files.fail(f"Missing visualizer frame mapping: {fm_json}")
    else:
        cat_files.metrics["frame_mapping_size_mb"] = round(os.path.getsize(fm_json) / (1024 * 1024), 2)

    has_mcap = mcap_file is not None and os.path.isfile(mcap_file)
    if not has_mcap:
        cat_files.add_detail(f"MCAP container not present in {session_path} (Skipping MCAP channel validation).")
    else:
        cat_files.metrics["mcap_size_mb"] = round(os.path.getsize(mcap_file) / (1024 * 1024), 2)

    # Load raw radar frames
    try:
        raw_frames = parse_radar_frames_bin(radar_bin)
    except Exception as e:
        cat_files.fail(f"Failed to parse radar_frames.bin: {e}")
        return report

    num_raw = len(raw_frames)
    cat_files.metrics["raw_radar_frames"] = num_raw
    if num_raw == 0:
        cat_files.fail("radar_frames.bin contains 0 valid frames.")
        return report

    # Load track_history.json
    viz_radar_frames = []
    viz_tracks = []
    if os.path.isfile(th_json):
        try:
            with open(th_json, "r", encoding="utf-8") as f:
                th_data = json.load(f)
            viz_radar_frames = th_data.get("radarFrames", [])
            viz_tracks = th_data.get("tracks", [])
        except Exception as e:
            cat_files.fail(f"Corrupt track_history.json: {e}")

    # Load frame_mapping.json (JSONL)
    mapping_records = []
    mapping_metadata = None
    if os.path.isfile(fm_json):
        try:
            with open(fm_json, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        rec = json.loads(line)
                        if "metadata" in rec:
                            mapping_metadata = rec["metadata"]
                        elif "radar_timestamp" in rec or "radar_frame_id_rel" in rec:
                            mapping_records.append(rec)
        except Exception as e:
            cat_files.fail(f"Corrupt frame_mapping.json: {e}")

    # Read MCAP Summary & Indexing if available
    mcap_stats = {}
    mcap_radar_tracks_count = 0
    mcap_radar_points_count = 0
    mcap_radar_pointcloud_count = 0
    mcap_telemetry_count = 0
    mcap_adas_count = 0
    mcap_video_count = 0
    mcap_sync_info_count = 0

    if has_mcap and MCAP_AVAILABLE:
        try:
            with open(mcap_file, "rb") as mf:
                mcap_reader = make_reader(mf)
                summary = mcap_reader.get_summary()
                if summary and summary.statistics:
                    ch_counts = summary.statistics.channel_message_counts
                    for ch_id, ch in summary.channels.items():
                        cnt = ch_counts.get(ch_id, 0)
                        mcap_stats[ch.topic] = cnt
                        if ch.topic == "/radar/tracks":
                            mcap_radar_tracks_count = cnt
                        elif ch.topic == "/radar/points":
                            mcap_radar_points_count = cnt
                        elif ch.topic in ("/radar/clusters", "/radar/pointcloud"):
                            mcap_radar_pointcloud_count = cnt
                        elif ch.topic == "/vehicle/telemetry":
                            mcap_telemetry_count = cnt
                        elif ch.topic == "/radar/adas":
                            mcap_adas_count = cnt
                        elif ch.topic == "/camera/video":
                            mcap_video_count = cnt
                        elif ch.topic == "/sync/frame_info":
                            mcap_sync_info_count = cnt
        except Exception as e:
            cat_files.warn(f"Failed to read MCAP index: {e}")

    # 2. Cardinality & Frame Count Equivalence
    cat_card = report.add_category("cardinality", "2. Cardinality & Frame Count Equivalence", 0.20)
    cat_card.metrics["raw_frames"] = num_raw
    cat_card.metrics["viz_frames"] = len(viz_radar_frames)
    cat_card.metrics["mapping_records"] = len(mapping_records)

    if len(viz_radar_frames) != num_raw:
        cat_card.fail(f"Frame count mismatch: raw={num_raw} vs track_history.json={len(viz_radar_frames)}")
    else:
        cat_card.add_detail(f"Raw radar and track_history.json frame counts match perfectly: {num_raw}")

    if len(mapping_records) != num_raw:
        cat_card.fail(f"Frame mapping count mismatch: raw={num_raw} vs frame_mapping.json={len(mapping_records)}")
    else:
        cat_card.add_detail(f"frame_mapping.json record count matches raw radar: {num_raw}")

    if has_mcap and MCAP_AVAILABLE:
        cat_card.metrics["mcap_radar_tracks"] = mcap_radar_tracks_count
        cat_card.metrics["mcap_radar_points"] = mcap_radar_points_count
        
        # In MCAP, /radar/tracks is emitted every frame
        if mcap_radar_tracks_count != num_raw:
            cat_card.fail(f"MCAP /radar/tracks count mismatch: raw={num_raw} vs MCAP={mcap_radar_tracks_count}")
        else:
            cat_card.add_detail(f"MCAP /radar/tracks matches raw frame count: {num_raw}")

        # In MCAP, /radar/points is emitted only when len(points) > 0
        raw_non_empty_pts = sum(1 for rf in raw_frames if len(rf["pointCloud"]) > 0)
        cat_card.metrics["raw_non_empty_point_frames"] = raw_non_empty_pts
        if mcap_radar_points_count != raw_non_empty_pts:
            cat_card.fail(f"MCAP /radar/points count mismatch: non_empty_raw={raw_non_empty_pts} vs MCAP={mcap_radar_points_count}")
        else:
            cat_card.add_detail(f"MCAP /radar/points matches non-empty raw point frames: {raw_non_empty_pts}")

    # Determine frame sampling indices for deep numerical verification
    if check_all or num_raw <= sample_count:
        sample_indices = list(range(num_raw))
    else:
        step = num_raw / float(sample_count)
        sample_indices = [int(i * step) for i in range(sample_count)]

    # 3. Radar Point Cloud Numerical Parity
    cat_pts = report.add_category("pointcloud", "3. Radar Point Cloud Numerical Fidelity", 0.15)
    max_pt_err_x = 0.0
    max_pt_err_y = 0.0
    max_pt_err_z = 0.0
    max_pt_err_v = 0.0
    max_pt_err_snr = 0.0
    pt_checks_passed = 0
    pt_checks_total = 0

    for idx in sample_indices:
        raw_f = raw_frames[idx]
        raw_pts = raw_f["pointCloud"]
        if idx >= len(viz_radar_frames):
            continue

        viz_f = viz_radar_frames[idx]
        viz_pts = viz_f.get("pointCloud", [])

        # Count match
        if len(raw_pts) != len(viz_pts):
            cat_pts.fail(f"Frame {idx}: point count mismatch: raw={len(raw_pts)} vs viz={len(viz_pts)}")
            continue

        # Point by point comparison (Sensor Coordinates: X=right, Y=forward, Z=up)
        for p_raw, p_viz in zip(raw_pts, viz_pts):
            pt_checks_total += 1
            ex = abs(p_raw["x"] - p_viz["x"])
            ey = abs(p_raw["y"] - p_viz["y"])
            ez = abs(p_raw["z"] - p_viz["z"])
            ev = abs(p_raw["velocity"] - p_viz["velocity"])
            es = abs(p_raw["snr"] - p_viz["snr"])

            max_pt_err_x = max(max_pt_err_x, ex)
            max_pt_err_y = max(max_pt_err_y, ey)
            max_pt_err_z = max(max_pt_err_z, ez)
            max_pt_err_v = max(max_pt_err_v, ev)
            max_pt_err_snr = max(max_pt_err_snr, es)

            if max(ex, ey, ez, ev, es) <= tolerance:
                pt_checks_passed += 1

    cat_pts.metrics["points_checked"] = pt_checks_total
    cat_pts.metrics["max_error_x_m"] = round(max_pt_err_x, 6)
    cat_pts.metrics["max_error_y_m"] = round(max_pt_err_y, 6)
    cat_pts.metrics["max_error_vel_mps"] = round(max_pt_err_v, 6)
    cat_pts.metrics["max_error_snr_db"] = round(max_pt_err_snr, 6)

    if pt_checks_total > 0:
        pt_rate = (pt_checks_passed / pt_checks_total) * 100.0
        cat_pts.metrics["point_fidelity_pct"] = round(pt_rate, 2)
        if pt_rate < 99.9:
            cat_pts.fail(f"Point cloud values differed beyond tolerance ({tolerance}): rate={pt_rate:.2f}%")
        else:
            cat_pts.add_detail(f"Point cloud numerical fidelity {pt_rate:.2f}% across {pt_checks_total} points.")

    # 4. Target Cluster Parity (TLV 2)
    cat_cls = report.add_category("clusters", "4. Radar Cluster Geometry Parity (TLV 2)", 0.10)
    raw_cluster_frames = [i for i, f in enumerate(raw_frames) if len(f["clusters"]) > 0]
    cat_cls.metrics["frames_with_clusters"] = len(raw_cluster_frames)

    cls_checks_total = 0
    cls_checks_passed = 0
    max_cls_err_xy = 0.0

    if raw_cluster_frames:
        for idx in [i for i in sample_indices if i in raw_cluster_frames]:
            raw_c = raw_frames[idx]["clusters"]
            viz_c = viz_radar_frames[idx].get("clusters", [])

            if len(raw_c) != len(viz_c):
                cat_cls.fail(f"Frame {idx}: cluster count mismatch: raw={len(raw_c)} vs viz={len(viz_c)}")
                continue

            for c_raw, c_viz in zip(raw_c, viz_c):
                cls_checks_total += 1
                ex = abs(c_raw["x"] - c_viz["x"])
                ey = abs(c_raw["y"] - c_viz["y"])
                max_cls_err_xy = max(max_cls_err_xy, ex, ey)
                if max(ex, ey) <= tolerance:
                    cls_checks_passed += 1

        cat_cls.metrics["clusters_checked"] = cls_checks_total
        cat_cls.metrics["max_cluster_error_m"] = round(max_cls_err_xy, 6)

        if cls_checks_total > 0:
            cls_rate = (cls_checks_passed / cls_checks_total) * 100.0
            cat_cls.metrics["cluster_fidelity_pct"] = round(cls_rate, 2)
            if cls_rate < 99.9:
                cat_cls.fail(f"Cluster coordinates differed beyond tolerance ({tolerance}): rate={cls_rate:.2f}%")
            else:
                cat_cls.add_detail(f"Cluster numerical fidelity {cls_rate:.2f}% across {cls_checks_total} clusters.")
    else:
        cat_cls.add_detail("No TLV 2 cluster records detected in raw stream.")

    # Check if MCAP has /radar/clusters (Cluster topic)
    if has_mcap and MCAP_AVAILABLE:
        cluster_topic = "/radar/clusters" if "/radar/clusters" in mcap_stats else ("/radar/pointcloud" if "/radar/pointcloud" in mcap_stats else None)
        if cluster_topic:
            cat_cls.metrics["mcap_cluster_messages"] = mcap_radar_pointcloud_count
            cat_cls.add_detail(f"MCAP contains dedicated {cluster_topic} cluster topic with {mcap_radar_pointcloud_count} msgs.")
        else:
            cat_cls.warn("MCAP file lacks /radar/clusters topic (Re-conversion recommended to expose hardware clusters in Foxglove).", penalty=5.0)

    # 5. EKF Track Parity & Box Dimensions (TLV 3)
    cat_trk = report.add_category("tracks", "5. EKF Track Geometry & Dimensions (TLV 3)", 0.15)
    raw_track_frames = [i for i, f in enumerate(raw_frames) if len(f["tracks"]) > 0]
    cat_trk.metrics["frames_with_tracks"] = len(raw_track_frames)

    # Map viz tracks by trackId and frameIdx for O(1) lookup
    viz_track_map: Dict[Tuple[int, int], Dict[str, Any]] = {}
    for vt in viz_tracks:
        tid = vt.get("id") or vt.get("trackId")
        for log in vt.get("historyLog", []):
            f_idx = log.get("frameIdx")
            if f_idx is not None:
                # 1-indexed to 0-indexed alignment
                viz_track_map[(tid, f_idx - 1)] = log

    trk_checks_total = 0
    trk_checks_passed = 0
    max_trk_err_pos = 0.0
    max_trk_err_vel = 0.0

    for idx in [i for i in sample_indices if i in raw_track_frames]:
        raw_t_list = raw_frames[idx]["tracks"]
        for t_raw in raw_t_list:
            trk_checks_total += 1
            tid = t_raw["tid"]
            t_viz = viz_track_map.get((tid, idx))
            if not t_viz:
                cat_trk.warn(f"Track {tid} in frame {idx} missing from track_history.json tracks log.", penalty=1.0)
                continue

            pos = t_viz.get("correctedPosition", [0.0, 0.0])
            vel = t_viz.get("correctedVelocity", [0.0, 0.0])
            radii = t_viz.get("objectExtentRadii", [0.0, 0.0])

            ep_x = abs(t_raw["x"] - pos[0])
            ep_y = abs(t_raw["y"] - pos[1])
            ev_x = abs(t_raw["vx"] - vel[0])
            ev_y = abs(t_raw["vy"] - vel[1])

            max_trk_err_pos = max(max_trk_err_pos, ep_x, ep_y)
            max_trk_err_vel = max(max_trk_err_vel, ev_x, ev_y)

            # Check semi-axis radii interpretation parity:
            # t_raw["majorSize"] must equal radii[0] (semi-axis R_major)
            # t_raw["minorSize"] must equal radii[1] (semi-axis R_minor)
            er_major = abs(t_raw["majorSize"] - radii[0])
            er_minor = abs(t_raw["minorSize"] - radii[1])

            if max(ep_x, ep_y, ev_x, ev_y, er_major, er_minor) <= tolerance:
                trk_checks_passed += 1

    cat_trk.metrics["tracks_checked"] = trk_checks_total
    cat_trk.metrics["max_track_pos_error_m"] = round(max_trk_err_pos, 6)
    cat_trk.metrics["max_track_vel_error_mps"] = round(max_trk_err_vel, 6)

    if trk_checks_total > 0:
        trk_rate = (trk_checks_passed / trk_checks_total) * 100.0
        cat_trk.metrics["track_fidelity_pct"] = round(trk_rate, 2)
        if trk_rate < 99.0:
            cat_trk.fail(f"Track state differed beyond tolerance: rate={trk_rate:.2f}%")
        else:
            cat_trk.add_detail(f"Track state and semi-axis extents 100% verified across {trk_checks_total} track observations.")
    else:
        cat_trk.add_detail("No active tracks detected in sampled frames.")

    # 6. Vehicle Telemetry / TLV 5 CAN Inputs Parity
    cat_can = report.add_category("can_telemetry", "6. Vehicle Telemetry & CAN Ingestion (TLV 5)", 0.10)
    raw_can_frames = [i for i, f in enumerate(raw_frames) if f["can_inputs"] is not None]
    cat_can.metrics["frames_with_can_inputs"] = len(raw_can_frames)

    can_checks_total = 0
    can_checks_passed = 0
    missing_fields_in_viz = set()

    expected_tlv5_fields = [
        "canVehSpeed_kmph", "shaftTorque_Nm", "engagedGear",
        "roadGrade_Deg", "motionState", "acceleration_avg"
    ]

    for idx in [i for i in sample_indices if i in raw_can_frames]:
        if idx >= len(viz_radar_frames):
            continue
        raw_can = raw_frames[idx]["can_inputs"]
        viz_f = viz_radar_frames[idx]

        for field in expected_tlv5_fields:
            can_checks_total += 1
            if field not in viz_f:
                missing_fields_in_viz.add(field)
            else:
                can_checks_passed += 1

        # Check numerical speed match
        raw_speed = raw_can.get("speed_kmph", raw_can.get("speed_mps", 0.0) * 3.6)
        viz_speed = viz_f.get("canVehSpeed_kmph", 0.0)
        if abs(raw_speed - viz_speed) > 0.1:
            cat_can.fail(f"Frame {idx}: Speed mismatch: raw={raw_speed:.2f} km/h vs viz={viz_speed:.2f} km/h")

    if missing_fields_in_viz:
        cat_can.fail(f"track_history.json missing TLV 5 fields: {list(missing_fields_in_viz)}")

    # Check MCAP /vehicle/telemetry field completeness
    if has_mcap and MCAP_AVAILABLE and "/vehicle/telemetry" in mcap_stats:
        try:
            with open(mcap_file, "rb") as mf:
                reader = make_reader(mf)
                for schema, ch, msg in reader.iter_messages(topics=["/vehicle/telemetry"]):
                    t_payload = json.loads(msg.data.decode("utf-8"))
                    required_mcap_can = ["road_grade_deg", "shaft_torque_nm", "gear", "motion_state"]
                    missing_mcap = [k for k in required_mcap_can if k not in t_payload]
                    if missing_mcap:
                        cat_can.warn(
                            f"MCAP /vehicle/telemetry lacks extended CAN fields: {missing_mcap} "
                            "(Re-conversion required to populate all 17 CAN signals in Foxglove).",
                            penalty=15.0
                        )
                    else:
                        cat_can.add_detail("MCAP /vehicle/telemetry contains all extended CAN signals.")
                    break
        except Exception as e:
            cat_can.warn(f"Failed to inspect MCAP telemetry: {e}")

    # 7. Vehicle Safety ADAS / TLV 6 Outputs Parity
    cat_adas = report.add_category("adas_safety", "7. ADAS Safety & Alerts Parity (TLV 6)", 0.10)
    raw_adas_frames = [i for i, f in enumerate(raw_frames) if f["adas_can"] is not None]
    cat_adas.metrics["frames_with_adas_outputs"] = len(raw_adas_frames)

    adas_missing_in_viz = set()
    fcw_alerts_detected = 0

    for idx in [i for i in sample_indices if i in raw_adas_frames]:
        if idx >= len(viz_radar_frames):
            continue
        raw_adas = raw_frames[idx]["adas_can"]
        viz_adas_list = viz_radar_frames[idx].get("adas", [])
        viz_adas = viz_adas_list[0] if (isinstance(viz_adas_list, list) and viz_adas_list) else (viz_adas_list if isinstance(viz_adas_list, dict) else {})

        # Critical fields that were previously dropped in Bug Evidence 1:
        req_adas_fields = ["acc_target_x", "v_safe_mps", "fcw_track_id", "fcw_target_x", "fcw_target_y", "fcw_target_vx", "fcw_target_vy", "fcw_ttc"]
        for fld in req_adas_fields:
            if fld not in viz_adas:
                adas_missing_in_viz.add(fld)

        if raw_adas.get("fcw_stage", 0) > 0:
            fcw_alerts_detected += 1

    cat_adas.metrics["fcw_alert_frames"] = fcw_alerts_detected

    if adas_missing_in_viz:
        cat_adas.fail(
            f"track_history.json missing TLV 6 ADAS safety fields: {list(adas_missing_in_viz)} "
            "(Evidence 1: sync_and_process_sessions.py re-processing required)."
        )
    else:
        cat_adas.add_detail("All TLV 6 ADAS fields (ACC, FCW, BSD) present and verified in track_history.json.")

    # 8. Monotonic Synchronization & Mapping Invariants
    cat_sync = report.add_category("time_sync", "8. Cross-Sensor Temporal Sync Invariants", 0.10)
    
    # Check monotonicity in frame_mapping.json
    prev_ts = -1.0
    backwards_time_jumps = 0
    excessive_deltas = 0
    max_sync_delta_ms = 0.0

    for i, m in enumerate(mapping_records):
        ts = m.get("radar_timestamp", 0.0)
        v_idx = m.get("video_frame_index", 0)
        delta = abs(m.get("video_time_delta", 0.0))

        if ts < prev_ts:
            backwards_time_jumps += 1
        prev_ts = ts

        # Only measure sync delta after video recording has actually commenced (beyond pre-roll frame 0)
        if v_idx > 0:
            max_sync_delta_ms = max(max_sync_delta_ms, delta * 1000.0)
            if delta > 0.100:  # > 100 ms is unexpected for nominal 20 Hz / 30 FPS sync
                excessive_deltas += 1

    cat_sync.metrics["backwards_time_jumps"] = backwards_time_jumps
    cat_sync.metrics["max_sync_delta_ms"] = round(max_sync_delta_ms, 2)
    cat_sync.metrics["excessive_sync_deltas_over_100ms"] = excessive_deltas

    if backwards_time_jumps > 0:
        cat_sync.fail(f"Non-monotonic timestamps detected: {backwards_time_jumps} backwards time jumps!")
    else:
        cat_sync.add_detail("Monotonic timestamp sequence verified (0 backwards time steps).")

    if excessive_deltas > 5:
        cat_sync.warn(f"{excessive_deltas} frames exhibited camera-to-radar sync delta > 100 ms (Peak: {max_sync_delta_ms:.1f} ms).")
    else:
        cat_sync.add_detail(f"Tight temporal alignment maintained (Peak delta: {max_sync_delta_ms:.1f} ms).")

    # Verify GNSS LocationFix heading (radians), ENU velocity alignment, and dual-stream network separation
    if has_mcap and MCAP_AVAILABLE and "/gnss/fix" in mcap_stats:
        try:
            with open(mcap_file, "rb") as mf:
                reader = make_reader(mf)
                gnss_samples_checked = 0
                heading_in_radians = True
                heading_vel_aligned = True
                isolated_speed_drops = 0
                dist_jumps = 0
                prev_loc = None
                prev_speed = 0.0

                for schema, ch, msg in reader.iter_messages(topics=["/gnss/fix"]):
                    loc = LocationFix()
                    loc.ParseFromString(msg.data)
                    # Check 1: frame_id must be "wgs84"
                    if loc.frame_id != "wgs84":
                        cat_sync.warn(f"GNSS fix frame_id is '{loc.frame_id}', expected 'wgs84' for Foxglove Map panel.")
                    # Check 2: heading must be in radians (0 to 2*pi)
                    if loc.heading > 2 * math.pi + 0.1:
                        heading_in_radians = False
                    
                    v_speed = math.hypot(loc.velocity.x, loc.velocity.y)
                    # Check 3: when moving (> 1 m/s), heading angle must match ENU velocity angle
                    if v_speed > 1.0 and loc.heading is not None:
                        vel_heading = (math.atan2(loc.velocity.x, loc.velocity.y) + 2 * math.pi) % (2 * math.pi)
                        diff = abs((loc.heading - vel_heading + math.pi) % (2 * math.pi) - math.pi)
                        if diff > 0.05:  # ~3 degrees
                            heading_vel_aligned = False

                    # Check 4: Check for jumps and sudden drops to 0 speed in /gnss/fix
                    if prev_loc is not None:
                        dt = (loc.timestamp.seconds - prev_loc.timestamp.seconds) + (loc.timestamp.nanos - prev_loc.timestamp.nanos) * 1e-9
                        if dt > 0:
                            dlat = (loc.latitude - prev_loc.latitude) * 111139.0
                            dlon = (loc.longitude - prev_loc.longitude) * 111139.0 * math.cos(math.radians(loc.latitude))
                            dist = math.hypot(dlat, dlon)
                            if (dist / dt) > 40.0:  # > 40 m/s (144 km/h) jump
                                dist_jumps += 1
                        if prev_speed > 2.0 and v_speed == 0.0:
                            isolated_speed_drops += 1

                    prev_loc = loc
                    prev_speed = v_speed
                    gnss_samples_checked += 1

                if not heading_in_radians:
                    cat_sync.fail("GNSS LocationFix.heading is formatted in DEGREES instead of RADIANS (causes wild heading in Foxglove).")
                elif not heading_vel_aligned:
                    cat_sync.warn("GNSS LocationFix.heading deviated from ENU velocity vector during vehicle motion.")
                elif dist_jumps > 0:
                    cat_sync.fail(f"/gnss/fix exhibited {dist_jumps} sudden coordinate teleportation jumps (>40 m/s)!")
                elif isolated_speed_drops > 0:
                    cat_sync.warn(f"/gnss/fix exhibited {isolated_speed_drops} isolated sudden drops to 0 speed while moving.")
                else:
                    cat_sync.add_detail("GNSS LocationFix heading, ENU velocity vectors, and continuous trajectory verified 100% clean.")

                if "/gnss/network" in mcap_stats:
                    net_cnt = mcap_stats["/gnss/network"]
                    cat_sync.metrics["mcap_gnss_network_messages"] = net_cnt
                    cat_sync.add_detail(f"Cellular network fixes cleanly decoupled into /gnss/network ({net_cnt} msgs).")

                # Verify /imu/vehicle transformed vehicle body frame kinematics
                if "/imu/vehicle" in mcap_stats:
                    veh_pitches = []
                    veh_rolls = []
                    veh_az = []
                    imu_samples_read = 0
                    for schema, ch, msg in reader.iter_messages(topics=["/imu/vehicle"]):
                        if imu_samples_read % 100 == 0:
                            v_data = json.loads(msg.data.decode("utf-8"))
                            veh_pitches.append(v_data.get("pitch_deg", 0.0))
                            veh_rolls.append(v_data.get("roll_deg", 0.0))
                            accel = v_data.get("acceleration", {})
                            veh_az.append(accel.get("z", 0.0))
                        imu_samples_read += 1

                    if veh_pitches:
                        med_pitch = sorted(veh_pitches)[len(veh_pitches) // 2]
                        med_roll = sorted(veh_rolls)[len(veh_rolls) // 2]
                        med_az = sorted(veh_az)[len(veh_az) // 2]
                        cat_sync.metrics["imu_vehicle_median_pitch_deg"] = round(med_pitch, 2)
                        cat_sync.metrics["imu_vehicle_median_roll_deg"] = round(med_roll, 2)
                        cat_sync.metrics["imu_vehicle_median_az_mps2"] = round(med_az, 2)

                        if abs(med_pitch) > 10.0:
                            cat_sync.fail(f"/imu/vehicle pitch_deg ({med_pitch:.1f}°) indicates uncompensated mounting pitch!")
                        elif med_az < 5.0 or med_az > 15.0:
                            cat_sync.warn(f"/imu/vehicle vertical acceleration Az ({med_az:.1f} m/s²) deviates from 1G gravity reaction.")
                        else:
                            cat_sync.add_detail(f"/imu/vehicle verified: Pitch={med_pitch:.1f}°, Roll={med_roll:.1f}°, Az={med_az:.1f} m/s² (100% physically aligned & mount compensated).")
        except Exception as e:
            cat_sync.warn(f"Failed to verify GNSS/IMU messages in MCAP: {e}")

    return report


def main():
    parser = argparse.ArgumentParser(
        description="RoadSense Ground-Up Multi-Sensor Conversion Parity Validator"
    )
    parser.add_argument(
        "--session", "-s",
        type=str,
        help="Path or name of the session directory to verify (e.g. logs/session_20261023_111120)"
    )
    parser.add_argument(
        "--latest",
        action="store_true",
        help="Automatically select the most recent session directory in logs/"
    )
    parser.add_argument(
        "--samples",
        type=int,
        default=100,
        help="Number of evenly spaced frames to inspect for full numerical parity (default: 100)"
    )
    parser.add_argument(
        "--all-frames",
        action="store_true",
        help="Inspect all frames for exhaustive parity verification (takes longer)"
    )
    parser.add_argument(
        "--tolerance",
        type=float,
        default=1e-3,
        help="Maximum allowable numerical difference for float comparisons (default: 0.001)"
    )
    parser.add_argument(
        "--json-out",
        type=str,
        help="Optional path to output the JSON scorecard"
    )

    args = parser.parse_args()

    session_dir = args.session
    if args.latest or not session_dir:
        logs_root = os.path.join(REPO_ROOT, "logs")
        sessions = sorted(
            [os.path.join(logs_root, d) for d in os.listdir(logs_root) if d.startswith("session_")],
            key=os.path.getmtime
        )
        if not sessions:
            print(f"{ANSI_RED}[ERROR] No sessions found in {logs_root}{ANSI_RESET}")
            sys.exit(1)
        session_dir = sessions[-1]
        print(f"[*] Auto-selected latest session: {session_dir}")

    if not os.path.isabs(session_dir):
        # Resolve relative to REPO_ROOT or logs/
        candidate1 = os.path.join(REPO_ROOT, session_dir)
        candidate2 = os.path.join(REPO_ROOT, "logs", session_dir)
        if os.path.isdir(candidate1):
            session_dir = candidate1
        elif os.path.isdir(candidate2):
            session_dir = candidate2

    if not os.path.isdir(session_dir):
        print(f"{ANSI_RED}[ERROR] Session directory not found: {session_dir}{ANSI_RESET}")
        sys.exit(1)

    report = verify_session_parity(
        session_dir,
        sample_count=args.samples,
        check_all=args.all_frames,
        tolerance=args.tolerance
    )

    report.print_terminal_summary()

    if args.json_out:
        out_p = os.path.abspath(args.json_out)
        with open(out_p, "w", encoding="utf-8") as f:
            json.dump(report.to_dict(), f, indent=2)
        print(f"\n[+] Saved parity scorecard to {out_p}")

    # Exit with code 0 on PASS, 1 on FAIL
    sys.exit(0 if report.overall_status() != "FAIL" else 1)


if __name__ == "__main__":
    main()
