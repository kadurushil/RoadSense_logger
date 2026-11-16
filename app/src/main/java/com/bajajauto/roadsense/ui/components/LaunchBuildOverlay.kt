package com.bajajauto.roadsense.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.BuildConfig
import com.bajajauto.roadsense.R
import kotlinx.coroutines.delay

private const val AUTO_DISMISS_MS = 3500L
private const val TICK_INTERVAL_MS = 50L

/**
 * Full-screen launch overlay displaying the RoadSense app icon, version/build number,
 * build timestamp, and git commit metadata so engineers can immediately verify they are
 * not running a stale APK.
 *
 * Auto-dismisses after [AUTO_DISMISS_MS], or can be paused with "Hold" / dismissed immediately.
 */
@Composable
fun LaunchBuildOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPaused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPaused) {
        if (isPaused) return@LaunchedEffect
        while (elapsedMs < AUTO_DISMISS_MS) {
            delay(TICK_INTERVAL_MS)
            elapsedMs = (elapsedMs + TICK_INTERVAL_MS).coerceAtMost(AUTO_DISMISS_MS)
        }
        onDismiss()
    }

    val isDirty = BuildConfig.GIT_SHA.endsWith("-dirty")
    val buildTypeColor = when (BuildConfig.BUILD_TYPE.lowercase()) {
        "release" -> Color(0xFF22C55E)
        "staging" -> Color(0xFF06B6D4)
        else -> Color(0xFFF59E0B)
    }
    val progress = (1f - (elapsedMs.toFloat() / AUTO_DISMISS_MS.toFloat())).coerceIn(0f, 1f)

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
            .padding(20.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF131A24),
            border = BorderStroke(1.dp, Color(0xFF283446)),
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(max = 440.dp)
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
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: App Icon + Name + Version/Build + Build Type Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_roadsense_logo),
                        contentDescription = "RoadSense Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "RoadSense",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = buildTypeColor.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, buildTypeColor.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = BuildConfig.BUILD_TYPE.uppercase(),
                                    color = buildTypeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "v${BuildConfig.VERSION_NAME}  •  Build #${BuildConfig.GIT_COMMIT_COUNT} (code ${BuildConfig.VERSION_CODE})",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (isDirty) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "UNCOMMITTED CHANGES",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF243041))

                // Build & Git Metadata Rows
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BuildMetaRow(
                        label = "Build Date",
                        value = BuildConfig.BUILD_TIMESTAMP
                    )
                    BuildMetaRow(
                        label = "Last Commit",
                        value = "${BuildConfig.GIT_SHA} (${BuildConfig.GIT_BRANCH})",
                        valueColor = if (isDirty) Color(0xFFFBBF24) else Color(0xFF38BDF8)
                    )
                    BuildMetaRow(
                        label = "Commit Date",
                        value = BuildConfig.GIT_COMMIT_DATE
                    )
                    BuildMetaRow(
                        label = "Commit Msg",
                        value = BuildConfig.GIT_COMMIT_MESSAGE,
                        maxLines = 2
                    )
                    BuildMetaRow(
                        label = "App ID",
                        value = BuildConfig.APPLICATION_ID
                    )
                }

                // Auto-dismiss progress bar + actions
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                "Paused • tap Continue"
                            } else {
                                val remainingSec = ((AUTO_DISMISS_MS - elapsedMs + 999L) / 1000L).coerceAtLeast(1L)
                                "Launching in ${remainingSec}s"
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
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF94A3B8))
                                ) {
                                    Text("Hold", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                            ) {
                                Text(
                                    "Continue",
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
private fun BuildMetaRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFE2E8F0),
    maxLines: Int = 1
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 82.dp)
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
