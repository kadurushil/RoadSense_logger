package com.bajajauto.roadsense.sync

import com.bajajauto.roadsense.camera.CameraFrameMetadata
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.imu.ImuFrame
import com.bajajauto.roadsense.models.RadarHeader
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-rich multi-sensor synchronized keyframe representing a single unified time-slice
 * centered on the smartphone camera shutter exposure.
 *
 * Implements the nuScenes / Waymo autonomous vehicle dataset paradigm where all multimodal
 * perception streams (Camera, high-rate IMU kinematics, Radar detections, and GNSS position)
 * are pre-associated with sub-millisecond precision into an immutable tabular record.
 */
data class SyncKeyframe(
    val keyframeId: Long,
    val wallTimeMs: Long,
    val syncGrade: String,

    // --- Camera Shutter Metadata ---
    val cameraFrameNumber: Long,
    val cameraShutterMonoNs: Long,
    val cameraExposureTimeNs: Long,
    val cameraIso: Int,

    // --- Nearest High-Rate IMU Sample (100–104 Hz) ---
    val imuSequenceId: Long,
    val imuMonoNs: Long,
    val imuDeliveryNs: Long,
    val deltaCamImuNs: Long,
    val deltaCamImuMs: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val linAccelX: Float,
    val linAccelY: Float,
    val linAccelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val quatW: Float,
    val quatX: Float,
    val quatY: Float,
    val quatZ: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val yawDeg: Float,

    // --- Nearest Radar Frame (20 Hz) ---
    val isRadarActive: Boolean,
    val radarFrameNumber: Long,
    val radarHostMonoNs: Long,
    val deltaCamRadarNs: Long,
    val deltaCamRadarMs: Float,
    val radarNumDetectedObj: Int,
    val radarNumTLVs: Int,
    val radarSubFrameNumber: Int,

    // --- Nearest GNSS Fix (1 Hz) ---
    val hasGnssFix: Boolean,
    val gnssUtcTimeMs: Long,
    val gnssLatitude: Double,
    val gnssLongitude: Double,
    val gnssAltitudeMeters: Double,
    val gnssSpeedMps: Float,
    val gnssBearingDegrees: Float,
    val gnssAccuracyMeters: Float
) {

    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.3f,%.3f,%.3f,%s,%d,%d,%d,%.3f,%d,%d,%d,%s,%d,%.7f,%.7f,%.2f,%.2f,%.1f,%.2f\n",
            keyframeId,
            wallTimeMs,
            syncGrade,
            cameraFrameNumber,
            cameraShutterMonoNs,
            cameraExposureTimeNs,
            cameraIso,
            imuSequenceId,
            imuMonoNs,
            imuDeliveryNs,
            deltaCamImuNs,
            deltaCamImuMs,
            accelX,
            accelY,
            accelZ,
            linAccelX,
            linAccelY,
            linAccelZ,
            gyroX,
            gyroY,
            gyroZ,
            quatW,
            quatX,
            quatY,
            quatZ,
            pitchDeg,
            rollDeg,
            yawDeg,
            if (isRadarActive) "CONNECTED" else "DISCONNECTED",
            radarFrameNumber,
            radarHostMonoNs,
            deltaCamRadarNs,
            deltaCamRadarMs,
            radarNumDetectedObj,
            radarNumTLVs,
            radarSubFrameNumber,
            if (hasGnssFix) "FIX" else "NO_FIX",
            gnssUtcTimeMs,
            gnssLatitude,
            gnssLongitude,
            gnssAltitudeMeters,
            gnssSpeedMps,
            gnssBearingDegrees,
            gnssAccuracyMeters
        )
    }

    companion object {
        const val CSV_HEADER = "keyframe_id,wall_time_ms,sync_grade," +
                "camera_frame_number,camera_shutter_mono_ns,camera_exposure_time_ns,camera_iso," +
                "imu_sequence_id,imu_mono_ns,imu_delivery_ns,delta_cam_imu_ns,delta_cam_imu_ms," +
                "accel_x_mps2,accel_y_mps2,accel_z_mps2,lin_accel_x_mps2,lin_accel_y_mps2,lin_accel_z_mps2," +
                "gyro_x_rads,gyro_y_rads,gyro_z_rads,quat_w,quat_x,quat_y,quat_z," +
                "pitch_deg,roll_deg,yaw_deg," +
                "radar_status,radar_frame_number,radar_host_mono_ns,delta_cam_radar_ns,delta_cam_radar_ms," +
                "radar_num_detected_obj,radar_num_tlvs,radar_subframe_number," +
                "gnss_status,gnss_utc_time_ms,gnss_latitude,gnss_longitude,gnss_altitude_m,gnss_speed_mps,gnss_bearing_deg,gnss_accuracy_m\n"

        /**
         * Calculates attitude Euler angles (Pitch, Roll, Yaw in degrees) from an orientation quaternion.
         */
        fun quaternionToEulerDeg(qw: Float, qx: Float, qy: Float, qz: Float): Triple<Float, Float, Float> {
            val sinPitch = 2.0 * (qw * qy - qz * qx)
            val pitch = if (kotlin.math.abs(sinPitch) >= 1.0) {
                Math.copySign(Math.PI / 2.0, sinPitch)
            } else {
                asin(sinPitch)
            }
            val roll = atan2(2.0 * (qw * qx + qy * qz), 1.0 - 2.0 * (qx * qx + qy * qy))
            val yaw = atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz))

            return Triple(
                Math.toDegrees(pitch).toFloat(),
                Math.toDegrees(roll).toFloat(),
                Math.toDegrees(yaw).toFloat()
            )
        }
    }
}
