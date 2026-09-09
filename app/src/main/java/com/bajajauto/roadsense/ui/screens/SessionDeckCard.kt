package com.bajajauto.roadsense.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.recording.SessionInfo
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.RadarViewModel

/**
 * Session & Storage Dashboard:
 * Overview of recorded multi-sensor sessions, stream directory trees,
 * one-tap ADB commands, and live file size gauges.
 */
@Composable
fun SessionDeckCard(
    viewModel: RadarViewModel,
    sessionState: SessionRecordingState,
    liveGnssFixes: Long,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    var copyNotice by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Session Status & Control Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (sessionState) {
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
                    text = "Multi-Sensor Storage Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                when (sessionState) {
                    is SessionRecordingState.Idle -> {
                        Text(
                            text = "Status: Idle (Ready to record)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Synchronized streams (Radar, GNSS, Camera) will be written with hardware monotonic timestamps into an isolated session directory.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    is SessionRecordingState.Recording -> {
                        Text(
                            text = "● RECORDING SESSION IN PROGRESS",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Session ID: ${sessionState.sessionInfo.sessionId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Radar Frames: ${sessionState.framesRecorded} (${sessionState.bytesRecorded / 1024} KB)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "GNSS Fixes: $liveGnssFixes fixes logged",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Duration: ${sessionState.durationMs / 1000}s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is SessionRecordingState.Finished -> {
                        Text(
                            text = "✓ Session Successfully Closed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Saved ${sessionState.totalFrames} radar frames and ${sessionState.totalGnssFixes} GNSS fixes in ${sessionState.durationMs / 1000}s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Session ID: ${sessionState.sessionInfo.sessionId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    is SessionRecordingState.Error -> {
                        Text(
                            text = "Recording Error: ${sessionState.message}",
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
                        enabled = sessionState !is SessionRecordingState.Recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (sessionState is SessionRecordingState.Finished) "New Session" else "Start Session")
                    }

                    OutlinedButton(
                        onClick = { viewModel.stopSessionRecording() },
                        enabled = sessionState is SessionRecordingState.Recording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Session")
                    }
                }
            }
        }

        // ADB Pull & CLI Tools Quick-Action Card
        if (sessionState is SessionRecordingState.Finished) {
            val sessionDir = sessionState.sessionInfo.sessionDir.absolutePath
            val adbCmd = "adb pull \"$sessionDir\" ."
            val inspectCmd = "python tools/inspect_session.py ${sessionState.sessionInfo.sessionId}"

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Download & Inspect Commands", style = MaterialTheme.typography.titleSmall)

                    Surface(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(text = adbCmd, color = Color.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = inspectCmd, color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(adbCmd))
                            copyNotice = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (copyNotice) "Copied ADB Command!" else "Copy ADB Pull Command")
                    }
                }
            }
        }

        // Active Stream Architecture Map
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "Multi-Sensor Storage Structure", style = MaterialTheme.typography.titleSmall)
                Text(text = "• radar/radar_frames.bin (24-byte ROAD sync header)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(text = "• radar/radar_raw_stream.bin (Uninterrupted UART)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(text = "• gnss/gnss_fixes.csv (WGS84, speed, accuracy, sats)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(text = "• session_timeline.csv (Master microsecond alignment)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(text = "• session_metadata.json (Hardware & run descriptor)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
