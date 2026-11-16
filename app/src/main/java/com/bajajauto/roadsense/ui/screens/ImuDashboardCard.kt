package com.bajajauto.roadsense.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.imu.ImuRatePreset
import com.bajajauto.roadsense.imu.ImuSensorCapability
import com.bajajauto.roadsense.imu.ImuTelemetryState
import com.bajajauto.roadsense.imu.SensorCategory
import com.bajajauto.roadsense.ui.RadarViewModel
import com.bajajauto.roadsense.ui.components.PhoneOrientation3DVisualizer
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
 *
 * State subscriptions (`imuCapabilities`, `imuBenchmarkStats`, `imuTelemetry`, `imuHz`)
 * are isolated inside their respective sub-tab composables so the root `ImuDashboardCard`
 * never recomposes on 25 Hz telemetry ticks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImuDashboardCard(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableStateOf(ImuSubTab.PROFILER) }
    var selectedCategoryFilter by remember { mutableStateOf<SensorCategory?>(null) }
    var selectedBenchmarkSensorType by remember { mutableStateOf(Sensor.TYPE_ACCELEROMETER) }
    var selectedRatePreset by remember { mutableStateOf(ImuRatePreset.RATE_100HZ) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- 1. Master Sub-Tab Bar ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(2.dp),
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
                        shape = RoundedCornerShape(2.dp),
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

        // --- SUB-TAB CONTENT (Isolated state subscriptions per sub-tab) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (selectedSubTab) {
                ImuSubTab.AUDIT -> {
                    ImuAuditSubTab(
                        viewModel = viewModel,
                        selectedCategory = selectedCategoryFilter,
                        onSelectCategory = { selectedCategoryFilter = it }
                    )
                }
                ImuSubTab.PROFILER -> {
                    ImuProfilerSubTab(
                        viewModel = viewModel,
                        selectedSensorType = selectedBenchmarkSensorType,
                        onSelectSensorType = { selectedBenchmarkSensorType = it },
                        selectedPreset = selectedRatePreset,
                        onSelectPreset = { selectedRatePreset = it }
                    )
                }
                ImuSubTab.TELEMETRY -> {
                    ImuTelemetrySubTab(viewModel = viewModel)
                }
            }
        }
    }
}

// ==========================================
// SUB-TAB 1: SENSOR AUDIT
// ==========================================

