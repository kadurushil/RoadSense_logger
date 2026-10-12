package com.bajajauto.roadsense.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.view.Surface
import kotlin.math.min

/**
 * Geometric mapping between camera sensor stream buffer (e.g. 1920x1080, 16:9)
 * and on-screen View/TextureView.
 *
 * Enforces Standard 16:9 FIT (letterbox/pillarbox) to guarantee that the camera
 * stream is never cropped, zoomed, or distorted, and that overlays align with 1:1 pixel fidelity.
 */
object CameraViewportTransform {

    data class ViewportRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun toRectF(): RectF = RectF(left, top, right, bottom)
    }

    data class ViewportGeometry(
        val videoWidth: Float,
        val videoHeight: Float,
        val offsetX: Float,
        val offsetY: Float,
        val scale: Float,
        val videoRect: ViewportRect
    )

    /**
     * Calculates the active video frame dimensions and offsets on screen for standard 16:9 FIT.
     */
    fun computeViewportGeometry(
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int = 1920,
        streamHeight: Int = 1080,
        displayRotation: Int = Surface.ROTATION_270
    ): ViewportGeometry {
        if (viewWidth <= 0 || viewHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) {
            val r = ViewportRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            return ViewportGeometry(viewWidth.toFloat(), viewHeight.toFloat(), 0f, 0f, 1f, r)
        }

        val isLandscape = (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270)
        
        // Effective stream buffer aspect in current display orientation:
        val effStreamW = if (isLandscape) streamWidth.toFloat() else streamHeight.toFloat()
        val effStreamH = if (isLandscape) streamHeight.toFloat() else streamWidth.toFloat()

        // Standard FIT scale factor (never crops, preserves 100% of FOV):
        val scale = min(viewWidth / effStreamW, viewHeight / effStreamH)

        val videoW = effStreamW * scale
        val videoH = effStreamH * scale

        val offsetX = (viewWidth - videoW) / 2f
        val offsetY = (viewHeight - videoH) / 2f

        val videoRect = ViewportRect(offsetX, offsetY, offsetX + videoW, offsetY + videoH)

        return ViewportGeometry(
            videoWidth = videoW,
            videoHeight = videoH,
            offsetX = offsetX,
            offsetY = offsetY,
            scale = scale,
            videoRect = videoRect
        )
    }

    /**
     * Calculates the Matrix transform for TextureView to display the camera stream in standard FIT.
     */
    fun configureTransformMatrix(
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        displayRotation: Int
    ): Matrix {
        val matrix = Matrix()
        if (viewWidth <= 0 || viewHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) return matrix

        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, streamHeight.toFloat(), streamWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            // FIT mode scale (never crops or zooms beyond bounds):
            val scale = min(
                viewHeight.toFloat() / streamHeight.toFloat(),
                viewWidth.toFloat() / streamWidth.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else if (displayRotation == Surface.ROTATION_180) {
            val scale = min(
                viewHeight.toFloat() / streamWidth.toFloat(),
                viewWidth.toFloat() / streamHeight.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(180f, centerX, centerY)
        } else {
            // PORTRAIT (Rotation 0)
            val bufferAspect = streamHeight.toFloat() / streamWidth.toFloat()
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            if (viewAspect > bufferAspect) {
                val scale = bufferAspect / viewAspect
                matrix.postScale(scale, 1f, centerX, centerY)
            } else if (viewAspect < bufferAspect) {
                val scale = viewAspect / bufferAspect
                matrix.postScale(1f, scale, centerX, centerY)
            }
        }

        return matrix
    }
}
