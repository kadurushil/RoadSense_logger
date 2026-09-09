package com.bajajauto.roadsense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.RadarBevPlot

/**
 * Radar Cockpit screen:
 * Displays real-time 2D Cartesian BEV plot, range scale buttons, target diagnostic HUD,
 * and connection controls.
 */
@Composable
fun RadarDashboardCard(
    viewModel: RadarViewModel,
    connectionState: RadarConnectionState,
    totalBytes: Long,
    totalPackets: Long,
    latestPacket: RawRadarPacket?,
    latestFrame: RadarFrame?,
    rawHexData: String,
    isHexPreviewEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val hexScrollState = rememberScrollState()
    var showBevPlot by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Radar Status & Port Info Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Radar Hardware: ${
                        when (connectionState) {
                            is RadarConnectionState.Disconnected -> "Disconnected"
                            is RadarConnectionState.Connecting -> "Connecting..."
                            is RadarConnectionState.Connected -> connectionState.portInfo
                            is RadarConnectionState.Error -> "Error: ${connectionState.message}"
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Total Bytes: ${totalBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Valid Frames: $totalPackets", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 2D Bird's-Eye View (BEV) Radar Plot
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Bird's-Eye View (BEV) Plot",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (showBevPlot) "Points (Doppler) + Tracks + Clusters" else "Plot paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    TextButton(onClick = { showBevPlot = !showBevPlot }) {
                        Text(if (showBevPlot) "Hide" else "Show")
                    }
                }

                if (showBevPlot) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RadarBevPlot(
                        frame = latestFrame,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Decoded Radar Frame Telemetry
        latestFrame?.let { frame ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Real-Time Target HUD",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Points: ${frame.points.size}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Tracks: ${frame.tracks.size}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Clusters: ${frame.clusters.size}", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (frame.points.isNotEmpty()) {
                        Text(text = "Detected Reflections:", style = MaterialTheme.typography.labelSmall)
                        frame.points.take(3).forEachIndexed { idx, pt ->
                            Text(
                                text = "  P$idx: X=${"%.2f".format(pt.x)}m, Y=${"%.2f".format(pt.y)}m, V=${"%.2f".format(pt.doppler)}m/s",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (frame.tracks.isNotEmpty()) {
                        Text(text = "Active Tracks:", style = MaterialTheme.typography.labelSmall)
                        frame.tracks.forEach { trk ->
                            Text(
                                text = "  TID ${trk.tid}: X=${"%.2f".format(trk.x)}m, Y=${"%.2f".format(trk.y)}m, Vx=${"%.1f".format(trk.vx)}m/s",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Connection Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.scanAndConnect() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect Radar")
            }
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        // Quick Command Buttons
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

        // Collapsible Hex Preview Box
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
}
