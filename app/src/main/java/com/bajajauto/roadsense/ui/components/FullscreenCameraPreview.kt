package com.bajajauto.roadsense.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.camera.RoadAeMode
import com.bajajauto.roadsense.camera.RoadAeState
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.screens.TapFocusReticleOverlay
import java.util.Locale

/**
 * Immersive edge-to-edge Fullscreen Camera Preview HUD:
 * - Edge-to-edge Camera2 TextureView with proper aspect ratio and display rotation transforms.
 * - Tap-to-focus (AF/AE) with floating reticle overlay.
 * - Live Radar HUD overlay (points, tracks, range arcs at 10m/30m/60m/120m).
 * - Floating top action pill: Exit button, resolution/fps badge, HUD toggle, Range Arcs toggle, Nudge toggle, and Record/Stop timer button.
 * - Translucent bottom micro-nudge bar for on-the-fly Pitch/Yaw calibration.
 */
@Composable
fun FullscreenCameraPreview(
    viewModel: RadarViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onExit)

    val calibrationParams by viewModel.calibrationParams.collectAsState()
    val activeIntrinsics by viewModel.activeIntrinsics.collectAsState()
    val radarFrame by viewModel.latestFrame.collectAsState()
    val isRadarOverlayEnabled by viewModel.isRadarOverlayEnabled.collectAsState()
    val isRadarArcsEnabled by viewModel.isRadarArcsEnabled.collectAsState()
    val sessionRecordingState by viewModel.sessionRecordingState.collectAsState()
    val cameraEngineState by viewModel.cameraEngineState.collectAsState()
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val selectedFps by viewModel.selectedFps.collectAsState()
    val isInfinityLocked by viewModel.isInfinityFocusLocked.collectAsState()
    val isAfAeLocked by viewModel.cameraEngine.isAfAeLocked.collectAsState()
    val tapPoint by viewModel.tapFocusPoint.collectAsState()
    val roadAeState by viewModel.roadAeState.collectAsState()
    val roadAeMode by viewModel.cameraEngine.roadAeMode.collectAsState()

    val context = LocalContext.current
    val windowManager = remember { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val displayRotation = windowManager.defaultDisplay.rotation

    var previewWidth by remember { mutableIntStateOf(1920) }
    var previewHeight by remember { mutableIntStateOf(1080) }
    var showNudgeBar by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. Edge-to-Edge Camera Viewfinder
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
                    viewModel.updateCameraDisplayRotation(displayRotation, textureView, textureView.width, textureView.height)
                    viewModel.updateCameraIntrinsics(textureView.width, textureView.height)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Tap-to-Focus Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                        viewModel.triggerCameraAf(normX, normY)
                    }
                }
        )

        // 3. AF/AE Focus Lock Reticle
        if (isAfAeLocked) {
            TapFocusReticleOverlay(tapPoint = tapPoint)
        }

        // 4. Radar HUD Overlay (Point Cloud, Tracks, Range Arcs)
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

        // 5. Slim Top Action Pill
        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Exit Button + Info Badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onExit,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = "CAMERA HUD",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )

                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${selectedRes.name.removePrefix("RES_")} • ${selectedFps.name.removePrefix("FPS_")}fps",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFCFD8DC),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (isInfinityLocked) {
                        Surface(
                            color = Color(0xFF1976D2).copy(alpha = 0.4f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "∞ LOCK",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF90CAF9),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (roadAeMode == RoadAeMode.AUTO_ROAD && roadAeState != RoadAeState.BALANCED && roadAeState != RoadAeState.TAP_LOCKED) {
                        Surface(
                            color = Color(0xFFF57C00).copy(alpha = 0.35f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            val txt = if (roadAeState == RoadAeState.SKY_BLOOM) "ROAD AE ☀️" else "ROAD AE 🌙"
                            Text(
                                text = txt,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB74D),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Right: HUD, Range Arcs, Nudge, and Record Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = isRadarOverlayEnabled,
                        onClick = { viewModel.toggleRadarOverlay() },
                        label = { Text("HUD", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.35f),
                            selectedLabelColor = Color(0xFF81C784)
                        )
                    )

                    if (isRadarOverlayEnabled) {
                        FilterChip(
                            selected = isRadarArcsEnabled,
                            onClick = { viewModel.setRadarArcsEnabled(!isRadarArcsEnabled) },
                            label = { Text("Arcs", fontSize = 10.sp) },
                            modifier = Modifier.height(28.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF80D8FF)
                            )
                        )
                    }

                    FilterChip(
                        selected = showNudgeBar,
                        onClick = { showNudgeBar = !showNudgeBar },
                        label = { Text("Nudge", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    // Live Session Recording Button
                    val isRecording = sessionRecordingState is SessionRecordingState.Recording || cameraEngineState is CameraEngineState.Recording
                    if (isRecording) {
                        Button(
                            onClick = { viewModel.stopSessionRecording() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val durationSec = (sessionRecordingState as? SessionRecordingState.Recording)?.durationMs?.div(1000) ?: 0L
                            val mins = durationSec / 60
                            val secs = durationSec % 60
                            Text(
                                text = "STOP ${String.format(Locale.US, "%02d:%02d", mins, secs)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startSessionRecording() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "REC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 6. Translucent Bottom Micro-Nudge Bar
        if (showNudgeBar) {
            QuickNudgeBar(
                calibrationParams = calibrationParams,
                onNudgePitch = { viewModel.nudgePitch(it) },
                onNudgeYaw = { viewModel.nudgeYaw(it) },
                onRevert = { viewModel.revertNudges() },
                onSave = { viewModel.saveBaselineCalibration() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}
