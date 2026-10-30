"""
Unit tests for tools/imu module:
  - Coordinate transformations (ImuFrameTransformer)
  - 2-state EKF heading fusion (GnssImuHeadingFilter)
  - Stream processor (ImuSessionProcessor)
"""

import math
import os
import tempfile
import unittest
import numpy as np

from tools.imu.transforms import ImuFrameTransformer
from tools.imu.heading_filter import GnssImuHeadingFilter, wrap_pi, wrap_360
from tools.imu.processor import ImuSessionProcessor


class TestImuModule(unittest.TestCase):

    def test_transforms_matrix_orthogonality(self):
        calib = {"pitchDeg": 5.03427, "rollDeg": -0.35610, "cameraYawDeg": 0.0}
        transformer = ImuFrameTransformer(calib)
        R = transformer.R_base_from_phone

        # Determinant must be +1.0 for a pure rotation
        det = np.linalg.det(R)
        self.assertAlmostEqual(det, 1.0, places=5)

        # R @ R.T must be Identity
        identity = R @ R.T
        np.testing.assert_allclose(identity, np.eye(3), atol=1e-5)

    def test_transforms_phone_to_vehicle_axes(self):
        # In Reverse Landscape on windshield:
        # Phone X pointing DOWN into Earth (-9.81 m/s^2)
        # Should transform into Vehicle +Z UP (+9.81 m/s^2)
        calib = {"pitchDeg": 5.0, "rollDeg": 0.0, "cameraYawDeg": 0.0}
        transformer = ImuFrameTransformer(calib)

        # Pure gravity along -X phone
        vx, vy, vz = transformer.transform_vector(-9.80665, 0.0, 0.0)
        self.assertGreater(vz, 9.0) # Vertical upward reaction
        self.assertAlmostEqual(vy, 0.0, places=2)

    def test_transforms_quaternion_euler_roundtrip(self):
        roll = math.radians(10.0)
        pitch = math.radians(-5.0)
        yaw = math.radians(120.0)

        qx, qy, qz, qw = ImuFrameTransformer.quaternion_from_euler(roll, pitch, yaw)
        r_deg, p_deg, y_deg = ImuFrameTransformer.euler_from_quaternion(qx, qy, qz, qw)

        self.assertAlmostEqual(r_deg, 10.0, places=2)
        self.assertAlmostEqual(p_deg, -5.0, places=2)
        self.assertAlmostEqual(y_deg, 120.0, places=2)

    def test_heading_filter_zupt_bias_convergence(self):
        # Simulate stationary vehicle with a persistent gyro rate bias of -0.0254 rad/s (-1.45 deg/s)
        bias_true = -0.0254
        ekf = GnssImuHeadingFilter(init_heading_deg=90.0, init_bias_deg_s=0.0)

        # Simulate 100 stationary samples at 100 Hz (1 second)
        for _ in range(100):
            ekf.update_zupt(v_gz=bias_true)
            ekf.predict(v_gz=bias_true, dt=0.01)

        # Estimated bias in omega_bearing (-v_gz) should converge to +0.0254 rad/s (+1.45 deg/s)
        self.assertAlmostEqual(ekf.get_bias_rad_s(), -bias_true, delta=0.005)
        # Heading should not have drifted
        self.assertAlmostEqual(ekf.get_heading_deg(), 90.0, delta=0.5)

    def test_heading_filter_gps_course_tracking(self):
        ekf = GnssImuHeadingFilter(init_heading_deg=0.0, init_bias_deg_s=0.0)

        # Drive straight North (0 deg) for 2 seconds at 10 m/s
        for _ in range(200):
            ekf.predict(v_gz=0.0, dt=0.01)
        ekf.update_gps(bearing_deg=0.0, speed_mps=10.0)

        self.assertAlmostEqual(ekf.get_heading_deg(), 0.0, delta=1.0)

        # Turn East (90 deg CW in navigation, CCW in FLU v_gz: v_gz = -pi/4 rad/s for 2 seconds)
        turn_rate_rad_s = -math.pi / 4.0 # negative v_gz = positive omega_bearing = clockwise turn
        for _ in range(200):
            ekf.predict(v_gz=turn_rate_rad_s, dt=0.01)
        ekf.update_gps(bearing_deg=90.0, speed_mps=10.0)

        self.assertAlmostEqual(ekf.get_heading_deg(), 90.0, delta=2.0)

    def test_imu_processor_mock_stream(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            imu_csv = os.path.join(tmpdir, "imu_frames.csv")
            with open(imu_csv, "w", encoding="utf-8") as f:
                f.write("elapsed_realtime_ns,wall_time_ms,ax,ay,az,gx,gy,gz,qx,qy,qz,qw,lin_ax,lin_ay,lin_az\n")
                f.write("1000000000,1700000000000,-9.77,0.1,0.8,0.02,0.01,-0.03,0.7,0.0,-0.7,0.0,0.0,0.0,0.0\n")
                f.write("1010000000,1700000000010,-9.77,0.1,0.8,0.02,0.01,-0.03,0.7,0.0,-0.7,0.0,0.0,0.0,0.0\n")

            gnss_csv = os.path.join(tmpdir, "gnss_fixes.csv")
            with open(gnss_csv, "w", encoding="utf-8") as f:
                f.write("elapsed_realtime_ns,speed_mps,bearing_deg,accuracy_m,provider\n")
                f.write("1000000000,5.0,90.0,1.0,gps\n")

            processor = ImuSessionProcessor(calib_params={"pitchDeg": 5.0, "rollDeg": 0.0, "cameraYawDeg": 0.0})
            samples = list(processor.process_stream(imu_csv, gnss_csv_path=gnss_csv))

            self.assertEqual(len(samples), 2)
            raw0, veh0 = samples[0]

            self.assertIn("ax", raw0)
            self.assertIn("yaw_deg", raw0)

            self.assertIn("ax", veh0)
            self.assertIn("az", veh0)
            self.assertIn("yaw_deg", veh0)
            self.assertGreater(veh0["az"], 8.0) # Upward gravity reaction


if __name__ == "__main__":
    unittest.main()

