package com.bajajauto.roadsense

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
}

