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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.bajajauto.roadsense.ui.RadarViewModel

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

    var hasCameraPermission by remember { mutableStateOf(viewModel.hasCameraPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Query window display rotation (0, 90, 180, 270)
    val windowManager = remember { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val displayRotation = windowManager.defaultDisplay.rotation

    // Settle delay: avoid touching Camera2 hardware during rapid tab transitions
    var isSettledPreviewActive by remember { mutableStateOf(false) }

    LaunchedEffect(isCurrentTab, isPreviewMutedByUser) {
        if (isCurrentTab && !isPreviewMutedByUser) {
            kotlinx.coroutines.delay(250)
            isSettledPreviewActive = true
        } else {
            isSettledPreviewActive = false
        }
    }

    // Effect: Detach preview surface when swiped away from Camera tab or muted by user
    val shouldRenderPreview = isSettledPreviewActive && hasCameraPermission
    DisposableEffect(shouldRenderPreview) {
        onDispose {
            if (!shouldRenderPreview) {
                viewModel.cameraEngine.detachPreviewSurface()
            }
        }
    }

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
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                        viewModel.cameraEngine.attachPreviewSurface(surface, width, height)
                                        viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                    }

                                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                        viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
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
                                viewModel.updateCameraDisplayRotation(displayRotation, textureView, textureView.width, textureView.height)
                            }
                        },
                        modifier = Modifier
                            .aspectRatio(if (selectedRes == CameraResolution.RES_480P) 4f / 3f else 16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    ViewfinderPlaceholder(
                        hasPermission = hasCameraPermission,
                        isMuted = isPreviewMutedByUser,
                        isSettling = isCurrentTab && !isPreviewMutedByUser && !isSettledPreviewActive,
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
                    Text(
                        text = when (engineState) {
                            is CameraEngineState.Recording -> "● REC ($sessionFrames f)"
                            is CameraEngineState.Previewing -> "${selectedCamera?.displayName ?: "Camera"} • ${selectedRes.label}"
                            else -> "PAUSED"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                        .aspectRatio(if (selectedRes == CameraResolution.RES_480P) 4f / 3f else 16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    if (shouldRenderPreview) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                            viewModel.cameraEngine.attachPreviewSurface(surface, width, height)
                                            viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                        }

                                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                            viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
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
                                    viewModel.updateCameraDisplayRotation(displayRotation, textureView, textureView.width, textureView.height)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        ViewfinderPlaceholder(
                            hasPermission = hasCameraPermission,
                            isMuted = isPreviewMutedByUser,
                            isSettling = isCurrentTab && !isPreviewMutedByUser && !isSettledPreviewActive,
                            onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onResume = { viewModel.setCameraPreviewMuted(false) }
                        )
                    }
                }
            }

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
                isMuted -> "Viewfinder Paused by User"
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
                Text("Resume Viewfinder")
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
