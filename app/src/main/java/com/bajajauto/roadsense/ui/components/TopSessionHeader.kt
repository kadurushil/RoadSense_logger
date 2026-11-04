package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.RadarViewModel

/**
 * Pinned cockpit session bar providing global recording control, duration timer,
 * and multi-sensor frame counters visible across all swipeable dashboard views.
 */
@Composable
fun TopSessionHeader(
    viewModel: RadarViewModel? = null,
    sessionState: SessionRecordingState? = null,
    liveGnssFixes: Long = 0L,
    liveCameraFrames: Long = 0L,
    onStartSession: () -> Unit = {},
    onStopSession: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val effectiveSessionState = sessionState ?: viewModel?.sessionRecordingState?.collectAsState()?.value ?: SessionRecordingState.Idle
    val actualGnssFixes = if (viewModel != null) viewModel.gnssSessionFixes.collectAsState().value else liveGnssFixes
    val actualCameraFrames = if (viewModel != null) viewModel.cameraSessionFrames.collectAsState().value else liveCameraFrames

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (effectiveSessionState) {
                is SessionRecordingState.Recording -> MaterialTheme.colorScheme.errorContainer
                is SessionRecordingState.Finished -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(if (isLandscape) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 2.dp else 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when (effectiveSessionState) {
                                is SessionRecordingState.Recording -> "● RECORDING"
                                is SessionRecordingState.Finished -> "✓ SAVED"
                                else -> "IDLE"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = when (effectiveSessionState) {
                                is SessionRecordingState.Recording -> MaterialTheme.colorScheme.error
                                is SessionRecordingState.Finished -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (effectiveSessionState is SessionRecordingState.Recording) {
                            val durationSec = effectiveSessionState.durationMs / 1000
                            val mins = durationSec / 60
                            val secs = durationSec % 60
                            Text(
                                text = "%02d:%02d".format(mins, secs),
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = when (effectiveSessionState) {
                            is SessionRecordingState.Recording -> "Radar: ${effectiveSessionState.framesRecorded} | GPS: $actualGnssFixes | Cam: $actualCameraFrames"
                            is SessionRecordingState.Finished -> "Saved: ${effectiveSessionState.totalFrames} radar | ${effectiveSessionState.totalGnssFixes} GPS | ${effectiveSessionState.totalCameraFrames} Cam"
                            is SessionRecordingState.Error -> "Error: ${effectiveSessionState.message}"
                            else -> "Multi-sensor synchronized capture"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sessionState is SessionRecordingState.Recording) {
                        Button(
                            onClick = onStopSession,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Stop", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onStartSession,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (sessionState is SessionRecordingState.Finished) "New" else "Record")
                        }
                    }
                }
            }
        }
    }
}
