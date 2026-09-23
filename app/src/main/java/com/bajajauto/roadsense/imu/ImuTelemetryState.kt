package com.bajajauto.roadsense.imu

/**
 * Conflated telemetry snapshot throttled at ~25 Hz for smooth, low-overhead Compose UI rendering.
 */
data class ImuTelemetryState(
    val rawAccel: FloatArray = floatArrayOf(0f, 0f, 0f, 0f), // X, Y, Z, Magnitude (m/s²)
    val linearAccel: FloatArray = floatArrayOf(0f, 0f, 0f, 0f), // X, Y, Z, Magnitude without g (m/s²)
    val gyroRates: FloatArray = floatArrayOf(0f, 0f, 0f), // wx, wy, wz in deg/s
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val isMonitoring: Boolean = false,
    val lastUpdateMonoNs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImuTelemetryState
        if (!rawAccel.contentEquals(other.rawAccel)) return false
        if (!linearAccel.contentEquals(other.linearAccel)) return false
        if (!gyroRates.contentEquals(other.gyroRates)) return false
        if (pitchDeg != other.pitchDeg) return false
        if (rollDeg != other.rollDeg) return false
        if (yawDeg != other.yawDeg) return false
        if (isMonitoring != other.isMonitoring) return false
        if (lastUpdateMonoNs != other.lastUpdateMonoNs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = rawAccel.contentHashCode()
        result = 31 * result + linearAccel.contentHashCode()
        result = 31 * result + gyroRates.contentHashCode()
        result = 31 * result + pitchDeg.hashCode()
        result = 31 * result + rollDeg.hashCode()
        result = 31 * result + yawDeg.hashCode()
        result = 31 * result + isMonitoring.hashCode()
        result = 31 * result + lastUpdateMonoNs.hashCode()
        return result
    }
}
