package com.bajajauto.roadsense.imu

import org.junit.Assert.assertEquals
import org.junit.Test

class ImuRateCalculationTest {

    @Test
    fun primaryImuRateMatches200HzAccelRecordingStream() {
        // 100 accel events over 500 ms == 200.0 Hz (never deflated to 150 Hz by 100 Hz synthetic HAL sensors)
        val hz = ImuManager.computePrimaryImuRateHz(primaryEventCount = 100L, durationNs = 500_000_000L)
        assertEquals(200.0f, hz, 0.01f)
    }

    @Test
    fun zeroDurationOrEventsSafelyReturnsZeroHz() {
        assertEquals(0.0f, ImuManager.computePrimaryImuRateHz(0L, 500_000_000L), 0.001f)
        assertEquals(0.0f, ImuManager.computePrimaryImuRateHz(100L, 0L), 0.001f)
        assertEquals(0.0f, ImuManager.computePrimaryImuRateHz(100L, -10L), 0.001f)
    }
}
