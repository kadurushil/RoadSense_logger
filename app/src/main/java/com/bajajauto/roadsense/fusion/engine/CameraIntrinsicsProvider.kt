package com.bajajauto.roadsense.fusion.engine

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF
import android.view.Surface
import com.bajajauto.roadsense.camera.CameraViewportTransform
import com.bajajauto.roadsense.logging.AppLogger
import kotlin.math.atan
import kotlin.math.tan

/**
 * Pinhole camera intrinsic parameters scaled to an arbitrary viewport resolution
 * in Standard 16:9 FIT (letterbox/pillarbox) geometry.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val hfovDeg: Float,
    val vfovDeg: Float,
    val videoOffsetX: Float = 0f,
    val videoOffsetY: Float = 0f,
    val videoWidth: Float = imageWidth.toFloat(),
    val videoHeight: Float = imageHeight.toFloat()
)

object CameraIntrinsicsProvider {

    private const val TAG = "CameraIntrinsics"

    /**
     * Extracts or calculates camera intrinsics for the specified camera and viewport dimensions,
     * maintaining square pixels and standard 16:9 FIT geometry.
     */
    fun getIntrinsics(
        context: Context,
        cameraId: String?,
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int = 1920,
        streamHeight: Int = 1080,
        displayRotation: Int = Surface.ROTATION_270
    ): CameraIntrinsics {
        val safeW = if (viewWidth > 0) viewWidth else 1920
        val safeH = if (viewHeight > 0) viewHeight else 1080
        val safeStreamW = if (streamWidth > 0) streamWidth else 1920
        val safeStreamH = if (streamHeight > 0) streamHeight else 1080

        val geo = CameraViewportTransform.computeViewportGeometry(
            viewWidth = safeW,
            viewHeight = safeH,
            streamWidth = safeStreamW,
            streamHeight = safeStreamH,
            displayRotation = displayRotation
        )

        if (cameraId == null) {
            return fallbackIntrinsics(safeW, safeH, safeStreamW, safeStreamH, geo)
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)

            val lensCalib = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalMm = focalLengths?.firstOrNull() ?: 3.58f

            // 1. Hardware-calibrated intrinsics [fx, fy, cx, cy, skew] if supported (e.g. FULL / LOGICAL)
            if (lensCalib != null && lensCalib.size >= 4 && activeArray != null && activeArray.width() > 0 && activeArray.height() > 0) {
                val sensorW = activeArray.width().toFloat()
                val sensorH = activeArray.height().toFloat()
                val sensorAspect = sensorW / sensorH
                val streamAspect = safeStreamW.toFloat() / safeStreamH.toFloat()

                // Compute ISP active crop region from sensor to stream aspect
                val cropW = if (streamAspect >= sensorAspect) sensorW else sensorH * streamAspect
                val cropH = if (streamAspect >= sensorAspect) sensorW / streamAspect else sensorH
                val cropX = (sensorW - cropW) / 2f
                val cropY = (sensorH - cropH) / 2f

                val scaleToStream = safeStreamW / cropW

                val fxStream = lensCalib[0] * scaleToStream
                val fyStream = lensCalib[1] * scaleToStream
                val cxStream = (lensCalib[2] - cropX) * scaleToStream
                val cyStream = (lensCalib[3] - cropY) * scaleToStream

                // Scale stream intrinsics into on-screen Viewport FIT coordinates
                val fxScreen = fxStream * geo.scale
                val fyScreen = fyStream * geo.scale
                val cxScreen = cxStream * geo.scale + geo.offsetX
                val cyScreen = cyStream * geo.scale + geo.offsetY

                val hfov = (2.0 * atan((geo.videoWidth / (2.0 * fxScreen))) * 180.0 / Math.PI).toFloat()
                val vfov = (2.0 * atan((geo.videoHeight / (2.0 * fyScreen))) * 180.0 / Math.PI).toFloat()

                AppLogger.d(TAG, "LENS_INTRINSIC_CALIBRATION (FIT): fx=$fxScreen, fy=$fyScreen, cx=$cxScreen, cy=$cyScreen (HFOV=$hfov°, VFOV=$vfov°)")
                return CameraIntrinsics(
                    fx = fxScreen,
                    fy = fyScreen,
                    cx = cxScreen,
                    cy = cyScreen,
                    imageWidth = safeW,
                    imageHeight = safeH,
                    hfovDeg = hfov,
                    vfovDeg = vfov,
                    videoOffsetX = geo.offsetX,
                    videoOffsetY = geo.offsetY,
                    videoWidth = geo.videoWidth,
                    videoHeight = geo.videoHeight
                )
            }

            // 2. Physical sensor dimensions derivation (e.g. Galaxy M30 / LIMITED hardware level)
            if (physicalSize != null && physicalSize.width > 0f && physicalSize.height > 0f && focalMm > 0f) {
                val sensorAspect = physicalSize.width / physicalSize.height
                val streamAspect = safeStreamW.toFloat() / safeStreamH.toFloat()

                // For 16:9 stream on 4:3 sensor, the ISP crops sensor height:
                // Base focal length in stream buffer coordinates (square pixels preserved):
                val baseFocalStreamPx = if (streamAspect >= sensorAspect) {
                    safeStreamW * (focalMm / physicalSize.width)
                } else {
                    safeStreamH * (focalMm / physicalSize.height)
                }

                val fxScreen = baseFocalStreamPx * geo.scale
                val fyScreen = baseFocalStreamPx * geo.scale // Physically square pixels
                val cxScreen = (safeStreamW / 2f) * geo.scale + geo.offsetX
                val cyScreen = (safeStreamH / 2f) * geo.scale + geo.offsetY

                val hfov = (2.0 * atan((geo.videoWidth / (2.0 * fxScreen))) * 180.0 / Math.PI).toFloat()
                val vfov = (2.0 * atan((geo.videoHeight / (2.0 * fyScreen))) * 180.0 / Math.PI).toFloat()

                AppLogger.d(TAG, "Derived from physical sensor (FIT): fx=$fxScreen, fy=$fyScreen, cx=$cxScreen, cy=$cyScreen, HFOV=$hfov°, VFOV=$vfov° (focal=${focalMm}mm)")
                return CameraIntrinsics(
                    fx = fxScreen,
                    fy = fyScreen,
                    cx = cxScreen,
                    cy = cyScreen,
                    imageWidth = safeW,
                    imageHeight = safeH,
                    hfovDeg = hfov,
                    vfovDeg = vfov,
                    videoOffsetX = geo.offsetX,
                    videoOffsetY = geo.offsetY,
                    videoWidth = geo.videoWidth,
                    videoHeight = geo.videoHeight
                )
            }

        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to query CameraCharacteristics: ${e.message}")
        }

        return fallbackIntrinsics(safeW, safeH, safeStreamW, safeStreamH, geo)
    }

    /**
     * Standard 65.7° horizontal FOV automotive pinhole model for 16:9 stream in FIT mode.
     */
    fun fallbackIntrinsics(
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int = 1920,
        streamHeight: Int = 1080,
        geo: CameraViewportTransform.ViewportGeometry = CameraViewportTransform.computeViewportGeometry(viewWidth, viewHeight, streamWidth, streamHeight)
    ): CameraIntrinsics {
        val hfov = 65.7f
        val halfHfovRad = Math.toRadians(hfov.toDouble() / 2.0)
        val fxStream = (streamWidth / (2.0 * tan(halfHfovRad))).toFloat()
        val fyStream = fxStream

        val fxScreen = fxStream * geo.scale
        val fyScreen = fyStream * geo.scale
        val cxScreen = (streamWidth / 2f) * geo.scale + geo.offsetX
        val cyScreen = (streamHeight / 2f) * geo.scale + geo.offsetY

        val vfov = (2.0 * atan((geo.videoHeight / (2.0 * fyScreen))) * 180.0 / Math.PI).toFloat()

        return CameraIntrinsics(
            fx = fxScreen,
            fy = fyScreen,
            cx = cxScreen,
            cy = cyScreen,
            imageWidth = viewWidth,
            imageHeight = viewHeight,
            hfovDeg = hfov,
            vfovDeg = vfov,
            videoOffsetX = geo.offsetX,
            videoOffsetY = geo.offsetY,
            videoWidth = geo.videoWidth,
            videoHeight = geo.videoHeight
        )
    }
}
