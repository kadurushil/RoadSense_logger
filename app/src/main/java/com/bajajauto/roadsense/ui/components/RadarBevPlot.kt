package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bird's-Eye View (BEV) top-down Cartesian radar display.
 * Maps radar coordinates (X = lateral meters, Y = longitudinal meters) to screen pixels with 1:1 aspect ratio.
 */
@Composable
fun RadarBevPlot(
    viewModel: RadarViewModel,
    frame: RadarFrame?,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val maxRangeMeters by viewModel.radarMaxRange.collectAsState()
    val dynamicOnly by viewModel.radarDynamicOnly.collectAsState()
    val minSnrFilter by viewModel.radarMinSnr.collectAsState()
    val rangeOptions = listOf(15f, 30f, 60f, 130f)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11141C)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = if (isLandscape) {
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            }
        ) {
            if (isLandscape) {
                // In Landscape: Compact single-row header combining title + 4 range scale buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BEV Scope (${maxRangeMeters.toInt()}m)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rangeOptions.forEach { range ->
                            val isSelected = maxRangeMeters == range
                            Surface(
                                selected = isSelected,
                                onClick = { viewModel.setRadarMaxRange(range) },
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF1E2530),
                                contentColor = if (isSelected) Color.White else Color(0xFF90A4AE),
                                border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF37474F)),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text(
                                        text = "${range.toInt()}m",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // In Portrait: Standard header & full-width segmented buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Display Range Scale",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFCFD8DC),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Max: ${maxRangeMeters.toInt()}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rangeOptions.forEach { range ->
                        val isSelected = maxRangeMeters == range
                        Surface(
                            selected = isSelected,
                            onClick = { viewModel.setRadarMaxRange(range) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF1E2530),
                            contentColor = if (isSelected) Color.White else Color(0xFF90A4AE),
                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF37474F)),
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${range.toInt()}m",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

            // BEV Canvas Box: In landscape it takes weight(1f) to fill the card height exactly!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isLandscape) Modifier.weight(1f) else Modifier.height(280.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090B10))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    // Radar Origin at bottom-center (with 24px bottom padding for ego marker)
                    val originX = canvasWidth / 2f
                    val originY = canvasHeight - 28f
                    val usableHeight = originY - 16f

                    // 1:1 Metric Scale (pixels per meter)
                    val scale = usableHeight / maxRangeMeters

                    // Grid / Ring Colors
                    val gridColor = Color(0xFF263238)
                    val ringColor = Color(0xFF37474F)
                    val fovColor = Color(0x334DD0E1)
                    val textColor = android.graphics.Color.argb(160, 176, 190, 197)

                    val textPaint = Paint().apply {
                        color = textColor
                        textSize = 28f
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }

                    // 1. Draw Field of View (FOV) cone lines (+-60 degrees)
                    val fovRad = Math.toRadians(60.0).toFloat()
                    val fovLen = usableHeight * 1.15f
                    val fovLeftX = originX - sin(fovRad) * fovLen
                    val fovLeftY = originY - cos(fovRad) * fovLen
                    val fovRightX = originX + sin(fovRad) * fovLen
                    val fovRightY = originY - cos(fovRad) * fovLen

                    drawLine(fovColor, Offset(originX, originY), Offset(fovLeftX, fovLeftY), strokeWidth = 1.5f)
                    drawLine(fovColor, Offset(originX, originY), Offset(fovRightX, fovRightY), strokeWidth = 1.5f)

                    // 2. Draw Longitudinal Centerline (Ego Forward Vector)
                    drawLine(
                        color = Color(0x5500E5FF),
                        start = Offset(originX, originY),
                        end = Offset(originX, 10f),
                        strokeWidth = 1.5f
                    )

                    // 3. Draw Range Rings & Labels
                    val ringStep = when (maxRangeMeters) {
                        15f -> 5f
                        30f -> 10f
                        60f -> 15f
                        else -> 25f
                    }

                    var ringDist = ringStep
                    while (ringDist <= maxRangeMeters) {
                        val radius = ringDist * scale
                        val top = originY - radius
                        val left = originX - radius
                        val diameter = radius * 2

                        // Draw range arc (centered upwards from 180 to 360 deg)
                        drawArc(
                            color = ringColor,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(left, top),
                            size = Size(diameter, diameter),
                            style = Stroke(width = 1.2f)
                        )

                        // Draw distance label on the ring
                        drawContext.canvas.nativeCanvas.drawText(
                            "${ringDist.toInt()}m",
                            originX,
                            top + 28f,
                            textPaint
                        )

                        ringDist += ringStep
                    }

                    // 4. Draw Lateral Markers (+-5m, +-10m)
                    val lateralStep = 5f
                    var latX = lateralStep
                    while (latX * scale < (canvasWidth / 2f) - 20f) {
                        val screenRightX = originX + latX * scale
                        val screenLeftX = originX - latX * scale

                        drawLine(
                            color = gridColor,
                            start = Offset(screenRightX, originY),
                            end = Offset(screenRightX, originY - 12f),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(screenLeftX, originY),
                            end = Offset(screenLeftX, originY - 12f),
                            strokeWidth = 1f
                        )

                        latX += lateralStep
                    }

                    // 5. Draw Ego Vehicle Marker (Centered at origin)
                    val egoWidth = 24f
                    val egoHeight = 32f
                    val egoPath = Path().apply {
                        moveTo(originX, originY - egoHeight) // Front tip
                        lineTo(originX + egoWidth / 2f, originY) // Bottom right
                        lineTo(originX - egoWidth / 2f, originY) // Bottom left
                        close()
                    }
                    drawPath(path = egoPath, color = Color(0xFF00E5FF))

                    // 6. Draw Clusters (TLV Type 2) as distinct compact yellow circles without text labels
                    frame?.clusters?.forEach { cluster ->
                        val cx = originX + cluster.x * scale
                        val cy = originY - cluster.y * scale
                        if (cy in 0f..originY && cx in 0f..canvasWidth) {
                            val r = 8f
                            drawCircle(
                                color = Color(0x55FFD54F),
                                center = Offset(cx, cy),
                                radius = r
                            )
                            drawCircle(
                                color = Color(0xFFFFD54F),
                                center = Offset(cx, cy),
                                radius = r,
                                style = Stroke(width = 1.5f)
                            )
                        }
                    }

                    // 7. Draw Point Cloud Reflections (TLV Type 1) with Doppler velocity colors
                    val pointsToRender = frame?.points?.filter { pt ->
                        (!dynamicOnly || kotlin.math.abs(pt.doppler) > 0.35f) &&
                        (!minSnrFilter || pt.snrDb >= 15f)
                    } ?: emptyList()

                    for (pt in pointsToRender) {
                        val px = originX + pt.x * scale
                        val py = originY - pt.y * scale

                        if (py in 0f..originY && px in 0f..canvasWidth) {
                            val pointColor = when {
                                pt.doppler < -0.35f -> Color(0xFFFF5252) // Approaching (Red)
                                pt.doppler > 0.35f -> Color(0xFF40C4FF)  // Receding (Cyan)
                                else -> Color(0xFF69F0AE)                // Stationary (Green)
                            }

                            val dotRadius = 4.5f

                            // Halo glow
                            drawCircle(
                                color = pointColor.copy(alpha = 0.35f),
                                radius = dotRadius + 2.5f,
                                center = Offset(px, py)
                            )
                            // Solid core
                            drawCircle(
                                color = pointColor,
                                radius = dotRadius,
                                center = Offset(px, py)
                            )
                        }
                    }

                    // 8. Draw Active Tracked Targets (TLV Type 3) as Red Circle with Yellow Velocity Pointer Triangle on circumference & leader line
                    val trackLabelPaint = Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 24f
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                        isFakeBoldText = true
                    }

                    frame?.tracks?.forEach { track ->
                        val tx = originX + track.x * scale
                        val ty = originY - track.y * scale

                        if (ty in 0f..originY && tx in 0f..canvasWidth) {
                            val trackRadius = 14f

                            // 1. Red circle for the tracked target
                            drawCircle(
                                color = Color(0x33FF5252),
                                center = Offset(tx, ty),
                                radius = trackRadius
                            )
                            drawCircle(
                                color = Color(0xFFFF5252),
                                center = Offset(tx, ty),
                                radius = trackRadius,
                                style = Stroke(width = 2.0f)
                            )
                            drawCircle(
                                color = Color(0xFFFF5252),
                                center = Offset(tx, ty),
                                radius = 3.0f
                            )

                            // 2. Velocity vector & direction triangle on circumference
                            val vxPx = track.vx * scale * 0.75f
                            val vyPx = -track.vy * scale * 0.75f // Forward is -Y on screen canvas
                            val vSpeed = hypot(vxPx, vyPx)

                            if (vSpeed > 0.8f) {
                                val theta = atan2(vyPx, vxPx)
                                val ux = cos(theta)
                                val uy = sin(theta)
                                val px = -uy
                                val py = ux

                                // Circumference anchor point
                                val circX = tx + ux * trackRadius
                                val circY = ty + uy * trackRadius

                                // Small Yellow pointer triangle on circumference pointing in velocity direction
                                val triLen = 6f
                                val triWidth = 4f
                                val tipX = circX + ux * triLen
                                val tipY = circY + uy * triLen
                                val b1X = circX - ux * 2f + px * triWidth
                                val b1Y = circY - uy * 2f + py * triWidth
                                val b2X = circX - ux * 2f - px * triWidth
                                val b2Y = circY - uy * 2f - py * triWidth

                                val triPath = Path().apply {
                                    moveTo(tipX, tipY)
                                    lineTo(b1X, b1Y)
                                    lineTo(b2X, b2Y)
                                    close()
                                }
                                drawPath(path = triPath, color = Color(0xFFFFD54F))

                                // When velocity is large, vector extends out of the circle proportional to speed
                                if (vSpeed > trackRadius) {
                                    val endX = tx + vxPx
                                    val endY = ty + vyPx
                                    drawLine(
                                        color = Color(0xFFFFD54F),
                                        start = Offset(tipX, tipY),
                                        end = Offset(endX, endY),
                                        strokeWidth = 2.0f
                                    )
                                    drawCircle(
                                        color = Color(0xFFFFD54F),
                                        radius = 3.0f,
                                        center = Offset(endX, endY)
                                    )
                                }
                            }

                            // 3. Target ID Label "#TID" above the circle
                            drawContext.canvas.nativeCanvas.drawText(
                                "#${track.tid}",
                                tx,
                                ty - trackRadius - 6f,
                                trackLabelPaint
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

            // Filter Toggles & Velocity Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filters
                Row(horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp)) {
                    Surface(
                        selected = dynamicOnly,
                        onClick = { viewModel.setRadarDynamicOnly(!dynamicOnly) },
                        shape = RoundedCornerShape(4.dp),
                        color = if (dynamicOnly) Color(0xFF37474F) else Color(0xFF181D26),
                        border = BorderStroke(1.dp, if (dynamicOnly) Color(0xFF80D8FF) else Color(0xFF263238)),
                        modifier = Modifier.height(if (isLandscape) 24.dp else 28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = if (isLandscape) 6.dp else 8.dp)) {
                            Text(
                                text = "Moving Only",
                                fontSize = if (isLandscape) 10.sp else 11.sp,
                                color = if (dynamicOnly) Color(0xFF80D8FF) else Color(0xFF90A4AE),
                                fontWeight = if (dynamicOnly) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Surface(
                        selected = minSnrFilter,
                        onClick = { viewModel.setRadarMinSnr(!minSnrFilter) },
                        shape = RoundedCornerShape(4.dp),
                        color = if (minSnrFilter) Color(0xFF37474F) else Color(0xFF181D26),
                        border = BorderStroke(1.dp, if (minSnrFilter) Color(0xFF80D8FF) else Color(0xFF263238)),
                        modifier = Modifier.height(if (isLandscape) 24.dp else 28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = if (isLandscape) 6.dp else 8.dp)) {
                            Text(
                                text = "SNR ≥ 15dB",
                                fontSize = if (isLandscape) 10.sp else 11.sp,
                                color = if (minSnrFilter) Color(0xFF80D8FF) else Color(0xFF90A4AE),
                                fontWeight = if (minSnrFilter) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Legend
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "● Appr", color = Color(0xFFFF5252), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = "● Rec", color = Color(0xFF40C4FF), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = "● Stat", color = Color(0xFF69F0AE), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
