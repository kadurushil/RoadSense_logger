package com.bajajauto.roadsense.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.recording.RecordingState
import com.bajajauto.roadsense.ui.theme.RoadSenseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadSenseTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RadarTestScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun RadarTestScreen(viewModel: RadarViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsState()
    val rawHexData by viewModel.rawHexData.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val totalPackets by viewModel.totalPackets.collectAsState()
    val latestPacket by viewModel.latestPacket.collectAsState()
    val latestFrame by viewModel.latestFrame.collectAsState()

    val scrollState = rememberScrollState()

    val recordingState by viewModel.recordingState.collectAsState()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var copyNotice by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RoadSense - Raw Binary Acquisition",
            style = MaterialTheme.typography.titleLarge
        )

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status: ${
                        when (connectionState) {
                            is RadarConnectionState.Disconnected -> "Disconnected"
                            is RadarConnectionState.Connecting -> "Connecting..."
                            is RadarConnectionState.Connected -> (connectionState as RadarConnectionState.Connected).portInfo
                            is RadarConnectionState.Error -> "Error: ${(connectionState as RadarConnectionState.Error).message}"
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Total Bytes: $totalBytes", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Valid Packets: $totalPackets", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // RAW BINARY RECORDER CARD
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (recordingState) {
                    is RecordingState.Recording -> MaterialTheme.colorScheme.errorContainer
                    is RecordingState.Finished -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Raw UART Binary Dump (.bin)",
                    style = MaterialTheme.typography.titleMedium
                )

                when (val state = recordingState) {
                    is RecordingState.Idle -> {
                        Text(
                            text = "Status: Idle (Not recording)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Dumps 100% of raw UART bytes directly to flash storage with zero packet drop.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is RecordingState.Recording -> {
                        Text(
                            text = "RECORDING IN PROGRESS...",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Bytes Written: ${state.bytesWritten} B (${state.bytesWritten / 1024} KB)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "File: ${state.file.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    is RecordingState.Finished -> {
                        Text(
                            text = "Dump Complete!",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Saved: ${state.totalBytes} B (${state.totalBytes / 1024} KB) in ${state.durationMs / 1000}s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Path: ${state.file.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )

                        val adbCmd = "adb pull \"${state.file.absolutePath}\" ."
                        Surface(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = adbCmd,
                                    color = Color.Green,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Button(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(adbCmd))
                                copyNotice = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (copyNotice) "Copied to Clipboard!" else "Copy ADB Pull Command")
                        }
                    }
                    is RecordingState.Error -> {
                        Text(
                            text = "Recording Error: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            copyNotice = false
                            viewModel.startRawRecording()
                        },
                        enabled = recordingState !is RecordingState.Recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Raw Dump")
                    }

                    OutlinedButton(
                        onClick = { viewModel.stopRawRecording() },
                        enabled = recordingState is RecordingState.Recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Dump")
                    }
                }
            }
        }

        // Latest Frame Header Telemetry
        latestPacket?.header?.let { header ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Latest Assembled Frame Header",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Frame #: ${header.frameNumber} | Subframe: ${header.subFrameNumber}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Packet Length: ${header.totalPacketLen} bytes", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Detected Objects: ${header.numDetectedObj} | TLV Count: ${header.numTLVs}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "CPU Cycles: ${header.timeCpuCycles}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Decoded Radar Frame Telemetry (Points, Tracks, Clusters)
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
                        text = "Decoded Radar Telemetry",
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

                    // Preview first 2 points
                    if (frame.points.isNotEmpty()) {
                        Text(text = "Sample Points:", style = MaterialTheme.typography.labelSmall)
                        frame.points.take(2).forEachIndexed { idx, pt ->
                            Text(
                                text = "  P$idx: X=${"%.2f".format(pt.x)}m, Y=${"%.2f".format(pt.y)}m, V=${"%.2f".format(pt.doppler)}m/s, SNR=${"%.1f".format(pt.snrDb)}dB",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Preview active tracks
                    if (frame.tracks.isNotEmpty()) {
                        Text(text = "Sample Tracks:", style = MaterialTheme.typography.labelSmall)
                        frame.tracks.take(2).forEach { trk ->
                            Text(
                                text = "  TID ${trk.tid}: X=${"%.2f".format(trk.x)}m, Y=${"%.2f".format(trk.y)}m, Vx=${"%.1f".format(trk.vx)}, Vy=${"%.1f".format(trk.vy)}m/s",
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

        // Hex Output Box
        Text(
            text = "Incoming UART Data (Hex):",
            style = MaterialTheme.typography.labelMedium
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(8.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = rawHexData,
                color = Color.Green,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
