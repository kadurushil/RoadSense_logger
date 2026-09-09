package com.bajajauto.roadsense.recording

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionInfoTest {

    @Test
    fun testSessionInfoJsonSerialization() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_session")
        val radarDir = File(tempDir, "radar")
        val sessionInfo = SessionInfo(
            sessionId = "session_20260909_120000",
            sessionDir = tempDir,
            radarDir = radarDir,
            startTimeWallMs = 1788935400000L,
            startTimeMonotonicNs = 5000000000L
        )

        sessionInfo.stopTimeWallMs = 1788935410000L
        sessionInfo.stopTimeMonotonicNs = 15000000000L
        sessionInfo.totalRadarFrames = 100L
        sessionInfo.totalRadarBytes = 50000L

        val jsonStr = sessionInfo.toJson()
        assertNotNull(jsonStr)

        val parsed = JSONObject(jsonStr)
        assertEquals("session_20260909_120000", parsed.getString("sessionId"))
        assertEquals(1788935400000L, parsed.getLong("startTimeWallMs"))
        assertEquals(10000L, parsed.getLong("durationMs"))
        assertEquals(5000000000L, parsed.getLong("startTimeMonotonicNs"))
        assertEquals(15000000000L, parsed.getLong("stopTimeMonotonicNs"))

        val radarObj = parsed.getJSONObject("radar")
        assertEquals(100L, radarObj.getLong("totalFrames"))
        assertEquals(50000L, radarObj.getLong("totalBytes"))
        assertEquals("TI AWR1843BOOST", radarObj.getString("sensor"))

        val streams = parsed.getJSONArray("activeStreams")
        assertTrue(streams.length() >= 2)
    }
}