@Composable
private fun ImuAuditSubTab(
    viewModel: RadarViewModel,
    selectedCategory: SensorCategory?,
    onSelectCategory: (SensorCategory?) -> Unit
) {
    val capabilities by viewModel.imuCapabilities.collectAsState()
    val categoryCounts = remember(capabilities) {
        SensorCategory.values().associateWith { cat -> capabilities.count { it.category == cat } }
    }
    val filtered = remember(capabilities, selectedCategory) {
        if (selectedCategory != null) {
            capabilities.filter { it.category == selectedCategory }
        } else {
            capabilities
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "audit_header") {
            Card(
                shape = RoundedCornerShape(2.dp),
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
                    Column(modifier = Modifier.clearAndSetSemantics {}) {
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
                    IconButton(onClick = { viewModel.auditImuSensors() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Inventory")
                    }
                }
            }
        }

        item(key = "audit_chips") {
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
                    val count = categoryCounts[cat] ?: 0
                    if (count > 0) {
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { onSelectCategory(if (selectedCategory == cat) null else cat) },
                            label = {
                                Text(
                                    "${cat.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} ($count)",
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }
            }
        }

        items(filtered, key = { it.sensorType }) { cap ->
            SensorCapabilityCard(cap = cap)
        }
    }
}

@Composable
private fun SensorCapabilityCard(cap: ImuSensorCapability) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {}
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
                    shape = RoundedCornerShape(2.dp)
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
private fun ImuProfilerSubTab(
    viewModel: RadarViewModel,
    selectedSensorType: Int,
    onSelectSensorType: (Int) -> Unit,
    selectedPreset: ImuRatePreset,
    onSelectPreset: (ImuRatePreset) -> Unit
) {
    val benchmarkStats by viewModel.imuBenchmarkStats.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            shape = RoundedCornerShape(2.dp),
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

                Button(
                    onClick = {
                        if (benchmarkStats.isRunning) {
                            viewModel.stopImuBenchmark()
                        } else {
                            viewModel.startImuBenchmark(selectedSensorType, selectedPreset)
                        }
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
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
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
                        Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(2.dp)) {
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
                    shape = RoundedCornerShape(2.dp)
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
}

// ==========================================
// SUB-TAB 3: LIVE TELEMETRY VISUALIZER
// ==========================================

@Composable
private fun ImuTelemetrySubTab(
    viewModel: RadarViewModel
) {
    // Keep State un-dereferenced so 30 Hz telemetry flows strictly to Canvas DrawScopes (0 recompositions)
    val telemetryState = viewModel.imuTelemetry.collectAsState()
    val isMonitoring by remember { derivedStateOf { telemetryState.value.isMonitoring } }
    val telemetryProvider: () -> ImuTelemetryState = remember { { telemetryState.value } }
    var show3DVisualizer by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ImuMonitoringHeaderCard(
            viewModel = viewModel,
            isMonitoring = isMonitoring
        )

        // Collapsible 3D Visualizer (collapsed by default to eliminate 3D projection/draw compute)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { show3DVisualizer = !show3DVisualizer }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = if (show3DVisualizer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "3D Attitude Visualizer",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (show3DVisualizer) "Expanded — tap to collapse & save compute" else "Collapsed to save compute — tap to expand",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (show3DVisualizer) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (show3DVisualizer) "Collapse 3D Visualizer" else "Expand 3D Visualizer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (show3DVisualizer) {
            PhoneOrientation3DVisualizer(
                telemetryProvider = telemetryProvider
            )
        }

        ImuNumericTelemetryCards(telemetryProvider = telemetryProvider)
    }
}

@Composable
private fun ImuMonitoringHeaderCard(
    viewModel: RadarViewModel,
    isMonitoring: Boolean
) {
    val imuHz by viewModel.imuHz.collectAsState()
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
            Column(modifier = Modifier.clearAndSetSemantics {}) {
                Text(
                    text = "Multi-Sensor Live Stream",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isMonitoring) "Streaming at %.1f Hz (Zero-Recomp Canvas)".format(imuHz) else "Monitoring Stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMonitoring) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isMonitoring,
                onCheckedChange = { enable ->
                    if (enable) viewModel.startImuMonitoring() else viewModel.stopImuMonitoring()
                }
            )
        }
    }
}

@Composable
private fun ImuNumericTelemetryCards(telemetryProvider: () -> ImuTelemetryState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Raw Accel (with 1g)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    ImuMagnitudeCanvas(
                        prefix = "|a| = ",
                        valueProvider = { telemetryProvider().rawAccel[3] },
                        textColor = MaterialTheme.colorScheme.primary
                    )
                }
                AxisBarRow(label = "X (Lateral)", valueProvider = { telemetryProvider().rawAccel[0] }, maxRange = 20f)
                AxisBarRow(label = "Y (Longitudinal)", valueProvider = { telemetryProvider().rawAccel[1] }, maxRange = 20f)
                AxisBarRow(label = "Z (Vertical)", valueProvider = { telemetryProvider().rawAccel[2] }, maxRange = 20f)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Linear Accel (without g)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    ImuMagnitudeCanvas(
                        prefix = "|a_lin| = ",
                        valueProvider = { telemetryProvider().linearAccel[3] },
                        textColor = Color(0xFF00E676)
                    )
                }
                AxisBarRow(label = "X (Cornering)", valueProvider = { telemetryProvider().linearAccel[0] }, maxRange = 10f)
                AxisBarRow(label = "Y (Brake / Gas)", valueProvider = { telemetryProvider().linearAccel[1] }, maxRange = 10f)
                AxisBarRow(label = "Z (Bump / Dip)", valueProvider = { telemetryProvider().linearAccel[2] }, maxRange = 10f)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
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
                AxisBarRow(label = "Roll Rate (ωx)", valueProvider = { telemetryProvider().gyroRates[0] }, unit = "°/s", maxRange = 100f)
                AxisBarRow(label = "Pitch Rate (ωy)", valueProvider = { telemetryProvider().gyroRates[1] }, unit = "°/s", maxRange = 100f)
                AxisBarRow(label = "Yaw Rate (ωz)", valueProvider = { telemetryProvider().gyroRates[2] }, unit = "°/s", maxRange = 100f)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Vehicle Attitude (Game Rotation Vector 6-DOF)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                AttitudeGaugeRowCanvas(
                    telemetryProvider = telemetryProvider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                )
            }
        }
    }
}

