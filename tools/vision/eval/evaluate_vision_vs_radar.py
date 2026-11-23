"""
evaluate_vision_vs_radar.py
---------------------------
CLI tool for programmatic evaluation of 3D geometric vision tracks against
mmWave radar tracks from RoadSense MCAP recordings.
Evaluates all lateral targets up to a tunable forward range (default 50m),
uses front contact-patch anchors and nearest-radius matching by default,
preserves backward-compatible JSON report keys, and optionally back-calculates
the session-wide ideal camera pitch angle with full re-benchmarking.
"""

import argparse
import bisect
from collections import Counter, defaultdict
import glob
import json
import os
import re
import sys
import time
from typing import Dict, Any, List, Optional, Set, Tuple, Callable

# Ensure UTF-8 console output & VT100 ANSI support on Windows
if sys.platform == "win32":
    os.system("")
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

ANSI_GREEN = "\033[92m"
ANSI_YELLOW = "\033[93m"
ANSI_RED = "\033[91m"
ANSI_CYAN = "\033[96m"
ANSI_MAGENTA = "\033[95m"
ANSI_WHITE = "\033[97m"
ANSI_GRAY = "\033[90m"
ANSI_BOLD = "\033[1m"
ANSI_RESET = "\033[0m"


def _color_rate(pct: float, padded: Optional[str] = None) -> str:
    txt = padded if padded is not None else f"{pct:.1f}%"
    if pct >= 50.0:
        return f"{ANSI_GREEN}{ANSI_BOLD}{txt}{ANSI_RESET}"
    if pct >= 20.0:
        return f"{ANSI_YELLOW}{ANSI_BOLD}{txt}{ANSI_RESET}"
    return f"{ANSI_RED}{ANSI_BOLD}{txt}{ANSI_RESET}"


def _color_mae(val_m: Optional[float], padded: Optional[str] = None) -> str:
    if val_m is None:
        return f"{ANSI_GRAY}{padded or 'N/A'}{ANSI_RESET}"
    txt = padded if padded is not None else f"{val_m:.3f}m"
    if val_m <= 1.25:
        return f"{ANSI_GREEN}{ANSI_BOLD}{txt}{ANSI_RESET}"
    if val_m <= 2.00:
        return f"{ANSI_YELLOW}{ANSI_BOLD}{txt}{ANSI_RESET}"
    return f"{ANSI_RED}{ANSI_BOLD}{txt}{ANSI_RESET}"


def _color_bias(bias_m: Optional[float], padded: Optional[str] = None) -> str:
    if bias_m is None:
        return f"{ANSI_GRAY}{padded or 'N/A'}{ANSI_RESET}"
    txt = padded if padded is not None else f"{bias_m:+.3f}m"
    ab = abs(bias_m)
    if ab <= 0.20:
        return f"{ANSI_GREEN}{ANSI_BOLD}{txt}{ANSI_RESET}"
    if ab <= 0.60:
        return f"{ANSI_YELLOW}{ANSI_BOLD}{txt}{ANSI_RESET}"
    return f"{ANSI_RED}{ANSI_BOLD}{txt}{ANSI_RESET}"


def _color_pitch_err(err_deg: float) -> str:
    txt = f"{err_deg:.3f}°"
    if err_deg < 0.30:
        return f"{ANSI_GREEN}{ANSI_BOLD}{txt}{ANSI_RESET}"
    if err_deg < 1.00:
        return f"{ANSI_YELLOW}{ANSI_BOLD}{txt}{ANSI_RESET}"
    return f"{ANSI_RED}{ANSI_BOLD}{txt}{ANSI_RESET}"


def _emit_progress(payload: Dict[str, Any], force: bool = False) -> None:
    """Emits machine-readable progress JSON for the RoadSense Web Dashboard SSE stream."""
    if not sys.stdout.isatty() or force:
        print(f"[PROGRESS] {json.dumps(payload)}", flush=True)

# Ensure repository root is on sys.path
repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if repo_root not in sys.path:
    sys.path.insert(0, repo_root)

from mcap.reader import make_reader
from foxglove_schemas_protobuf.SceneUpdate_pb2 import SceneUpdate

