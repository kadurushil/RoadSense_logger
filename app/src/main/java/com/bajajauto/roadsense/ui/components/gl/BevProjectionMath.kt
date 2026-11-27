package com.bajajauto.roadsense.ui.components.gl

/**
 * Pure-Kotlin coordinate projection & viewport geometry for the Radar BEV scope.
 * Shared identically by both OpenGL ES 3.0 (`RadarGlBevRenderer`) and Skia Canvas (`RadarBevCanvas`)
 * so world-to-pixel alignment (`Bug #33` ±60° FOV horizontal fit) never drifts between backends.
 */
data class BevViewportMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val originX: Float,
    val originY: Float,
    val usableHeight: Float,
    val scalePxPerMeter: Float,
    val maxRangeMeters: Float,
    val ringStepMeters: Float,
    val maxVisibleDistMeters: Float
) {
    val isValid: Boolean
        get() = widthPx > 0f && heightPx > 0f && scalePxPerMeter > 0f
}

object BevProjectionMath {
    const val SIN_60_DEG = 0.8660254f
    const val COS_60_DEG = 0.5f
    const val BOTTOM_MARGIN_PX = 28f
    const val TOP_MARGIN_PX = 16f
    const val SIDE_MARGIN_PX = 12f
    const val LATERAL_TICK_STEP_METERS = 5f
    const val LATERAL_TICK_MARGIN_PX = 20f
    const val LATERAL_TICK_HEIGHT_PX = 12f

    fun computeRingStep(maxRangeMeters: Float): Float = when (maxRangeMeters) {
        15f -> 5f
        30f -> 10f
        60f -> 15f
        else -> 25f
    }

    fun computeMetrics(
        widthPx: Float,
        heightPx: Float,
        maxRangeMeters: Float
    ): BevViewportMetrics {
        if (widthPx <= 0f || heightPx <= 0f || maxRangeMeters <= 0f) {
            return BevViewportMetrics(
                widthPx = widthPx.coerceAtLeast(0f),
                heightPx = heightPx.coerceAtLeast(0f),
                originX = 0f,
                originY = 0f,
                usableHeight = 0f,
                scalePxPerMeter = 0f,
                maxRangeMeters = maxRangeMeters.coerceAtLeast(1f),
                ringStepMeters = computeRingStep(maxRangeMeters),
                maxVisibleDistMeters = 0f
            )
        }
        val originX = widthPx / 2f
        val originY = heightPx - BOTTOM_MARGIN_PX
        val usableHeight = (originY - TOP_MARGIN_PX).coerceAtLeast(1f)
        val halfWidth = ((widthPx / 2f) - SIDE_MARGIN_PX).coerceAtLeast(1f)
        val maxLateralMeters = maxRangeMeters * SIN_60_DEG
        val scale = minOf(usableHeight / maxRangeMeters, halfWidth / maxLateralMeters)
        val ringStep = computeRingStep(maxRangeMeters)
        val maxVisibleDist = if (scale > 0f) (usableHeight / scale) + 0.01f else maxRangeMeters
        return BevViewportMetrics(
            widthPx = widthPx,
            heightPx = heightPx,
            originX = originX,
            originY = originY,
            usableHeight = usableHeight,
            scalePxPerMeter = scale,
            maxRangeMeters = maxRangeMeters,
            ringStepMeters = ringStep,
            maxVisibleDistMeters = maxVisibleDist
        )
    }

    fun worldToScreenX(xMeters: Float, metrics: BevViewportMetrics): Float =
        metrics.originX + xMeters * metrics.scalePxPerMeter

    fun worldToScreenY(yMeters: Float, metrics: BevViewportMetrics): Float =
        metrics.originY - yMeters * metrics.scalePxPerMeter

    /**
     * Populates a column-major 4x4 orthographic projection matrix mapping Android pixel coordinates
     * `(0..widthPx, 0..heightPx)` (top-left origin, Y-down) to OpenGL NDC `(-1..+1, +1..-1)`.
     */
    fun buildOrthoMvpMatrix(
        widthPx: Float,
        heightPx: Float,
        outMatrix16: FloatArray
    ) {
        require(outMatrix16.size >= 16) { "outMatrix16 must hold at least 16 floats" }
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        for (i in 0 until 16) outMatrix16[i] = 0f
        // Column 0
        outMatrix16[0] = 2f / w
        // Column 1
        outMatrix16[5] = -2f / h
        // Column 2
        outMatrix16[10] = -1f
        // Column 3 (translation)
        outMatrix16[12] = -1f
        outMatrix16[13] = 1f
        outMatrix16[15] = 1f
    }
}
