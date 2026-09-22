package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.bajajauto.roadsense.fusion.engine.CameraIntrinsics
import com.bajajauto.roadsense.fusion.engine.SpatialProjectionEngine
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.models.RadarFrame

/**
 * Renders 2D radar point cloud detections, tracked obstacles, and translucent radar range lines
 * (10m, 30m, 60m, 120m) with boresight and ±60° FOV boundaries projected onto the camera viewfinder.
 */
@Composable
fun ViewfinderRadarOverlay(
    radarFrame: RadarFrame?,
    calibrationParams: CalibrationParameters,
    intrinsics: CameraIntrinsics?,
    viewWidth: Int,
    viewHeight: Int,
    showRangeArcs: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (intrinsics == null || viewWidth <= 0 || viewHeight <= 0) return
    if (radarFrame == null && !showRangeArcs) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width.toInt()
        val h = size.height.toInt()

        // 1. Draw Translucent Radar Range Arcs, Boresight, and FOV Boundary Lines
        if (showRangeArcs) {
            val rangeOverlay = SpatialProjectionEngine.calculateRadarRangeOverlay(
                params = calibrationParams,
                intrinsics = intrinsics,
                viewWidth = w,
                viewHeight = h,
                ranges = listOf(10f, 30f, 60f, 120f)
            )

            val fovColor = Color(0x3D4DD0E1)       // Translucent teal/cyan
            val boresightColor = Color(0x5500E5FF) // Translucent cyan centerline
            val arcColor = Color(0x6600E5FF)       // Crisp translucent cyan
            val fovDash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            val arcDash = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)

            // Draw FOV Boundary Rays (±60° Azimuth)
            if (rangeOverlay.leftFovRay.size >= 2) {
                val pathLeft = Path().apply {
                    moveTo(rangeOverlay.leftFovRay[0].xPx, rangeOverlay.leftFovRay[0].yPx)
                    for (i in 1 until rangeOverlay.leftFovRay.size) {
                        lineTo(rangeOverlay.leftFovRay[i].xPx, rangeOverlay.leftFovRay[i].yPx)
                    }
                }
                drawPath(pathLeft, color = fovColor, style = Stroke(width = 1.2.dp.toPx(), pathEffect = fovDash))
            }

            if (rangeOverlay.rightFovRay.size >= 2) {
                val pathRight = Path().apply {
                    moveTo(rangeOverlay.rightFovRay[0].xPx, rangeOverlay.rightFovRay[0].yPx)
                    for (i in 1 until rangeOverlay.rightFovRay.size) {
                        lineTo(rangeOverlay.rightFovRay[i].xPx, rangeOverlay.rightFovRay[i].yPx)
                    }
                }
                drawPath(pathRight, color = fovColor, style = Stroke(width = 1.2.dp.toPx(), pathEffect = fovDash))
            }

            // Draw Radar Center Boresight Line (0° Azimuth extending till 120m)
            if (rangeOverlay.boresightRay.size >= 2) {
                val pathBoresight = Path().apply {
                    moveTo(rangeOverlay.boresightRay[0].xPx, rangeOverlay.boresightRay[0].yPx)
                    for (i in 1 until rangeOverlay.boresightRay.size) {
                        lineTo(rangeOverlay.boresightRay[i].xPx, rangeOverlay.boresightRay[i].yPx)
                    }
                }
                drawPath(pathBoresight, color = boresightColor, style = Stroke(width = 1.5.dp.toPx(), pathEffect = fovDash))
            }

            // Draw Concentric Range Arcs (10m, 30m, 60m, 120m) and Distance Labels
            val labelPaint = Paint().apply {
                color = android.graphics.Color.argb(215, 128, 216, 255)
                textSize = 8.5f * density
                isFakeBoldText = true
                typeface = Typeface.MONOSPACE
                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
            }

            for (arc in rangeOverlay.arcs) {
                if (arc.points.size >= 2) {
                    val pathArc = Path().apply {
                        moveTo(arc.points[0].xPx, arc.points[0].yPx)
                        for (i in 1 until arc.points.size) {
                            lineTo(arc.points[i].xPx, arc.points[i].yPx)
                        }
                    }
                    drawPath(pathArc, color = arcColor, style = Stroke(width = 1.5.dp.toPx(), pathEffect = arcDash))
                }

                // Small indication beside the range ring showing what range we are looking at
                arc.labelPoint?.let { lp ->
                    drawContext.canvas.nativeCanvas.drawText(
                        arc.label,
                        lp.xPx + 6f * density,
                        lp.yPx - 3f * density,
                        labelPaint
                    )
                }
            }
        }

        // 2. Draw Hybrid Radar Lollipops & Ground Plane Footprints (TLV Type 1 & Type 3)
        if (radarFrame != null) {
            val lollipops = SpatialProjectionEngine.calculateRadarLollipops(
                radarFrame = radarFrame,
                params = calibrationParams,
                intrinsics = intrinsics,
                viewWidth = w,
                viewHeight = h,
                filterUntrackedClutter = false
            )

            val badgeTextPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 9f * density
                isFakeBoldText = true
                typeface = Typeface.MONOSPACE
                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
            }

            for (lollipop in lollipops) {
                // Color coding based on tracking and Doppler velocity:
                // Closing in: Red (doppler < -0.5 m/s)
                // Opening gap: Green (doppler > 0.5 m/s)
                // Tracked obstacle: Amber (when |doppler| <= 0.5 m/s)
                // Stationary point / clutter: Cyan
                val targetColor = when {
                    lollipop.dopplerMps < -0.5f -> Color(0xFFFF5252) // Closing in / hazard
                    lollipop.dopplerMps > 0.5f -> Color(0xFF69F0AE)  // Opening gap
                    lollipop.isTracked -> Color(0xFFFFD54F)           // Active tracked vehicle / obstacle
                    else -> Color(0xFF40C4FF)                         // Stationary ground point
                }

                // Distance-based alpha fall-off: distant objects fade gently to avoid visual clutter
                val depthFactor = (lollipop.depthM / 100f).coerceIn(0f, 1f)
                val baseAlpha = (1.0f - depthFactor * 0.65f).coerceIn(0.20f, 0.95f)
                val snrFactor = if (lollipop.isTracked) 1.0f else (lollipop.snrDb / 35f).coerceIn(0.6f, 1.0f)
                val alpha = (baseAlpha * snrFactor).coerceIn(0.20f, 0.95f)

                // Perspective scale factor based on forward depth
                val pScale = (15.0f / lollipop.depthM.coerceAtLeast(3f))
                val stemWidth = if (lollipop.isTracked) {
                    (2.5.dp.toPx() * pScale).coerceIn(1.5.dp.toPx(), 4.dp.toPx())
                } else {
                    (1.2.dp.toPx() * pScale).coerceIn(0.8.dp.toPx(), 2.dp.toPx())
                }

                val headRadius = if (lollipop.isTracked) {
                    (6.dp.toPx() * pScale).coerceIn(4.dp.toPx(), 12.dp.toPx())
                } else {
                    (3.dp.toPx() * pScale).coerceIn(1.5.dp.toPx(), 5.dp.toPx())
                }

                // A. Ground Footprint Ellipse on road asphalt plane
                val fp = lollipop.footprint
                if (fp != null && fp.polygon.size >= 4) {
                    val footPath = Path().apply {
                        moveTo(fp.polygon[0].xPx, fp.polygon[0].yPx)
                        for (i in 1 until fp.polygon.size) {
                            lineTo(fp.polygon[i].xPx, fp.polygon[i].yPx)
                        }
                        close()
                    }

                    // Translucent asphalt footprint fill
                    drawPath(
                        path = footPath,
                        color = targetColor.copy(alpha = alpha * (if (lollipop.isTracked) 0.25f else 0.12f))
                    )
                    // Glowing perimeter border
                    drawPath(
                        path = footPath,
                        color = targetColor.copy(alpha = alpha * (if (lollipop.isTracked) 0.85f else 0.45f)),
                        style = Stroke(width = if (lollipop.isTracked) 2.dp.toPx() else 1.2.dp.toPx())
                    )
                }

                // Ground contact anchor dot
                drawCircle(
                    color = targetColor.copy(alpha = alpha * 0.9f),
                    radius = if (lollipop.isTracked) 2.5.dp.toPx() else 1.5.dp.toPx(),
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
                drawCircle(
                    color = targetColor.copy(alpha = alpha * 0.35f),
                    radius = headRadius * 1.5f,
                    center = Offset(lollipop.headPt.xPx, lollipop.headPt.yPx)
                )
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

                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.75f * alpha),
                        topLeft = Offset(badgeLeft, badgeTop),
                        size = Size(badgeWidth, badgeHeight),
                        cornerRadius = CornerRadius(4f * density, 4f * density)
                    )
                    drawRoundRect(
                        color = targetColor.copy(alpha = 0.8f * alpha),
                        topLeft = Offset(badgeLeft, badgeTop),
                        size = Size(badgeWidth, badgeHeight),
                        cornerRadius = CornerRadius(4f * density, 4f * density),
                        style = Stroke(width = 1f * density)
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
    }
}
