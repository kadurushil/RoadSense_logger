package com.bajajauto.roadsense.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuSamplingBenchmarkTest {

    @Test
    fun testImuRatePresets() {
        assertEquals(0, ImuRatePreset.FASTEST.delayUs)
        assertEquals(10000, ImuRatePreset.RATE_100HZ.delayUs)
        assertEquals(20000, ImuRatePreset.RATE_50HZ.delayUs)
        assertEquals(50000, ImuRatePreset.RATE_20HZ.delayUs)

        assertEquals(100f, ImuRatePreset.RATE_100HZ.targetHz, 0.01f)
        assertEquals(50f, ImuRatePreset.RATE_50HZ.targetHz, 0.01f)
    }

    @Test
    fun testImuSamplingBenchmarkCalculations() {
        val benchmark = ImuSamplingBenchmark(
            sensorName = "LSM6DSL Accelerometer",
            sensorType = 1,
            preset = ImuRatePreset.RATE_100HZ,
            measuredHz = 99.8f,
            totalSamples = 500L,
            durationMs = 5010L,
            meanIntervalMs = 10.02f,
            minIntervalMs = 9.85f,
            maxIntervalMs = 10.25f,
            jitterStdDevMs = 0.08f,
            isRunning = true
        )

        assertEquals(5.01f, benchmark.durationSec, 0.01f)
        assertEquals("±0.08 ms", benchmark.jitterFormatted)
        assertEquals("9.9 / 10.3 ms", benchmark.intervalRangeFormatted)
        assertTrue(benchmark.isRunning)
    }
}
