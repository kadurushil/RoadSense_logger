package com.bajajauto.roadsense.fusion.engine

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF
import com.bajajauto.roadsense.logging.AppLogger
import kotlin.math.atan

/**
 * Pinhole camera intrinsic parameters scaled to an arbitrary viewport resolution.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val hfovDeg: Float,
    val vfovDeg: Float
)

object CameraIntrinsicsProvider {

    private const val TAG = "CameraIntrinsics"

    /**
     * Extracts or calculates camera intrinsics for the specified camera and viewport dimensions.
     */
    fun getIntrinsics(
        context: Context,
        cameraId: String?,
        viewWidth: Int,
        viewHeight: Int
    ): CameraIntrinsics {
        val safeW = if (viewWidth > 0) viewWidth else 1920
        val safeH = if (viewHeight > 0) viewHeight else 1080

        if (cameraId == null) {
            return fallbackIntrinsics(safeW, safeH)
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)

            // 1. Check for hardware-calibrated intrinsics [fx, fy, cx, cy, skew]
            val lensCalib = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalMm = focalLengths?.firstOrNull() ?: 4.0f

            if (lensCalib != null && lensCalib.size >= 4 && activeArray != null && activeArray.width() > 0 && activeArray.height() > 0) {
                val scaleX = safeW.toFloat() / activeArray.width().toFloat()
                val scaleY = safeH.toFloat() / activeArray.height().toFloat()

                val fx = lensCalib[0] * scaleX
                val fy = lensCalib[1] * scaleY
                val cx = lensCalib[2] * scaleX
                val cy = lensCalib[3] * scaleY

                val hfov = (2.0 * atan((safeW / (2.0 * fx))) * 180.0 / Math.PI).toFloat()
                val vfov = (2.0 * atan((safeH / (2.0 * fy))) * 180.0 / Math.PI).toFloat()

                AppLogger.d(TAG, "Using LENS_INTRINSIC_CALIBRATION: fx=$fx, fy=$fy, cx=$cx, cy=$cy (HFOV=$hfov°)")
                return CameraIntrinsics(fx, fy, cx, cy, safeW, safeH, hfov, vfov)
            }

            // 2. Physical sensor dimensions fallback calculation
            if (physicalSize != null && physicalSize.width > 0f && physicalSize.height > 0f && focalMm > 0f) {
                val fx = safeW * (focalMm / physicalSize.width)
                val fy = safeH * (focalMm / physicalSize.height)
                val cx = safeW / 2f
                val cy = safeH / 2f

                val hfov = (2.0 * atan((physicalSize.width / (2.0 * focalMm))) * 180.0 / Math.PI).toFloat()
                val vfov = (2.0 * atan((physicalSize.height / (2.0 * focalMm))) * 180.0 / Math.PI).toFloat()

                AppLogger.d(TAG, "Calculated from sensor physical size: fx=$fx, fy=$fy, HFOV=$hfov° (focal=${focalMm}mm)")
                return CameraIntrinsics(fx, fy, cx, cy, safeW, safeH, hfov, vfov)
            }

        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to query CameraCharacteristics: ${e.message}")
        }

        return fallbackIntrinsics(safeW, safeH)
    }

    /**
     * Standard 68° horizontal FOV automotive pinhole model.
     */
    fun fallbackIntrinsics(viewWidth: Int, viewHeight: Int): CameraIntrinsics {
        val hfov = 68.0f
        val halfHfovRad = Math.toRadians(hfov.toDouble() / 2.0)
        val fx = (viewWidth / (2.0 * kotlin.math.tan(halfHfovRad))).toFloat()
        val fy = fx // Square pixels assumed
        val cx = viewWidth / 2.0f
        val cy = viewHeight / 2.0f
        val vfov = (2.0 * atan((viewHeight / (2.0 * fy))) * 180.0 / Math.PI).toFloat()

        return CameraIntrinsics(fx, fy, cx, cy, viewWidth, viewHeight, hfov, vfov)
    }
}
