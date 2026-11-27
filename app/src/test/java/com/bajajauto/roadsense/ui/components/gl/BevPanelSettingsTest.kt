package com.bajajauto.roadsense.ui.components.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BevPanelSettingsTest {

    @Test
    fun autoColorModeResolvesPerViewAndExplicitModeAppliesAcrossBoth() {
        assertEquals(BevPointColorMode.VELOCITY, BevPointColorMode.AUTO.resolveForView(is3d = false))
        assertEquals(BevPointColorMode.RANGE, BevPointColorMode.AUTO.resolveForView(is3d = true))

        for (explicit in listOf(
            BevPointColorMode.VELOCITY,
            BevPointColorMode.RANGE,
            BevPointColorMode.SNR,
            BevPointColorMode.ELEVATION_Z,
            BevPointColorMode.OUTLIER,
            BevPointColorMode.CLUSTER_ID
        )) {
            assertEquals(explicit, explicit.resolveForView(is3d = false))
            assertEquals(explicit, explicit.resolveForView(is3d = true))
        }
    }

    @Test
    fun smartSnrBoundsAdaptCleanlyBelow5DbAndAbove35DbWithSpanFloor() {
        // 1. Points below 5 dB & above 35 dB -> expands immediately to observed bounds
        val (minWide, maxWide) = BevPanelColorizer.computeSmartSnrBounds(
            observedMinDb = -3.0f,
            observedMaxDb = 62.0f,
            prevMinDb = BevPanelColorizer.DEFAULT_SNR_MIN_DB,
            prevMaxDb = BevPanelColorizer.DEFAULT_SNR_MAX_DB,
            hasPrev = false
        )
        assertEquals(-3.0f, minWide, 1e-4f)
        assertEquals(62.0f, maxWide, 1e-4f)

        val lowScalar = BevPanelColorizer.normalizeScalar(
            resolvedMode = BevPointColorMode.SNR,
            forwardY = 10f, z = 0f, dopplerMps = 0f, snrDb = -3.0f,
            clusterId = 0, isOutlier = false, maxRangeM = 100f,
            snrMinDb = minWide, snrMaxDb = maxWide
        )
        val midScalar = BevPanelColorizer.normalizeScalar(
            resolvedMode = BevPointColorMode.SNR,
            forwardY = 10f, z = 0f, dopplerMps = 0f, snrDb = 29.5f,
            clusterId = 0, isOutlier = false, maxRangeM = 100f,
            snrMinDb = minWide, snrMaxDb = maxWide
        )
        val highScalar = BevPanelColorizer.normalizeScalar(
            resolvedMode = BevPointColorMode.SNR,
            forwardY = 10f, z = 0f, dopplerMps = 0f, snrDb = 62.0f,
            clusterId = 0, isOutlier = false, maxRangeM = 100f,
            snrMinDb = minWide, snrMaxDb = maxWide
        )
        assertEquals(0.0f, lowScalar, 1e-4f)
        assertEquals(0.5f, midScalar, 1e-4f)
        assertEquals(1.0f, highScalar, 1e-4f)

        // 2. Narrow SNR cluster around 48..50 dB (well outside 5..35 dB) -> enforces >= 10 dB span floor
        val (minFloor, maxFloor) = BevPanelColorizer.computeSmartSnrBounds(
            observedMinDb = 48.0f,
            observedMaxDb = 50.0f,
            prevMinDb = 0f,
            prevMaxDb = 0f,
            hasPrev = false
        )
        assertEquals(BevPanelColorizer.MIN_SNR_SPAN_DB, maxFloor - minFloor, 1e-4f)
        assertEquals(44.0f, minFloor, 1e-4f)
        assertEquals(54.0f, maxFloor, 1e-4f)
    }

    @Test
    fun outlierModeAndFoxgloveTurboProduceDistinctFiniteRgb() {
        val rgbIn = FloatArray(3)
        val rgbOut = FloatArray(3)
        val sIn = BevPanelColorizer.normalizeScalar(
            BevPointColorMode.OUTLIER, 10f, 0f, 0f, 20f, 0, isOutlier = false, 100f, 5f, 35f
        )
        val sOut = BevPanelColorizer.normalizeScalar(
            BevPointColorMode.OUTLIER, 10f, 0f, 0f, 20f, 0, isOutlier = true, 100f, 5f, 35f
        )
        assertEquals(0f, sIn, 1e-4f)
        assertEquals(1f, sOut, 1e-4f)

        BevPanelColorizer.evalPointRgb(BevPanelColorizer.SCHEME_OUTLIER, sIn, rgbIn)
        BevPanelColorizer.evalPointRgb(BevPanelColorizer.SCHEME_OUTLIER, sOut, rgbOut)
        assertTrue("Inlier green-teal channel must exceed outlier", rgbIn[1] > rgbOut[1] + 0.3f)
        assertTrue("Outlier red channel must exceed inlier", rgbOut[0] > rgbIn[0] + 0.5f)

        val turboLow = FloatArray(3)
        val turboHigh = FloatArray(3)
        BevPanelColorizer.evalFoxgloveTurbo(0.0f, turboLow)
        BevPanelColorizer.evalFoxgloveTurbo(1.0f, turboHigh)
        for (c in 0..2) {
            assertTrue(turboLow[c] in 0f..1f)
            assertTrue(turboHigh[c] in 0f..1f)
        }
        assertTrue("Turbo 1.0 should be redder than Turbo 0.0", turboHigh[0] > turboLow[0])
        assertFalse(BevPointColorMode.OUTLIER.usesColormap(true))
        assertTrue(BevPointColorMode.SNR.usesColormap(false))

        // Wide -20m..+20m elevation band & cluster 0/255 noise sentinels
        assertEquals(0.0f, BevPanelColorizer.normalizeScalar(BevPointColorMode.ELEVATION_Z, 10f, -20f, 0f, 20f, 0, false, 100f, 5f, 35f), 1e-4f)
        assertEquals(0.5f, BevPanelColorizer.normalizeScalar(BevPointColorMode.ELEVATION_Z, 10f, 0f, 0f, 20f, 0, false, 100f, 5f, 35f), 1e-4f)
        assertEquals(1.0f, BevPanelColorizer.normalizeScalar(BevPointColorMode.ELEVATION_Z, 10f, 20f, 0f, 20f, 0, false, 100f, 5f, 35f), 1e-4f)
        assertEquals(0.0f, BevPanelColorizer.normalizeScalar(BevPointColorMode.CLUSTER_ID, 10f, 0f, 0f, 20f, 255, false, 100f, 5f, 35f), 1e-4f)
        assertEquals(9, BevPanelColorizer.evalColorMapStopsArgb(BevColorMap.TURBO, 9).size)
    }
}
