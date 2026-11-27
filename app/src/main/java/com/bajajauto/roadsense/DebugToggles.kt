package com.bajajauto.roadsense

import com.bajajauto.roadsense.ui.components.gl.BevColorMap
import com.bajajauto.roadsense.ui.components.gl.BevDecayPreset
import com.bajajauto.roadsense.ui.components.gl.BevPointColorMode
import com.bajajauto.roadsense.ui.components.gl.BevPointShape
import com.bajajauto.roadsense.ui.components.gl.BevPointSizePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Overlay rendering modes for Camera / SBS / Calibration viewfinders.
 */
enum class HudOverlayMode(val label: String) {
    /** Tracked objects (TLV 3) within 80 m rendered as ground lollipops + compact tags. */
    TRACKS_LOLLIPOP_80M("Tracks <80m"),
    /** Ultra-lightweight rectangular target boxes (TLV 3, <80 m) with minimal Skia cost. */
    SIMPLE_BOXES_80M("Boxes <80m"),
    /** Legacy diagnostic mode projecting raw clutter detections (<= 80 m). */
    RAW_POINTS_LEGACY("Raw Points")
}

/**
 * Snapshot of all reactive bench/debug toggles observed by Compose dashboards.
 */
data class DebugToggleSnapshot(
    val masterDebugEnabled: Boolean = DebugToggles.DEFAULT_MASTER_DEBUG,
    val displayPacerVerboseLogs: Boolean = true,
    val displayPacerDropWarnings: Boolean = true,
    val canvasTimingLogs: Boolean = true,
    val uiPerfProfilingLogs: Boolean = false,
    val uiPerfFrameJankToFileLogs: Boolean = false,
    val diskWriterPriorityLogs: Boolean = true,
    val bevShowRawPoints: Boolean = true,
    val bevShowTracks: Boolean = true,
    val bevShowClusters: Boolean = false,
    val bevShowGrid: Boolean = true,
    val useGlBevRenderer: Boolean = true,
    val bev3dMode: Boolean = false,
    val bevTrailEnabled: Boolean = false,
    val bevPointColorMode: BevPointColorMode = BevPointColorMode.AUTO,
    val bevColorMap: BevColorMap = BevColorMap.TURBO,
    val bevPointShape: BevPointShape = BevPointShape.CIRCLE,
    val bevPointSizePreset: BevPointSizePreset = BevPointSizePreset.MEDIUM,
    val bevDecayPreset: BevDecayPreset = BevDecayPreset.OFF,
    val bevStixelsEnabled: Boolean = true,
    val bevShowRoadLanes: Boolean = true,
    val bevHideOutliers: Boolean = false,
    val bevFlipZ: Boolean = false,
    val hudOverlayMode: HudOverlayMode = HudOverlayMode.TRACKS_LOLLIPOP_80M,
    val canedgeVerboseLogs: Boolean = false,
    val sessionLifecycleDebugLogs: Boolean = false
)

/**
 * Master centralized configuration for all RoadSense runtime & bench debug toggles.
 *
 * Flipping [DEFAULT_MASTER_DEBUG] (or calling [setMasterDebugEnabled]) acts as the
 * single master kill-switch for all high-frequency diagnostic telemetry and logcat/file
 * timing emissions (`DisplayPacer`, `[DRAW_BEV]`, `[DRAW_OVERLAY]`, verbose jank logs).
 */
object DebugToggles {

    /**
     * MASTER KILL-SWITCH DEFAULT.
     * Keep `false` for normal road/drive sessions to prevent 40-80 Hz disk flush spam.
     * Set `true` (or toggle at runtime on bench) to enable high-frequency timing traces.
     */
    const val DEFAULT_MASTER_DEBUG: Boolean = false

    @Volatile
    var masterDebugEnabled: Boolean = DEFAULT_MASTER_DEBUG
        private set

    @Volatile
    var displayPacerVerboseLogs: Boolean = true
        private set

    @Volatile
    var displayPacerDropWarnings: Boolean = true
        private set

    @Volatile
    var canvasTimingLogs: Boolean = true
        private set

    @Volatile
    var uiPerfProfilingLogs: Boolean = false
        private set

    @Volatile
    var uiPerfFrameJankToFileLogs: Boolean = false
        private set

    @Volatile
    var diskWriterPriorityLogs: Boolean = true
        private set

    // --- BEV / Viewfinder Bench Isolation Toggles ---

    @Volatile
    var bevShowRawPoints: Boolean = true
        private set

    @Volatile
    var bevShowTracks: Boolean = true
        private set

    @Volatile
    var bevShowClusters: Boolean = false
        private set

    @Volatile
    var bevShowGrid: Boolean = true
        private set

    @Volatile
    var useGlBevRenderer: Boolean = true
        private set

    @Volatile
    var bev3dMode: Boolean = false
        private set

