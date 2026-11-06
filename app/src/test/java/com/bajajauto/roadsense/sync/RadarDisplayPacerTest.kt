package com.bajajauto.roadsense.sync

import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RadarDisplayPacerTest {

    private fun createDummyFrame(frameNumber: Long): RadarFrame {
        val header = RadarHeader(
            version = 1L,
            totalPacketLen = 100,
            platform = 1L,
            frameNumber = frameNumber,
            timeCpuCycles = frameNumber * 30_000_000L,
            numDetectedObj = 0,
            numTLVs = 0,
            subFrameNumber = 0
        )
        val raw = RawRadarPacket(
            header = header,
            payload = byteArrayOf(),
            hostTimestampNs = frameNumber * 50_000_000L
        )
        return RadarFrame(
            header = header,
            points = emptyList(),
            tracks = emptyList(),
            clusters = emptyList(),
            diagnostics = null,
            canInputs = null,
            canOutputs = null,
            versionInfo = null,
            rawPacket = raw
        )
    }

    @Test
    fun testPacerAbsorbsBimodalJitterAndDispatchesSequentially() {
        val pacer = RadarDisplayPacer(
            frameIntervalMs = 50L,
            bufferDelayMs = 50L,
            maxQueueDepth = 2
        )

        // At t=0ms: Frame 1 arrives
        pacer.enqueueFrame(createDummyFrame(1L))
        // Before 50ms buffer delay, latestFrame must still be null
        assertNull("Frame must not emit immediately (buffering)", pacer.latestFrame.value)

        // Wait 70ms: 50ms buffer delay has elapsed, Frame 1 should be dispatched!
        Thread.sleep(70L)
        assertEquals(1L, pacer.latestFrame.value?.header?.frameNumber)

        // Simulate bimodal USB arrival: Frame 2 arrives (delayed by CP2105) and Frame 3 arrives quickly after
        pacer.enqueueFrame(createDummyFrame(2L))
        pacer.enqueueFrame(createDummyFrame(3L))

        // Wait another 55ms (total ~125ms from start): Frame 2 should be emitted at constant cadence
        Thread.sleep(55L)
        assertEquals(2L, pacer.latestFrame.value?.header?.frameNumber)

        // Wait another 55ms (total ~180ms from start): Frame 3 should be emitted
        Thread.sleep(55L)
        assertEquals(3L, pacer.latestFrame.value?.header?.frameNumber)

        pacer.reset()
    }

    @Test
    fun testMaxQueueDepthPruningPreventsLatencyCreep() {
        val pacer = RadarDisplayPacer(
            frameIntervalMs = 50L,
            bufferDelayMs = 50L,
            maxQueueDepth = 2
        )

        // Simulate a huge thread stall where 5 frames arrive during a UI freeze
        pacer.enqueueFrame(createDummyFrame(10L))
        pacer.enqueueFrame(createDummyFrame(11L))
        pacer.enqueueFrame(createDummyFrame(12L))
        pacer.enqueueFrame(createDummyFrame(13L))
        pacer.enqueueFrame(createDummyFrame(14L))

        // Wait for buffer delay + first tick
        Thread.sleep(70L)

        // Old frames (10, 11, 12) must have been pruned to keep queue depth <= 2
        val emitted = pacer.latestFrame.value
        assertNotNull("A frame should be emitted", emitted)
        assertTrue("Stale frames must be pruned to avoid latency creep", (emitted?.header?.frameNumber ?: 0L) >= 13L)

        pacer.reset()
    }

    @Test
    fun testResetClearsStateAndEmitsNull() {
        val pacer = RadarDisplayPacer(
            frameIntervalMs = 50L,
            bufferDelayMs = 50L,
            maxQueueDepth = 2
        )

        pacer.enqueueFrame(createDummyFrame(100L))
        Thread.sleep(70L)
        assertEquals(100L, pacer.latestFrame.value?.header?.frameNumber)

        pacer.reset()
        assertNull("Reset must clear latest frame to null", pacer.latestFrame.value)
    }

    @Test
    fun testSynchronousStepMethodForDeterministicValidation() {
        val pacer = RadarDisplayPacer(maxQueueDepth = 2)

        val f1 = createDummyFrame(1L)
        val f2 = createDummyFrame(2L)
        val f3 = createDummyFrame(3L)

        pacer.step(f1)
        assertEquals(1L, pacer.latestFrame.value?.header?.frameNumber)

        pacer.step(f2)
        assertEquals(2L, pacer.latestFrame.value?.header?.frameNumber)

        pacer.step(f3)
        assertEquals(3L, pacer.latestFrame.value?.header?.frameNumber)

        pacer.reset()
        assertNull(pacer.latestFrame.value)
    }

    @Test
    fun testReleaseCancelsScopeAndCleansUp() {
        val pacer = RadarDisplayPacer(maxQueueDepth = 6)
        pacer.enqueueFrame(createDummyFrame(1L))
        pacer.release()
        assertNull("Release must clear latest frame", pacer.latestFrame.value)
    }
}
