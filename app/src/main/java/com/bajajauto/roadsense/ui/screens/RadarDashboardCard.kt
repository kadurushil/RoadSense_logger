package com.bajajauto.roadsense.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.RadarBevPlot

/**
 * Radar Cockpit Screen:
 * - Dual Orientation Layout:
 *   - Landscape: Side-by-side layout with the Bird's-Eye View (BEV) radar plot fitted to screen
 *     on the left pane (no vertical scrolling required), and streamlined diagnostics/controls
 *     on the right pane.
 *   - Portrait: Clean vertical card deck with full-width radar plot and diagnostics.
 * - Displays 2D Cartesian BEV plot with dynamic range scaling (15m, 30m, 60m, 130m).
 * - Streamlined Hardware Status with single-line TLV, Points, Tracks, Clusters, and Sequence metrics.
 * - Hardware connection controls. (Target HUD removed to eliminate Main-thread layout stalls;
 *   Hex stream preview container hidden per user workflow).
 */
@Composable
fun RadarDashboardCard(
    viewModel: RadarViewModel,
    connectionState: RadarConnectionState? = null,
    totalBytes: Long = 0L,
    totalPackets: Long = 0L,
    latestPacket: RawRadarPacket? = null,
    latestFrame: RadarFrame? = null,
    rawHexData: String = "",
    isHexPreviewEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val effectiveConnectionState = connectionState ?: viewModel.connectionState.collectAsState().value
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val recordingStateHolder = viewModel.sessionRecordingState.collectAsState()
    val isRecording by remember {
        derivedStateOf { recordingStateHolder.value is SessionRecordingState.Recording }
    }

    val radarFrameState = viewModel.latestFrame.collectAsState()

    if (isLandscape) {
        // --- LANDSCAPE MODE: Side-by-Side Dual Pane ---
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left Pane (55% width): Dedicated BEV Radar Scope fitted to screen height (Decoupled Canvas Draw Phase)
            RadarBevPlot(
                viewModel = viewModel,
                frameProducer = { radarFrameState.value },
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight(),
                isLandscape = true
            )

            // Right Pane (45% width): Flat Column (eliminates SubcomposeLayout overhead on tab switch)
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadarHardwareStatusCard(
                    viewModel = viewModel,
                    connectionState = effectiveConnectionState,
                    latestFrameOverride = latestFrame,
                    isRecording = isRecording
                )
                RadarConnectionButtons(viewModel, effectiveConnectionState)
            }
        }
    } else {
        // --- PORTRAIT MODE: Flat Scrollable Deck ---
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadarHardwareStatusCard(
                viewModel = viewModel,
                connectionState = effectiveConnectionState,
                latestFrameOverride = latestFrame,
                isRecording = isRecording
            )
            RadarBevPlot(
                viewModel = viewModel,
                frameProducer = { radarFrameState.value },
                modifier = Modifier.fillMaxWidth(),
                isLandscape = false
            )
            RadarConnectionButtons(viewModel, effectiveConnectionState)
        }
    }
}

/**
 * Radar hardware connection status, port info, and streamlined telemetry summary.
 * Isolates 5 Hz telemetry subscription inside leaf composable so root RadarDashboardCard never recomposes.
 */
