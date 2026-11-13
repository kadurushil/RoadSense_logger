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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.DebugToggleSnapshot
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Pre-allocated Stroke styles & Colors to eliminate heap allocations inside 20 Hz Canvas loops
private val BEV_STROKE_RING = Stroke(width = 1.2f)
private val BEV_STROKE_CLUSTER = Stroke(width = 1.5f)
private val BEV_STROKE_FCW_OUTER = Stroke(width = 1.8f)
private val BEV_STROKE_FCW_FALLBACK_OUTER = Stroke(width = 2.0f)
private val BEV_STROKE_ACC_OUTER = Stroke(width = 1.5f)
private val BEV_STROKE_TRACK_PRIORITY = Stroke(width = 2.5f)
private val BEV_STROKE_TRACK_NORMAL = Stroke(width = 2.0f)

private val BEV_COLOR_GRID = Color(0xFF263238)
private val BEV_COLOR_RING = Color(0xFF37474F)
private val BEV_COLOR_FOV = Color(0x334DD0E1)
private val BEV_COLOR_CENTERLINE = Color(0x5500E5FF)
private val BEV_COLOR_EGO = Color(0xFF00E5FF)
private val BEV_COLOR_CLUSTER_FILL = Color(0x55FFD54F)
private val BEV_COLOR_CLUSTER_STROKE = Color(0xFFFFD54F)

private val BEV_COLOR_APPR = Color(0xFFFF5252)
private val BEV_COLOR_APPR_GLOW = Color(0x59FF5252)
private val BEV_COLOR_REC = Color(0xFF40C4FF)
private val BEV_COLOR_REC_GLOW = Color(0x5940C4FF)
private val BEV_COLOR_STAT = Color(0xFF69F0AE)
private val BEV_COLOR_STAT_GLOW = Color(0x5969F0AE)

private val BEV_COLOR_FCW = Color(0xFFFF1744)
private val BEV_COLOR_FCW_GLOW = Color(0x40FF1744)
private val BEV_COLOR_FCW_HALO = Color(0xA6FF1744)
private val BEV_COLOR_FCW_FALLBACK_HALO = Color(0xB3FF1744)
private val BEV_COLOR_ACC = Color(0xFF00E5FF)
private val BEV_COLOR_ACC_GLOW = Color(0x3800E5FF)
private val BEV_COLOR_ACC_HALO = Color(0x9900E5FF)
private val BEV_COLOR_TRACK_DEFAULT = Color(0xFFFFB300)
private val BEV_COLOR_TRACK_DEFAULT_FILL = Color(0x40FFB300)
private val BEV_COLOR_VEC_FCW = Color(0xFFFF5252)
private val BEV_COLOR_VEC_ACC = Color(0xFF80D8FF)
private val BEV_COLOR_VEC_DEFAULT = Color(0xFFFFD54F)

private class BevGeometryCache {
    val egoPath = Path()
    val triPath = Path()
    var cachedEgoW = -1f
    var cachedEgoH = -1f
}

private class BevDrawTimingState {
    var lastDrawStartNs: Long = 0L
}

/**
 * Bird's-Eye View (BEV) top-down Cartesian radar display.
 * Maps radar coordinates (X = lateral meters, Y = longitudinal meters) to screen pixels with 1:1 aspect ratio.
 */
