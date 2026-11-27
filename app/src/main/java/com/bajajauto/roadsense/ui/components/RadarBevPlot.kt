package com.bajajauto.roadsense.ui.components

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.DebugToggleSnapshot
import com.bajajauto.roadsense.diagnostics.TrackRecomposition
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.gl.BevCameraPreset
import com.bajajauto.roadsense.ui.components.gl.BevColorMap
import com.bajajauto.roadsense.ui.components.gl.BevPanelColorizer
import com.bajajauto.roadsense.ui.components.gl.BevPointColorMode
import com.bajajauto.roadsense.ui.components.gl.BevProjectionMath
import com.bajajauto.roadsense.ui.components.gl.BevTrackAssociator
import com.bajajauto.roadsense.ui.components.gl.RadarGlBevView
import com.bajajauto.roadsense.ui.components.gl.isOpenGlEs3Supported
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
private val BEV_COLOR_CLUSTER_FILL = Color(0x2400E676)
private val BEV_COLOR_CLUSTER_STROKE = Color(0xFF00E676)

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

private fun createBevPointPaint(argbColor: Int): Paint = Paint().apply {
    color = argbColor
    strokeWidth = 9.2f
    strokeCap = Paint.Cap.SQUARE
    isAntiAlias = false
}

private fun ensurePointBuffer(buf: FloatArray, required: Int): FloatArray =
    if (required <= buf.size) buf else buf.copyOf((buf.size * 2).coerceAtLeast(required))

private class BevGeometryCache {
    val egoPath = Path()
    val triPath = Path()
    var cachedGridW = -1f
    var cachedGridH = -1f
    var cachedGridRange = -1f
    var cachedGridShow = false

    var fovLeftX = 0f
    var fovLeftY = 0f
    var fovRightX = 0f
    var fovRightY = 0f

    var ringCount = 0
    val ringLefts = FloatArray(16)
    val ringTops = FloatArray(16)
    val ringDiams = FloatArray(16)
    val ringLabelYs = FloatArray(16)
    val ringLabelTexts = Array(16) { "" }

    var latTickCount = 0
    val latTickDx = FloatArray(32)

