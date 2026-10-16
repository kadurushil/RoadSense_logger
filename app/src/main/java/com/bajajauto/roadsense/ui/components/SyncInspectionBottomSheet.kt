package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bajajauto.roadsense.sync.LiveSyncTelemetry

/**
 * High-density, 100% opaque cross-sensor synchronization inspection modal.
 *
 * Provides real-time visibility into microsecond sensor timing offsets, Nyquist thresholds,
 * stream continuity drop counters, and active session certification grades.
 *
 * Uses fully opaque surfaces (Color #161B22) and an elevated z-layer to guarantee zero
 * visual bleed-through over live camera preview or background graphs.
 */
@Composable
fun SyncInspectionDialog(
    telemetry: LiveSyncTelemetry,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161B22), // 100% Opaque JetBrains/GitHub dark
            tonalElevation = 12.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Color(0xFF30363D))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Cross-Sensor Synchronization",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF0F6FC)
                        )
                        Text(
                            text = "Real-time hardware clock association telemetry",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B949E)
                        )
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF8B949E)
                        )
                    }
                }

                // Grade Banner
                val (gradeColor, gradeBg, gradeText) = when (telemetry.liveGrade) {
                    "GOLD" -> Triple(Color(0xFF3FB950), Color(0xFF0D3319), "● GOLD CERTIFIED SYNC (< 3.5ms proximity)")
                    "SILVER" -> Triple(Color(0xFF58A6FF), Color(0xFF0C2D48), "● SILVER SYNCHRONIZATION (< 7.5ms proximity)")
                    "WARN" -> Triple(Color(0xFFD29922), Color(0xFF382800), "⚠️ ELEVATED JITTER / OFFSET DETECTED")
                    else -> Triple(Color(0xFF8B949E), Color(0xFF21262D), "○ IDLE / MONITORING STREAM")
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = gradeBg,
                    border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = gradeText,
                            color = gradeColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Keyframes: ${telemetry.totalKeyframes}",
                            color = Color(0xFFF0F6FC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Two Alignment Detail Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card 1: Camera <-> IMU
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D1117),
                        border = BorderStroke(1.dp, Color(0xFF30363D))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Camera ↔ IMU",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8B949E)
                            )
                            Text(
                                text = "${"%.2f".format(telemetry.camToImuDeltaMs)} ms",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (telemetry.camToImuDeltaMs <= 3.5f) Color(0xFF3FB950) else Color(0xFFD29922),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Target: < 2.50 ms | Nyquist: 5.0 ms",
                                fontSize = 9.sp,
                                color = Color(0xFF6E7681)
                            )
                            Text(
                                text = if (telemetry.isImuActive) "● IMU Active (104 Hz)" else "○ IMU Inactive",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (telemetry.isImuActive) Color(0xFF3FB950) else Color(0xFFF85149)
                            )
                        }
                    }

                    // Card 2: Radar <-> Camera
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D1117),
                        border = BorderStroke(1.dp, Color(0xFF30363D))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Radar ↔ Camera",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8B949E)
                            )
                            if (telemetry.isRadarActive) {
                                Text(
                                    text = "${"%.2f".format(telemetry.radarToCamDeltaMs)} ms",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (telemetry.radarToCamDeltaMs <= 16.67f) Color(0xFF3FB950) else Color(0xFFD29922),
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Target: < 8.33 ms | Nyquist: 16.7 ms",
                                    fontSize = 9.sp,
                                    color = Color(0xFF6E7681)
                                )
                                Text(
                                    text = "● Radar Active (20 Hz)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF3FB950)
                                )
                            } else {
                                Text(
                                    text = "Disconnected",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8B949E)
                                )
                                Text(
                                    text = "Hardware unplugged / paused",
                                    fontSize = 9.sp,
                                    color = Color(0xFF6E7681)
                                )
                                Text(
                                    text = "○ Bypassed from sync grade",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF58A6FF)
                                )
                            }
                        }
                    }
                }

                // Continuity & Drop Counters
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0D1117),
                    border = BorderStroke(1.dp, Color(0xFF21262D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        StatCounter(label = "Cam Drops", count = telemetry.cameraDropCount, isGoodWhenZero = true)
                        StatCounter(label = "IMU Jitter", count = telemetry.imuJitterCount, isGoodWhenZero = true)
                        StatCounter(label = "Radar Delays", count = telemetry.radarDropCount, isGoodWhenZero = true)
                    }
                }

                // Footer note
                Text(
                    text = "Table output: sync/sync_keyframes.csv (Pre-indexed multimodal tabular association for O(1) ML dataset loading).",
                    fontSize = 10.sp,
                    color = Color(0xFF8B949E),
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun StatCounter(
    label: String,
    count: Long,
    isGoodWhenZero: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFF8B949E)
        )
        Text(
            text = "$count",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (count == 0L && isGoodWhenZero) Color(0xFF3FB950) else Color(0xFFD29922)
        )
    }
}
