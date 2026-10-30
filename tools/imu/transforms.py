"""
RoadSense IMU Coordinate Transformations & 6-DOF Mounting Extrinsics Solver
===========================================================================
Handles rigid body spatial rotations between:
  1. Phone Hardware Body Frame (Android Portrait native: X right, Y up, Z out of screen)
  2. Camera Optical Frame (RDF: X right, Y down, Z forward along lens optical axis)
  3. Vehicle Body Frame (ISO 8855 / ROS FLU: X forward, Y left, Z up)

Mounting Mode: Reverse Landscape (Surface.ROTATION_270) clamped at rear-view mirror.
"""

import math
import numpy as np


class ImuFrameTransformer:
    """
    Transforms 3D IMU kinematics (acceleration, angular velocity, linear acceleration)
    and attitude between phone hardware frame and vehicle chassis base_link (FLU).
    """

    def __init__(self, calib_params=None):
        """
        Initializes the 3D rotation pipeline from calibration parameters.
        
        Args:
            calib_params: Optional dict containing 'pitchDeg', 'rollDeg', 'cameraYawDeg' / 'yawDeg'.
        """
        self.calib_params = calib_params or {}
        self.R_base_from_phone = self._build_phone_to_base_matrix(self.calib_params)
        
        # Pre-extract row-major coefficients for high-speed inner loop evaluation
        self.m00 = float(self.R_base_from_phone[0, 0])
        self.m01 = float(self.R_base_from_phone[0, 1])
        self.m02 = float(self.R_base_from_phone[0, 2])
        self.m10 = float(self.R_base_from_phone[1, 0])
        self.m11 = float(self.R_base_from_phone[1, 1])
        self.m12 = float(self.R_base_from_phone[1, 2])
        self.m20 = float(self.R_base_from_phone[2, 0])
        self.m21 = float(self.R_base_from_phone[2, 1])
        self.m22 = float(self.R_base_from_phone[2, 2])

    @staticmethod
    def _build_phone_to_base_matrix(calib_params):
        """
        Builds the 3x3 rotation matrix mapping Phone Hardware Frame -> Vehicle base_link FLU.
        R_base_from_phone = R_cam_to_base @ R_opt_from_phone
        where R_cam_to_base = (Rz(roll) @ Rx(pitch) @ Ry(yaw) @ R0)^T
        """
        # Nominal mapping from Optical RDF -> Vehicle FLU
        R0 = np.array([
            [ 0.0, -1.0,  0.0],
            [ 0.0,  0.0, -1.0],
            [ 1.0,  0.0,  0.0]
        ], dtype=float)

        if calib_params:
            pitch_rad = math.radians(float(calib_params.get("pitchDeg", 0.0)))
            roll_rad = math.radians(float(calib_params.get("rollDeg", 0.0)))
            yaw_rad = math.radians(float(calib_params.get("cameraYawDeg", calib_params.get("yawDeg", 0.0))))

            Ry = np.array([
                [ math.cos(yaw_rad),  0.0, -math.sin(yaw_rad)],
                [ 0.0,                1.0,  0.0],
                [ math.sin(yaw_rad),  0.0,  math.cos(yaw_rad)]
            ], dtype=float)

            Rx = np.array([
                [ 1.0, 0.0,                  0.0],
                [ 0.0, math.cos(pitch_rad), -math.sin(pitch_rad)],
                [ 0.0, math.sin(pitch_rad),  math.cos(pitch_rad)]
            ], dtype=float)

            Rz = np.array([
                [ math.cos(roll_rad),  math.sin(roll_rad), 0.0],
                [-math.sin(roll_rad),  math.cos(roll_rad), 0.0],
                [ 0.0,                 0.0,                1.0]
            ], dtype=float)

            R_total = Rz @ Rx @ Ry @ R0

            # Phone portrait frame -> Camera optical RDF in ROTATION_270
            # X_opt = +Y_phone (passenger right), Y_opt = +X_phone (down), Z_opt = -Z_phone (forward)
            R_opt_from_phone = np.array([
                [ 0.0,  1.0,  0.0],
                [ 1.0,  0.0,  0.0],
                [ 0.0,  0.0, -1.0]
            ], dtype=float)

            return R_total.T @ R_opt_from_phone
        else:
            # Fallback nominal swap without mounting calibration angles
            return np.array([
                [ 0.0,  0.0, -1.0],
                [ 0.0, -1.0,  0.0],
                [-1.0,  0.0,  0.0]
            ], dtype=float)

    def transform_vector(self, x: float, y: float, z: float):
        """
        Transforms a 3D vector (acceleration, angular velocity, linear acceleration)
        from phone hardware coordinates into vehicle base_link FLU coordinates.
        Returns: (vx, vy, vz)
        """
        vx = self.m00 * x + self.m01 * y + self.m02 * z
        vy = self.m10 * x + self.m11 * y + self.m12 * z
        vz = self.m20 * x + self.m21 * y + self.m22 * z
        return vx, vy, vz

    def compute_vehicle_attitude_from_phone_quat(self, qx: float, qy: float, qz: float, qw: float):
        """
        Computes the vehicle body attitude (Euler pitch, roll, and nominal yaw)
        and normalized vehicle quaternion directly from the phone's Android rotation quaternion.
        
        Returns:
            v_pitch_deg, v_roll_deg, v_yaw_deg, (vq_x, vq_y, vq_z, vq_w)
        """
        # Convert phone quaternion to 3x3 rotation matrix R_world_from_phone
        p00 = 1.0 - 2.0 * (qy * qy + qz * qz)
        p01 = 2.0 * (qx * qy - qz * qw)
        p02 = 2.0 * (qx * qz + qy * qw)

        p10 = 2.0 * (qx * qy + qz * qw)
        p11 = 1.0 - 2.0 * (qx * qx + qz * qz)
        p12 = 2.0 * (qy * qz - qx * qw)

        p20 = 2.0 * (qx * qz - qy * qw)
        p21 = 2.0 * (qy * qz + qx * qw)
        p22 = 1.0 - 2.0 * (qx * qx + qy * qy)

        # R_world_from_base = R_world_from_phone @ R_base_from_phone.T
        wb00 = p00 * self.m00 + p01 * self.m01 + p02 * self.m02
        wb01 = p00 * self.m10 + p01 * self.m11 + p02 * self.m12
        wb02 = p00 * self.m20 + p01 * self.m21 + p02 * self.m22

        wb10 = p10 * self.m00 + p11 * self.m01 + p12 * self.m02
        wb11 = p10 * self.m10 + p11 * self.m11 + p12 * self.m12
        wb12 = p10 * self.m20 + p11 * self.m21 + p12 * self.m22

        wb20 = p20 * self.m00 + p21 * self.m01 + p22 * self.m02
        wb21 = p20 * self.m10 + p21 * self.m11 + p22 * self.m12
        wb22 = p20 * self.m20 + p21 * self.m21 + p22 * self.m22

        # Vehicle body Euler angles (ISO 8855 FLU):
        v_pitch_deg = math.degrees(math.asin(max(-1.0, min(1.0, wb20))))
        v_roll_deg = math.degrees(math.atan2(wb21, wb22))
        v_yaw_deg = math.degrees(math.atan2(wb10, wb00)) % 360.0

        # Convert R_world_from_base to normalized quaternion [qx, qy, qz, qw]
        tr = wb00 + wb11 + wb22
        if tr > 0:
            s = 0.5 / math.sqrt(tr + 1.0)
            vq_w = 0.25 / s
            vq_x = (wb21 - wb12) * s
            vq_y = (wb02 - wb20) * s
            vq_z = (wb10 - wb01) * s
        elif wb00 > wb11 and wb00 > wb22:
            s = 2.0 * math.sqrt(1.0 + wb00 - wb11 - wb22)
            vq_w = (wb21 - wb12) / s
            vq_x = 0.25 * s
            vq_y = (wb01 + wb10) / s
            vq_z = (wb02 + wb20) / s
        elif wb11 > wb22:
            s = 2.0 * math.sqrt(1.0 + wb11 - wb00 - wb22)
            vq_w = (wb02 - wb20) / s
            vq_x = (wb01 + wb10) / s
            vq_y = 0.25 * s
            vq_z = (wb12 + wb21) / s
        else:
            s = 2.0 * math.sqrt(1.0 + wb22 - wb00 - wb11)
            vq_w = (wb10 - wb01) / s
            vq_x = (wb02 + wb20) / s
            vq_y = (wb12 + wb21) / s
            vq_z = 0.25 * s

        q_norm = math.sqrt(vq_x * vq_x + vq_y * vq_y + vq_z * vq_z + vq_w * vq_w)
        if q_norm > 0:
            vq_x /= q_norm
            vq_y /= q_norm
            vq_z /= q_norm
            vq_w /= q_norm
        if vq_w < 0:
            vq_x, vq_y, vq_z, vq_w = -vq_x, -vq_y, -vq_z, -vq_w

        return v_pitch_deg, v_roll_deg, v_yaw_deg, (vq_x, vq_y, vq_z, vq_w)

    @staticmethod
    def quaternion_from_euler(roll_rad: float, pitch_rad: float, yaw_rad: float):
        """
        Synthesizes a normalized quaternion [qx, qy, qz, qw] from Euler angles
        using standard aerospace / ISO 8855 Tait-Bryan Z-Y-X convention.
        Yaw (Z), Pitch (Y), Roll (X).
        """
        cy = math.cos(yaw_rad * 0.5)
        sy = math.sin(yaw_rad * 0.5)
        cp = math.cos(pitch_rad * 0.5)
        sp = math.sin(pitch_rad * 0.5)
        cr = math.cos(roll_rad * 0.5)
        sr = math.sin(roll_rad * 0.5)

        qw = cr * cp * cy + sr * sp * sy
        qx = sr * cp * cy - cr * sp * sy
        qy = cr * sp * cy + sr * cp * sy
        qz = cr * cp * sy - sr * sp * cy

        norm = math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if norm > 0:
            qx /= norm
            qy /= norm
            qz /= norm
            qw /= norm
        if qw < 0:
            qx, qy, qz, qw = -qx, -qy, -qz, -qw
        return qx, qy, qz, qw

    @staticmethod
    def euler_from_quaternion(qx: float, qy: float, qz: float, qw: float):
        """
        Extracts Tait-Bryan roll, pitch, yaw (in degrees) from a quaternion.
        """
        sinr_cosp = 2.0 * (qw * qx + qy * qz)
        cosr_cosp = 1.0 - 2.0 * (qx * qx + qy * qy)
        roll_deg = math.degrees(math.atan2(sinr_cosp, cosr_cosp))

        sinp = 2.0 * (qw * qy - qz * qx)
        if abs(sinp) >= 1.0:
            pitch_deg = math.copysign(90.0, sinp)
        else:
            pitch_deg = math.degrees(math.asin(sinp))

        siny_cosp = 2.0 * (qw * qz + qx * qy)
        cosy_cosp = 1.0 - 2.0 * (qy * qy + qz * qz)
        yaw_deg = math.degrees(math.atan2(siny_cosp, cosy_cosp))

        return roll_deg, pitch_deg, yaw_deg

