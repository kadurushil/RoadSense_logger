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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bajajauto.roadsense.recording.SessionSyncAuditor
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
    liveCameraFrames: Long = 0L,
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
                            text = "Camera Frames: $liveCameraFrames frames logged (H.264 MP4)",
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
                            text = "Saved ${sessionState.totalFrames} radar frames, ${sessionState.totalGnssFixes} GNSS fixes, and ${sessionState.totalCameraFrames} camera frames in ${sessionState.durationMs / 1000}s",
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

        // Session Sync & Sensor Health Scorecard
        if (sessionState is SessionRecordingState.Finished) {
            val audit = sessionState.sessionInfo.syncAuditResult ?: viewModel.latestSessionAudit.collectAsState().value

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(1.dp, Color(0xFF30363D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Session Health & Sync Scorecard",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF0F6FC)
                        )
                        val grade = audit?.certificationGrade ?: "AUDITED"
                        val (badgeColor, badgeBg) = when (grade) {
                            "GOLD" -> Pair(Color(0xFF3FB950), Color(0xFF0D3319))
                            "SILVER" -> Pair(Color(0xFF58A6FF), Color(0xFF0C2D48))
                            "WARN" -> Pair(Color(0xFFD29922), Color(0xFF382800))
                            else -> Pair(Color(0xFF8B949E), Color(0xFF21262D))
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = badgeBg,
                            border = BorderStroke(1.dp, badgeColor)
                        ) {
                            Text(
                                text = "GRADE: $grade",
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (audit != null) {
                        Surface(
                            color = Color(0xFF0D1117),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = audit.summaryNote,
                                color = Color(0xFFC9D1D9),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        // Stream Continuity Grid
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Stream Rates & Frame Continuity",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8B949E)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "• Camera: ${"%.1f".format(audit.cameraStats.effectiveRateHz)} FPS (${audit.cameraStats.totalCount} frames, ${audit.cameraStats.anomalyCount} drops)",
                                    fontSize = 11.sp,
                                    color = when {
                                        audit.cameraStats.anomalyCount == 0 -> Color(0xFF3FB950) // Green
                                        audit.cameraStats.anomalyCount <= 1 -> Color(0xFF69F0AE) // Soft Green / Nominal startup pause
                                        audit.cameraStats.anomalyCount <= 3 -> Color(0xFFFFB300) // Amber / Moderate drops
                                        else -> Color(0xFFFF5252)                                // Red / Critical drops
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "• IMU: ${"%.1f".format(audit.imuStats.effectiveRateHz)} Hz (${audit.imuStats.totalCount} samples, ${audit.imuStats.anomalyCount} jitter spikes)",
                                    fontSize = 11.sp,
                                    color = if (audit.imuStats.anomalyCount == 0) Color(0xFF3FB950) else Color(0xFFFFB300)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val radText = if (audit.radarStats != null) {
                                    "• Radar: ${"%.1f".format(audit.radarStats.effectiveRateHz)} Hz (${audit.radarStats.totalCount} frames)"
                                } else {
                                    "• Radar: Disconnected (Excluded from sync check)"
                                }
                                Text(
                                    text = radText,
                                    fontSize = 11.sp,
                                    color = if (audit.radarStats != null) Color(0xFF3FB950) else Color(0xFF8B949E)
                                )
                            }
                        }

                        // Cross-Sensor Temporal Proximity Grid
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Cross-Sensor Temporal Proximity (Δt)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8B949E)
                            )
                            Text(
                                text = "• Camera ↔ IMU Mean: ${"%.2f".format(audit.cameraImuProximity.meanDeltaMs)} ms (p50: ${"%.2f".format(audit.cameraImuProximity.p50DeltaMs)}ms, max: ${"%.2f".format(audit.cameraImuProximity.peakMaxDeltaMs)}ms, ${"%.1f".format(audit.cameraImuProximity.pctCompliant)}% <= 5ms)",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF58A6FF)
                            )
                            if (audit.radarCameraProximity != null) {
                                Text(
                                    text = "• Radar ↔ Camera Mean: ${"%.2f".format(audit.radarCameraProximity.meanDeltaMs)} ms (p50: ${"%.2f".format(audit.radarCameraProximity.p50DeltaMs)}ms, ${"%.1f".format(audit.radarCameraProximity.pctCompliant)}% <= 16.7ms)",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF58A6FF)
                                )
                            }
                        }

                        Text(
                            text = "Indexed table saved to sync/sync_keyframes.csv",
                            fontSize = 10.sp,
                            color = Color(0xFF6E7681)
                        )
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
