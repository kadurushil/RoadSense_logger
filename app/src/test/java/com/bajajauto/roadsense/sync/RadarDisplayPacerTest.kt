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

    @Test
    fun testSingleFrameCushionDoesNotCauseFalseDrops() {
        val pacer = RadarDisplayPacer(maxQueueDepth = 3)

        // Seed 1 cushion frame + 1 frame arriving every tick -> qDepth stays 1, must never drop
        pacer.enqueueForTesting(createDummyFrame(1L))
        for (seq in 2L..11L) {
            val emitted = pacer.step(createDummyFrame(seq))
            assertEquals(seq - 1L, emitted?.header?.frameNumber)
            assertEquals(1, pacer.getBufferedCount())
        }
        assertEquals("Healthy 1-frame cushion must suffer zero bloat drops", 0L, pacer.getTotalDroppedFrames())
        pacer.release()
    }

    @Test
    fun testSustainedBufferbloatSelfHealsWithoutPermanentLag() {
        val pacer = RadarDisplayPacer(maxQueueDepth = 3)

        // Seed 3 frames on tick 1 -> emits #1, leaves #2 & #3 buffered (2-frame multi-frame backlog)
        pacer.enqueueForTesting(createDummyFrame(1L))
        pacer.enqueueForTesting(createDummyFrame(2L))
        assertEquals(1L, pacer.step(createDummyFrame(3L))?.header?.frameNumber)
        assertEquals(2, pacer.getBufferedCount())

        // Sustained 2-frame backlog across ticks 2..4
        assertEquals(2L, pacer.step(createDummyFrame(4L))?.header?.frameNumber)
        assertEquals(2, pacer.getBufferedCount())

        assertEquals(3L, pacer.step(createDummyFrame(5L))?.header?.frameNumber)
        assertEquals(2, pacer.getBufferedCount())

        assertEquals(4L, pacer.step(createDummyFrame(6L))?.header?.frameNumber)
        assertEquals(2, pacer.getBufferedCount())

        // Tick 5: sustainedBacklogTicks == 4 and queue.size == 3 (#5, #6, #7) -> drops older #5, emits #6, leaves #7 (depth 1)
        val healed = pacer.step(createDummyFrame(7L))
        assertEquals("Should skip stale #5 and advance to #6", 6L, healed?.header?.frameNumber)
        assertEquals("Buffered backlog must shrink from 2 down to 1", 1, pacer.getBufferedCount())
        assertEquals(1L, pacer.getTotalDroppedFrames())

        pacer.release()
    }

    @Test
    fun testHealthyBimodalJitterProducesZeroFalseDrops() {
        val pacer = RadarDisplayPacer(maxQueueDepth = 2)

        var seq = 1L
        val emitted = mutableListOf<Long>()
        repeat(10) {
            // Burst cycle: 2 frames arrive together before tick -> tick 1 emits first (#1 left in queue), tick 2 drains empty
            pacer.enqueueForTesting(createDummyFrame(seq++))
            pacer.step(createDummyFrame(seq++))?.let { emitted.add(it.header.frameNumber) }
            pacer.step(null)?.let { emitted.add(it.header.frameNumber) }
        }

        assertEquals("Every bimodal frame 1..20 must emit in order", (1L..20L).toList(), emitted)
        assertEquals("Zero false drops under bimodal jitter", 0L, pacer.getTotalDroppedFrames())
        pacer.release()
    }

    @Test
    fun testDefaultPacerRunsDedicatedExecutorWithShallowDepth() {
        val pacer = RadarDisplayPacer()
        assertEquals(3, pacer.maxQueueDepth)

        for (seq in 1L..6L) {
            pacer.enqueueFrame(createDummyFrame(seq))
        }

        Thread.sleep(90L)
        val emitted = pacer.latestFrame.value?.header?.frameNumber ?: 0L
        assertTrue("Expected freshest burst frame (>= 4), got $emitted", emitted >= 4L)
        assertTrue("Buffered count must stay bounded <= 2, got ${pacer.getBufferedCount()}", pacer.getBufferedCount() <= 2)
        assertTrue(pacer.getTotalDroppedFrames() >= 3L)
        pacer.release()
    }
}
