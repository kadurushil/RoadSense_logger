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
    fun testCanedgeDeviceUrls() {
        val dev = CanedgeDevice(deviceId = "7AC5E17F", ipAddress = "192.168.43.100")
        assertEquals("http://192.168.43.100:80/", dev.baseUrl)
        assertEquals("http://192.168.43.100:80/api/", dev.apiBaseUrl)
    }
}
