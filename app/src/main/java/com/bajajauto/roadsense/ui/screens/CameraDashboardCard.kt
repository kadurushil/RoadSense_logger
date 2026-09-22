package com.bajajauto.roadsense.ui.screens

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bajajauto.roadsense.camera.CameraDeviceInfo
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.camera.CameraFrameRate
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.camera.RoadAeMode
import com.bajajauto.roadsense.camera.RoadAeState
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.CameraCalibrationCard
import com.bajajauto.roadsense.ui.components.QuickNudgeBar
import com.bajajauto.roadsense.ui.components.ViewfinderRadarOverlay

/**
 * Camera & Computer Vision Deck:
 * - Dual Orientation Layout: Side-by-side viewfinder & controls in landscape, vertical stack in portrait.
 * - Dynamic Camera Lens Switcher (Main Wide, Ultra-Wide, Front).
 * - Matrix-transformed live Camera2 viewfinder (aspect-ratio & rotation corrected).
 * - User toggle to explicitly pause/resume preview on-demand.
 * - Configurable resolution (480p, 720p, 1080p).
 * - Configurable target frame rate (15 FPS, 30 FPS, 60 FPS).
 * - Real-time shutter sync and frame count telemetry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraDashboardCard(
    viewModel: RadarViewModel,
    isCurrentTab: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val engineState by viewModel.cameraEngineState.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val selectedFps by viewModel.selectedFps.collectAsState()
    val sessionFrames by viewModel.cameraSessionFrames.collectAsState()
    val isPreviewMutedByUser by viewModel.isCameraPreviewMuted.collectAsState()
    val isInfinityLocked by viewModel.isInfinityFocusLocked.collectAsState()
    val isAfAeLocked by viewModel.isAfAeLocked.collectAsState()
    val tapPoint by viewModel.tapFocusPoint.collectAsState()

    // Autonomous Road AE states
    val roadAeMode by viewModel.roadAeMode.collectAsState()
    val roadAeState by viewModel.roadAeState.collectAsState()
    val roadAeContrastRatio by viewModel.roadAeContrastRatio.collectAsState()

    // Sensor Fusion & Calibration states
    val calibrationParams by viewModel.calibrationParams.collectAsState()
    val isRadarOverlayEnabled by viewModel.isRadarOverlayEnabled.collectAsState()
    val isRadarArcsEnabled by viewModel.isRadarArcsEnabled.collectAsState()
    val activeIntrinsics by viewModel.activeIntrinsics.collectAsState()
    val radarFrame by viewModel.latestFrame.collectAsState()

    var previewWidth by remember { mutableStateOf(1920) }
    var previewHeight by remember { mutableStateOf(1080) }

    var hasCameraPermission by remember { mutableStateOf(viewModel.hasCameraPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Query window display rotation (0, 90, 180, 270)
    val windowManager = remember { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val displayRotation = windowManager.defaultDisplay.rotation

    val isCameraFullScreen by viewModel.isCameraFullScreen.collectAsState()
    val isCalibrationFullScreen by viewModel.isCalibrationFullScreen.collectAsState()

    // Render preview only when this tab is active, not covered by fullscreen, and permitted/unmuted.
    val shouldRenderPreview = isCurrentTab && !isCameraFullScreen && !isCalibrationFullScreen && !isPreviewMutedByUser && hasCameraPermission

    if (isLandscape) {
        // --- LANDSCAPE LAYOUT (Side-by-Side) ---
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left Pane: Large Live Viewfinder
            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (shouldRenderPreview) {
                    val cameraAspect = if (selectedRes == CameraResolution.RES_480P) 4f / 3f else 16f / 9f
                    Box(
                        modifier = Modifier
                            .aspectRatio(cameraAspect)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(shouldRenderPreview) {
                                if (shouldRenderPreview) {
                                    detectTapGestures { offset ->
                                        val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                        viewModel.triggerCameraAf(normX, normY)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                            previewWidth = width
                                            previewHeight = height
                                            viewModel.cameraEngine.attachPreviewSurface(surface, width, height)
                                            viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                            viewModel.updateCameraIntrinsics(width, height)
                                        }

                                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                            previewWidth = width
                                            previewHeight = height
                                            viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                            viewModel.updateCameraIntrinsics(width, height)
                                        }

                                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                            viewModel.cameraEngine.detachPreviewSurface(surface)
                                            return true
                                        }

                                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                    }
                                }
                            },
                            update = { textureView ->
                                if (textureView.isAvailable) {
                                    previewWidth = textureView.width
                                    previewHeight = textureView.height
                                    val st = textureView.surfaceTexture
                                    if (shouldRenderPreview && st != null && (engineState is CameraEngineState.Closed || !viewModel.cameraEngine.isSurfaceAttached(st))) {
                                        viewModel.cameraEngine.attachPreviewSurface(st, textureView.width, textureView.height)
                                    }
                                    viewModel.updateCameraDisplayRotation(displayRotation, textureView, textureView.width, textureView.height)
                                    viewModel.updateCameraIntrinsics(textureView.width, textureView.height)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isAfAeLocked) {
                            TapFocusReticleOverlay(tapPoint = tapPoint)
                        }
                        if (isRadarOverlayEnabled) {
                            ViewfinderRadarOverlay(
                                radarFrame = radarFrame,
                                calibrationParams = calibrationParams,
                                intrinsics = activeIntrinsics,
                                viewWidth = previewWidth,
                                viewHeight = previewHeight,
                                showRangeArcs = isRadarArcsEnabled,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        QuickNudgeBar(
                            calibrationParams = calibrationParams,
                            onNudgePitch = { viewModel.nudgePitch(it) },
                            onNudgeYaw = { viewModel.nudgeYaw(it) },
                            onRevert = { viewModel.revertNudges() },
                            onSave = { viewModel.saveBaselineCalibration() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                } else {
                    ViewfinderPlaceholder(
                        hasPermission = hasCameraPermission,
                        isMuted = isPreviewMutedByUser,
                        onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onResume = { viewModel.setCameraPreviewMuted(false) }
                    )
                }

                // Overlay Status Badge
                Surface(
                    color = if (engineState is CameraEngineState.Recording) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    val lockBadge = when {
                        isInfinityLocked -> " • ∞ LOCK"
                        isAfAeLocked -> " • AF/AE LOCK"
                        else -> ""
                    }
                    val aeBadge = if (roadAeMode == RoadAeMode.AUTO_ROAD) {
                        when (roadAeState) {
                            RoadAeState.SKY_BLOOM -> " • ROAD AE ☀️"
                            RoadAeState.NIGHT_TUNNEL -> " • ROAD AE 🌙"
                            RoadAeState.BALANCED -> " • ROAD AE"
                            RoadAeState.TAP_LOCKED -> ""
                        }
                    } else ""
                    Text(
                        text = when (val state = engineState) {
                            is CameraEngineState.Recording -> "● REC ($sessionFrames f)$aeBadge"
                            is CameraEngineState.Previewing -> "${selectedCamera?.displayName ?: "Camera"} • ${selectedRes.label}$lockBadge$aeBadge"
                            is CameraEngineState.Opening -> "OPENING..."
                            is CameraEngineState.Error -> "ERROR: ${state.message.take(16)}"
                            CameraEngineState.Closed -> if (isPreviewMutedByUser) "PAUSED" else "STANDBY"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Fullscreen Expand Button (Landscape)
                IconButton(
                    onClick = { viewModel.setCameraFullScreen(true) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(34.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Full Screen Preview",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Right Pane: Scrollable Controls & Telemetry
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraPipelineStatusHeader(
                    engineState = engineState,
                    selectedRes = selectedRes,
                    selectedFps = selectedFps,
                    sessionFrames = sessionFrames,
                    hasCameraPermission = hasCameraPermission,
                    isPreviewMutedByUser = isPreviewMutedByUser,
                    onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onToggleMute = { viewModel.setCameraPreviewMuted(!isPreviewMutedByUser) }
                )

                CameraCalibrationCard(
                    calibrationParams = calibrationParams,
                    isRadarOverlayEnabled = isRadarOverlayEnabled,
                    onLaunchCalibration = { viewModel.setCalibrationFullScreen(true) },
                    onToggleRadarOverlay = { viewModel.toggleRadarOverlay() },
                    onResetDefaults = { viewModel.resetCalibrationToDefaults() }
                )

                CameraFocusControlCard(
                    isInfinityLocked = isInfinityLocked,
                    isAfAeLocked = isAfAeLocked,
                    roadAeMode = roadAeMode,
                    roadAeState = roadAeState,
                    contrastRatio = roadAeContrastRatio,
                    onTriggerAf = { viewModel.triggerCameraAf() },
                    onToggleInfinity = { viewModel.toggleInfinityFocus() },
                    onToggleRoadAe = { viewModel.toggleRoadAeMode() }
                )

                CameraLensSelectorCard(
                    availableCameras = availableCameras,
                    selectedCamera = selectedCamera,
                    isRecording = engineState is CameraEngineState.Recording,
                    onSelectCamera = { viewModel.selectCamera(it) }
                )

                ResolutionSelectorCard(
                    selectedRes = selectedRes,
                    isRecording = engineState is CameraEngineState.Recording,
                    onSelectRes = { viewModel.setCameraResolution(it) }
                )

                FrameRateSelectorCard(
                    selectedFps = selectedFps,
                    isRecording = engineState is CameraEngineState.Recording,
                    onSelectFps = { viewModel.setCameraFrameRate(it) }
                )

                CameraSyncSpecCard()
            }
        }
    } else {
        // --- PORTRAIT LAYOUT (Vertical Stack) ---
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CameraPipelineStatusHeader(
                engineState = engineState,
                selectedRes = selectedRes,
                selectedFps = selectedFps,
                sessionFrames = sessionFrames,
                hasCameraPermission = hasCameraPermission,
                isPreviewMutedByUser = isPreviewMutedByUser,
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onToggleMute = { viewModel.setCameraPreviewMuted(!isPreviewMutedByUser) }
            )

            // Live Camera Viewfinder Card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (selectedRes == CameraResolution.RES_480P) 4f / 3f else 16f / 9f)
                        .pointerInput(shouldRenderPreview) {
                            if (shouldRenderPreview) {
                                detectTapGestures { offset ->
                                    val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    viewModel.triggerCameraAf(normX, normY)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (shouldRenderPreview) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                            previewWidth = width
                                            previewHeight = height
                                            viewModel.cameraEngine.attachPreviewSurface(surface, width, height)
                                            viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                            viewModel.updateCameraIntrinsics(width, height)
                                        }

                                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                            previewWidth = width
                                            previewHeight = height
                                            viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                            viewModel.updateCameraIntrinsics(width, height)
                                        }

                                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                            viewModel.cameraEngine.detachPreviewSurface(surface)
                                            return true
                                        }

                                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                    }
                                }
                            },
                            update = { textureView ->
                                if (textureView.isAvailable) {
                                    previewWidth = textureView.width
                                    previewHeight = textureView.height
                                    val st = textureView.surfaceTexture
                                    if (shouldRenderPreview && st != null && (engineState is CameraEngineState.Closed || !viewModel.cameraEngine.isSurfaceAttached(st))) {
                                        viewModel.cameraEngine.attachPreviewSurface(st, textureView.width, textureView.height)
                                    }
                                    viewModel.updateCameraDisplayRotation(displayRotation, textureView, textureView.width, textureView.height)
                                    viewModel.updateCameraIntrinsics(textureView.width, textureView.height)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                        if (isAfAeLocked) {
                            TapFocusReticleOverlay(tapPoint = tapPoint)
                        }
                        if (isRadarOverlayEnabled) {
                            ViewfinderRadarOverlay(
                                radarFrame = radarFrame,
                                calibrationParams = calibrationParams,
                                intrinsics = activeIntrinsics,
                                viewWidth = previewWidth,
                                viewHeight = previewHeight,
                                showRangeArcs = isRadarArcsEnabled,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        QuickNudgeBar(
                            calibrationParams = calibrationParams,
                            onNudgePitch = { viewModel.nudgePitch(it) },
                            onNudgeYaw = { viewModel.nudgeYaw(it) },
                            onRevert = { viewModel.revertNudges() },
                            onSave = { viewModel.saveBaselineCalibration() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    } else {
                        ViewfinderPlaceholder(
                            hasPermission = hasCameraPermission,
                            isMuted = isPreviewMutedByUser,
                            onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onResume = { viewModel.setCameraPreviewMuted(false) }
                        )
                    }

                    // Overlay Status Badge
                    Surface(
                        color = if (engineState is CameraEngineState.Recording) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        val lockBadge = when {
                            isInfinityLocked -> " • ∞ LOCK"
                            isAfAeLocked -> " • AF/AE LOCK"
                            else -> ""
                        }
                        val aeBadge = if (roadAeMode == RoadAeMode.AUTO_ROAD) {
                            when (roadAeState) {
                                RoadAeState.SKY_BLOOM -> " • ROAD AE ☀️"
                                RoadAeState.NIGHT_TUNNEL -> " • ROAD AE 🌙"
                                RoadAeState.BALANCED -> " • ROAD AE"
                                RoadAeState.TAP_LOCKED -> ""
                            }
                        } else ""
                        Text(
                            text = when (val state = engineState) {
                                is CameraEngineState.Recording -> "● REC ($sessionFrames f)$aeBadge"
                                is CameraEngineState.Previewing -> "${selectedCamera?.displayName ?: "Camera"} • ${selectedRes.label}$lockBadge$aeBadge"
                                is CameraEngineState.Opening -> "OPENING..."
                                is CameraEngineState.Error -> "ERROR: ${state.message.take(16)}"
                                CameraEngineState.Closed -> if (isPreviewMutedByUser) "PAUSED" else "STANDBY"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Fullscreen Expand Button (Portrait)
                    IconButton(
                        onClick = { viewModel.setCameraFullScreen(true) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(34.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Full Screen Preview",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            CameraCalibrationCard(
                calibrationParams = calibrationParams,
                isRadarOverlayEnabled = isRadarOverlayEnabled,
                onLaunchCalibration = { viewModel.setCalibrationFullScreen(true) },
                onToggleRadarOverlay = { viewModel.toggleRadarOverlay() },
                onResetDefaults = { viewModel.resetCalibrationToDefaults() }
            )

            CameraFocusControlCard(
                isInfinityLocked = isInfinityLocked,
                isAfAeLocked = isAfAeLocked,
                roadAeMode = roadAeMode,
                roadAeState = roadAeState,
                contrastRatio = roadAeContrastRatio,
                onTriggerAf = { viewModel.triggerCameraAf() },
                onToggleInfinity = { viewModel.toggleInfinityFocus() },
                onToggleRoadAe = { viewModel.toggleRoadAeMode() }
            )

            CameraLensSelectorCard(
                availableCameras = availableCameras,
                selectedCamera = selectedCamera,
                isRecording = engineState is CameraEngineState.Recording,
                onSelectCamera = { viewModel.selectCamera(it) }
            )

            ResolutionSelectorCard(
                selectedRes = selectedRes,
                isRecording = engineState is CameraEngineState.Recording,
                onSelectRes = { viewModel.setCameraResolution(it) }
            )

            FrameRateSelectorCard(
                selectedFps = selectedFps,
                isRecording = engineState is CameraEngineState.Recording,
                onSelectFps = { viewModel.setCameraFrameRate(it) }
            )

            CameraSyncSpecCard()
        }
    }
}

@Composable
private fun CameraPipelineStatusHeader(
    engineState: CameraEngineState,
    selectedRes: CameraResolution,
    selectedFps: CameraFrameRate,
    sessionFrames: Long,
    hasCameraPermission: Boolean,
    isPreviewMutedByUser: Boolean,
    onGrant: () -> Unit,
    onToggleMute: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (engineState) {
                is CameraEngineState.Recording -> MaterialTheme.colorScheme.errorContainer
                is CameraEngineState.Previewing -> MaterialTheme.colorScheme.primaryContainer
                is CameraEngineState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Camera & Vision Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (engineState) {
                        is CameraEngineState.Closed -> if (!hasCameraPermission) "Permission Required" else "Camera Idle / Muted"
                        is CameraEngineState.Opening -> "Opening Sensor Hardware..."
                        is CameraEngineState.Previewing -> "Preview Active (${selectedRes.label} @ ${selectedFps.label})"
                        is CameraEngineState.Recording -> "● Recording H.264 ($sessionFrames frames)"
                        is CameraEngineState.Error -> engineState.message
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!hasCameraPermission) {
                Button(onClick = onGrant) {
                    Text("Grant")
                }
            } else {
                TextButton(onClick = onToggleMute) {
                    Text(if (isPreviewMutedByUser) "Resume" else "Mute")
                }
            }
        }
    }
}

@Composable
private fun CameraFocusControlCard(
    isInfinityLocked: Boolean,
    isAfAeLocked: Boolean,
    roadAeMode: RoadAeMode,
    roadAeState: RoadAeState,
    contrastRatio: Float,
    onTriggerAf: () -> Unit,
    onToggleInfinity: () -> Unit,
    onToggleRoadAe: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FOCUS & EXPOSURE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                if (isInfinityLocked) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "∞ ROAD LOCKED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (isAfAeLocked) {
                    Surface(
                        color = Color(0xFFFFD54F).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "TAP AF/AE LOCKED",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFD54F),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 1: Re-Focus (Centered AF sweep) or Reset Lock if AF/AE locked
                if (isAfAeLocked) {
                    Button(
                        onClick = onTriggerAf,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)), // High-visibility Amber/Orange
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = "Reset AF/AE Lock",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Reset Lock",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onTriggerAf,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = "Re-Focus",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Re-Focus",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Button 2: Infinity Focus Lock
                if (isInfinityLocked) {
                    Button(
                        onClick = onToggleInfinity,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AllInclusive,
                            contentDescription = "Infinity Focus Locked",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "∞ Locked",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onToggleInfinity,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AllInclusive,
                            contentDescription = "Infinity Focus Lock",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "∞ Lock",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }

            // Road AE Mode Toggle & Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isAutoRoad = roadAeMode == RoadAeMode.AUTO_ROAD
                if (isAutoRoad) {
                    Button(
                        onClick = onToggleRoadAe,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WbSunny,
                            contentDescription = "Road AE Mode Active",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Road AE: Auto",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onToggleRoadAe,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Full Frame AE",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Road AE: Off",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                // Road AE Dynamic Status / Telemetry Chip
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val statusText = if (!isAutoRoad) {
                        "Matrix AE"
                    } else {
                        when (roadAeState) {
                            RoadAeState.SKY_BLOOM -> "Sky Bloom (${String.format("%.1f", contrastRatio)}x)"
                            RoadAeState.BALANCED -> "Balanced (${String.format("%.1f", contrastRatio)}x)"
                            RoadAeState.NIGHT_TUNNEL -> "Night/Tunnel (${String.format("%.1f", contrastRatio)}x)"
                            RoadAeState.TAP_LOCKED -> "Tap-Locked"
                        }
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (roadAeState == RoadAeState.SKY_BLOOM && isAutoRoad) MaterialTheme.colorScheme.primary else Color.Gray,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Focus & Exposure lock reticle drawn at the normalized tap position on top of the viewfinder.
 */
