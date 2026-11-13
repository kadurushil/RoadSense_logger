package com.bajajauto.roadsense.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWindowStatsTest {

    @Test
    fun windowSummaryIncludesPerPhasePercentilesAndResets() {
        val stats = FrameWindowStats(capacity = 64)
        assertNull(stats.buildWindowSummaryAndReset())

        // Record 20 deterministic frames (1..20)
        for (i in 1..20) {
            stats.record(
                totalMs = i * 2.0f,       // 2..40 ms -> >16.67ms when i >= 9 (12 janky frames)
                layoutMs = i * 0.25f,     // 0.25..5.0 ms
                drawMs = i * 0.50f,       // 0.50..10.0 ms
                syncMs = i * 0.15f,       // 0.15..3.0 ms
                gpuMs = i * 0.40f         // 0.40..8.0 ms
            )
        }

        assertEquals(20, stats.frameCount)
        assertEquals(12, stats.jankCount)

        val summary = stats.buildWindowSummaryAndReset()
        assertNotNull(summary)
        summary!!

        assertTrue(summary.contains("frames=20"))
        assertTrue(summary.contains("jank=12 (60.0%)"))
        assertTrue(summary.contains("p50=22.00ms"))
        assertTrue(summary.contains("max=40.00ms"))
        assertTrue(summary.contains("drawP50=5.50ms"))
        assertTrue(summary.contains("drawP95=10.00ms"))
        assertTrue(summary.contains("layoutP95=5.00ms"))
        assertTrue(summary.contains("syncP95=3.00ms"))
        assertTrue(summary.contains("gpuP95=8.00ms"))

        // State resets cleanly
        assertEquals(0, stats.frameCount)
        assertEquals(0, stats.jankCount)
        assertNull(stats.buildWindowSummaryAndReset())
    }
}

