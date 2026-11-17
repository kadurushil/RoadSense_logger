package com.bajajauto.roadsense.camera

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceView

/**
 * A [SurfaceView] that maintains the camera's native aspect ratio without stretching
 * and renders on a dedicated SurfaceFlinger hardware overlay plane (zero HWUI RenderThread copy).
 *
 * Uses default window-behind hole-punching (`setZOrderMediaOverlay(false)`) so Compose HUD
 * overlays (`ViewfinderRadarOverlay`, `TapFocusReticleOverlay`, badges) render cleanly on top.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    init {
        setZOrderMediaOverlay(false)
    }

    /**
     * Sets the aspect ratio for this view.
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "Size values must be non-negative" }
        if (ratioWidth != width || ratioHeight != height) {
            ratioWidth = width
            ratioHeight = height
            requestLayout()
        }
    }

    /**
     * Sets the aspect ratio aware of display orientation (portrait vs landscape).
     */
    fun setAspectRatio(streamWidth: Int, streamHeight: Int, displayRotation: Int) {
        val isLandscape = (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270)
        val w = if (isLandscape) maxOf(streamWidth, streamHeight) else minOf(streamWidth, streamHeight)
        val h = if (isLandscape) minOf(streamWidth, streamHeight) else maxOf(streamWidth, streamHeight)
        setAspectRatio(w, h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }
}