@Composable
fun TapFocusReticleOverlay(
    tapPoint: Pair<Float, Float>?,
    modifier: Modifier = Modifier
) {
    if (tapPoint == null) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val targetX = maxWidth * tapPoint.first
        val targetY = maxHeight * tapPoint.second
        val boxSize = 54.dp
        val halfSize = boxSize / 2
        val left = (targetX - halfSize).coerceIn(4.dp, maxWidth - boxSize - 4.dp)
        val top = (targetY - halfSize).coerceIn(4.dp, maxHeight - boxSize - 4.dp)

        Box(
            modifier = Modifier
                .offset(x = left, y = top)
                .size(boxSize)
                .border(
                    width = 2.dp,
                    color = Color(0xFFFFD54F),
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFFFFD54F), CircleShape)
            )
            Text(
                text = "AF/AE LOCK",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD54F),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 12.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraLensSelectorCard(
    availableCameras: List<CameraDeviceInfo>,
    selectedCamera: CameraDeviceInfo?,
    isRecording: Boolean,
    onSelectCamera: (CameraDeviceInfo) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACTIVE CAMERA LENS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                if (isRecording) {
                    Text(
                        text = "Locked during recording",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (availableCameras.isEmpty()) {
                Text("Searching for cameras...", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableCameras.forEach { cam ->
                        FilterChip(
                            selected = selectedCamera?.id == cam.id,
                            onClick = { if (!isRecording) onSelectCamera(cam) },
                            enabled = !isRecording,
                            label = {
                                Text(
                                    text = if (cam.isUltraWide) "0.5x Ultra-Wide" else if (cam.isBackFacing) "1.0x Main" else "Front",
                                    fontWeight = if (selectedCamera?.id == cam.id) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionSelectorCard(
    selectedRes: CameraResolution,
    isRecording: Boolean,
    onSelectRes: (CameraResolution) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "RECORDING RESOLUTION",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraResolution.values().forEach { res ->
                    FilterChip(
                        selected = selectedRes == res,
                        onClick = { if (!isRecording) onSelectRes(res) },
                        enabled = !isRecording,
                        label = {
                            Text(
                                text = res.label,
                                fontWeight = if (selectedRes == res) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrameRateSelectorCard(
    selectedFps: CameraFrameRate,
    isRecording: Boolean,
    onSelectFps: (CameraFrameRate) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "TARGET FRAME RATE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraFrameRate.values().forEach { fps ->
                    FilterChip(
                        selected = selectedFps == fps,
                        onClick = { if (!isRecording) onSelectFps(fps) },
                        enabled = !isRecording,
                        label = {
                            Text(
                                text = fps.label,
                                fontWeight = if (selectedFps == fps) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraSyncSpecCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Synchronization & Codec Spec",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "• Shutter Stamping: CameraCaptureSession SystemClock.elapsedRealtimeNanos()\n" +
                        "• Video Codec: Hardware H.264 (AVC) in MP4 (Audio disabled)\n" +
                        "• Timeline Alignment: Cross-referenced with Radar & GPS in session_timeline.csv",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ViewfinderPlaceholder(
    hasPermission: Boolean,
    isMuted: Boolean,
    isSettling: Boolean = false,
    onGrantPermission: () -> Unit,
    onResume: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = when {
                !hasPermission -> "Camera Permission Required"
                isSettling -> "Connecting Viewfinder..."
                isMuted -> "Viewfinder Paused (Off by Default)"
                else -> "Viewfinder Inactive"
            },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        if (!hasPermission) {
            Button(onClick = onGrantPermission) {
                Text("Grant Camera Permission")
            }
        } else if (isMuted) {
            Button(onClick = onResume) {
                Text("Enable Viewfinder")
            }
        } else if (isSettling) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
