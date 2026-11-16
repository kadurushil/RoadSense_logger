package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.imu.ImuTelemetryState
import kotlin.math.*

private data class ProjectedPoint(val u: Float, val v: Float, val depth: Float)

private data class FaceDef(
    val name: String,
    val indices: List<Int>,
    val baseColor: Color,
    val borderColor: Color
)

private data class SortedFace(
    val face: FaceDef,
    val points: List<ProjectedPoint>,
    val avgDepth: Float
)

private val VIZ_GROUND_GRID_COLOR = Color(0xFF00E5FF).copy(alpha = 0.12f)
private val VIZ_GROUND_GRID_BORDER = Color(0xFF00E5FF).copy(alpha = 0.35f)
private val VIZ_RING_COLOR = Color(0xFF00E5FF).copy(alpha = 0.15f)
private val VIZ_FWD_ARROW_COLOR = Color(0xFF00E676).copy(alpha = 0.7f)
private val VIZ_PLUMB_COLOR = Color(0xFFFFB300).copy(alpha = 0.5f)
private val VIZ_SHADOW_COLOR = Color.Black.copy(alpha = 0.45f)
private val VIZ_SHADOW_DOT_COLOR = Color(0xFFFFB300).copy(alpha = 0.8f)
private val VIZ_SCREEN_FILL_COLOR = Color(0xFF0284C7).copy(alpha = 0.25f)
private val VIZ_SCREEN_STROKE_COLOR = Color(0xFF38BDF8).copy(alpha = 0.6f)
private val VIZ_RAY_COLOR = Color(0xFF00E676).copy(alpha = 0.85f)

private val VIZ_RING_STROKE = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
private val VIZ_PLUMB_DASH = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
private val VIZ_FACE_BORDER_STROKE = Stroke(width = 1.5f)
private val VIZ_INNER_SCREEN_STROKE = Stroke(width = 1f)

private val VIZ_FACES = listOf(
    FaceDef("Screen (Driver)", listOf(4, 5, 6, 7), Color(0xFF0F172A), Color(0xFF00E5FF)),
    FaceDef("Chassis (Windshield)", listOf(0, 3, 2, 1), Color(0xFF1E293B), Color(0xFF475569)),
    FaceDef("Top Rim", listOf(3, 2, 6, 7), Color(0xFF334155), Color(0xFF64748B)),
    FaceDef("Bottom Rim", listOf(0, 1, 5, 4), Color(0xFF334155), Color(0xFF64748B)),
    FaceDef("Right Rim", listOf(1, 2, 6, 5), Color(0xFF283548), Color(0xFF64748B)),
    FaceDef("Left Rim", listOf(0, 3, 7, 4), Color(0xFF283548), Color(0xFF64748B))
)

private class VisualizerDrawScratch {
    val rotMat = FloatArray(9)
    val worldCorners = Array(8) { FloatArray(3) }
    val projectedCorners = arrayOfNulls<ProjectedPoint>(8)
    val ringPath = Path()
    val facePath = Path()
    val innerPath = Path()
    val labelPaint = Paint().apply {
        color = android.graphics.Color.argb(200, 148, 163, 184)
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        isAntiAlias = true
    }
    val orthoPaint = Paint().apply {
        color = android.graphics.Color.argb(230, 0, 230, 118)
        textSize = 26f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
    }
}

/**
 * 3D Phone Orientation Visualizer:
 * Renders a real-time, interactive 3D model of the smartphone in space hovering above
 * the reference level ground plane beneath the vehicle.
 *
 * Driven by Android's Sensor.TYPE_GAME_ROTATION_VECTOR (magnet-free, gravitationally leveled).
 * Supports draw-phase [telemetryProvider] so 30 Hz attitude updates only redraw the Canvas
 * while numeric badges update at a lower cadence via [badgeTelemetry].
 */