@Composable
private fun RadarHardwareStatusCard(
    viewModel: RadarViewModel,
    connectionState: RadarConnectionState,
    latestFrameOverride: RadarFrame?,
    isRecording: Boolean
) {
    val telemetry by viewModel.radarUiTelemetry.collectAsState()
    var sampledFrame by remember { mutableStateOf(viewModel.latestFrame.value) }
    LaunchedEffect(telemetry, isRecording) {
        if (!isRecording) {
            sampledFrame = viewModel.latestFrame.value
        }
    }
    val latestFrame = latestFrameOverride ?: sampledFrame

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                is RadarConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                is RadarConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TI AWR1843BOOST",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = when (connectionState) {
                        is RadarConnectionState.Connected -> Color(0xFF00E676)
                        is RadarConnectionState.Connecting -> Color(0xFFFFB300)
                        is RadarConnectionState.Error -> Color(0xFFFF5252)
                        else -> Color(0xFF78909C)
                    },
                    shape = RoundedCornerShape(2.dp)
                ) {
                    Text(
                        text = when (connectionState) {
                            is RadarConnectionState.Connected -> "ONLINE (3.125M)"
                            is RadarConnectionState.Connecting -> "CONNECTING"
                            is RadarConnectionState.Error -> "ERROR"
                            else -> "OFFLINE"
                        },
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (connectionState) {
                    is RadarConnectionState.Disconnected -> "Hardware disconnected (UART / CP2105)"
                    is RadarConnectionState.Connecting -> "Handshaking with mmWave radar..."
                    is RadarConnectionState.Connected -> connectionState.portInfo
                    is RadarConnectionState.Error -> "Error: ${connectionState.message}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Streamlined single-line telemetry chip (TLV version + Points / Tracks / Clusters / Frame Sequence)
            val tlvVersion = latestFrame?.tlvVersion ?: "v2.2"
            val frameSeq = latestFrame?.header?.frameNumber ?: 0L
            val ptsCount = latestFrame?.points?.size ?: 0
            val trkCount = latestFrame?.tracks?.size ?: 0
            val clsCount = latestFrame?.clusters?.size ?: 0

            Surface(
                color = Color(0xFF11141C),
                shape = RoundedCornerShape(2.dp),
                border = BorderStroke(1.dp, Color(0xFF263238)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {}
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TLV $tlvVersion",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (isRecording) {
                            "● REC [Seq #$frameSeq | Pts: $ptsCount | Trk: $trkCount]"
                        } else {
                            "Seq #$frameSeq | ● $ptsCount | ■ $trkCount | ⚡ $clsCount"
                        },
                        color = if (isRecording) Color(0xFFFF5252) else Color(0xFF80D8FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Radar Connect / Disconnect action buttons.
 */
@Composable
private fun RadarConnectionButtons(
    viewModel: RadarViewModel,
    connectionState: RadarConnectionState
) {
    val isConnected = connectionState is RadarConnectionState.Connected
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { viewModel.scanAndConnect() },
            modifier = Modifier.weight(1f),
            enabled = !isConnected
        ) {
            Text("Connect Radar")
        }
        OutlinedButton(
            onClick = { viewModel.disconnect() },
            modifier = Modifier.weight(1f),
            enabled = isConnected
        ) {
            Text("Disconnect")
        }
    }
}

/**
 * Quick radar command shortcuts (sensorStop, version).
 */
@Composable
private fun RadarQuickCommands(
    viewModel: RadarViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { viewModel.sendConfig("sensorStop") },
            modifier = Modifier.weight(1f)
        ) {
            Text("sensorStop")
        }
        OutlinedButton(
            onClick = { viewModel.sendConfig("version") },
            modifier = Modifier.weight(1f)
        ) {
            Text("version")
        }
    }
}

/**
 * Collapsible raw UART hex stream preview window.
 */
@Composable
private fun RadarHexPreviewCard(
    rawHexData: String,
    isHexPreviewEnabled: Boolean,
    viewModel: RadarViewModel,
    hexScrollState: androidx.compose.foundation.ScrollState
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Raw UART Stream", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (isHexPreviewEnabled) "Live sample active" else "Muted to conserve CPU",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                TextButton(onClick = { viewModel.setHexPreviewEnabled(!isHexPreviewEnabled) }) {
                    Text(if (isHexPreviewEnabled) "Hide" else "Show Hex")
                }
            }

            if (isHexPreviewEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(Color.Black)
                        .padding(8.dp)
                        .verticalScroll(hexScrollState)
                ) {
                    Text(
                        text = rawHexData,
                        color = Color.Green,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
