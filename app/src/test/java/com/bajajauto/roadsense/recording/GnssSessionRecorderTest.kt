package com.bajajauto.roadsense.recording

import com.bajajauto.roadsense.gnss.GnssFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GnssSessionRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGnssCsvRecordingAndTimelineEvents() {
        val sessionDir = tempFolder.newFolder("session_test")
        val radarDir = File(sessionDir, "radar").apply { mkdirs() }
        val gnssDir = File(sessionDir, "gnss").apply { mkdirs() }

        val sessionInfo = SessionInfo(
            sessionId = "session_test",
            sessionDir = sessionDir,
            radarDir = radarDir,
            gnssDir = gnssDir,
            startTimeWallMs = 1788935400000L,
            startTimeMonotonicNs = 1000000000L
        )

        val timelineWriter = SessionTimelineWriter()
        timelineWriter.start(sessionDir)

        val recorder = GnssSessionRecorder()
        recorder.startRecording(sessionInfo)

        val fix1 = GnssFix(
            latitude = 18.5204303,
            longitude = 73.8567437,
            altitudeMeters = 560.5,
            speedMps = 12.5f,
            bearingDegrees = 180.0f,
            accuracyMeters = 2.5f,
            elapsedRealtimeNs = 1050000000L,
            utcTimeMs = 1788935400050L,
            provider = "gps",
            satellitesInView = 14,
            satellitesUsed = 9
        )

        recorder.recordFix(fix1)
        Thread.sleep(100) // allow executor to write

        recorder.stopRecording()
        timelineWriter.stop()
        Thread.sleep(100)

        // Verify CSV file exists and has content
        val csvFile = File(gnssDir, "gnss_fixes.csv")
        assertTrue("CSV file should exist", csvFile.exists())
        val lines = csvFile.readLines()
        assertTrue("Should have header and at least 1 record", lines.size >= 2)
        assertEquals(GnssFix.CSV_HEADER.trim(), lines[0])
        assertTrue("Line should contain latitude", lines[1].contains("18.5204303"))
        assertTrue("Line should contain speed km/h", lines[1].contains("45.00"))
        assertTrue("Line should contain satellites used/inView", lines[1].contains("9/14"))
    }
}
