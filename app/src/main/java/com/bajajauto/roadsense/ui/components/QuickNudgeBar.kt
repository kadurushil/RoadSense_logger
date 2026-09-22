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
import java.util.Locale

/**
 * Compact floating micro-adjustment bar for in-field fine-tuning of camera Pitch & Yaw.
 */
@Composable
fun QuickNudgeBar(
    calibrationParams: CalibrationParameters,
    onNudgePitch: (Float) -> Unit,
    onNudgeYaw: (Float) -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .padding(8.dp)
            .wrapContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pitch Nudge Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "P: ${String.format(Locale.US, "%.1f°", calibrationParams.effectivePitchDeg)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                IconButton(
                    onClick = { onNudgePitch(-0.1f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Pitch Up (-0.1°)",
                        tint = Color.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { onNudgePitch(0.1f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Pitch Down (+0.1°)",
                        tint = Color.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Divider(
                color = Color.DarkGray,
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
            )

            // Yaw Nudge Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Y: ${String.format(Locale.US, "%.1f°", calibrationParams.effectiveYawDeg)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                IconButton(
                    onClick = { onNudgeYaw(-0.1f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "Yaw Left (-0.1°)",
                        tint = Color.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { onNudgeYaw(0.1f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Yaw Right (+0.1°)",
                        tint = Color.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // If there are active nudges, offer Revert and Save
            if (calibrationParams.nudgePitchDeg != 0f || calibrationParams.nudgeYawDeg != 0f) {
                Divider(
                    color = Color.DarkGray,
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                )

                IconButton(
                    onClick = onRevert,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Undo,
                        contentDescription = "Revert Nudges",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onSave,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Save as Baseline",
                        tint = Color(0xFF69F0AE),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
