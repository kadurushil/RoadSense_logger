package com.bajajauto.roadsense.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter

class ImuRingBufferTest {

    @Test
    fun testEnqueueAndDrain() {
        val ring = ImuRingBuffer(capacity = 8)
        assertEquals(0, ring.availableToRead())
        assertEquals(0L, ring.droppedFrames)

        // Enqueue 3 items
        assertTrue(ring.enqueue(100L, 105L, 1000L, 0.1f, 0.2f, 9.8f, 0.01f, 0.02f, 0.03f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
        assertTrue(ring.enqueue(200L, 205L, 1010L, 0.2f, 0.3f, 9.7f, 0.02f, 0.03f, 0.04f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
        assertTrue(ring.enqueue(300L, 305L, 1020L, 0.3f, 0.4f, 9.6f, 0.03f, 0.04f, 0.05f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))

        assertEquals(3, ring.availableToRead())

        val sw = StringWriter()
        val bw = BufferedWriter(sw)
        val sb = StringBuilder()

        val writtenSlugs = mutableListOf<Long>()
        val drainedCount = ring.drainTo(bw, sb, maxBatch = 10) { slot, tail ->
            writtenSlugs.add(slot.elapsedRealtimeNs)
        }
        bw.flush()

        assertEquals(3, drainedCount)
        assertEquals(0, ring.availableToRead())
        assertEquals(listOf(100L, 200L, 300L), writtenSlugs)

        val csvLines = sw.toString().trim().split("\n")
        assertEquals(3, csvLines.size)
        assertTrue(csvLines[0].startsWith("100,1000,0.10000,0.20000,9.80000"))
        assertTrue(csvLines[1].startsWith("200,1010,0.20000,0.30000,9.70000"))
        assertTrue(csvLines[2].startsWith("300,1020,0.30000,0.40000,9.60000"))
    }

    @Test
    fun testCapacityOverflowAndDropCounting() {
        val ring = ImuRingBuffer(capacity = 4)

        // Fill to capacity
        for (i in 1..4) {
            assertTrue(ring.enqueue(i * 100L, 0L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
        }
        assertEquals(4, ring.availableToRead())
        assertEquals(0L, ring.droppedFrames)

        // 5th enqueue must fail and increment droppedFrames
        assertFalse(ring.enqueue(500L, 0L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
        assertEquals(1L, ring.droppedFrames)
        assertEquals(4, ring.availableToRead())

        // Drain 2 items
        val sw = StringWriter()
        val bw = BufferedWriter(sw)
        val sb = StringBuilder()
        val drained = ring.drainTo(bw, sb, maxBatch = 2) { _, _ -> }
        assertEquals(2, drained)
        assertEquals(2, ring.availableToRead())

        // Now enqueue should succeed again
        assertTrue(ring.enqueue(600L, 0L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
        assertEquals(3, ring.availableToRead())
    }

    @Test
    fun testWrapAroundCircularIndexing() {
        val ring = ImuRingBuffer(capacity = 4)
        val sw = StringWriter()
        val bw = BufferedWriter(sw)
        val sb = StringBuilder()

        // Push and drain multiple cycles to verify head/tail wrap past capacity
        for (cycle in 0 until 50) {
            for (i in 0 until 3) {
                val stamp = (cycle * 3 + i).toLong()
                assertTrue(ring.enqueue(stamp, 0L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
            }
            val drained = ring.drainTo(bw, sb, maxBatch = 3) { _, _ -> }
            assertEquals(3, drained)
            assertEquals(0, ring.availableToRead())
        }
        assertEquals(0L, ring.droppedFrames)
    }
}
