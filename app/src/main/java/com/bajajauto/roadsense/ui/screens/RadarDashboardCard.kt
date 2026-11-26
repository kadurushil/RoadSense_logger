package com.bajajauto.roadsense.ui.screens

import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.diagnostics.TrackRecomposition
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.recording.SessionLifecyclePhase
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.RadarBevPlot

/**
 * Radar Cockpit Screen:
 * - Dual Orientation Layout:
 *   - Landscape: Side-by-side layout with the Bird's-Eye View (BEV) radar plot fitted to screen
 *     on the left pane (no vertical scrolling required), and streamlined diagnostics/controls
 *     on the right pane.
 *   - Portrait: Clean vertical card deck with full-width radar plot and diagnostics.
 * - Displays 2D Cartesian BEV plot with dynamic range scaling (15m, 30m, 60m, 100m, 150m).
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
    TrackRecomposition("RadarCard")
    val configuration = LocalConfiguration.current
    val effectiveConnectionState = connectionState ?: viewModel.connectionState.collectAsState().value
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lifecyclePhase by viewModel.sessionLifecyclePhase.collectAsState()
    val isRecording = lifecyclePhase == SessionLifecyclePhase.RECORDING

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
                frame = latestFrame,
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
                frame = latestFrame,
                modifier = Modifier.fillMaxWidth(),
                isLandscape = false
            )
            RadarConnectionButtons(viewModel, effectiveConnectionState)
        }
    }
}

/**
 * Radar hardware connection status, port info, and streamlined telemetry summary.
 * Telemetry strip uses Direct-Canvas draw-phase rendering (`0` recompositions per 20 Hz frame).
 */
@Composable
private fun RadarHardwareStatusCard(
    viewModel: RadarViewModel,
    connectionState: RadarConnectionState,
    latestFrameOverride: RadarFrame?,
    isRecording: Boolean
) {
    TrackRecomposition("RadarHwCard")
    val telemetryState = viewModel.radarUiTelemetry.collectAsState()

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
                        is RadarConnectionState.Connecting,
                        is RadarConnectionState.Reconnecting -> Color(0xFFFFB300)
                        is RadarConnectionState.Error -> Color(0xFFFF5252)
                        else -> Color(0xFF78909C)
                    },
                    shape = RoundedCornerShape(2.dp)
                ) {
                    Text(
                        text = when (connectionState) {
                            is RadarConnectionState.Connected -> "ONLINE (3.125M)"
                            is RadarConnectionState.Connecting -> "CONNECTING"
                            is RadarConnectionState.Reconnecting -> "RECONNECTING"
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
                    is RadarConnectionState.Reconnecting -> connectionState.message
                    is RadarConnectionState.Connected -> connectionState.portInfo
                    is RadarConnectionState.Error -> "Error: ${connectionState.message}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Direct-Canvas telemetry chip (5 Hz radarUiTelemetry): 0 recompositions, 0 Measure/Layout, isolated RenderNode
            RadarTelemetryStripCanvas(
                telemetryProvider = { telemetryState.value },
                frameOverride = latestFrameOverride,
                isRecording = isRecording
            )
        }
    }
}

@Composable
private fun RadarTelemetryStripCanvas(
    telemetryProvider: () -> com.bajajauto.roadsense.ui.RadarUiTelemetry,
    frameOverride: RadarFrame?,
    isRecording: Boolean
) {
    TrackRecomposition("RadarTelemetry")
    val density = LocalDensity.current.density
    val scaledDensity = LocalDensity.current.fontScale * density
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()

    val bgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF11141C.toInt()
        }
    }
    val borderPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0xFF263238.toInt()
        }
    }
    val tlvPaint = remember(scaledDensity, primaryArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * scaledDensity
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = primaryArgb
            textAlign = Paint.Align.LEFT
        }
    }
    val metricPaint = remember(scaledDensity) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * scaledDensity
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
    }
    val rect = remember { RectF() }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clearAndSetSemantics {}
            .graphicsLayer()
    ) {
        val tel = telemetryProvider()
        val tlvVersion = frameOverride?.tlvVersion ?: tel.tlvVersion
        val frameSeq = frameOverride?.header?.frameNumber ?: tel.lastFrameSeq
        val ptsCount = frameOverride?.points?.size ?: tel.pointsCount
        val trkCount = frameOverride?.tracks?.size ?: tel.tracksCount
        val clsCount = frameOverride?.clusters?.size ?: tel.clustersCount

        val nc = drawContext.canvas.nativeCanvas
        val cornerPx = 2f * density
        rect.set(0f, 0f, size.width, size.height)
        nc.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
        val inset = borderPaint.strokeWidth / 2f
        rect.set(inset, inset, size.width - inset, size.height - inset)
        nc.drawRoundRect(rect, cornerPx, cornerPx, borderPaint)

        val padX = 8f * density
        val baseline = (size.height / 2f) - ((metricPaint.descent() + metricPaint.ascent()) / 2f)
        nc.drawText("TLV $tlvVersion", padX, baseline, tlvPaint)

        val statusText = if (isRecording) {
            "● REC [Seq #$frameSeq | Pts: $ptsCount | Trk: $trkCount]"
        } else {
            "Seq #$frameSeq | ● $ptsCount | ■ $trkCount | ⚡ $clsCount"
        }
        metricPaint.color = if (isRecording) 0xFFFF5252.toInt() else 0xFF80D8FF.toInt()
        nc.drawText(statusText, size.width - padX, baseline, metricPaint)
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
    val canDisconnect = isConnected ||
        connectionState is RadarConnectionState.Reconnecting ||
        connectionState is RadarConnectionState.Connecting
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { viewModel.scanAndConnect() },
            modifier = Modifier.weight(1f),
            enabled = !isConnected
        ) {
            Text("Connect Radar", maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = { viewModel.disconnect() },
            modifier = Modifier.weight(1f),
            enabled = canDisconnect
        ) {
            Text("Disconnect", maxLines = 1, softWrap = false)
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
