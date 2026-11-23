import math
import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.vision.eval.spatial_associator import SpatialAssociator
from tools.vision.eval.error_metrics import ErrorMetricsCalculator
from tools.vision.eval.evaluate_vision_vs_radar import parse_radar_label, _run_benchmark_pass
from tools.vision.geometry.camera_extrinsics import CameraExtrinsics
from tools.vision.geometry.ground_plane_projector import GroundPlaneProjector, PinholeIntrinsics
from tools.vision.geometry.dynamic_pitch_solver import DynamicPitchSolver


class TestVisionEvalPipeline(unittest.TestCase):
    def test_contact_patch_vs_center_anchor(self):
        v_tracks = [
            {
                "track_id": 7,
                "class_name": "truck",
                "contact_patch": {"x_fwd_m": 10.2, "y_left_m": -0.4},
                "position_3d": {"x_fwd_m": 14.45, "y_left_m": -0.4},
            }
        ]
        r_tracks = [{"track_id": 12, "x_fwd_m": 10.0, "y_left_m": -0.5}]

        assoc_cp = SpatialAssociator(max_distance_gate_m=3.5, anchor_mode="contact_patch", association_mode="nearest")
        res_cp = assoc_cp.associate_frame(v_tracks, r_tracks, time_delta_ms=4.0)
        self.assertEqual(len(res_cp["matched_pairs"]), 1)
        self.assertAlmostEqual(res_cp["matched_pairs"][0]["delta_fwd_m"], 0.2, places=3)

        assoc_ctr = SpatialAssociator(max_distance_gate_m=3.5, anchor_mode="position_3d", association_mode="nearest")
        res_ctr = assoc_ctr.associate_frame(v_tracks, r_tracks, time_delta_ms=4.0)
        self.assertEqual(len(res_ctr["matched_pairs"]), 0)

    def test_nearest_radius_vs_bipartite(self):
        v_tracks = [
            {"track_id": 1, "class_name": "person", "contact_patch": {"x_fwd_m": 12.0, "y_left_m": 0.0}},
            {"track_id": 2, "class_name": "motorcycle", "contact_patch": {"x_fwd_m": 12.6, "y_left_m": 0.4}},
        ]
        r_tracks = [{"track_id": 99, "x_fwd_m": 12.2, "y_left_m": 0.1}]

        assoc_nn = SpatialAssociator(max_distance_gate_m=3.5, anchor_mode="contact_patch", association_mode="nearest")
        res_nn = assoc_nn.associate_frame(v_tracks, r_tracks, time_delta_ms=2.0)
        self.assertEqual(len(res_nn["matched_pairs"]), 2)
        self.assertEqual(res_nn["radar_matched_indices"], {0})

        assoc_bip = SpatialAssociator(max_distance_gate_m=3.5, anchor_mode="contact_patch", association_mode="bipartite")
        res_bip = assoc_bip.associate_frame(v_tracks, r_tracks, time_delta_ms=2.0)
        self.assertEqual(len(res_bip["matched_pairs"]), 1)
        self.assertEqual(res_bip["unmatched_reasons"], ["lost_in_bipartite_1to1"])

    def test_no_lateral_filtering_and_max_range_cutoff(self):
        base_ts = 1_795_000_000_000_000_000
        vision_frames = [
            {
                "timestamp_utc_ns": base_ts,
                "tracks": [
                    # Wide lateral target within 50m -> MUST be included (zero lateral filtering)
                    {"track_id": 1, "class_name": "car", "confidence": 0.8, "contact_patch": {"x_fwd_m": 20.0, "y_left_m": 12.5}},
                    # Beyond 50m -> excluded from 0-50m top-level scope, tracked in extended_range_bins & all_ranges
                    {"track_id": 2, "class_name": "car", "confidence": 0.8, "contact_patch": {"x_fwd_m": 62.0, "y_left_m": 0.2}},
                ],
            }
        ]
        radar_frames = [
            {
                "timestamp_ns": base_ts,
                "tracks": [
                    {"track_id": 10, "x_fwd_m": 20.5, "y_left_m": 12.2},
                ],
            }
        ]
        assoc = SpatialAssociator(max_distance_gate_m=3.5, anchor_mode="contact_patch", association_mode="nearest")
        summary, scoped_pairs = _run_benchmark_pass(
            vision_frames=vision_frames,
            radar_frames=radar_frames,
            radar_ts=[base_ts],
            time_offset_ns=0,
            associator=assoc,
            max_range_m=50.0,
            excluded_classes=set(),
            min_conf=0.0,
        )

        # Primary 0-50m scope retains wide lateral target and excludes 62m target
        self.assertEqual(summary["total_vision_tracks"], 1)
        self.assertEqual(summary["matched_count"], 1)
        self.assertEqual(summary["match_rate_pct"], 100.0)
        self.assertEqual(len(scoped_pairs), 1)

        # Unbounded all_ranges & extended_range_bins track the 62m detection
        self.assertEqual(summary["all_ranges"]["total_vision_tracks"], 2)
        self.assertEqual(summary["all_ranges"]["match_rate_pct"], 50.0)
        self.assertEqual(summary["extended_range_bins"]["50_75m"]["vision_count"], 1)
        self.assertEqual(summary["extended_range_bins"]["50_75m"]["count"], 0)

    def test_backward_compatible_summary_keys(self):
        matches = [
            {"v_fwd_m": 10.0, "r_fwd_m": 9.8, "delta_fwd_m": 0.2, "delta_left_m": 0.1, "distance_error_m": 0.22, "class_name": "car"},
        ]
        stats = ErrorMetricsCalculator.compute_summary_statistics(matches, 4, 3)
        for key in ("matched_count", "total_vision_tracks", "total_radar_tracks", "match_rate_pct", "overall", "range_bins"):
            self.assertIn(key, stats)
        for rb in ("near_0_15m", "mid_15_30m", "far_above_30m"):
            self.assertIn(rb, stats["range_bins"])

    def test_parse_radar_label_extracts_id_and_speed(self):
        tid, spd = parse_radar_label("[ACC LEAD] ID #42 | -2.5 m/s | TTI 4.1s", fallback_id=1)
        self.assertEqual(tid, 42)
        self.assertAlmostEqual(spd, -2.5, places=3)

        tid_fb, spd_fb = parse_radar_label("[FCW S1 HAZARD] | TTC 1.2s", fallback_id=7)
        self.assertEqual(tid_fb, 7)
        self.assertEqual(spd_fb, 0.0)

    def test_dynamic_pitch_solver_recovers_injected_pitch_error(self):
        # Generate synthetic detections observed by a camera whose true pitch is 4.0 deg,
        # while the session calibration JSON mistakenly claims 5.6 deg.
        true_pitch = 4.0
        wrong_pitch = 5.6
        intr = PinholeIntrinsics.from_fov(width=1280, height=720, hfov_deg=70.0)
        ext_true = CameraExtrinsics(camera_height_m=1.45, pitch_deg=true_pitch, yaw_deg=0.0, roll_deg=0.0, setback_m=0.3)
        ext_wrong = CameraExtrinsics(camera_height_m=1.45, pitch_deg=wrong_pitch, yaw_deg=0.0, roll_deg=0.0, setback_m=0.3)
        proj_wrong = GroundPlaneProjector(ext_wrong, intr, 0)

        # True ground objects across 8..35m and diverse lateral positions & classes
        true_targets = [
            ("car", 10.0, -1.5),
            ("truck", 18.0, 2.0),
            ("person", 14.0, -4.0),
            ("car", 26.0, 0.5),
            ("motorcycle", 32.0, -2.2),
        ]

        # Project true 3D ground points back into pixel coordinates using true camera rotation
        import numpy as np
        from tools.vision.geometry.dynamic_pitch_solver import _build_cam_to_base_rotation
        r_cam_to_base = _build_cam_to_base_rotation(true_pitch, 0.0, 0.0, 0)
        r_base_to_cam = r_cam_to_base.T
        cam_pos = np.array([-0.3, 0.0, 1.45])

        vision_frames = []
        radar_frames = []
        radar_ts = []
        base_ts = 1_795_000_000_000_000_000

        for frame_i in range(40):
            ts = base_ts + frame_i * 50_000_000
            v_tracks = []
            r_tracks = [
                # Inject dense near-range radar clutter (< 4m) that must NOT trick the solver into over-tilting
                {"track_id": 900, "x_fwd_m": 2.1, "y_left_m": 0.3},
                {"track_id": 901, "x_fwd_m": 3.0, "y_left_m": -0.5},
            ]
            for k, (cls_name, x_m, y_m) in enumerate(true_targets):
                p_cam = r_base_to_cam @ (np.array([x_m, y_m, 0.0]) - cam_pos)
                u_px = intr.fx * (p_cam[0] / p_cam[2]) + intr.cx
                v_px = intr.fy * (p_cam[1] / p_cam[2]) + intr.cy
                gp_wrong = proj_wrong.project_bottom_center_to_ground(u_px, v_px)
                v_tracks.append({
                    "track_id": k + 1,
                    "class_name": cls_name,
                    "confidence": 0.9,
                    "bbox_2d": [u_px - 20.0, v_px - 40.0, u_px + 20.0, v_px],
                    "contact_patch": {"x_fwd_m": gp_wrong["x_fwd_m"], "y_left_m": gp_wrong["y_left_m"], "z_up_m": 0.0},
                    "position_3d": {"x_fwd_m": gp_wrong["x_fwd_m"] + 2.0, "y_left_m": gp_wrong["y_left_m"], "z_up_m": 0.75},
                })
                r_tracks.append({"track_id": k + 10, "x_fwd_m": x_m, "y_left_m": y_m})

            vision_frames.append({"timestamp_utc_ns": ts, "tracks": v_tracks})
            radar_frames.append({"timestamp_ns": ts, "tracks": r_tracks})
            radar_ts.append(ts)

        res = DynamicPitchSolver.solve_session_pitch(
            vision_frames=vision_frames,
            radar_frames=radar_frames,
            radar_ts=radar_ts,
            extrinsics=ext_wrong,
            intrinsics=intr,
            max_range_m=50.0,
        )
        self.assertTrue(res["success"])
        self.assertAlmostEqual(res["ideal_pitch_deg"], true_pitch, delta=0.15)
        self.assertLess(res["ideal_median_dist_m"], res["configured_median_dist_m"])

        # Re-project at solved ideal pitch and verify match rate jumps to 100%
        reproj = DynamicPitchSolver.reproject_vision_frames(vision_frames, ext_wrong, intr, res["ideal_pitch_deg"])
        assoc = SpatialAssociator(max_distance_gate_m=3.5, anchor_mode="contact_patch", association_mode="nearest")
        summary, _ = _run_benchmark_pass(
            vision_frames=reproj,
            radar_frames=radar_frames,
            radar_ts=radar_ts,
            time_offset_ns=0,
            associator=assoc,
            max_range_m=50.0,
            excluded_classes=set(),
            min_conf=0.0,
        )
        self.assertEqual(summary["match_rate_pct"], 100.0)


if __name__ == "__main__":
    unittest.main()
