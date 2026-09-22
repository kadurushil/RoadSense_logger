package com.bajajauto.roadsense.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.RadarBevPlot

/**
 * Radar Cockpit Screen:
 * - Dual Orientation Layout:
 *   - Landscape: Side-by-side layout with the Bird's-Eye View (BEV) radar plot fitted to screen
 *     on the left pane (no vertical scrolling required), and scrollable diagnostics/telemetry/controls
 *     on the right pane.
 *   - Portrait: Clean vertical card deck with full-width radar plot and diagnostics.
 * - Displays 2D Cartesian BEV plot with dynamic range scaling (15m, 30m, 60m, 100m).
 * - Real-time Target HUD with active tracks, velocity vectors, clusters, and Doppler reflections.
 * - Hardware connection controls and raw UART hex stream preview.
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()
    val hexScrollState = rememberScrollState()

    if (isLandscape) {
        // --- LANDSCAPE MODE: Side-by-Side Dual Pane ---
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left Pane (55% width): Dedicated BEV Radar Scope fitted to screen height
            RadarBevPlot(
                viewModel = viewModel,
                frame = latestFrame,
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight(),
                isLandscape = true
            )

            // Right Pane (45% width): Scrollable Telemetry, Target HUD, Controls & Hex Dump
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadarHardwareStatusCard(connectionState, totalBytes, totalPackets, latestFrame)
                RadarConnectionButtons(viewModel, connectionState)
                RadarTargetHudCard(latestFrame)
                // RadarQuickCommands hidden from view per user request
                RadarHexPreviewCard(rawHexData, isHexPreviewEnabled, viewModel, hexScrollState)
            }
        }
    } else {
        // --- PORTRAIT MODE: Unified Vertical Deck ---
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadarHardwareStatusCard(connectionState, totalBytes, totalPackets, latestFrame)
            RadarBevPlot(
                viewModel = viewModel,
                frame = latestFrame,
                modifier = Modifier.fillMaxWidth(),
                isLandscape = false
            )
            RadarTargetHudCard(latestFrame)
            RadarConnectionButtons(viewModel, connectionState)
            // RadarQuickCommands hidden from view per user request
            RadarHexPreviewCard(rawHexData, isHexPreviewEnabled, viewModel, hexScrollState)
        }
    }
}

/**
 * Radar hardware connection status, port info, and traffic metrics.
 */
@Composable
private fun RadarHardwareStatusCard(
    connectionState: RadarConnectionState,
    totalBytes: Long,
    totalPackets: Long,
    latestFrame: RadarFrame?
) {
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
                    shape = RoundedCornerShape(4.dp)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val formattedBytes = if (totalBytes > 1024 * 1024) {
                    "${"%.1f".format(totalBytes / (1024.0 * 1024.0))} MB"
                } else {
                    "${totalBytes / 1024} KB"
                }
                Text(
                    text = "Bytes: $formattedBytes",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                val tlvVersion = latestFrame?.tlvVersion ?: "v2.2"
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = "TLV $tlvVersion",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "Frames: $totalPackets",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
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
 * Real-Time Target Telemetry HUD displaying decoded target coordinates, velocities, and clusters.
 */
@Composable
private fun RadarTargetHudCard(
    latestFrame: RadarFrame?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Target Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Frame #${latestFrame?.header?.frameNumber ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF80D8FF),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Target Count Summary Bar
            Surface(
                color = Color(0xFF11141C),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "● Points: ${latestFrame?.points?.size ?: 0}",
                        color = Color(0xFF69F0AE),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "■ Tracks: ${latestFrame?.tracks?.size ?: 0}",
                        color = Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "⚡ Clusters: ${latestFrame?.clusters?.size ?: 0}",
                        color = Color(0xFFFF8F00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Line-of-Sight Priority Targets in Vehicle Forward Corridor (x: ±10m, y: 0-100m)
            val losTracks = latestFrame?.tracks?.filter { trk ->
                trk.x in -10.0f..10.0f && trk.y in 0.0f..100.0f
            }?.sortedBy { it.y }?.take(3) ?: emptyList()

            Text(
                text = "LINE-OF-SIGHT TARGETS (±10m Corridor, 0-100m):",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )

            if (losTracks.isNotEmpty()) {
                losTracks.forEach { trk ->
                    val speedKmh = trk.vy * 3.6f
                    val isApproaching = trk.vy < -0.3f
                    val isReceding = trk.vy > 0.3f
                    val statusText = if (isApproaching) "APPROACHING" else if (isReceding) "RECEDING" else "STATIONARY"
                    val statusColor = if (isApproaching) Color(0xFFFF5252) else if (isReceding) Color(0xFF40C4FF) else Color(0xFF69F0AE)
                    val lanePosition = if (kotlin.math.abs(trk.x) <= 1.8f) "Direct Lane" else if (trk.x < 0) "Left Corridor" else "Right Corridor"

                    Surface(
                        color = Color(0xFF181D26),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (isApproaching) Color(0xFFFF5252).copy(alpha = 0.6f) else Color(0xFF37474F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "🎯 Target #${trk.tid}",
                                        color = Color(0xFFFFD54F),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Surface(
                                        color = statusColor.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(3.dp)
                                    ) {
                                        Text(
                                            text = lanePosition,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Range: ${"%.1f".format(trk.y)}m  (Lat ${"%.1f".format(trk.x)}m)",
                                    color = Color(0xFFCFD8DC),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${"%.1f".format(kotlin.math.abs(speedKmh))} km/h",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "No priority targets in forward line-of-sight corridor",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF78909C),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            val otherTracksCount = (latestFrame?.tracks?.size ?: 0) - losTracks.size
            if (otherTracksCount > 0) {
                Text(
                    text = "+ $otherTracksCount peripheral tracked target(s) outside corridor",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
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
