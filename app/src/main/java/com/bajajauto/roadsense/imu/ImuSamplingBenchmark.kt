package com.bajajauto.roadsense.imu

/**
 * Standard delay presets for Android SensorManager registration.
 */
enum class ImuRatePreset(val label: String, val delayUs: Int, val targetHz: Float) {
    FASTEST("FASTEST (0 µs)", 0, 100f),
    RATE_100HZ("100 Hz (10,000 µs)", 10000, 100f),
    RATE_50HZ("50 Hz (20,000 µs)", 20000, 50f),
    RATE_20HZ("20 Hz (50,000 µs)", 50000, 20f)
}

/**
 * Statistical state and performance metrics from an empirical sensor sampling rate benchmark.
 */
data class ImuSamplingBenchmark(
    val sensorName: String = "",
    val sensorType: Int = 0,
    val preset: ImuRatePreset = ImuRatePreset.RATE_100HZ,
    val measuredHz: Float = 0f,
    val totalSamples: Long = 0L,
    val durationMs: Long = 0L,
    val meanIntervalMs: Float = 0f,
    val minIntervalMs: Float = 0f,
    val maxIntervalMs: Float = 0f,
    val jitterStdDevMs: Float = 0f,
    val isRunning: Boolean = false
) {
    val durationSec: Float get() = durationMs / 1000f

    val jitterFormatted: String
        get() = "±%.2f ms".format(jitterStdDevMs)

    val intervalRangeFormatted: String
        get() = "%.1f / %.1f ms".format(minIntervalMs, maxIntervalMs)
}