    @Volatile
    var bevTrailEnabled: Boolean = false
        private set

    @Volatile
    var bevPointColorMode: BevPointColorMode = BevPointColorMode.AUTO
        private set

    @Volatile
    var bevColorMap: BevColorMap = BevColorMap.TURBO
        private set

    @Volatile
    var bevPointShape: BevPointShape = BevPointShape.CIRCLE
        private set

    @Volatile
    var bevPointSizePreset: BevPointSizePreset = BevPointSizePreset.MEDIUM
        private set

    @Volatile
    var bevDecayPreset: BevDecayPreset = BevDecayPreset.OFF
        private set

    @Volatile
    var bevStixelsEnabled: Boolean = true
        private set

    @Volatile
    var bevShowRoadLanes: Boolean = true
        private set

    @Volatile
    var bevHideOutliers: Boolean = false
        private set

    @Volatile
    var bevFlipZ: Boolean = false
        private set

    @Volatile
    var hudOverlayMode: HudOverlayMode = HudOverlayMode.TRACKS_LOLLIPOP_80M
        private set

    @Volatile
    var canedgeVerboseLogs: Boolean = false
        private set

    @Volatile
    var sessionLifecycleDebugLogs: Boolean = false
        private set

    private val _state = MutableStateFlow(DebugToggleSnapshot())
    val state: StateFlow<DebugToggleSnapshot> = _state.asStateFlow()

    /** True only when master debug is active AND DisplayPacer 40 Hz verbose logs are enabled. */
    val isDisplayPacerVerboseActive: Boolean
        get() = masterDebugEnabled && displayPacerVerboseLogs

    /** True when `[DRAW_BEV]` and `[DRAW_OVERLAY]` timing logs should emit for bench scripts. */
    val isCanvasTimingActive: Boolean
        get() = masterDebugEnabled && canvasTimingLogs

    /** True when RecompAudit 5s tables and verbose `[JANK_FRAME]` logcat traces should emit. */
    val isUiPerfProfilingActive: Boolean
        get() = uiPerfProfilingLogs || masterDebugEnabled

    /** True when `[JANK_FRAME]` >24ms entries should be mirrored into `session_debug.log`. */
    val isUiPerfJankToFileActive: Boolean
        get() = masterDebugEnabled && uiPerfFrameJankToFileLogs

    /** True when disk-writer worker OS/Java thread priority verification logs should emit to `session_debug.log`. */
    val isDiskWriterPriorityLogsActive: Boolean
        get() = masterDebugEnabled && diskWriterPriorityLogs

    /** True when CANedge2 verbose `[CAN_DIAG]` / poll debug logs should emit without forcing 40 Hz Canvas logs. */
    val isCanedgeVerboseActive: Boolean
        get() = canedgeVerboseLogs || masterDebugEnabled

    /** True when recording start/warmup/stop/audit & CAN transition verification logs should emit. */
    val isSessionLifecycleDebugActive: Boolean
        get() = sessionLifecycleDebugLogs || masterDebugEnabled

    fun setMasterDebugEnabled(enabled: Boolean) {
        masterDebugEnabled = enabled
        publishState()
    }

    fun setDisplayPacerVerboseLogs(enabled: Boolean) {
        displayPacerVerboseLogs = enabled
        publishState()
    }

    fun setDisplayPacerDropWarnings(enabled: Boolean) {
        displayPacerDropWarnings = enabled
        publishState()
    }

    fun setCanvasTimingLogs(enabled: Boolean) {
        canvasTimingLogs = enabled
        publishState()
    }

    fun setUiPerfProfilingLogs(enabled: Boolean) {
        uiPerfProfilingLogs = enabled
        publishState()
    }

    fun setUiPerfFrameJankToFileLogs(enabled: Boolean) {
        uiPerfFrameJankToFileLogs = enabled
        publishState()
    }

    fun setDiskWriterPriorityLogs(enabled: Boolean) {
        diskWriterPriorityLogs = enabled
        publishState()
    }

    /** Convenience one-tap helper for UI bench toggles: arms both master & canvas/pacer traces. */
    fun setBenchTimingLogging(enabled: Boolean) {
        masterDebugEnabled = enabled
        displayPacerVerboseLogs = enabled
        canvasTimingLogs = enabled
        publishState()
    }

    fun setBevLayerToggles(
        showRawPoints: Boolean = bevShowRawPoints,
        showTracks: Boolean = bevShowTracks,
        showClusters: Boolean = bevShowClusters,
        showGrid: Boolean = bevShowGrid
    ) {
        bevShowRawPoints = showRawPoints
        bevShowTracks = showTracks
        bevShowClusters = showClusters
        bevShowGrid = showGrid
        publishState()
    }

    fun setUseGlBevRenderer(enabled: Boolean) {
        useGlBevRenderer = enabled
        publishState()
    }

