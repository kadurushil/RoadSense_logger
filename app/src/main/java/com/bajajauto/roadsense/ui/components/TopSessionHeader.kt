package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.canedge.model.CanedgeHudPhase
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.diagnostics.TrackRecomposition
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.ui.RadarViewModel
import kotlinx.coroutines.delay

internal data class CockpitStatusPillSpec(
    val text: String,
    val bgColor: Color,
    val fgColor: Color
)

@Composable
internal fun rememberTransientSavedFlag(isFinished: Boolean): Boolean {
    var showSavedRecently by remember { mutableStateOf(false) }
    LaunchedEffect(isFinished) {
        if (isFinished) {
            showSavedRecently = true
            delay(4000L)
            showSavedRecently = false
        } else {
            showSavedRecently = false
        }
    }
    return showSavedRecently
}

@Composable
internal fun rememberTransientSavedFlag(state: SessionRecordingState): Boolean =
    rememberTransientSavedFlag(state is SessionRecordingState.Finished)

internal fun resolveCockpitStatusPill(
    state: SessionRecordingState,
    isAuditing: Boolean,
    canStats: CanedgeSyncStats?,
    showSavedRecently: Boolean,
    compactRecordingText: Boolean,
    recordingContainerColor: Color,
    recordingContentColor: Color,
    savedContainerColor: Color,
    savedContentColor: Color,
    idleContainerColor: Color,
    idleContentColor: Color
): CockpitStatusPillSpec {
    return when {
        state is SessionRecordingState.Recording -> {
            val text = if (compactRecordingText) {
                val durationSec = state.durationMs / 1000
                "● REC %02d:%02d (%df)".format(durationSec / 60, durationSec % 60, state.framesRecorded)
            } else {
                "● RECORDING"
            }
            CockpitStatusPillSpec(text, recordingContainerColor, recordingContentColor)
        }
        state is SessionRecordingState.WarmingUp ->
            CockpitStatusPillSpec("WARMING…", Color(0xFF3B2605), Color(0xFFFBBF24))
        state is SessionRecordingState.Finalizing ->
            CockpitStatusPillSpec("SAVING…", Color(0xFF1E3A5F), Color(0xFF38BDF8))
        isAuditing ->
            CockpitStatusPillSpec("AUDITING…", Color(0xFF0C2D48), Color(0xFF38BDF8))
        canStats?.hudPhase == CanedgeHudPhase.SYNCING -> {
            val pct = (canStats.activeFileProgress * 100).toInt().coerceIn(0, 99)
            CockpitStatusPillSpec("CAN SYNC $pct%", Color(0xFF0C2D48), Color(0xFF38BDF8))
        }
        canStats?.hudPhase == CanedgeHudPhase.WAITING_FINAL_CHUNK ->
            CockpitStatusPillSpec("CAN WAIT…", Color(0xFF3B2605), Color(0xFFFBBF24))
        canStats?.hudPhase == CanedgeHudPhase.CALIBRATING ->
            CockpitStatusPillSpec("CAN CAL…", Color(0xFF3B2605), Color(0xFFFBBF24))
        canStats?.hudPhase == CanedgeHudPhase.SCANNING ->
            CockpitStatusPillSpec("CAN SCAN", Color(0xFF3B2605), Color(0xFFFBBF24))
        state is SessionRecordingState.Finished && showSavedRecently -> {
            val text = if (compactRecordingText) "✓ SAVED (${state.totalFrames}f)" else "✓ SAVED"
            CockpitStatusPillSpec(text, savedContainerColor, savedContentColor)
        }
        state is SessionRecordingState.Error ->
            CockpitStatusPillSpec("ERR", Color(0xFF3F1315), Color(0xFFF87171))
        else ->
            CockpitStatusPillSpec("IDLE ⓘ", idleContainerColor, idleContentColor)
    }
}

private enum class SessionHeaderPhase {
    IDLE,
    WARMING,
    RECORDING,
    FINALIZING,
    FINISHED,
    ERROR
}

private fun SessionRecordingState.toPhase(): SessionHeaderPhase = when (this) {
    is SessionRecordingState.WarmingUp -> SessionHeaderPhase.WARMING
    is SessionRecordingState.Recording -> SessionHeaderPhase.RECORDING
    is SessionRecordingState.Finalizing -> SessionHeaderPhase.FINALIZING
    is SessionRecordingState.Finished -> SessionHeaderPhase.FINISHED
    is SessionRecordingState.Error -> SessionHeaderPhase.ERROR
    else -> SessionHeaderPhase.IDLE
}

/**
 * Pinned portrait cockpit session bar providing global recording control, clickable IDLE/Preflight pill,
 * 4-way swipe Event Bookmark button, duration timer, and multi-sensor frame counters.
 */
