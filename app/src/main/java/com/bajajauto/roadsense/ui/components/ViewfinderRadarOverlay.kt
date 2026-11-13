package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.HudOverlayMode
import com.bajajauto.roadsense.fusion.engine.CameraIntrinsics
import com.bajajauto.roadsense.fusion.engine.ProjectedRadarLollipop
import com.bajajauto.roadsense.fusion.engine.RadarRangeOverlay
import com.bajajauto.roadsense.fusion.engine.SpatialProjectionEngine
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.models.RadarFrame
import java.util.Locale

private val HUD_COLOR_FOV = Color(0x3D4DD0E1)
private val HUD_COLOR_BORESIGHT = Color(0x5500E5FF)
private val HUD_COLOR_ARC = Color(0x6600E5FF)
private val HUD_COLOR_FCW = Color(0xFFFF1744)
private val HUD_COLOR_ACC = Color(0xFF00E5FF)
private val HUD_COLOR_TRACK = Color(0xFFFFD54F)
private val HUD_COLOR_CLOSING = Color(0xFFFF5252)
private val HUD_COLOR_OPENING = Color(0xFF69F0AE)
private val HUD_COLOR_STATIONARY = Color(0xFF40C4FF)
private val HUD_BG_FCW = Color(0xFF2E0007)
private val HUD_BG_ACC = Color(0xFF001E2B)

private class LollipopProjectionCache {
    var lastFrameRef: RadarFrame? = null
    var lastCalibration: CalibrationParameters? = null
    var lastIntrinsics: CameraIntrinsics? = null
    var lastWidth: Int = -1
    var lastHeight: Int = -1
    var lastHudMode: HudOverlayMode? = null
    var cachedLollipops: List<ProjectedRadarLollipop> = emptyList()
    var cachedRangeOverlay: RadarRangeOverlay? = null
}

private class OverlayDrawTimingState {
    var lastDrawStartNs: Long = 0L
}

/**
 * Renders 2D radar targets and translucent radar range lines (10m, 30m, 60m, 120m) with
 * boresight and ±60° FOV boundaries projected onto the camera viewfinder.
 *
 * Performance Hardening:
 * - Zero object allocations inside the 20 Hz Canvas draw loop (`reusablePath`, hoisted `Stroke`/`PathEffect`).
 * - Defaults to tracked objects (`TLV 3`) within 80m (`TRACKS_LOLLIPOP_80M` / `SIMPLE_BOXES_80M`),
 *   cutting draw primitives by >90% compared to raw point cloud overlays.
 * - Memoized `RadarRangeOverlay` and `ProjectedRadarLollipop` list across identical frame/calibration/mode states.
 */
