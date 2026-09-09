package com.bajajauto.roadsense.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameMetadataTest {

    @Test
    fun testCameraFrameCsvSerialization() {
        val meta = CameraFrameMetadata(
            frameNumber = 105L,
            shutterTimestampNs = 89823713656620L,
            exposureTimeNs = 15000000L,
            iso = 200,
            utcTimestampMs = 1788936008000L
        )

        val csvRow = meta.toCsvRow()
        assertTrue(csvRow.startsWith("105,89823713656620,15000000,200,1788936008000"))
        assertTrue(csvRow.endsWith("\n"))
        assertEquals("frame_number,shutter_monotonic_ns,exposure_time_ns,iso,utc_time_ms,utc_iso\n", CameraFrameMetadata.CSV_HEADER)
    }

    @Test
    fun testCameraResolutionDefaults() {
        assertEquals(640, CameraResolution.RES_480P.width)
        assertEquals(480, CameraResolution.RES_480P.height)
        assertEquals(1280, CameraResolution.RES_720P.width)
        assertEquals(720, CameraResolution.RES_720P.height)
        assertEquals(1920, CameraResolution.RES_1080P.width)
        assertEquals(1080, CameraResolution.RES_1080P.height)
    }
}
