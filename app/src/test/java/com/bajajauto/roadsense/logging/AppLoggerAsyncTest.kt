package com.bajajauto.roadsense.logging

import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.sync.RadarDisplayPacer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppLoggerAsyncTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        DebugToggles.resetDefaults()
        AppLogger.resetForTesting()
        tempDir = Files.createTempDirectory("applogger_test").toFile()
    }

    @After
    fun tearDown() {
        AppLogger.resetForTesting()
        DebugToggles.resetDefaults()
        tempDir.deleteRecursively()
    }

    @Test
    fun multiThreadedLoggingFlushesCompleteSessionWithoutDrops() {
        val logFile = File(tempDir, "session_debug.log")
        AppLogger.attachSessionFileForTesting(logFile)

        val threadCount = 8
        val logsPerThread = 250
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            Thread {
                startGate.await()
                for (i in 0 until logsPerThread) {
                    AppLogger.d("Worker$t", "msg-$t-$i")
                }
                doneGate.countDown()
            }.start()
        }

        startGate.countDown()
        assertTrue("Producers did not finish quickly", doneGate.await(5, TimeUnit.SECONDS))

        AppLogger.detachSession()

        val lines = logFile.readLines().filter { it.contains("msg-") }
        assertEquals("All async log lines must flush on detachSession()", threadCount * logsPerThread, lines.size)
        assertEquals(0L, AppLogger.getDroppedLogCount())
    }

    @Test
    fun queueOverflowTracksDropsWithoutBlockingCallers() {
        val tempFile = File(tempDir, "burst_debug.log")
        AppLogger.attachSessionFileForTesting(tempFile)

        val totalSent = 10_000
        val startNs = System.nanoTime()
        for (i in 0 until totalSent) {
            AppLogger.d("BurstTag", "burst-$i")
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("Caller threads must stay non-blocking (took ${elapsedMs}ms)", elapsedMs < 2000.0)

        AppLogger.flushAndSync()
        AppLogger.detachSession()

        val writtenCount = tempFile.readLines().count { it.contains("[BurstTag]") }
        val droppedCount = AppLogger.getDroppedLogCount().toInt()
        assertEquals("Every message must either be written or accounted for as dropped", totalSent, writtenCount + droppedCount)
    }

    @Test
    fun displayPacerVerboseSpamIsSuppressedByMasterToggle() {
        val logFile = File(tempDir, "pacer_gate.log")
        AppLogger.attachSessionFileForTesting(logFile)

        val pacer = RadarDisplayPacer(frameIntervalMs = 50L, bufferDelayMs = 0L, maxQueueDepth = 2)
        val header = RadarHeader(1L, 64, 1L, 42L, 100L, 0, 0, 0)
        val frame = RadarFrame(
            header = header,
            points = emptyList(),
            tracks = emptyList(),
            clusters = emptyList(),
            diagnostics = null,
            canInputs = null,
            canOutputs = null,
            versionInfo = null,
            rawPacket = RawRadarPacket(header, byteArrayOf(), 1000L)
        )

        // Default: masterDebugEnabled = false -> no [ENQUEUE] logs emitted
        pacer.step(frame)
        AppLogger.flushAndSync()
        assertFalse(logFile.readText().contains("[ENQUEUE]"))

        // Enable master bench logging -> [ENQUEUE] logs emit
        DebugToggles.setBenchTimingLogging(true)
        pacer.enqueueFrame(frame)
        AppLogger.flushAndSync()
        assertTrue(logFile.readText().contains("[ENQUEUE] seq=#42"))

        pacer.release()
        AppLogger.detachSession()
    }
}

