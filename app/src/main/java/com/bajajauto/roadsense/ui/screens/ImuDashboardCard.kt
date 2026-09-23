package com.bajajauto.roadsense.ui.screens

import android.hardware.Sensor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.imu.ImuRatePreset
import com.bajajauto.roadsense.imu.ImuSamplingBenchmark
import com.bajajauto.roadsense.imu.ImuSensorCapability
import com.bajajauto.roadsense.imu.ImuTelemetryState
import com.bajajauto.roadsense.imu.SensorCategory
import com.bajajauto.roadsense.ui.RadarViewModel
import java.util.Locale
import kotlin.math.abs

enum class ImuSubTab(val title: String) {
    AUDIT("Sensor Audit"),
    PROFILER("Rate Profiler"),
    TELEMETRY("Live Telemetry")
}

/**
 * IMU & Multi-Sensor Cockpit Card:
 * 1. Hardware sensor audit exposing all device physical and synthetic chips.
 * 2. Real-time sampling rate benchmarking and timing jitter analyzer.
 * 3. Live 3-axis accelerometer, gyroscope, and vehicle attitude monitor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImuDashboardCard(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    val capabilities by viewModel.imuCapabilities.collectAsState()
    val benchmarkStats by viewModel.imuBenchmarkStats.collectAsState()
    val telemetry by viewModel.imuTelemetry.collectAsState()
    val imuHz by viewModel.imuHz.collectAsState()

    var selectedSubTab by remember { mutableStateOf(ImuSubTab.PROFILER) }
    var selectedCategoryFilter by remember { mutableStateOf<SensorCategory?>(null) }
    var selectedBenchmarkSensorType by remember { mutableStateOf(Sensor.TYPE_ACCELEROMETER) }
    var selectedRatePreset by remember { mutableStateOf(ImuRatePreset.RATE_100HZ) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- 1. Master Sub-Tab Bar ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ImuSubTab.values().forEach { tab ->
                    val isSelected = selectedSubTab == tab
                    Surface(
                        selected = isSelected,
                        onClick = { selectedSubTab = tab },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = tab.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // --- SUB-TAB CONTENT ---
        when (selectedSubTab) {
            ImuSubTab.AUDIT -> {
                SensorAuditContent(
                    capabilities = capabilities,
                    selectedCategory = selectedCategoryFilter,
                    onSelectCategory = { selectedCategoryFilter = it },
                    onRefresh = { viewModel.auditImuSensors() }
                )
            }
            ImuSubTab.PROFILER -> {
                RateProfilerContent(
                    capabilities = capabilities,
                    benchmarkStats = benchmarkStats,
                    selectedSensorType = selectedBenchmarkSensorType,
                    onSelectSensorType = { selectedBenchmarkSensorType = it },
                    selectedPreset = selectedRatePreset,
                    onSelectPreset = { selectedRatePreset = it },
                    onStartBenchmark = { viewModel.startImuBenchmark(selectedBenchmarkSensorType, selectedRatePreset) },
                    onStopBenchmark = { viewModel.stopImuBenchmark() }
                )
            }
            ImuSubTab.TELEMETRY -> {
                LiveTelemetryContent(
                    telemetry = telemetry,
                    imuHz = imuHz,
                    onStartMonitoring = { viewModel.startImuMonitoring() },
                    onStopMonitoring = { viewModel.stopImuMonitoring() }
                )
            }
        }
    }
}

// ==========================================
// SUB-TAB 1: SENSOR AUDIT
// ==========================================

@Composable
private fun SensorAuditContent(
    capabilities: List<ImuSensorCapability>,
    selectedCategory: SensorCategory?,
    onSelectCategory: (SensorCategory?) -> Unit,
    onRefresh: () -> Unit
) {
    // Header & Summary
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hardware Sensor Inventory",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${capabilities.size} sensors discovered via Android HAL",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Inventory")
            }
        }
    }

    // Category Filter Chips
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onSelectCategory(null) },
            label = { Text("All (${capabilities.size})", fontSize = 11.sp) }
        )
        SensorCategory.values().forEach { cat ->
            val count = capabilities.count { it.category == cat }
            if (count > 0) {
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(if (selectedCategory == cat) null else cat) },
                    label = { Text("${cat.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} ($count)", fontSize = 11.sp) }
                )
            }
        }
    }

    val filtered = if (selectedCategory != null) {
        capabilities.filter { it.category == selectedCategory }
    } else {
        capabilities
    }

    filtered.forEach { cap ->
        SensorCapabilityCard(cap = cap)
    }
}

@Composable
private fun SensorCapabilityCard(cap: ImuSensorCapability) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cap.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${cap.vendor} • v${cap.version} (Type ${cap.sensorType})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = if (cap.maxRateHz >= 100f) Color(0xFF1B5E20) else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = cap.rateRangeFormatted,
                        color = if (cap.maxRateHz >= 100f) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DetailColumn(label = "Range", value = "±%.1f".format(cap.maxRange))
                DetailColumn(label = "Resolution", value = cap.resolutionFormatted)
                DetailColumn(label = "Power", value = cap.powerFormatted)
                DetailColumn(label = "Category", value = cap.category.displayName.substringBefore(" "))
            }
        }
    }
}

@Composable
private fun DetailColumn(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

// ==========================================
// SUB-TAB 2: RATE PROFILER & JITTER ANALYZER
// ==========================================

@Composable
private fun RateProfilerContent(
    capabilities: List<ImuSensorCapability>,
    benchmarkStats: ImuSamplingBenchmark,
    selectedSensorType: Int,
    onSelectSensorType: (Int) -> Unit,
    selectedPreset: ImuRatePreset,
    onSelectPreset: (ImuRatePreset) -> Unit,
    onStartBenchmark: () -> Unit,
    onStopBenchmark: () -> Unit
) {
    // 1. Benchmark Target Selector
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Target Sensor for Empirical Profiling",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Primary sensor targets
            val keySensors = listOf(
                Sensor.TYPE_ACCELEROMETER to "LSM6DSL Accel",
                Sensor.TYPE_GYROSCOPE to "LSM6DSL Gyro",
                Sensor.TYPE_LINEAR_ACCELERATION to "Linear Accel (No g)",
                Sensor.TYPE_GAME_ROTATION_VECTOR to "Game Rotation (6-DOF)"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                keySensors.forEach { (type, label) ->
                    val isSelected = selectedSensorType == type
                    OutlinedButton(
                        onClick = { onSelectSensorType(type) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).height(34.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            Text(
                text = "Requested Sampling Delay Preset",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ImuRatePreset.values().forEach { preset ->
                    val isSelected = selectedPreset == preset
                    Surface(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = preset.label.substringBefore(" "),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Start / Stop Control Button
            Button(
                onClick = {
                    if (benchmarkStats.isRunning) onStopBenchmark() else onStartBenchmark()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (benchmarkStats.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Icon(
                    imageVector = if (benchmarkStats.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (benchmarkStats.isRunning) "Stop Rate Benchmark" else "Start Sampling Benchmark",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // 2. Real-Time Benchmark Metrics & Jitter KPI Cards
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (benchmarkStats.isRunning) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Empirical Rate Measurement",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (benchmarkStats.isRunning) {
                    Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = "● RUNNING (%.1fs)".format(benchmarkStats.durationSec),
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(text = "IDLE", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Big KPI Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "MEASURED RATE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "%.1f Hz".format(benchmarkStats.measuredHz),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (benchmarkStats.measuredHz >= 90f) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "TIMING JITTER (σ)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = benchmarkStats.jitterFormatted,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (benchmarkStats.jitterStdDevMs < 1.0f) Color(0xFF00E676) else Color(0xFFFFB300)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Secondary Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DetailColumn(label = "Mean Δt", value = "%.2f ms".format(benchmarkStats.meanIntervalMs))
                DetailColumn(label = "Min / Max Δt", value = benchmarkStats.intervalRangeFormatted)
                DetailColumn(label = "Total Samples", value = "%,d".format(benchmarkStats.totalSamples))
                DetailColumn(label = "Target", value = "%.0f Hz".format(selectedPreset.targetHz))
            }

            // HAL Cap Explanation Pill
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Hardware Observation: Samsung Exynos sensor HAL enforces an upper rate cap of 100.00 Hz (minDelay = 10,000 µs) on the STMicroelectronics LSM6DSL chip.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

// ==========================================
// SUB-TAB 3: LIVE TELEMETRY VISUALIZER
// ==========================================

@Composable
private fun LiveTelemetryContent(
    telemetry: ImuTelemetryState,
    imuHz: Float,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    // 1. Monitor Control Card
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Multi-Sensor Live Stream",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (telemetry.isMonitoring) "Streaming at %.1f Hz (Throttled 25Hz UI)".format(imuHz) else "Monitoring Stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (telemetry.isMonitoring) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = telemetry.isMonitoring,
                onCheckedChange = { enable ->
                    if (enable) onStartMonitoring() else onStopMonitoring()
                }
            )
        }
    }

    // 2. Accelerometer Comparison (Raw vs Linear without g)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Raw Accel (with 1g)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "|a| = %.2f m/s²".format(telemetry.rawAccel[3]),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            AxisBarRow(label = "X (Lateral)", value = telemetry.rawAccel[0], maxRange = 20f)
            AxisBarRow(label = "Y (Longitudinal)", value = telemetry.rawAccel[1], maxRange = 20f)
            AxisBarRow(label = "Z (Vertical)", value = telemetry.rawAccel[2], maxRange = 20f)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Linear Accel (without g)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "|a_lin| = %.2f m/s²".format(telemetry.linearAccel[3]),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF00E676)
                )
            }
            AxisBarRow(label = "X (Cornering)", value = telemetry.linearAccel[0], maxRange = 10f)
            AxisBarRow(label = "Y (Brake / Gas)", value = telemetry.linearAccel[1], maxRange = 10f)
            AxisBarRow(label = "Z (Bump / Dip)", value = telemetry.linearAccel[2], maxRange = 10f)
        }
    }

    // 3. Gyroscope Angular Velocity & Vehicle Attitude
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Gyroscope Angular Velocity (LSM6DSL)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            AxisBarRow(label = "Roll Rate (ωx)", value = telemetry.gyroRates[0], unit = "°/s", maxRange = 100f)
            AxisBarRow(label = "Pitch Rate (ωy)", value = telemetry.gyroRates[1], unit = "°/s", maxRange = 100f)
            AxisBarRow(label = "Yaw Rate (ωz)", value = telemetry.gyroRates[2], unit = "°/s", maxRange = 100f)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "Vehicle Attitude (Game Rotation Vector 6-DOF)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AttitudeGauge(label = "PITCH (Grade)", angleDeg = telemetry.pitchDeg)
                AttitudeGauge(label = "ROLL (Bank)", angleDeg = telemetry.rollDeg)
                AttitudeGauge(label = "YAW (Relative)", angleDeg = telemetry.yawDeg)
            }
        }
    }
}

@Composable
private fun AxisBarRow(
    label: String,
    value: Float,
    unit: String = "m/s²",
    maxRange: Float = 20f
) {
    val fraction = (abs(value) / maxRange).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, modifier = Modifier.width(110.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        if (value >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
        Text(
            text = "%+5.2f %s".format(value, unit),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(85.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun AttitudeGauge(label: String, angleDeg: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "%+.1f°".format(angleDeg),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (abs(angleDeg) > 15f) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary
        )
    }
}
