package com.bajajauto.roadsense.ui.components.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BevProjectionMathTest {

    @Test
    fun computeMetricsRespectsBug33SixtyDegreeFovFitAcrossViewports() {
        val landscape = BevProjectionMath.computeMetrics(1200f, 800f, 100f)
        assertTrue(landscape.isValid)
        assertEquals(600f, landscape.originX, 1e-3f)
        assertEquals(772f, landscape.originY, 1e-3f)
        assertEquals(756f, landscape.usableHeight, 1e-3f)

        // Full ±60° lateral extent at maxRangeMeters must remain inside halfWidth (588 px)
        val halfWidth = 600f - 12f
        val lateralPxAtMaxRange = 100f * BevProjectionMath.SIN_60_DEG * landscape.scalePxPerMeter
        assertTrue("±60° FOV must fit horizontally ($lateralPxAtMaxRange <= $halfWidth)", lateralPxAtMaxRange <= halfWidth + 1e-3f)
        assertEquals(halfWidth / (100f * BevProjectionMath.SIN_60_DEG), landscape.scalePxPerMeter, 1e-4f)

        // Tall narrow SBS half-width pane is width-constrained
        val sbsHalf = BevProjectionMath.computeMetrics(640f, 900f, 60f)
        val sbsHalfWidth = 320f - 12f
        assertEquals(sbsHalfWidth / (60f * BevProjectionMath.SIN_60_DEG), sbsHalf.scalePxPerMeter, 1e-4f)
        assertEquals(15f, sbsHalf.ringStepMeters, 1e-4f)

        // Wide short pane is height-constrained
        val wideShort = BevProjectionMath.computeMetrics(1600f, 400f, 30f)
        assertEquals((400f - 28f - 16f) / 30f, wideShort.scalePxPerMeter, 1e-4f)
        assertEquals(10f, wideShort.ringStepMeters, 1e-4f)
    }

    @Test
    fun computeRingStepMatchesAllRangeScales() {
        assertEquals(5f, BevProjectionMath.computeRingStep(15f), 1e-4f)
        assertEquals(10f, BevProjectionMath.computeRingStep(30f), 1e-4f)
        assertEquals(15f, BevProjectionMath.computeRingStep(60f), 1e-4f)
        assertEquals(25f, BevProjectionMath.computeRingStep(100f), 1e-4f)
        assertEquals(25f, BevProjectionMath.computeRingStep(150f), 1e-4f)
    }

    @Test
    fun invalidViewportDimensionsProduceSafeNonValidMetrics() {
        assertFalse(BevProjectionMath.computeMetrics(0f, 800f, 100f).isValid)
        assertFalse(BevProjectionMath.computeMetrics(1200f, 0f, 100f).isValid)
        assertFalse(BevProjectionMath.computeMetrics(1200f, 800f, 0f).isValid)
    }

    @Test
    fun orthoMvpMatrixMapsPixelCornersAndOriginAccuratelyToNdc() {
        val m = FloatArray(16)
        val w = 1000f
        val h = 500f
        BevProjectionMath.buildOrthoMvpMatrix(w, h, m)

        fun transform(x: Float, y: Float): Pair<Float, Float> {
            val nx = m[0] * x + m[4] * y + m[12]
            val ny = m[1] * x + m[5] * y + m[13]
            return nx to ny
        }

        val (tlX, tlY) = transform(0f, 0f)
        assertEquals(-1f, tlX, 1e-5f)
        assertEquals(1f, tlY, 1e-5f)

        val (brX, brY) = transform(w, h)
        assertEquals(1f, brX, 1e-5f)
        assertEquals(-1f, brY, 1e-5f)

        val (cX, cY) = transform(w / 2f, h / 2f)
        assertEquals(0f, cX, 1e-5f)
        assertEquals(0f, cY, 1e-5f)
    }
}
