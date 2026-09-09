package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.recording.SessionRecordingState

/**
 * Pinned cockpit session bar providing global recording control, duration timer,
 * and multi-sensor frame counters visible across all swipeable dashboard views.
 */
@Composable
fun TopSessionHeader(
    sessionState: SessionRecordingState,
    liveGnssFixes: Long,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (sessionState) {
                is SessionRecordingState.Recording -> MaterialTheme.colorScheme.errorContainer
                is SessionRecordingState.Finished -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            text = when (sessionState) {
                                is SessionRecordingState.Recording -> "● RECORDING"
                                is SessionRecordingState.Finished -> "✓ SAVED"
                                else -> "IDLE"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = when (sessionState) {
                                is SessionRecordingState.Recording -> MaterialTheme.colorScheme.error
                                is SessionRecordingState.Finished -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (sessionState is SessionRecordingState.Recording) {
                            val durationSec = sessionState.durationMs / 1000
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
                        text = when (sessionState) {
                            is SessionRecordingState.Recording -> "Radar: ${sessionState.framesRecorded} | GPS: $liveGnssFixes"
                            is SessionRecordingState.Finished -> "Saved: ${sessionState.totalFrames} radar | ${sessionState.totalGnssFixes} GPS"
                            is SessionRecordingState.Error -> "Error: ${sessionState.message}"
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
