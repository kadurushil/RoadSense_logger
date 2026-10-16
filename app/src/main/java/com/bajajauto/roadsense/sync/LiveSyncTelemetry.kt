package com.bajajauto.roadsense.sync

/**
 * Real-time synchronization telemetry exposed to UI and diagnostic monitors.
 */
data class LiveSyncTelemetry(
    val isCameraActive: Boolean = false,
    val isImuActive: Boolean = false,
    val isRadarActive: Boolean = false,
    val camToImuDeltaMs: Float = 0.0f,
    val camToImuMaxMs: Float = 0.0f,
    val radarToCamDeltaMs: Float = 0.0f,
    val radarToCamMaxMs: Float = 0.0f,
    val liveGrade: String = "IDLE",
    val totalKeyframes: Long = 0L,
    val cameraDropCount: Long = 0L,
    val imuJitterCount: Long = 0L,
    val radarDropCount: Long = 0L,
    val lastUpdateMonoNs: Long = 0L
) {
    val isFullySynchronized: Boolean
        get() = liveGrade == "GOLD" || liveGrade == "SILVER"

    val statusSummary: String
        get() = when {
            !isCameraActive && !isImuActive && !isRadarActive -> "IDLE"
            isCameraActive && isImuActive && !isRadarActive -> "SYNC: ${"%.1f".format(camToImuDeltaMs)} ms (Cam-IMU)"
            isCameraActive && isImuActive && isRadarActive -> "SYNC: ${"%.1f".format(camToImuDeltaMs)} ms"
            else -> "SYNC: ACTIVE"
        }
}
