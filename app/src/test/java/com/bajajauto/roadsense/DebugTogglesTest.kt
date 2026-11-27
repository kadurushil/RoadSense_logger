package com.bajajauto.roadsense

import com.bajajauto.roadsense.ui.components.gl.BevColorMap
import com.bajajauto.roadsense.ui.components.gl.BevDecayPreset
import com.bajajauto.roadsense.ui.components.gl.BevPointColorMode
import com.bajajauto.roadsense.ui.components.gl.BevPointShape
import com.bajajauto.roadsense.ui.components.gl.BevPointSizePreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugTogglesTest {

    @Before
    fun setUp() {
        DebugToggles.resetDefaults()
    }

    @After
    fun tearDown() {
        DebugToggles.resetDefaults()
    }

    @Test
    fun defaultsDisableNoisyHighFrequencyLogs() {
        assertFalse("Master debug switch must default OFF", DebugToggles.masterDebugEnabled)
        assertFalse("DisplayPacer 40 Hz logs must be inactive by default", DebugToggles.isDisplayPacerVerboseActive)
        assertFalse("Canvas timing logs must be inactive by default", DebugToggles.isCanvasTimingActive)
        assertFalse("UI jank file mirroring must be inactive by default", DebugToggles.isUiPerfJankToFileActive)
        assertTrue("Rare drop warnings remain active", DebugToggles.displayPacerDropWarnings)
        assertFalse("Clusters default OFF to keep live BEV uncluttered", DebugToggles.bevShowClusters)
        assertEquals(HudOverlayMode.TRACKS_LOLLIPOP_80M, DebugToggles.hudOverlayMode)
    }

    @Test
    fun masterSwitchControlsVerboseDiagnostics() {
        DebugToggles.setMasterDebugEnabled(true)
        assertTrue(DebugToggles.isDisplayPacerVerboseActive)
        assertTrue(DebugToggles.isCanvasTimingActive)
        assertFalse("UI jank file mirror stays explicit opt-in", DebugToggles.isUiPerfJankToFileActive)

        DebugToggles.setUiPerfFrameJankToFileLogs(true)
        assertTrue(DebugToggles.isUiPerfJankToFileActive)

        // Turning off master kill switch suppresses all verbose logs regardless of sub-toggles
        DebugToggles.setMasterDebugEnabled(false)
        assertFalse(DebugToggles.isDisplayPacerVerboseActive)
        assertFalse(DebugToggles.isCanvasTimingActive)
        assertFalse(DebugToggles.isUiPerfJankToFileActive)
    }

    @Test
    fun benchTimingHelperUpdatesReactiveStateFlow() {
        DebugToggles.setBenchTimingLogging(true)
        val snap = DebugToggles.state.value
        assertTrue(snap.masterDebugEnabled)
        assertTrue(snap.displayPacerVerboseLogs)
        assertTrue(snap.canvasTimingLogs)

        DebugToggles.setBevLayerToggles(showRawPoints = false, showGrid = false)
        assertFalse(DebugToggles.bevShowRawPoints)
        assertFalse(DebugToggles.state.value.bevShowGrid)
        assertTrue(DebugToggles.state.value.bevShowTracks)
    }

    @Test
    fun canedgeVerboseToggleWorksIndependentlyOfMasterSwitch() {
        assertFalse("CANedge verbose logs must default OFF", DebugToggles.isCanedgeVerboseActive)
        DebugToggles.setCanedgeVerboseLogs(true)
        assertTrue(DebugToggles.isCanedgeVerboseActive)
        assertTrue(DebugToggles.state.value.canedgeVerboseLogs)
        assertFalse("Master switch stays OFF so 40 Hz Canvas logs stay silent", DebugToggles.isCanvasTimingActive)

        DebugToggles.setCanedgeVerboseLogs(false)
        assertFalse(DebugToggles.isCanedgeVerboseActive)

        DebugToggles.setMasterDebugEnabled(true)
        assertTrue("Master debug switch also activates CANedge verbose logs", DebugToggles.isCanedgeVerboseActive)
    }

    @Test
    fun uiPerfProfilingToggleDefaultsOffAndCanArmWithoutMasterDebug() {
        assertFalse("UI perf profiling logs must default OFF", DebugToggles.isUiPerfProfilingActive)
        DebugToggles.setUiPerfProfilingLogs(true)
        assertTrue(DebugToggles.isUiPerfProfilingActive)
        assertTrue(DebugToggles.state.value.uiPerfProfilingLogs)
        assertFalse("Master switch stays OFF so 40 Hz Canvas logs stay silent", DebugToggles.isCanvasTimingActive)

        DebugToggles.setUiPerfProfilingLogs(false)
        assertFalse(DebugToggles.isUiPerfProfilingActive)
    }

    @Test
    fun useGlBevRendererDefaultsTrueAndSwitchesReactively() {
        assertTrue("OpenGL ES 3.0 BEV renderer must default ON", DebugToggles.useGlBevRenderer)
        assertTrue(DebugToggles.state.value.useGlBevRenderer)

        DebugToggles.setUseGlBevRenderer(false)
        assertFalse(DebugToggles.useGlBevRenderer)
        assertFalse(DebugToggles.state.value.useGlBevRenderer)

        DebugToggles.resetDefaults()
        assertTrue(DebugToggles.useGlBevRenderer)
        assertTrue(DebugToggles.state.value.useGlBevRenderer)
    }

    @Test
    fun bev3dModeAndTrailDefaultOffAndToggleReactively() {
        assertFalse(DebugToggles.bev3dMode)
        assertFalse(DebugToggles.bevTrailEnabled)

        DebugToggles.setBev3dMode(true)
        DebugToggles.setBevTrailEnabled(true)
        assertTrue(DebugToggles.state.value.bev3dMode)
        assertTrue(DebugToggles.state.value.bevTrailEnabled)

        DebugToggles.resetDefaults()
        assertFalse(DebugToggles.state.value.bev3dMode)
        assertFalse(DebugToggles.state.value.bevTrailEnabled)
    }

    @Test
    fun foxglovePanelTogglesUpdateAndResetCleanly() {
        assertEquals(BevPointColorMode.AUTO, DebugToggles.state.value.bevPointColorMode)
        assertEquals(BevColorMap.TURBO, DebugToggles.state.value.bevColorMap)
        assertTrue(DebugToggles.state.value.bevStixelsEnabled)
        assertTrue(DebugToggles.state.value.bevShowRoadLanes)
        assertFalse(DebugToggles.state.value.bevHideOutliers)

        DebugToggles.setBevPointColorMode(BevPointColorMode.SNR)
        DebugToggles.setBevColorMap(BevColorMap.RAINBOW)
        DebugToggles.setBevPointShape(BevPointShape.SQUARE)
        DebugToggles.setBevPointSizePreset(BevPointSizePreset.LARGE)
        DebugToggles.setBevDecayPreset(BevDecayPreset.SEC_1)
        DebugToggles.setBevStixelsEnabled(false)
        DebugToggles.setBevShowRoadLanes(false)
        DebugToggles.setBevHideOutliers(true)
        DebugToggles.setBevFlipZ(true)

        val snap = DebugToggles.state.value
        assertEquals(BevPointColorMode.SNR, snap.bevPointColorMode)
        assertEquals(BevColorMap.RAINBOW, snap.bevColorMap)
        assertEquals(BevPointShape.SQUARE, snap.bevPointShape)
        assertEquals(BevPointSizePreset.LARGE, snap.bevPointSizePreset)
        assertEquals(BevDecayPreset.SEC_1, snap.bevDecayPreset)
        assertTrue(snap.bevTrailEnabled)
        assertFalse(snap.bevStixelsEnabled)
        assertFalse(snap.bevShowRoadLanes)
        assertTrue(snap.bevHideOutliers)
        assertTrue(snap.bevFlipZ)

        DebugToggles.resetDefaults()
        assertEquals(BevPointColorMode.AUTO, DebugToggles.state.value.bevPointColorMode)
        assertEquals(BevDecayPreset.OFF, DebugToggles.state.value.bevDecayPreset)
        assertTrue(DebugToggles.state.value.bevStixelsEnabled)
        assertTrue(DebugToggles.state.value.bevShowRoadLanes)
        assertFalse(DebugToggles.state.value.bevFlipZ)
    }
}

