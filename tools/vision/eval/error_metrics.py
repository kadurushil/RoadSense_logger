"""
error_metrics.py
----------------
Computes statistical benchmark metrics comparing 3D geometric vision tracks
against mmWave radar tracks.
Preserves all legacy summary keys while providing true per-bin match rates,
extended range bands beyond 50m, per-class breakdowns, signed depth biases,
and bidirectional Radar -> Vision diagnostics.
"""

import math
from collections import Counter, defaultdict
from typing import List, Dict, Any, Optional


LEGACY_RANGE_BINS = (
    ("near_0_15m", 0.0, 15.0),
    ("mid_15_30m", 15.0, 30.0),
    ("far_above_30m", 30.0, float("inf")),
)

EXTENDED_RANGE_BINS = (
    ("0_15m", 0.0, 15.0),
    ("15_30m", 15.0, 30.0),
    ("30_50m", 30.0, 50.0),
    ("50_75m", 50.0, 75.0),
    ("75m_plus", 75.0, float("inf")),
)


class ErrorMetricsCalculator:
    """Calculates benchmark metrics across vision and radar track populations."""

    @staticmethod
    def _compute_error_block(pairs: List[Dict[str, Any]]) -> Dict[str, Any]:
        if not pairs:
            return {}
        n = len(pairs)
        fwd_errors = [p["delta_fwd_m"] for p in pairs]
        left_errors = [p["delta_left_m"] for p in pairs]
        dist_errors = [p["distance_error_m"] for p in pairs]

        fwd_mae = sum(abs(e) for e in fwd_errors) / n
        fwd_rmse = math.sqrt(sum(e ** 2 for e in fwd_errors) / n)
        fwd_bias = sum(fwd_errors) / n

        left_mae = sum(abs(e) for e in left_errors) / n
        left_rmse = math.sqrt(sum(e ** 2 for e in left_errors) / n)

        dist_mae = sum(dist_errors) / n
        dist_rmse = math.sqrt(sum(e ** 2 for e in dist_errors) / n)

        return {
            "longitudinal_fwd_mae_m": round(fwd_mae, 3),
            "longitudinal_fwd_rmse_m": round(fwd_rmse, 3),
            "mean_signed_fwd_bias_m": round(fwd_bias, 3),
            "lateral_left_mae_m": round(left_mae, 3),
            "lateral_left_rmse_m": round(left_rmse, 3),
            "total_euclidean_mae_m": round(dist_mae, 3),
            "total_euclidean_rmse_m": round(dist_rmse, 3),
        }

    @staticmethod
    def _compute_range_bin_stats(
        bin_defs,
        vision_records: List[Dict[str, Any]],
        matched_pairs: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """
        Bins both total vision detections (denominator) and matched pairs by forward range.
        Uses vision forward distance (v_fwd_m) when vision_records are available so
        match_rate_pct per bin is always bounded in [0, 100].
        Falls back to r_fwd_m when called in legacy 3-arg mode without vision_records.
        """
        stats = {}
        use_vision_bins = bool(vision_records)

        for b_name, lo, hi in bin_defs:
            if use_vision_bins:
                v_in_bin = [
                    r for r in vision_records
                    if lo < r["x_fwd_m"] <= hi or (lo == 0.0 and r["x_fwd_m"] <= 0.0 and hi == 15.0)
                ]
                # Normal positive-range binning: (lo, hi]
                v_in_bin = [r for r in vision_records if lo < r["x_fwd_m"] <= hi]
                b_pairs = [p for p in matched_pairs if lo < p["v_fwd_m"] <= hi]
                v_count = len(v_in_bin)
            else:
                b_pairs = [
                    p for p in matched_pairs
                    if (lo == 0.0 and p["r_fwd_m"] <= hi) or (lo > 0.0 and lo < p["r_fwd_m"] <= hi)
                ]
                v_count = None

            m_count = len(b_pairs)
            if m_count == 0:
                entry = {
                    "count": 0,
                    "fwd_mae_m": None,
                    "fwd_rmse_m": None,
                    "mean_signed_fwd_bias_m": None,
                    "left_mae_m": None,
                }
            else:
                b_fwd = [p["delta_fwd_m"] for p in b_pairs]
                b_left = [p["delta_left_m"] for p in b_pairs]
                entry = {
                    "count": m_count,
                    "fwd_mae_m": round(sum(abs(e) for e in b_fwd) / m_count, 3),
                    "fwd_rmse_m": round(math.sqrt(sum(e ** 2 for e in b_fwd) / m_count), 3),
                    "mean_signed_fwd_bias_m": round(sum(b_fwd) / m_count, 3),
                    "left_mae_m": round(sum(abs(e) for e in b_left) / m_count, 3),
                }

            if v_count is not None:
                entry["vision_count"] = v_count
                entry["match_rate_pct"] = round((m_count / max(v_count, 1)) * 100.0, 1) if v_count > 0 else 0.0

            stats[b_name] = entry

        return stats

    @staticmethod
    def _compute_by_class(
        vision_records: List[Dict[str, Any]],
        matched_pairs: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        cls_v_counts = Counter(r["class_name"] for r in vision_records)
        cls_pairs = defaultdict(list)
        for p in matched_pairs:
            cls_pairs[p.get("class_name") or "unknown"].append(p)

        all_classes = sorted(set(cls_v_counts.keys()) | set(cls_pairs.keys()))
        result = {}
        for cls in all_classes:
            v_cnt = cls_v_counts.get(cls, 0)
            pairs = cls_pairs.get(cls, [])
            m_cnt = len(pairs)
            rate = round((m_cnt / max(v_cnt, 1)) * 100.0, 1) if v_cnt > 0 else 0.0
            if m_cnt > 0:
                fwd = [p["delta_fwd_m"] for p in pairs]
                left = [p["delta_left_m"] for p in pairs]
                result[cls] = {
                    "vision_count": v_cnt,
                    "matched_count": m_cnt,
                    "match_rate_pct": rate,
                    "fwd_mae_m": round(sum(abs(e) for e in fwd) / m_cnt, 3),
                    "mean_signed_fwd_bias_m": round(sum(fwd) / m_cnt, 3),
                    "left_mae_m": round(sum(abs(e) for e in left) / m_cnt, 3),
                }
            else:
                result[cls] = {
                    "vision_count": v_cnt,
                    "matched_count": 0,
                    "match_rate_pct": 0.0,
                    "fwd_mae_m": None,
                    "mean_signed_fwd_bias_m": None,
                    "left_mae_m": None,
                }
        return result

    @classmethod
    def compute_summary_statistics(
        cls,
        matched_pairs: List[Dict[str, Any]],
        total_vision_tracks: int,
        total_radar_tracks: int,
        scoped_vision_records: Optional[List[Dict[str, Any]]] = None,
        all_vision_records: Optional[List[Dict[str, Any]]] = None,
        all_matched_pairs: Optional[List[Dict[str, Any]]] = None,
        unmatched_breakdown: Optional[Dict[str, int]] = None,
        radar_to_vision_stats: Optional[Dict[str, Any]] = None,
        eval_config: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """
        Computes aggregate errors, range-binned performance, per-class metrics,
        and bidirectional Radar -> Vision diagnostics while preserving legacy keys.
        """
        n_matched = len(matched_pairs)
        match_rate_pct = (n_matched / max(total_vision_tracks, 1)) * 100.0

        overall = cls._compute_error_block(matched_pairs)
        v_records = scoped_vision_records or []
        range_bins = cls._compute_range_bin_stats(LEGACY_RANGE_BINS, v_records, matched_pairs)

        summary: Dict[str, Any] = {
            "matched_count": n_matched,
            "total_vision_tracks": total_vision_tracks,
            "total_radar_tracks": total_radar_tracks,
            "match_rate_pct": round(match_rate_pct, 1),
            "overall": overall,
            "range_bins": range_bins,
        }

        if eval_config is not None:
            summary["eval_config"] = eval_config

        # Extended range bins across all ranges (explicitly exposes >50m falloff)
        full_v = all_vision_records if all_vision_records is not None else v_records
        full_m = all_matched_pairs if all_matched_pairs is not None else matched_pairs
        if full_v or full_m:
            summary["extended_range_bins"] = cls._compute_range_bin_stats(
                EXTENDED_RANGE_BINS, full_v, full_m
            )

        if all_vision_records is not None and all_matched_pairs is not None:
            tot_all = len(all_vision_records)
            m_all = len(all_matched_pairs)
            summary["all_ranges"] = {
                "matched_count": m_all,
                "total_vision_tracks": tot_all,
                "match_rate_pct": round((m_all / max(tot_all, 1)) * 100.0, 1) if tot_all > 0 else 0.0,
            }

        if v_records:
            summary["by_class"] = cls._compute_by_class(v_records, matched_pairs)

        if unmatched_breakdown is not None:
            summary["unmatched_breakdown"] = unmatched_breakdown

        if radar_to_vision_stats is not None:
            summary["radar_to_vision"] = radar_to_vision_stats

        return summary


if __name__ == "__main__":
    print("[ErrorMetricsCalculator Self-Test]")
    sample_matches = [
        {"v_fwd_m": 10.4, "r_fwd_m": 10.0, "delta_fwd_m": 0.4, "delta_left_m": -0.1, "distance_error_m": 0.41, "class_name": "car"},
        {"v_fwd_m": 20.8, "r_fwd_m": 22.0, "delta_fwd_m": -1.2, "delta_left_m": 0.2, "distance_error_m": 1.22, "class_name": "truck"},
    ]
    stats = ErrorMetricsCalculator.compute_summary_statistics(sample_matches, 2, 2)
    assert stats["matched_count"] == 2, "Must count 2 matches"
    assert stats["match_rate_pct"] == 100.0
    print(f"  Overall Forward MAE: {stats['overall']['longitudinal_fwd_mae_m']}m")
    print(f"  Match Rate: {stats['match_rate_pct']}%")
    print("  Status: PASSED")