@Composable
fun RadarBevPlot(
    viewModel: RadarViewModel,
    frame: RadarFrame? = null,
    frameProducer: (() -> RadarFrame?)? = null,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val maxRangeMeters by viewModel.radarMaxRange.collectAsState()
    val dynamicOnly by viewModel.radarDynamicOnly.collectAsState()
    val minSnrFilter by viewModel.radarMinSnr.collectAsState()
    val debugState by viewModel.debugToggleState.collectAsState()
    var showBenchStrip by remember(isLandscape) { mutableStateOf(!isLandscape) }
    val rangeOptions = remember { listOf(15f, 30f, 60f, 130f) }

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

            // BEV Canvas Box: isolated leaf composable so 20 Hz frames only invalidate Canvas draw phase
            RadarBevCanvas(
                viewModel = viewModel,
                staticFrame = frame,
                frameProducer = frameProducer,
                maxRangeMeters = maxRangeMeters,
                dynamicOnly = dynamicOnly,
                minSnrFilter = minSnrFilter,
                debugState = debugState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isLandscape) Modifier.weight(1f) else Modifier.height(280.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090B10))
            )

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

            // Filter Toggles, Bench Toggle & Target/Velocity Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filters + Compact Bench Expand Chip
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

                    Surface(
                        selected = showBenchStrip,
                        onClick = { showBenchStrip = !showBenchStrip },
                        shape = RoundedCornerShape(4.dp),
                        color = if (showBenchStrip) Color(0xFF2A2438) else Color(0xFF181D26),
                        border = BorderStroke(1.dp, if (showBenchStrip) Color(0xFFB388FF) else Color(0xFF263238)),
                        modifier = Modifier.height(if (isLandscape) 24.dp else 28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = if (isLandscape) 5.dp else 7.dp)) {
                            Text(
                                text = "Bench",
                                fontSize = if (isLandscape) 10.sp else 11.sp,
                                color = if (showBenchStrip) Color(0xFFD1C4E9) else Color(0xFF90A4AE),
                                fontWeight = if (showBenchStrip) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Legend: ADAS Targets & Point Cloud
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clearAndSetSemantics {}
                ) {
                    Text(text = "■ FCW", color = Color(0xFFFF1744), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.Bold)
                    Text(text = "■ ACC", color = Color(0xFF00E5FF), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.Bold)
                    Text(text = "■ Trk", color = Color(0xFFFFB300), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.Bold)
                    Text(text = "● Appr", color = Color(0xFFFF5252), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.Medium)
                    Text(text = "● Rec", color = Color(0xFF40C4FF), fontSize = if (isLandscape) 9.sp else 10.sp, fontWeight = FontWeight.Medium)
                }
            }

            if (showBenchStrip) {
                Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 6.dp))
                val isTimingActive = debugState.masterDebugEnabled && debugState.canvasTimingLogs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BevBenchToggleChip(
                        label = "Pts",
                        enabled = debugState.bevShowRawPoints,
                        isLandscape = isLandscape,
                        modifier = Modifier.weight(1f),
                        onToggle = { viewModel.setBevShowRawPoints(!debugState.bevShowRawPoints) }
                    )
                    BevBenchToggleChip(
                        label = "Trk",
                        enabled = debugState.bevShowTracks,
                        isLandscape = isLandscape,
                        modifier = Modifier.weight(1f),
                        onToggle = { viewModel.setBevShowTracks(!debugState.bevShowTracks) }
                    )
                    BevBenchToggleChip(
                        label = "Clst",
                        enabled = debugState.bevShowClusters,
                        isLandscape = isLandscape,
                        modifier = Modifier.weight(1f),
                        onToggle = { viewModel.setBevShowClusters(!debugState.bevShowClusters) }
                    )
                    BevBenchToggleChip(
                        label = "Grid",
                        enabled = debugState.bevShowGrid,
                        isLandscape = isLandscape,
                        modifier = Modifier.weight(1f),
                        onToggle = { viewModel.setBevShowGrid(!debugState.bevShowGrid) }
                    )
                    BevBenchToggleChip(
                        label = "Log",
                        enabled = isTimingActive,
                        accentColor = Color(0xFFFF8A65),
                        isLandscape = isLandscape,
                        modifier = Modifier.weight(1f),
                        onToggle = { viewModel.setBenchTimingLogging(!isTimingActive) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BevBenchToggleChip(
    label: String,
    enabled: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF69F0AE),
    onToggle: () -> Unit
) {
    Surface(
        selected = enabled,
        onClick = onToggle,
        shape = RoundedCornerShape(4.dp),
        color = if (enabled) accentColor.copy(alpha = 0.16f) else Color(0xFF161A22),
        border = BorderStroke(1.dp, if (enabled) accentColor else Color(0xFF263238)),
        modifier = modifier.height(if (isLandscape) 22.dp else 26.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (enabled) "$label ON" else "$label OFF",
                fontSize = if (isLandscape) 9.sp else 10.sp,
                color = if (enabled) accentColor else Color(0xFF78909C),
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/**
 * Isolated Canvas leaf for BEV scope drawing.
 * Reads frame state strictly inside DrawScope so 20 Hz updates skip parent composition.
 */
@Composable
private fun RadarBevCanvas(
    viewModel: RadarViewModel,
    staticFrame: RadarFrame?,
    frameProducer: (() -> RadarFrame?)?,
    maxRangeMeters: Float,
    dynamicOnly: Boolean,
    minSnrFilter: Boolean,
    debugState: DebugToggleSnapshot,
    modifier: Modifier = Modifier
) {
    // Only allocate fallback StateFlow collector when caller did not supply frameProducer
    val fallbackFrameState = if (frameProducer == null) viewModel.latestFrame.collectAsState() else null
    val enableTimingLog = debugState.masterDebugEnabled && debugState.canvasTimingLogs
    val showGrid = debugState.bevShowGrid
    val showClusters = debugState.bevShowClusters
    val showRawPoints = debugState.bevShowRawPoints
    val showTracks = debugState.bevShowTracks

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(160, 176, 190, 197)
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val trackLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val bannerPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(240, 255, 23, 68)
            textSize = 26f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val geometry = remember { BevGeometryCache() }
    val timingState = remember { BevDrawTimingState() }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
        ) {
            val drawStartNs = if (enableTimingLog) System.nanoTime() else 0L
            val activeFrame = frameProducer?.invoke() ?: fallbackFrameState?.value ?: staticFrame
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Radar Origin at bottom-center (with 28px bottom padding for ego marker)
            val originX = canvasWidth / 2f
            val originY = canvasHeight - 28f
            val usableHeight = originY - 16f

            // 1:1 Metric Scale (pixels per meter)
            val scale = usableHeight / maxRangeMeters

            if (showGrid) {
                // 1. Draw Field of View (FOV) cone lines (+-60 degrees)
                val fovRad = Math.toRadians(60.0).toFloat()
                val fovLen = usableHeight * 1.15f
                val fovLeftX = originX - sin(fovRad) * fovLen
                val fovLeftY = originY - cos(fovRad) * fovLen
                val fovRightX = originX + sin(fovRad) * fovLen
                val fovRightY = originY - cos(fovRad) * fovLen

                drawLine(BEV_COLOR_FOV, Offset(originX, originY), Offset(fovLeftX, fovLeftY), strokeWidth = 1.5f)
                drawLine(BEV_COLOR_FOV, Offset(originX, originY), Offset(fovRightX, fovRightY), strokeWidth = 1.5f)

                // 2. Draw Longitudinal Centerline (Ego Forward Vector)
                drawLine(
                    color = BEV_COLOR_CENTERLINE,
                    start = Offset(originX, originY),
                    end = Offset(originX, 10f),
                    strokeWidth = 1.5f
                )

                // 3. Draw Range Rings & Labels (pre-allocated Stroke)
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

                    drawArc(
                        color = BEV_COLOR_RING,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(left, top),
                        size = Size(diameter, diameter),
                        style = BEV_STROKE_RING
                    )

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
                        color = BEV_COLOR_GRID,
                        start = Offset(screenRightX, originY),
                        end = Offset(screenRightX, originY - 12f),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = BEV_COLOR_GRID,
                        start = Offset(screenLeftX, originY),
                        end = Offset(screenLeftX, originY - 12f),
                        strokeWidth = 1f
                    )

                    latX += lateralStep
                }
            }

            // 5. Draw Ego Vehicle Marker (cached Path rebuilt only when Canvas dimensions change)
            if (geometry.cachedEgoW != canvasWidth || geometry.cachedEgoH != canvasHeight) {
                val egoWidth = 24f
                val egoHeight = 32f
                geometry.egoPath.reset()
                geometry.egoPath.moveTo(originX, originY - egoHeight)
                geometry.egoPath.lineTo(originX + egoWidth / 2f, originY)
                geometry.egoPath.lineTo(originX - egoWidth / 2f, originY)
                geometry.egoPath.close()
                geometry.cachedEgoW = canvasWidth
                geometry.cachedEgoH = canvasHeight
            }
            drawPath(path = geometry.egoPath, color = BEV_COLOR_EGO)

            // 6. Draw Clusters (TLV Type 2)
            if (showClusters) {
                activeFrame?.clusters?.forEach { cluster ->
                    val cx = originX + cluster.x * scale
                    val cy = originY - cluster.y * scale
                    if (cy in 0f..originY && cx in 0f..canvasWidth) {
                        val r = 8f
                        drawCircle(
                            color = BEV_COLOR_CLUSTER_FILL,
                            center = Offset(cx, cy),
                            radius = r
                        )
                        drawCircle(
                            color = BEV_COLOR_CLUSTER_STROKE,
                            center = Offset(cx, cy),
                            radius = r,
                            style = BEV_STROKE_CLUSTER
                        )
                    }
                }
            }

            // 7. Draw Point Cloud Reflections (TLV Type 1)
            if (showRawPoints) {
                activeFrame?.points?.forEach { pt ->
                    val passDynamic = !dynamicOnly || kotlin.math.abs(pt.doppler) > 0.35f
                    val passSnr = !minSnrFilter || pt.snrDb >= 15f
                    if (passDynamic && passSnr) {
                        val px = originX + pt.x * scale
                        val py = originY - pt.y * scale

                        if (py in 0f..originY && px in 0f..canvasWidth) {
                            val glowColor: Color
                            val pointColor: Color
                            when {
                                pt.doppler < -0.35f -> {
                                    glowColor = BEV_COLOR_APPR_GLOW
                                    pointColor = BEV_COLOR_APPR
                                }
                                pt.doppler > 0.35f -> {
                                    glowColor = BEV_COLOR_REC_GLOW
                                    pointColor = BEV_COLOR_REC
                                }
                                else -> {
                                    glowColor = BEV_COLOR_STAT_GLOW
                                    pointColor = BEV_COLOR_STAT
                                }
                            }

                            val dotRadius = 4.5f
                            drawCircle(
                                color = glowColor,
                                radius = dotRadius + 2.5f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = pointColor,
                                radius = dotRadius,
                                center = Offset(px, py)
                            )
                        }
                    }
                }
            }

            // 8. Draw Active Tracked Targets (TLV Type 3) with ADAS highlighting (FCW & ACC)
            if (showTracks) {
                val fcwAlert = activeFrame?.canOutputs?.fcw
                val accTarget = activeFrame?.canOutputs?.acc
                val fcwTrackId = if ((fcwAlert?.trackId ?: 0) > 0) fcwAlert?.trackId else null
                val accPoiId = if ((accTarget?.poiId ?: 0) > 0) accTarget?.poiId else null

                val tracksList = activeFrame?.tracks ?: emptyList()
                var matchedFcwIndex: Int? = null
                if (fcwAlert != null && fcwAlert.stage > 0) {
                    matchedFcwIndex = tracksList.indexOfFirst { track ->
                        fcwTrackId != null && (track.tid == fcwTrackId || track.clusterId == fcwTrackId || (track.tid % 256) == fcwTrackId)
                    }.takeIf { it >= 0 }

                    if (matchedFcwIndex == null && fcwAlert.targetY > 0.5f) {
                        matchedFcwIndex = tracksList.indexOfFirst { track ->
                            kotlin.math.abs(track.y - fcwAlert.targetY) < 4.0f || kotlin.math.hypot(track.x - fcwAlert.targetX, track.y - fcwAlert.targetY) < 5.0f
                        }.takeIf { it >= 0 }
                    }

                    if (matchedFcwIndex == null) {
                        var bestRiskScore = -Float.MAX_VALUE
                        var bestIdx = -1
                        for (idx in tracksList.indices) {
                            val track = tracksList[idx]
                            val inCorridor = kotlin.math.abs(track.x) <= 4.5f
                            if (track.risk >= 2 && inCorridor && track.y <= 50.0f) {
                                val score = track.risk * 100f - track.y
                                if (score > bestRiskScore) {
                                    bestRiskScore = score
                                    bestIdx = idx
                                }
                            }
                        }
                        if (bestIdx == -1) {
                            for (idx in tracksList.indices) {
                                val track = tracksList[idx]
                                val inCorridor = kotlin.math.abs(track.x) <= 4.5f
                                if (track.risk >= 1 && track.vy < -1.0f && inCorridor) {
                                    val score = track.risk * 100f - track.y
                                    if (score > bestRiskScore) {
                                        bestRiskScore = score
                                        bestIdx = idx
                                    }
                                }
                            }
                        }
                        if (bestIdx >= 0) {
                            matchedFcwIndex = bestIdx
                        }
                    }
                }

                var matchedAccIndex: Int? = null
                if (accTarget != null && (accPoiId != null || accTarget.targetY > 0.5f)) {
                    matchedAccIndex = tracksList.indexOfFirst { track ->
                        accPoiId != null && (track.tid == accPoiId || track.clusterId == accPoiId || (track.tid % 256) == accPoiId)
                    }.takeIf { it >= 0 }

                    if (matchedAccIndex == null && accTarget.targetY > 0.5f) {
                        matchedAccIndex = tracksList.indexOfFirst { track ->
                            kotlin.math.abs(track.y - accTarget.targetY) < 4.0f || kotlin.math.hypot(track.x - accTarget.targetX, track.y - accTarget.targetY) < 5.0f
                        }.takeIf { it >= 0 }
                    }
                }

                tracksList.forEachIndexed { index, track ->
                    val tx = originX + track.x * scale
                    val ty = originY - track.y * scale

                    if (ty in 0f..originY && tx in 0f..canvasWidth) {
                        val isFcw = (index == matchedFcwIndex)
                        val isAcc = (index == matchedAccIndex)

                        val trackColor = when {
                            isFcw -> BEV_COLOR_FCW
                            isAcc -> BEV_COLOR_ACC
                            else -> BEV_COLOR_TRACK_DEFAULT
                        }
                        val trackFillColor = when {
                            isFcw -> BEV_COLOR_FCW_GLOW
                            isAcc -> BEV_COLOR_ACC_GLOW
                            else -> BEV_COLOR_TRACK_DEFAULT_FILL
                        }

                        val trackRadius = if (isFcw || isAcc) 16f else 14f

                        if (isFcw) {
                            drawCircle(
                                color = BEV_COLOR_FCW_GLOW,
                                center = Offset(tx, ty),
                                radius = trackRadius + 7f
                            )
                            drawCircle(
                                color = BEV_COLOR_FCW_HALO,
                                center = Offset(tx, ty),
                                radius = trackRadius + 3.5f,
                                style = BEV_STROKE_FCW_OUTER
                            )
                        } else if (isAcc) {
                            drawCircle(
                                color = BEV_COLOR_ACC_GLOW,
                                center = Offset(tx, ty),
                                radius = trackRadius + 6f
                            )
                            drawCircle(
                                color = BEV_COLOR_ACC_HALO,
                                center = Offset(tx, ty),
                                radius = trackRadius + 3f,
                                style = BEV_STROKE_ACC_OUTER
                            )
                        }

                        drawCircle(
                            color = trackFillColor,
                            center = Offset(tx, ty),
                            radius = trackRadius
                        )
                        drawCircle(
                            color = trackColor,
                            center = Offset(tx, ty),
                            radius = trackRadius,
                            style = if (isFcw || isAcc) BEV_STROKE_TRACK_PRIORITY else BEV_STROKE_TRACK_NORMAL
                        )
                        drawCircle(
                            color = trackColor,
                            center = Offset(tx, ty),
                            radius = 3.5f
                        )

                        val vxPx = track.vx * scale * 0.75f
                        val vyPx = -track.vy * scale * 0.75f
                        val vSpeed = hypot(vxPx, vyPx)

                        val vectorColor = when {
                            isFcw -> BEV_COLOR_VEC_FCW
                            isAcc -> BEV_COLOR_VEC_ACC
                            else -> BEV_COLOR_VEC_DEFAULT
                        }

                        if (vSpeed > 0.8f) {
                            val theta = atan2(vyPx, vxPx)
                            val ux = cos(theta)
                            val uy = sin(theta)
                            val px = -uy
                            val py = ux

                            val circX = tx + ux * trackRadius
                            val circY = ty + uy * trackRadius

                            val triLen = 6.5f
                            val triWidth = 4f
                            val tipX = circX + ux * triLen
                            val tipY = circY + uy * triLen
                            val b1X = circX - ux * 2f + px * triWidth
                            val b1Y = circY - uy * 2f + py * triWidth
                            val b2X = circX - ux * 2f - px * triWidth
                            val b2Y = circY - uy * 2f - py * triWidth

                            geometry.triPath.reset()
                            geometry.triPath.moveTo(tipX, tipY)
                            geometry.triPath.lineTo(b1X, b1Y)
                            geometry.triPath.lineTo(b2X, b2Y)
                            geometry.triPath.close()
                            drawPath(path = geometry.triPath, color = vectorColor)

                            if (vSpeed > trackRadius) {
                                val endX = tx + vxPx
                                val endY = ty + vyPx
                                drawLine(
                                    color = vectorColor,
                                    start = Offset(tipX, tipY),
                                    end = Offset(endX, endY),
                                    strokeWidth = 2.0f
                                )
                                drawCircle(
                                    color = vectorColor,
                                    radius = 3.0f,
                                    center = Offset(endX, endY)
                                )
                            }
                        }

                        val adasTag = when {
                            isFcw && isAcc -> " [FCW+ACC]"
                            isFcw -> " [FCW]"
                            isAcc -> " [ACC]"
                            else -> ""
                        }
                        val labelText = "#${track.tid}$adasTag"
                        trackLabelPaint.color = when {
                            isFcw -> android.graphics.Color.argb(255, 255, 82, 82)
                            isAcc -> android.graphics.Color.argb(255, 128, 216, 255)
                            else -> android.graphics.Color.WHITE
                        }

                        drawContext.canvas.nativeCanvas.drawText(
                            labelText,
                            tx,
                            ty - trackRadius - 6f,
                            trackLabelPaint
                        )
                    }
                }

                // 8.5 Draw Fallback FCW Target if firmware has active alert but track list didn't associate
                if (fcwAlert != null && fcwAlert.stage > 0 && matchedFcwIndex == null && fcwAlert.targetY > 0.5f) {
                    val ftx = originX + fcwAlert.targetX * scale
                    val fty = originY - fcwAlert.targetY * scale
                    if (fty in 0f..originY && ftx in 0f..canvasWidth) {
                        val fRadius = 16f
                        drawCircle(color = BEV_COLOR_FCW_GLOW, center = Offset(ftx, fty), radius = fRadius + 8f)
                        drawCircle(color = BEV_COLOR_FCW_FALLBACK_HALO, center = Offset(ftx, fty), radius = fRadius + 4f, style = BEV_STROKE_FCW_FALLBACK_OUTER)
                        drawCircle(color = BEV_COLOR_FCW, center = Offset(ftx, fty), radius = fRadius, style = BEV_STROKE_TRACK_PRIORITY)
                        drawCircle(color = BEV_COLOR_FCW, center = Offset(ftx, fty), radius = 4f)

                        trackLabelPaint.color = android.graphics.Color.argb(255, 255, 82, 82)
                        drawContext.canvas.nativeCanvas.drawText(
                            "[FCW S${fcwAlert.stage}]",
                            ftx,
                            fty - fRadius - 6f,
                            trackLabelPaint
                        )
                    }
                }

                // 8.6 Draw Top-of-Canvas FCW Warning Banner if FCW is active
                if (fcwAlert != null && fcwAlert.stage > 0) {
                    val stageText = if (fcwAlert.stage == 2) "FCW HAZARD: AUDIBLE ALERT" else "FCW: VISUAL WARNING"
                    val ttcText = if (fcwAlert.ttcSec > 0f) " • TTC ${"%.1f".format(fcwAlert.ttcSec)}s" else ""
                    val distText = if (fcwAlert.targetY > 0f) " • ${"%.1f".format(fcwAlert.targetY)}m" else ""
                    val bannerText = "$stageText$ttcText$distText"

                    drawContext.canvas.nativeCanvas.drawText(
                        bannerText,
                        originX,
                        24f,
                        bannerPaint
                    )
                }
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
                    "RadarBevPlot",
                    String.format(
                        Locale.US,
                        "[DRAW_BEV] seq=#%d | deltaDrawMs=%.2f | durationMs=%.2f",
                        seqNum,
                        deltaDrawMs,
                        durationMs
                    )
                )
            }
        }
    }
}
