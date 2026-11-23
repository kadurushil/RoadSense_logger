"""
dynamic_pitch_solver.py
-----------------------
Session-wide camera pitch back-calculation solver and re-projection utility.
Uses saved 2D bounding box tire-contact pixels (bbox_2d) and camera extrinsics/intrinsics
to recover the optimal downward pitch angle (theta*) that minimizes Huber-weighted
spatial residual and range-dependent longitudinal bias against mmWave radar tracks,
while guarding against clutter-collapse into dense near-range radar clutter.
"""

import bisect
import math
import time
from typing import Dict, Any, List, Optional, Tuple, Set, Callable
import numpy as np

from tools.vision.geometry.camera_extrinsics import CameraExtrinsics
from tools.vision.geometry.ground_plane_projector import GroundPlaneProjector, PinholeIntrinsics


def _huber_loss(residuals: np.ndarray, delta: float = 1.5) -> np.ndarray:
    abs_r = np.abs(residuals)
    quad = abs_r <= delta
    return np.where(quad, 0.5 * (abs_r ** 2), delta * (abs_r - 0.5 * delta))


def _build_cam_to_base_rotation(
    pitch_deg: float, yaw_deg: float, roll_deg: float, video_rotation_deg: int = 0
) -> np.ndarray:
    """
    Replicates GroundPlaneProjector rotation matrix construction in a lightweight helper.
    Returns R_cam_to_base (3x3).
    """
    pitch = math.radians(pitch_deg)
    yaw = math.radians(yaw_deg)
    roll = math.radians((roll_deg + video_rotation_deg) % 360.0)

    r0 = np.array([[0.0, -1.0, 0.0], [0.0, 0.0, -1.0], [1.0, 0.0, 0.0]], dtype=np.float64)
    ry = np.array(
        [[math.cos(yaw), 0.0, -math.sin(yaw)], [0.0, 1.0, 0.0], [math.sin(yaw), 0.0, math.cos(yaw)]],
        dtype=np.float64,
    )
    rx = np.array(
        [[1.0, 0.0, 0.0], [0.0, math.cos(pitch), -math.sin(pitch)], [0.0, math.sin(pitch), math.cos(pitch)]],
        dtype=np.float64,
    )
    rz = np.array(
        [[math.cos(roll), math.sin(roll), 0.0], [-math.sin(roll), math.cos(roll), 0.0], [0.0, 0.0, 1.0]],
        dtype=np.float64,
    )
    return (rz @ rx @ ry @ r0).T


