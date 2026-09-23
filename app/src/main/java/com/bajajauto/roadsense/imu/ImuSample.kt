package com.bajajauto.roadsense.imu

import android.hardware.Sensor
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Nanosecond-synchronized sample from an onboard IMU or motion sensor.
 * Aligns directly with Android SystemClock.elapsedRealtimeNanos() for zero-drift cross-sensor fusion.
 */
data class ImuSample(
    val elapsedRealtimeNs: Long,
    val wallTimeMs: Long,
    val sensorType: Int,
    val values: FloatArray,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
) {
    val x: Float get() = if (values.isNotEmpty()) values[0] else 0f
    val y: Float get() = if (values.size > 1) values[1] else 0f
    val z: Float get() = if (values.size > 2) values[2] else 0f

    val magnitude: Float
        get() = sqrt(x * x + y * y + z * z)

    // Quaternion components if sensorType is a rotation vector
    val qx: Float get() = if (values.isNotEmpty()) values[0] else 0f
    val qy: Float get() = if (values.size > 1) values[1] else 0f
    val qz: Float get() = if (values.size > 2) values[2] else 0f
    val qw: Float
        get() {
            return if (values.size >= 4) {
                values[3]
            } else {
                // If 4th component omitted by HAL, reconstruct: qw = sqrt(1 - (qx² + qy² + qz²))
                val sumSq = qx * qx + qy * qy + qz * qz
                if (sumSq < 1f) sqrt(1f - sumSq) else 0f
            }
        }

    /**
     * Converts rotation vector quaternion to vehicle attitude Euler angles (Pitch, Roll, Yaw) in degrees.
     */
    fun toEulerAnglesDeg(): FloatArray {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        // Check if quaternion has valid length
        return try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            floatArrayOf(
                Math.toDegrees(orientationAngles[1].toDouble()).toFloat(), // Pitch
                Math.toDegrees(orientationAngles[2].toDouble()).toFloat(), // Roll
                Math.toDegrees(orientationAngles[0].toDouble()).toFloat()  // Yaw / Azimuth
            )
        } catch (e: Exception) {
            floatArrayOf(0f, 0f, 0f)
        }
    }

    val pitchDeg: Float get() = toEulerAnglesDeg()[0]
    val rollDeg: Float get() = toEulerAnglesDeg()[1]
    val yawDeg: Float get() = toEulerAnglesDeg()[2]

    fun toCsvRow(): String {
        val v0 = x
        val v1 = y
        val v2 = z
        val v3 = if (values.size > 3) values[3] else 0f
        return "$elapsedRealtimeNs,$wallTimeMs,$sensorType,%.5f,%.5f,%.5f,%.5f,$accuracy\n".format(
            v0, v1, v2, v3
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImuSample
        if (elapsedRealtimeNs != other.elapsedRealtimeNs) return false
        if (wallTimeMs != other.wallTimeMs) return false
        if (sensorType != other.sensorType) return false
        if (!values.contentEquals(other.values)) return false
        if (accuracy != other.accuracy) return false
        return true
    }

    override fun hashCode(): Int {
        var result = elapsedRealtimeNs.hashCode()
        result = 31 * result + wallTimeMs.hashCode()
        result = 31 * result + sensorType
        result = 31 * result + values.contentHashCode()
        result = 31 * result + accuracy
        return result
    }

    companion object {
        const val CSV_HEADER = "elapsed_realtime_ns,wall_time_ms,sensor_type,val_0,val_1,val_2,val_3,accuracy\n"
    }
}
