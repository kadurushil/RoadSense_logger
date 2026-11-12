package com.bajajauto.roadsense.camera

import androidx.compose.runtime.Immutable
import android.util.Size

/**
 * User-configurable camera video recording profiles.
 */
enum class CameraResolution(val label: String, val width: Int, val height: Int) {
    RES_480P("480p (SD)", 640, 480),
    RES_720P("720p (HD)", 1280, 720),
    RES_1080P("1080p (FHD)", 1920, 1080);

    val size: Size
        get() = Size(width, height)
}

enum class CameraFrameRate(val label: String, val fps: Int) {
    FPS_15("15 FPS", 15),
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60)
}

/**
 * Lifecycle and recording states of the camera engine.
 */
@Immutable
sealed class CameraEngineState {
    @Immutable
    object Closed : CameraEngineState()
    @Immutable
    object Opening : CameraEngineState()
    @Immutable
    data class Previewing(
        val resolution: CameraResolution,
        val fps: Int
    ) : CameraEngineState()
    @Immutable
    data class Recording(
        val resolution: CameraResolution,
        val fps: Int,
        val framesRecorded: Long,
        val durationMs: Long
    ) : CameraEngineState()
    @Immutable
    data class Error(val message: String) : CameraEngineState()
}
