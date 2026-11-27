package com.bajajauto.roadsense.ui.components

import android.content.Context
import android.content.res.Configuration
import android.view.Surface as AndroidSurface
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel

/**
 * Immersive Side-by-Side (SBS) Fullscreen Mode:
 * - Hides LiveMetricsBar, CockpitTabNavigationBar, pane headers, and BEV footer
 *   to maximize screen real estate for the Camera Viewfinder and Radar BEV Scope.
 * - Reuses MainActivity's single persistent AutoFitSurfaceView via bindPersistentCameraViewport.
 * - Provides a minimal translucent floating top pill (Exit, HUD, Range, Settings, REC/STOP).
 */
@Composable
fun FullscreenSbsPreview(
    viewModel: RadarViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onExit)

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val windowManager = remember { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val displayRotation = windowManager.defaultDisplay.rotation

    val selectedCamera by viewModel.selectedCamera.collectAsState()
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val isPreviewMutedByUser by viewModel.isCameraPreviewMuted.collectAsState()
    val hasCameraPermission = remember { viewModel.hasCameraPermission() }

    val calibrationParams by viewModel.calibrationParams.collectAsState()
    val activeIntrinsics by viewModel.activeIntrinsics.collectAsState()
    val isRadarOverlayEnabled by viewModel.isRadarOverlayEnabled.collectAsState()
    val isRadarArcsEnabled by viewModel.isRadarArcsEnabled.collectAsState()
    val debugState by viewModel.debugToggleState.collectAsState()

    val maxRangeMeters by viewModel.radarMaxRange.collectAsState()
    val dynamicOnly by viewModel.radarDynamicOnly.collectAsState()
    val minSnrFilter by viewModel.radarMinSnr.collectAsState()

    var previewWidth by remember { mutableIntStateOf(1920) }
    var previewHeight by remember { mutableIntStateOf(1080) }
    var showSettingsStrip by remember { mutableStateOf(false) }
    var isGlCameraModified by remember { mutableStateOf(false) }

    val canRenderCamera = !isPreviewMutedByUser && hasCameraPermission
    val rangeOptions = remember { listOf(15f, 30f, 60f, 100f, 150f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (canRenderCamera) Color.Transparent else Color(0xFF090B10))
    ) {
        // --- 50/50 Split Content (Camera Feed + Radar BEV Scope) ---
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FullscreenSbsCameraPane(
                    viewModel = viewModel,
                    canRenderCamera = canRenderCamera,
                    displayRotation = displayRotation,
                    selectedResWidth = selectedRes.width,
                    selectedResHeight = selectedRes.height,
                    lensLabel = selectedCamera?.displayName,
                    isRadarOverlayEnabled = isRadarOverlayEnabled,
                    isRadarArcsEnabled = isRadarArcsEnabled,
                    calibrationParams = calibrationParams,
                    activeIntrinsics = activeIntrinsics,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    onViewportSized = { w, h ->
                        if (previewWidth != w || previewHeight != h) {
                            previewWidth = w
                            previewHeight = h
                        }
                    },
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                )

                RadarBevPlot(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight(),
                    isLandscape = true,
                    showChrome = false,
                    onCameraModifiedChanged = { isGlCameraModified = it }
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FullscreenSbsCameraPane(
                    viewModel = viewModel,
                    canRenderCamera = canRenderCamera,
                    displayRotation = displayRotation,
                    selectedResWidth = selectedRes.width,
                    selectedResHeight = selectedRes.height,
                    lensLabel = selectedCamera?.displayName,
                    isRadarOverlayEnabled = isRadarOverlayEnabled,
                    isRadarArcsEnabled = isRadarArcsEnabled,
                    calibrationParams = calibrationParams,
                    activeIntrinsics = activeIntrinsics,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    onViewportSized = { w, h ->
                        if (previewWidth != w || previewHeight != h) {
                            previewWidth = w
                            previewHeight = h
                        }
                    },
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxWidth()
                )

                RadarBevPlot(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxWidth(),
                    isLandscape = false,
                    showChrome = false,
                    onCameraModifiedChanged = { isGlCameraModified = it }
                )
            }
        }

        // --- Minimal Floating Translucent Control Pill + Separate Large Bookmark Button to the Left of EXIT ---
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FullscreenSbsBookmarkAction(viewModel = viewModel)

                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Exit Fullscreen SBS
                    Surface(
                        onClick = onExit,
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E2530),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Exit Fullscreen SBS",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "EXIT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // HUD ON/OFF Toggle
                    SbsFloatingChip(
                        label = if (isRadarOverlayEnabled) "HUD ON" else "HUD OFF",
                        selected = isRadarOverlayEnabled,
                        selectedBg = Color(0xFF1B5E20).copy(alpha = 0.75f),
                        selectedFg = Color(0xFFA5D6A7),
                        onClick = { viewModel.toggleRadarOverlay() }
                    )

                    // HUD Mode Cycle (only when HUD is ON)
                    if (isRadarOverlayEnabled) {
                        SbsFloatingChip(
                            label = debugState.hudOverlayMode.label,
                            selected = true,
                            selectedBg = Color(0xFF006064).copy(alpha = 0.75f),
                            selectedFg = Color(0xFF80DEEA),
                            onClick = { viewModel.cycleHudOverlayMode() }
                        )
                    }

                    // BEV Range Cycle (15m -> 30m -> 60m -> 100m -> 150m)
                    SbsFloatingChip(
                        label = "${maxRangeMeters.toInt()}m",
                        selected = true,
                        selectedBg = Color(0xFF1E3A5F).copy(alpha = 0.85f),
                        selectedFg = Color(0xFF80D8FF),
                        onClick = {
                            val idx = rangeOptions.indexOfFirst { it == maxRangeMeters }
                            val nextRange = rangeOptions[(idx + 1) % rangeOptions.size]
                            viewModel.setRadarMaxRange(nextRange)
                        }
                    )

                    // 2D / 3D & Reset View controls (OpenGL ES 3.0 mode)
                    if (debugState.useGlBevRenderer) {
                        SbsFloatingChip(
                            label = if (debugState.bev3dMode) "3D" else "2D",
                            selected = debugState.bev3dMode,
                            selectedBg = Color(0xFF006064).copy(alpha = 0.85f),
                            selectedFg = Color(0xFF00E5FF),
                            onClick = { viewModel.setBev3dMode(!debugState.bev3dMode) }
                        )
                        if (debugState.bev3dMode || isGlCameraModified) {
                            SbsFloatingChip(
                                label = "Reset",
                                selected = true,
                                selectedBg = Color(0xFF4E342E).copy(alpha = 0.85f),
                                selectedFg = Color(0xFFFFCC80),
                                onClick = { viewModel.requestBevCameraReset() }
                            )
                        }
                    }

                    // Settings Drawer Toggle
                    Surface(
                        selected = showSettingsStrip,
                        onClick = { showSettingsStrip = !showSettingsStrip },
                        shape = RoundedCornerShape(14.dp),
                        color = if (showSettingsStrip) Color(0xFF2A2438) else Color(0xFF1E2530),
                        border = BorderStroke(
                            1.dp,
                            if (showSettingsStrip) Color(0xFFB388FF) else Color(0xFF37474F)
                        ),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "SBS Quick Settings",
                                tint = if (showSettingsStrip) Color(0xFFD1C4E9) else Color(0xFFB0BEC5),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Isolated REC / STOP Button
                    FullscreenRecordButton(viewModel = viewModel)
                }
            }
            }

            // Expandable Compact Settings Strip
            AnimatedVisibility(visible = showSettingsStrip) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    val isTimingActive = debugState.masterDebugEnabled && debugState.canvasTimingLogs
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SbsFloatingChip(
                            label = "Moving Only",
                            selected = dynamicOnly,
                            selectedBg = Color(0xFF37474F),
                            selectedFg = Color(0xFF80D8FF),
                            onClick = { viewModel.setRadarDynamicOnly(!dynamicOnly) }
                        )
                        SbsFloatingChip(
                            label = "SNR ≥ 15dB",
                            selected = minSnrFilter,
                            selectedBg = Color(0xFF37474F),
                            selectedFg = Color(0xFF80D8FF),
                            onClick = { viewModel.setRadarMinSnr(!minSnrFilter) }
                        )
                        SbsFloatingChip(
                            label = if (debugState.useGlBevRenderer) "GL" else "Skia",
                            selected = debugState.useGlBevRenderer,
                            selectedBg = Color(0xFF102A22),
                            selectedFg = Color(0xFF69F0AE),
                            onClick = { viewModel.setUseGlBevRenderer(!debugState.useGlBevRenderer) }
                        )
                        if (debugState.useGlBevRenderer) {
                            SbsFloatingChip(
                                label = "Col:${debugState.bevPointColorMode.shortLabel}",
                                selected = debugState.bevPointColorMode != com.bajajauto.roadsense.ui.components.gl.BevPointColorMode.AUTO,
                                selectedBg = Color(0xFF16323A),
                                selectedFg = Color(0xFF80DEEA),
                                onClick = { viewModel.cycleBevPointColorMode() }
                            )
                            SbsFloatingChip(
                                label = "Map:${debugState.bevColorMap.shortLabel}",
                                selected = debugState.bevPointColorMode.usesColormap(debugState.bev3dMode),
                                selectedBg = Color(0xFF2A2438),
                                selectedFg = Color(0xFFD1C4E9),
                                onClick = { viewModel.setBevColorMap(debugState.bevColorMap.next()) }
                            )
                            if (debugState.bev3dMode) {
                                SbsFloatingChip(
                                    label = if (debugState.bevStixelsEnabled) "Stix ON" else "Stix OFF",
                                    selected = debugState.bevStixelsEnabled,
                                    selectedBg = Color(0xFF006064).copy(alpha = 0.75f),
                                    selectedFg = Color(0xFF00E5FF),
                                    onClick = { viewModel.setBevStixelsEnabled(!debugState.bevStixelsEnabled) }
                                )
                            }
                            SbsFloatingChip(
                                label = if (debugState.bevShowRoadLanes) "Lanes ON" else "Lanes OFF",
                                selected = debugState.bevShowRoadLanes,
                                selectedBg = Color(0xFF1B5E20).copy(alpha = 0.75f),
                                selectedFg = Color(0xFF76FF03),
                                onClick = { viewModel.setBevShowRoadLanes(!debugState.bevShowRoadLanes) }
                            )
                            SbsFloatingChip(
                                label = "Decay:${debugState.bevDecayPreset.shortLabel}",
                                selected = debugState.bevDecayPreset.frameCount > 1,
                                selectedBg = Color(0xFF006064).copy(alpha = 0.75f),
                                selectedFg = Color(0xFF80DEEA),
                                onClick = { viewModel.setBevDecayPreset(debugState.bevDecayPreset.next()) }
                            )
                            SbsFloatingChip(
                                label = if (debugState.bevFlipZ) "FlipZ ON" else "FlipZ OFF",
                                selected = debugState.bevFlipZ,
                                selectedBg = Color(0xFF4E342E).copy(alpha = 0.85f),
                                selectedFg = Color(0xFFFFAB40),
                                onClick = { viewModel.setBevFlipZ(!debugState.bevFlipZ) }
                            )
                        }
                        SbsFloatingChip(
                            label = if (isRadarArcsEnabled) "Arcs ON" else "Arcs OFF",
                            selected = isRadarArcsEnabled,
                            selectedBg = Color(0xFF006064).copy(alpha = 0.75f),
                            selectedFg = Color(0xFF80DEEA),
                            onClick = { viewModel.setRadarArcsEnabled(!isRadarArcsEnabled) }
                        )
                        SbsFloatingChip(
                            label = if (isTimingActive) "Log ON" else "Log OFF",
                            selected = isTimingActive,
                            selectedBg = Color(0xFFE65100).copy(alpha = 0.75f),
                            selectedFg = Color(0xFFFFCC80),
                            onClick = { viewModel.setBenchTimingLogging(!isTimingActive) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenSbsCameraPane(
    viewModel: RadarViewModel,
    canRenderCamera: Boolean,
    displayRotation: Int,
    selectedResWidth: Int,
    selectedResHeight: Int,
    lensLabel: String?,
    isRadarOverlayEnabled: Boolean,
    isRadarArcsEnabled: Boolean,
    calibrationParams: com.bajajauto.roadsense.fusion.model.CalibrationParameters,
    activeIntrinsics: com.bajajauto.roadsense.fusion.engine.CameraIntrinsics?,
    previewWidth: Int,
    previewHeight: Int,
    onViewportSized: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(if (canRenderCamera) Color.Transparent else Color(0xFF090B10)),
        contentAlignment = Alignment.Center
    ) {
        if (canRenderCamera) {
            val isRotLandscape = displayRotation == AndroidSurface.ROTATION_90 ||
                displayRotation == AndroidSurface.ROTATION_270
            val cameraAspect = if (isRotLandscape) {
                selectedResWidth.toFloat() / selectedResHeight.toFloat()
            } else {
                selectedResHeight.toFloat() / selectedResWidth.toFloat()
            }
            Box(
                modifier = Modifier
                    .aspectRatio(cameraAspect)
                    .bindPersistentCameraViewport(
                        slotId = "FullscreenSbs",
                        active = canRenderCamera,
                        displayRotation = displayRotation,
                        onViewportSized = onViewportSized
                    ),
                contentAlignment = Alignment.Center
            ) {
                Spacer(modifier = Modifier.fillMaxSize())

                if (isRadarOverlayEnabled) {
                    val radarFrameState = viewModel.latestFrame.collectAsState()
                    val overlayFrameProducer: () -> RadarFrame? = remember(radarFrameState) { { radarFrameState.value } }
                    ViewfinderRadarOverlay(
                        radarFrameProducer = overlayFrameProducer,
                        calibrationParams = calibrationParams,
                        intrinsics = activeIntrinsics,
                        viewWidth = previewWidth,
                        viewHeight = previewHeight,
                        showRangeArcs = isRadarArcsEnabled,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                lensLabel?.let { label ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clearAndSetSemantics {}
                    ) {
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = "Camera Paused",
                    tint = Color(0xFF546E7A),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Camera Paused",
                    color = Color(0xFF78909C),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun FullscreenSbsBookmarkAction(
    viewModel: RadarViewModel
) {
    val sessionRecordingState by viewModel.sessionRecordingState.collectAsState()
    val bookmarkCount by viewModel.sessionBookmarkCount.collectAsState()
    val hapticsEnabled by viewModel.bookmarkHapticsEnabled.collectAsState()
    val isRecording = sessionRecordingState is com.bajajauto.roadsense.recording.SessionRecordingState.Recording

    EventBookmarkButton(
        isRecording = isRecording,
        bookmarkCount = bookmarkCount,
        hapticsEnabled = hapticsEnabled,
        style = EventBookmarkButtonStyle.LARGE_DOCK,
        showDockCaption = false,
        onMarkEvent = { type -> viewModel.markEvent(type) }
    )
}

@Composable
internal fun SbsFloatingChip(
    label: String,
    selected: Boolean,
    selectedBg: Color,
    selectedFg: Color,
    onClick: () -> Unit
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) selectedBg else Color(0xFF1E2530),
        border = BorderStroke(1.dp, if (selected) selectedFg.copy(alpha = 0.6f) else Color(0xFF37474F)),
        modifier = Modifier.height(28.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 9.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) selectedFg else Color(0xFFB0BEC5),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
