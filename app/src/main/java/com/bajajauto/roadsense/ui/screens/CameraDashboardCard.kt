package com.bajajauto.roadsense.ui.screens

import android.Manifest
import android.graphics.SurfaceTexture
import android.view.TextureView
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.camera.CameraFrameRate
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.ui.RadarViewModel

/**
 * Camera & Computer Vision Deck:
 * - Live Camera2 viewfinder surface with auto-pause/mute when swiped away.
 * - User toggle to explicitly pause/resume preview on-demand.
 * - Configurable resolution (480p, 720p, 1080p).
 * - Configurable target frame rate (15 FPS, 30 FPS, 60 FPS).
 * - Real-time shutter sync and frame count telemetry.
 */
@Composable
fun CameraDashboardCard(
    viewModel: RadarViewModel,
    isCurrentTab: Boolean,
    modifier: Modifier = Modifier
) {
    val engineState by viewModel.cameraEngineState.collectAsState()
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val selectedFps by viewModel.selectedFps.collectAsState()
    val sessionFrames by viewModel.cameraSessionFrames.collectAsState()

    var isPreviewMutedByUser by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(viewModel.hasCameraPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Effect: Detach preview surface when swiped away from Camera tab or muted by user
    val shouldRenderPreview = isCurrentTab && !isPreviewMutedByUser && hasCameraPermission
    DisposableEffect(shouldRenderPreview) {
        onDispose {
            if (!shouldRenderPreview) {
                viewModel.cameraEngine.detachPreviewSurface()
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status & Permission Card
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
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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
                            is CameraEngineState.Error -> (engineState as CameraEngineState.Error).message
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!hasCameraPermission) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant")
                    }
                } else {
                    TextButton(onClick = { isPreviewMutedByUser = !isPreviewMutedByUser }) {
                        Text(if (isPreviewMutedByUser) "Resume Preview" else "Mute Preview")
                    }
                }
            }
        }

        // Live Viewfinder Surface (Rendered only when active tab and not muted)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (shouldRenderPreview) {
                    AndroidView(
                        factory = { context ->
                            TextureView(context).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                        viewModel.cameraEngine.attachPreviewSurface(surface)
                                    }

                                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                        surface.setDefaultBufferSize(selectedRes.width, selectedRes.height)
                                    }

                                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                        viewModel.cameraEngine.detachPreviewSurface()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isPreviewMutedByUser) "Preview Paused by User" else if (!isCurrentTab) "Preview Muted (Background Tab)" else "Camera Ready",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (engineState is CameraEngineState.Recording) "Recording continues in background with zero UI overhead" else "Tap 'Resume Preview' to start live viewfinder",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Resolution Selector
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "RECORDING RESOLUTION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CameraResolution.values().forEach { res ->
                        val isSelected = selectedRes == res
                        if (isSelected) {
                            Button(
                                onClick = { viewModel.setCameraResolution(res) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(res.label, fontSize = 11.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setCameraResolution(res) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(res.label, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Target Frame Rate (FPS) Selector
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "TARGET FRAME RATE", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CameraFrameRate.values().forEach { fps ->
                        val isSelected = selectedFps == fps
                        if (isSelected) {
                            Button(
                                onClick = { viewModel.setCameraFrameRate(fps) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(fps.label, fontSize = 11.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setCameraFrameRate(fps) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(fps.label, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Telemetry & Storage Spec Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "Synchronization & Codec Spec", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "• Shutter Stamping: CameraCaptureSession.onCaptureStarted nanoseconds",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "• Container: camera/camera_video.mp4 (H.264 / AVC hardware surface)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "• Frame Index: camera/camera_frames.csv -> session_timeline.csv",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
