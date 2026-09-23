package com.bajajauto.roadsense.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuSensorCapabilityTest {

    @Test
    fun testImuSensorCapabilityPropertiesAndFormatting() {
        val cap = ImuSensorCapability(
            sensorType = 1,
            name = "LSM6DSL Accelerometer",
            vendor = "STM",
            version = 1,
            maxRange = 78.4f,
            resolution = 0.00239f,
            powerMa = 0.25f,
            minDelayUs = 10000,
            maxDelayUs = 200000,
            maxRateHz = 100f,
            minRateHz = 5f,
            category = SensorCategory.MOTION,
            fifoMaxEventCount = 0,
            fifoReservedEventCount = 0,
            stringType = "android.sensor.accelerometer"
        )

        assertEquals("LSM6DSL Accelerometer", cap.name)
        assertEquals("STM", cap.vendor)
        assertEquals(SensorCategory.MOTION, cap.category)
        assertTrue(cap.isContinuous)
        assertEquals("5.0 – 100.0 Hz", cap.rateRangeFormatted)
        assertEquals("0.25 mA", cap.powerFormatted)
        assertEquals("0.002390", cap.resolutionFormatted)
    }

    @Test
    fun testOnChangeSensorFormatting() {
        val cap = ImuSensorCapability(
            sensorType = 27,
            name = "Screen Orientation",
            vendor = "Samsung",
            version = 3,
            maxRange = 3f,
            resolution = 1f,
            powerMa = 0f,
            minDelayUs = 0,
            maxDelayUs = 0,
            maxRateHz = 0f,
            minRateHz = 0f,
            category = SensorCategory.AUXILIARY,
            fifoMaxEventCount = 0,
            fifoReservedEventCount = 0,
            stringType = "type_27"
        )

        assertEquals("On-Change / Event", cap.rateRangeFormatted)
    }
}
