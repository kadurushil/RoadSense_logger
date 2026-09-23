package com.bajajauto.roadsense.recording

import com.bajajauto.roadsense.imu.ImuFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImuSessionRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testImuSessionRecordingLifecycleAndCsvOutput() {
        val sessionDir = tempFolder.newFolder("session_test_imu")
        val radarDir = File(sessionDir, "radar").apply { mkdirs() }
        val imuDir = File(sessionDir, "imu").apply { mkdirs() }

        val sessionInfo = SessionInfo(
            sessionId = "session_test_imu",
            sessionDir = sessionDir,
            radarDir = radarDir,
            imuDir = imuDir,
            startTimeWallMs = 1788935400000L,
            startTimeMonotonicNs = 1000000000L
        )

        val recorder = ImuSessionRecorder()
        assertFalse(recorder.isRecording.value)
        assertEquals(0L, recorder.framesRecorded.value)

        recorder.startRecording(sessionInfo)
        assertTrue(recorder.isRecording.value)

        val frame1 = ImuFrame(
            elapsedRealtimeNs = 1000100000L,
            wallTimeMs = 1788935400100L,
            ax = 0.05f,
            ay = -1.25f,
            az = 9.78f,
            gx = 0.001f,
            gy = 0.002f,
            gz = -0.015f,
            qx = 0.0f,
            qy = 0.0f,
            qz = 0.1f,
            qw = 0.995f,
            linAx = 0.04f,
            linAy = -1.20f,
            linAz = 0.02f
        )

        val frame2 = ImuFrame(
            elapsedRealtimeNs = 1000200000L,
            wallTimeMs = 1788935400200L,
            ax = 0.08f,
            ay = -1.30f,
            az = 9.81f,
            gx = 0.002f,
            gy = 0.003f,
            gz = -0.018f,
            qx = 0.0f,
            qy = 0.0f,
            qz = 0.12f,
            qw = 0.993f,
            linAx = 0.07f,
            linAy = -1.25f,
            linAz = 0.05f
        )

        recorder.recordFrame(frame1)
        recorder.recordFrame(frame2)
        Thread.sleep(150) // Allow background worker to execute write

        assertEquals(2L, recorder.framesRecorded.value)

        val stoppedFrames = recorder.stopRecording()
        assertEquals(2L, stoppedFrames)
        assertFalse(recorder.isRecording.value)
        Thread.sleep(150) // Allow writer to flush & close

        // Verify CSV file exists and contains the correct formatted rows
        val csvFile = File(imuDir, "imu_frames.csv")
        assertTrue("imu_frames.csv should exist", csvFile.exists())

        val lines = csvFile.readLines()
        assertEquals("Should have header + 2 frame lines", 3, lines.size)
        assertEquals(ImuFrame.CSV_HEADER.trim(), lines[0])

        // Verify frame 1 content
        val parts1 = lines[1].split(",")
        assertEquals(15, parts1.size)
        assertEquals("1000100000", parts1[0])
        assertEquals("1788935400100", parts1[1])
        assertEquals("0.05000", parts1[2])
        assertEquals("-1.25000", parts1[3])
        assertEquals("9.78000", parts1[4])

        // Verify frame 2 content
        val parts2 = lines[2].split(",")
        assertEquals("1000200000", parts2[0])
        assertEquals("-1.30000", parts2[3])
    }
}