from tools.vision.eval.spatial_associator import (
    SpatialAssociator,
    get_vision_anchor_xy,
    normalize_anchor_mode,
    normalize_association_mode,
)
from tools.vision.eval.error_metrics import ErrorMetricsCalculator
from tools.vision.geometry.camera_extrinsics import CameraExtrinsics
from tools.vision.geometry.ground_plane_projector import PinholeIntrinsics
from tools.vision.geometry.dynamic_pitch_solver import DynamicPitchSolver


_RADAR_LABEL_RE = re.compile(r"ID\s*#(\d+)\s*\|\s*([+-]?\d+(?:\.\d+)?)\s*m/s")


def parse_radar_label(text: str, fallback_id: int) -> Tuple[int, float]:
    """Extracts hardware radar track ID and forward relative velocity from Foxglove label."""
    if text:
        m = _RADAR_LABEL_RE.search(text)
        if m:
            return int(m.group(1)), float(m.group(2))
    return fallback_id, 0.0


def load_radar_tracks_from_mcap(
    mcap_path: str,
    progress_cb: Optional[Callable[[int], None]] = None,
) -> List[Dict[str, Any]]:
    """Reads all radar frames and tracks from '/radar/tracks' in the session MCAP."""
    frames = []
    last_t = time.time()
    with open(mcap_path, "rb") as f:
        reader = make_reader(f)
        for schema, channel, message in reader.iter_messages(topics=["/radar/tracks"]):
            su = SceneUpdate()
            su.ParseFromString(message.data)
            frame_tracks = []
            for entity in su.entities:
                for idx, cube in enumerate(entity.cubes):
                    label_txt = entity.texts[idx].text if idx < len(entity.texts) else ""
                    track_id, vx_fwd = parse_radar_label(label_txt, idx + 1)
                    frame_tracks.append({
                        "track_id": track_id,
                        "vx_fwd_mps": vx_fwd,
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
            if progress_cb is not None and len(frames) % 2500 == 0:
                now = time.time()
                if now - last_t >= 0.35:
                    last_t = now
                    progress_cb(len(frames))
    return frames


def _resolve_camera_params(
    session_dir: str, vision_data: Dict[str, Any]
) -> Tuple[CameraExtrinsics, PinholeIntrinsics]:
    """Resolves CameraExtrinsics and PinholeIntrinsics from session calib JSON or vision JSON."""
    calib_path = os.path.join(session_dir, "radar_camera_calib.json")
    width = int(vision_data.get("image_width", 1280))
    height = int(vision_data.get("image_height", 720))

    if os.path.isfile(calib_path):
        extrinsics = CameraExtrinsics.from_calib_json(calib_path)
        intrinsics = PinholeIntrinsics.from_calib_json(calib_path, width=width, height=height)
        return extrinsics, intrinsics

    v_ext = vision_data.get("camera_extrinsics", {})
    extrinsics = CameraExtrinsics(
        camera_height_m=float(v_ext.get("height_m", 1.45)),
        pitch_deg=float(v_ext.get("pitch_deg", 7.0)),
        yaw_deg=float(v_ext.get("yaw_deg", 0.0)),
        roll_deg=float(v_ext.get("roll_deg", 0.0)),
        camera_yaw_deg=float(v_ext.get("yaw_deg", 0.0)),
    )
    if v_ext.get("fx") and v_ext.get("fy"):
        intrinsics = PinholeIntrinsics(
            fx=float(v_ext["fx"]),
            fy=float(v_ext["fy"]),
            cx=float(v_ext.get("cx", width / 2.0)),
            cy=float(v_ext.get("cy", height / 2.0)),
            width=width,
            height=height,
        )
    else:
        intrinsics = PinholeIntrinsics.from_fov(width=width, height=height)
    return extrinsics, intrinsics


def _run_benchmark_pass(
    vision_frames: List[Dict[str, Any]],
    radar_frames: List[Dict[str, Any]],
    radar_ts: List[int],
    time_offset_ns: int,
    associator: SpatialAssociator,
    max_range_m: Optional[float],
    excluded_classes: Set[str],
    min_conf: float,
    progress_cb: Optional[Callable[[int, int, float, float], None]] = None,
) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """
    Executes one full Vision <-> Radar evaluation pass over vision_frames.
    Applies NO lateral filtering. Scopes top-level metrics to (0, max_range_m]
    while tracking full-range extended bins (including >50m falloff).
    """
    total_r_tracks = sum(len(rf.get("tracks", [])) for rf in radar_frames)
    empty_radar_frames = sum(1 for rf in radar_frames if not rf.get("tracks"))
    behind_bumper_tracks = sum(
        1 for rf in radar_frames for rt in rf.get("tracks", []) if rt["x_fwd_m"] <= 0.0
    )

    all_matched_pairs: List[Dict[str, Any]] = []
    scoped_matched_pairs: List[Dict[str, Any]] = []
    all_vision_records: List[Dict[str, Any]] = []
    scoped_vision_records: List[Dict[str, Any]] = []
    unmatched_counts: Counter = Counter()
    radar_hits_by_frame: Dict[int, Set[int]] = defaultdict(set)

    total_v_frames = len(vision_frames)
    t_start = time.time()
    last_prog_t = t_start

    for f_idx, vf in enumerate(vision_frames):
        if progress_cb is not None and ((f_idx + 1) % 1500 == 0 or (f_idx + 1) == total_v_frames):
            now = time.time()
            if (now - last_prog_t >= 0.35) or (f_idx + 1) == total_v_frames:
                last_prog_t = now
                elapsed = max(now - t_start, 0.001)
                fps = (f_idx + 1) / elapsed
                eta_s = (total_v_frames - (f_idx + 1)) / max(fps, 1.0)
                progress_cb(f_idx + 1, total_v_frames, fps, eta_s)
        if "timestamp_utc_ns" in vf and vf["timestamp_utc_ns"] > 1e16:
            v_ts = int(vf["timestamp_utc_ns"])
        else:
            raw_ts = int(vf.get("timestamp_ns", 0))
            v_ts = raw_ts if raw_ts > 1e16 else (raw_ts + time_offset_ns)

        eligible_tracks = [
            vt for vt in vf.get("tracks", [])
            if str(vt.get("class_name", "")).lower() not in excluded_classes
            and float(vt.get("confidence", 1.0)) >= min_conf
        ]
        if not eligible_tracks or not radar_ts:
            continue

        idx = bisect.bisect_left(radar_ts, v_ts)
        candidates = [c for c in (idx - 1, idx) if 0 <= c < len(radar_ts)]
        best_c = min(candidates, key=lambda c: abs(v_ts - radar_ts[c]))
        best_dt_ms = abs(v_ts - radar_ts[best_c]) / 1e6
        best_rf = radar_frames[best_c]

        # Record all vision tracks across all ranges
        in_scope_flags: List[bool] = []
        for vt in eligible_tracks:
            vx, vy = get_vision_anchor_xy(vt, associator.anchor_mode)
            rec = {
                "x_fwd_m": vx,
                "y_left_m": vy,
                "class_name": vt.get("class_name", "unknown"),
            }
            all_vision_records.append(rec)
            in_scope = vx > 0.0 and (max_range_m is None or max_range_m <= 0.0 or vx <= max_range_m)
            in_scope_flags.append(in_scope)
            if in_scope:
                scoped_vision_records.append(rec)

        if best_dt_ms <= associator.max_time_ms:
            res = associator.associate_frame(eligible_tracks, best_rf["tracks"], time_delta_ms=best_dt_ms)
            matched_v_set = res["matched_v_indices"]

            # Deduplicate Radar -> Vision hits per unique radar frame index
            if best_rf["tracks"]:
                radar_hits_by_frame[best_c].update(res["radar_matched_indices"])

            for p in res["matched_pairs"]:
                all_matched_pairs.append(p)
                if p["v_fwd_m"] > 0.0 and (max_range_m is None or max_range_m <= 0.0 or p["v_fwd_m"] <= max_range_m):
                    scoped_matched_pairs.append(p)

            # Tally unmatched reasons specifically for scoped vision tracks
            unmatched_iter = iter(res["unmatched_reasons"])
            for v_i, in_scope in enumerate(in_scope_flags):
                if v_i not in matched_v_set:
                    reason = next(unmatched_iter, "unknown")
                    if in_scope:
                        unmatched_counts[reason] += 1
        else:
            for in_scope in in_scope_flags:
                if in_scope:
                    unmatched_counts["time_gate_exceeded"] += 1

    # Radar -> Vision precision across unique synchronized radar frames
    eval_r_tracks = sum(len(radar_frames[rf_idx]["tracks"]) for rf_idx in radar_hits_by_frame)
    matched_r_tracks = sum(len(hits) for hits in radar_hits_by_frame.values())
    r2v_rate = round((matched_r_tracks / max(eval_r_tracks, 1)) * 100.0, 1) if eval_r_tracks > 0 else 0.0

    radar_to_vision_stats = {
        "evaluated_radar_tracks": eval_r_tracks,
        "matched_radar_tracks": matched_r_tracks,
        "match_rate_pct": r2v_rate,
        "empty_radar_frames": empty_radar_frames,
        "total_radar_frames": len(radar_frames),
        "behind_bumper_tracks_le_0m": behind_bumper_tracks,
    }

    eval_config = {
        "max_gate_m": associator.max_gate_m,
        "max_range_m": max_range_m if (max_range_m and max_range_m > 0) else None,
        "anchor_mode": associator.anchor_mode,
        "association_mode": associator.association_mode,
        "excluded_classes": sorted(excluded_classes),
        "min_conf": min_conf,
        "lateral_filtering": False,
    }

    summary = ErrorMetricsCalculator.compute_summary_statistics(
        matched_pairs=scoped_matched_pairs,
        total_vision_tracks=len(scoped_vision_records),
        total_radar_tracks=total_r_tracks,
        scoped_vision_records=scoped_vision_records,
        all_vision_records=all_vision_records,
        all_matched_pairs=all_matched_pairs,
        unmatched_breakdown=dict(unmatched_counts),
        radar_to_vision_stats=radar_to_vision_stats,
        eval_config=eval_config,
    )
    return summary, scoped_matched_pairs


def _print_summary_block(title: str, stats: Dict[str, Any]) -> None:
    print(f"\n  {ANSI_CYAN}{ANSI_BOLD}--- {title} ---{ANSI_RESET}")
    print(
        f"  Scoped Vision Tracks:  {ANSI_WHITE}{ANSI_BOLD}{stats['total_vision_tracks']:,}{ANSI_RESET} | "
        f"Total Radar Tracks: {ANSI_WHITE}{ANSI_BOLD}{stats['total_radar_tracks']:,}{ANSI_RESET}"
    )
    rate_str = _color_rate(stats["match_rate_pct"], f"{stats['match_rate_pct']}% match rate")
    print(f"  Matched Track Pairs:   {ANSI_WHITE}{ANSI_BOLD}{stats['matched_count']:,}{ANSI_RESET} ({rate_str})")
    if "all_ranges" in stats:
        ar = stats["all_ranges"]
        ar_rate = _color_rate(ar["match_rate_pct"], f"{ar['match_rate_pct']}%")
        print(
            f"  All Ranges (0-inf m):  {ANSI_WHITE}{ar['matched_count']:,}/{ar['total_vision_tracks']:,}{ANSI_RESET} ({ar_rate})"
        )
    ov = stats.get("overall", {})
    if ov:
        fwd_mae_c = _color_mae(ov.get("longitudinal_fwd_mae_m"), f"{ov.get('longitudinal_fwd_mae_m')}m")
        fwd_rmse_c = _color_mae(ov.get("longitudinal_fwd_rmse_m"), f"{ov.get('longitudinal_fwd_rmse_m')}m")
        bias_c = _color_bias(ov.get("mean_signed_fwd_bias_m"), f"{ov.get('mean_signed_fwd_bias_m'):+}m")
        lat_mae_c = _color_mae(ov.get("lateral_left_mae_m"), f"{ov.get('lateral_left_mae_m')}m")
        eucl_mae_c = _color_mae(ov.get("total_euclidean_mae_m"), f"{ov.get('total_euclidean_mae_m')}m")
        print(f"  Fwd MAE / RMSE / Bias: {fwd_mae_c} / {fwd_rmse_c} / {bias_c}")
        print(f"  Lat MAE / Eucl MAE:    {lat_mae_c} / {eucl_mae_c}")
    ext_bins = stats.get("extended_range_bins", {})
    if ext_bins:
        print(f"  {ANSI_CYAN}{ANSI_BOLD}EXTENDED RANGE BREAKDOWN (ALL LATERAL POSITIONS):{ANSI_RESET}")
        for b_name, bd in ext_bins.items():
            vc = bd.get("vision_count", 0)
            if vc > 0 or bd.get("count", 0) > 0:
                rate_val = bd.get("match_rate_pct", 0.0)
                rate_pad = _color_rate(rate_val, f"{rate_val:5.1f}%")
                bias_raw = f"{bd['mean_signed_fwd_bias_m']:+}m" if bd.get("mean_signed_fwd_bias_m") is not None else "N/A"
                mae_raw = f"{bd['fwd_mae_m']}m" if bd.get("fwd_mae_m") is not None else "N/A"
                mae_pad = _color_mae(bd.get("fwd_mae_m"), f"{mae_raw:>6s}")
                bias_pad = _color_bias(bd.get("mean_signed_fwd_bias_m"), f"{bias_raw:>7s}")
                print(
                    f"    • {ANSI_WHITE}{b_name:10s}{ANSI_RESET}: {bd['count']:5d}/{vc:5d} ({rate_pad}) | "
                    f"MAE={mae_pad} | Bias={bias_pad}"
                )
    by_cls = stats.get("by_class", {})
    if by_cls:
        print(f"  {ANSI_CYAN}{ANSI_BOLD}BY CLASS:{ANSI_RESET}")
        for c_name, cd in by_cls.items():
            rate_val = cd["match_rate_pct"]
            rate_pad = _color_rate(rate_val, f"{rate_val:5.1f}%")
            mae_raw = f"{cd['fwd_mae_m']}m" if cd.get("fwd_mae_m") is not None else "N/A"
            bias_raw = f"{cd['mean_signed_fwd_bias_m']:+}m" if cd.get("mean_signed_fwd_bias_m") is not None else "N/A"
            mae_pad = _color_mae(cd.get("fwd_mae_m"), f"{mae_raw:>6s}")
            bias_pad = _color_bias(cd.get("mean_signed_fwd_bias_m"), f"{bias_raw:>7s}")
            print(
                f"    • {ANSI_WHITE}{c_name:11s}{ANSI_RESET}: {cd['matched_count']:5d}/{cd['vision_count']:5d} "
                f"({rate_pad}) | MAE={mae_pad} | Bias={bias_pad}"
            )
    r2v = stats.get("radar_to_vision", {})
    if r2v:
        r2v_rate = _color_rate(r2v["match_rate_pct"], f"{r2v['match_rate_pct']}%")
        empty_pct = (r2v["empty_radar_frames"] / max(r2v["total_radar_frames"], 1)) * 100.0
        empty_col = ANSI_RED if empty_pct > 15.0 else (ANSI_YELLOW if empty_pct > 5.0 else ANSI_GREEN)
        print(
            f"  Radar -> Vision Prec:  {ANSI_WHITE}{r2v['matched_radar_tracks']:,}/{r2v['evaluated_radar_tracks']:,}{ANSI_RESET} "
            f"({r2v_rate}) | Empty radar frames: "
            f"{empty_col}{ANSI_BOLD}{r2v['empty_radar_frames']}/{r2v['total_radar_frames']}{ANSI_RESET}"
        )


def evaluate_session(
    session_dir: str,
    vision_json_path: Optional[str] = None,
    output_dir: Optional[str] = None,
    max_gate_m: float = 3.5,
    max_range_m: Optional[float] = 50.0,
    anchor_mode: str = "contact_patch",
    association_mode: str = "nearest",
    exclude_classes: Optional[List[str]] = None,
    min_conf: float = 0.0,
    solve_pitch: bool = True,
) -> Dict[str, Any]:
    """
    Executes automated spatio-temporal benchmarking between vision tracks and radar tracks,
    plus optional session-wide ideal pitch back-calculation and re-benchmarking.
    """
    session_name = os.path.basename(os.path.normpath(session_dir))
    _emit_progress({"step": "load_mcap", "desc": f"[{session_name}] Loading radar tracks from MCAP...", "pct": 2})

    mcap_path = os.path.join(session_dir, f"{session_name}.mcap")
    if not os.path.isfile(mcap_path):
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

    norm_anchor = normalize_anchor_mode(anchor_mode)
    norm_assoc = normalize_association_mode(association_mode)
    excluded_set = {c.strip().lower() for c in (exclude_classes or []) if c.strip()}
    eff_max_range = max_range_m if (max_range_m is not None and max_range_m > 0) else None

    print(f"\n[RoadSense Evaluation] Benchmarking Vision vs. Radar")
    print(f"  Session:     {session_name}")
    print(f"  MCAP Source: {mcap_path}")
    print(f"  Vision JSON: {vision_json_path}")
    print(
        f"  Config:      gate={max_gate_m}m | max_range={eff_max_range or 'ALL'}m (all lateral) | "
        f"anchor={norm_anchor} | assoc={norm_assoc} | excl={sorted(excluded_set) or 'none'}"
    )

    radar_frames = load_radar_tracks_from_mcap(
        mcap_path,
        progress_cb=lambda n: _emit_progress({
            "step": "load_mcap",
            "desc": f"[{session_name}] Reading MCAP radar frames...",
            "current": n,
            "pct": min(10.0, 2.0 + (n / 10000.0)),
        }),
    )
    radar_ts = [rf["timestamp_ns"] for rf in radar_frames]

    _emit_progress({"step": "load_vision", "desc": f"[{session_name}] Loading vision tracks JSON...", "pct": 12})
    with open(vision_json_path, "r", encoding="utf-8") as f:
        vision_data = json.load(f)
    vision_frames = vision_data.get("frames", [])

    time_offset_ns = int(vision_data.get("time_offset_ns", 0))
    if time_offset_ns == 0:
        from tools.vision.pipeline.timeline_loader import TimelineLoader
        time_offset_ns = TimelineLoader.get_session_time_offset_ns(session_dir)

    print(f"  Loaded {len(radar_frames)} radar frames and {len(vision_frames)} vision frames.")

    associator = SpatialAssociator(
        max_distance_gate_m=max_gate_m,
        anchor_mode=norm_anchor,
        association_mode=norm_assoc,
    )

    # Pass 1: Configured calibration benchmark (15% -> 40% if solving pitch, else 15% -> 95%)
    p1_start, p1_span = 15.0, (25.0 if solve_pitch else 80.0)
    _emit_progress({
        "step": "vision_eval_pass1",
        "desc": f"[{session_name}] Pass 1: Benchmarking configured pitch...",
        "current": 0,
        "total": len(vision_frames),
        "pct": p1_start,
    })
    stats, scoped_matched = _run_benchmark_pass(
        vision_frames=vision_frames,
        radar_frames=radar_frames,
        radar_ts=radar_ts,
        time_offset_ns=time_offset_ns,
        associator=associator,
        max_range_m=eff_max_range,
        excluded_classes=excluded_set,
        min_conf=min_conf,
        progress_cb=lambda cur, tot, fps, eta: _emit_progress({
            "step": "vision_eval_pass1",
            "desc": f"[{session_name}] Pass 1: Evaluating configured pitch",
            "current": cur,
            "total": tot,
            "fps": round(fps, 1),
            "eta_s": round(eta, 1),
            "pct": round(p1_start + (cur / max(tot, 1)) * p1_span, 1),
        }),
    )

    # Pass 2: Session-wide pitch back-calculation & ideal-pitch re-benchmark
    if solve_pitch:
        _emit_progress({
            "step": "pitch_solve",
            "desc": f"[{session_name}] Preparing session pitch regression...",
            "pct": 40.0,
        })
        extrinsics, intrinsics = _resolve_camera_params(session_dir, vision_data)
        pitch_analysis = DynamicPitchSolver.solve_session_pitch(
            vision_frames=vision_frames,
            radar_frames=radar_frames,
            radar_ts=radar_ts,
            extrinsics=extrinsics,
            intrinsics=intrinsics,
            time_offset_ns=time_offset_ns,
            max_range_m=eff_max_range,
            exclude_classes=excluded_set,
            min_conf=min_conf,
            progress_cb=lambda desc_txt, frac, cur, tot, eta: _emit_progress({
                "step": "pitch_solve",
                "desc": f"[{session_name}] {desc_txt}",
                "current": cur,
                "total": tot,
                "eta_s": round(eta, 1) if eta is not None else None,
                "pct": round(40.0 + frac * 38.0, 1),
            }),
        )
        stats["pitch_calibration_analysis"] = pitch_analysis

        if pitch_analysis.get("success"):
            ideal_pitch = pitch_analysis["ideal_pitch_deg"]
            _emit_progress({
                "step": "reproject",
                "desc": f"[{session_name}] Re-projecting vision frames @ {ideal_pitch:.3f}°...",
                "pct": 79.0,
            })
            reproj_frames = DynamicPitchSolver.reproject_vision_frames(
                vision_frames=vision_frames,
                extrinsics=extrinsics,
                intrinsics=intrinsics,
                target_pitch_deg=ideal_pitch,
            )
            _emit_progress({
                "step": "vision_eval_pass2",
                "desc": f"[{session_name}] Pass 2: Re-benchmarking @ {ideal_pitch:.3f}°",
                "current": 0,
                "total": len(reproj_frames),
                "pct": 82.0,
            })
            ideal_stats, _ = _run_benchmark_pass(
                vision_frames=reproj_frames,
                radar_frames=radar_frames,
                radar_ts=radar_ts,
                time_offset_ns=time_offset_ns,
                associator=associator,
                max_range_m=eff_max_range,
                excluded_classes=excluded_set,
                min_conf=min_conf,
                progress_cb=lambda cur, tot, fps, eta: _emit_progress({
                    "step": "vision_eval_pass2",
                    "desc": f"[{session_name}] Pass 2: Re-benchmarking @ {ideal_pitch:.3f}°",
                    "current": cur,
                    "total": tot,
                    "fps": round(fps, 1),
                    "eta_s": round(eta, 1),
                    "pct": round(82.0 + (cur / max(tot, 1)) * 15.0, 1),
                }),
            )
            stats["ideal_pitch_benchmark"] = ideal_stats

    _emit_progress({"step": "write_report", "desc": f"[{session_name}] Writing benchmark report JSON...", "pct": 98.0})
    # Save report
    out_dir = output_dir or os.path.join("tools", "vision", "output", "reports")
    os.makedirs(out_dir, exist_ok=True)
    report_path = os.path.join(out_dir, f"{session_name}_benchmark_report.json")

    report_payload = {
        "session_name": session_name,
        "evaluation_timestamp": time.time(),
        "summary": stats,
        "matched_pairs": scoped_matched,
    }

    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report_payload, f, indent=2)

    # Terminal report
    print("\n" + f"{ANSI_CYAN}{ANSI_BOLD}{'=' * 72}{ANSI_RESET}")
    print(f"  {ANSI_CYAN}{ANSI_BOLD}BENCHMARK RESULTS: {ANSI_WHITE}{session_name}{ANSI_RESET}")
    print(f"{ANSI_CYAN}{ANSI_BOLD}{'=' * 72}{ANSI_RESET}")
    _print_summary_block(f"CONFIGURED PITCH BENCHMARK (0-{eff_max_range or 'inf'}m)", stats)

    pa = stats.get("pitch_calibration_analysis")
    if pa and pa.get("success"):
        adj = pa["pitch_adjustment_deg"]
        direction = "too steep (tilted down)" if adj < 0 else "too shallow (tilted up)"
        print("\n" + f"{ANSI_MAGENTA}{ANSI_BOLD}{'-' * 72}{ANSI_RESET}")
        print(f"  {ANSI_MAGENTA}{ANSI_BOLD}PITCH CALIBRATION REGRESSION (ALL CLASSES, SESSION-WIDE){ANSI_RESET}")
        print(f"  Configured Pitch:      {ANSI_YELLOW}{ANSI_BOLD}{pa['configured_pitch_deg']:.3f}°{ANSI_RESET}")
        print(f"  Back-Calculated Ideal: {ANSI_GREEN}{ANSI_BOLD}{pa['ideal_pitch_deg']:.3f}°{ANSI_RESET}")
        print(
            f"  Pitch Error:           {_color_pitch_err(abs(adj))} "
            f"({direction}; adjust {ANSI_CYAN}{ANSI_BOLD}{adj:+.3f}°{ANSI_RESET})"
        )
        cfg_d_c = _color_mae(pa["configured_median_dist_m"], f"{pa['configured_median_dist_m']}m")
        idl_d_c = _color_mae(pa["ideal_median_dist_m"], f"{pa['ideal_median_dist_m']}m")
        cfg_b_c = _color_bias(pa["configured_median_fwd_bias_m"], f"{pa['configured_median_fwd_bias_m']:+}m")
        idl_b_c = _color_bias(pa["ideal_median_fwd_bias_m"], f"{pa['ideal_median_fwd_bias_m']:+}m")
        print(f"  Mutual-NN Med Dist:    {cfg_d_c} -> {idl_d_c} | Med Fwd Bias: {cfg_b_c} -> {idl_b_c}")
        if "ideal_pitch_benchmark" in stats:
            _print_summary_block(
                f"IDEAL PITCH RE-BENCHMARK (@ {pa['ideal_pitch_deg']:.3f}°)",
                stats["ideal_pitch_benchmark"],
            )

    print(f"{ANSI_CYAN}{ANSI_BOLD}{'=' * 72}{ANSI_RESET}")
    print(f"  Report exported to: {ANSI_CYAN}{report_path}{ANSI_RESET}\n")
    _emit_progress({"step": "done", "desc": f"[{session_name}] Benchmark Complete", "pct": 100})
    return report_payload


def main():
    parser = argparse.ArgumentParser(description="Evaluate 3D vision tracks against radar tracks")
    parser.add_argument("session", help="Path to session directory (e.g. logs/session_20261120_112453)")
    parser.add_argument("-v", "--vision_json", default=None, help="Path to vision tracks JSON")
    parser.add_argument("-o", "--output_dir", default=None, help="Output directory for reports")
    parser.add_argument("-g", "--gate_m", type=float, default=3.5, help="Max distance gate in meters (default: 3.5)")
    parser.add_argument(
        "--max-range",
        type=float,
        default=50.0,
        help="Max forward distance in meters for primary summary (default: 50.0, 0 for unlimited; no lateral filtering)",
    )
    parser.add_argument(
        "--anchor",
        choices=["contact_patch", "center", "position_3d"],
        default="contact_patch",
        help="Vision 3D reference point (default: contact_patch)",
    )
    parser.add_argument(
        "--assoc-mode",
        choices=["nearest", "bipartite"],
        default="nearest",
        help="Matching strategy: nearest-radius existence or 1-to-1 Hungarian bipartite (default: nearest)",
    )
    parser.add_argument(
        "--exclude-classes",
        nargs="*",
        default=[],
        help="Optional classes to exclude (e.g. --exclude-classes person). Default evaluates all classes.",
    )
    parser.add_argument("--min-conf", type=float, default=0.0, help="Minimum YOLO detection confidence (default: 0.0)")
    parser.add_argument(
        "--solve-pitch",
        dest="solve_pitch",
        action="store_true",
        default=True,
        help="Back-calculate ideal session pitch and run ideal-pitch re-benchmark (default: enabled)",
    )
    parser.add_argument(
        "--no-solve-pitch",
        dest="solve_pitch",
        action="store_false",
        help="Skip session-wide pitch regression pass",
    )
    args = parser.parse_args()

    evaluate_session(
        session_dir=args.session,
        vision_json_path=args.vision_json,
        output_dir=args.output_dir,
        max_gate_m=args.gate_m,
        max_range_m=args.max_range,
        anchor_mode=args.anchor,
        association_mode=args.assoc_mode,
        exclude_classes=args.exclude_classes,
        min_conf=args.min_conf,
        solve_pitch=args.solve_pitch,
    )


if __name__ == "__main__":
    main()
