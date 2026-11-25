package com.bajajauto.roadsense.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bajajauto.roadsense.recording.EventBookmarkGestureResolver
import com.bajajauto.roadsense.recording.EventBookmarkToast
import com.bajajauto.roadsense.recording.EventBookmarkType
import com.bajajauto.roadsense.recording.SwipeDirection
import kotlinx.coroutines.delay
import kotlin.math.hypot

enum class EventBookmarkButtonStyle {
    /** Large thumb-friendly button (matches fullscreen camera focus dock / SBS top-left action). */
    LARGE_DOCK,
    /** Compact pill button for LandscapeSessionControlBar and TopSessionHeader. */
    COMPACT_BAR
}

/**
 * 4-Direction Swipe Event Bookmark Button:
 * - Single tap (< 36 dp drag) marks a plain [EventBookmarkType.EVENT].
 * - Dragging >= 36 dp locks a directional trigger:
 *   ↑ [EventBookmarkType.CUT_IN], ↓ [EventBookmarkType.BRAKE],
 *   ← [EventBookmarkType.FALSE_ALARM], → [EventBookmarkType.BUMP].
 * - Displays an isolated translucent radial direction guide [Popup] while pressed/dragged
 *   so drivers can see directions without reflowing underlying layouts.
 */
@Composable
fun EventBookmarkButton(
    isRecording: Boolean,
    bookmarkCount: Int,
    hapticsEnabled: Boolean,
    style: EventBookmarkButtonStyle,
    onMarkEvent: (EventBookmarkType) -> Unit,
    modifier: Modifier = Modifier,
    showDockCaption: Boolean = true
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val thresholdPx = with(density) { EventBookmarkGestureResolver.MIN_SWIPE_THRESHOLD_DP.dp.toPx() }
    val guideShowPx = with(density) { EventBookmarkGestureResolver.RADIAL_GUIDE_SHOW_DP.dp.toPx() }

    var isPressed by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var activeDirection by remember { mutableStateOf(SwipeDirection.CENTER) }

    // If the driver holds their finger briefly (~180 ms) while recording, reveal the 4-way guide automatically
    LaunchedEffect(isPressed, isRecording) {
        if (isPressed && isRecording) {
            delay(180L)
            if (isPressed) {
                showGuide = true
            }
        } else {
            showGuide = false
        }
    }

    val currentOnMark by rememberUpdatedState(onMarkEvent)
    val currentHaptics by rememberUpdatedState(hapticsEnabled)
    val currentIsRecording by rememberUpdatedState(isRecording)

    val activeType = EventBookmarkGestureResolver.directionToBookmarkType(activeDirection)
    val activeAccent = Color(activeType.accentHex)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        val gestureModifier = Modifier.pointerInput(thresholdPx, guideShowPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentIsRecording) {
                    // Consume tap silently when idle so accidental taps don't leak through
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }

                isPressed = true
                activeDirection = SwipeDirection.CENTER
                var totalDx = 0f
                var totalDy = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                    if (change == null || !change.pressed) {
                        break
                    }
                    val delta = change.positionChange()
                    if (delta.x != 0f || delta.y != 0f) {
                        totalDx += delta.x
                        totalDy += delta.y
                        change.consume()
                        if (!showGuide && hypot(totalDx, totalDy) >= guideShowPx) {
                            showGuide = true
                        }
                        val nextDir = EventBookmarkGestureResolver.resolveDirection(totalDx, totalDy, thresholdPx)
                        if (nextDir != activeDirection) {
                            activeDirection = nextDir
                            if (currentHaptics && nextDir != SwipeDirection.CENTER) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                }

                val committedType = EventBookmarkGestureResolver.directionToBookmarkType(activeDirection)
                isPressed = false
                showGuide = false
                activeDirection = SwipeDirection.CENTER

                if (currentHaptics) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                currentOnMark(committedType)
            }
        }

        when (style) {
            EventBookmarkButtonStyle.LARGE_DOCK -> {
                Surface(
                    color = Color.Black.copy(alpha = if (isRecording) 0.72f else 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isPressed) 1.5.dp else 1.dp,
                        color = when {
                            isPressed -> activeAccent
                            isRecording -> Color(0xFF38BDF8).copy(alpha = 0.65f)
                            else -> Color.White.copy(alpha = 0.16f)
                        }
                    ),
                    modifier = gestureModifier.alpha(if (isRecording) 1f else 0.48f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = if (showDockCaption) 7.dp else 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    color = when {
                                        isPressed -> activeAccent.copy(alpha = 0.35f)
                                        isRecording -> Color(0xFF0284C7).copy(alpha = 0.32f)
                                        else -> Color.White.copy(alpha = 0.10f)
                                    },
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = "Mark Event (Tap or Swipe 4-Way)",
                                tint = when {
                                    isPressed -> activeAccent
                                    isRecording -> Color(0xFF7DD3FC)
                                    else -> Color(0xFF94A3B8)
                                },
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        if (showDockCaption) {
                            Text(
                                text = when {
                                    isPressed && activeDirection != SwipeDirection.CENTER -> activeType.shortLabel
                                    bookmarkCount > 0 -> "MARK ($bookmarkCount)"
                                    else -> "MARK"
                                },
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isPressed -> activeAccent
                                    isRecording -> Color(0xFFBAE6FD)
                                    else -> Color(0xFF94A3B8)
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            EventBookmarkButtonStyle.COMPACT_BAR -> {
                Surface(
                    color = when {
                        isPressed -> activeAccent.copy(alpha = 0.30f)
                        isRecording -> Color(0xFF0C4A6E)
                        else -> Color(0xFF1E293B)
                    },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = when {
                            isPressed -> activeAccent
                            isRecording -> Color(0xFF38BDF8).copy(alpha = 0.70f)
                            else -> Color(0xFF334155)
                        }
                    ),
                    modifier = gestureModifier
                        .height(26.dp)
                        .alpha(if (isRecording) 1f else 0.50f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = "Mark Event",
                            tint = when {
                                isPressed -> activeAccent
                                isRecording -> Color(0xFF38BDF8)
                                else -> Color(0xFF64748B)
                            },
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = when {
                                isPressed && activeDirection != SwipeDirection.CENTER -> activeType.shortLabel
                                bookmarkCount > 0 -> "MARK $bookmarkCount"
                                else -> "MARK"
                            },
                            color = when {
                                isPressed -> activeAccent
                                isRecording -> Color(0xFFE0F2FE)
                                else -> Color(0xFF94A3B8)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Translucent 4-Way Swipe Radial Guide Popup (isolated window layer, zero layout shift)
        if (isPressed && showGuide && isRecording) {
            Popup(
                alignment = Alignment.Center,
                offset = IntOffset(0, 0),
                properties = PopupProperties(focusable = false, clippingEnabled = true)
            ) {
                RadialBookmarkGuide(activeDirection = activeDirection)
            }
        }
    }
}

@Composable
private fun RadialBookmarkGuide(activeDirection: SwipeDirection) {
    Surface(
        color = Color(0xEB0B1220),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GuideDirectionChip(
                label = "↑ CUT-IN",
                type = EventBookmarkType.CUT_IN,
                selected = activeDirection == SwipeDirection.UP
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GuideDirectionChip(
                    label = "← FALSE",
                    type = EventBookmarkType.FALSE_ALARM,
                    selected = activeDirection == SwipeDirection.LEFT
                )
                GuideDirectionChip(
                    label = "● EVENT",
                    type = EventBookmarkType.EVENT,
                    selected = activeDirection == SwipeDirection.CENTER
                )
                GuideDirectionChip(
                    label = "BUMP →",
                    type = EventBookmarkType.BUMP,
                    selected = activeDirection == SwipeDirection.RIGHT
                )
            }
            GuideDirectionChip(
                label = "↓ BRAKE",
                type = EventBookmarkType.BRAKE,
                selected = activeDirection == SwipeDirection.DOWN
            )
        }
    }
}

@Composable
private fun GuideDirectionChip(
    label: String,
    type: EventBookmarkType,
    selected: Boolean
) {
    val accent = Color(type.accentHex)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accent.copy(alpha = 0.28f) else Color(0xFF162032),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else Color(0xFF283548)
        )
    ) {
        Text(
            text = label,
            color = if (selected) accent else Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Brief non-blocking confirmation toast pill displayed for 1.6 s whenever a bookmark is recorded.
 */
@Composable
fun EventBookmarkToastBanner(
    toast: EventBookmarkToast?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        if (toast != null) {
            val accent = Color(toast.accentHex)
            Surface(
                color = Color(0xE80D1520),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.85f)),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = toast.message,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
