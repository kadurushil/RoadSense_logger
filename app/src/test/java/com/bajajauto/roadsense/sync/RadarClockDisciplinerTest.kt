package com.bajajauto.roadsense.sync

import com.bajajauto.roadsense.models.RadarHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarClockDisciplinerTest {

    private fun createHeader(frameNumber: Long, cycles: Long): RadarHeader {
        return RadarHeader(
            version = 0x01090000L,
            totalPacketLen = 512,
            platform = 0xA1843L,
            frameNumber = frameNumber,
            timeCpuCycles = cycles,
            numDetectedObj = 5,
            numTLVs = 2,
            subFrameNumber = 0
        )
    }

    @Test
    fun testJitterEliminationOnPairedUsbBulkSplits() {
        val discipliner = RadarClockDiscipliner()

        // Radar fires every 50 ms = 30,000,000 DSP cycles at 600 MHz
        val dspCyclesPerFrame = 30_000_000L
        val baseMonoNs = 1_000_000_000L
        val baseWallMs = 1_700_000_000_000L

        // Simulated USB batching: frames arrive in alternating pairs (71ms, 29ms)
        // Frame 0: 0 ms
        // Frame 1: 71 ms (delayed due to 64-byte boundary bulk waiting)
        // Frame 2: 100 ms (delivered immediately after)
        // Frame 3: 171 ms
        // Frame 4: 200 ms
        val usbArrivalOffsetsMs = listOf(0L, 71L, 100L, 171L, 200L)

        val disciplinedTimestamps = mutableListOf<RadarClockDiscipliner.DisciplinedTimestamp>()

        for (i in usbArrivalOffsetsMs.indices) {
            val cycles = i * dspCyclesPerFrame
            val header = createHeader(frameNumber = i.toLong(), cycles = cycles)
            val usbMonoNs = baseMonoNs + (usbArrivalOffsetsMs[i] * 1_000_000L)
            val usbWallMs = baseWallMs + usbArrivalOffsetsMs[i]

            val disciplined = discipliner.discipline(header, usbMonoNs, usbWallMs)
            disciplinedTimestamps.add(disciplined)
        }

        // Verify anchor frame
        assertTrue(disciplinedTimestamps[0].isAnchor)
        assertEquals(baseMonoNs, disciplinedTimestamps[0].monotonicNs)
        assertEquals(baseWallMs, disciplinedTimestamps[0].wallClockMs)

        // Verify jitter is completely eliminated: each subsequent frame advances by exactly 50 ms (50,000,000 ns)
        for (i in 1 until disciplinedTimestamps.size) {
            val deltaNs = disciplinedTimestamps[i].monotonicNs - disciplinedTimestamps[i - 1].monotonicNs
            val deltaMs = (disciplinedTimestamps[i].monotonicNs - disciplinedTimestamps[0].monotonicNs) / 1_000_000L
            val expectedMs = i * 50L

            assertEquals("Delta T for frame $i must be exactly 50 ms", 50_000_000L, deltaNs)
            assertEquals("Elapsed time for frame $i must be $expectedMs ms", expectedMs, deltaMs)
        }
    }

    @Test
    fun test32BitUnsignedRolloverUnwrapping() {
        val discipliner = RadarClockDiscipliner()

        val baseMonoNs = 5_000_000_000L
        val baseWallMs = 1_700_000_050_000L
        val dspCyclesPerFrame = 30_000_000L

        // Position Frame 0 near the end of 32-bit unsigned range (2^32 - 40,000,000)
        val cycleNearMax = 4_294_967_296L - 40_000_000L // 4,254,967,296L
        val h0 = createHeader(frameNumber = 100L, cycles = cycleNearMax)
        val t0 = discipliner.discipline(h0, baseMonoNs, baseWallMs)

        // Frame 1: Still before wrap (+30M -> 4,284,967,296L)
        val cyclePreWrap = cycleNearMax + dspCyclesPerFrame
        val h1 = createHeader(frameNumber = 101L, cycles = cyclePreWrap)
        val t1 = discipliner.discipline(h1, baseMonoNs + 50_000_000L, baseWallMs + 50)
        assertEquals(50_000_000L, t1.monotonicNs - t0.monotonicNs)

        // Frame 2: Rolled over across 2^32! (4,284,967,296 + 30,000,000 - 4,294,967,296 = 20,000,000L)
        val cyclePostWrap = 20_000_000L
        val h2 = createHeader(frameNumber = 102L, cycles = cyclePostWrap)
        val t2 = discipliner.discipline(h2, baseMonoNs + 100_000_000L, baseWallMs + 100)
        assertEquals("Delta across 32-bit rollover must remain perfectly 50 ms", 50_000_000L, t2.monotonicNs - t1.monotonicNs)

        // Frame 3: Normal progression after wrap
        val cyclePostWrap2 = cyclePostWrap + dspCyclesPerFrame
        val h3 = createHeader(frameNumber = 103L, cycles = cyclePostWrap2)
        val t3 = discipliner.discipline(h3, baseMonoNs + 150_000_000L, baseWallMs + 150)
        assertEquals(50_000_000L, t3.monotonicNs - t2.monotonicNs)
    }

    @Test
    fun testDroppedFramePreservesTimelineContinuity() {
        val discipliner = RadarClockDiscipliner()

        val baseMonoNs = 10_000_000_000L
        val baseWallMs = 1_700_000_000_000L
        val dspCyclesPerFrame = 30_000_000L

        // Frame 1
        val h1 = createHeader(frameNumber = 1L, cycles = 100_000_000L)
        val t1 = discipliner.discipline(h1, baseMonoNs, baseWallMs)

        // Frame 2
        val h2 = createHeader(frameNumber = 2L, cycles = 130_000_000L)
        val t2 = discipliner.discipline(h2, baseMonoNs + 50_000_000L, baseWallMs + 50)
        assertEquals(50_000_000L, t2.monotonicNs - t1.monotonicNs)

        // Frame 3 is dropped! Next is Frame 4 (+60,000,000 cycles)
        val h4 = createHeader(frameNumber = 4L, cycles = 190_000_000L)
        val t4 = discipliner.discipline(h4, baseMonoNs + 150_000_000L, baseWallMs + 150)
        assertEquals("Delta over 1 dropped frame must be exactly 100 ms", 100_000_000L, t4.monotonicNs - t2.monotonicNs)
    }

    @Test
    fun testRebootSequenceGapDetection() {
        val discipliner = RadarClockDiscipliner()

        // Stream running normally
        val h10 = createHeader(frameNumber = 10L, cycles = 300_000_000L)
        val t10 = discipliner.discipline(h10, 1_000_000_000L, 1_700_000_000_000L)
        assertTrue(t10.isAnchor)

        // Radar power cycle: sequence resets to 0 and cycles reset to 5,000
        val hReset = createHeader(frameNumber = 0L, cycles = 5_000L)
        val tReset = discipliner.discipline(hReset, 5_000_000_000L, 1_700_000_004_000L)
        assertTrue("Reboot (sequence reset) must trigger new anchor", tReset.isAnchor)
        assertEquals(5_000_000_000L, tReset.monotonicNs)

        // Huge sequence jump (> 50 frames gap)
        val hJump = createHeader(frameNumber = 100L, cycles = 10_000_000L)
        val tJump = discipliner.discipline(hJump, 10_000_000_000L, 1_700_000_009_000L)
        assertTrue("Sequence gap > 50 must trigger new anchor", tJump.isAnchor)
    }

    @Test
    fun testAdaptiveCrystalDriftTracking() {
        val discipliner = RadarClockDiscipliner()

        val baseMonoNs = 1_000_000_000L
        val baseWallMs = 1_700_000_000_000L
        val dspCyclesPerFrame = 30_000_000L // 50 ms at nominal 600 MHz

        // Simulate 200 frames (~10 seconds) with a slightly fast host clock (or DSP running at 598 MHz)
        // True alpha ~ 1.672 ns/cycle instead of nominal 1.666667 ns/cycle
        val actualNsPerFrame = 50_160_000L // ~3200 ppm difference for testing drift adaptation

        var lastDisciplined: RadarClockDiscipliner.DisciplinedTimestamp? = null
        for (i in 0..200) {
            val cycles = i * dspCyclesPerFrame
            val header = createHeader(frameNumber = i.toLong(), cycles = cycles)
            val hostMonoNs = baseMonoNs + (i * actualNsPerFrame)
            val hostWallMs = baseWallMs + (i * actualNsPerFrame / 1_000_000L)

            lastDisciplined = discipliner.discipline(header, hostMonoNs, hostWallMs)
        }

        // After 10s (200 frames), discipliner should have tracked the drift without diverging
        val finalDisciplined = lastDisciplined!!
        val finalHostNs = baseMonoNs + (200 * actualNsPerFrame)
        val diffNs = kotlin.math.abs(finalDisciplined.monotonicNs - finalHostNs)

        // Difference must be kept well bounded (< 50 ms over 10 seconds of severe drift)
        assertTrue("Disciplined clock must track long-term crystal drift. Diff: ${diffNs / 1_000_000} ms", diffNs < 50_000_000L)
    }
}

