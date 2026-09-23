package com.bajajauto.roadsense.imu

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ImuSampleTest {

    @Test
    fun testImuSampleVectorCalculations() {
        val sample = ImuSample(
            elapsedRealtimeNs = 123456789000000L,
            wallTimeMs = 1789105441000L,
            sensorType = Sensor.TYPE_ACCELEROMETER,
            values = floatArrayOf(0.0f, 0.0f, 9.81f),
            accuracy = 3
        )

        assertEquals(0.0f, sample.x, 0.001f)
        assertEquals(0.0f, sample.y, 0.001f)
        assertEquals(9.81f, sample.z, 0.001f)
        assertEquals(9.81f, sample.magnitude, 0.001f)

        val csv = sample.toCsvRow()
        assertTrue(csv.startsWith("123456789000000,1789105441000,1,"))
        assertTrue(csv.contains("0.00000,0.00000,9.81000,0.00000,3"))
        assertTrue(csv.endsWith("\n"))
    }

    @Test
    fun testLinearAccelerationWithoutGravity() {
        // Dynamic acceleration during vehicle braking: -3.5 m/s² longitudinal
        val linearSample = ImuSample(
            elapsedRealtimeNs = 123456789000000L,
            wallTimeMs = 1789105441000L,
            sensorType = Sensor.TYPE_LINEAR_ACCELERATION,
            values = floatArrayOf(0.1f, -3.5f, 0.05f),
            accuracy = 3
        )

        assertEquals(0.1f, linearSample.x, 0.001f)
        assertEquals(-3.5f, linearSample.y, 0.001f)
        val expectedMag = sqrt(0.1f * 0.1f + (-3.5f) * (-3.5f) + 0.05f * 0.05f)
        assertEquals(expectedMag, linearSample.magnitude, 0.01f)
    }

    @Test
    fun testQuaternionReconstruction() {
        // Test quaternion with 4 components
        val quatSample = ImuSample(
            elapsedRealtimeNs = 1000L,
            wallTimeMs = 2000L,
            sensorType = Sensor.TYPE_GAME_ROTATION_VECTOR,
            values = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9274f)
        )
        assertEquals(0.1f, quatSample.qx, 0.001f)
        assertEquals(0.2f, quatSample.qy, 0.001f)
        assertEquals(0.3f, quatSample.qz, 0.001f)
        assertEquals(0.9274f, quatSample.qw, 0.001f)

        // Test quaternion with 3 components (qw reconstructed)
        val quat3Sample = ImuSample(
            elapsedRealtimeNs = 1000L,
            wallTimeMs = 2000L,
            sensorType = Sensor.TYPE_GAME_ROTATION_VECTOR,
            values = floatArrayOf(0.0f, 0.6f, 0.0f)
        )
        // qw = sqrt(1 - 0.36) = 0.8
        assertEquals(0.8f, quat3Sample.qw, 0.001f)
    }
}
