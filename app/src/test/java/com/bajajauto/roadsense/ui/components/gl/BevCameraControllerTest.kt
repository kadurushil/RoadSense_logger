package com.bajajauto.roadsense.ui.components.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BevCameraControllerTest {

    @Test
    fun orbitPitchClampsSafelyAndYawWrapsAcrossPlusMinus180() {
        val ctrl = BevCameraController(BevCameraState(is3D = true))
        assertFalse(ctrl.state.isModifiedFromDefault)

        // Drag vertically to extreme upward & downward limits
        ctrl.onSingleFingerDrag(0f, 1000f, 1200f, 800f, 100f)
        assertEquals(BevCameraState.MAX_PITCH_3D_DEG, ctrl.state.pitch3DDeg, 1e-4f)
        assertTrue(ctrl.state.isModifiedFromDefault)

        ctrl.onSingleFingerDrag(0f, -2000f, 1200f, 800f, 100f)
        assertEquals(BevCameraState.MIN_PITCH_3D_DEG, ctrl.state.pitch3DDeg, 1e-4f)

        // Drag horizontally past +180° -> wraps into [-180, 180]
        ctrl.onSingleFingerDrag(1000f, 0f, 1200f, 800f, 100f)
        assertTrue(ctrl.state.yaw3DDeg in -180f..180f)

        // Reset restores canonical pose while staying in 3D mode
        val reset = ctrl.resetView()
        assertTrue(reset.is3D)
        assertFalse(reset.isModifiedFromDefault)
        assertEquals(BevCameraState.DEFAULT_YAW_3D_DEG, reset.yaw3DDeg, 1e-4f)
        assertEquals(BevCameraState.DEFAULT_PITCH_3D_DEG, reset.pitch3DDeg, 1e-4f)
    }

    @Test
    fun pinchZoomAndTwoFingerPanRespectBoundsIn2DAnd3D() {
        val ctrl = BevCameraController(BevCameraState(is3D = false))
        repeat(20) { ctrl.onPinchZoom(1.3f) }
        assertEquals(BevCameraState.MAX_ZOOM_2D, ctrl.state.zoom2D, 1e-4f)
        assertTrue(ctrl.state.isModifiedFromDefault)

        ctrl.set3DMode(true)
        assertTrue(ctrl.state.is3D)
        val initialDist = ctrl.state.orbitDistScale
        ctrl.onPinchZoom(1.25f) // Zoom in -> smaller orbit distance
        assertTrue(ctrl.state.orbitDistScale < initialDist)

        ctrl.onTwoFingerPan(120f, -80f, 1200f, 800f, 100f)
        assertTrue(ctrl.state.targetX3DMeters != 0f || ctrl.state.targetY3DOffsetMeters != 0f)

        ctrl.resetView()
        assertFalse(ctrl.state.isModifiedFromDefault)
    }

    @Test
    fun perspectiveMvpProjectsForwardTargetToCenterRightPositiveXAndUpPositiveZ() {
        val mvp = FloatArray(16)
        val state = BevCameraState(is3D = true)
        val w = 1200f
        val h = 800f
        val maxRange = 100f
        BevCameraController.buildPerspectiveMvpMatrix(state, w, h, maxRange, mvp)

        val out = FloatArray(2)
        val targetY = maxRange * BevCameraState.DEFAULT_TARGET_Y_FRACTION

        // Look-at target (0, targetY, 0) must project to exact screen center
        assertTrue(BevCameraController.projectWorldToScreen(0f, targetY, 0f, mvp, w, h, out))
        assertEquals(w * 0.5f, out[0], 1.0f)
        assertEquals(h * 0.5f, out[1], 1.0f)

        // +X (right in radar ISO frame) must project to the right of center (screenX > w/2)
        assertTrue(BevCameraController.projectWorldToScreen(5f, targetY, 0f, mvp, w, h, out))
        assertTrue("Expected +X to project right of center, got x=${out[0]}", out[0] > w * 0.5f + 10f)

        // +Z (up in radar ISO frame) must project above center (screenY < h/2 in top-left Y-down coords)
        assertTrue(BevCameraController.projectWorldToScreen(0f, targetY, 3f, mvp, w, h, out))
        assertTrue("Expected +Z to project above center, got y=${out[1]}", out[1] < h * 0.5f - 10f)

        // Point far behind camera eye must return false
        assertFalse(BevCameraController.projectWorldToScreen(0f, -120f, 0f, mvp, w, h, out))
    }

    @Test
    fun apply2DCameraIsIdentityAtDefaultAndScalesWhenZoomed() {
        val base = BevProjectionMath.computeMetrics(1200f, 800f, 100f)
        val identity = BevCameraController.apply2DCamera(base, BevCameraState(is3D = false))
        assertEquals(base, identity)

        val zoomed = BevCameraController.apply2DCamera(base, BevCameraState(is3D = false, zoom2D = 2.0f))
        assertEquals(base.scalePxPerMeter * 2.0f, zoomed.scalePxPerMeter, 1e-4f)
    }

    @Test
    fun cameraPresetsSwitchTo3DWithExpectedPitchAzimuthAndDistance() {
        val ctrl = BevCameraController(BevCameraState(is3D = false))
        val top = ctrl.applyPreset(BevCameraPreset.TOP)
        assertTrue(top.is3D)
        assertEquals(80f, top.pitch3DDeg, 1e-4f)
        assertEquals(0f, top.yaw3DDeg, 1e-4f)

        val iso = ctrl.applyPreset(BevCameraPreset.ISO)
        assertTrue(iso.is3D)
        assertEquals(-38f, iso.yaw3DDeg, 1e-4f)
        assertEquals(38f, iso.pitch3DDeg, 1e-4f)

        val chase = ctrl.applyPreset(BevCameraPreset.CHASE)
        assertEquals(20f, chase.pitch3DDeg, 1e-4f)
        assertFalse("Chase preset matches default 3D pose", chase.isModifiedFromDefault)
    }
}
