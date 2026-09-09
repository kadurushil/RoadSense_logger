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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.recording.RecordingState
import com.bajajauto.roadsense.recording.SessionRecordingState
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.bajajauto.roadsense.gnss.GnssState
import com.bajajauto.roadsense.ui.components.RadarBevPlot
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
    val isHexPreviewEnabled by viewModel.isHexPreviewEnabled.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val totalPackets by viewModel.totalPackets.collectAsState()
    val latestPacket by viewModel.latestPacket.collectAsState()
    val latestFrame by viewModel.latestFrame.collectAsState()

    var showBevPlot by remember { mutableStateOf(true) }

    val mainScrollState = rememberScrollState()
    val hexScrollState = rememberScrollState()

    val sessionRecordingState by viewModel.sessionRecordingState.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val latestGnssFix by viewModel.latestGnssFix.collectAsState()
    val totalGnssFixes by viewModel.totalGnssFixes.collectAsState()
    val isGnssRecording by viewModel.isGnssRecording.collectAsState()
    val gnssSessionFixes by viewModel.gnssSessionFixes.collectAsState()

    var hasLocationPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            viewModel.startGnssUpdates()
        }
    }

    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var copyNotice by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(mainScrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RoadSense - Multi-Sensor Acquisition",
            style = MaterialTheme.typography.titleLarge
        )

        // INDEPENDENT GNSS (GPS) CONTROL & TELEMETRY CARD
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (gnssState) {
                    is GnssState.Active -> MaterialTheme.colorScheme.primaryContainer
                    is GnssState.Searching -> MaterialTheme.colorScheme.tertiaryContainer
                    is GnssState.Error -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "GNSS / GPS Navigation",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when (val s = gnssState) {
                                is GnssState.Disabled -> "Status: Disabled (Offline)"
                                is GnssState.Searching -> "Status: Acquiring GPS Satellites..."
                                is GnssState.Active -> "Status: Active Fix (${s.totalFixes} logged)"
                                is GnssState.Error -> "Status: ${s.message}"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Switch(
                        checked = gnssState !is GnssState.Disabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (!hasLocationPermission) {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    viewModel.startGnssUpdates()
                                }
                            } else {
                                viewModel.stopGnssUpdates()
                            }
                        }
                    )
                }

                latestGnssFix?.let { fix ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lat: %.6f°".format(java.util.Locale.US, fix.latitude),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Lon: %.6f°".format(java.util.Locale.US, fix.longitude),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Speed: %.1f km/h".format(java.util.Locale.US, fix.speedKmh),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Alt: %.1f m".format(java.util.Locale.US, fix.altitudeMeters),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Acc: ±%.1f m".format(java.util.Locale.US, fix.accuracyMeters),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Sats: ${fix.satellitesUsed}/${fix.satellitesInView}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Course: %.0f°".format(java.util.Locale.US, fix.bearingDegrees),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Radar Acquisition Status Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Radar Status: ${
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

        // STRUCTURED SESSION RECORDER CARD
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (sessionRecordingState) {
                    is SessionRecordingState.Recording -> MaterialTheme.colorScheme.errorContainer
                    is SessionRecordingState.Finished -> MaterialTheme.colorScheme.secondaryContainer
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
                    text = "RoadSense Session Recorder",
                    style = MaterialTheme.typography.titleMedium
                )

                when (val state = sessionRecordingState) {
                    is SessionRecordingState.Idle -> {
                        Text(
                            text = "Status: Idle (No active session)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Records timestamped binary radar frames (.bin), raw stream, and metadata into a synchronized session directory.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is SessionRecordingState.Recording -> {
                        Text(
                            text = "● RECORDING SESSION ACTIVE",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Session: ${state.sessionInfo.sessionId}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Radar: ${state.framesRecorded} frames (${state.bytesRecorded / 1024} KB) | GNSS: $gnssSessionFixes fixes | Time: ${state.durationMs / 1000}s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Directory: sessions/${state.sessionInfo.sessionId}/",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    is SessionRecordingState.Finished -> {
                        Text(
                            text = "Session Complete!",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Saved: ${state.totalFrames} radar frames | ${state.totalGnssFixes} GNSS fixes in ${state.durationMs / 1000}s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Session ID: ${state.sessionInfo.sessionId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )

                        val adbCmd = "adb pull \"${state.sessionInfo.sessionDir.absolutePath}\" ."
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
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Inspect: python tools/inspect_session.py ${state.sessionInfo.sessionId}",
                                    color = Color.Cyan,
                                    fontSize = 10.sp,
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
                    is SessionRecordingState.Error -> {
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
                            viewModel.startSessionRecording()
                        },
                        enabled = sessionRecordingState !is SessionRecordingState.Recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (sessionRecordingState is SessionRecordingState.Finished) "New Session" else "Start Session")
                    }

                    OutlinedButton(
                        onClick = { viewModel.stopSessionRecording() },
                        enabled = sessionRecordingState is SessionRecordingState.Recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Session")
                    }
                }
            }
        }

        // Real-Time Bird's-Eye View (BEV) Radar Plot
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
                            text = "Real-Time BEV Radar Plot",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (showBevPlot) "Live 2D Cartesian radar view" else "Plot paused/hidden to minimize UI rendering",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    TextButton(onClick = { showBevPlot = !showBevPlot }) {
                        Text(if (showBevPlot) "Hide Plot" else "Show Plot")
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
                        Text(text = "Active Tracks: ${frame.tracks.size}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Clusters: ${frame.clusters.size}", style = MaterialTheme.typography.bodyMedium)
                    }

                    // Preview detected points
                    if (frame.points.isNotEmpty()) {
                        Text(text = "Sample Points:", style = MaterialTheme.typography.labelSmall)
                        frame.points.take(3).forEachIndexed { idx, pt ->
                            Text(
                                text = "  P$idx: X=${"%.2f".format(pt.x)}m, Y=${"%.2f".format(pt.y)}m, V=${"%.2f".format(pt.doppler)}m/s, SNR=${"%.1f".format(pt.snrDb)}dB",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(text = "No reflections detected in this frame", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }

                    // Preview active tracks (filtered from hardware tracker table)
                    if (frame.tracks.isNotEmpty()) {
                        Text(text = "Active Tracks:", style = MaterialTheme.typography.labelSmall)
                        frame.tracks.forEach { trk ->
                            Text(
                                text = "  TID ${trk.tid}: X=${"%.2f".format(trk.x)}m, Y=${"%.2f".format(trk.y)}m, Vx=${"%.1f".format(trk.vx)}, Vy=${"%.1f".format(trk.vy)}m/s",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(text = "No active targets tracked (all 30 tracker slots empty)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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

        // Collapsible Hex Preview Box (Muted by default to conserve CPU)
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
                        Text(
                            text = "Raw UART Hex Preview",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isHexPreviewEnabled) "Live 4 Hz hex sample (Active)" else "Muted to conserve CPU & battery",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    TextButton(onClick = { viewModel.setHexPreviewEnabled(!isHexPreviewEnabled) }) {
                        Text(if (isHexPreviewEnabled) "Hide / Mute" else "Show Hex")
                    }
                }

                if (isHexPreviewEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(Color.Black)
                            .padding(8.dp)
                            .verticalScroll(hexScrollState)
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
        }
    }
}
