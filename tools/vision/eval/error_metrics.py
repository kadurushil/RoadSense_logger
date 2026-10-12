"""
error_metrics.py
----------------
Computes statistical benchmark metrics comparing 3D geometric vision tracks
against mmWave radar ground truth tracks.
Calculates MAE, RMSE, lateral error, range-binned distributions, and match rates.
"""

import math
from typing import List, Dict, Any


class ErrorMetricsCalculator:
    """Calculates benchmark metrics across matched track pairs."""

    @staticmethod
    def compute_summary_statistics(
        matched_pairs: List[Dict[str, Any]],
        total_vision_tracks: int,
        total_radar_tracks: int,
    ) -> Dict[str, Any]:
        """
        Computes aggregate errors and range-binned performance statistics.
        """
        n_matched = len(matched_pairs)
        match_rate_pct = (n_matched / max(total_vision_tracks, 1)) * 100.0

        if n_matched == 0:
            return {
                "matched_count": 0,
                "total_vision_tracks": total_vision_tracks,
                "total_radar_tracks": total_radar_tracks,
                "match_rate_pct": 0.0,
                "overall": {},
                "range_bins": {},
            }

        # Overall errors
        fwd_errors = [p["delta_fwd_m"] for p in matched_pairs]
        abs_fwd_errors = [abs(e) for e in fwd_errors]
        left_errors = [p["delta_left_m"] for p in matched_pairs]
        abs_left_errors = [abs(e) for e in left_errors]
        dist_errors = [p["distance_error_m"] for p in matched_pairs]

        fwd_mae = sum(abs_fwd_errors) / n_matched
        fwd_rmse = math.sqrt(sum(e ** 2 for e in fwd_errors) / n_matched)

        left_mae = sum(abs_left_errors) / n_matched
        left_rmse = math.sqrt(sum(e ** 2 for e in left_errors) / n_matched)

        dist_mae = sum(dist_errors) / n_matched
        dist_rmse = math.sqrt(sum(e ** 2 for e in dist_errors) / n_matched)

        # Range-binned breakdown
        # Near (0-15m), Mid (15-30m), Far (>30m) based on radar ground truth forward distance
        bins = {
            "near_0_15m": [p for p in matched_pairs if p["r_fwd_m"] <= 15.0],
            "mid_15_30m": [p for p in matched_pairs if 15.0 < p["r_fwd_m"] <= 30.0],
            "far_above_30m": [p for p in matched_pairs if p["r_fwd_m"] > 30.0],
        }

        binned_stats = {}
        for b_name, b_pairs in bins.items():
            if not b_pairs:
                binned_stats[b_name] = {"count": 0, "fwd_mae_m": None, "fwd_rmse_m": None}
                continue
            b_fwd = [p["delta_fwd_m"] for p in b_pairs]
            b_mae = sum(abs(e) for e in b_fwd) / len(b_pairs)
            b_rmse = math.sqrt(sum(e ** 2 for e in b_fwd) / len(b_pairs))
            binned_stats[b_name] = {
                "count": len(b_pairs),
                "fwd_mae_m": round(b_mae, 3),
                "fwd_rmse_m": round(b_rmse, 3),
            }

        return {
            "matched_count": n_matched,
            "total_vision_tracks": total_vision_tracks,
            "total_radar_tracks": total_radar_tracks,
            "match_rate_pct": round(match_rate_pct, 1),
            "overall": {
                "longitudinal_fwd_mae_m": round(fwd_mae, 3),
                "longitudinal_fwd_rmse_m": round(fwd_rmse, 3),
                "lateral_left_mae_m": round(left_mae, 3),
                "lateral_left_rmse_m": round(left_rmse, 3),
                "total_euclidean_mae_m": round(dist_mae, 3),
                "total_euclidean_rmse_m": round(dist_rmse, 3),
            },
            "range_bins": binned_stats,
        }


if __name__ == "__main__":
    print("[ErrorMetricsCalculator Self-Test]")
    sample_matches = [
        {"r_fwd_m": 10.0, "delta_fwd_m": 0.4, "delta_left_m": -0.1, "distance_error_m": 0.41},
        {"r_fwd_m": 22.0, "delta_fwd_m": -1.2, "delta_left_m": 0.2, "distance_error_m": 1.22},
    ]
    stats = ErrorMetricsCalculator.compute_summary_statistics(sample_matches, 2, 2)
    assert stats["matched_count"] == 2, "Must count 2 matches"
    print(f"  Overall Forward MAE: {stats['overall']['longitudinal_fwd_mae_m']}m")
    print(f"  Match Rate: {stats['match_rate_pct']}%")
    print("  Status: PASSED")
