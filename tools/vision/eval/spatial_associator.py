"""
spatial_associator.py
---------------------
Associates 3D geometric vision tracks with mmWave radar tracks.
Supports front tire contact-patch (default) or volumetric center anchors,
nearest-radius existence matching (default) or 1-to-1 Hungarian bipartite matching,
and bidirectional Vision <-> Radar diagnostics.
"""

import math
from typing import List, Dict, Any, Tuple
import numpy as np

try:
    import lap
    HAS_LAP = True
except ImportError:
    HAS_LAP = False


VALID_ANCHOR_MODES = ("contact_patch", "position_3d", "center")
VALID_ASSOC_MODES = ("nearest", "nearest_radius", "bipartite")


def normalize_anchor_mode(anchor_mode: str) -> str:
    mode = (anchor_mode or "contact_patch").strip().lower()
    if mode in ("center", "position_3d"):
        return "position_3d"
    if mode == "contact_patch":
        return "contact_patch"
    raise ValueError(f"Unsupported anchor_mode '{anchor_mode}'. Expected 'contact_patch' or 'position_3d'.")


def normalize_association_mode(association_mode: str) -> str:
    mode = (association_mode or "nearest").strip().lower()
    if mode in ("nearest", "nearest_radius"):
        return "nearest"
    if mode == "bipartite":
        return "bipartite"
    raise ValueError(f"Unsupported association_mode '{association_mode}'. Expected 'nearest' or 'bipartite'.")


def get_vision_anchor_xy(vt: Dict[str, Any], anchor_mode: str = "contact_patch") -> Tuple[float, float]:
    """
    Extracts (x_fwd_m, y_left_m) for a vision track using the requested anchor.
    Uses contact_patch by default (with fallback to position_3d if absent).
    """
    norm_mode = normalize_anchor_mode(anchor_mode)
    if norm_mode == "contact_patch":
        cp = vt.get("contact_patch")
        if isinstance(cp, dict) and "x_fwd_m" in cp and "y_left_m" in cp:
            return float(cp["x_fwd_m"]), float(cp["y_left_m"])
    pos = vt.get("position_3d", {})
    return float(pos.get("x_fwd_m", 0.0)), float(pos.get("y_left_m", 0.0))


