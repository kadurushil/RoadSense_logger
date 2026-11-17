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
        val sb = StringBuilder(384)
        sb.append(keyframeId).append(',')
            .append(wallTimeMs).append(',')
            .append(syncGrade).append(',')
            .append(cameraFrameNumber).append(',')
            .append(cameraShutterMonoNs).append(',')
            .append(cameraExposureTimeNs).append(',')
            .append(cameraIso).append(',')
            .append(imuSequenceId).append(',')
            .append(imuMonoNs).append(',')
            .append(imuDeliveryNs).append(',')
            .append(deltaCamImuNs).append(',')
            .append(deltaCamImuMs).append(',')
            .append(accelX).append(',').append(accelY).append(',').append(accelZ).append(',')
            .append(linAccelX).append(',').append(linAccelY).append(',').append(linAccelZ).append(',')
            .append(gyroX).append(',').append(gyroY).append(',').append(gyroZ).append(',')
            .append(quatW).append(',').append(quatX).append(',').append(quatY).append(',').append(quatZ).append(',')
            .append(pitchDeg).append(',').append(rollDeg).append(',').append(yawDeg).append(',')
            .append(if (isRadarActive) "CONNECTED" else "DISCONNECTED").append(',')
            .append(radarFrameNumber).append(',')
            .append(radarHostMonoNs).append(',')
            .append(deltaCamRadarNs).append(',')
            .append(deltaCamRadarMs).append(',')
            .append(radarNumDetectedObj).append(',')
            .append(radarNumTLVs).append(',')
            .append(radarSubFrameNumber).append(',')
            .append(if (hasGnssFix) "FIX" else "NO_FIX").append(',')
            .append(gnssUtcTimeMs).append(',')
            .append(gnssLatitude).append(',')
            .append(gnssLongitude).append(',')
            .append(gnssAltitudeMeters).append(',')
            .append(gnssSpeedMps).append(',')
            .append(gnssBearingDegrees).append(',')
            .append(gnssAccuracyMeters).append('\n')
        return sb.toString()
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