    fun setBev3dMode(enabled: Boolean) {
        bev3dMode = enabled
        publishState()
    }

    fun setBevTrailEnabled(enabled: Boolean) {
        bevTrailEnabled = enabled
        bevDecayPreset = if (enabled) {
            if (bevDecayPreset == BevDecayPreset.OFF) BevDecayPreset.MS_200 else bevDecayPreset
        } else {
            BevDecayPreset.OFF
        }
        publishState()
    }

    fun setBevPointColorMode(mode: BevPointColorMode) {
        bevPointColorMode = mode
        publishState()
    }

    fun setBevColorMap(colorMap: BevColorMap) {
        bevColorMap = colorMap
        publishState()
    }

    fun setBevPointShape(shape: BevPointShape) {
        bevPointShape = shape
        publishState()
    }

    fun setBevPointSizePreset(size: BevPointSizePreset) {
        bevPointSizePreset = size
        publishState()
    }

    fun setBevDecayPreset(preset: BevDecayPreset) {
        bevDecayPreset = preset
        bevTrailEnabled = preset != BevDecayPreset.OFF
        publishState()
    }

    fun setBevStixelsEnabled(enabled: Boolean) {
        bevStixelsEnabled = enabled
        publishState()
    }

    fun setBevShowRoadLanes(enabled: Boolean) {
        bevShowRoadLanes = enabled
        publishState()
    }

    fun setBevHideOutliers(enabled: Boolean) {
        bevHideOutliers = enabled
        publishState()
    }

    fun setBevFlipZ(enabled: Boolean) {
        bevFlipZ = enabled
        publishState()
    }

    fun setHudOverlayMode(mode: HudOverlayMode) {
        hudOverlayMode = mode
        publishState()
    }

    fun setCanedgeVerboseLogs(enabled: Boolean) {
        canedgeVerboseLogs = enabled
        publishState()
    }

    fun setSessionLifecycleDebugLogs(enabled: Boolean) {
        sessionLifecycleDebugLogs = enabled
        publishState()
    }

    fun resetDefaults() {
        masterDebugEnabled = DEFAULT_MASTER_DEBUG
        displayPacerVerboseLogs = true
        displayPacerDropWarnings = true
        canvasTimingLogs = true
        uiPerfProfilingLogs = false
        uiPerfFrameJankToFileLogs = false
        diskWriterPriorityLogs = true
        bevShowRawPoints = true
        bevShowTracks = true
        bevShowClusters = false
        bevShowGrid = true
        useGlBevRenderer = true
        bev3dMode = false
        bevTrailEnabled = false
        bevPointColorMode = BevPointColorMode.AUTO
        bevColorMap = BevColorMap.TURBO
        bevPointShape = BevPointShape.CIRCLE
        bevPointSizePreset = BevPointSizePreset.MEDIUM
        bevDecayPreset = BevDecayPreset.OFF
        bevStixelsEnabled = true
        bevShowRoadLanes = true
        bevHideOutliers = false
        bevFlipZ = false
        hudOverlayMode = HudOverlayMode.TRACKS_LOLLIPOP_80M
        canedgeVerboseLogs = false
        sessionLifecycleDebugLogs = false
        publishState()
    }

    private fun publishState() {
        _state.value = DebugToggleSnapshot(
            masterDebugEnabled = masterDebugEnabled,
            displayPacerVerboseLogs = displayPacerVerboseLogs,
            displayPacerDropWarnings = displayPacerDropWarnings,
            canvasTimingLogs = canvasTimingLogs,
            uiPerfProfilingLogs = uiPerfProfilingLogs,
            uiPerfFrameJankToFileLogs = uiPerfFrameJankToFileLogs,
            diskWriterPriorityLogs = diskWriterPriorityLogs,
            bevShowRawPoints = bevShowRawPoints,
            bevShowTracks = bevShowTracks,
            bevShowClusters = bevShowClusters,
            bevShowGrid = bevShowGrid,
            useGlBevRenderer = useGlBevRenderer,
            bev3dMode = bev3dMode,
            bevTrailEnabled = bevTrailEnabled,
            bevPointColorMode = bevPointColorMode,
            bevColorMap = bevColorMap,
            bevPointShape = bevPointShape,
            bevPointSizePreset = bevPointSizePreset,
            bevDecayPreset = bevDecayPreset,
            bevStixelsEnabled = bevStixelsEnabled,
            bevShowRoadLanes = bevShowRoadLanes,
            bevHideOutliers = bevHideOutliers,
            bevFlipZ = bevFlipZ,
            hudOverlayMode = hudOverlayMode,
            canedgeVerboseLogs = canedgeVerboseLogs,
            sessionLifecycleDebugLogs = sessionLifecycleDebugLogs
        )
    }
}

