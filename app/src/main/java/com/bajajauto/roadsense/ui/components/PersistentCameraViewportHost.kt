package com.bajajauto.roadsense.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.logging.AppLogger
import kotlin.math.roundToInt

/**
 * Coordinates window-relative bounds for the single persistent [AutoFitSurfaceView] host
 * in [MainActivity], preventing Surface destruction across Camera, SBS, Fullscreen, and
 * Calibration transitions (guaranteeing 0-drop MediaRecorder H.264 recording).
 */
@Stable
class PersistentCameraViewportState {
    var activeSlotId by mutableStateOf<String?>(null)
        private set

    var boundsInWindow by mutableStateOf(IntRect.Zero)
        private set

    var displayRotation by mutableIntStateOf(0)
        private set

    val isActive: Boolean
        get() = activeSlotId != null && boundsInWindow.width > 0 && boundsInWindow.height > 0

    fun updateSlot(slotId: String, bounds: IntRect, rotation: Int) {
        val changed = activeSlotId != slotId || boundsInWindow != bounds || displayRotation != rotation
        activeSlotId = slotId
        boundsInWindow = bounds
        displayRotation = rotation
        if (changed && DebugToggles.masterDebugEnabled) {
            AppLogger.d(
                "SurfaceViewport",
                "[SURFACE_VIEWPORT] slot=$slotId | active=true | bounds=${bounds.left},${bounds.top},${bounds.width}x${bounds.height} | rot=$rotation"
            )
        }
    }

    fun releaseSlot(slotId: String) {
        if (activeSlotId == slotId) {
            activeSlotId = null
            boundsInWindow = IntRect.Zero
            if (DebugToggles.masterDebugEnabled) {
                AppLogger.d("SurfaceViewport", "[SURFACE_VIEWPORT] slot=$slotId | active=false (parked 1x1)")
            }
        }
    }
}

val LocalPersistentCameraViewport = compositionLocalOf<PersistentCameraViewportState?> { null }

/**
 * Binds this composable's window bounds to the single persistent [AutoFitSurfaceView] host
 * while [active] is true, and releases it when [active] becomes false or the composable leaves.
 */
fun Modifier.bindPersistentCameraViewport(
    slotId: String,
    active: Boolean,
    displayRotation: Int,
    onViewportSized: ((width: Int, height: Int) -> Unit)? = null
): Modifier = composed {
    val viewportState = LocalPersistentCameraViewport.current

    DisposableEffect(viewportState, slotId, active) {
        if (!active) {
            viewportState?.releaseSlot(slotId)
        }
        onDispose {
            viewportState?.releaseSlot(slotId)
        }
    }

    if (active && viewportState != null) {
        this.onGloballyPositioned { coords ->
            if (!coords.isAttached) return@onGloballyPositioned
            val rect = coords.boundsInWindow()
            val intRect = IntRect(
                left = rect.left.roundToInt(),
                top = rect.top.roundToInt(),
                right = rect.right.roundToInt(),
                bottom = rect.bottom.roundToInt()
            )
            if (intRect.width > 0 && intRect.height > 0) {
                viewportState.updateSlot(slotId, intRect, displayRotation)
                onViewportSized?.invoke(intRect.width, intRect.height)
            }
        }
    } else {
        this
    }
}
