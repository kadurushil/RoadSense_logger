package com.bajajauto.roadsense.camera

import android.view.Surface
import com.bajajauto.roadsense.fusion.engine.CameraIntrinsicsProvider
import org.junit.Assert.*
import org.junit.Test

class CameraViewportTransformTest {

    @Test
    fun testGalaxyM30LandscapeViewportGeometry() {
        // Galaxy M30 display: 2340 x 1080 pixels (19.5:9)
        // Camera stream: 1920 x 1080 pixels (16:9)
        val geo = CameraViewportTransform.computeViewportGeometry(
            viewWidth = 2340,
            viewHeight = 1080,
            streamWidth = 1920,
            streamHeight = 1080,
            displayRotation = Surface.ROTATION_270
        )

        assertEquals("Scale factor must be exactly 1.0 for matching 1080p height", 1.0f, geo.scale, 0.001f)
        assertEquals("Video width must be 1920px unscaled", 1920f, geo.videoWidth, 0.001f)
        assertEquals("Video height must be 1080px unscaled", 1080f, geo.videoHeight, 0.001f)
        assertEquals("Horizontal pillarbox offset on left must be (2340 - 1920)/2 = 210px", 210f, geo.offsetX, 0.001f)
        assertEquals("Vertical offset must be 0px", 0f, geo.offsetY, 0.001f)
        assertEquals("Left edge of videoRect", 210f, geo.videoRect.left, 0.001f)
        assertEquals("Right edge of videoRect", 2130f, geo.videoRect.right, 0.001f)
    }

    @Test
    fun testStandard16By9ScreenViewportGeometry() {
        // Standard 1080p display: 1920 x 1080 pixels (16:9)
        val geo = CameraViewportTransform.computeViewportGeometry(
            viewWidth = 1920,
            viewHeight = 1080,
            streamWidth = 1920,
            streamHeight = 1080,
            displayRotation = Surface.ROTATION_270
        )

        assertEquals(1.0f, geo.scale, 0.001f)
        assertEquals(1920f, geo.videoWidth, 0.001f)
        assertEquals(1080f, geo.videoHeight, 0.001f)
        assertEquals(0f, geo.offsetX, 0.001f)
        assertEquals(0f, geo.offsetY, 0.001f)
    }

    @Test
    fun testFallbackIntrinsicsPreservesSquarePixelsAndCentersOnGalaxyM30() {
        val geo = CameraViewportTransform.computeViewportGeometry(
            viewWidth = 2340,
            viewHeight = 1080,
            streamWidth = 1920,
            streamHeight = 1080,
            displayRotation = Surface.ROTATION_270
        )

        val intrinsics = CameraIntrinsicsProvider.fallbackIntrinsics(
            viewWidth = 2340,
            viewHeight = 1080,
            streamWidth = 1920,
            streamHeight = 1080,
            geo = geo
        )

        assertEquals("fx and fy must be equal (square pixels)", intrinsics.fx, intrinsics.fy, 0.001f)
        assertEquals("Optical center cx must align with screen center (1170px)", 1170f, intrinsics.cx, 0.001f)
        assertEquals("Optical center cy must align with screen center (540px)", 540f, intrinsics.cy, 0.001f)
        assertEquals("Video offset X", 210f, intrinsics.videoOffsetX, 0.001f)
        assertEquals("Video width", 1920f, intrinsics.videoWidth, 0.001f)
    }
}
