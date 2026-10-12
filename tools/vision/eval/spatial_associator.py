"""
spatial_associator.py
---------------------
Associates 3D geometric vision tracks with mmWave radar ground truth tracks.
Performs time-window synchronization and 2D Euclidean ground-plane bipartite matching
via the Hungarian algorithm (linear_sum_assignment) with distance gating.
"""

import math
from typing import List, Dict, Any, Tuple
import numpy as np
try:
    import lap
    HAS_LAP = True
except ImportError:
    HAS_LAP = False


class SpatialAssociator:
    """Associates vision detections and radar tracks in space and time."""

    def __init__(self, max_distance_gate_m: float = 3.5, max_time_gate_ms: float = 50.0):
        self.max_gate_m = max_distance_gate_m
        self.max_time_ms = max_time_gate_ms

    def _solve_assignment(self, cost_matrix: np.ndarray) -> List[Tuple[int, int]]:
        """Solves optimal bipartite assignment via lapjv or greedy fallback."""
        n_rows, n_cols = cost_matrix.shape
        if HAS_LAP:
            try:
                _, x, _ = lap.lapjv(cost_matrix, extend_cost=True)
                return [(i, x[i]) for i in range(n_rows) if x[i] >= 0]
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

        Args:
            vision_tracks: List of 3D vision track dictionaries.
            radar_tracks: List of 3D radar track dictionaries.
            time_delta_ms: Millisecond delta between vision and radar timestamps.

        Returns:
            Dict containing matched_pairs, unmatched_vision, and unmatched_radar.
        """
        if abs(time_delta_ms) > self.max_time_ms:
            return {
                "matched_pairs": [],
                "unmatched_vision": vision_tracks,
                "unmatched_radar": radar_tracks,
            }

        n_v = len(vision_tracks)
        n_r = len(radar_tracks)

        if n_v == 0 or n_r == 0:
            return {
                "matched_pairs": [],
                "unmatched_vision": vision_tracks,
                "unmatched_radar": radar_tracks,
            }

        # Build pairwise cost matrix
        cost_matrix = np.zeros((n_v, n_r), dtype=np.float32)
        for i, vt in enumerate(vision_tracks):
            v_pos = vt.get("position_3d", {})
            v_x = float(v_pos.get("x_fwd_m", 0.0))
            v_y = float(v_pos.get("y_left_m", 0.0))

            for j, rt in enumerate(radar_tracks):
                r_x = float(rt.get("x_fwd_m", 0.0))
                r_y = float(rt.get("y_left_m", 0.0))
                dist = math.hypot(v_x - r_x, v_y - r_y)
                cost_matrix[i, j] = dist

        # Linear assignment
        assignments = self._solve_assignment(cost_matrix)

        matched_pairs = []
        matched_v_indices = set()
        matched_r_indices = set()

        for v_idx, r_idx in assignments:
            dist = float(cost_matrix[v_idx, r_idx])
            if dist <= self.max_gate_m:
                vt = vision_tracks[v_idx]
                rt = radar_tracks[r_idx]
                v_pos = vt.get("position_3d", {})
                r_x = float(rt.get("x_fwd_m", 0.0))
                r_y = float(rt.get("y_left_m", 0.0))
                v_x = float(v_pos.get("x_fwd_m", 0.0))
                v_y = float(v_pos.get("y_left_m", 0.0))

                matched_pairs.append({
                    "vision_track_id": vt.get("track_id"),
                    "radar_track_id": rt.get("track_id"),
                    "class_name": vt.get("class_name"),
                    "v_fwd_m": round(v_x, 3),
                    "r_fwd_m": round(r_x, 3),
                    "v_left_m": round(v_y, 3),
                    "r_left_m": round(r_y, 3),
                    "delta_fwd_m": round(v_x - r_x, 3),
                    "delta_left_m": round(v_y - r_y, 3),
                    "distance_error_m": round(dist, 3),
                    "time_delta_ms": round(time_delta_ms, 2),
                })
                matched_v_indices.add(v_idx)
                matched_r_indices.add(r_idx)

        unmatched_vision = [vt for i, vt in enumerate(vision_tracks) if i not in matched_v_indices]
        unmatched_radar = [rt for j, rt in enumerate(radar_tracks) if j not in matched_r_indices]

        return {
            "matched_pairs": matched_pairs,
            "unmatched_vision": unmatched_vision,
            "unmatched_radar": unmatched_radar,
        }


if __name__ == "__main__":
    print("[SpatialAssociator Self-Test]")
    associator = SpatialAssociator(max_distance_gate_m=3.5)
    v_sample = [{"track_id": 1, "class_name": "car", "position_3d": {"x_fwd_m": 15.2, "y_left_m": -0.5}}]
    r_sample = [{"track_id": 4, "x_fwd_m": 15.0, "y_left_m": -0.6}]

    res = associator.associate_frame(v_sample, r_sample, time_delta_ms=5.0)
    assert len(res["matched_pairs"]) == 1, "Must match close tracks"
    match = res["matched_pairs"][0]
    print(f"  Matched: dist_error={match['distance_error_m']}m, delta_fwd={match['delta_fwd_m']}m")
    print("  Status: PASSED")
