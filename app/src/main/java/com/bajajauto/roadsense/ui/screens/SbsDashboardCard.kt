package com.bajajauto.roadsense.ui.screens

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.RadarBevPlot

/**
 * Side-by-Side (SBS) Synchronized Feed Dashboard Card:
 * - Displays the live Camera viewfinder feed and real-time Radar Bird's-Eye View (BEV) plot
 *   simultaneously side-by-side in landscape (or split vertically in portrait).
 * - Retains live FPS, resolution chip, lens name, and recording telemetry.
 */
@Composable
fun SbsDashboardCard(
    viewModel: RadarViewModel,
    isCurrentTab: Boolean,
    latestFrame: RadarFrame?,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // --- LANDSCAPE: Side-by-Side Dual Pane (50% Camera / 50% Radar) ---
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left Pane: Live Camera Feed with telemetry header
            SbsCameraFeedPane(
                viewModel = viewModel,
                isCurrentTab = isCurrentTab,
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight()
            )

            // Right Pane: Live Radar BEV Scope
            RadarBevPlot(
                viewModel = viewModel,
                frame = latestFrame,
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight(),
                isLandscape = true
            )
        }
    } else {
        // --- PORTRAIT: Split Dual View (Top Camera / Bottom Radar) ---
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SbsCameraFeedPane(
                viewModel = viewModel,
                isCurrentTab = isCurrentTab,
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxWidth()
            )

            RadarBevPlot(
                viewModel = viewModel,
                frame = latestFrame,
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxWidth(),
                isLandscape = true
            )
        }
    }
}

/**
 * Live Camera Viewfinder pane for SBS view with compact telemetry header and matrix-aligned preview.
 */
@Composable
private fun SbsCameraFeedPane(
    viewModel: RadarViewModel,
    isCurrentTab: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engineState by viewModel.cameraEngineState.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val sessionFrames by viewModel.cameraSessionFrames.collectAsState()
    val isPreviewMutedByUser by viewModel.isCameraPreviewMuted.collectAsState()
    val cameraFps by viewModel.cameraFps.collectAsState()

    var hasCameraPermission by remember { mutableStateOf(viewModel.hasCameraPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

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

    val shouldRenderPreview = isSettledPreviewActive && hasCameraPermission

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11141C)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header Row: Title & Telemetry Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera",
                        tint = Color(0xFF80D8FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Camera Feed",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute / Pause Preview Toggle
                    IconButton(
                        onClick = { viewModel.setCameraPreviewMuted(!isPreviewMutedByUser) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isPreviewMutedByUser) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isPreviewMutedByUser) "Resume Viewfinder" else "Pause Viewfinder",
                            tint = if (isPreviewMutedByUser) MaterialTheme.colorScheme.error else Color(0xFF80D8FF),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Resolution Badge
                    Surface(
                        color = Color(0xFF1E2530),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = selectedRes.label,
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // FPS Badge
                    Surface(
                        color = Color(0xFF1E2530),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "%.0f FPS".format(cameraFps),
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // REC Pill if actively recording
                    if (engineState is CameraEngineState.Recording) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "● REC (${sessionFrames}f)",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Viewfinder Surface Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090B10)),
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
                            .clip(RoundedCornerShape(8.dp))
                    )

                    // Overlay Camera Lens Label
                    selectedCamera?.let { cam ->
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = cam.displayName,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                } else {
                    // Placeholder when camera is unpermitted or paused
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = when {
                                !hasCameraPermission -> "Camera Permission Required"
                                isCurrentTab && !isPreviewMutedByUser && !isSettledPreviewActive -> "Connecting Viewfinder..."
                                isPreviewMutedByUser -> "Viewfinder Paused (Off by Default)"
                                else -> "Viewfinder Inactive"
                            },
                            color = Color(0xFF90A4AE),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (isCurrentTab && !isPreviewMutedByUser && !isSettledPreviewActive) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (!hasCameraPermission) {
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Grant Permission", fontSize = 11.sp)
                            }
                        } else if (isPreviewMutedByUser) {
                            Button(
                                onClick = { viewModel.setCameraPreviewMuted(false) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Enable Viewfinder", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
