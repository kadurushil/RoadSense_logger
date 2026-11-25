package com.bajajauto.roadsense.recording

import kotlin.math.abs

/**
 * User-triggered event bookmark categories written as `USER,EVENT_MARKER` into `session_timeline.csv`
 * and exported to Foxglove `/events` + `/diagnostics/logs`.
 */
enum class EventBookmarkType(
    val tag: String,
    val shortLabel: String,
    val accentHex: Long
) {
    EVENT("EVENT", "MARK", 0xFF38BDF8),
    CUT_IN("CUT_IN", "CUT-IN", 0xFFF59E0B),
    BRAKE("BRAKE", "BRAKE", 0xFFEF4444),
    FALSE_ALARM("FALSE_ALARM", "FALSE", 0xFFA855F7),
    BUMP("BUMP", "BUMP", 0xFF10B981)
}

enum class SwipeDirection {
    CENTER, UP, DOWN, LEFT, RIGHT
}

data class EventBookmarkRecord(
    val id: Long,
    val type: EventBookmarkType,
    val elapsedRealtimeNs: Long,
    val wallTimeMs: Long,
    val note: String = ""
)

data class EventBookmarkToast(
    val id: Long,
    val message: String,
    val accentHex: Long,
    val isWarning: Boolean = false
)

/**
 * Pure gesture resolver for the 4-direction swipe Event Bookmark button:
 * - Drag displacement < [MIN_SWIPE_THRESHOLD_DP] resolves to plain [EventBookmarkType.EVENT].
 * - Drag >= threshold resolves dominant axis: UP -> CUT_IN, DOWN -> BRAKE, LEFT -> FALSE_ALARM, RIGHT -> BUMP.
 */
object EventBookmarkGestureResolver {
    const val MIN_SWIPE_THRESHOLD_DP = 36f
    const val RADIAL_GUIDE_SHOW_DP = 8f

    fun resolveDirection(dxPx: Float, dyPx: Float, thresholdPx: Float): SwipeDirection {
        val t = thresholdPx.coerceAtLeast(1f)
        if ((dxPx * dxPx + dyPx * dyPx) < (t * t)) {
            return SwipeDirection.CENTER
        }
        return if (abs(dxPx) >= abs(dyPx)) {
            if (dxPx < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
        } else {
            if (dyPx < 0f) SwipeDirection.UP else SwipeDirection.DOWN
        }
    }

    fun directionToBookmarkType(direction: SwipeDirection): EventBookmarkType = when (direction) {
        SwipeDirection.CENTER -> EventBookmarkType.EVENT
        SwipeDirection.UP -> EventBookmarkType.CUT_IN
        SwipeDirection.DOWN -> EventBookmarkType.BRAKE
        SwipeDirection.LEFT -> EventBookmarkType.FALSE_ALARM
        SwipeDirection.RIGHT -> EventBookmarkType.BUMP
    }

    fun resolve(dxPx: Float, dyPx: Float, thresholdPx: Float): EventBookmarkType {
        return directionToBookmarkType(resolveDirection(dxPx, dyPx, thresholdPx))
    }
}