@Composable
fun ViewfinderRadarOverlay(
    radarFrame: RadarFrame? = null,
    radarFrameProducer: (() -> RadarFrame?)? = null,
    calibrationParams: CalibrationParameters,
    intrinsics: CameraIntrinsics?,
    viewWidth: Int,
    viewHeight: Int,
    showRangeArcs: Boolean = true,
    hudOverlayMode: HudOverlayMode? = null,
    modifier: Modifier = Modifier
) {
    if (intrinsics == null || viewWidth <= 0 || viewHeight <= 0) return

    val debugState by DebugToggles.state.collectAsState()
    val effectiveHudMode = hudOverlayMode ?: debugState.hudOverlayMode
    val enableTimingLog = debugState.masterDebugEnabled && debugState.canvasTimingLogs

    val densityVal = LocalDensity.current.density
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(230, 128, 216, 255)
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
        }
    }
    val badgeTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
        }
    }
    val reusablePath = remember { Path() }
    val fovDash = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f) }
    val arcDash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f) }
    val defaultRanges = remember { listOf(10f, 30f, 60f, 120f) }
    val projectionCache = remember { LollipopProjectionCache() }
    val timingState = remember { OverlayDrawTimingState() }

    // Pre-allocated Stroke styles (avoiding per-frame/per-loop Stroke instantiation)
    val fovStroke = remember(densityVal, fovDash) { Stroke(width = 1.2f * densityVal, pathEffect = fovDash) }
    val boresightStroke = remember(densityVal, fovDash) { Stroke(width = 1.5f * densityVal, pathEffect = fovDash) }
    val arcStroke = remember(densityVal, arcDash) { Stroke(width = 1.5f * densityVal, pathEffect = arcDash) }
    val footprintPriorityStroke = remember(densityVal) { Stroke(width = 2.5f * densityVal) }
    val footprintTrackStroke = remember(densityVal) { Stroke(width = 2.0f * densityVal) }
    val footprintRawStroke = remember(densityVal) { Stroke(width = 1.2f * densityVal) }
    val fcwHaloStroke = remember(densityVal) { Stroke(width = 1.5f * densityVal) }
    val accHaloStroke = remember(densityVal) { Stroke(width = 1.2f * densityVal) }
    val badgePriorityStroke = remember(densityVal) { Stroke(width = 1.5f * densityVal) }
    val badgeNormalStroke = remember(densityVal) { Stroke(width = 1.0f * densityVal) }
    val boxPriorityStroke = remember(densityVal) { Stroke(width = 2.2f * densityVal) }
    val boxNormalStroke = remember(densityVal) { Stroke(width = 1.6f * densityVal) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
    ) {
        val drawStartNs = if (enableTimingLog) System.nanoTime() else 0L
        val activeFrame = radarFrameProducer?.invoke() ?: radarFrame
        if (activeFrame == null && !showRangeArcs) return@Canvas

        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@Canvas

        val geomChanged = projectionCache.lastCalibration != calibrationParams ||
            projectionCache.lastIntrinsics != intrinsics ||
            projectionCache.lastWidth != w ||
            projectionCache.lastHeight != h

        // 1. Draw Translucent Radar Range Arcs, Boresight, and FOV Boundary Lines
        if (showRangeArcs) {
            android.os.Trace.beginSection("RS_UI_DrawArcs")
            val rangeOverlay = if (!geomChanged && projectionCache.cachedRangeOverlay != null) {
                projectionCache.cachedRangeOverlay!!
            } else {
                SpatialProjectionEngine.calculateRadarRangeOverlay(
                    params = calibrationParams,
                    intrinsics = intrinsics,
                    viewWidth = w,
                    viewHeight = h,
                    ranges = defaultRanges
                ).also {
                    projectionCache.cachedRangeOverlay = it
                }
            }

            // Draw FOV Boundary Rays (±60° Azimuth)
            if (rangeOverlay.leftFovRay.size >= 2) {
                reusablePath.reset()
                reusablePath.moveTo(rangeOverlay.leftFovRay[0].xPx, rangeOverlay.leftFovRay[0].yPx)
                for (i in 1 until rangeOverlay.leftFovRay.size) {
                    reusablePath.lineTo(rangeOverlay.leftFovRay[i].xPx, rangeOverlay.leftFovRay[i].yPx)
                }
                drawPath(reusablePath, color = HUD_COLOR_FOV, style = fovStroke)
            }

            if (rangeOverlay.rightFovRay.size >= 2) {
                reusablePath.reset()
                reusablePath.moveTo(rangeOverlay.rightFovRay[0].xPx, rangeOverlay.rightFovRay[0].yPx)
                for (i in 1 until rangeOverlay.rightFovRay.size) {
                    reusablePath.lineTo(rangeOverlay.rightFovRay[i].xPx, rangeOverlay.rightFovRay[i].yPx)
                }
                drawPath(reusablePath, color = HUD_COLOR_FOV, style = fovStroke)
            }

            // Draw Radar Center Boresight Line (0° Azimuth extending till 120m)
            if (rangeOverlay.boresightRay.size >= 2) {
                reusablePath.reset()
                reusablePath.moveTo(rangeOverlay.boresightRay[0].xPx, rangeOverlay.boresightRay[0].yPx)
                for (i in 1 until rangeOverlay.boresightRay.size) {
                    reusablePath.lineTo(rangeOverlay.boresightRay[i].xPx, rangeOverlay.boresightRay[i].yPx)
                }
                drawPath(reusablePath, color = HUD_COLOR_BORESIGHT, style = boresightStroke)
            }

            // Draw Concentric Range Arcs (10m, 30m, 60m, 120m) and Distance Labels
            labelPaint.textSize = 8.5f * density

            for (arc in rangeOverlay.arcs) {
                if (arc.points.size >= 2) {
                    reusablePath.reset()
                    reusablePath.moveTo(arc.points[0].xPx, arc.points[0].yPx)
                    for (i in 1 until arc.points.size) {
                        reusablePath.lineTo(arc.points[i].xPx, arc.points[i].yPx)
                    }
                    drawPath(reusablePath, color = HUD_COLOR_ARC, style = arcStroke)
                }

                arc.labelPoint?.let { lp ->
                    drawContext.canvas.nativeCanvas.drawText(
                        arc.label,
                        lp.xPx + 6f * density,
                        lp.yPx - 3f * density,
                        labelPaint
                    )
                }
            }
            android.os.Trace.endSection()
        }

        // 2. Draw Radar Detections (Tracks <80m Lollipops, Simple Boxes <80m, or Legacy Raw Points <=80m)
        if (activeFrame != null) {
            android.os.Trace.beginSection("RS_UI_CalculateLollipops")
            val includeRawPoints = effectiveHudMode == HudOverlayMode.RAW_POINTS_LEGACY
            val lollipops = if (!geomChanged &&
                projectionCache.lastFrameRef === activeFrame &&
                projectionCache.lastHudMode == effectiveHudMode
            ) {
                projectionCache.cachedLollipops
            } else {
                SpatialProjectionEngine.calculateRadarLollipops(
                    radarFrame = activeFrame,
                    params = calibrationParams,
                    intrinsics = intrinsics,
                    viewWidth = w,
                    viewHeight = h,
                    filterUntrackedClutter = !includeRawPoints,
                    includeUntrackedPoints = includeRawPoints,
                    maxRangeM = 80f
                ).also {
                    projectionCache.lastFrameRef = activeFrame
                    projectionCache.lastHudMode = effectiveHudMode
                    projectionCache.cachedLollipops = it
                }
            }
            android.os.Trace.endSection()

            android.os.Trace.beginSection("RS_UI_DrawLollipops")
            badgeTextPaint.textSize = 9f * density

            if (effectiveHudMode == HudOverlayMode.SIMPLE_BOXES_80M) {
                // Ultra-lightweight rectangular target boxes: minimum Skia fill/path calls
                val minBoxW = 24f * density
                val maxBoxW = 72f * density
                val minBoxH = 18f * density
                val maxBoxH = 56f * density
                for (target in lollipops) {
                    val targetColor = when {
                        target.isFcwTarget -> HUD_COLOR_FCW
                        target.isAccTarget -> HUD_COLOR_ACC
                        target.isTracked -> HUD_COLOR_TRACK
                        else -> HUD_COLOR_STATIONARY
                    }
                    val pScale = (16.0f / target.depthM.coerceAtLeast(3f))
                    val boxW = (34f * density * pScale).coerceIn(minBoxW, maxBoxW)
                    val spanH = (target.basePt.yPx - target.headPt.yPx).coerceAtLeast(12f * density)
                    val boxH = (spanH * 1.4f).coerceIn(minBoxH, maxBoxH)
                    val cx = target.headPt.xPx
                    val cy = (target.headPt.yPx + target.basePt.yPx) * 0.5f
                    val left = (cx - boxW * 0.5f).coerceIn(2f * density, w - boxW - 2f * density)
                    val top = (cy - boxH * 0.5f).coerceIn(2f * density, h - boxH - 2f * density)

                    drawRect(
                        color = targetColor.copy(alpha = 0.14f),
                        topLeft = Offset(left, top),
                        size = Size(boxW, boxH)
                    )
                    drawRect(
                        color = targetColor,
                        topLeft = Offset(left, top),
                        size = Size(boxW, boxH),
                        style = if (target.isFcwTarget || target.isAccTarget) boxPriorityStroke else boxNormalStroke
                    )

                    if (target.trackId != null) {
                        val tagTop = (top - 4f * density).coerceAtLeast(12f * density)
                        drawContext.canvas.nativeCanvas.drawText(
                            target.label,
                            left + 2f * density,
                            tagTop,
                            badgeTextPaint
                        )
                    }
                }
            } else {
                for (lollipop in lollipops) {
                    val targetColor = when {
                        lollipop.isFcwTarget -> HUD_COLOR_FCW
                        lollipop.isAccTarget -> HUD_COLOR_ACC
                        lollipop.isTracked -> HUD_COLOR_TRACK
                        lollipop.dopplerMps < -0.5f -> HUD_COLOR_CLOSING
                        lollipop.dopplerMps > 0.5f -> HUD_COLOR_OPENING
                        else -> HUD_COLOR_STATIONARY
                    }

                    val depthFactor = (lollipop.depthM / 100f).coerceIn(0f, 1f)
                    val baseAlpha = (1.0f - depthFactor * 0.65f).coerceIn(0.20f, 0.95f)
                    val snrFactor = if (lollipop.isTracked) 1.0f else (lollipop.snrDb / 35f).coerceIn(0.6f, 1.0f)
                    val alpha = (baseAlpha * snrFactor).coerceIn(0.20f, 0.95f)

                    val pScale = (15.0f / lollipop.depthM.coerceAtLeast(3f))
                    val stemWidth = if (lollipop.isFcwTarget || lollipop.isAccTarget) {
                        (3.0.dp.toPx() * pScale).coerceIn(2.0.dp.toPx(), 5.dp.toPx())
                    } else if (lollipop.isTracked) {
                        (2.5.dp.toPx() * pScale).coerceIn(1.5.dp.toPx(), 4.dp.toPx())
                    } else {
                        (1.2.dp.toPx() * pScale).coerceIn(0.8.dp.toPx(), 2.dp.toPx())
                    }

                    val headRadius = if (lollipop.isFcwTarget || lollipop.isAccTarget) {
                        (7.dp.toPx() * pScale).coerceIn(5.dp.toPx(), 14.dp.toPx())
                    } else if (lollipop.isTracked) {
                        (6.dp.toPx() * pScale).coerceIn(4.dp.toPx(), 12.dp.toPx())
                    } else {
                        (3.dp.toPx() * pScale).coerceIn(1.5.dp.toPx(), 5.dp.toPx())
                    }

                    // A. Ground Footprint Ellipse on road asphalt plane
                    val fp = lollipop.footprint
                    if (fp != null && fp.polygon.size >= 4) {
                        reusablePath.reset()
                        reusablePath.moveTo(fp.polygon[0].xPx, fp.polygon[0].yPx)
                        for (i in 1 until fp.polygon.size) {
                            reusablePath.lineTo(fp.polygon[i].xPx, fp.polygon[i].yPx)
                        }
                        reusablePath.close()

                        val footprintAlpha = when {
                            lollipop.isFcwTarget -> 0.35f
                            lollipop.isAccTarget -> 0.30f
                            lollipop.isTracked -> 0.25f
                            else -> 0.12f
                        }
                        val strokeAlpha = when {
                            lollipop.isFcwTarget -> 0.95f
                            lollipop.isAccTarget -> 0.90f
                            lollipop.isTracked -> 0.85f
                            else -> 0.45f
                        }

                        drawPath(
                            path = reusablePath,
                            color = targetColor.copy(alpha = alpha * footprintAlpha)
                        )
                        drawPath(
                            path = reusablePath,
                            color = targetColor.copy(alpha = alpha * strokeAlpha),
                            style = when {
                                lollipop.isFcwTarget || lollipop.isAccTarget -> footprintPriorityStroke
                                lollipop.isTracked -> footprintTrackStroke
                                else -> footprintRawStroke
                            }
                        )
                    }

                    // Ground contact anchor dot
                    drawCircle(
                        color = targetColor.copy(alpha = alpha * 0.9f),
                        radius = if (lollipop.isFcwTarget || lollipop.isAccTarget) 3.5.dp.toPx() else if (lollipop.isTracked) 2.5.dp.toPx() else 1.5.dp.toPx(),
                        center = Offset(lollipop.basePt.xPx, lollipop.basePt.yPx)
                    )

                    // B. Vertical Perspective Stem
                    val stemDx = lollipop.headPt.xPx - lollipop.basePt.xPx
                    val stemDy = lollipop.headPt.yPx - lollipop.basePt.yPx
                    if (kotlin.math.hypot(stemDx, stemDy) > 2f) {
                        drawLine(
                            color = targetColor.copy(alpha = alpha * 0.85f),
                            start = Offset(lollipop.basePt.xPx, lollipop.basePt.yPx),
                            end = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx),
                            strokeWidth = stemWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    // C. Lollipop Head with outer halo and specular center
                    val outerHaloRadius = when {
                        lollipop.isFcwTarget -> headRadius * 2.0f
                        lollipop.isAccTarget -> headRadius * 1.8f
                        else -> headRadius * 1.5f
                    }
                    drawCircle(
                        color = targetColor.copy(alpha = alpha * 0.35f),
                        radius = outerHaloRadius,
                        center = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx)
                    )
                    if (lollipop.isFcwTarget) {
                        drawCircle(
                            color = HUD_COLOR_FCW.copy(alpha = alpha * 0.7f),
                            radius = headRadius * 1.4f,
                            center = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx),
                            style = fcwHaloStroke
                        )
                    } else if (lollipop.isAccTarget) {
                        drawCircle(
                            color = HUD_COLOR_ACC.copy(alpha = alpha * 0.6f),
                            radius = headRadius * 1.3f,
                            center = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx),
                            style = accHaloStroke
                        )
                    }
                    drawCircle(
                        color = targetColor.copy(alpha = alpha),
                        radius = headRadius,
                        center = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx)
                    )
                    if (lollipop.isTracked) {
                        drawCircle(
                            color = Color.White.copy(alpha = alpha * 0.9f),
                            radius = headRadius * 0.35f,
                            center = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx)
                        )
                    }

                    // D. Track ID & Range Badge (for tracked obstacles)
                    if (lollipop.trackId != null) {
                        val trackLabel = lollipop.label
                        val textWidth = badgeTextPaint.measureText(trackLabel)
                        val badgeHeight = 14f * density
                        val badgeWidth = textWidth + 8f * density
                        val badgeLeft = (lollipop.headPt.xPx - badgeWidth / 2f).coerceIn(4f * density, w - badgeWidth - 4f * density)
                        val badgeTop = (lollipop.headPt.yPx - headRadius - badgeHeight - 3f * density).coerceAtLeast(4f * density)

                        val bgTint = when {
                            lollipop.isFcwTarget -> HUD_BG_FCW.copy(alpha = 0.85f * alpha)
                            lollipop.isAccTarget -> HUD_BG_ACC.copy(alpha = 0.85f * alpha)
                            else -> Color.Black.copy(alpha = 0.75f * alpha)
                        }

                        drawRoundRect(
                            color = bgTint,
                            topLeft = Offset(badgeLeft, badgeTop),
                            size = Size(badgeWidth, badgeHeight),
                            cornerRadius = CornerRadius(4f * density, 4f * density)
                        )
                        drawRoundRect(
                            color = targetColor.copy(alpha = 0.9f * alpha),
                            topLeft = Offset(badgeLeft, badgeTop),
                            size = Size(badgeWidth, badgeHeight),
                            cornerRadius = CornerRadius(4f * density, 4f * density),
                            style = if (lollipop.isFcwTarget || lollipop.isAccTarget) badgePriorityStroke else badgeNormalStroke
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            trackLabel,
                            badgeLeft + 4f * density,
                            badgeTop + 10.5f * density,
                            badgeTextPaint
                        )
                    }
                }
            }
            android.os.Trace.endSection()
        }

        if (geomChanged) {
            projectionCache.lastCalibration = calibrationParams
            projectionCache.lastIntrinsics = intrinsics
            projectionCache.lastWidth = w
            projectionCache.lastHeight = h
        }

        if (enableTimingLog) {
            val drawEndNs = System.nanoTime()
            val durationMs = (drawEndNs - drawStartNs) / 1_000_000.0
            val deltaDrawMs = if (timingState.lastDrawStartNs > 0L) {
                (drawStartNs - timingState.lastDrawStartNs) / 1_000_000.0
            } else {
                0.0
            }
            timingState.lastDrawStartNs = drawStartNs
            val seqNum = activeFrame?.header?.frameNumber ?: -1L
            AppLogger.d(
                "ViewfinderOverlay",
                String.format(
                    Locale.US,
                    "[DRAW_OVERLAY] seq=#%d | deltaDrawMs=%.2f | durationMs=%.2f",
                    seqNum,
                    deltaDrawMs,
                    durationMs
                )
            )
        }
    }
}
