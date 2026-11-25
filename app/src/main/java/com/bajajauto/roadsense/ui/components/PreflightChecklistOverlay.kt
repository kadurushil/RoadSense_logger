package com.bajajauto.roadsense.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.canedge.model.CanedgeHudPhase
import com.bajajauto.roadsense.gnss.GnssState
import com.bajajauto.roadsense.recording.StorageHealth
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.SensorHudTone
import java.util.Locale

/**
 * Detailed Pre-Flight Sensor Readiness Modal opened by tapping the `IDLE` / `✓ SAVED` status pill
 * beside the Record button (or automatically before REC when "Prompt before REC" is enabled).
 *
 * Styled identically to [LaunchBuildOverlay] and [PostDriveSummaryOverlay], optimized as a
 * single-glance zero-scroll 2-column landscape dialog.
 */
@Composable
fun PreflightChecklistOverlay(
    viewModel: RadarViewModel,
    onDismiss: () -> Unit,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val metrics by viewModel.liveMetricsSnapshot.collectAsState()
    val radarConn by viewModel.connectionState.collectAsState()
    val cameraState by viewModel.cameraEngineState.collectAsState()
    val selectedRes by viewModel.selectedResolution.collectAsState()
    val selectedFps by viewModel.selectedFps.collectAsState()
    val isCameraMuted by viewModel.isCameraPreviewMuted.collectAsState()
    val gnssState by viewModel.gnssState.collectAsState()
    val imuTelemetry by viewModel.imuTelemetry.collectAsState()
    val canStats by viewModel.canedgeSyncStats.collectAsState()
    val calib by viewModel.calibrationParams.collectAsState()
    val sessionTags by viewModel.sessionTags.collectAsState()
    val promptBeforeRec by viewModel.promptPreflightBeforeRec.collectAsState()

    val isStorageBlocked = metrics.storageStatus.health == StorageHealth.CRITICAL_STOP
    val allCoreGreen = metrics.radarPill.tone == SensorHudTone.GREEN &&
        metrics.gpsPill.tone == SensorHudTone.GREEN &&
        metrics.storageStatus.health == StorageHealth.OK

    val (readinessLabel, readinessFg, readinessBg) = when {
        isStorageBlocked -> Triple("STORAGE BLOCKED", Color(0xFFF87171), Color(0xFF3F1315))
        allCoreGreen -> Triple("FLIGHT READY", Color(0xFF4ADE80), Color(0xFF0D3319))
        else -> Triple("PARTIAL READY", Color(0xFFFBBF24), Color(0xFF3B2605))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE6090D14))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = if (isLandscape) 12.dp else 16.dp, vertical = if (isLandscape) 6.dp else 14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF131A24),
            border = BorderStroke(1.dp, Color(0xFF283446)),
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(max = if (isLandscape) 720.dp else 440.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isLandscape) 14.dp else 16.dp, vertical = if (isLandscape) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pre-Flight Readiness",
                        color = Color.White,
                        fontSize = if (isLandscape) 14.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = readinessBg,
                        border = BorderStroke(1.dp, readinessFg)
                    ) {
                        Text(
                            text = readinessLabel,
                            color = readinessFg,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Preflight",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF243041))

                val radarDetail = when (val rc = radarConn) {
                    is RadarConnectionState.Connected -> "Connected • ${String.format(Locale.US, "%.1f", metrics.radarHz)} Hz"
                    is RadarConnectionState.Connecting -> "Connecting USB…"
                    is RadarConnectionState.Reconnecting -> "Reconnecting USB…"
                    is RadarConnectionState.Error -> "Error: ${rc.message}"
                    is RadarConnectionState.Disconnected -> "Disconnected (USB not attached)"
                }

                val camDetail = when (cameraState) {
                    is CameraEngineState.Previewing -> "Streaming ${String.format(Locale.US, "%.0f", metrics.cameraFps)} fps (${selectedRes.label} ${selectedFps.fps}p)"
                    is CameraEngineState.Recording -> "Recording (${selectedRes.label} ${selectedFps.fps}p)"
                    is CameraEngineState.Standby -> "Standby • auto-warms on REC"
                    is CameraEngineState.Opening -> "Opening Camera2 HAL…"
                    is CameraEngineState.Closed -> if (isCameraMuted) "Muted / Standby • auto-warms on REC" else "Closed • auto-warms on REC"
                    is CameraEngineState.Error -> "Error: ${(cameraState as CameraEngineState.Error).message}"
                }

                val imuDetail = when {
                    metrics.imuHz >= 80f -> "Live • ${String.format(Locale.US, "%.0f", metrics.imuHz)} Hz"
                    imuTelemetry.isMonitoring -> "Warming (${String.format(Locale.US, "%.0f", metrics.imuHz)} Hz)"
                    else -> "Standby • auto-warms on REC"
                }

                val gnssDetail = when (gnssState) {
                    is GnssState.Active -> "Fix ${String.format(Locale.US, "%.1f", metrics.gnssHz)} Hz • ${metrics.satellitesUsed}/${metrics.satellitesInView} sats (±${String.format(Locale.US, "%.1f", metrics.accuracyMeters)}m)"
                    is GnssState.Searching -> "Searching satellites (${metrics.satellitesUsed}/${metrics.satellitesInView})"
                    is GnssState.Disabled -> "Disabled / No permission"
                    is GnssState.Error -> "Error"
                }

                val canDetail = when (canStats.hudPhase) {
                    CanedgeHudPhase.CONNECTED_SYNCED -> "Online & Synced"
                    CanedgeHudPhase.SYNCING -> "Syncing (${(canStats.activeFileProgress * 100).toInt()}%)"
                    CanedgeHudPhase.WAITING_FINAL_CHUNK -> "Waiting final chunk"
                    CanedgeHudPhase.OFFLINE_PENDING -> "Offline • ${canStats.pendingSessionsCount} session(s) queued"
                    CanedgeHudPhase.SCANNING -> "Scanning Wi-Fi AP…"
                    CanedgeHudPhase.CALIBRATING -> "Calibrating clock…"
                    CanedgeHudPhase.PAUSED_RECORDING -> "Paused (recording)"
                    CanedgeHudPhase.ERROR -> "Error / Retry queued"
                    CanedgeHudPhase.OFFLINE -> "Offline (auto-syncs after drive)"
                }

                val storageDetail = if (metrics.storageStatus.freeMb >= 0L) {
                    val gb = metrics.storageStatus.freeMb / 1024f
                    "${String.format(Locale.US, "%.1f", gb)} GB free (${metrics.storageStatus.health.name})"
                } else {
                    "Checking…"
                }
                val storageTone = when (metrics.storageStatus.health) {
                    StorageHealth.OK -> SensorHudTone.GREEN
                    StorageHealth.LOW_WARN -> SensorHudTone.YELLOW
                    StorageHealth.CRITICAL_STOP -> SensorHudTone.RED
                }

                val thermalTone = when (metrics.thermalSeverity.rank) {
                    0 -> SensorHudTone.GREEN
                    1 -> SensorHudTone.YELLOW
                    else -> SensorHudTone.RED
                }
                val calibSummary = "P ${String.format(Locale.US, "%.1f", calib.effectivePitchDeg)}°  Y ${String.format(Locale.US, "%.1f", calib.effectiveYawDeg)}°  R ${String.format(Locale.US, "%.1f", calib.rollDeg)}°"
                val tagsSummary = listOfNotNull(
                    sessionTags.vehicleId.takeIf { it.isNotBlank() },
                    sessionTags.mountType.takeIf { it.isNotBlank() },
                    sessionTags.lightingCondition.takeIf { it.isNotBlank() },
                    sessionTags.dspFirmwareBuild.takeIf { it.isNotBlank() }
                ).joinToString(" • ").ifEmpty { "No tags set (edit in Storage tab)" }

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1.08f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "SENSOR SUBSYSTEMS",
                                color = Color(0xFF64748B),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            PreflightStatusRow("Radar", radarDetail, metrics.radarPill.tone)
                            PreflightStatusRow(
                                "Camera",
                                camDetail,
                                if (cameraState is CameraEngineState.Closed || cameraState is CameraEngineState.Standby) SensorHudTone.YELLOW else metrics.camPill.tone
                            )
                            PreflightStatusRow(
                                "IMU",
                                imuDetail,
                                if (!imuTelemetry.isMonitoring) SensorHudTone.YELLOW else metrics.imuPill.tone
                            )
                            PreflightStatusRow("GNSS", gnssDetail, metrics.gpsPill.tone)
                            PreflightStatusRow("CANedge2", canDetail, metrics.canPill.tone)
                        }

                        Column(
                            modifier = Modifier.weight(0.92f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "VEHICLE & ENVIRONMENT",
                                color = Color(0xFF64748B),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            PreflightStatusRow("Storage", storageDetail, storageTone)
                            PreflightStatusRow(
                                "Power",
                                "${metrics.batteryPct}% • ${String.format(Locale.US, "%.1f", metrics.batteryTempC)}°C (${metrics.thermalSeverity.name})",
                                thermalTone
                            )
                            PreflightStatusRow("Calib", calibSummary, SensorHudTone.CYAN)
                            PreflightStatusRow("Tags", tagsSummary, SensorHudTone.CYAN)
                            PreflightStatusRow("Warmup", "Auto-settles cold Cam/IMU (≤2s)", SensorHudTone.CYAN)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        PreflightStatusRow("Radar", radarDetail, metrics.radarPill.tone)
                        PreflightStatusRow("Camera", camDetail, if (cameraState is CameraEngineState.Closed || cameraState is CameraEngineState.Standby) SensorHudTone.YELLOW else metrics.camPill.tone)
                        PreflightStatusRow("IMU", imuDetail, if (!imuTelemetry.isMonitoring) SensorHudTone.YELLOW else metrics.imuPill.tone)
                        PreflightStatusRow("GNSS", gnssDetail, metrics.gpsPill.tone)
                        PreflightStatusRow("CANedge2", canDetail, metrics.canPill.tone)
                        HorizontalDivider(color = Color(0xFF243041))
                        PreflightStatusRow("Storage", storageDetail, storageTone)
                        PreflightStatusRow("Power", "${metrics.batteryPct}% • ${String.format(Locale.US, "%.1f", metrics.batteryTempC)}°C (${metrics.thermalSeverity.name})", thermalTone)
                        PreflightStatusRow("Calib", calibSummary, SensorHudTone.CYAN)
                        PreflightStatusRow("Tags", tagsSummary, SensorHudTone.CYAN)
                    }
                }

                HorizontalDivider(color = Color(0xFF243041))

                // Footer: Explicit Prompt-Before-REC toggle + Close + Start REC
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PreflightPromptCheckboxRow(
                            checked = promptBeforeRec,
                            onToggle = { viewModel.setPromptPreflightBeforeRec(!promptBeforeRec) },
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                border = BorderStroke(1.dp, Color(0xFF475569)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Close", fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = {
                                    onDismiss()
                                    onStartRecording()
                                },
                                enabled = !isStorageBlocked,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = "Start REC",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreflightPromptCheckboxRow(
                            checked = promptBeforeRec,
                            onToggle = { viewModel.setPromptPreflightBeforeRec(!promptBeforeRec) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, Color(0xFF475569)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                                modifier = Modifier.weight(1f).height(36.dp)
                            ) {
                                Text("Close", fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = {
                                    onDismiss()
                                    onStartRecording()
                                },
                                enabled = !isStorageBlocked,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                modifier = Modifier.weight(1f).height(36.dp)
                            ) {
                                Text(
                                    text = "Start REC",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
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
private fun PreflightPromptCheckboxRow(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (checked) Color(0xFF0284C7) else Color(0xFF0F172A),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (checked) Color(0xFF38BDF8) else Color(0xFF64748B)
            ),
            modifier = Modifier.size(18.dp)
        ) {
            if (checked) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Show pre-flight check before REC",
                color = Color(0xFFE2E8F0),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "When off, tapping Record starts immediately",
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreflightStatusRow(
    label: String,
    detail: String,
    tone: SensorHudTone
) {
    val dotColor = when (tone) {
        SensorHudTone.GREEN -> Color(0xFF22C55E)
        SensorHudTone.YELLOW -> Color(0xFFF59E0B)
        SensorHudTone.CYAN -> Color(0xFF38BDF8)
        SensorHudTone.RED -> Color(0xFFEF4444)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(56.dp),
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = detail,
            color = Color(0xFFE2E8F0),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
