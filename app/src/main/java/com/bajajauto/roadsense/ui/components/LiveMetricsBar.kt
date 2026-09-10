package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.gnss.GnssFix

/**
 * Sleek, high-density live telemetry ticker for in-vehicle testing.
 * Displays real-time sensor sampling frequencies (Hz/FPS), GNSS satellite lock quality,
 * and device battery & thermal status.
 */
@Composable
fun LiveMetricsBar(
    radarHz: Float,
    cameraFps: Float,
    gnssHz: Float,
    latestFix: GnssFix?,
    batteryPct: Int,
    batteryTempC: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF090B10),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sensor Hz rates
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(
                    label = "RADAR",
                    value = "${"%.1f".format(radarHz)} Hz",
                    color = if (radarHz > 15f) Color(0xFF00E676) else if (radarHz > 0f) Color(0xFFFFB300) else Color(0xFF78909C)
                )
                MetricPill(
                    label = "CAM",
                    value = "${"%.1f".format(cameraFps)} fps",
                    color = if (cameraFps > 20f) Color(0xFF00E676) else if (cameraFps > 0f) Color(0xFFFFB300) else Color(0xFF78909C)
                )
                MetricPill(
                    label = "GPS",
                    value = "${"%.1f".format(gnssHz)} Hz",
                    color = if (gnssHz > 0.5f) Color(0xFF00E676) else Color(0xFF78909C)
                )
            }

            // GNSS Satellites & Accuracy
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(
                    label = "SVs",
                    value = "${latestFix?.satellitesUsed ?: 0}/${latestFix?.satellitesInView ?: 0}",
                    color = if ((latestFix?.satellitesUsed ?: 0) >= 6) Color(0xFF00E676) else Color(0xFFFFB300)
                )
                if (latestFix != null && latestFix.accuracyMeters > 0f) {
                    MetricPill(
                        label = "ACC",
                        value = "±${"%.1f".format(latestFix.accuracyMeters)}m",
                        color = if (latestFix.accuracyMeters <= 2.5f) Color(0xFF00E676) else Color(0xFFFFB300)
                    )
                }
            }

            // Battery & Device Thermal Telemetry
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tempColor = when {
                    batteryTempC >= 45f -> Color(0xFFFF5252) // Overheating warning
                    batteryTempC >= 40f -> Color(0xFFFFB300) // Warm
                    else -> Color(0xFF80D8FF)               // Normal
                }
                Text(
                    text = "🔋 $batteryPct% • ${"%.1f".format(batteryTempC)}°C",
                    color = tempColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray
        )
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}
