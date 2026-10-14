package com.bajajauto.roadsense.camera

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView

/**
 * A standard [TextureView] that can be adjusted to a specified aspect ratio (e.g. 16:9).
 *
 * Measures itself to strictly maintain the camera aspect ratio without stretching,
 * cropping, or runtime matrix distortion. Fits within the parent layout bounds.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    /**
     * Sets the aspect ratio for this view.
     *
     * @param width  Relative horizontal size (e.g. 16 or 1920)
     * @param height Relative vertical size (e.g. 9 or 1080)
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
            // Standard FIT: ensure the entire aspect ratio fits inside available bounds without cropping or stretching
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }
}
