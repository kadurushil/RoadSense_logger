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
import com.bajajauto.roadsense.ui.components.TopSessionHeader
import com.bajajauto.roadsense.ui.screens.CameraDashboardCard
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

enum class CockpitTab(val title: String, val icon: String) {
    RADAR("Radar", "📡"),
    GNSS("GNSS / GPS", "🛰️"),
    CAMERA("Camera", "📹"),
    SESSION("Storage", "💾")
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

    val coroutineScope = rememberCoroutineScope()
    val tabs = CockpitTab.values()
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        // Pinned Global Session Bar (Visible regardless of swipe tab)
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TopSessionHeader(
                sessionState = sessionRecordingState,
                liveGnssFixes = liveGnssSessionFixes,
                onStartSession = { viewModel.startSessionRecording() },
                onStopSession = { viewModel.stopSessionRecording() }
            )
        }

        // Horizontal Sensor Tabs Bar
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
                    text = {
                        Text(
                            text = "${tab.icon} ${tab.title}",
                            fontSize = 12.sp,
                            fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
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
                    CameraDashboardCard()
                }
                CockpitTab.SESSION -> {
                    SessionDeckCard(
                        viewModel = viewModel,
                        sessionState = sessionRecordingState,
                        liveGnssFixes = liveGnssSessionFixes
                    )
                }
            }
        }
    }
}
