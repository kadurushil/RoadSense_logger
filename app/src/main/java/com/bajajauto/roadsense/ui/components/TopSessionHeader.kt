package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.RadarViewModel

private enum class SessionHeaderPhase {
    IDLE,
    RECORDING,
    FINISHED,
    ERROR
}

private fun SessionRecordingState.toPhase(): SessionHeaderPhase = when (this) {
    is SessionRecordingState.Recording -> SessionHeaderPhase.RECORDING
    is SessionRecordingState.Finished -> SessionHeaderPhase.FINISHED
    is SessionRecordingState.Error -> SessionHeaderPhase.ERROR
    else -> SessionHeaderPhase.IDLE
}

/**
 * Pinned cockpit session bar providing global recording control, duration timer,
 * and multi-sensor frame counters visible across all swipeable dashboard views.
 *
 * Isolates high-frequency 30 Hz camera / 1-10 Hz GNSS / 1 Hz timer updates inside leaf Text
 * composables so the outer Card and action buttons only recompose on coarse phase transitions.
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
    val sessionStateHolder: State<SessionRecordingState>? = viewModel?.sessionRecordingState?.collectAsState()
    val gnssFixesHolder: State<Long>? = viewModel?.gnssSessionFixes?.collectAsState()
    val cameraFramesHolder: State<Long>? = viewModel?.cameraSessionFrames?.collectAsState()

    val headerPhase by remember(sessionState, sessionStateHolder) {
        derivedStateOf {
            (sessionState ?: sessionStateHolder?.value ?: SessionRecordingState.Idle).toPhase()
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (headerPhase) {
                SessionHeaderPhase.RECORDING -> MaterialTheme.colorScheme.errorContainer
                SessionHeaderPhase.FINISHED -> MaterialTheme.colorScheme.secondaryContainer
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
                            text = when (headerPhase) {
                                SessionHeaderPhase.RECORDING -> "● RECORDING"
                                SessionHeaderPhase.FINISHED -> "✓ SAVED"
                                else -> "IDLE"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = when (headerPhase) {
                                SessionHeaderPhase.RECORDING -> MaterialTheme.colorScheme.error
                                SessionHeaderPhase.FINISHED -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (headerPhase == SessionHeaderPhase.RECORDING) {
                            SessionTimerText(
                                sessionStateProvider = { sessionState ?: sessionStateHolder?.value ?: SessionRecordingState.Idle }
                            )
                        }
                    }

                    SessionTelemetrySummaryText(
                        sessionStateProvider = { sessionState ?: sessionStateHolder?.value ?: SessionRecordingState.Idle },
                        gnssFixesProvider = { gnssFixesHolder?.value ?: liveGnssFixes },
                        cameraFramesProvider = { cameraFramesHolder?.value ?: liveCameraFrames }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (headerPhase == SessionHeaderPhase.RECORDING) {
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
                            Text(if (headerPhase == SessionHeaderPhase.FINISHED) "New" else "Record")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTimerText(
    sessionStateProvider: () -> SessionRecordingState
) {
    val state = sessionStateProvider()
    if (state is SessionRecordingState.Recording) {
        val durationSec = state.durationMs / 1000
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

@Composable
private fun SessionTelemetrySummaryText(
    sessionStateProvider: () -> SessionRecordingState,
    gnssFixesProvider: () -> Long,
    cameraFramesProvider: () -> Long
) {
    val state = sessionStateProvider()
    val text = when (state) {
        is SessionRecordingState.Recording -> {
            val gnss = gnssFixesProvider()
            val cam = cameraFramesProvider()
            "Radar: ${state.framesRecorded} | GPS: $gnss | Cam: $cam"
        }
        is SessionRecordingState.Finished -> {
            "Saved: ${state.totalFrames} radar | ${state.totalGnssFixes} GPS | ${state.totalCameraFrames} Cam"
        }
        is SessionRecordingState.Error -> "Error: ${state.message}"
        else -> "Multi-sensor synchronized capture"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
}
