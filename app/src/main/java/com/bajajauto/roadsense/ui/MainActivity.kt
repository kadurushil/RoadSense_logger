package com.bajajauto.roadsense.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.bajajauto.roadsense.camera.AutoFitSurfaceView
import com.bajajauto.roadsense.ui.components.LocalPersistentCameraViewport
import com.bajajauto.roadsense.ui.components.PersistentCameraViewportState
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.diagnostics.UiFrameMetricsProfiler
import com.bajajauto.roadsense.BuildConfig
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.components.FullscreenCalibrationStudio
import com.bajajauto.roadsense.ui.components.FullscreenCameraPreview
import com.bajajauto.roadsense.ui.components.LaunchBuildOverlay
import com.bajajauto.roadsense.ui.components.LiveMetricsBar
import com.bajajauto.roadsense.ui.components.TopSessionHeader
import com.bajajauto.roadsense.ui.screens.CameraDashboardCard
import com.bajajauto.roadsense.ui.screens.CanedgeDashboardCard
import com.bajajauto.roadsense.ui.screens.GnssDashboardCard
import com.bajajauto.roadsense.ui.screens.ImuDashboardCard
import com.bajajauto.roadsense.ui.screens.RadarDashboardCard
import com.bajajauto.roadsense.ui.screens.SbsDashboardCard
import com.bajajauto.roadsense.ui.screens.SessionDeckCard
import com.bajajauto.roadsense.ui.theme.RoadSenseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()
    private val frameMetricsProfiler = UiFrameMetricsProfiler()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        AppLogger.initAppLogging(applicationContext)
        AppLogger.i(
            "MainActivity",
            "RoadSense started. Build=v${BuildConfig.VERSION_NAME} (#${BuildConfig.GIT_COMMIT_COUNT}, code=${BuildConfig.VERSION_CODE}, type=${BuildConfig.BUILD_TYPE}, commit=${BuildConfig.GIT_SHA}, branch=${BuildConfig.GIT_BRANCH}, built=${BuildConfig.BUILD_TIMESTAMP})"
        )

        // Lock screen refresh rate to 60.0 Hz for exact integer harmonic synchronization:
        // Radar 20.0 Hz = exactly 3 VSYNCs (50.0 ms) | Camera 30.0 FPS = exactly 2 VSYNCs (33.3 ms)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val params = window.attributes
                params.preferredRefreshRate = 60.0f
                window.attributes = params
                AppLogger.i("MainActivity", "Enforced 60.0 Hz display refresh rate via preferredRefreshRate")
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val mode60 = display?.supportedModes?.firstOrNull { kotlin.math.abs(it.refreshRate - 60.0f) < 1.0f }
                if (mode60 != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = mode60.modeId
                    window.attributes = params
                    AppLogger.i("MainActivity", "Enforced 60.0 Hz display refresh rate via modeId: ${mode60.modeId}")
                }
            }
        } catch (e: Exception) {
            AppLogger.w("MainActivity", "Could not set 60.0 Hz display refresh rate: ${e.message}")
        }

        frameMetricsProfiler.attach(window)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.importantForContentCapture =
                android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }

        enableEdgeToEdge()
        setContent {
            RoadSenseTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RoadSenseCockpitScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        frameMetricsProfiler.detach(window)
        AppLogger.i("MainActivity", "RoadSense onDestroy. Closing app run log.")
        AppLogger.closeAppLogging()
        super.onDestroy()
    }
}

enum class CockpitTab(val title: String, val icon: ImageVector) {
    RADAR("Radar", Icons.Default.Sensors),
    GNSS("GNSS", Icons.Default.MyLocation),
    IMU("IMU", Icons.Default.Explore),
    CAMERA("Camera", Icons.Default.Videocam),
    CANEDGE("CANedge", Icons.Default.DirectionsCar),
    SBS("SBS", Icons.Default.Splitscreen),
    SESSION("Storage", Icons.Default.Folder)
}

