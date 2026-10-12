package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.imu.ImuTelemetryState
import java.util.Locale

/**
 * Dashboard card in the Camera controls panel displaying current radar-camera
 * spatial calibration status and providing actions to launch the Fullscreen Calibration Studio
 * and perform one-tap pitch synchronization from phone IMU.
 */
@Composable
fun CameraCalibrationCard(
    calibrationParams: CalibrationParameters,
    isRadarOverlayEnabled: Boolean,
    imuTelemetry: ImuTelemetryState? = null,
    onSyncImuPitch: (() -> Unit)? = null,
    onLaunchCalibration: () -> Unit,
    onToggleRadarOverlay: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Calibration",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "RADAR-CAMERA CALIBRATION",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = calibrationParams.profileName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Telemetry Readout Grid
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Row 1: Angles (θ_cam, ψ_rad, ϕ_roll)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "θ_cam: ${String.format(Locale.US, "%.1f°", calibrationParams.effectivePitchDeg)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64B5F6)
                        )
                        Text(
                            text = "ψ_rad: ${String.format(Locale.US, "%.1f°", calibrationParams.effectiveYawDeg)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF81C784)
                        )
                        Text(
                            text = "ϕ_roll: ${String.format(Locale.US, "%.1f°", calibrationParams.rollDeg)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Row 2: IMU Assistant Badges (Feature B & D)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DigitalClampLevelPill(
                            rollDeg = imuTelemetry?.rollDeg ?: calibrationParams.rollDeg,
                            onClick = onSyncImuPitch
                        )
                        PitchQualityBadge(
                            quality = calibrationParams.pitchQuality,
                            pitchDeg = calibrationParams.effectivePitchDeg
                        )
                    }

                    // Row 3: Physical Geometry Offsets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Setback: ${String.format(Locale.US, "%.2fm", calibrationParams.setbackM)}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Height Diff: ${String.format(Locale.US, "%.2fm", calibrationParams.heightOffsetM)}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Target: ${String.format(Locale.US, "%.1fm", calibrationParams.targetDistanceM)}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Primary Actions: One-Tap Sync & Fullscreen Launch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onSyncImuPitch != null) {
                    OutlinedButton(
                        onClick = onSyncImuPitch,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF64B5F6)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Pitch from IMU",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Sync IMU",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = onLaunchCalibration,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(if (onSyncImuPitch != null) 1.3f else 1f)
                        .height(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Calibrate (Studio)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Secondary Actions: Overlay Toggle & Reset
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleRadarOverlay,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isRadarOverlayEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(
                        imageVector = if (isRadarOverlayEnabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Radar HUD Overlay",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isRadarOverlayEnabled) "HUD: ON" else "HUD: OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onResetDefaults,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}