def _project_uv_batch(
    uv: np.ndarray,
    intrinsics: PinholeIntrinsics,
    r_cam_to_base: np.ndarray,
    cam_pos_base: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Vectorized ground-plane projection for Nx2 (u_cx, v_bottom) pixels.
    Returns (ground_xy [M, 2], valid_mask [N]).
    """
    ray_x = (uv[:, 0] - intrinsics.cx) / intrinsics.fx
    ray_y = (uv[:, 1] - intrinsics.cy) / intrinsics.fy
    rays_cam = np.stack([ray_x, ray_y, np.ones(len(uv), dtype=np.float64)], axis=1)
    rays_base = rays_cam @ r_cam_to_base.T

    valid = rays_base[:, 2] < -1e-4
    if not np.any(valid):
        return np.zeros((0, 2), dtype=np.float64), valid

    rv = rays_base[valid]
    s = -cam_pos_base[2] / rv[:, 2]
    pos_mask = s > 0.0
    if not np.any(pos_mask):
        full_valid = np.zeros(len(uv), dtype=bool)
        return np.zeros((0, 2), dtype=np.float64), full_valid

    pts = cam_pos_base + s[:, None] * rv
    in_front = (s > 0.0) & (pts[:, 0] > 0.0)

    full_valid = np.zeros(len(uv), dtype=bool)
    valid_indices = np.where(valid)[0][in_front]
    full_valid[valid_indices] = True
    return pts[in_front, :2], full_valid


def _mutual_nearest_residuals(
    v_xy: np.ndarray,
    r_xy: np.ndarray,
    solve_gate_m: float,
    min_radar_fwd_m: float,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Finds mutual nearest-neighbor pairs between vision and radar points within solve_gate_m.
    Mutual-NN prevents multiple far vision detections from collapsing onto a single
    close-range radar clutter track.
    Returns (euclidean_dists, signed_fwd_deltas) for matched pairs with r_fwd >= min_radar_fwd_m.
    """
    if len(v_xy) == 0 or len(r_xy) == 0:
        return np.zeros(0, dtype=np.float64), np.zeros(0, dtype=np.float64)

    diff = v_xy[:, None, :] - r_xy[None, :, :]
    dists = np.hypot(diff[:, :, 0], diff[:, :, 1])

    best_r_for_v = np.argmin(dists, axis=1)
    best_v_for_r = np.argmin(dists, axis=0)

    v_idx = np.arange(len(v_xy))
    is_mutual = best_v_for_r[best_r_for_v] == v_idx
    min_d = dists[v_idx, best_r_for_v]
    r_fwd = r_xy[best_r_for_v, 0]

    keep = is_mutual & (min_d <= solve_gate_m) & (r_fwd >= min_radar_fwd_m)
    if not np.any(keep):
        return np.zeros(0, dtype=np.float64), np.zeros(0, dtype=np.float64)

    matched_d = min_d[keep]
    matched_df = v_xy[keep, 0] - r_fwd[keep]
    return matched_d, matched_df


class DynamicPitchSolver:
    """Back-calculates optimal camera downward pitch angle across a full session."""

    @staticmethod
    def solve_session_pitch(
        vision_frames: List[Dict[str, Any]],
        radar_frames: List[Dict[str, Any]],
        radar_ts: List[int],
        extrinsics: CameraExtrinsics,
        intrinsics: PinholeIntrinsics,
        time_offset_ns: int = 0,
        max_range_m: Optional[float] = 50.0,
        max_time_gate_ms: float = 50.0,
        solve_gate_m: float = 4.5,
        search_half_window_deg: float = 3.5,
        exclude_classes: Optional[Set[str]] = None,
        min_conf: float = 0.0,
        min_radar_fwd_m: float = 4.0,
        progress_cb: Optional[Callable[[str, float, Optional[int], Optional[int], Optional[float]], None]] = None,
    ) -> Dict[str, Any]:
        """
        Searches over pitch_deg in [configured - window, configured + window] using
        coarse grid + fine golden-section refinement to find the angle that minimizes
        Huber-loss 2D residual and median longitudinal bias across mutual-NN pairs.
        """
        excluded = {c.lower() for c in (exclude_classes or set())}
        cam_pos_base = np.array(
            [-extrinsics.setback_m, -extrinsics.lateral_offset_m, extrinsics.camera_height_m],
            dtype=np.float64,
        )
        yaw_deg = getattr(extrinsics, "camera_yaw_deg", extrinsics.yaw_deg)

        # Pre-extract synchronized (uv, r_xy) frame pairs once
        paired_samples: List[Tuple[np.ndarray, np.ndarray]] = []
        for vf in vision_frames:
            if "timestamp_utc_ns" in vf and vf["timestamp_utc_ns"] > 1e16:
                v_ts = int(vf["timestamp_utc_ns"])
            else:
                raw_ts = int(vf.get("timestamp_ns", 0))
                v_ts = raw_ts if raw_ts > 1e16 else (raw_ts + time_offset_ns)

            if not radar_ts:
                continue
            idx = bisect.bisect_left(radar_ts, v_ts)
            cands = [c for c in (idx - 1, idx) if 0 <= c < len(radar_ts)]
            best_c = min(cands, key=lambda c: abs(v_ts - radar_ts[c]))
            if abs(v_ts - radar_ts[best_c]) / 1e6 > max_time_gate_ms:
                continue

            r_tracks = radar_frames[best_c]["tracks"]
            if not r_tracks:
                continue

            uv_list = []
            for vt in vf.get("tracks", []):
                cls_name = str(vt.get("class_name", "")).lower()
                if cls_name in excluded:
                    continue
                if float(vt.get("confidence", 1.0)) < min_conf:
                    continue
                bb = vt.get("bbox_2d")
                if isinstance(bb, (list, tuple)) and len(bb) >= 4:
                    u_cx = (float(bb[0]) + float(bb[2])) * 0.5
                    v_bot = float(bb[3])
                    uv_list.append((u_cx, v_bot))

            if uv_list:
                r_xy = np.array(
                    [[float(rt["x_fwd_m"]), float(rt["y_left_m"])] for rt in r_tracks],
                    dtype=np.float64,
                )
                paired_samples.append((np.array(uv_list, dtype=np.float64), r_xy))

        if not paired_samples:
            return {
                "success": False,
                "reason": "No synchronized frames with bbox_2d and non-empty radar tracks found",
                "configured_pitch_deg": round(extrinsics.pitch_deg, 4),
                "ideal_pitch_deg": round(extrinsics.pitch_deg, 4),
                "pitch_error_deg": 0.0,
                "mutual_pair_count": 0,
            }

        # Subsample evenly for search speed while keeping final evaluation on full samples
        coarse_stride = max(1, len(paired_samples) // 6000)
        coarse_samples = paired_samples[::coarse_stride]
        fine_stride = max(1, len(paired_samples) // 12000)
        fine_samples = paired_samples[::fine_stride]

        def evaluate_pitch(pitch_deg: float, samples: List[Tuple[np.ndarray, np.ndarray]]) -> Tuple[float, Dict[str, float]]:
            r_mat = _build_cam_to_base_rotation(pitch_deg, yaw_deg, extrinsics.roll_deg, 0)
            all_d = []
            all_df = []
            for uv, r_xy in samples:
                pts_xy, _ = _project_uv_batch(uv, intrinsics, r_mat, cam_pos_base)
                if max_range_m is not None and max_range_m > 0:
                    pts_xy = pts_xy[pts_xy[:, 0] <= max_range_m]
                if len(pts_xy) == 0:
                    continue
                md, mdf = _mutual_nearest_residuals(pts_xy, r_xy, solve_gate_m, min_radar_fwd_m)
                if len(md) > 0:
                    all_d.append(md)
                    all_df.append(mdf)

            if not all_d:
                return 1e6, {"n": 0, "med_dist": 99.0, "med_bias": 99.0, "huber": 99.0}

            d_cat = np.concatenate(all_d)
            df_cat = np.concatenate(all_df)
            n = len(d_cat)
            if n < 20:
                return 1e5 - n, {"n": n, "med_dist": float(np.median(d_cat)), "med_bias": float(np.median(df_cat)), "huber": 99.0}

            huber_mean = float(np.mean(_huber_loss(d_cat, delta=1.5)))
            med_dist = float(np.median(d_cat))
            med_bias = float(np.median(df_cat))
            # Joint objective: minimize spatial Huber residual + longitudinal bias magnitude,
            # with a mild log support reward to break flat ties without encouraging clutter collapse.
            score = huber_mean + 0.50 * abs(med_bias) - 0.03 * math.log(max(n, 1))
            return score, {
                "n": int(n),
                "med_dist": round(med_dist, 3),
                "med_bias": round(med_bias, 3),
                "huber": round(huber_mean, 4),
            }

        cfg_pitch = float(extrinsics.pitch_deg)
        lo_bound = max(0.2, cfg_pitch - search_half_window_deg)
        hi_bound = cfg_pitch + search_half_window_deg

        # 1. Coarse grid search (0.25 deg step)
        grid = np.arange(lo_bound, hi_bound + 1e-6, 0.25)
        n_coarse = len(grid)
        n_fine_evals = 14
        n_final_evals = 2
        total_evals = n_coarse + n_fine_evals + n_final_evals
        t_solve_start = time.time()
        eval_done = 0

        def _notify(desc_txt: str) -> None:
            if progress_cb is not None:
                elapsed = max(time.time() - t_solve_start, 0.001)
                rate = eval_done / elapsed
                eta_s = (total_evals - eval_done) / max(rate, 0.05)
                progress_cb(desc_txt, eval_done / total_evals, eval_done, total_evals, eta_s)

        grid_scores = []
        for p in grid:
            sc, meta = evaluate_pitch(float(p), coarse_samples)
            grid_scores.append((sc, float(p), meta))
            eval_done += 1
            _notify(f"Pitch coarse scan @ {float(p):.2f}°")

        best_sc, best_p, _ = min(grid_scores, key=lambda x: x[0])

        # 2. Fine Golden-section search around best_p +/- 0.5 deg
        a = max(lo_bound, best_p - 0.5)
        b = min(hi_bound, best_p + 0.5)
        phi = (math.sqrt(5.0) - 1.0) / 2.0
        c = b - phi * (b - a)
        d = a + phi * (b - a)
        fc, _ = evaluate_pitch(c, fine_samples)
        eval_done += 1
        _notify(f"Pitch fine refinement @ {c:.3f}°")
        fd, _ = evaluate_pitch(d, fine_samples)
        eval_done += 1
        _notify(f"Pitch fine refinement @ {d:.3f}°")

        for _ in range(12):
            if fc < fd:
                b = d
                d = c
                fd = fc
                c = b - phi * (b - a)
                fc, _ = evaluate_pitch(c, fine_samples)
                eval_done += 1
                _notify(f"Pitch fine refinement @ {c:.3f}°")
            else:
                a = c
                c = d
                fc = fd
                d = a + phi * (b - a)
                fd, _ = evaluate_pitch(d, fine_samples)
                eval_done += 1
                _notify(f"Pitch fine refinement @ {d:.3f}°")

        ideal_pitch = (a + b) * 0.5
        _, cfg_meta = evaluate_pitch(cfg_pitch, paired_samples)
        eval_done += 1
        _notify(f"Scoring configured pitch {cfg_pitch:.3f}°")
        _, ideal_meta = evaluate_pitch(ideal_pitch, paired_samples)
        eval_done += 1
        _notify(f"Scoring ideal pitch {ideal_pitch:.3f}°")

        # Positive pitch_error_deg means configured pitch was too large (steep) vs ideal, or vice versa:
        # delta = configured - ideal, and ideal - configured shows adjustment needed.
        adj_deg = ideal_pitch - cfg_pitch
        return {
            "success": True,
            "configured_pitch_deg": round(cfg_pitch, 4),
            "ideal_pitch_deg": round(ideal_pitch, 4),
            "pitch_adjustment_deg": round(adj_deg, 4),
            "pitch_error_abs_deg": round(abs(adj_deg), 4),
            "evaluated_frames": len(paired_samples),
            "configured_mutual_pairs": cfg_meta["n"],
            "configured_median_dist_m": cfg_meta["med_dist"],
            "configured_median_fwd_bias_m": cfg_meta["med_bias"],
            "ideal_mutual_pairs": ideal_meta["n"],
            "ideal_median_dist_m": ideal_meta["med_dist"],
            "ideal_median_fwd_bias_m": ideal_meta["med_bias"],
        }

    @staticmethod
    def reproject_vision_frames(
        vision_frames: List[Dict[str, Any]],
        extrinsics: CameraExtrinsics,
        intrinsics: PinholeIntrinsics,
        target_pitch_deg: float,
    ) -> List[Dict[str, Any]]:
        """
        Creates a lightweight re-projected copy of vision_frames using target_pitch_deg.
        Updates contact_patch (and shifts position_3d by the same delta to preserve box size).
        Tracks that project above the horizon at target_pitch_deg are omitted cleanly.
        """
        new_ext = CameraExtrinsics(
            camera_height_m=extrinsics.camera_height_m,
            pitch_deg=float(target_pitch_deg),
            yaw_deg=extrinsics.yaw_deg,
            roll_deg=extrinsics.roll_deg,
            setback_m=extrinsics.setback_m,
            lateral_offset_m=extrinsics.lateral_offset_m,
            camera_yaw_deg=extrinsics.camera_yaw_deg,
            radar_yaw_deg=extrinsics.radar_yaw_deg,
        )
        projector = GroundPlaneProjector(new_ext, intrinsics, video_rotation_deg=0)

        reprojected_frames = []
        for vf in vision_frames:
            new_tracks = []
            for vt in vf.get("tracks", []):
                bb = vt.get("bbox_2d")
                if not (isinstance(bb, (list, tuple)) and len(bb) >= 4):
                    new_tracks.append(vt)
                    continue

                u_cx = (float(bb[0]) + float(bb[2])) * 0.5
                v_bot = float(bb[3])
                gp = projector.project_bottom_center_to_ground(u_cx, v_bot)
                if not gp:
                    continue

                old_cp = vt.get("contact_patch") or vt.get("position_3d", {})
                old_pos = vt.get("position_3d", {})
                dx_box = float(old_pos.get("x_fwd_m", gp["x_fwd_m"])) - float(old_cp.get("x_fwd_m", gp["x_fwd_m"]))
                dy_box = float(old_pos.get("y_left_m", gp["y_left_m"])) - float(old_cp.get("y_left_m", gp["y_left_m"]))

                vt_new = dict(vt)
                vt_new["range_m"] = gp["range_m"]
                vt_new["contact_patch"] = {
                    "x_fwd_m": gp["x_fwd_m"],
                    "y_left_m": gp["y_left_m"],
                    "z_up_m": 0.0,
                }
                vt_new["position_3d"] = {
                    "x_fwd_m": round(gp["x_fwd_m"] + dx_box, 3),
                    "y_left_m": round(gp["y_left_m"] + dy_box, 3),
                    "z_up_m": float(old_pos.get("z_up_m", 0.75)),
                }
                new_tracks.append(vt_new)

            vf_new = dict(vf)
            vf_new["tracks"] = new_tracks
            vf_new["track_count"] = len(new_tracks)
            reprojected_frames.append(vf_new)

        return reprojected_frames
