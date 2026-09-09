package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bird's-Eye View (BEV) top-down Cartesian radar display.
 * Maps radar coordinates (X = lateral meters, Y = longitudinal meters) to screen pixels with 1:1 aspect ratio.
 */
@Composable
fun RadarBevPlot(
    frame: RadarFrame?,
    modifier: Modifier = Modifier
) {
    var maxRangeMeters by remember { mutableFloatStateOf(30f) }
    val rangeOptions = listOf(15f, 30f, 60f, 100f)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11141C)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with title and Range selector chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bird's-Eye View (BEV)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rangeOptions.forEach { range ->
                        FilterChip(
                            selected = maxRangeMeters == range,
                            onClick = { maxRangeMeters = range },
                            label = { Text("${range.toInt()}m", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // BEV Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
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
                }
            }
        }
    }
}
