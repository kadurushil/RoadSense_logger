package com.bajajauto.roadsense.imu

import android.hardware.Sensor
import android.os.Build

/**
 * Categorization of Android hardware and synthetic sensors for automotive data logging.
 */
enum class SensorCategory(val displayName: String) {
    MOTION("Motion & Dynamics"),
    ORIENTATION("Orientation & Attitude"),
    UNCALIBRATED("Uncalibrated Raw"),
    AUXILIARY("Auxiliary & Environmental")
}

/**
 * Hardware specifications and capabilities of a discovered device sensor.
 */
data class ImuSensorCapability(
    val sensorType: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val powerMa: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val maxRateHz: Float,
    val minRateHz: Float,
    val category: SensorCategory,
    val fifoMaxEventCount: Int,
    val fifoReservedEventCount: Int,
    val stringType: String
) {
    val rateRangeFormatted: String
        get() {
            return if (maxRateHz > 0f) {
                if (minRateHz > 0f && minRateHz != maxRateHz) {
                    "%.1f – %.1f Hz".format(minRateHz, maxRateHz)
                } else {
                    "Up to %.1f Hz".format(maxRateHz)
                }
            } else {
                "On-Change / Event"
            }
        }

    val powerFormatted: String
        get() = "%.2f mA".format(powerMa)

    val resolutionFormatted: String
        get() = "%.6f".format(resolution)

    val isContinuous: Boolean
        get() = minDelayUs > 0

    companion object {
        fun fromSensor(sensor: Sensor): ImuSensorCapability {
            val minDelayUs = sensor.minDelay
            val maxDelayUs = sensor.maxDelay

            val maxRateHz = if (minDelayUs > 0) 1_000_000f / minDelayUs else 0f
            val minRateHz = if (maxDelayUs > 0) 1_000_000f / maxDelayUs else if (minDelayUs > 0) maxRateHz else 0f

            val category = when (sensor.type) {
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GRAVITY -> SensorCategory.MOTION

                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                @Suppress("DEPRECATION")
                Sensor.TYPE_ORIENTATION -> SensorCategory.ORIENTATION

                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> SensorCategory.UNCALIBRATED

                else -> SensorCategory.AUXILIARY
            }

            val stringType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                sensor.stringType ?: "type_${sensor.type}"
            } else {
                "type_${sensor.type}"
            }

            return ImuSensorCapability(
                sensorType = sensor.type,
                name = sensor.name ?: "Unknown Sensor",
                vendor = sensor.vendor ?: "Unknown Vendor",
                version = sensor.version,
                maxRange = sensor.maximumRange,
                resolution = sensor.resolution,
                powerMa = sensor.power,
                minDelayUs = minDelayUs,
                maxDelayUs = maxDelayUs,
                maxRateHz = maxRateHz,
                minRateHz = minRateHz,
                category = category,
                fifoMaxEventCount = sensor.fifoMaxEventCount,
                fifoReservedEventCount = sensor.fifoReservedEventCount,
                stringType = stringType
            )
        }
    }
}
