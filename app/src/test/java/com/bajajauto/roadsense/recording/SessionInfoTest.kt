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
        sessionInfo.totalRadarSalvagedFrames = 2L
        sessionInfo.totalRadarSequenceDrops = 1L
        sessionInfo.peakThermalSeverity = "MODERATE"
        sessionInfo.peakBatteryTempC = 41.5f
        sessionInfo.totalEventBookmarks = 4
        sessionInfo.recordedWarnings = mutableListOf("Radar USB detached mid-drive")
        sessionInfo.sessionTags = SessionTags(
            vehicleId = "Dominar-Test-02",
            dspFirmwareBuild = "v3.2-can",
            mountType = "Handlebar",
            lightingCondition = "Dusk",
            notes = "Highway cut-in validation"
        )

        val jsonStr = sessionInfo.toJson()
        assertNotNull(jsonStr)

        val parsed = JSONObject(jsonStr)
        assertEquals("session_20260909_120000", parsed.getString("sessionId"))
        assertEquals(1788935400000L, parsed.getLong("startTimeWallMs"))
        assertEquals(10000L, parsed.getLong("durationMs"))
        assertEquals(5000000000L, parsed.getLong("startTimeMonotonicNs"))
        assertEquals(15000000000L, parsed.getLong("stopTimeMonotonicNs"))
        assertEquals(4, parsed.getInt("bookmarksCount"))

        val tagsObj = parsed.getJSONObject("sessionTags")
        assertEquals("Dominar-Test-02", tagsObj.getString("vehicleId"))
        assertEquals("v3.2-can", tagsObj.getString("dspFirmwareBuild"))
        assertEquals("Handlebar", tagsObj.getString("mountType"))
        assertEquals("Dusk", tagsObj.getString("lightingCondition"))
        assertEquals("Highway cut-in validation", tagsObj.getString("notes"))

        val radarObj = parsed.getJSONObject("radar")
        assertEquals(100L, radarObj.getLong("totalFrames"))
        assertEquals(50000L, radarObj.getLong("totalBytes"))
        assertEquals(2L, radarObj.getLong("salvagedFrames"))
        assertEquals(1L, radarObj.getLong("sequenceDrops"))
        assertEquals("TI AWR1843BOOST", radarObj.getString("sensor"))

        val healthObj = parsed.getJSONObject("systemHealth")
        assertEquals("MODERATE", healthObj.getString("peakThermalSeverity"))
        assertEquals(false, healthObj.getBoolean("terminatedByCrash"))
        assertEquals(1, healthObj.getJSONArray("warnings").length())
        assertEquals("Radar USB detached mid-drive", healthObj.getJSONArray("warnings").getString(0))

        val streams = parsed.getJSONArray("activeStreams")
        assertTrue(streams.length() >= 2)
    }
}