@Composable
fun RoadSenseCockpitScreen(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    val isCalibrationFullScreen by viewModel.isCalibrationFullScreen.collectAsState()
    val isCameraFullScreen by viewModel.isCameraFullScreen.collectAsState()
    var showSyncDialog by remember { mutableStateOf(false) }
    var showLaunchOverlay by rememberSaveable { mutableStateOf(true) }

    val tabs = remember { CockpitTab.values() }
    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val context = androidx.compose.ui.platform.LocalContext.current
    val composeView = androidx.compose.ui.platform.LocalView.current
    val hasSpokenScreenReader = remember(context) {
        val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)?.isNotEmpty() == true
    }
    SideEffect {
        if (!hasSpokenScreenReader) {
            composeView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            androidx.core.view.ViewCompat.setAccessibilityDelegate(composeView, null)
        }
    }

    val viewportState = remember { PersistentCameraViewportState() }
    var rootOriginInWindow by remember { mutableStateOf(IntOffset.Zero) }
    val lastAppliedViewport = remember {
        object {
            var resW = -1
            var resH = -1
            var rot = -1
            var w = -1
            var h = -1
            var active: Boolean? = null
        }
    }
    val density = LocalDensity.current
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val isActiveViewport = viewportState.isActive
    val viewportBounds = viewportState.boundsInWindow
    val relX = if (isActiveViewport) viewportBounds.left - rootOriginInWindow.x else 0
    val relY = if (isActiveViewport) viewportBounds.top - rootOriginInWindow.y else 0
    val targetW = if (isActiveViewport) viewportBounds.width else 1
    val targetH = if (isActiveViewport) viewportBounds.height else 1

    CompositionLocalProvider(LocalPersistentCameraViewport provides viewportState) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                rootOriginInWindow = IntOffset(p.x.roundToInt(), p.y.roundToInt())
            }
            .then(if (!hasSpokenScreenReader) Modifier.clearAndSetSemantics {} else Modifier)
    ) {
        // Single Persistent AutoFitSurfaceView Host (Point 1 + Point 2):
        // Renders Camera2 directly onto SurfaceFlinger HWC overlay plane (0ms HWUI GPU copy)
        // and stays attached across Camera, SBS, Fullscreen, Calibration, and non-camera tabs.
        AndroidView(
            factory = { ctx ->
                AutoFitSurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val res = viewModel.cameraEngine.selectedResolution.value
                            holder.setFixedSize(res.width, res.height)
                            if (viewportState.isActive) {
                                viewModel.cameraEngine.attachPreviewSurface(
                                    holder.surface,
                                    viewportState.boundsInWindow.width,
                                    viewportState.boundsInWindow.height
                                )
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (viewportState.isActive && width > 1 && height > 1) {
                                viewModel.cameraEngine.attachPreviewSurface(holder.surface, width, height)
                                viewModel.updateCameraDisplayRotation(viewportState.displayRotation, width = width, height = height)
                                viewModel.updateCameraIntrinsics(width, height)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.cameraEngine.detachPreviewSurface(holder.surface)
                        }
                    })
                }
            },
            update = { surfaceView ->
                val resChanged = lastAppliedViewport.resW != selectedRes.width || lastAppliedViewport.resH != selectedRes.height
                if (resChanged) {
                    surfaceView.holder.setFixedSize(selectedRes.width, selectedRes.height)
                    lastAppliedViewport.resW = selectedRes.width
                    lastAppliedViewport.resH = selectedRes.height
                }
                if (isActiveViewport) {
                    val rot = viewportState.displayRotation
                    val geomChanged = resChanged || lastAppliedViewport.rot != rot || lastAppliedViewport.w != targetW || lastAppliedViewport.h != targetH
                    val becameActive = lastAppliedViewport.active != true
                    if (resChanged || lastAppliedViewport.rot != rot) {
                        surfaceView.setAspectRatio(selectedRes.width, selectedRes.height, rot)
                    }
                    val surface = surfaceView.holder.surface
                    if (surface != null && surface.isValid && (becameActive || geomChanged)) {
                        viewModel.cameraEngine.attachPreviewSurface(surface, targetW, targetH)
                        if (geomChanged) {
                            viewModel.updateCameraDisplayRotation(rot, width = targetW, height = targetH)
                            viewModel.updateCameraIntrinsics(targetW, targetH)
                        }
                        lastAppliedViewport.rot = rot
                        lastAppliedViewport.w = targetW
                        lastAppliedViewport.h = targetH
                        lastAppliedViewport.active = true
                    }
                } else if (lastAppliedViewport.active != false) {
                    viewModel.cameraEngine.setPreviewPaused(true)
                    lastAppliedViewport.active = false
                }
            },
            modifier = Modifier
                .offset { IntOffset(relX, relY) }
                .size(
                    width = with(density) { targetW.toDp() },
                    height = with(density) { targetH.toDp() }
                )
        )
        if (isCameraFullScreen) {
            FullscreenCameraPreview(
                viewModel = viewModel,
                onExit = { viewModel.setCameraFullScreen(false) },
                modifier = Modifier.fillMaxSize()
            )
        } else if (isCalibrationFullScreen) {
            FullscreenCalibrationStudio(
                viewModel = viewModel,
                onExit = { viewModel.setCalibrationFullScreen(false) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (isLandscape) 1.dp else 4.dp)
            ) {
                // Single-Node Direct Canvas HUD (0 LayoutNodes, 0 Semantics tree walks, isolated RenderNode)
                Box(modifier = Modifier.padding(horizontal = if (isLandscape) 6.dp else 8.dp, vertical = 2.dp)) {
                    LiveMetricsBar(
                        viewModel = viewModel,
                        onSyncClick = { showSyncDialog = true }
                    )
                }

                CockpitTabNavigationBar(
                    tabs = tabs,
                    currentTabIndexProvider = { currentTabIndex },
                    onSelectTab = { index ->
                        if (currentTabIndex != index) {
                            currentTabIndex = index
                            AppLogger.i("UI", "Switched to dashboard tab: ${tabs[index].title}")
                        }
                    },
                    isLandscape = isLandscape,
                    viewModel = viewModel
                )

                // Single-Tab Composition Host:
                // Only the active tab is mounted in the Composition tree at any time, eliminating
                // fullscreen-exit multi-tab invalidation storms and off-screen RenderNode overhead.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (CockpitTab.entries[currentTabIndex]) {
                        CockpitTab.RADAR -> RadarDashboardCard(viewModel = viewModel)
                        CockpitTab.GNSS -> GnssDashboardCard(viewModel = viewModel)
                        CockpitTab.IMU -> ImuDashboardCard(viewModel = viewModel)
                        CockpitTab.CAMERA -> CameraDashboardCard(
                            viewModel = viewModel,
                            isCurrentTabProvider = { currentTabIndex == CockpitTab.CAMERA.ordinal }
                        )
                        CockpitTab.CANEDGE -> CanedgeDashboardCard(viewModel = viewModel)
                        CockpitTab.SBS -> SbsDashboardCard(
                            viewModel = viewModel,
                            isCurrentTabProvider = { currentTabIndex == CockpitTab.SBS.ordinal }
                        )
                        CockpitTab.SESSION -> SessionDeckCard(viewModel = viewModel)
                    }
                }
            }
        }

        if (showSyncDialog) {
            val syncTelemetry by viewModel.liveSyncTelemetry.collectAsState()
            com.bajajauto.roadsense.ui.components.SyncInspectionDialog(
                telemetry = syncTelemetry,
                onDismissRequest = { showSyncDialog = false }
            )
        }

        AnimatedVisibility(
            visible = showLaunchOverlay,
            exit = fadeOut(animationSpec = tween(durationMillis = 250))
        ) {
            LaunchBuildOverlay(onDismiss = { showLaunchOverlay = false })
        }
    }
    }
}