@Composable
fun PhoneOrientation3DVisualizer(
    telemetry: ImuTelemetryState? = null,
    telemetryProvider: (() -> ImuTelemetryState)? = null,
    badgeTelemetry: ImuTelemetryState? = null,
    modifier: Modifier = Modifier
) {
    val initialSnap = badgeTelemetry ?: telemetry ?: telemetryProvider?.invoke() ?: ImuTelemetryState()
    val effectiveProvider: () -> ImuTelemetryState = telemetryProvider ?: { telemetry ?: initialSnap }
    val uiTelemetry = badgeTelemetry ?: telemetry ?: initialSnap

    // 3D Viewport Orbit Camera Angles (Spherical Coordinates)
    var orbitAzimuthDeg by remember { mutableFloatStateOf(45f) }
    var orbitElevationDeg by remember { mutableFloatStateOf(28f) }
    var yawTareDeg by remember { mutableFloatStateOf(0f) }
    var isTareActive by remember { mutableStateOf(false) }

    val isOrtho = abs(orbitAzimuthDeg - 90f) < 0.5f && abs(orbitElevationDeg) < 0.5f

    LaunchedEffect(uiTelemetry.isMonitoring) {
        if (uiTelemetry.isMonitoring && !isTareActive) {
            yawTareDeg = effectiveProvider().yawDeg
            isTareActive = true
        }
    }

    // Relative yaw heading with respect to tared Road (+Y) axis
    val relativeYawDeg = remember(uiTelemetry.yawDeg, yawTareDeg, isTareActive) {
        if (!isTareActive) uiTelemetry.yawDeg
        else {
            var diff = (uiTelemetry.yawDeg - yawTareDeg) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            diff
        }
    }

    val isLevel = abs(uiTelemetry.rollDeg) <= 1.2f
    val isPitchNominal = uiTelemetry.pitchDeg in 2.0f..14.0f
    val isOptimalAlignment = isLevel && isPitchNominal
    val drawScratch = remember { VisualizerDrawScratch() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // --- 1. Card Header & Telemetry Status Badges ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "3D Vehicle Attitude & Level Ground",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Game Rotation Vector (6-DOF Leveled)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Level Alignment Badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = when {
                        !uiTelemetry.isMonitoring -> MaterialTheme.colorScheme.surfaceVariant
                        isOptimalAlignment -> Color(0xFF00E676).copy(alpha = 0.15f)
                        isLevel -> Color(0xFF00B0FF).copy(alpha = 0.15f)
                        else -> Color(0xFFFFB300).copy(alpha = 0.15f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            !uiTelemetry.isMonitoring -> Color.Transparent
                            isOptimalAlignment -> Color(0xFF00E676)
                            isLevel -> Color(0xFF00B0FF)
                            else -> Color(0xFFFFB300)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    when {
                                        !uiTelemetry.isMonitoring -> Color.Gray
                                        isOptimalAlignment -> Color(0xFF00E676)
                                        isLevel -> Color(0xFF00B0FF)
                                        else -> Color(0xFFFFB300)
                                    },
                                    CircleShape
                                )
                        )
                        Text(
                            text = when {
                                !uiTelemetry.isMonitoring -> "STANDBY"
                                isOptimalAlignment -> "OPTIMAL MOUNT"
                                isLevel -> "LEVEL (PITCH OFF)"
                                else -> "TILTED (ROLL OFF)"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = when {
                                !uiTelemetry.isMonitoring -> MaterialTheme.colorScheme.onSurfaceVariant
                                isOptimalAlignment -> Color(0xFF00E676)
                                isLevel -> Color(0xFF00B0FF)
                                else -> Color(0xFFFFB300)
                            }
                        )
                    }
                }
            }

            // --- 2. Live HUD Value Badges ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttitudeMetricBox(
                    label = "CAMERA PITCH",
                    valueStr = "%+.1f°".format(uiTelemetry.pitchDeg),
                    hint = if (isPitchNominal) "Windshield OK" else "Adjust Tilt",
                    color = if (isPitchNominal) Color(0xFF00E676) else Color(0xFFFFB300),
                    modifier = Modifier.weight(1f)
                )
                AttitudeMetricBox(
                    label = "LATERAL ROLL",
                    valueStr = "%+.1f°".format(uiTelemetry.rollDeg),
                    hint = if (isLevel) "Level (<1.2°)" else "Tilt Detected",
                    color = if (isLevel) Color(0xFF00E676) else Color(0xFFFF1744),
                    modifier = Modifier.weight(1f)
                )
                AttitudeMetricBox(
                    label = "YAW HEADING",
                    valueStr = "%+.1f°".format(relativeYawDeg),
                    hint = if (isTareActive && abs(relativeYawDeg) < 1.0f) "Road Aligned" else "Relative",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            // --- 3. Interactive 3D Canvas Stage ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color(0xFF0B1017), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            orbitAzimuthDeg = (orbitAzimuthDeg - dragAmount.x * 0.45f) % 360f
                            orbitElevationDeg = (orbitElevationDeg + dragAmount.y * 0.35f).coerceIn(0f, 85f)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    draw3DScene(
                        telemetry = effectiveProvider(),
                        scratch = drawScratch,
                        orbitAzimuthDeg = orbitAzimuthDeg,
                        orbitElevationDeg = orbitElevationDeg,
                        yawTareDeg = if (isTareActive) yawTareDeg else 0f,
                        isOrtho = isOrtho
                    )
                }

                // Overlay Controls & Guidance Hints
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOrtho) "ORTHO SIDE VIEW (PITCH) | YZ Plane" else "Drag to orbit 360° | Elev: %.0f° Az: %.0f°".format(orbitElevationDeg, orbitAzimuthDeg),
                        fontSize = 10.sp,
                        color = if (isOrtho) Color(0xFF00E676) else Color(0xFF64748B),
                        fontWeight = if (isOrtho) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Orthogonal Side (Pitch) View Shortcut
                        IconButton(
                            onClick = {
                                if (isOrtho) {
                                    orbitAzimuthDeg = 45f
                                    orbitElevationDeg = 28f
                                } else {
                                    orbitAzimuthDeg = 90f
                                    orbitElevationDeg = 0f
                                    if (!isTareActive) {
                                        yawTareDeg = effectiveProvider().yawDeg
                                        isTareActive = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(
                                    if (isOrtho) Color(0xFF00E676).copy(alpha = 0.25f) else Color(0xFF1E293B).copy(alpha = 0.8f),
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (isOrtho) Color(0xFF00E676) else Color.Transparent,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewInAr,
                                contentDescription = "Orthogonal Side Pitch View",
                                tint = if (isOrtho) Color(0xFF00E676) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Reset Viewpoint & Align Road (+Y) Axis
                        IconButton(
                            onClick = {
                                orbitAzimuthDeg = 45f
                                orbitElevationDeg = 28f
                                yawTareDeg = effectiveProvider().yawDeg
                                isTareActive = true
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF1E293B).copy(alpha = 0.8f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset & Align Road (+Y)",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Bottom Stage Indicator
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LegendItem(label = "Ground Plane (Z=0)", color = Color(0xFF00E5FF))
                    LegendItem(label = "Camera Boresight", color = Color(0xFF00E676))
                    LegendItem(label = "Plumb Bob", color = Color(0xFFFFB300))
                }
            }
        }
    }
}

@Composable
private fun AttitudeMetricBox(
    label: String,
    valueStr: String,
    hint: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = valueStr,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            Text(text = hint, fontSize = 9.sp, color = color.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Text(text = label, fontSize = 10.sp, color = Color(0xFF94A3B8))
    }
}

/**
 * Core 3D Rendering Pipeline in Pure Jetpack Compose DrawScope:
 * 1. Computes Camera View Matrix from orbit azimuth & elevation.
 * 2. Renders reference level ground plane (Z = 0) with distance rings and road heading axis.
 * 3. Rotates 3D smartphone cuboid via telemetry.rotationMatrix.
 * 4. Projects vertices to 2D screen coordinates with perspective depth foreshortening.
 * 5. Draws plumb bob drop line, projected shadow, and camera optical boresight ray.
 */
private fun DrawScope.draw3DScene(
    telemetry: ImuTelemetryState,
    scratch: VisualizerDrawScratch,
    orbitAzimuthDeg: Float,
    orbitElevationDeg: Float,
    yawTareDeg: Float = 0f,
    isOrtho: Boolean = false
) {
    val canvasW = size.width
    val canvasH = size.height
    val centerX = canvasW / 2f
    val centerY = canvasH / 2f + 15f

    // 1. Camera View Setup (Spherical Orbit)
    val azRad = Math.toRadians(orbitAzimuthDeg.toDouble()).toFloat()
    val elRad = Math.toRadians(orbitElevationDeg.toDouble()).toFloat()
    val camDist = 5.2f

    val targetX = 0f
    val targetY = 0f
    val targetZ = 0.65f // Look at phone hover center

    // Eye coordinates in world frame (where Z_world is Up, XY is Ground Plane)
    val eyeX = camDist * cos(elRad) * sin(azRad)
    val eyeY = -camDist * cos(elRad) * cos(azRad)
    val eyeZ = targetZ + camDist * sin(elRad)

    // Camera basis vectors
    val fwdX = targetX - eyeX
    val fwdY = targetY - eyeY
    val fwdZ = targetZ - eyeZ
    val fwdLen = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ).coerceAtLeast(0.001f)
    val fx = fwdX / fwdLen
    val fy = fwdY / fwdLen
    val fz = fwdZ / fwdLen

    // World Up = [0, 0, 1]. Right = Fwd x Up = [fy, -fx, 0]
    val rLen = sqrt(fy * fy + fx * fx).coerceAtLeast(0.001f)
    val rx = fy / rLen
    val ry = -fx / rLen
    val rz = 0f

    // True Up = Right x Fwd
    val ux = ry * fz - rz * fy
    val uy = rz * fx - rx * fz
    val uz = rx * fy - ry * fx

    val focalLength = min(canvasW, canvasH) * 1.15f

    // Projection lambda: maps (x, y, z) in world coordinates to 2D Canvas (u, v) and depth
    fun project(wx: Float, wy: Float, wz: Float): ProjectedPoint? {
        val dx = wx - eyeX
        val dy = wy - eyeY
        val dz = wz - eyeZ

        val camZ = dx * fx + dy * fy + dz * fz
        if (camZ <= 0.15f) return null // Behind camera

        val camX = dx * rx + dy * ry + dz * rz
        val camY = dx * ux + dy * uy + dz * uz

        val screenU = centerX + (camX / camZ) * focalLength
        val screenV = centerY - (camY / camZ) * focalLength
        return ProjectedPoint(screenU, screenV, camZ)
    }

    // --- 2. Render Reference Level Ground Plane (Z = 0) ---
    val groundGridColor = VIZ_GROUND_GRID_COLOR
    val groundGridBorder = VIZ_GROUND_GRID_BORDER
    val gridExtent = 2.4f
    val gridStep = 0.6f

    // Draw Ground Grid Lines
    var x = -gridExtent
    while (x <= gridExtent + 0.01f) {
        val p1 = project(x, -gridExtent, 0f)
        val p2 = project(x, gridExtent, 0f)
        if (p1 != null && p2 != null) {
            drawLine(
                color = if (abs(x) < 0.05f) groundGridBorder else groundGridColor,
                start = Offset(p1.u, p1.v),
                end = Offset(p2.u, p2.v),
                strokeWidth = if (abs(x) < 0.05f) 1.5f else 1f
            )
        }
        x += gridStep
    }

    var y = -gridExtent
    while (y <= gridExtent + 0.01f) {
        val p1 = project(-gridExtent, y, 0f)
        val p2 = project(gridExtent, y, 0f)
        if (p1 != null && p2 != null) {
            drawLine(
                color = if (abs(y) < 0.05f) groundGridBorder else groundGridColor,
                start = Offset(p1.u, p1.v),
                end = Offset(p2.u, p2.v),
                strokeWidth = if (abs(y) < 0.05f) 1.5f else 1f
            )
        }
        y += gridStep
    }

    // Concentric Range Rings on Ground Plane
    val ringRadii = floatArrayOf(0.8f, 1.6f, 2.4f)
    for (rIdx in ringRadii.indices) {
        val radius = ringRadii[rIdx]
        val ringPath = scratch.ringPath
        ringPath.reset()
        var firstPoint = true
        for (i in 0..36) {
            val angle = Math.toRadians((i * 10).toDouble()).toFloat()
            val rxRing = radius * cos(angle)
            val ryRing = radius * sin(angle)
            val proj = project(rxRing, ryRing, 0f)
            if (proj != null) {
                if (firstPoint) {
                    ringPath.moveTo(proj.u, proj.v)
                    firstPoint = false
                } else {
                    ringPath.lineTo(proj.u, proj.v)
                }
            }
        }
        drawPath(
            path = ringPath,
            color = VIZ_RING_COLOR,
            style = VIZ_RING_STROKE
        )
    }

    // Vehicle Forward Road Axis Arrow (+Y)
    val fwdStart = project(0f, 0f, 0f)
    val fwdEnd = project(0f, 2.2f, 0f)
    if (fwdStart != null && fwdEnd != null) {
        drawLine(
            color = VIZ_FWD_ARROW_COLOR,
            start = Offset(fwdStart.u, fwdStart.v),
            end = Offset(fwdEnd.u, fwdEnd.v),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
    }

    // --- 3. Compute Phone 3D Model Vertices in World Coordinates ---
    val hoverZ = 1.05f // Height of phone above ground plane

    // Compute effective rotation matrix tared to Road (+Y) axis:
    val yawTareRad = Math.toRadians(yawTareDeg.toDouble()).toFloat()
    val c = cos(yawTareRad)
    val s = sin(yawTareRad)

    val R = scratch.rotMat
    // Rotate world frame around Z by yawTareRad so phone boresight at tare points along +Y
    R[0] = c * telemetry.rotationMatrix[0] - s * telemetry.rotationMatrix[3]
    R[1] = c * telemetry.rotationMatrix[1] - s * telemetry.rotationMatrix[4]
    R[2] = c * telemetry.rotationMatrix[2] - s * telemetry.rotationMatrix[5]

    R[3] = s * telemetry.rotationMatrix[0] + c * telemetry.rotationMatrix[3]
    R[4] = s * telemetry.rotationMatrix[1] + c * telemetry.rotationMatrix[4]
    R[5] = s * telemetry.rotationMatrix[2] + c * telemetry.rotationMatrix[5]

    R[6] = telemetry.rotationMatrix[6]
    R[7] = telemetry.rotationMatrix[7]
    R[8] = telemetry.rotationMatrix[8]

    // Physical Smartphone proportions in native Android body frame:
    // +X: screen right, +Y: screen top, +Z: screen front (facing user)
    val pw = 0.74f / 2f  // Half-width across phone body (X)
    val ph = 1.48f / 2f  // Half-length along phone body (Y)
    val pt = 0.10f / 2f  // Half-thickness (Z)

    val worldCorners = scratch.worldCorners
    // 0: (-pw,-ph,-pt), 1: (pw,-ph,-pt), 2: (pw,ph,-pt), 3: (-pw,ph,-pt),
    // 4: (-pw,-ph,pt),  5: (pw,-ph,pt),  6: (pw,ph,pt),  7: (-pw,ph,pt)
    for (idx in 0 until 8) {
        val lx = if (idx == 1 || idx == 2 || idx == 5 || idx == 6) pw else -pw
        val ly = if (idx == 2 || idx == 3 || idx == 6 || idx == 7) ph else -ph
        val lz = if (idx >= 4) pt else -pt
        val dst = worldCorners[idx]
        dst[0] = R[0] * lx + R[1] * ly + R[2] * lz
        dst[1] = R[3] * lx + R[4] * ly + R[5] * lz
        dst[2] = R[6] * lx + R[7] * ly + R[8] * lz + hoverZ
    }

    // --- 4. Plumb Bob & Projected Drop Shadow ---
    val centerProj = project(0f, 0f, hoverZ)
    val groundShadowProj = project(0f, 0f, 0f)

    if (centerProj != null && groundShadowProj != null) {
        // Vertical plumb line
        drawLine(
            color = VIZ_PLUMB_COLOR,
            start = Offset(centerProj.u, centerProj.v),
            end = Offset(groundShadowProj.u, groundShadowProj.v),
            strokeWidth = 1.5f,
            pathEffect = VIZ_PLUMB_DASH
        )

        // Drop shadow disc on ground plane
        drawCircle(
            color = VIZ_SHADOW_COLOR,
            radius = 22f,
            center = Offset(groundShadowProj.u, groundShadowProj.v)
        )
        drawCircle(
            color = VIZ_SHADOW_DOT_COLOR,
            radius = 3.5f,
            center = Offset(groundShadowProj.u, groundShadowProj.v)
        )
    }

    // --- 5. Depth Sort & Render Phone Cuboid Faces ---
    val projectedCorners = scratch.projectedCorners
    for (i in 0 until 8) {
        val wc = worldCorners[i]
        projectedCorners[i] = project(wc[0], wc[1], wc[2])
    }

    // Sort faces from back to front (Painter's algorithm)
    val sortedFaces = VIZ_FACES.mapNotNull { face ->
        val p0 = projectedCorners[face.indices[0]]
        val p1 = projectedCorners[face.indices[1]]
        val p2 = projectedCorners[face.indices[2]]
        val p3 = projectedCorners[face.indices[3]]
        if (p0 != null && p1 != null && p2 != null && p3 != null) {
            val avgDepth = (p0.depth + p1.depth + p2.depth + p3.depth) * 0.25f
            SortedFace(face, listOf(p0, p1, p2, p3), avgDepth)
        } else null
    }.sortedByDescending { it.avgDepth }

    val facePath = scratch.facePath
    val innerPath = scratch.innerPath
    sortedFaces.forEach { sortedFace ->
        val face = sortedFace.face
        val projs = sortedFace.points
        facePath.reset()
        facePath.moveTo(projs[0].u, projs[0].v)
        facePath.lineTo(projs[1].u, projs[1].v)
        facePath.lineTo(projs[2].u, projs[2].v)
        facePath.lineTo(projs[3].u, projs[3].v)
        facePath.close()

        // Draw solid face
        drawPath(path = facePath, color = face.baseColor, style = Fill)
        // Draw chamfer border
        drawPath(path = facePath, color = face.borderColor, style = VIZ_FACE_BORDER_STROKE)

        // If screen face, draw inner luminous display grid
        if (face.name.startsWith("Screen")) {
            val p0 = Offset(projs[0].u, projs[0].v)
            val p1 = Offset(projs[1].u, projs[1].v)
            val p2 = Offset(projs[2].u, projs[2].v)
            val p3 = Offset(projs[3].u, projs[3].v)
            val inset = 0.12f
            val ip0 = p0 + (p2 - p0) * inset
            val ip1 = p1 + (p3 - p1) * inset
            val ip2 = p2 + (p0 - p2) * inset
            val ip3 = p3 + (p1 - p3) * inset

            innerPath.reset()
            innerPath.moveTo(ip0.x, ip0.y)
            innerPath.lineTo(ip1.x, ip1.y)
            innerPath.lineTo(ip2.x, ip2.y)
            innerPath.lineTo(ip3.x, ip3.y)
            innerPath.close()
            drawPath(path = innerPath, color = VIZ_SCREEN_FILL_COLOR, style = Fill)
            drawPath(path = innerPath, color = VIZ_SCREEN_STROKE_COLOR, style = VIZ_INNER_SCREEN_STROKE)
        }
    }

    // --- 6. Optical Boresight Ray (Camera Axis pointing out rear lens: -Z_phone) ---
    // Camera Lens location in local frame (Back chassis -pt, top-left of phone when viewed from back)
    val lensLocalX = 0.18f // Positive X is on the LEFT when looking at the back of the phone
    val lensLocalY = 0.50f
    val lensLocalZ = -pt - 0.01f

    val lensWx = R[0] * lensLocalX + R[1] * lensLocalY + R[2] * lensLocalZ
    val lensWy = R[3] * lensLocalX + R[4] * lensLocalY + R[5] * lensLocalZ
    val lensWz = R[6] * lensLocalX + R[7] * lensLocalY + R[8] * lensLocalZ + hoverZ

    // Optical boresight ray direction: -Z_phone = [-R2, -R5, -R8] in world coordinates
    val rayDirX = -R[2]
    val rayDirY = -R[5]
    val rayDirZ = -R[8]
    val rayLen = 2.2f

    val rayEndWx = lensWx + rayDirX * rayLen
    val rayEndWy = lensWy + rayDirY * rayLen
    val rayEndWz = lensWz + rayDirZ * rayLen

    val lensProj = project(lensWx, lensWy, lensWz)
    val rayEndProj = project(rayEndWx, rayEndWy, rayEndWz)

    if (lensProj != null && rayEndProj != null) {
        // Draw Camera Lens aperture badge
        drawCircle(
            color = Color(0xFF00E676),
            radius = 5.5f,
            center = Offset(lensProj.u, lensProj.v)
        )
        drawCircle(
            color = Color.Black,
            radius = 2.5f,
            center = Offset(lensProj.u, lensProj.v)
        )

        // Draw Boresight Beam
        drawLine(
            color = VIZ_RAY_COLOR,
            start = Offset(lensProj.u, lensProj.v),
            end = Offset(rayEndProj.u, rayEndProj.v),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )

        // Draw Target Pointer at end of ray
        drawCircle(
            color = Color(0xFF00E676),
            radius = 4f,
            center = Offset(rayEndProj.u, rayEndProj.v)
        )
    }

    // --- 7. Native HUD Canvas Text Labels ---
    drawContext.canvas.nativeCanvas.apply {
        if (fwdEnd != null) {
            drawText("ROAD (+Y)", fwdEnd.u - 40f, fwdEnd.v - 10f, scratch.labelPaint)
        }
        if (isOrtho) {
            drawText("SIDE PITCH: %+.1f°".format(telemetry.pitchDeg), 20f, canvasH - 20f, scratch.orthoPaint)
        }
    }
}
