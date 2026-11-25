package com.bajajauto.roadsense.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.ui.PostDriveSummaryData
import kotlinx.coroutines.delay
import java.util.Locale

private const val SUMMARY_AUTO_DISMISS_MS = 10_000L
private const val SUMMARY_TICK_MS = 50L

/**
 * Post-Drive Summary Card modeled on [LaunchBuildOverlay]:
 * - Appears automatically once asynchronous [SessionSyncAuditor] completes after stopping a drive.
 * - Auto-dismisses after 10 seconds with a live progress bar, or tap anywhere on the card / "Hold" to inspect.
 * - Uses a zero-scroll 2-column landscape layout showing Sync Grade, sensor stream counts/drops,
 *   any occurred warnings/errors, CANedge2 background sync status, thermal peak, and bookmarks/tags.
 */
@Composable
fun PostDriveSummaryOverlay(
    summary: PostDriveSummaryData,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isPaused by remember(summary.sessionId) { mutableStateOf(false) }
    var elapsedMs by remember(summary.sessionId) { mutableLongStateOf(0L) }

    LaunchedEffect(summary.sessionId, isPaused) {
        if (isPaused) return@LaunchedEffect
        while (elapsedMs < SUMMARY_AUTO_DISMISS_MS) {
            delay(SUMMARY_TICK_MS)
            elapsedMs = (elapsedMs + SUMMARY_TICK_MS).coerceAtMost(SUMMARY_AUTO_DISMISS_MS)
        }
        onDismiss()
    }

    val progress = (1f - (elapsedMs.toFloat() / SUMMARY_AUTO_DISMISS_MS.toFloat())).coerceIn(0f, 1f)
    val grade = summary.audit?.certificationGrade ?: "AUDITED"
    val (gradeFg, gradeBg) = when (grade) {
        "GOLD" -> Color(0xFF22C55E) to Color(0xFF0D3319)
        "SILVER" -> Color(0xFF38BDF8) to Color(0xFF0C2D48)
        "WARN" -> Color(0xFFF59E0B) to Color(0xFF3B2605)
        else -> Color(0xFF94A3B8) to Color(0xFF1E293B)
    }

    val durSec = (summary.durationMs / 1000L).coerceAtLeast(0L)
    val durationLabel = String.format(Locale.US, "%02d:%02d", durSec / 60L, durSec % 60L)

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
                    onClick = { isPaused = true }
                )
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isLandscape) 14.dp else 16.dp, vertical = if (isLandscape) 10.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 7.dp else 11.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Post-Drive Session Summary",
                            color = Color.White,
                            fontSize = if (isLandscape) 16.sp else 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${summary.sessionId}  •  Duration $durationLabel",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = gradeBg,
                        border = BorderStroke(1.dp, gradeFg)
                    ) {
                        Text(
                            text = "SYNC: $grade",
                            color = gradeFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF243041))

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        SummaryStreamsColumn(
                            summary = summary,
                            modifier = Modifier.weight(1.05f)
                        )
                        SummaryHealthColumn(
                            summary = summary,
                            modifier = Modifier.weight(0.95f)
                        )
                    }
                } else {
                    SummaryStreamsColumn(summary = summary, modifier = Modifier.fillMaxWidth())
                    SummaryHealthColumn(summary = summary, modifier = Modifier.fillMaxWidth())
                }

                // Footer countdown + actions
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = if (isPaused) Color(0xFF64748B) else Color(0xFF38BDF8),
                        trackColor = Color(0xFF1E293B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPaused) {
                                "Paused • tap Done when ready"
                            } else {
                                val remSec = ((SUMMARY_AUTO_DISMISS_MS - elapsedMs + 999L) / 1000L).coerceAtLeast(1L)
                                "Auto-closing in ${remSec}s (tap card to hold)"
                            },
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!isPaused) {
                                TextButton(
                                    onClick = { isPaused = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF94A3B8))
                                ) {
                                    Text("Hold", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                            ) {
                                Text(
                                    text = "Done",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
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
private fun SummaryStreamsColumn(
    summary: PostDriveSummaryData,
    modifier: Modifier = Modifier
) {
    val audit = summary.audit
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "STREAMS & SYNCHRONIZATION",
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        val camStat = audit?.cameraStats
        val camColor = when {
            camStat == null -> Color(0xFF94A3B8)
            camStat.anomalyCount == 0 -> Color(0xFF3FB950)
            camStat.anomalyCount <= 1 -> Color(0xFF69F0AE)  // Light Green for 1 startup pause (matches Storage scorecard)
            camStat.anomalyCount <= 3 -> Color(0xFFFBBF24)
            else -> Color(0xFFF87171)
        }
        SummaryMetricRow(
            label = "Camera",
            value = if (camStat != null) {
                "${
                    String.format(
                        Locale.US,
                        "%.1f",
                        camStat.effectiveRateHz
                    )
                } fps • ${summary.cameraFrames}f (${camStat.anomalyCount} drops)"
            } else {
                "${summary.cameraFrames} frames"
            },
            valueColor = camColor
        )

        val radStat = audit?.radarStats
        val effectiveRadarDrops = maxOf(summary.radarDrops, radStat?.anomalyCount?.toLong() ?: 0L)
        val radColor = when {
            summary.radarFrames <= 0L && radStat == null -> Color(0xFF94A3B8)
            effectiveRadarDrops == 0L -> Color(0xFF3FB950)
            effectiveRadarDrops < 5L -> Color(0xFF69F0AE)  // Light Green for < 5 minor drops
            effectiveRadarDrops <= 15L -> Color(0xFFFBBF24)
            else -> Color(0xFFF87171)
        }
        SummaryMetricRow(
            label = "Radar",
            value = if (radStat != null) {
                "${
                    String.format(
                        Locale.US,
                        "%.1f",
                        radStat.effectiveRateHz
                    )
                } Hz • ${summary.radarFrames}f (${effectiveRadarDrops}d/${summary.radarSalvaged}s)"
            } else if (summary.radarFrames > 0L) {
                "${summary.radarFrames}f ($effectiveRadarDrops drops)"
            } else {
                "Disconnected"
            },
            valueColor = radColor
        )

        val imuStat = audit?.imuStats
        SummaryMetricRow(
            label = "IMU",
            value = if (imuStat != null) {
                "${
                    String.format(
                        Locale.US,
                        "%.0f",
                        imuStat.effectiveRateHz
                    )
                } Hz • ${summary.imuFrames}s (${imuStat.anomalyCount} spikes)"
            } else {
                "${summary.imuFrames} samples"
            },
            valueColor = if (imuStat != null && imuStat.anomalyCount == 0) Color(0xFF4ADE80) else Color(0xFFFBBF24)
        )

        SummaryMetricRow(
            label = "GNSS",
            value = "${summary.gnssFixes} fixes logged",
            valueColor = if (summary.gnssFixes > 0L) Color(0xFF4ADE80) else Color(0xFF94A3B8)
        )

        if (audit != null) {
            Surface(
                color = Color(0xFF0D1420),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Cam↔IMU Δt: ${
                            String.format(
                                Locale.US,
                                "%.2f",
                                audit.cameraImuProximity.meanDeltaMs
                            )
                        }ms mean (${String.format(Locale.US, "%.1f", audit.cameraImuProximity.pctCompliant)}% ≤5ms)",
                        color = Color(0xFF7DD3FC),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    audit.radarCameraProximity?.let { rc ->
                        Text(
                            text = "Rad↔Cam Δt: ${
                                String.format(
                                    Locale.US,
                                    "%.2f",
                                    rc.meanDeltaMs
                                )
                            }ms mean (${String.format(Locale.US, "%.1f", rc.pctCompliant)}% ≤${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    rc.complianceThresholdMs
                                )
                            }ms)",
                            color = Color(0xFF7DD3FC),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryHealthColumn(
    summary: PostDriveSummaryData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "HEALTH, TAGS & CANEDGE2",
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // Occurred Errors & Warnings Box
        val hasWarnings = summary.warnings.isNotEmpty()
        Surface(
            color = if (hasWarnings) Color(0xFF2D1914) else Color(0xFF0D261A),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                1.dp,
                if (hasWarnings) Color(0xFFF59E0B).copy(alpha = 0.6f) else Color(0xFF22C55E).copy(alpha = 0.45f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!hasWarnings) {
                    Text(
                        text = "✓ Zero errors or warnings during drive",
                        color = Color(0xFF4ADE80),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "⚠ ${summary.warnings.size} warning(s) occurred:",
                        color = Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    summary.warnings.take(3).forEach { w ->
                        Text(
                            text = "• $w",
                            color = Color(0xFFFDE68A),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (summary.warnings.size > 3) {
                        Text(
                            text = "+${summary.warnings.size - 3} more in session_metadata.json",
                            color = Color(0xFF94A3B8),
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        SummaryMetricRow(
            label = "Bookmarks",
            value = "${summary.bookmarksCount} marker(s) • Thermal ${summary.peakThermalSeverity} (${
                String.format(
                    Locale.US,
                    "%.1f",
                    summary.peakBatteryTempC
                )
            }°C)",
            valueColor = Color(0xFFE2E8F0)
        )

        val tagSummary = listOfNotNull(
            summary.sessionTags.vehicleId.takeIf { it.isNotBlank() },
            summary.sessionTags.mountType.takeIf { it.isNotBlank() },
            summary.sessionTags.lightingCondition.takeIf { it.isNotBlank() }
        ).joinToString(" • ").ifEmpty { "Default" }
        SummaryMetricRow(
            label = "Run Tags",
            value = tagSummary,
            valueColor = Color(0xFFCBD5E1)
        )

        // CANedge2 Background Transition Notice
        Surface(
            color = Color(0xFF0C2234),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "CAN: ${summary.canStatusLabel}",
                color = Color(0xFFBAE6FD),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun SummaryMetricRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(68.dp),
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