@Composable
private fun ImuMagnitudeCanvas(
    prefix: String,
    valueProvider: () -> Float,
    textColor: Color
) {
    val densityVal = LocalDensity.current.density
    val argb = textColor.toArgb()
    val paint = remember(densityVal, argb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            textSize = 12f * densityVal
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
    }
    Canvas(
        modifier = Modifier
            .width(145.dp)
            .height(16.dp)
            .clearAndSetSemantics {}
    ) {
        val text = String.format(Locale.US, "%s%.2f m/s²", prefix, valueProvider())
        drawContext.canvas.nativeCanvas.drawText(text, size.width, 12.5f * densityVal, paint)
    }
}

@Composable
private fun AxisBarRow(
    label: String,
    valueProvider: () -> Float,
    unit: String = "m/s²",
    maxRange: Float = 20f
) {
    val densityVal = LocalDensity.current.density
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val valueArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    val valuePaint = remember(densityVal, valueArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueArgb
            textSize = 11f * densityVal
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(110.dp)
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
        ) {
            val value = valueProvider()
            val fraction = (abs(value) / maxRange).coerceIn(0f, 1f)
            val textColW = 85f * densityVal
            val barW = (size.width - textColW).coerceAtLeast(10f)
            val barH = 10f * densityVal
            val barTop = (size.height - barH) * 0.5f
            val corner = CornerRadius(2f * densityVal, 2f * densityVal)

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, barTop),
                size = Size(barW, barH),
                cornerRadius = corner
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = if (value >= 0f) primaryColor else errorColor,
                    topLeft = Offset(0f, barTop),
                    size = Size(barW * fraction, barH),
                    cornerRadius = corner
                )
            }
            val text = String.format(Locale.US, "%+5.2f %s", value, unit)
            drawContext.canvas.nativeCanvas.drawText(
                text,
                size.width,
                barTop + barH * 0.85f,
                valuePaint
            )
        }
    }
}

@Composable
private fun AttitudeGaugeRowCanvas(
    telemetryProvider: () -> ImuTelemetryState,
    modifier: Modifier = Modifier
) {
    val densityVal = LocalDensity.current.density
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val warnArgb = android.graphics.Color.rgb(255, 179, 0)

    val labelPaint = remember(densityVal, labelArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelArgb
            textSize = 9f * densityVal
            textAlign = Paint.Align.CENTER
        }
    }
    val anglePaint = remember(densityVal) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15.5f * densityVal
            isFakeBoldText = true
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val t = telemetryProvider()
        val gap = 8.dp.toPx()
        val boxW = (size.width - gap * 2f) / 3f
        val boxH = size.height
        val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

        val labels = arrayOf("PITCH (Grade)", "ROLL (Bank)", "YAW (Relative)")
        val angles = floatArrayOf(t.pitchDeg, t.rollDeg, t.yawDeg)
        val nc = drawContext.canvas.nativeCanvas
        val labelY = 16f * densityVal
        val valueY = 38f * densityVal

        for (i in 0 until 3) {
            val left = i * (boxW + gap)
            val cx = left + boxW * 0.5f
            drawRoundRect(
                color = cardBg,
                topLeft = Offset(left, 0f),
                size = Size(boxW, boxH),
                cornerRadius = corner
            )
            nc.drawText(labels[i], cx, labelY, labelPaint)
            anglePaint.color = if (abs(angles[i]) > 15f) warnArgb else primaryArgb
            nc.drawText(String.format(Locale.US, "%+.1f°", angles[i]), cx, valueY, anglePaint)
        }
    }
}