class SpatialAssociator:
    """Associates vision detections and radar tracks in space and time."""

    def __init__(
        self,
        max_distance_gate_m: float = 3.5,
        max_time_gate_ms: float = 50.0,
        anchor_mode: str = "contact_patch",
        association_mode: str = "nearest",
    ):
        self.max_gate_m = float(max_distance_gate_m)
        self.max_time_ms = float(max_time_gate_ms)
        self.anchor_mode = normalize_anchor_mode(anchor_mode)
        self.association_mode = normalize_association_mode(association_mode)

    def _solve_assignment(self, cost_matrix: np.ndarray) -> List[Tuple[int, int]]:
        """Solves optimal 1-to-1 bipartite assignment via lapjv or greedy fallback."""
        n_rows, n_cols = cost_matrix.shape
        if HAS_LAP:
            try:
                _, x, _ = lap.lapjv(cost_matrix, extend_cost=True)
                return [(i, int(x[i])) for i in range(n_rows) if x[i] >= 0]
            except Exception:
                pass

        # Greedy fallback
        pairs = []
        used_rows = set()
        used_cols = set()
        flat_indices = np.argsort(cost_matrix, axis=None)
        for idx in flat_indices:
            r = int(idx // n_cols)
            c = int(idx % n_cols)
            if r not in used_rows and c not in used_cols:
                pairs.append((r, c))
                used_rows.add(r)
                used_cols.add(c)
                if len(used_rows) == n_rows or len(used_cols) == n_cols:
                    break
        return pairs

    def associate_frame(
        self,
        vision_tracks: List[Dict[str, Any]],
        radar_tracks: List[Dict[str, Any]],
        time_delta_ms: float = 0.0,
    ) -> Dict[str, Any]:
        """
        Pairs vision tracks with radar tracks for a single synchronized frame.

        Returns:
            Dict containing matched_pairs, unmatched_vision, unmatched_radar,
            matched_v_indices, unmatched_reasons, and radar_matched_indices.
        """
        if abs(time_delta_ms) > self.max_time_ms:
            return {
                "matched_pairs": [],
                "unmatched_vision": vision_tracks,
                "unmatched_radar": radar_tracks,
                "matched_v_indices": set(),
                "unmatched_reasons": ["time_gate_exceeded"] * len(vision_tracks),
                "radar_matched_indices": set(),
            }

        n_v = len(vision_tracks)
        n_r = len(radar_tracks)

        if n_v == 0 or n_r == 0:
            reasons = ["no_radar_tracks_in_frame"] * n_v if n_r == 0 else []
            return {
                "matched_pairs": [],
                "unmatched_vision": vision_tracks,
                "unmatched_radar": radar_tracks,
                "matched_v_indices": set(),
                "unmatched_reasons": reasons,
                "radar_matched_indices": set(),
            }

        # Build pairwise 2D ground Euclidean distance matrix
        v_xy = np.array(
            [get_vision_anchor_xy(vt, self.anchor_mode) for vt in vision_tracks],
            dtype=np.float64,
        )
        r_xy = np.array(
            [[float(rt.get("x_fwd_m", 0.0)), float(rt.get("y_left_m", 0.0))] for rt in radar_tracks],
            dtype=np.float64,
        )
        diff = v_xy[:, None, :] - r_xy[None, :, :]
        cost_matrix = np.hypot(diff[:, :, 0], diff[:, :, 1])

        matched_pairs = []
        matched_v_indices = set()
        matched_r_indices = set()

        if self.association_mode == "nearest":
            best_r_per_v = np.argmin(cost_matrix, axis=1)
            for v_idx in range(n_v):
                r_idx = int(best_r_per_v[v_idx])
                dist = float(cost_matrix[v_idx, r_idx])
                if dist <= self.max_gate_m:
                    matched_pairs.append(
                        self._build_pair_record(
                            vision_tracks[v_idx],
                            radar_tracks[r_idx],
                            float(v_xy[v_idx, 0]),
                            float(v_xy[v_idx, 1]),
                            float(r_xy[r_idx, 0]),
                            float(r_xy[r_idx, 1]),
                            dist,
                            time_delta_ms,
                        )
                    )
                    matched_v_indices.add(v_idx)
                    matched_r_indices.add(r_idx)
        else:
            assignments = self._solve_assignment(cost_matrix.astype(np.float32))
            for v_idx, r_idx in assignments:
                dist = float(cost_matrix[v_idx, r_idx])
                if dist <= self.max_gate_m:
                    matched_pairs.append(
                        self._build_pair_record(
                            vision_tracks[v_idx],
                            radar_tracks[r_idx],
                            float(v_xy[v_idx, 0]),
                            float(v_xy[v_idx, 1]),
                            float(r_xy[r_idx, 0]),
                            float(r_xy[r_idx, 1]),
                            dist,
                            time_delta_ms,
                        )
                    )
                    matched_v_indices.add(v_idx)
                    matched_r_indices.add(r_idx)

        # Classify unmatched vision tracks by nearest radar distance
        min_r_dist_per_v = np.min(cost_matrix, axis=1)
        unmatched_vision = []
        unmatched_reasons = []
        for i, vt in enumerate(vision_tracks):
            if i not in matched_v_indices:
                unmatched_vision.append(vt)
                md = float(min_r_dist_per_v[i])
                if md <= self.max_gate_m:
                    unmatched_reasons.append("lost_in_bipartite_1to1")
                elif md <= 6.0:
                    unmatched_reasons.append("near_miss_gate_to_6m")
                elif md <= 10.0:
                    unmatched_reasons.append("miss_6_to_10m")
                else:
                    unmatched_reasons.append("far_gt_10m")

        # Reverse Radar -> Vision existence hits within max_gate_m
        min_v_dist_per_r = np.min(cost_matrix, axis=0)
        radar_matched_indices = {
            j for j in range(n_r) if float(min_v_dist_per_r[j]) <= self.max_gate_m
        }
        unmatched_radar = [rt for j, rt in enumerate(radar_tracks) if j not in matched_r_indices]

        return {
            "matched_pairs": matched_pairs,
            "unmatched_vision": unmatched_vision,
            "unmatched_radar": unmatched_radar,
            "matched_v_indices": matched_v_indices,
            "unmatched_reasons": unmatched_reasons,
            "radar_matched_indices": radar_matched_indices,
        }

    def _build_pair_record(
        self,
        vt: Dict[str, Any],
        rt: Dict[str, Any],
        v_x: float,
        v_y: float,
        r_x: float,
        r_y: float,
        dist: float,
        time_delta_ms: float,
    ) -> Dict[str, Any]:
        return {
            "vision_track_id": vt.get("track_id"),
            "radar_track_id": rt.get("track_id"),
            "class_name": vt.get("class_name"),
            "anchor_mode": self.anchor_mode,
            "v_fwd_m": round(v_x, 3),
            "r_fwd_m": round(r_x, 3),
            "v_left_m": round(v_y, 3),
            "r_left_m": round(r_y, 3),
            "delta_fwd_m": round(v_x - r_x, 3),
            "delta_left_m": round(v_y - r_y, 3),
            "distance_error_m": round(dist, 3),
            "time_delta_ms": round(time_delta_ms, 2),
        }


if __name__ == "__main__":
    print("[SpatialAssociator Self-Test]")
    associator = SpatialAssociator(max_distance_gate_m=3.5)
    v_sample = [
        {
            "track_id": 1,
            "class_name": "truck",
            "contact_patch": {"x_fwd_m": 10.2, "y_left_m": -0.5},
            "position_3d": {"x_fwd_m": 14.45, "y_left_m": -0.5},
        }
    ]
    r_sample = [{"track_id": 4, "x_fwd_m": 10.0, "y_left_m": -0.6}]

    res = associator.associate_frame(v_sample, r_sample, time_delta_ms=5.0)
    assert len(res["matched_pairs"]) == 1, "Must match close truck via contact_patch"
    match = res["matched_pairs"][0]
    print(f"  Matched: dist_error={match['distance_error_m']}m, delta_fwd={match['delta_fwd_m']}m")
    print("  Status: PASSED")
