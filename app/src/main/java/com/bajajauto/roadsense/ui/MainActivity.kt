package com.bajajauto.roadsense.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.Splitscreen
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.components.LiveMetricsBar
import com.bajajauto.roadsense.ui.components.TopSessionHeader
import com.bajajauto.roadsense.ui.screens.CameraDashboardCard
import com.bajajauto.roadsense.ui.screens.CanedgeDashboardCard
import com.bajajauto.roadsense.ui.screens.GnssDashboardCard
import com.bajajauto.roadsense.ui.screens.ImuDashboardCard
import com.bajajauto.roadsense.ui.screens.RadarDashboardCard
import com.bajajauto.roadsense.ui.screens.SbsDashboardCard
import com.bajajauto.roadsense.ui.screens.SessionDeckCard
import com.bajajauto.roadsense.ui.components.FullscreenCalibrationStudio
import com.bajajauto.roadsense.ui.components.FullscreenCameraPreview
import com.bajajauto.roadsense.ui.theme.RoadSenseTheme
import androidx.compose.material.icons.filled.Explore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.initAppLogging(applicationContext)
        AppLogger.i("MainActivity", "RoadSense started. App run diagnostics logging initialized.")

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoadSenseCockpitScreen(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    // High-level Cockpit Overlay State
    val isCalibrationFullScreen by viewModel.isCalibrationFullScreen.collectAsState()
    val isCameraFullScreen by viewModel.isCameraFullScreen.collectAsState()
    var showSyncDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val tabs = remember { CockpitTab.values() }
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Breadcrumb: Record tab navigation
    LaunchedEffect(pagerState.currentPage) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Swiped to dashboard tab: ${tabs[pagerState.currentPage].title}")
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                // High-Density Live Telemetry Ticker (Sensors Hz, GNSS Lock, Battery, CPU & Temp - self-contained collection)
                Box(modifier = Modifier.padding(horizontal = if (isLandscape) 6.dp else 8.dp, vertical = 2.dp)) {
                    LiveMetricsBar(
                        viewModel = viewModel,
                        onSyncClick = { showSyncDialog = true }
                    )
                }

                if (isLandscape) {
                    // --- LANDSCAPE: Unified Slim Header Bar (Tabs on Left, Recording Status & Button on Right) ---
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Slim Tab Switcher
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    val isSelected = pagerState.currentPage == index
                                    Surface(
                                        selected = isSelected,
                                        onClick = {
                                            coroutineScope.launch { pagerState.scrollToPage(index) }
                                        },
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
                                            Icon(imageVector = tab.icon, contentDescription = tab.title, modifier = Modifier.size(15.dp))
                                            Text(text = tab.title, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }

                            // Right: Recording Status Pill & Action Button (Isolated self-contained composable)
                            LandscapeSessionControlBar(viewModel = viewModel)
                        }
                    }
                } else {
                    // --- PORTRAIT: Standard TopSessionHeader & TabRow with vector icons ---
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        TopSessionHeader(
                            viewModel = viewModel,
                            onStartSession = { viewModel.startSessionRecording() },
                            onStopSession = { viewModel.stopSessionRecording() }
                        )
                    }

                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.scrollToPage(index)
                                    }
                                },
                                icon = { Icon(imageVector = tab.icon, contentDescription = tab.title, modifier = Modifier.size(18.dp)) },
                                text = {
                                    Text(
                                        text = tab.title,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                // Deck of Dedicated Sensor Dashboards (Swipe disabled: navigation strictly via top tab icons)
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    beyondViewportPageCount = 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    // Strict active-page isolation: inactive tabs are NEVER composed in the background
                    if (page == pagerState.currentPage) {
                        when (tabs[page]) {
                            CockpitTab.RADAR -> {
                                RadarDashboardCard(
                                    viewModel = viewModel
                                )
                            }
                            CockpitTab.GNSS -> {
                                GnssDashboardCard(
                                    viewModel = viewModel
                                )
                            }
                            CockpitTab.IMU -> {
                                ImuDashboardCard(
                                    viewModel = viewModel
                                )
                            }
                            CockpitTab.CAMERA -> {
                                CameraDashboardCard(
                                    viewModel = viewModel,
                                    isCurrentTab = true
                                )
                            }
                            CockpitTab.CANEDGE -> {
                                CanedgeDashboardCard(
                                    viewModel = viewModel
                                )
                            }
                            CockpitTab.SBS -> {
                                SbsDashboardCard(
                                    viewModel = viewModel,
                                    isCurrentTab = true
                                )
                            }
                            CockpitTab.SESSION -> {
                                SessionDeckCard(
                                    viewModel = viewModel
                                )
                            }
                        }
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
    }
}

/**
 * Isolated recording control and status pill for landscape cockpit mode.
 * Collects sessionRecordingState internally to keep the outer cockpit screen completely static.
 */
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
            shape = RoundedCornerShape(4.dp)
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
