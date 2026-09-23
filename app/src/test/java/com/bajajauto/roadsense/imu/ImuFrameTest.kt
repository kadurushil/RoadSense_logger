package com.bajajauto.roadsense.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuFrameTest {

    @Test
    fun testImuFrameCsvSerialization() {
        val frame = ImuFrame(
            elapsedRealtimeNs = 123456789012345L,
            wallTimeMs = 1789105441000L,
            ax = 0.12345f,
            ay = -0.67890f,
            az = 9.80665f,
            gx = 0.01234f,
            gy = -0.05678f,
            gz = 0.00901f,
            qx = 0.00100f,
            qy = 0.00200f,
            qz = 0.00300f,
            qw = 0.99999f,
            linAx = 0.12000f,
            linAy = -0.67000f,
            linAz = 0.01000f
        )

        assertEquals(123456789012345L, frame.elapsedRealtimeNs)
        assertEquals(1789105441000L, frame.wallTimeMs)
        assertEquals(0.12345f, frame.ax, 0.0001f)
        assertEquals(-0.67890f, frame.ay, 0.0001f)
        assertEquals(9.80665f, frame.az, 0.0001f)
        assertEquals(0.01234f, frame.gx, 0.0001f)
        assertEquals(-0.05678f, frame.gy, 0.0001f)
        assertEquals(0.00901f, frame.gz, 0.0001f)
        assertEquals(0.99999f, frame.qw, 0.0001f)

        val csvRow = frame.toCsvRow()
        assertTrue("CSV row should end with newline", csvRow.endsWith("\n"))

        val parts = csvRow.trim().split(",")
        // Header: elapsed_realtime_ns,wall_time_ms,ax,ay,az,gx,gy,gz,qx,qy,qz,qw,lin_ax,lin_ay,lin_az -> 15 columns
        assertEquals(15, parts.size)
        assertEquals("123456789012345", parts[0])
        assertEquals("1789105441000", parts[1])
        assertEquals("0.12345", parts[2])
        assertEquals("-0.67890", parts[3])
        assertEquals("9.80665", parts[4])
        assertEquals("0.01234", parts[5])
        assertEquals("-0.05678", parts[6])
        assertEquals("0.00901", parts[7])
        assertEquals("0.00100", parts[8])
        assertEquals("0.00200", parts[9])
        assertEquals("0.00300", parts[10])
        assertEquals("0.99999", parts[11])
        assertEquals("0.12000", parts[12])
        assertEquals("-0.67000", parts[13])
        assertEquals("0.01000", parts[14])
    }

    @Test
    fun testImuFrameCsvHeader() {
        val header = ImuFrame.CSV_HEADER
        val columns = header.trim().split(",")
        assertEquals(15, columns.size)
        assertEquals("elapsed_realtime_ns", columns[0])
        assertEquals("wall_time_ms", columns[1])
        assertEquals("ax", columns[2])
        assertEquals("ay", columns[3])
        assertEquals("az", columns[4])
        assertEquals("gx", columns[5])
        assertEquals("gy", columns[6])
        assertEquals("gz", columns[7])
        assertEquals("qx", columns[8])
        assertEquals("qy", columns[9])
        assertEquals("qz", columns[10])
        assertEquals("qw", columns[11])
        assertEquals("lin_ax", columns[12])
        assertEquals("lin_ay", columns[13])
        assertEquals("lin_az", columns[14])
    }
}
