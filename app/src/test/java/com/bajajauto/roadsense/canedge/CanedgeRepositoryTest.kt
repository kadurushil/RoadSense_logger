package com.bajajauto.roadsense.canedge

import com.bajajauto.roadsense.canedge.model.CanedgeDevice
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import com.bajajauto.roadsense.canedge.network.CanedgeHttpClient
import com.bajajauto.roadsense.canedge.repository.CanedgeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanedgeRepositoryTest {

    private val device = CanedgeDevice(
        deviceId = "7AC5E17F",
        ipAddress = "192.168.43.50"
    )

    @Test
    fun testFileStabilityEvaluation() {
        val repo = CanedgeRepository(CanedgeHttpClient())

        val nowSec = System.currentTimeMillis() / 1000L

        // File 1: Old file (60 seconds ago)
        val oldFile = CanedgeFile(
            name = "00000001.MF4",
            path = "/00000001/00000001.MF4",
            isDirectory = false,
            sizeBytes = 25600L,
            lastWrittenEpochSec = nowSec - 60
        )

        // File 2: Brand new active file (2 seconds ago)
        val activeFile = CanedgeFile(
            name = "00000002.MF4",
            path = "/00000001/00000002.MF4",
            isDirectory = false,
            sizeBytes = 4096L,
            lastWrittenEpochSec = nowSec - 2
        )

        val allFiles = listOf(oldFile, activeFile)

        // Old file must be stable (it is > 12s old AND has a newer sibling)
        assertTrue("Old file should be deemed stable", repo.isFileStable(oldFile, allFiles))

        // Active file should NOT be stable (it is only 2s old and has no newer sibling)
        assertFalse("Brand new file should not be deemed stable", repo.isFileStable(activeFile, allFiles))
    }

    @Test
    fun testClockSkewCompensationPreventsPrematureActiveDownload() {
        val repo = CanedgeRepository(CanedgeHttpClient())
        val nowMs = 1_795_000_100_000L
        val nowSec = nowMs / 1000L

        // Active chunk written 4s ago, but CANedge RTC lags by 40s (so raw file timestamp is nowSec - 44)
        val skewedActive = CanedgeFile(
            name = "00000005.MF4",
            path = "/LOG/7AC5E17F/00000117/00000005.MF4",
            isDirectory = false,
            sizeBytes = 65536L,
            lastWrittenEpochSec = nowSec - 44L
        )

        // First observation with +40s offset compensated must NOT treat it as finished
        assertFalse(
            "Skew-compensated 4s-old active chunk should not look finished on first poll",
            repo.isFileStable(skewedActive, listOf(skewedActive), estimatedClockOffsetMs = 40_000L, nowWallMs = nowMs)
        )

        // Second observation 1s later with same size is still too short (< 3s stability window)
        assertFalse(
            "Same size within <3s window should not yet confirm stability",
            repo.isFileStable(skewedActive, listOf(skewedActive), estimatedClockOffsetMs = 40_000L, nowWallMs = nowMs + 1_000L)
        )

        // Third observation 3.5s after first poll confirms unchanged size -> safe!
        assertTrue(
            "Same non-zero size after >=3s interval should confirm stability",
            repo.isFileStable(skewedActive, listOf(skewedActive), estimatedClockOffsetMs = 40_000L, nowWallMs = nowMs + 3_500L)
        )
    }

    @Test
    fun testCanedgeDeviceUrls() {
        val dev = CanedgeDevice(deviceId = "7AC5E17F", ipAddress = "192.168.43.100")
        assertEquals("http://192.168.43.100:80/", dev.baseUrl)
        assertEquals("http://192.168.43.100:80/api/", dev.apiBaseUrl)
    }
}
