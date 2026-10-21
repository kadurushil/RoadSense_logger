package com.bajajauto.roadsense.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import com.bajajauto.roadsense.logging.AppLogger
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.bajajauto.roadsense.camera.AutoFitTextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.fusion.engine.CameraIntrinsics
import com.bajajauto.roadsense.fusion.engine.SpatialProjectionEngine
import androidx.compose.foundation.horizontalScroll
import com.bajajauto.roadsense.fusion.model.PitchQuality
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.fusion.sim.SimulatedRadarDataProvider
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Immersive Fullscreen Calibration Studio providing an edge-to-edge camera viewfinder
 * with reverse-camera style ground guidelines, distance ladder, interactive touch-dragging,
 * micro-nudge controls, and full GUI adjustment of all 6-DOF extrinsic vehicle parameters.
 */
@Composable
fun FullscreenCalibrationStudio(
    viewModel: RadarViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onExit)

    DisposableEffect(Unit) {
        onDispose {
            viewModel.setCalibrationFullScreen(false)
        }
    }

    val calibrationParams by viewModel.calibrationParams.collectAsState()
    val savedBaseline by viewModel.savedBaselineParams.collectAsState()
    val activeIntrinsics by viewModel.activeIntrinsics.collectAsState()
    val radarFrame by viewModel.latestFrame.collectAsState()
    val isRadarArcsEnabled by viewModel.isRadarArcsEnabled.collectAsState()
    val imuTelemetry by viewModel.imuTelemetry.collectAsState()
    val isLiveImuHorizonEnabled by viewModel.isLiveImuHorizonEnabled.collectAsState()

    // Dynamically synthesize active display parameters when Live IMU Horizon Follow is active
    val activeDisplayParams = remember(calibrationParams, imuTelemetry, isLiveImuHorizonEnabled) {
        if (isLiveImuHorizonEnabled) {
            calibrationParams.copy(
                pitchDeg = imuTelemetry.pitchDeg,
                rollDeg = imuTelemetry.rollDeg,
                nudgePitchDeg = 0f
            )
        } else {
            calibrationParams
        }
    }

    val context = LocalContext.current
    val windowManager = remember { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val displayRotation = windowManager.defaultDisplay.rotation

    var showExtrinsicsDrawer by remember { mutableStateOf(false) }
    var showHorizonLine by remember { mutableStateOf(true) }
    var showGuideLines by remember { mutableStateOf(true) }
    var showSimulatedPoints by remember { mutableStateOf(false) }
    var simFrameIndex by remember { mutableIntStateOf(0) }
    var studioWidth by remember { mutableStateOf(1920) }
    var studioHeight by remember { mutableStateOf(1080) }

    // 10 Hz animation cycle for the 10-frame simulated radar point cloud
    LaunchedEffect(showSimulatedPoints) {
        if (showSimulatedPoints) {
            while (isActive) {
                delay(100)
                simFrameIndex = (simFrameIndex + 1) % 10
            }
        }
    }

    // Detect if current calibration configuration has been disturbed/modified from saved baseline
    val isModified = remember(calibrationParams, savedBaseline) {
        savedBaseline == null ||
        abs(calibrationParams.effectivePitchDeg - savedBaseline!!.pitchDeg) > 0.05f ||
        abs(calibrationParams.effectiveYawDeg - savedBaseline!!.yawDeg) > 0.05f ||
        abs(calibrationParams.nudgePitchDeg) > 0.01f ||
        abs(calibrationParams.nudgeYawDeg) > 0.01f ||
        abs(calibrationParams.setbackM - savedBaseline!!.setbackM) > 0.01f ||
        abs(calibrationParams.heightOffsetM - savedBaseline!!.heightOffsetM) > 0.01f ||
        abs(calibrationParams.radarHeightM - savedBaseline!!.radarHeightM) > 0.01f ||
        abs(calibrationParams.targetDistanceM - savedBaseline!!.targetDistanceM) > 0.05f ||
        abs(calibrationParams.targetWidthM - savedBaseline!!.targetWidthM) > 0.01f ||
        abs(calibrationParams.targetHeightM - savedBaseline!!.targetHeightM) > 0.01f ||
        abs(calibrationParams.rollDeg - savedBaseline!!.rollDeg) > 0.05f ||
        abs(calibrationParams.lateralOffsetM - savedBaseline!!.lateralOffsetM) > 0.01f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Viewport Box: Houses camera preview and all spatial projection overlays edge-to-edge
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 1. Edge-to-Edge Camera Viewfinder (Rotated according to display orientation, strict 16:9 Fit)
            AndroidView(
                factory = { ctx ->
                    AutoFitTextureView(ctx).apply {
                        val res = viewModel.cameraEngine.selectedResolution.value
                        setAspectRatio(res.width, res.height, displayRotation)
                        val persistent = viewModel.cameraEngine.persistentSurfaceTexture
                        if (persistent != null && !persistent.isReleased) {
                            try {
                                setSurfaceTexture(persistent)
                            } catch (e: Exception) {
                                AppLogger.w("FullscreenCalibrationStudio", "Could not reuse persistent SurfaceTexture: ${e.message}")
                            }
                        }
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                studioWidth = width
                                studioHeight = height
                                viewModel.cameraEngine.attachPreviewSurface(surface, width, height)
                                viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                viewModel.updateCameraIntrinsics(width, height)
                            }

                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                studioWidth = width
                                studioHeight = height
                                viewModel.cameraEngine.attachPreviewSurface(surface, width, height)
                                viewModel.updateCameraDisplayRotation(displayRotation, this@apply, width, height)
                                viewModel.updateCameraIntrinsics(width, height)
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                viewModel.cameraEngine.detachPreviewSurface(surface)
                                return false
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                        }
                    }
                },
                update = { textureView ->
                    val res = viewModel.cameraEngine.selectedResolution.value
                    textureView.setAspectRatio(res.width, res.height, displayRotation)
                    if (textureView.isAvailable) {
                        studioWidth = textureView.width
                        studioHeight = textureView.height
                        val st = textureView.surfaceTexture
                        if (st != null && !viewModel.cameraEngine.isSurfaceAttached(st)) {
                            viewModel.cameraEngine.attachPreviewSurface(st, textureView.width, textureView.height)
                        }
                        viewModel.updateCameraDisplayRotation(displayRotation, textureView, textureView.width, textureView.height)
                        viewModel.updateCameraIntrinsics(textureView.width, textureView.height)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Reverse-Camera Style Ground Guidelines, Distance Ladder & Horizon Line
            if ((showHorizonLine || showGuideLines) && activeIntrinsics != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width.toInt()
                    val h = size.height.toInt()
                    val guides = SpatialProjectionEngine.calculateGroundGuideLines(
                        params = activeDisplayParams,
                        intrinsics = activeIntrinsics!!,
                        viewWidth = w,
                        viewHeight = h
                    )

                    // Ground Wheel Tracks & Centerline
                    if (showGuideLines) {
                        // Left Wheel Track
                        if (guides.leftTrack.size >= 2) {
                            val pathL = Path().apply {
                                moveTo(guides.leftTrack[0].xPx, guides.leftTrack[0].yPx)
                                for (i in 1 until guides.leftTrack.size) {
                                    lineTo(guides.leftTrack[i].xPx, guides.leftTrack[i].yPx)
                                }
                            }
                            drawPath(
                                path = pathL,
                                color = Color(0xFF00E5FF).copy(alpha = 0.55f),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        // Right Wheel Track
                        if (guides.rightTrack.size >= 2) {
                            val pathR = Path().apply {
                                moveTo(guides.rightTrack[0].xPx, guides.rightTrack[0].yPx)
                                for (i in 1 until guides.rightTrack.size) {
                                    lineTo(guides.rightTrack[i].xPx, guides.rightTrack[i].yPx)
                                }
                            }
                            drawPath(
                                path = pathR,
                                color = Color(0xFF00E5FF).copy(alpha = 0.55f),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        // Centerline (Forward Boresight Track)
                        if (guides.centerline.size >= 2) {
                            val pathC = Path().apply {
                                moveTo(guides.centerline[0].xPx, guides.centerline[0].yPx)
                                for (i in 1 until guides.centerline.size) {
                                    lineTo(guides.centerline[i].xPx, guides.centerline[i].yPx)
                                }
                            }
                            drawPath(
                                path = pathC,
                                color = Color.White.copy(alpha = 0.45f),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                                )
                            )
                        }

                        // Ground Distance Ladder Rungs
                        for (rung in guides.rungs) {
                            val rungColor = when {
                                rung.isTarget -> Color(0xFFFFD54F) // Gold Target
                                rung.distanceM <= 3.5f -> Color(0xFFFF5252) // Red 3m
                                rung.distanceM <= 6.0f -> Color(0xFFFFB74D) // Amber 5m
                                rung.distanceM <= 16.0f -> Color(0xFF69F0AE) // Green 10m/15m
                                else -> Color(0xFF40C4FF).copy(alpha = 0.6f) // Cyan 20m/30m
                            }
                            val baseStrokeW = 2.dp.toPx()

                            // 1. Full transverse distance bar (consistent perspective ladder width)
                            drawLine(
                                color = if (rung.isTarget) rungColor.copy(alpha = 0.65f) else rungColor,
                                start = Offset(rung.leftPt.xPx, rung.leftPt.yPx),
                                end = Offset(rung.rightPt.xPx, rung.rightPt.yPx),
                                strokeWidth = baseStrokeW,
                                cap = StrokeCap.Round
                            )

                            // 2. Outer end notch ticks (ladder rails perspective alignment)
                            val outerTickLen = 8f * density
                            drawLine(
                                color = rungColor,
                                start = Offset(rung.leftPt.xPx, rung.leftPt.yPx - outerTickLen),
                                end = Offset(rung.leftPt.xPx, rung.leftPt.yPx + outerTickLen),
                                strokeWidth = baseStrokeW,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = rungColor,
                                start = Offset(rung.rightPt.xPx, rung.rightPt.yPx - outerTickLen),
                                end = Offset(rung.rightPt.xPx, rung.rightPt.yPx + outerTickLen),
                                strokeWidth = baseStrokeW,
                                cap = StrokeCap.Round
                            )

                            // 3. If Target: Draw inner vehicle caliper markings (1.3m car width)
                            if (rung.isTarget && rung.targetLeftPt != null && rung.targetRightPt != null) {
                                val tL = rung.targetLeftPt
                                val tR = rung.targetRightPt

                                // Bold highlighted car width contact bar
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tL.xPx, tL.yPx),
                                    end = Offset(tR.xPx, tR.yPx),
                                    strokeWidth = 3.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )

                                // Vertical caliper brackets at car width boundaries [ ... ]
                                val caliperLen = 14f * density
                                val cornerTick = 6f * density

                                // Left caliper bracket [
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tL.xPx, tL.yPx - caliperLen),
                                    end = Offset(tL.xPx, tL.yPx + caliperLen),
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tL.xPx, tL.yPx - caliperLen),
                                    end = Offset(tL.xPx + cornerTick, tL.yPx - caliperLen),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tL.xPx, tL.yPx + caliperLen),
                                    end = Offset(tL.xPx + cornerTick, tL.yPx + caliperLen),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )

                                // Right caliper bracket ]
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tR.xPx, tR.yPx - caliperLen),
                                    end = Offset(tR.xPx, tR.yPx + caliperLen),
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tR.xPx, tR.yPx - caliperLen),
                                    end = Offset(tR.xPx - cornerTick, tR.yPx - caliperLen),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(tR.xPx, tR.yPx + caliperLen),
                                    end = Offset(tR.xPx - cornerTick, tR.yPx + caliperLen),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )

                                // Center boresight notch (crosshair tick on vehicle center badge)
                                val midX = (tL.xPx + tR.xPx) / 2f
                                val midY = (tL.yPx + tR.yPx) / 2f
                                drawLine(
                                    color = Color(0xFFFFD54F),
                                    start = Offset(midX, midY - 10f * density),
                                    end = Offset(midX, midY + 10f * density),
                                    strokeWidth = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )

                                // Centered car width label below caliper brackets & yellow target line
                                val carLabel = "${String.format(java.util.Locale.US, "%.1fm CAR", activeDisplayParams.targetWidthM)}"
                                val carTextPaint = Paint().apply {
                                    color = android.graphics.Color.YELLOW
                                    textSize = 10f * density
                                    isFakeBoldText = true
                                    typeface = Typeface.MONOSPACE
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                                }
                                drawContext.canvas.nativeCanvas.drawText(
                                    carLabel,
                                    midX,
                                    midY + caliperLen + 4f * density + carTextPaint.textSize,
                                    carTextPaint
                                )
                            }

                            // 4. Outer Distance Text Label (Right Side of Ladder)
                            val text = rung.label
                            val textPaint = Paint().apply {
                                color = if (rung.isTarget) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                                textSize = (if (rung.isTarget) 12f else 10f) * density
                                isFakeBoldText = true
                                typeface = Typeface.MONOSPACE
                                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                            }
                            val labelX = rung.rightPt.xPx + 10f * density
                            val labelY = rung.rightPt.yPx + (textPaint.textSize / 3f)
                            drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, textPaint)
                        }
                    }

                    // 3. Artificial Horizon Line
                    if (showHorizonLine) {
                        val horizon = guides.horizonLine
                        drawLine(
                            color = Color.Cyan.copy(alpha = 0.75f),
                            start = Offset(horizon.first.xPx, horizon.first.yPx),
                            end = Offset(horizon.second.xPx, horizon.second.yPx),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(25f, 15f), 0f)
                        )

                        // Center Boresight Horizon Crosshair
                        val hCenterX = w / 2f
                        val hCenterY = (horizon.first.yPx + horizon.second.yPx) / 2f
                        drawLine(
                            color = Color.Cyan,
                            start = Offset(hCenterX - 20.dp.toPx(), hCenterY),
                            end = Offset(hCenterX + 20.dp.toPx(), hCenterY),
                            strokeWidth = 2.5.dp.toPx()
                        )
                        drawLine(
                            color = Color.Cyan,
                            start = Offset(hCenterX, hCenterY - 10.dp.toPx()),
                            end = Offset(hCenterX, hCenterY + 10.dp.toPx()),
                            strokeWidth = 2.5.dp.toPx()
                        )

                        // Horizon Pitch & Roll Text Badge
                        val hPaint = Paint().apply {
                            color = android.graphics.Color.CYAN
                            textSize = 10f * density
                            isFakeBoldText = true
                            typeface = Typeface.MONOSPACE
                            setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                        }
                        val hText = "HORIZON (θ: ${String.format(Locale.US, "%.1f°", activeDisplayParams.effectivePitchDeg)}, ϕ: ${String.format(Locale.US, "%.1f°", activeDisplayParams.rollDeg)})"
                        drawContext.canvas.nativeCanvas.drawText(hText, hCenterX - 110f * density, hCenterY - 14f, hPaint)
                    }
                }
            }

            // 3. Live or Simulated Radar Detections Overlay
            val activeRadarFrame = if (showSimulatedPoints) {
                SimulatedRadarDataProvider.getFrame(
                    frameIndex = simFrameIndex,
                    targetDist = activeDisplayParams.targetDistanceM,
                    targetWidth = activeDisplayParams.targetWidthM
                )
            } else {
                radarFrame
            }

            ViewfinderRadarOverlay(
                radarFrame = activeRadarFrame,
                calibrationParams = activeDisplayParams,
                intrinsics = activeIntrinsics,
                viewWidth = studioWidth,
                viewHeight = studioHeight,
                showRangeArcs = isRadarArcsEnabled,
                modifier = Modifier.fillMaxSize()
            )

            // 4. Gesture Trackpad Layer (Swiping anywhere on screen moves calibration)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(studioWidth, studioHeight) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            viewModel.applyCalibrationDelta(dragAmount.x, dragAmount.y, size.width, size.height)
                        }
                    }
            )
        }

        // 5. Slim Top Floating Bar with IMU Assistant Deck
        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Exit Button + Compact Title
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onExit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "CALIBRATION STUDIO",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Center: IMU Assistant Cluster (Level Pill, Pitch Quality, Live IMU, Sync Pitch)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Feature B: Digital Spirit Level Pill
                    DigitalClampLevelPill(
                        rollDeg = imuTelemetry.rollDeg,
                        onClick = { viewModel.updateExtrinsics(rollDeg = imuTelemetry.rollDeg) }
                    )

                    // Feature D: Pitch Quality Badge
                    PitchQualityBadge(
                        quality = activeDisplayParams.pitchQuality,
                        pitchDeg = activeDisplayParams.effectivePitchDeg
                    )

                    // Feature C: Live IMU Follow Toggle
                    FilterChip(
                        selected = isLiveImuHorizonEnabled,
                        onClick = { viewModel.toggleLiveImuHorizon() },
                        label = {
                            Text(
                                if (isLiveImuHorizonEnabled) "Live IMU ●" else "Live IMU",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.35f),
                            selectedLabelColor = Color(0xFF00E5FF)
                        )
                    )

                    // Feature A: One-Tap Sync Pitch Button
                    OutlinedButton(
                        onClick = { viewModel.syncPitchFromImu() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF64B5F6)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Pitch from IMU",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Sync Pitch", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right: Toggles & Dynamic Save / Load Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = showHorizonLine,
                        onClick = { showHorizonLine = !showHorizonLine },
                        label = { Text("Horizon", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = showGuideLines,
                        onClick = { showGuideLines = !showGuideLines },
                        label = { Text("Grid", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = isRadarArcsEnabled,
                        onClick = { viewModel.setRadarArcsEnabled(!isRadarArcsEnabled) },
                        label = { Text("Radar Arcs", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.25f),
                            selectedLabelColor = Color(0xFF80D8FF)
                        )
                    )
                    FilterChip(
                        selected = showSimulatedPoints,
                        onClick = { showSimulatedPoints = !showSimulatedPoints },
                        label = { Text("Sim Cloud", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE91E63).copy(alpha = 0.35f),
                            selectedLabelColor = Color(0xFFFF80AB)
                        )
                    )

                    // Load Saved Config (Always present, active when modified, disabled when in sync)
                    OutlinedButton(
                        onClick = { viewModel.loadSavedCalibration() },
                        enabled = isModified,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFFB74D),
                            disabledContentColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Load Saved",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Load Saved", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // Save Baseline / Saved status button
                    Button(
                        onClick = { viewModel.saveBaselineCalibration() },
                        enabled = isModified,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            disabledContainerColor = Color(0xFF2E7D32).copy(alpha = 0.7f),
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isModified) Icons.Default.Save else Icons.Default.Check,
                            contentDescription = if (isModified) "Save Baseline" else "Saved",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isModified) "Save Baseline" else "Saved",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 6. Left Pillarbox Deck (Camera Pitch Controls - Vertically Arranged in Left Wing)
        Surface(
            color = Color.Black.copy(alpha = 0.70f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .padding(start = 10.dp, top = 54.dp, bottom = 12.dp)
                .align(Alignment.CenterStart)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Pitch badge (θ_cam)
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewModel.resetPitchToZero() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "θ_cam",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f°", activeDisplayParams.effectivePitchDeg),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF64B5F6)
                    )
                }

                // Vertical Pitch Nudges ▲ and ▼
                OutlinedButton(
                    onClick = { viewModel.nudgePitch(0.1f) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(30.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("▲", fontSize = 11.sp, color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { viewModel.nudgePitch(-0.1f) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(30.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("▼", fontSize = 11.sp, color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(modifier = Modifier.width(32.dp), color = Color.DarkGray)

                // SYNC / LOCK PITCH Button
                if (isLiveImuHorizonEnabled) {
                    Button(
                        onClick = { viewModel.lockLiveImuPitch() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("LOCK", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.syncPitchFromImu() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(26.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6))
                    ) {
                        Text("SYNC", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 7. Right Pillarbox Deck (Radar Yaw Controls & Extrinsics Dialog in Right Wing)
        Surface(
            color = Color.Black.copy(alpha = 0.70f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .padding(end = 10.dp, top = 54.dp, bottom = 12.dp)
                .align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Radar Yaw badge (ψ_rad)
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewModel.resetRadarYawToZero() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ψ_rad",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f°", activeDisplayParams.effectiveYawDeg),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF81C784)
                    )
                }

                // Horizontal Yaw Nudges ◄ and ►
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.nudgeYaw(-0.1f) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("◄", fontSize = 11.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.nudgeYaw(0.1f) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("►", fontSize = 11.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                    }
                }

                // Reset Radar Yaw (0°)
                OutlinedButton(
                    onClick = { viewModel.resetRadarYawToZero() },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81C784))
                ) {
                    Text("0°", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(modifier = Modifier.width(32.dp), color = Color.DarkGray)

                // Extrinsics Settings Dialog Button
                IconButton(
                    onClick = { showExtrinsicsDrawer = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Extrinsics Settings",
                        tint = Color.Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 7. Extrinsics & Mounting Setup Dialog (Smoothly scrollable in landscape & portrait)
        if (showExtrinsicsDrawer) {
            CalibrationExtrinsicsDialog(
                params = calibrationParams,
                onUpdateParams = { setback, heightOffset, radarHeight, lateral, targetDist, targetW, targetH, roll ->
                    viewModel.updateExtrinsics(
                        setbackM = setback,
                        heightOffsetM = heightOffset,
                        radarHeightM = radarHeight,
                        lateralOffsetM = lateral,
                        targetDistanceM = targetDist,
                        targetWidthM = targetW,
                        targetHeightM = targetH,
                        rollDeg = roll
                    )
                },
                onDismiss = { showExtrinsicsDrawer = false }
            )
        }
    }
}

/**
 * Clean, scrollable modal dialog for adjusting extrinsic physical parameters.
 * Eliminates screen clutter, overlaps, and layout breakage in landscape mode.
 */
@Composable
private fun CalibrationExtrinsicsDialog(
    params: CalibrationParameters,
    onUpdateParams: (
        setback: Float?,
        heightOffset: Float?,
        radarHeight: Float?,
        lateral: Float?,
        targetDist: Float?,
        targetW: Float?,
        targetH: Float?,
        roll: Float?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "⚙️ Extrinsics & Target Setup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Physical vehicle measurements & target geometry",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)

                // Scrollable Content Column (Smooth scrolling in landscape)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "🚗 VEHICLE & RADAR MOUNT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64B5F6)
                    )

                    PrecisionStepperRow(
                        label = "Setback (ΔY)",
                        subtitle = "Phone distance behind radar",
                        value = params.setbackM,
                        unit = "m",
                        range = 0.10f..3.50f,
                        step = 0.05f,
                        onValueChange = { onUpdateParams(it, null, null, null, null, null, null, null) }
                    )

                    PrecisionStepperRow(
                        label = "Height Diff (ΔZ)",
                        subtitle = "Phone lens elevation above radar",
                        value = params.heightOffsetM,
                        unit = "m",
                        range = -0.50f..1.50f,
                        step = 0.05f,
                        onValueChange = { onUpdateParams(null, it, null, null, null, null, null, null) }
                    )

                    PrecisionStepperRow(
                        label = "Radar Height",
                        subtitle = "Bonnet elevation above road",
                        value = params.radarHeightM,
                        unit = "m",
                        range = 0.30f..1.80f,
                        step = 0.05f,
                        onValueChange = { onUpdateParams(null, null, it, null, null, null, null, null) }
                    )

                    PrecisionStepperRow(
                        label = "Lateral Offset (ΔX)",
                        subtitle = "Left / right offset from radar",
                        value = params.lateralOffsetM,
                        unit = "m",
                        range = -0.50f..0.50f,
                        step = 0.05f,
                        onValueChange = { onUpdateParams(null, null, null, it, null, null, null, null) }
                    )

                    PrecisionStepperRow(
                        label = "Windshield Clamp Roll (ϕ)",
                        subtitle = "Phone lateral rotation tilt relative to road",
                        value = params.rollDeg,
                        unit = "°",
                        range = -10.0f..10.0f,
                        step = 0.5f,
                        onValueChange = { onUpdateParams(null, null, null, null, null, null, null, it) }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.DarkGray.copy(alpha = 0.5f))

                    Text(
                        text = "🎯 TARGET VEHICLE SETUP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD54F)
                    )

                    PrecisionStepperRow(
                        label = "Target Distance (D)",
                        subtitle = "Known distance to parked car",
                        value = params.targetDistanceM,
                        unit = "m",
                        range = 3.0f..25.0f,
                        step = 0.5f,
                        onValueChange = { onUpdateParams(null, null, null, null, it, null, null, null) }
                    )

                    PrecisionStepperRow(
                        label = "Target Car Width (W)",
                        subtitle = "Known physical width of car",
                        value = params.targetWidthM,
                        unit = "m",
                        range = 0.80f..2.50f,
                        step = 0.05f,
                        onValueChange = { onUpdateParams(null, null, null, null, null, it, null, null) }
                    )

                    PrecisionStepperRow(
                        label = "Target Feature Elevation",
                        subtitle = "Feature height relative to radar",
                        value = params.targetHeightM,
                        unit = "m",
                        range = -1.50f..1.50f,
                        step = 0.05f,
                        onValueChange = { onUpdateParams(null, null, null, null, null, null, it, null) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)

                // Dialog Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Precision stepper row combining [+] and [-] increment buttons with a smooth slider.
 */
@Composable
private fun PrecisionStepperRow(
    label: String,
    subtitle: String,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF262626), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }

            // Stepper buttons [-] [value] [+]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val next = (value - step).coerceIn(range.start, range.endInclusive)
                        onValueChange((next * 100).roundToInt() / 100f)
                    },
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(28.dp),
                    enabled = value > range.start
                ) {
                    Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Surface(
                    color = Color(0xFF333333),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.width(72.dp).height(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = String.format(Locale.US, "%.2f %s", value, unit),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFFD54F)
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        val next = (value + step).coerceIn(range.start, range.endInclusive)
                        onValueChange((next * 100).roundToInt() / 100f)
                    },
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(28.dp),
                    enabled = value < range.endInclusive
                ) {
                    Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Slider for quick continuous dragging
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )
    }
}

/**
 * Visual Digital Spirit Level Pill (Feature B).
 * Guides the user to level the smartphone windshield clamp (Roll -> 0.0°).
 * Displays glowing green when within ±0.8° of level ground,
 * or amber directional arrows when tilted left/right.
 */
@Composable
fun DigitalClampLevelPill(
    rollDeg: Float,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val absRoll = abs(rollDeg)
    val isLevel = absRoll <= 0.8f
    val isSevere = absRoll > 3.0f

    val (bgCol, textCol, labelText) = when {
        isSevere -> Triple(
            Color(0xFFB71C1C).copy(alpha = 0.85f),
            Color.White,
            String.format(Locale.US, "▲ HIGH ROLL %+.1f°", rollDeg)
        )
        isLevel -> Triple(
            Color(0xFF2E7D32).copy(alpha = 0.85f),
            Color(0xFFA5D6A7),
            String.format(Locale.US, "● LEVEL %+.1f°", rollDeg)
        )
        rollDeg < -0.8f -> Triple(
            Color(0xFFE65100).copy(alpha = 0.85f),
            Color(0xFFFFCC80),
            String.format(Locale.US, "◄ TILT L %.1f°", absRoll)
        )
        else -> Triple(
            Color(0xFFE65100).copy(alpha = 0.85f),
            Color(0xFFFFCC80),
            String.format(Locale.US, "TILT R %.1f° ►", absRoll)
        )
    }

    Surface(
        color = bgCol,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = labelText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textCol
            )
        }
    }
}

/**
 * Pitch Quality Status Badge (Feature D).
 * Evaluates camera pitch angle for forward automotive ADAS perception.
 */
@Composable
fun PitchQualityBadge(
    quality: PitchQuality,
    pitchDeg: Float,
    modifier: Modifier = Modifier
) {
    val (bgCol, textCol, labelText) = when (quality) {
        PitchQuality.OPTIMAL -> Triple(
            Color(0xFF1B5E20).copy(alpha = 0.85f),
            Color(0xFF81C784),
            "OPTIMAL PITCH"
        )
        PitchQuality.TOO_SHALLOW -> Triple(
            Color(0xFFE65100).copy(alpha = 0.85f),
            Color(0xFFFFCC80),
            String.format(Locale.US, "TOO SHALLOW (%.1f°)", pitchDeg)
        )
        PitchQuality.TOO_STEEP -> Triple(
            Color(0xFFBF360C).copy(alpha = 0.85f),
            Color(0xFFFFAB91),
            String.format(Locale.US, "TOO STEEP (%.1f°)", pitchDeg)
        )
        PitchQuality.FACING_UP -> Triple(
            Color(0xFFB71C1C).copy(alpha = 0.85f),
            Color.White,
            String.format(Locale.US, "FACING UP (%.1f°)", pitchDeg)
        )
    }

    Surface(
        color = bgCol,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Text(
            text = labelText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textCol,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
