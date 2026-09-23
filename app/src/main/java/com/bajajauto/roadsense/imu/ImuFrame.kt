package com.bajajauto.roadsense.imu

import java.util.Locale

/**
 * Consolidated 100 Hz synchronized IMU frame optimized for direct MCAP / Foxglove export
 * (conforming to ROS sensor_msgs/msg/Imu).
 *
 * @param elapsedRealtimeNs Monotonic timestamp aligned with SystemClock.elapsedRealtimeNanos()
 * @param wallTimeMs Epoch UTC timestamp in milliseconds
 * @param ax Linear acceleration X including gravity (m/s²)
 * @param ay Linear acceleration Y including gravity (m/s²)
 * @param az Linear acceleration Z including gravity (m/s²)
 * @param gx Angular velocity X / Roll rate (rad/s)
 * @param gy Angular velocity Y / Pitch rate (rad/s)
 * @param gz Angular velocity Z / Yaw rate (rad/s)
 * @param qx 6-DOF attitude quaternion X (remapped to vehicle frame)
 * @param qy 6-DOF attitude quaternion Y (remapped to vehicle frame)
 * @param qz 6-DOF attitude quaternion Z (remapped to vehicle frame)
 * @param qw 6-DOF attitude quaternion W (remapped to vehicle frame)
 * @param linAx Dynamic linear acceleration X without gravity (m/s²)
 * @param linAy Dynamic linear acceleration Y without gravity (m/s²)
 * @param linAz Dynamic linear acceleration Z without gravity (m/s²)
 */
data class ImuFrame(
    val elapsedRealtimeNs: Long,
    val wallTimeMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val linAx: Float = 0f,
    val linAy: Float = 0f,
    val linAz: Float = 0f
) {
    companion object {
        const val CSV_HEADER = "elapsed_realtime_ns,wall_time_ms,ax,ay,az,gx,gy,gz,qx,qy,qz,qw,lin_ax,lin_ay,lin_az\n"
    }

    /**
     * Serializes frame to a single CSV row formatted for fast streaming and direct MCAP conversion.
     */
    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%d,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f\n",
            elapsedRealtimeNs, wallTimeMs,
            ax, ay, az,
            gx, gy, gz,
            qx, qy, qz, qw,
            linAx, linAy, linAz
        )
    }
}