@Composable
fun TopSessionHeader(
    viewModel: RadarViewModel? = null,
    sessionState: SessionRecordingState? = null,
    liveGnssFixes: Long = 0L,
    liveCameraFrames: Long = 0L,
    onStartSession: () -> Unit = {},
    onStopSession: () -> Unit = {},
    onOpenPreflight: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val sessionStateHolder: State<SessionRecordingState>? = viewModel?.sessionRecordingState?.collectAsState()
    val gnssFixesHolder: State<Long>? = viewModel?.gnssSessionFixes?.collectAsState()
    val cameraFramesHolder: State<Long>? = viewModel?.cameraSessionFrames?.collectAsState()
    val isAuditingHolder: State<Boolean>? = viewModel?.isSessionAuditing?.collectAsState()
    val canStatsHolder: State<CanedgeSyncStats>? = viewModel?.canedgeSyncStats?.collectAsState()
    val bookmarkCountHolder: State<Int>? = viewModel?.sessionBookmarkCount?.collectAsState()
    val hapticsEnabledHolder: State<Boolean>? = viewModel?.bookmarkHapticsEnabled?.collectAsState()

    TrackRecomposition("TopHeader")
    val headerPhase by remember(sessionStateHolder, sessionState) {
        derivedStateOf { (sessionState ?: sessionStateHolder?.value ?: SessionRecordingState.Idle).toPhase() }
    }
    val showSavedRecently = rememberTransientSavedFlag(headerPhase == SessionHeaderPhase.FINISHED)
    val canOpenPreflight = headerPhase == SessionHeaderPhase.IDLE ||
        headerPhase == SessionHeaderPhase.FINISHED ||
        headerPhase == SessionHeaderPhase.ERROR

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
            modifier = Modifier.padding(if (isLandscape) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 2.dp else 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TopSessionStatusPill(
                            stateProvider = { sessionState ?: sessionStateHolder?.value ?: SessionRecordingState.Idle },
                            isAuditingProvider = { isAuditingHolder?.value == true },
                            canStatsProvider = { canStatsHolder?.value },
                            showSavedRecently = showSavedRecently,
                            canOpenPreflight = canOpenPreflight,
                            onOpenPreflight = onOpenPreflight
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewModel != null) {
                        EventBookmarkButton(
                            isRecording = headerPhase == SessionHeaderPhase.RECORDING,
                            bookmarkCount = bookmarkCountHolder?.value ?: 0,
                            hapticsEnabled = hapticsEnabledHolder?.value ?: true,
                            style = EventBookmarkButtonStyle.COMPACT_BAR,
                            onMarkEvent = { type -> viewModel.markEvent(type) }
                        )
                    }

                    when (headerPhase) {
                        SessionHeaderPhase.RECORDING -> {
                            Button(
                                onClick = onStopSession,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Stop", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                        SessionHeaderPhase.WARMING -> {
                            OutlinedButton(
                                onClick = { viewModel?.cancelWarmup() ?: onStopSession() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Cancel", maxLines = 1, softWrap = false)
                            }
                        }
                        SessionHeaderPhase.FINALIZING -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Saving…", maxLines = 1, softWrap = false)
                            }
                        }
                        else -> {
                            Button(
                                onClick = onStartSession,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(
                                    text = if (headerPhase == SessionHeaderPhase.FINISHED) "New" else "Record",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
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
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
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
        is SessionRecordingState.WarmingUp -> state.statusText
        is SessionRecordingState.Finalizing -> "Flushing video & sensor streams…"
        is SessionRecordingState.Recording -> {
            val gnss = gnssFixesProvider()
            val cam = cameraFramesProvider()
            "Radar: ${state.framesRecorded} | GPS: $gnss | Cam: $cam"
        }
        is SessionRecordingState.Finished -> {
            "Saved: ${state.totalFrames} radar | ${state.totalGnssFixes} GPS | ${state.totalCameraFrames} Cam"
        }
        is SessionRecordingState.Error -> "Error: ${state.message}"
        else -> "Tap IDLE for pre-flight sensor check"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun TopSessionStatusPill(
    stateProvider: () -> SessionRecordingState,
    isAuditingProvider: () -> Boolean,
    canStatsProvider: () -> CanedgeSyncStats?,
    showSavedRecently: Boolean,
    canOpenPreflight: Boolean,
    onOpenPreflight: () -> Unit
) {
    val statusPill = resolveCockpitStatusPill(
        state = stateProvider(),
        isAuditing = isAuditingProvider(),
        canStats = canStatsProvider(),
        showSavedRecently = showSavedRecently,
        compactRecordingText = false,
        recordingContainerColor = Color.Transparent,
        recordingContentColor = MaterialTheme.colorScheme.error,
        savedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        savedContentColor = MaterialTheme.colorScheme.primary,
        idleContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        idleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Surface(
        color = statusPill.bgColor,
        shape = RoundedCornerShape(4.dp),
        border = if (canOpenPreflight) BorderStroke(1.dp, Color(0xFF475569).copy(alpha = 0.6f)) else null,
        modifier = Modifier.then(if (canOpenPreflight) Modifier.clickable(onClick = onOpenPreflight) else Modifier)
    ) {
        Text(
            text = statusPill.text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            color = statusPill.fgColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
