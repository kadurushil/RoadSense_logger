package com.bajajauto.roadsense.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssFixTest {

    @Test
    fun testGnssFixPropertiesAndCsvFormatting() {
        val fix = GnssFix(
            latitude = 18.5204303,
            longitude = 73.8567437,
            altitudeMeters = 560.5,
            speedMps = 10.0f,
            bearingDegrees = 180.0f,
            accuracyMeters = 1.8f,
            elapsedRealtimeNs = 123456789012345L,
            utcTimeMs = 1788935400000L,
            provider = "gps",
            satellitesInView = 14,
            satellitesUsed = 10
        )

        // Validate speed conversion (10 m/s -> 36.0 km/h)
        assertEquals(36.0f, fix.speedKmh, 0.01f)

        val csv = fix.toCsvRow()
        assertTrue(csv.startsWith("123456789012345,1788935400000,"))
        assertTrue(csv.contains("18.5204303,73.8567437,560.50,10.00,36.00,180.0,1.8,gps,10/14"))
        assertTrue(csv.endsWith("\n"))
    }
}
