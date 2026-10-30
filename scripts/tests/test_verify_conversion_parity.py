#!/usr/bin/env python3
"""
Unit tests for tools/verify_conversion_parity.py.
Verifies that the parity validator correctly catches:
  1. Perfect parity sessions (100% PASS).
  2. Frame count cardinality mismatches.
  3. Numerical coordinate deviations in radar point clouds.
  4. Missing TLV 5 CAN telemetry fields.
  5. Missing TLV 6 ADAS safety fields.
  6. Non-monotonic timestamp inversions.
"""

import os
import sys
import json
import math
import struct
import tempfile
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.verify_conversion_parity import verify_session_parity


def build_synthetic_session(temp_dir: str, num_frames: int = 5, corrupt_mode: str = "none") -> str:
    """Creates a temporary synthetic session with known ground truth."""
    sess_path = os.path.join(temp_dir, "synthetic_session")
    os.makedirs(os.path.join(sess_path, "radar"), exist_ok=True)
    os.makedirs(os.path.join(sess_path, "camera"), exist_ok=True)

    radar_bin_path = os.path.join(sess_path, "radar", "radar_frames.bin")
    th_path = os.path.join(sess_path, "track_history.json")
    fm_path = os.path.join(sess_path, "frame_mapping.json")

    ti_magic = b'\x02\x01\x04\x03\x06\x05\x08\x07'

    viz_frames = []
    viz_tracks = []
    mapping_records = []

    # Write radar_frames.bin
    with open(radar_bin_path, "wb") as bf:
        for f_idx in range(num_frames):
            frame_num = 1000 + f_idx
            mono_ns = 1_000_000_000 + f_idx * 50_000_000  # 20 Hz
            wall_ms = 1_700_000_000_000 + f_idx * 50

            # TLV 1: Points (1 point per frame: 8B descriptor + 10B point = 14B payload)
            tlv1_hdr = struct.pack("<II", 1, 14)
            desc = struct.pack("<HH", 1, 7)
            pt_raw = struct.pack("<hHhhh", -128, 1361, 128, 256, 0)

            # TLV 5: CAN inputs (56B payload)
            tlv5_hdr = struct.pack("<II", 5, 56)
            tlv5_payload = struct.pack(
                "<fffffffffffIBBbBB3s",
                36.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 50.0, 0.0, 0.0,
                1000, 3, 0, 0, 1, 0,
                b"\x00\x00\x00"
            )

            # TLV 6: ADAS CAN (24B payload: fcw(8) + bsd(8) + acc(8))
            tlv6_hdr = struct.pack("<II", 6, 24)
            tlv6_payload = b"\x00" * 16 + bytes([1, 2, 54, 138, 138, 102, 117, 128])

            tlvs = tlv1_hdr + desc + pt_raw + tlv5_hdr + tlv5_payload + tlv6_hdr + tlv6_payload
            total_len = 40 + len(tlvs)

            # 24B ROAD header
            road_hdr = struct.pack(">4sQQI", b"ROAD", mono_ns, wall_ms, total_len)
            bf.write(road_hdr)

            # TI Frame Header (40B: 8s + 8*I = 9 items)
            ti_hdr = struct.pack("<8sIIIIIIII", ti_magic, 0x01020304, total_len, 0, frame_num, 0, 1, 3, 0)
            bf.write(ti_hdr + tlvs)

            # Ground truth visualizer frame
            pt_x = 1.0 if corrupt_mode != "corrupt_points" else 99.0
            viz_frame = {
                "frameIdx": f_idx + 1,
                "timestamp": f_idx * 50.0,
                "numPoints": 1,
                "motionState": 0,
                "egoVelocity": [0.0, 10.0],
                "canVehSpeed_kmph": 36.0,
                "AccelPedal_Act_perc": 0.0,
                "shaftTorque_Nm": 50.0,
                "engagedGear": 3,
                "roadGrade_Deg": 0.0,
                "acceleration_avg": 0.0,
                "video_frame_index": f_idx,
                "video_time_delta": 0.005,
                "pointCloud": [{"x": pt_x, "y": 2.0, "z": 0.0, "velocity": -1.0, "snr": 16.004}],
                "clusters": [],
                "adas": [{
                    "poi_id": 258,
                    "acc_dist": 27.0,
                    "acc_target_x": 2.0,
                    "acc_rel_v": 5.0,
                    "acc_rel_a": 0.0,
                    "acc_status": 1,
                    "tti": 10.2,
                    "v_safe_mps": 11.7,
                    "fcw_stage": 0,
                    "fcw_track_id": 0,
                    "fcw_target_x": -25.6,
                    "fcw_target_y": 0.0,
                    "fcw_target_vx": -25.6,
                    "fcw_target_vy": -64.0,
                    "fcw_ttc": 0.0,
                    "bsd_left_active": False,
                    "bsd_right_active": False,
                    "lca_warning_level": 0
                }]
            }

            if corrupt_mode == "missing_adas_fields":
                del viz_frame["adas"][0]["acc_target_x"]
                del viz_frame["adas"][0]["fcw_target_x"]

            if corrupt_mode == "missing_can_fields":
                del viz_frame["shaftTorque_Nm"]

            viz_frames.append(viz_frame)

            # Mapping record
            ts = mono_ns / 1e9
            if corrupt_mode == "backwards_time" and f_idx == 2:
                ts = (mono_ns - 100_000_000) / 1e9  # Invert time step

            mapping_records.append({
                "radar_frame_id_rel": f_idx + 1,
                "radar_frame_id_abs": frame_num,
                "radar_timestamp": ts,
                "video_frame_index": f_idx,
                "video_frame_ts": ts + 0.005,
                "video_time_delta": 0.005,
                "camera_hw_frame_number": 2000 + f_idx
            })

    if corrupt_mode == "cardinality_mismatch":
        viz_frames.pop()  # Drop one frame from JSON

    # Write track_history.json
    with open(th_path, "w", encoding="utf-8") as f:
        json.dump({"radarFrames": viz_frames, "tracks": viz_tracks}, f, indent=2)

    # Write frame_mapping.json (JSONL)
    with open(fm_path, "w", encoding="utf-8") as f:
        for r in mapping_records:
            f.write(json.dumps(r) + "\n")
        # Metadata footer
        f.write(json.dumps({"metadata": {"average_fps": 30.0, "duration_sec": 0.25, "total_frames": num_frames}}) + "\n")

    return sess_path


