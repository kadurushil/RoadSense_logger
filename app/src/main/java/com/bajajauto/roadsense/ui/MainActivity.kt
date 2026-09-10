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
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.components.LiveMetricsBar
import com.bajajauto.roadsense.ui.components.TopSessionHeader
import com.bajajauto.roadsense.ui.screens.CameraDashboardCard
import com.bajajauto.roadsense.ui.screens.CanedgeDashboardCard
import com.bajajauto.roadsense.ui.screens.GnssDashboardCard
import com.bajajauto.roadsense.ui.screens.RadarDashboardCard
import com.bajajauto.roadsense.ui.screens.SessionDeckCard
import com.bajajauto.roadsense.ui.theme.RoadSenseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

enum class CockpitTab(val title: String, val icon: ImageVector) {
    RADAR("Radar", Icons.Default.Sensors),
    GNSS("GNSS", Icons.Default.MyLocation),
    CAMERA("Camera", Icons.Default.Videocam),
    CANEDGE("CANedge", Icons.Default.DirectionsCar),
    SESSION("Storage", Icons.Default.Folder)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoadSenseCockpitScreen(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    // Sensor State Collection from ViewModel
    val connectionState by viewModel.connectionState.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val totalPackets by viewModel.totalPackets.collectAsState()
    val latestPacket by viewModel.latestPacket.collectAsState()
    val latestFrame by viewModel.latestFrame.collectAsState()
    val rawHexData by viewModel.rawHexData.collectAsState()
    val isHexPreviewEnabled by viewModel.isHexPreviewEnabled.collectAsState()

    val sessionRecordingState by viewModel.sessionRecordingState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val latestGnssFix by viewModel.latestGnssFix.collectAsState()
    val totalGnssFixes by viewModel.totalGnssFixes.collectAsState()
    val liveGnssSessionFixes by viewModel.gnssSessionFixes.collectAsState()
    val liveCameraSessionFrames by viewModel.cameraSessionFrames.collectAsState()

    val radarHz by viewModel.radarHz.collectAsState()
    val cameraFps by viewModel.cameraFps.collectAsState()
    val gnssHz by viewModel.gnssHz.collectAsState()
    val batteryPct by viewModel.batteryPct.collectAsState()
    val batteryTempC by viewModel.batteryTempC.collectAsState()
    val cpuUsagePct by viewModel.cpuUsagePct.collectAsState()
    val cpuTempC by viewModel.cpuTempC.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val tabs = CockpitTab.values()
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Breadcrumb: Record tab navigation
    LaunchedEffect(pagerState.currentPage) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Swiped to dashboard tab: ${tabs[pagerState.currentPage].title}")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = if (isLandscape) 1.dp else 4.dp)
    ) {
        // High-Density Live Telemetry Ticker (Sensors Hz, GNSS Lock, Battery, CPU & Temp)
        Box(modifier = Modifier.padding(horizontal = if (isLandscape) 6.dp else 8.dp, vertical = 2.dp)) {
            LiveMetricsBar(
                radarHz = radarHz,
                cameraFps = cameraFps,
                gnssHz = gnssHz,
                latestFix = latestGnssFix,
                batteryPct = batteryPct,
                batteryTempC = batteryTempC,
                cpuUsagePct = cpuUsagePct,
                cpuTempC = cpuTempC
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
                                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
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

                    // Right: Recording Status Pill & Action Button
                    val currentSessionState = sessionRecordingState
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = when (currentSessionState) {
                                is SessionRecordingState.Recording -> MaterialTheme.colorScheme.errorContainer
                                is SessionRecordingState.Finished -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            val statusText = when (currentSessionState) {
                                is SessionRecordingState.Recording -> {
                                    val durationSec = currentSessionState.durationMs / 1000
                                    "● REC %02d:%02d (%df)".format(durationSec / 60, durationSec % 60, currentSessionState.framesRecorded)
                                }
                                is SessionRecordingState.Finished -> "✓ SAVED (${currentSessionState.totalFrames}f)"
                                else -> "IDLE"
                            }
                            Text(
                                text = statusText,
                                color = when (currentSessionState) {
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

                        if (currentSessionState is SessionRecordingState.Recording) {
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
                                Text(if (currentSessionState is SessionRecordingState.Finished) "New" else "Record", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            // --- PORTRAIT: Standard TopSessionHeader & TabRow with vector icons ---
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TopSessionHeader(
                    sessionState = sessionRecordingState,
                    liveGnssFixes = liveGnssSessionFixes,
                    liveCameraFrames = liveCameraSessionFrames,
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
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.title, modifier = Modifier.size(18.dp)) },
                        text = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        // Swipeable Horizontal Deck of Dedicated Sensor Dashboards
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (tabs[page]) {
                CockpitTab.RADAR -> {
                    RadarDashboardCard(
                        viewModel = viewModel,
                        connectionState = connectionState,
                        totalBytes = totalBytes,
                        totalPackets = totalPackets,
                        latestPacket = latestPacket,
                        latestFrame = latestFrame,
                        rawHexData = rawHexData,
                        isHexPreviewEnabled = isHexPreviewEnabled
                    )
                }
                CockpitTab.GNSS -> {
                    GnssDashboardCard(
                        viewModel = viewModel,
                        gnssState = gnssState,
                        latestFix = latestGnssFix,
                        totalFixes = totalGnssFixes
                    )
                }
                CockpitTab.CAMERA -> {
                    CameraDashboardCard(
                        viewModel = viewModel,
                        isCurrentTab = pagerState.currentPage == page
                    )
                }
                CockpitTab.CANEDGE -> {
                    CanedgeDashboardCard(
                        viewModel = viewModel
                    )
                }
                CockpitTab.SESSION -> {
                    SessionDeckCard(
                        viewModel = viewModel,
                        sessionState = sessionRecordingState,
                        liveGnssFixes = liveGnssSessionFixes,
                        liveCameraFrames = liveCameraSessionFrames
                    )
                }
            }
        }
    }
}