@Composable
private fun CockpitTabNavigationBar(
    tabs: Array<CockpitTab>,
    currentTabIndexProvider: () -> Int,
    onSelectTab: (Int) -> Unit,
    isLandscape: Boolean,
    viewModel: RadarViewModel
) {
    val currentTabIndex = currentTabIndexProvider()
    if (isLandscape) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .graphicsLayer()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = currentTabIndex == index
                        Surface(
                            selected = isSelected,
                            onClick = { onSelectTab(index) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(imageVector = tab.icon, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(text = tab.title, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }

                LandscapeSessionControlBar(viewModel = viewModel)
            }
        }
    } else {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TopSessionHeader(
                viewModel = viewModel,
                onStartSession = { viewModel.startSessionRecording() },
                onStopSession = { viewModel.stopSessionRecording() }
            )
        }

        PrimaryTabRow(
            selectedTabIndex = currentTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer()
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = currentTabIndex == index,
                    onClick = { onSelectTab(index) },
                    icon = { Icon(imageVector = tab.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = {
                        Text(
                            text = tab.title,
                            fontSize = 10.sp,
                            maxLines = 1,
                            fontWeight = if (currentTabIndex == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun LandscapeSessionControlBar(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    val state = viewModel.sessionRecordingState.collectAsState().value
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = when (state) {
                is SessionRecordingState.Recording -> MaterialTheme.colorScheme.errorContainer
                is SessionRecordingState.Finished -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.clearAndSetSemantics {}
        ) {
            val statusText = when (state) {
                is SessionRecordingState.Recording -> {
                    val durationSec = state.durationMs / 1000
                    "● REC %02d:%02d (%df)".format(durationSec / 60, durationSec % 60, state.framesRecorded)
                }
                is SessionRecordingState.Finished -> "✓ SAVED (${state.totalFrames}f)"
                else -> "IDLE"
            }
            Text(
                text = statusText,
                color = when (state) {
                    is SessionRecordingState.Recording -> MaterialTheme.colorScheme.error
                    is SessionRecordingState.Finished -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (state is SessionRecordingState.Recording) {
            Button(
                onClick = { viewModel.stopSessionRecording() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(26.dp)
            ) {
                Text("Stop", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { viewModel.startSessionRecording() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(26.dp)
            ) {
                Text(if (state is SessionRecordingState.Finished) "New" else "Record", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