class TestVerifyConversionParity(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_synthetic_perfect_parity(self):
        """Clean session must achieve 100% PASS score across all checks."""
        sess = build_synthetic_session(self.temp_dir.name, num_frames=10, corrupt_mode="none")
        report = verify_session_parity(sess, sample_count=10, check_all=True)
        self.assertEqual(report.overall_status(), "PASS")
        self.assertEqual(report.compute_total_score(), 100.0)

    def test_cardinality_mismatch_detected(self):
        """Mismatched frame count between raw and JSON must trigger FAIL."""
        sess = build_synthetic_session(self.temp_dir.name, num_frames=5, corrupt_mode="cardinality_mismatch")
        report = verify_session_parity(sess, sample_count=5, check_all=True)
        self.assertEqual(report.overall_status(), "FAIL")
        self.assertEqual(report.categories["cardinality"].status, "FAIL")

    def test_pointcloud_coordinate_error_detected(self):
        """Coordinate deviation must fail point cloud numerical fidelity."""
        sess = build_synthetic_session(self.temp_dir.name, num_frames=5, corrupt_mode="corrupt_points")
        report = verify_session_parity(sess, sample_count=5, check_all=True)
        self.assertEqual(report.overall_status(), "FAIL")
        self.assertEqual(report.categories["pointcloud"].status, "FAIL")

    def test_missing_adas_fields_detected(self):
        """Dropping TLV 6 ADAS outputs must fail ADAS safety check."""
        sess = build_synthetic_session(self.temp_dir.name, num_frames=5, corrupt_mode="missing_adas_fields")
        report = verify_session_parity(sess, sample_count=5, check_all=True)
        self.assertEqual(report.overall_status(), "FAIL")
        self.assertEqual(report.categories["adas_safety"].status, "FAIL")

    def test_missing_can_telemetry_detected(self):
        """Missing shaftTorque_Nm must fail CAN telemetry check."""
        sess = build_synthetic_session(self.temp_dir.name, num_frames=5, corrupt_mode="missing_can_fields")
        report = verify_session_parity(sess, sample_count=5, check_all=True)
        self.assertEqual(report.overall_status(), "FAIL")
        self.assertEqual(report.categories["can_telemetry"].status, "FAIL")

    def test_backwards_time_jump_detected(self):
        """Backwards time jump in frame_mapping.json must fail temporal sync check."""
        sess = build_synthetic_session(self.temp_dir.name, num_frames=5, corrupt_mode="backwards_time")
        report = verify_session_parity(sess, sample_count=5, check_all=True)
        self.assertEqual(report.overall_status(), "FAIL")
        self.assertEqual(report.categories["time_sync"].status, "FAIL")

    def test_gnss_network_separation(self):
        """Verify that load_gnss_fixes_csv filters out network fixes when gps is present."""
        from tools.sync_and_process_sessions import load_gnss_fixes_csv
        from tools.convert_session_to_mcap import parse_gnss_csv
        import csv

        csv_path = os.path.join(self.temp_dir.name, "test_gnss.csv")
        with open(csv_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["elapsed_realtime_ns", "utc_time_ms", "utc_iso", "latitude", "longitude", "altitude_m", "speed_mps", "speed_kmh", "bearing_deg", "accuracy_m", "provider", "satellites"])
            writer.writerow([1000000000, 1700000000000, "2026-01-01T00:00:00Z", 18.0, 73.0, 500.0, 10.0, 36.0, 90.0, 5.0, "gps", "12/15"])
            writer.writerow([1050000000, 1700000000500, "2026-01-01T00:00:00Z", 18.1, 73.1, 540.0, 0.0, 0.0, 0.0, 75.0, "network", "12/15"])
            writer.writerow([2000000000, 1700000001000, "2026-01-01T00:00:01Z", 18.0001, 73.0001, 500.0, 10.5, 37.8, 90.0, 5.0, "gps", "12/15"])

        # In sync_and_process_sessions: only gps fixes must be loaded
        sync_fixes = load_gnss_fixes_csv(csv_path)
        self.assertEqual(len(sync_fixes), 2)
        self.assertTrue(all(f["provider"] == "gps" for f in sync_fixes))

        # In convert_session_to_mcap: parse_gnss_csv must yield all fixes with provider
        mcap_fixes = list(parse_gnss_csv(csv_path))
        self.assertEqual(len(mcap_fixes), 3)
        self.assertEqual(mcap_fixes[0]["provider"], "gps")
        self.assertEqual(mcap_fixes[1]["provider"], "network")
        self.assertEqual(mcap_fixes[2]["provider"], "gps")

    def test_imu_vehicle_frame_transformation(self):
        """Verify parse_imu_csv transforms raw Reverse Landscape phone IMU into vehicle base_link with calibration compensation."""
        from tools.convert_session_to_mcap import parse_imu_csv
        import csv

        csv_path = os.path.join(self.temp_dir.name, "test_imu.csv")
        with open(csv_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["elapsed_realtime_ns", "wall_time_ms", "ax", "ay", "az", "gx", "gy", "gz", "qx", "qy", "qz", "qw", "lin_ax", "lin_ay", "lin_az"])
            # Sample from real windshield reverse landscape mount (pitch ~ 5.01 deg, roll ~ -0.35 deg)
            writer.writerow([1000000000, 1700000000000, -9.37569, 0.03472, -0.34955, -0.01976, 0.01263, 0.06464, 0.67451, 0.03698, -0.73598, 0.04482, 0.10416, -0.00486, -0.01460])

        calib_params = {
            "pitchDeg": 5.03427,
            "rollDeg": -0.35610,
            "cameraYawDeg": 0.0
        }

        # Test with mounting calibration compensation
        samples = list(parse_imu_csv(csv_path, calib_params=calib_params))
        self.assertEqual(len(samples), 1)
        raw_sample, veh_sample = samples[0]

        # 1. Raw phone frame preserved for /imu/data
        self.assertAlmostEqual(raw_sample["ax"], -9.37569, places=4)
        self.assertAlmostEqual(raw_sample["pitch_deg"], 84.98, delta=1.0)  # Phone portrait pitch

        # 2. Transformed vehicle body frame for /imu/vehicle (ISO 8855 FLU)
        # Vertical acceleration Az is positive normal reaction against gravity
        self.assertGreater(veh_sample["acceleration"]["z"], 9.0)
        self.assertGreater(veh_sample["az"], 9.0)

        # 3. Attitude angles in vehicle chassis (compensated for mounting pitch and roll)
        # In vehicle base_link on flat ground, pitch and roll should be centered near 0.0°
        self.assertAlmostEqual(veh_sample["pitch_deg"], 0.02, delta=0.2)   # Compensated vehicle pitch (~0°)
        self.assertAlmostEqual(veh_sample["roll_deg"], 0.01, delta=0.2)    # Compensated vehicle roll (~0°)

        # 4. Vehicle quaternion norm = 1.0 and upright (dominant qw)
        q = veh_sample["orientation"]
        norm = math.sqrt(q["x"]**2 + q["y"]**2 + q["z"]**2 + q["w"]**2)
        self.assertAlmostEqual(norm, 1.0, places=4)
        self.assertGreater(q["w"], 0.99)  # Upright vehicle body frame scalar component


if __name__ == "__main__":
    unittest.main()
