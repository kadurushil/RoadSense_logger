"""
RoadSense GNSS-IMU Heading Fusion Engine (EKF with ZUPT)
========================================================
Implements a 2-state Extended Kalman Filter fusing 100 Hz IMU gyroscope rates
with 1 Hz GNSS Doppler velocity course (bearing) and Zero Velocity Updates (ZUPT).

Solves the persistent open-loop MEMS gyroscope yaw drift by:
  1. Continually estimating and subtracting the physical DC gyro bias (b_z)
     during stationary intervals (ZUPT).
  2. Bounding heading to true geographic course over ground (0° = North, 90° = East)
     during active driving (speed > 1.5 m/s).
  3. Seamlessly dead-reckoning turns at 100 Hz with 0.0° stationary drift.
"""

import math
import numpy as np


def wrap_pi(rad: float) -> float:
    """Wraps an angle in radians to [-pi, pi)."""
    return (rad + math.pi) % (2.0 * math.pi) - math.pi


def wrap_360(deg: float) -> float:
    """Wraps an angle in degrees to [0, 360.0)."""
    return deg % 360.0


class GnssImuHeadingFilter:
    """
    2-state Extended Kalman Filter for vehicle geographic heading.
    State vector: x = [psi, b_z]^T
      - psi: geographic heading (bearing) in radians [-pi, pi), where 0 = North, pi/2 = East.
      - b_z: estimated zero-rate gyroscope bias in rad/s.
    """

    def __init__(self, init_heading_deg: float = 0.0, init_bias_deg_s: float = 0.0):
        # State: [psi_rad, b_z_rad_s]
        init_heading_rad = wrap_pi(math.radians(init_heading_deg))
        init_bias_rad_s = math.radians(init_bias_deg_s)
        self.x = np.array([init_heading_rad, init_bias_rad_s], dtype=float)

        # State error covariance
        self.P = np.array([
            [math.radians(10.0)**2, 0.0],
            [0.0,                   math.radians(1.0)**2]
        ], dtype=float)

        # Process noise:
        # q_omega: IMU angular velocity integration noise (rad^2 / s)
        # q_bias:  gyro bias random walk (rad^2 / s^3)
        self.q_omega = math.radians(0.15)**2
        self.q_bias = math.radians(0.0001)**2

        # Status tracking
        self.has_gps_lock = False
        self.total_predict_steps = 0
        self.total_zupt_updates = 0
        self.total_gps_updates = 0

    def predict(self, v_gz: float, dt: float):
        """
        High-rate prediction step at ~100 Hz.
        
        Args:
            v_gz: Vehicle body yaw rate around +Z Up (FLU) in rad/s. Positive = CCW.
                  Since navigation bearing increases CW, omega_bearing = -v_gz.
            dt: Time delta since previous sample in seconds.
        """
        if dt <= 0.0 or dt > 0.5:
            dt = 0.01

        # Angular rate in clockwise geographic bearing frame
        omega_bearing = -v_gz

        # Correct for estimated bias
        rate_corrected = omega_bearing - self.x[1]

        # Predict heading state
        self.x[0] = wrap_pi(self.x[0] + rate_corrected * dt)

        # Error covariance propagation: P = F * P * F^T + Q
        # F = [[1, -dt], [0, 1]]
        F = np.array([
            [1.0, -dt],
            [0.0,  1.0]
        ], dtype=float)

        Q = np.array([
            [self.q_omega * dt, 0.0],
            [0.0,               self.q_bias * dt]
        ], dtype=float)

        self.P = F @ self.P @ F.T + Q
        self.total_predict_steps += 1

    def update_zupt(self, v_gz: float, r_zupt: float = 1e-4):
        """
        Zero Velocity Update (ZUPT) step executed when the vehicle is stationary.
        
        When the vehicle is not moving, the true angular velocity is identically zero.
        Therefore, any measured angular rate is purely the sensor bias.
        
        Measurement: z = omega_bearing = -v_gz
        Measurement model: z = b_z + v_noise, H = [0, 1]
        """
        omega_bearing = -v_gz
        H = np.array([[0.0, 1.0]], dtype=float)
        y = omega_bearing - self.x[1]

        S = float((H @ self.P @ H.T)[0, 0] + r_zupt)
        K = (self.P @ H.T) / S

        self.x += (K * y).flatten()
        self.x[0] = wrap_pi(self.x[0])
        self.P = (np.eye(2) - K @ H) @ self.P
        self.total_zupt_updates += 1

    def update_gps(self, bearing_deg: float, speed_mps: float, r_base: float = 0.04):
        """
        Measurement update from GNSS Doppler course / bearing.
        
        Executed when vehicle speed > 1.5 m/s.
        Measurement model: z = psi + v_noise, H = [1, 0]
        
        Measurement noise variance scales inversely with speed because Doppler velocity
        course accuracy improves as velocity increases.
        """
        bearing_rad = wrap_pi(math.radians(bearing_deg))

        # Dynamic measurement noise: tighter at highway speeds, looser during slow creep
        speed_clamped = max(1.0, speed_mps / 5.0)
        r_gps = r_base / speed_clamped

        H = np.array([[1.0, 0.0]], dtype=float)
        y = wrap_pi(bearing_rad - self.x[0])

        S = float((H @ self.P @ H.T)[0, 0] + r_gps)
        K = (self.P @ H.T) / S

        self.x += (K * y).flatten()
        self.x[0] = wrap_pi(self.x[0])
        self.P = (np.eye(2) - K @ H) @ self.P

        self.has_gps_lock = True
        self.total_gps_updates += 1

    def get_heading_deg(self) -> float:
        """Returns the current vehicle geographic heading in [0, 360.0) degrees."""
        return wrap_360(math.degrees(self.x[0]))

    def get_heading_rad(self) -> float:
        """Returns the current vehicle geographic heading in [-pi, pi) radians."""
        return self.x[0]

    def get_bias_deg_s(self) -> float:
        """Returns the current estimated gyroscope yaw bias in degrees per second."""
        return math.degrees(self.x[1])

    def get_bias_rad_s(self) -> float:
        """Returns the current estimated gyroscope yaw bias in rad/s."""
        return self.x[1]