    var apprPts = FloatArray(512)
    var recPts = FloatArray(512)
    var statPts = FloatArray(512)
    val apprPointPaint = createBevPointPaint(0xFFFF5252.toInt())
    val recPointPaint = createBevPointPaint(0xFF40C4FF.toInt())
    val statPointPaint = createBevPointPaint(0xFF69F0AE.toInt())
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
    isLandscape: Boolean = false,
    showChrome: Boolean = true,
    showLegend: Boolean = showChrome,
    onFullscreenClick: (() -> Unit)? = null,
    onCameraModifiedChanged: (Boolean) -> Unit = {}
) {
    TrackRecomposition("RadarBevPlot")
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val maxRangeMeters by viewModel.radarMaxRange.collectAsState()
    val dynamicOnly by viewModel.radarDynamicOnly.collectAsState()
    val minSnrFilter by viewModel.radarMinSnr.collectAsState()
    val debugState by viewModel.debugToggleState.collectAsState()

    var glInitFailed by remember { mutableStateOf(false) }
    var isGlCameraModified by remember { mutableStateOf(false) }
    val gles3Available = remember(context, isInspection) {
        !isInspection && isOpenGlEs3Supported(context)
    }
    // Portrait RadarDashboardCard embeds RadarBevPlot inside verticalScroll(); keep Tier A Skia Canvas there
    // so SurfaceView never lags/clips while scrolling. Landscape Radar, SBS, and Fullscreen SBS use GLES 3.0.
    val canMountGlSurface = gles3Available && !glInitFailed && (isLandscape || !showChrome) && frame == null
    val useGlBackend = debugState.useGlBevRenderer && canMountGlSurface
    val is3dActive = useGlBackend && debugState.bev3dMode

    LaunchedEffect(useGlBackend) {
        if (!useGlBackend) {
            isGlCameraModified = false
            onCameraModifiedChanged(false)
        }
    }

    if (!showChrome) {
        Box(modifier = modifier.background(Color(0xFF090B10))) {
            if (useGlBackend) {
                RadarGlBevView(
                    viewModel = viewModel,
                    maxRangeMeters = maxRangeMeters,
                    dynamicOnly = dynamicOnly,
                    minSnrFilter = minSnrFilter,
                    debugState = debugState,
                    onGlInitError = { glInitFailed = true },
                    onCameraModifiedChanged = { modified ->
                        isGlCameraModified = modified
                        onCameraModifiedChanged(modified)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                )
            } else {
                RadarBevCanvas(
                    viewModel = viewModel,
                    staticFrame = frame,
                    frameProducer = frameProducer,
                    maxRangeMeters = maxRangeMeters,
                    dynamicOnly = dynamicOnly,
                    minSnrFilter = minSnrFilter,
                    debugState = debugState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF090B10))
                )
            }
            if (showLegend) {
                val effectiveColorMode = if (useGlBackend) {
                    debugState.bevPointColorMode.resolveForView(is3dActive)
                } else {
                    BevPointColorMode.VELOCITY
                }
                BevRadarLegendBar(
                    effectiveColorMode = effectiveColorMode,
                    colorMap = debugState.bevColorMap,
                    maxRangeMeters = maxRangeMeters,
                    flipZ = debugState.bevFlipZ,
                    isLandscape = isLandscape,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                )
            }
        }
        return
    }

    var showBenchStrip by remember(isLandscape) { mutableStateOf(!isLandscape) }
    val rangeOptions = remember { listOf(15f, 30f, 60f, 100f, 150f) }

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
                        text = if (is3dActive) "3D Radar (${maxRangeMeters.toInt()}m)" else "BEV Scope (${maxRangeMeters.toInt()}m)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (is3dActive) Color(0xFF69F0AE) else Color(0xFF80D8FF),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
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
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                                    Text(
                                        text = "${range.toInt()}m",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        if (onFullscreenClick != null) {
                            Surface(
                                onClick = onFullscreenClick,
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF1E2530),
                                border = BorderStroke(1.dp, Color(0xFF37474F)),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Fullscreen Radar BEV",
                                        tint = Color(0xFF80D8FF),
                                        modifier = Modifier.size(16.dp)
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
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Max: ${maxRangeMeters.toInt()}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF80D8FF),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (onFullscreenClick != null) {
                            Surface(
                                onClick = onFullscreenClick,
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF1E2530),
                                border = BorderStroke(1.dp, Color(0xFF37474F)),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Fullscreen Radar BEV",
                                        tint = Color(0xFF80D8FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
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

            // BEV Viewport: Strictly mutually exclusive — only the active renderer is mounted & running.
            // Floating compact legend sits in the unused upper-left corner to maximize vertical plot height.
            val viewportSlotModifier = Modifier
                .fillMaxWidth()
                .then(if (isLandscape) Modifier.weight(1f) else Modifier.height(280.dp))
            Box(modifier = viewportSlotModifier) {
                if (useGlBackend) {
                    RadarGlBevView(
                        viewModel = viewModel,
                        maxRangeMeters = maxRangeMeters,
                        dynamicOnly = dynamicOnly,
                        minSnrFilter = minSnrFilter,
                        debugState = debugState,
                        onGlInitError = { glInitFailed = true },
                        onCameraModifiedChanged = { isGlCameraModified = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                    )
                } else {
                    RadarBevCanvas(
                        viewModel = viewModel,
                        staticFrame = frame,
                        frameProducer = frameProducer,
                        maxRangeMeters = maxRangeMeters,
                        dynamicOnly = dynamicOnly,
                        minSnrFilter = minSnrFilter,
                        debugState = debugState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF090B10))
                    )
                }

                // Sleek single-row translucent legend bar pinned along the top edge (reflects active scheme & colormap)
                if (showLegend) {
                    val effectiveColorMode = if (useGlBackend) {
                        debugState.bevPointColorMode.resolveForView(is3dActive)
                    } else {
                        BevPointColorMode.VELOCITY
                    }
                    BevRadarLegendBar(
                        effectiveColorMode = effectiveColorMode,
                        colorMap = debugState.bevColorMap,
                        maxRangeMeters = maxRangeMeters,
                        flipZ = debugState.bevFlipZ,
                        isLandscape = isLandscape,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 6.dp))

            // Equally spaced full-width footer button bar (never overflows when Reset appears)
            val footerBtnHeight = if (isLandscape) 24.dp else 28.dp
            val footerFont = if (isLandscape) 9.5.sp else 10.5.sp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (useGlBackend) {
                    val isExplicitColorOverride = debugState.bevPointColorMode != BevPointColorMode.AUTO
                    Surface(
                        selected = isExplicitColorOverride,
                        onClick = { viewModel.cycleBevPointColorMode() },
                        shape = RoundedCornerShape(4.dp),
                        color = if (isExplicitColorOverride) Color(0xFF16323A) else Color(0xFF181D26),
                        border = BorderStroke(
                            1.dp,
                            if (isExplicitColorOverride) Color(0xFF4DD0E1) else Color(0xFF37474F)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(footerBtnHeight)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                            Text(
                                text = "Col:${debugState.bevPointColorMode.shortLabel}",
                                fontSize = footerFont,
                                color = if (isExplicitColorOverride) Color(0xFF80DEEA) else Color(0xFFB0BEC5),
                                fontWeight = if (isExplicitColorOverride) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Surface(
                        selected = dynamicOnly,
                        onClick = { viewModel.setRadarDynamicOnly(!dynamicOnly) },
                        shape = RoundedCornerShape(4.dp),
                        color = if (dynamicOnly) Color(0xFF37474F) else Color(0xFF181D26),
                        border = BorderStroke(1.dp, if (dynamicOnly) Color(0xFF80D8FF) else Color(0xFF263238)),
                        modifier = Modifier
                            .weight(1f)
                            .height(footerBtnHeight)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                            Text(
                                text = "Moving",
                                fontSize = footerFont,
                                color = if (dynamicOnly) Color(0xFF80D8FF) else Color(0xFF90A4AE),
                                fontWeight = if (dynamicOnly) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Surface(
                    selected = minSnrFilter,
                    onClick = { viewModel.setRadarMinSnr(!minSnrFilter) },
                    shape = RoundedCornerShape(4.dp),
                    color = if (minSnrFilter) Color(0xFF37474F) else Color(0xFF181D26),
                    border = BorderStroke(1.dp, if (minSnrFilter) Color(0xFF80D8FF) else Color(0xFF263238)),
                    modifier = Modifier
                        .weight(1f)
                        .height(footerBtnHeight)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                        Text(
                            text = "SNR≥15",
                            fontSize = footerFont,
                            color = if (minSnrFilter) Color(0xFF80D8FF) else Color(0xFF90A4AE),
                            fontWeight = if (minSnrFilter) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    selected = useGlBackend,
                    enabled = canMountGlSurface,
                    onClick = { viewModel.setUseGlBevRenderer(!debugState.useGlBevRenderer) },
                    shape = RoundedCornerShape(4.dp),
                    color = if (useGlBackend) Color(0xFF102A22) else Color(0xFF2B2112),
                    border = BorderStroke(
                        1.dp,
                        if (useGlBackend) Color(0xFF69F0AE) else Color(0xFFFFB300)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(footerBtnHeight)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                        Text(
                            text = if (useGlBackend) "GL" else "Skia",
                            fontSize = footerFont,
                            color = if (useGlBackend) Color(0xFF69F0AE) else Color(0xFFFFD54F),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (useGlBackend) {
                    Surface(
                        selected = is3dActive,
                        onClick = { viewModel.setBev3dMode(!debugState.bev3dMode) },
                        shape = RoundedCornerShape(4.dp),
                        color = if (is3dActive) Color(0xFF0E2F3A) else Color(0xFF181D26),
                        border = BorderStroke(
                            1.dp,
                            if (is3dActive) Color(0xFF00E5FF) else Color(0xFF37474F)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(footerBtnHeight)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                            Text(
                                text = if (is3dActive) "3D" else "2D",
                                fontSize = footerFont,
                                color = if (is3dActive) Color(0xFF00E5FF) else Color(0xFFB0BEC5),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (is3dActive || isGlCameraModified) {
                        Surface(
                            onClick = { viewModel.requestBevCameraReset() },
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF261D18),
                            border = BorderStroke(1.dp, Color(0xFFFFAB40)),
                            modifier = Modifier
                                .weight(1f)
                                .height(footerBtnHeight)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                                Text(
                                    text = "Reset",
                                    fontSize = footerFont,
                                    color = Color(0xFFFFCC80),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                Surface(
                    selected = showBenchStrip,
                    onClick = { showBenchStrip = !showBenchStrip },
                    shape = RoundedCornerShape(4.dp),
                    color = if (showBenchStrip) Color(0xFF2A2438) else Color(0xFF181D26),
                    border = BorderStroke(1.dp, if (showBenchStrip) Color(0xFFB388FF) else Color(0xFF263238)),
                    modifier = Modifier
                        .weight(1f)
                        .height(footerBtnHeight)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                        Text(
                            text = "Bench",
                            fontSize = footerFont,
                            color = if (showBenchStrip) Color(0xFFD1C4E9) else Color(0xFF90A4AE),
                            fontWeight = if (showBenchStrip) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (showBenchStrip) {
                Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 6.dp))
                val isTimingActive = debugState.masterDebugEnabled && debugState.canvasTimingLogs

                // Row 1: Point Filter + Foxglove Appearance Controls (horizontalScroll so never crunched)
                if (useGlBackend) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BevPanelActionChip(
                            label = if (dynamicOnly) "Moving ON" else "Moving OFF",
                            active = dynamicOnly,
                            accentColor = Color(0xFF80D8FF),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setRadarDynamicOnly(!dynamicOnly) }
                        )
                        BevPanelActionChip(
                            label = "Map:${debugState.bevColorMap.shortLabel}",
                            active = debugState.bevPointColorMode.usesColormap(is3dActive),
                            accentColor = Color(0xFFB388FF),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevColorMap(debugState.bevColorMap.next()) }
                        )
                        BevPanelActionChip(
                            label = "Sz:${debugState.bevPointSizePreset.shortLabel}",
                            active = true,
                            accentColor = Color(0xFF80CBC4),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevPointSizePreset(debugState.bevPointSizePreset.next()) }
                        )
                        BevPanelActionChip(
                            label = "Pt:${debugState.bevPointShape.symbolLabel}",
                            active = true,
                            accentColor = Color(0xFF80CBC4),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevPointShape(debugState.bevPointShape.toggle()) }
                        )
                        BevPanelActionChip(
                            label = if (debugState.bevFlipZ) "FlipZ ON" else "FlipZ OFF",
                            active = debugState.bevFlipZ,
                            accentColor = Color(0xFFFFAB40),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevFlipZ(!debugState.bevFlipZ) }
                        )
                        if (is3dActive) {
                            BevPanelActionChip(
                                label = if (debugState.bevStixelsEnabled) "Stix ON" else "Stix OFF",
                                active = debugState.bevStixelsEnabled,
                                accentColor = Color(0xFF00E5FF),
                                isLandscape = isLandscape,
                                onClick = { viewModel.setBevStixelsEnabled(!debugState.bevStixelsEnabled) }
                            )
                        }
                        BevPanelActionChip(
                            label = if (debugState.bevHideOutliers) "HideOut ON" else "HideOut OFF",
                            active = debugState.bevHideOutliers,
                            accentColor = Color(0xFFFF7043),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevHideOutliers(!debugState.bevHideOutliers) }
                        )
                        BevPanelActionChip(
                            label = "Decay:${debugState.bevDecayPreset.shortLabel}",
                            active = debugState.bevDecayPreset.frameCount > 1,
                            accentColor = Color(0xFF00E5FF),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevDecayPreset(debugState.bevDecayPreset.next()) }
                        )
                    }
                    Spacer(modifier = Modifier.height(if (isLandscape) 3.dp else 4.dp))
                }

                // Row 2: Geometry Layers, TLV 4 Road Lanes, 3D Camera Angle Presets & Log
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BevPanelActionChip(
                        label = if (debugState.bevShowRawPoints) "Pts ON" else "Pts OFF",
                        active = debugState.bevShowRawPoints,
                        accentColor = Color(0xFF69F0AE),
                        isLandscape = isLandscape,
                        onClick = { viewModel.setBevShowRawPoints(!debugState.bevShowRawPoints) }
                    )
                    BevPanelActionChip(
                        label = if (debugState.bevShowTracks) "Trk ON" else "Trk OFF",
                        active = debugState.bevShowTracks,
                        accentColor = Color(0xFF69F0AE),
                        isLandscape = isLandscape,
                        onClick = { viewModel.setBevShowTracks(!debugState.bevShowTracks) }
                    )
                    BevPanelActionChip(
                        label = if (debugState.bevShowClusters) "Clst ON" else "Clst OFF",
                        active = debugState.bevShowClusters,
                        accentColor = Color(0xFF69F0AE),
                        isLandscape = isLandscape,
                        onClick = { viewModel.setBevShowClusters(!debugState.bevShowClusters) }
                    )
                    if (useGlBackend) {
                        BevPanelActionChip(
                            label = if (debugState.bevShowRoadLanes) "Lanes ON" else "Lanes OFF",
                            active = debugState.bevShowRoadLanes,
                            accentColor = Color(0xFF76FF03),
                            isLandscape = isLandscape,
                            onClick = { viewModel.setBevShowRoadLanes(!debugState.bevShowRoadLanes) }
                        )
                    }
                    BevPanelActionChip(
                        label = if (debugState.bevShowGrid) "Grid ON" else "Grid OFF",
                        active = debugState.bevShowGrid,
                        accentColor = Color(0xFF69F0AE),
                        isLandscape = isLandscape,
                        onClick = { viewModel.setBevShowGrid(!debugState.bevShowGrid) }
                    )
                    if (is3dActive) {
                        BevCameraPreset.entries.forEach { preset ->
                            BevPanelActionChip(
                                label = "Cam:${preset.shortLabel}",
                                active = false,
                                accentColor = Color(0xFFFFAB40),
                                isLandscape = isLandscape,
                                onClick = { viewModel.requestBevCameraPreset(preset) }
                            )
                        }
                    }
                    BevPanelActionChip(
                        label = if (isTimingActive) "Log ON" else "Log OFF",
                        active = isTimingActive,
                        accentColor = Color(0xFFFF8A65),
                        isLandscape = isLandscape,
                        onClick = { viewModel.setBenchTimingLogging(!isTimingActive) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BevRadarLegendBar(
    effectiveColorMode: BevPointColorMode,
    colorMap: BevColorMap,
    maxRangeMeters: Float,
    flipZ: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val legendFont = if (isLandscape) 8.sp else 9.sp
    val gradientStops = remember(colorMap) {
        BevPanelColorizer.evalColorMapStopsArgb(colorMap, steps = 9).map { Color(it) }
    }

    Row(
        modifier = modifier
            .background(Color(0xA6090B10), RoundedCornerShape(4.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "■FCW", color = Color(0xFFFF1744), fontSize = legendFont, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(text = "■ACC", color = Color(0xFF00E5FF), fontSize = legendFont, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(text = "■Trk", color = Color(0xFFFFB300), fontSize = legendFont, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(text = "|", color = Color(0xFF455A64), fontSize = legendFont, maxLines = 1, softWrap = false)

        when (effectiveColorMode) {
            BevPointColorMode.RANGE,
            BevPointColorMode.SNR,
            BevPointColorMode.ELEVATION_Z -> {
                val (minLabel, maxLabel) = when (effectiveColorMode) {
                    BevPointColorMode.RANGE -> "0m" to "${maxRangeMeters.toInt()}m"
                    BevPointColorMode.SNR -> "Low dB" to "High dB"
                    BevPointColorMode.ELEVATION_Z -> {
                        val suffix = if (flipZ) " (Z-)" else ""
                        "${BevPanelColorizer.ELEV_MIN_M.toInt()}m" to "+${BevPanelColorizer.ELEV_MAX_M.toInt()}m$suffix"
                    }
                    else -> "0" to "1"
                }
                Text(
                    text = "${colorMap.shortLabel}:",
                    color = Color(0xFFD1C4E9),
                    fontSize = legendFont,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = minLabel,
                    color = gradientStops.first(),
                    fontSize = legendFont,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(gradientStops))
                )
                Text(
                    text = maxLabel,
                    color = gradientStops.last(),
                    fontSize = legendFont,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }
            BevPointColorMode.OUTLIER -> {
                Text(text = "●Inlier", color = Color(0xFF24E8A6), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Text(text = "●Outlier", color = Color(0xFFFD543D), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            BevPointColorMode.CLUSTER_ID -> {
                Text(text = "●Clst 1..N", color = Color(0xFF00E676), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Text(text = "●Noise", color = Color(0xFF90A4AE), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            BevPointColorMode.AUTO,
            BevPointColorMode.VELOCITY -> {
                Text(text = "●Appr", color = Color(0xFFFF5252), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Text(text = "●Rec", color = Color(0xFF40C4FF), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Text(text = "●Stat", color = Color(0xFF69F0AE), fontSize = legendFont, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun BevPanelActionChip(
    label: String,
    active: Boolean,
    accentColor: Color,
    isLandscape: Boolean,
    onClick: () -> Unit
) {
    Surface(
        selected = active,
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (active) accentColor.copy(alpha = 0.16f) else Color(0xFF161A22),
        border = BorderStroke(1.dp, if (active) accentColor else Color(0xFF263238)),
        modifier = Modifier.height(if (isLandscape) 22.dp else 26.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = if (isLandscape) 6.dp else 8.dp)
        ) {
            Text(
                text = label,
                fontSize = if (isLandscape) 9.sp else 10.sp,
                color = if (active) accentColor else Color(0xFF90A4AE),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

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
    TrackRecomposition("RadarBev")
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

    Box(modifier = modifier.clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .graphicsLayer()
        ) {
            val drawStartNs = if (enableTimingLog) System.nanoTime() else 0L
            val activeFrame = frameProducer?.invoke() ?: fallbackFrameState?.value ?: staticFrame
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Shared metric projection (exact parity with OpenGL ES 3.0 backend)
            val metrics = BevProjectionMath.computeMetrics(canvasWidth, canvasHeight, maxRangeMeters)
            val originX = metrics.originX
            val originY = metrics.originY
            val usableHeight = metrics.usableHeight
            val scale = metrics.scalePxPerMeter

            // 1-5. Static Grid, Range Rings & Ego Marker (Tier A: individual GL primitives, zero 2 MB RGBA texture overdraw & zero combined-Path SW Path Mask)
            if (canvasWidth > 0f && canvasHeight > 0f) {
                if (geometry.cachedGridW != canvasWidth ||
                    geometry.cachedGridH != canvasHeight ||
                    geometry.cachedGridRange != maxRangeMeters ||
                    geometry.cachedGridShow != showGrid
                ) {
                    if (showGrid) {
                        val fovRad = Math.toRadians(60.0).toFloat()
                        val fovLen = kotlin.math.hypot(canvasWidth / 2f, originY)
                        geometry.fovLeftX = originX - sin(fovRad) * fovLen
                        geometry.fovLeftY = originY - cos(fovRad) * fovLen
                        geometry.fovRightX = originX + sin(fovRad) * fovLen
                        geometry.fovRightY = originY - cos(fovRad) * fovLen

                        val ringStep = when (maxRangeMeters) {
                            15f -> 5f
                            30f -> 10f
                            60f -> 15f
                            else -> 25f
                        }
                        var ringDist = ringStep
                        var rIdx = 0
                        val maxVisibleDist = (usableHeight / scale) + 0.01f
                        while (ringDist <= maxVisibleDist && rIdx < geometry.ringDiams.size) {
                            val radius = ringDist * scale
                            val top = originY - radius
                            geometry.ringLefts[rIdx] = originX - radius
                            geometry.ringTops[rIdx] = top
                            geometry.ringDiams[rIdx] = radius * 2f
                            geometry.ringLabelYs[rIdx] = top + 28f
                            geometry.ringLabelTexts[rIdx] = "${ringDist.toInt()}m"
                            rIdx++
                            ringDist += ringStep
                        }
                        geometry.ringCount = rIdx

                        val lateralStep = 5f
                        var latX = lateralStep
                        var lIdx = 0
                        val maxTickPx = (canvasWidth / 2f) - 20f
                        while (latX * scale < maxTickPx && lIdx < geometry.latTickDx.size) {
                            geometry.latTickDx[lIdx] = latX * scale
                            lIdx++
                            latX += lateralStep
                        }
                        geometry.latTickCount = lIdx
                    } else {
                        geometry.ringCount = 0
                        geometry.latTickCount = 0
                    }

                    val egoWidth = 24f
                    val egoHeight = 32f
                    geometry.egoPath.reset()
                    geometry.egoPath.moveTo(originX, originY - egoHeight)
                    geometry.egoPath.lineTo(originX + egoWidth / 2f, originY)
                    geometry.egoPath.lineTo(originX - egoWidth / 2f, originY)
                    geometry.egoPath.close()

                    geometry.cachedGridW = canvasWidth
                    geometry.cachedGridH = canvasHeight
                    geometry.cachedGridRange = maxRangeMeters
                    geometry.cachedGridShow = showGrid
                }

                if (showGrid) {
                    val originPt = Offset(originX, originY)
                    drawLine(BEV_COLOR_FOV, originPt, Offset(geometry.fovLeftX, geometry.fovLeftY), strokeWidth = 1.5f)
                    drawLine(BEV_COLOR_FOV, originPt, Offset(geometry.fovRightX, geometry.fovRightY), strokeWidth = 1.5f)
                    drawLine(BEV_COLOR_CENTERLINE, originPt, Offset(originX, 10f), strokeWidth = 1.5f)

                    for (rIdx in 0 until geometry.ringCount) {
                        val d = geometry.ringDiams[rIdx]
                        drawArc(
                            color = BEV_COLOR_RING,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(geometry.ringLefts[rIdx], geometry.ringTops[rIdx]),
                            size = Size(d, d),
                            style = BEV_STROKE_RING
                        )
                    }

                    val tickTopY = originY - 12f
                    for (lIdx in 0 until geometry.latTickCount) {
                        val dx = geometry.latTickDx[lIdx]
                        val rx = originX + dx
                        val lx = originX - dx
                        drawLine(BEV_COLOR_GRID, Offset(rx, originY), Offset(rx, tickTopY), strokeWidth = 1f)
                        drawLine(BEV_COLOR_GRID, Offset(lx, originY), Offset(lx, tickTopY), strokeWidth = 1f)
                    }

                    val ncGrid = drawContext.canvas.nativeCanvas
                    for (rIdx in 0 until geometry.ringCount) {
                        ncGrid.drawText(
                            geometry.ringLabelTexts[rIdx],
                            originX,
                            geometry.ringLabelYs[rIdx],
                            textPaint
                        )
                    }
                }
                drawPath(path = geometry.egoPath, color = BEV_COLOR_EGO)
            }

            // 6. Draw Clusters (TLV Type 2)
            if (showClusters) {
                val clusters = activeFrame?.clusters
                if (clusters != null && clusters.isNotEmpty()) {
                    for (i in clusters.indices) {
                        val cluster = clusters[i]
                        val cx = originX + cluster.x * scale
                        val cy = originY - cluster.y * scale
                        if (cy in 0f..originY && cx in 0f..canvasWidth) {
                            val r = 18f
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
            }

            // 7. Draw Point Cloud Reflections (TLV Type 1) via 3 batched nativeCanvas.drawPoints(Cap.SQUARE) calls
            if (showRawPoints) {
                val points = activeFrame?.points
                if (points != null && points.isNotEmpty()) {
                    var apprCount = 0
                    var recCount = 0
                    var statCount = 0
                    var apprBuf = geometry.apprPts
                    var recBuf = geometry.recPts
                    var statBuf = geometry.statPts

                    for (i in points.indices) {
                        val pt = points[i]
                        if ((!dynamicOnly || kotlin.math.abs(pt.doppler) > 0.35f) &&
                            (!minSnrFilter || pt.snrDb >= 15f)
                        ) {
                            val px = originX + pt.x * scale
                            val py = originY - pt.y * scale
                            if (py in 0f..originY && px in 0f..canvasWidth) {
                                when {
                                    pt.doppler < -0.35f -> {
                                        if (apprCount + 2 > apprBuf.size) {
                                            apprBuf = ensurePointBuffer(apprBuf, apprCount + 2)
                                            geometry.apprPts = apprBuf
                                        }
                                        apprBuf[apprCount++] = px
                                        apprBuf[apprCount++] = py
                                    }
                                    pt.doppler > 0.35f -> {
                                        if (recCount + 2 > recBuf.size) {
                                            recBuf = ensurePointBuffer(recBuf, recCount + 2)
                                            geometry.recPts = recBuf
                                        }
                                        recBuf[recCount++] = px
                                        recBuf[recCount++] = py
                                    }
                                    else -> {
                                        if (statCount + 2 > statBuf.size) {
                                            statBuf = ensurePointBuffer(statBuf, statCount + 2)
                                            geometry.statPts = statBuf
                                        }
                                        statBuf[statCount++] = px
                                        statBuf[statCount++] = py
                                    }
                                }
                            }
                        }
                    }

                    val nc = drawContext.canvas.nativeCanvas
                    if (apprCount > 0) nc.drawPoints(apprBuf, 0, apprCount, geometry.apprPointPaint)
                    if (recCount > 0) nc.drawPoints(recBuf, 0, recCount, geometry.recPointPaint)
                    if (statCount > 0) nc.drawPoints(statBuf, 0, statCount, geometry.statPointPaint)
                }
            }

            // 8. Draw Active Tracked Targets (TLV Type 3) with ADAS highlighting (FCW & ACC)
            if (showTracks) {
                val fcwAlert = activeFrame?.canOutputs?.fcw
                val tracksList = activeFrame?.tracks ?: emptyList()
                val packedMatch = BevTrackAssociator.matchPacked(activeFrame)
                val matchedFcwIndex: Int? = BevTrackAssociator.unpackFcwIndex(packedMatch).takeIf { it >= 0 }
                val matchedAccIndex: Int? = BevTrackAssociator.unpackAccIndex(packedMatch).takeIf { it >= 0 }

                for (index in tracksList.indices) {
                    val track = tracksList[index]
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
                                color = BEV_COLOR_FCW_HALO,
                                center = Offset(tx, ty),
                                radius = trackRadius + 4f,
                                style = BEV_STROKE_FCW_OUTER
                            )
                        } else if (isAcc) {
                            drawCircle(
                                color = BEV_COLOR_ACC_HALO,
                                center = Offset(tx, ty),
                                radius = trackRadius + 3.5f,
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
