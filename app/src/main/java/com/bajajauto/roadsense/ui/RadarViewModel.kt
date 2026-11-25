package com.bajajauto.roadsense.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.bajajauto.roadsense.DebugToggleSnapshot
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.HudOverlayMode
import com.bajajauto.roadsense.acquisition.RadarConnectionManager
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.camera.CameraEngine
import com.bajajauto.roadsense.camera.CameraFrameRate
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.decoding.RadarPacketAssembler
import com.bajajauto.roadsense.decoding.RadarTlvDecoder
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.gnss.GnssLocationManager
import com.bajajauto.roadsense.gnss.GnssState
import com.bajajauto.roadsense.diagnostics.CrashRecoveryHandler
import com.bajajauto.roadsense.diagnostics.ThermalMonitor
import com.bajajauto.roadsense.diagnostics.ThermalSeverity
import com.bajajauto.roadsense.recording.CameraSessionRecorder
import com.bajajauto.roadsense.recording.EventBookmarkToast
import com.bajajauto.roadsense.recording.EventBookmarkType
import com.bajajauto.roadsense.recording.GnssSessionRecorder
import com.bajajauto.roadsense.recording.ImuSessionRecorder
import com.bajajauto.roadsense.recording.RadarSessionRecorder
import com.bajajauto.roadsense.recording.RawUartRecorder
import com.bajajauto.roadsense.recording.RecordingForegroundService
import com.bajajauto.roadsense.recording.RecordingState
import com.bajajauto.roadsense.recording.SessionInfo
import com.bajajauto.roadsense.recording.SessionSyncAuditor
import com.bajajauto.roadsense.recording.SessionTags
import com.bajajauto.roadsense.recording.StorageGuard
import com.bajajauto.roadsense.recording.StorageHealth
import com.bajajauto.roadsense.recording.StorageStatus
import com.bajajauto.roadsense.sync.CrossSensorSyncAssociator
import com.bajajauto.roadsense.sync.LiveSyncTelemetry
import com.bajajauto.roadsense.sync.RadarDisplayPacer
import com.bajajauto.roadsense.canedge.discovery.CanedgeDiscovery
import com.bajajauto.roadsense.canedge.ingestion.CanedgeIngestionManager
import com.bajajauto.roadsense.canedge.model.CanedgeConnectionState
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.canedge.model.CanedgeUnifiedFileItem
import com.bajajauto.roadsense.canedge.network.CanedgeHttpClient
import com.bajajauto.roadsense.canedge.repository.CanedgeRepository
import com.bajajauto.roadsense.recording.SessionManager
import com.bajajauto.roadsense.recording.SessionRecordingState
import com.bajajauto.roadsense.recording.SessionTimelineWriter
import com.bajajauto.roadsense.imu.ImuManager
import com.bajajauto.roadsense.imu.ImuRatePreset
import com.bajajauto.roadsense.imu.ImuSamplingBenchmark
import com.bajajauto.roadsense.imu.ImuSensorCapability
import com.bajajauto.roadsense.imu.ImuTelemetryState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.bajajauto.roadsense.fusion.engine.CameraIntrinsics
import com.bajajauto.roadsense.fusion.engine.CameraIntrinsicsProvider
import com.bajajauto.roadsense.fusion.engine.SpatialProjectionEngine
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.fusion.storage.CalibrationStorageManager
import androidx.compose.runtime.Immutable

@Immutable
data class RadarUiTelemetry(
    val totalBytes: Long = 0L,
    val totalPackets: Long = 0L,
    val pointsCount: Int = 0,
    val clustersCount: Int = 0,
    val tracksCount: Int = 0,
    val dataRateKbps: Float = 0f,
    val lastFrameSeq: Long = 0L
)

@Immutable
data class LiveMetricsSnapshot(
    val radarHz: Float = 0.0f,
    val cameraFps: Float = 0.0f,
    val gnssHz: Float = 0.0f,
    val imuHz: Float = 0.0f,
    val satellitesUsed: Int = 0,
    val satellitesInView: Int = 0,
    val accuracyMeters: Float = 0.0f,
    val batteryPct: Int = 0,
    val batteryTempC: Float = 0.0f,
    val cpuUsagePct: Int = 0,
    val cpuTempC: Float? = null,
    val syncGrade: String = "IDLE",
    val syncDeltaMs: Float = 0.0f,
    val isSyncActive: Boolean = false,
    val canHudPhase: com.bajajauto.roadsense.canedge.model.CanedgeHudPhase = com.bajajauto.roadsense.canedge.model.CanedgeHudPhase.OFFLINE,
    val canHudLabel: String = "OFF",
    val radarPill: SensorPillStatus = SensorReadinessEvaluator.evaluateRadar(
        com.bajajauto.roadsense.acquisition.RadarConnectionState.Disconnected, 0f, false, false
    ),
    val camPill: SensorPillStatus = SensorReadinessEvaluator.evaluateCamera(
        com.bajajauto.roadsense.camera.CameraEngineState.Closed, 0f
    ),
    val gpsPill: SensorPillStatus = SensorReadinessEvaluator.evaluateGnss(
        com.bajajauto.roadsense.gnss.GnssState.Disabled, 0f
    ),
    val imuPill: SensorPillStatus = SensorReadinessEvaluator.evaluateImu(0f, false, false),
    val canPill: SensorPillStatus = SensorReadinessEvaluator.evaluateCan(
        com.bajajauto.roadsense.canedge.model.CanedgeSyncStats()
    ),
    val thermalSeverity: ThermalSeverity = ThermalSeverity.NOMINAL,
    val storageStatus: StorageStatus = StorageStatus(-1L, StorageHealth.OK)
)

@Immutable
data class PostDriveSummaryData(
    val sessionId: String,
    val durationMs: Long,
    val radarFrames: Long,
    val radarSalvaged: Long,
    val radarDrops: Long,
    val cameraFrames: Long,
    val imuFrames: Long,
    val gnssFixes: Long,
    val bookmarksCount: Int,
    val audit: SessionSyncAuditor.AuditResult?,
    val warnings: List<String>,
    val canPendingCount: Int,
    val canStatusLabel: String,
    val peakThermalSeverity: String,
    val peakBatteryTempC: Float,
    val sessionTags: SessionTags
)

@androidx.compose.runtime.Stable
class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val appPreferences = com.bajajauto.roadsense.storage.AppPreferences(application)
    private val sessionManager = SessionManager(application)
    private val connectionManager = RadarConnectionManager(application)
    private val packetAssembler = RadarPacketAssembler()
    private val tlvDecoder = RadarTlvDecoder()
    private val rawRecorder = RawUartRecorder(application)
    private val clockDiscipliner = com.bajajauto.roadsense.sync.RadarClockDiscipliner()
    private data class RawUartChunk(
        val bytes: ByteArray,
        val hostMonoNs: Long,
        val hostWallMs: Long
    )

    // Bounded queue decoupling USB serial reception from frame processing/decoding (< 2 µs enqueue)
    private val rawByteQueue = java.util.concurrent.ArrayBlockingQueue<RawUartChunk>(2048)
    @Volatile private var isRadarWorkerRunning = true
    private var radarProcessingThread: Thread? = null
    val syncAssociator = CrossSensorSyncAssociator()
    val liveSyncTelemetry: StateFlow<LiveSyncTelemetry> = syncAssociator.liveSyncTelemetry

    private val sessionRecorder = RadarSessionRecorder(application, sessionManager, syncAssociator)
    private val gnssLocationManager = GnssLocationManager(application)
    private val gnssSessionRecorder = GnssSessionRecorder(sessionManager)
    val cameraEngine = CameraEngine(application)
    private val cameraSessionRecorder = CameraSessionRecorder(sessionManager, syncAssociator)
    val imuManager = ImuManager(application)
    val imuSessionRecorder = ImuSessionRecorder(sessionManager, syncAssociator)

    private val _latestSessionAudit = MutableStateFlow<SessionSyncAuditor.AuditResult?>(null)
    val latestSessionAudit: StateFlow<SessionSyncAuditor.AuditResult?> = _latestSessionAudit.asStateFlow()

    // CANedge2 Network & Ingestion Engine
    private val canedgeHttpClient = CanedgeHttpClient()
    private val canedgeRepository = CanedgeRepository(canedgeHttpClient)
    val canedgeDiscovery = CanedgeDiscovery(application, canedgeHttpClient, appPreferences)
    val canedgeIngestionManager = CanedgeIngestionManager(
        discovery = canedgeDiscovery,
        repository = canedgeRepository,
        httpClient = canedgeHttpClient,
        sessionManager = sessionManager,
        appPreferences = appPreferences
    ).also { it.startWatchdog() }

    val canedgeConnectionState: StateFlow<CanedgeConnectionState> = canedgeDiscovery.connectionState
    val canedgeSyncStats: StateFlow<CanedgeSyncStats> = canedgeIngestionManager.stats
    val canedgeRemoteFiles: StateFlow<List<CanedgeFile>> = canedgeIngestionManager.remoteFiles
    val canedgeLocalSyncedFiles: StateFlow<List<File>> = canedgeIngestionManager.localSyncedFiles
    val canedgeLocalSessionFiles: StateFlow<List<File>> = canedgeIngestionManager.localSessionFiles
    val canedgeUnifiedFiles: StateFlow<List<CanedgeUnifiedFileItem>> = canedgeIngestionManager.unifiedFiles

    // Sensor Fusion & Camera Calibration
    private val calibrationStorage = CalibrationStorageManager(application)
    private val _calibrationParams = MutableStateFlow(CalibrationParameters())
    val calibrationParams: StateFlow<CalibrationParameters> = _calibrationParams.asStateFlow()

    private val _savedBaselineParams = MutableStateFlow<CalibrationParameters?>(null)
    val savedBaselineParams: StateFlow<CalibrationParameters?> = _savedBaselineParams.asStateFlow()

    private val _isCalibrationFullScreen = MutableStateFlow(false)
    val isCalibrationFullScreen: StateFlow<Boolean> = _isCalibrationFullScreen.asStateFlow()

    private val _isCameraFullScreen = MutableStateFlow(false)
    val isCameraFullScreen: StateFlow<Boolean> = _isCameraFullScreen.asStateFlow()

    private val _isSbsFullScreen = MutableStateFlow(false)
    val isSbsFullScreen: StateFlow<Boolean> = _isSbsFullScreen.asStateFlow()

    private val _isRadarOverlayEnabled = MutableStateFlow(true)
    val isRadarOverlayEnabled: StateFlow<Boolean> = _isRadarOverlayEnabled.asStateFlow()

    private val _isRadarArcsEnabled = MutableStateFlow(true)
    val isRadarArcsEnabled: StateFlow<Boolean> = _isRadarArcsEnabled.asStateFlow()

    private val _isLiveImuHorizonEnabled = MutableStateFlow(false)
    val isLiveImuHorizonEnabled: StateFlow<Boolean> = _isLiveImuHorizonEnabled.asStateFlow()
    private var imuMonitoringStartedByCalibration = false

    private val _activeIntrinsics = MutableStateFlow<CameraIntrinsics?>(null)
    val activeIntrinsics: StateFlow<CameraIntrinsics?> = _activeIntrinsics.asStateFlow()

    val connectionState: StateFlow<RadarConnectionState> = connectionManager.connectionState
    val recordingState: StateFlow<RecordingState> = rawRecorder.recordingState
    val sessionRecordingState: StateFlow<SessionRecordingState> = sessionRecorder.recordingState

    val gnssState: StateFlow<GnssState> = gnssLocationManager.gnssState
    val latestGnssFix: StateFlow<GnssFix?> = gnssLocationManager.latestFix
    val totalGnssFixes: StateFlow<Long> = gnssLocationManager.totalFixes
    val isGnssRecording: StateFlow<Boolean> = gnssSessionRecorder.isRecording
    val gnssSessionFixes: StateFlow<Long> = gnssSessionRecorder.fixesRecorded

    // IMU Subsystem State Flows
    val imuCapabilities: StateFlow<List<ImuSensorCapability>> = imuManager.capabilities
    val imuBenchmarkStats: StateFlow<ImuSamplingBenchmark> = imuManager.benchmarkStats
    val imuTelemetry: StateFlow<ImuTelemetryState> = imuManager.telemetryState
    val imuHz: StateFlow<Float> = imuManager.imuHz
    val isImuRecording: StateFlow<Boolean> = imuSessionRecorder.isRecording
    val imuSessionFrames: StateFlow<Long> = imuSessionRecorder.framesRecorded

    val cameraEngineState = cameraEngine.engineState
    val selectedResolution = cameraEngine.selectedResolution
    val selectedFps = cameraEngine.selectedFps
    val isInfinityFocusLocked = cameraEngine.isInfinityFocusLocked
    val cameraSessionFrames: StateFlow<Long> = cameraSessionRecorder.framesRecorded

    private val _rawHexData = MutableStateFlow("No data received yet. Connect to radar and start stream.")
    val rawHexData: StateFlow<String> = _rawHexData.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val _totalPackets = MutableStateFlow(0L)
    val totalPackets: StateFlow<Long> = _totalPackets.asStateFlow()

    private val _radarUiTelemetry = MutableStateFlow(RadarUiTelemetry())
    val radarUiTelemetry: StateFlow<RadarUiTelemetry> = _radarUiTelemetry.asStateFlow()

    private val _latestPacket = MutableStateFlow<RawRadarPacket?>(null)
    val latestPacket: StateFlow<RawRadarPacket?> = _latestPacket.asStateFlow()

    val displayPacer = RadarDisplayPacer(
        frameIntervalMs = 50L,
        bufferDelayMs = 50L,
        maxQueueDepth = 3
    )
    val latestFrame: StateFlow<RadarFrame?> = displayPacer.latestFrame

    private val _isHexPreviewEnabled = MutableStateFlow(false)
    val isHexPreviewEnabled: StateFlow<Boolean> = _isHexPreviewEnabled.asStateFlow()

    private val hexBuilder = StringBuilder()
    private val MAX_HEX_CHARS = 4000

    // Telemetry and watermark monitoring
    @Volatile private var peakRawByteQueueDepth = 0
    @Volatile private var totalRawByteChunksEnqueued = 0L
    @Volatile private var totalRawByteChunksDropped = 0L
    @Volatile private var lastObservedRadarSeq = -1L
    @Volatile private var totalRadarSequenceGaps = 0L
    @Volatile private var totalRadarDroppedFrames = 0L
    @Volatile private var totalRadarSalvagedFrames = 0L
    @Volatile private var rawBytesReceivedCounter = 0L
    @Volatile private var rawPacketsReceivedCounter = 0L
    @Volatile private var latestFrameSeqNum = 0L
    @Volatile private var latestPointsNum = 0
    @Volatile private var latestClustersNum = 0
    @Volatile private var latestTracksNum = 0

    // Persistent UI settings
    private val _radarMaxRange = MutableStateFlow(appPreferences.radarMaxRange)
    val radarMaxRange: StateFlow<Float> = _radarMaxRange.asStateFlow()

    private val _radarDynamicOnly = MutableStateFlow(appPreferences.radarDynamicOnly)
    val radarDynamicOnly: StateFlow<Boolean> = _radarDynamicOnly.asStateFlow()

    private val _radarMinSnr = MutableStateFlow(appPreferences.radarMinSnrFilter)
    val radarMinSnr: StateFlow<Boolean> = _radarMinSnr.asStateFlow()

    val debugToggleState: StateFlow<DebugToggleSnapshot> = DebugToggles.state

    // Camera preview is always off/muted by default on every app launch (lag elimination).
    // While the app is running in the current session, the state stays as left by the user across cards.
    private val _isCameraPreviewMuted = MutableStateFlow(true)
    val isCameraPreviewMuted: StateFlow<Boolean> = _isCameraPreviewMuted.asStateFlow()

    // Multi-sensor live Hz rates & hardware metrics
    private val _radarHz = MutableStateFlow(0.0f)
    val radarHz: StateFlow<Float> = _radarHz.asStateFlow()

    private val _cameraFps = MutableStateFlow(0.0f)
    val cameraFps: StateFlow<Float> = _cameraFps.asStateFlow()

    private val _gnssHz = MutableStateFlow(0.0f)
    val gnssHz: StateFlow<Float> = _gnssHz.asStateFlow()

    private val _batteryPct = MutableStateFlow(0)
    val batteryPct: StateFlow<Int> = _batteryPct.asStateFlow()

    private val _batteryTempC = MutableStateFlow(0.0f)
    val batteryTempC: StateFlow<Float> = _batteryTempC.asStateFlow()

    private val _cpuUsagePct = MutableStateFlow(0)
    val cpuUsagePct: StateFlow<Int> = _cpuUsagePct.asStateFlow()

    private val _cpuTempC = MutableStateFlow<Float?>(null)
    val cpuTempC: StateFlow<Float?> = _cpuTempC.asStateFlow()

    private val _liveMetricsSnapshot = MutableStateFlow(LiveMetricsSnapshot())
    val liveMetricsSnapshot: StateFlow<LiveMetricsSnapshot> = _liveMetricsSnapshot.asStateFlow()

    private val _storageAlert = MutableStateFlow<String?>(null)
    val storageAlert: StateFlow<String?> = _storageAlert.asStateFlow()

    fun dismissStorageAlert() {
        _storageAlert.value = null
    }

    // Tier 2 Field UX StateFlows: Tags, Preflight, Event Bookmarks, Async Audit & Post-Drive Summary
    private val _sessionTags = MutableStateFlow(
        SessionTags(
            vehicleId = appPreferences.sessionVehicleId,
            dspFirmwareBuild = appPreferences.sessionDspFirmware,
            mountType = appPreferences.sessionMountType,
            lightingCondition = appPreferences.sessionLightingCondition,
            notes = ""
        )
    )
    val sessionTags: StateFlow<SessionTags> = _sessionTags.asStateFlow()

    private val _bookmarkHapticsEnabled = MutableStateFlow(appPreferences.bookmarkHapticsEnabled)
    val bookmarkHapticsEnabled: StateFlow<Boolean> = _bookmarkHapticsEnabled.asStateFlow()

    private val _promptPreflightBeforeRec = MutableStateFlow(appPreferences.promptPreflightBeforeRec)
    val promptPreflightBeforeRec: StateFlow<Boolean> = _promptPreflightBeforeRec.asStateFlow()

    private val _isSessionAuditing = MutableStateFlow(false)
    val isSessionAuditing: StateFlow<Boolean> = _isSessionAuditing.asStateFlow()

    private val _sessionBookmarkCount = MutableStateFlow(0)
    val sessionBookmarkCount: StateFlow<Int> = _sessionBookmarkCount.asStateFlow()

    private val _lastBookmarkToast = MutableStateFlow<EventBookmarkToast?>(null)
    val lastBookmarkToast: StateFlow<EventBookmarkToast?> = _lastBookmarkToast.asStateFlow()

    private val _postDriveSummary = MutableStateFlow<PostDriveSummaryData?>(null)
    val postDriveSummary: StateFlow<PostDriveSummaryData?> = _postDriveSummary.asStateFlow()

    private val activeSessionWarnings = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val activeAuditToken = java.util.concurrent.atomic.AtomicLong(0L)
    private var warmupJob: Job? = null
    private var bookmarkToastJob: Job? = null
    @Volatile private var imuStartedByWarmup = false

    private fun recordSessionWarning(warning: String) {
        if (sessionRecorder.currentSession != null && !activeSessionWarnings.contains(warning)) {
            activeSessionWarnings.add(warning)
        }
    }

    fun dismissPostDriveSummary() {
        _postDriveSummary.value = null
    }

    fun setSessionTags(tags: SessionTags) {
        _sessionTags.value = tags
        appPreferences.sessionVehicleId = tags.vehicleId
        appPreferences.sessionDspFirmware = tags.dspFirmwareBuild
        appPreferences.sessionMountType = tags.mountType
        appPreferences.sessionLightingCondition = tags.lightingCondition
    }

    fun setBookmarkHapticsEnabled(enabled: Boolean) {
        _bookmarkHapticsEnabled.value = enabled
        appPreferences.bookmarkHapticsEnabled = enabled
    }

    fun setPromptPreflightBeforeRec(enabled: Boolean) {
        _promptPreflightBeforeRec.value = enabled
        appPreferences.promptPreflightBeforeRec = enabled
    }

    fun setSessionLifecycleDebugLogs(enabled: Boolean) {
        DebugToggles.setSessionLifecycleDebugLogs(enabled)
        com.bajajauto.roadsense.logging.AppLogger.i(
            "LifecycleDiag",
            "[LIFECYCLE_DIAG] SESSION_LIFECYCLE_DEBUG_LOGS enabled=$enabled"
        )
    }

    /**
     * Stamps a driver/engineer event bookmark (`USER,EVENT_MARKER`) into `session_timeline.csv`
     * with nanosecond monotonic alignment while a recording session is active.
     */
    fun markEvent(
        type: EventBookmarkType = EventBookmarkType.EVENT,
        note: String = ""
    ) {
        val activeSession = sessionRecorder.currentSession ?: return
        val monoNs = SystemClock.elapsedRealtimeNanos()
        val seq = activeSession.totalEventBookmarks + 1
        activeSession.totalEventBookmarks = seq
        _sessionBookmarkCount.value = seq

        val sanitizedNote = note.replace(',', ';').replace('\n', ' ').trim()
        val summary = if (sanitizedNote.isNotEmpty()) {
            "type=${type.name};note=$sanitizedNote"
        } else {
            "type=${type.name}"
        }
        sessionManager.recordTimelineEvent(
            elapsedRealtimeNs = monoNs,
            sensor = "USER",
            event = "EVENT_MARKER",
            sequenceId = seq.toLong(),
            relativePath = "session_timeline.csv",
            summary = summary
        )
        com.bajajauto.roadsense.logging.AppLogger.i(
            "EventBookmark",
            "BOOKMARK #$seq: type=${type.name} monoNs=$monoNs session=${activeSession.sessionId}"
        )

        val elapsedSec = ((monoNs - activeSession.startTimeMonotonicNs) / 1_000_000_000L).coerceAtLeast(0L)
        val timeFmt = String.format(Locale.US, "%02d:%02d", elapsedSec / 60L, elapsedSec % 60L)
        _lastBookmarkToast.value = EventBookmarkToast(
            id = seq.toLong(),
            message = "#$seq ${type.shortLabel} • $timeFmt",
            accentHex = type.accentHex
        )
        bookmarkToastJob?.cancel()
        bookmarkToastJob = viewModelScope.launch {
            delay(1600L)
            _lastBookmarkToast.value = null
        }
    }

    private val sessionLifecycleLock = Any()
    @Volatile private var isAutoStoppingForStorage = false
    @Volatile private var lowStorageWarnLoggedInSession = false
    @Volatile private var currentStorageStatus = StorageStatus(-1L, StorageHealth.OK)
    @Volatile private var currentThermalSeverity = ThermalSeverity.NOMINAL
    @Volatile private var peakThermalSeverity = ThermalSeverity.NOMINAL
    @Volatile private var peakBatteryTempC = 0f

    private val thermalMonitor = ThermalMonitor(application) { severity, pmStatus ->
        if (severity.rank > peakThermalSeverity.rank && sessionRecorder.currentSession != null) {
            peakThermalSeverity = severity
        }
        val activeSession = sessionRecorder.currentSession
        if (activeSession != null && severity != currentThermalSeverity) {
            com.bajajauto.roadsense.logging.AppLogger.w(
                "Thermal",
                "THERMAL_CHANGE during recording: ${currentThermalSeverity.name} -> ${severity.name} (pmStatus=$pmStatus)"
            )
            sessionManager.recordTimelineEvent(
                elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                sensor = "SYSTEM",
                event = "THERMAL_CHANGE",
                sequenceId = 0L,
                relativePath = "session_debug.log",
                summary = "severity=${severity.name};pmStatus=$pmStatus"
            )
            if (severity.rank >= ThermalSeverity.MODERATE.rank) {
                recordSessionWarning("Thermal ${severity.name}")
            }
        }
        currentThermalSeverity = severity
    }

    @Volatile private var radarFramesThisSec = 0
    @Volatile private var cameraFramesThisSec = 0
    @Volatile private var firstCameraFrameInSecMonoNs = 0L
    @Volatile private var lastCameraFrameInSecMonoNs = 0L
    @Volatile private var gnssFixesThisSec = 0
    @Volatile private var radarWasConnectedInSession = false
    @Volatile private var radarStreamActiveInSession = false

    init {
        // Sync baud rate from connection manager to packet assembler for accurate SOF physical byte timing
        packetAssembler.baudRate = connectionManager.currentDataBaudRate

        RecordingForegroundService.onStopRequested = {
            if (sessionRecorder.currentSession != null) {
                stopSessionRecording()
            }
        }

        CrashRecoveryHandler.emergencyFlushCallback = {
            emergencyFlushActiveSession()
        }

        cameraEngine.onRecorderErrorListener = { errMsg ->
            val activeSession = sessionRecorder.currentSession
            if (activeSession != null) {
                recordSessionWarning("Camera recorder error: $errMsg")
                sessionManager.recordTimelineEvent(
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    sensor = "CAMERA",
                    event = "RECORDER_ERROR",
                    sequenceId = 0L,
                    relativePath = "camera/camera_video.mp4",
                    summary = "error=$errMsg"
                )
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    stopSessionRecording()
                }
            }
        }

        connectionManager.onUsbLifecycleEvent = { event, detail ->
            val activeSession = sessionRecorder.currentSession
            if (event == "USB_DETACHED") {
                syncAssociator.markRadarDisconnected()
                recordSessionWarning("Radar USB detached mid-drive")
            } else if (event == "USB_RECONNECTED") {
                rawByteQueue.clear()
                packetAssembler.reset()
                clockDiscipliner.reset()
                displayPacer.reset()
            }
            if (activeSession != null) {
                sessionManager.recordTimelineEvent(
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    sensor = "RADAR",
                    event = event,
                    sequenceId = 0L,
                    relativePath = "radar/radar_frames.bin",
                    summary = detail
                )
            }
        }

        // Restore persistent camera settings
        cameraEngine.setResolution(appPreferences.cameraResolution)
        cameraEngine.setFrameRate(appPreferences.cameraFrameRate)

        // Load persistent radar-camera calibration
        viewModelScope.launch {
            val loaded = calibrationStorage.loadCalibration()
            _calibrationParams.value = loaded
            _savedBaselineParams.value = loaded
        }

        // Restore persistent BEV & HUD overlay filters while keeping master timing logs governed by DebugToggles default
        DebugToggles.setBevLayerToggles(
            showRawPoints = appPreferences.bevShowRawPoints,
            showTracks = appPreferences.bevShowTracks,
            showClusters = appPreferences.bevShowClusters,
            showGrid = appPreferences.bevShowGrid
        )
        DebugToggles.setHudOverlayMode(appPreferences.hudOverlayMode)

        // Periodic 1-second ticker for multi-sensor rates & battery/CPU telemetry
        viewModelScope.launch(Dispatchers.Default) {
            var lastCpuTimeMs = android.os.Process.getElapsedCpuTime()
            var lastWallTimeMs = SystemClock.elapsedRealtime()
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            var tickCount = 0
            var cpuTempSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N

            while (isActive) {
                delay(1000)
                val rHz = radarFramesThisSec.toFloat()
                radarFramesThisSec = 0
                if (_radarHz.value != rHz) _radarHz.value = rHz

                val camCount = cameraFramesThisSec
                val camFirstNs = firstCameraFrameInSecMonoNs
                val camLastNs = lastCameraFrameInSecMonoNs
                cameraFramesThisSec = 0
                firstCameraFrameInSecMonoNs = 0L
                lastCameraFrameInSecMonoNs = 0L
                val cFps = if (camCount >= 3 && camFirstNs > 0L && camLastNs > camFirstNs) {
                    val spanSec = (camLastNs - camFirstNs) / 1_000_000_000f
                    // Avoid under-reporting a partial-second startup burst (e.g. 9 frames in 0.3s is 30fps, not 9fps)
                    if (spanSec in 0.06f..0.85f) {
                        ((camCount - 1) / spanSec).coerceIn(0f, 65f)
                    } else {
                        camCount.toFloat()
                    }
                } else {
                    camCount.toFloat()
                }
                if (_cameraFps.value != cFps) _cameraFps.value = cFps

                val gHz = gnssFixesThisSec.toFloat()
                gnssFixesThisSec = 0
                if (_gnssHz.value != gHz) _gnssHz.value = gHz

                // 1. Process CPU usage %
                val curCpuTimeMs = android.os.Process.getElapsedCpuTime()
                val curWallTimeMs = SystemClock.elapsedRealtime()
                val dCpu = curCpuTimeMs - lastCpuTimeMs
                val dWall = curWallTimeMs - lastWallTimeMs
                var usage = _cpuUsagePct.value
                if (dWall > 0) {
                    usage = ((dCpu.toFloat() / (dWall * numCores)) * 100f).toInt().coerceIn(0, 100)
                    if (_cpuUsagePct.value != usage) _cpuUsagePct.value = usage
                }
                lastCpuTimeMs = curCpuTimeMs
                lastWallTimeMs = curWallTimeMs

                // 2. Battery & storage telemetry (poll every 10s to avoid sticky-broadcast Binder IPC every 1s)
                var batPct = _batteryPct.value
                var batTemp = _batteryTempC.value
                var diskStatus = currentStorageStatus
                if (tickCount % 10 == 0) {
                    try {
                        val bIntent = application.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        if (bIntent != null) {
                            val level = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                            val scale = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                            if (level >= 0 && scale > 0) {
                                batPct = (level * 100 / scale.toFloat()).toInt()
                                if (_batteryPct.value != batPct) _batteryPct.value = batPct
                            }
                            val tempTenths = bIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)
                            if (tempTenths > 0) {
                                batTemp = tempTenths / 10.0f
                                if (_batteryTempC.value != batTemp) _batteryTempC.value = batTemp
                            }
                        }
                    } catch (_: Exception) {}

                    diskStatus = StorageGuard.evaluate(StorageGuard.queryFreeMb(sessionManager.sessionsBaseDir))
                    currentStorageStatus = diskStatus
                    val activeRecSession = sessionRecorder.currentSession
                    if (activeRecSession != null) {
                        when (diskStatus.health) {
                            StorageHealth.LOW_WARN -> {
                                if (!lowStorageWarnLoggedInSession) {
                                    lowStorageWarnLoggedInSession = true
                                    _storageAlert.value = "Low storage warning: ${diskStatus.freeMb} MB free (< 1.5 GB)"
                                    recordSessionWarning("Low storage (${diskStatus.freeMb} MB free)")
                                    com.bajajauto.roadsense.logging.AppLogger.w("Storage", "LOW_STORAGE_WARN: ${diskStatus.freeMb} MB free")
                                    sessionManager.recordTimelineEvent(
                                        elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                                        sensor = "STORAGE",
                                        event = "LOW_WARN",
                                        sequenceId = 0L,
                                        relativePath = "session_debug.log",
                                        summary = "freeMb=${diskStatus.freeMb}"
                                    )
                                }
                            }
                            StorageHealth.CRITICAL_STOP -> {
                                if (!isAutoStoppingForStorage) {
                                    isAutoStoppingForStorage = true
                                    _storageAlert.value = "Storage critical (${diskStatus.freeMb} MB < 500 MB) — stopping & saving MP4 cleanly"
                                    recordSessionWarning("Storage critical auto-stop (${diskStatus.freeMb} MB)")
                                    com.bajajauto.roadsense.logging.AppLogger.e("Storage", "CRITICAL_AUTO_STOP: ${diskStatus.freeMb} MB free")
                                    sessionManager.recordTimelineEvent(
                                        elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                                        sensor = "STORAGE",
                                        event = "CRITICAL_AUTO_STOP",
                                        sequenceId = 0L,
                                        relativePath = "camera/camera_video.mp4",
                                        summary = "freeMb=${diskStatus.freeMb}"
                                    )
                                    viewModelScope.launch(Dispatchers.Main.immediate) {
                                        stopSessionRecording()
                                    }
                                }
                            }
                            StorageHealth.OK -> {}
                        }
                    }
                }

                // 3. Hardware CPU temperature & live thermal severity (warn + log only)
                var cTemp = _cpuTempC.value
                if (cpuTempSupported) {
                    try {
                        val hpm = application.getSystemService(android.content.Context.HARDWARE_PROPERTIES_SERVICE) as? android.os.HardwarePropertiesManager
                        val temps = hpm?.getDeviceTemperatures(android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT)
                        if (temps != null && temps.isNotEmpty() && temps[0] > 0f && temps[0] < 120f) {
                            cTemp = temps[0]
                            if (_cpuTempC.value != cTemp) _cpuTempC.value = cTemp
                        } else {
                            cpuTempSupported = false
                        }
                    } catch (_: SecurityException) {
                        cpuTempSupported = false
                    } catch (_: Exception) {}
                }
                val resolvedTherm = thermalMonitor.currentSeverity(batTemp)
                if (resolvedTherm != currentThermalSeverity) {
                    if (sessionRecorder.currentSession != null) {
                        com.bajajauto.roadsense.logging.AppLogger.w(
                            "Thermal",
                            "THERMAL_CHANGE during recording: ${currentThermalSeverity.name} -> ${resolvedTherm.name} (bat=${batTemp}C)"
                        )
                        sessionManager.recordTimelineEvent(
                            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                            sensor = "SYSTEM",
                            event = "THERMAL_CHANGE",
                            sequenceId = 0L,
                            relativePath = "session_debug.log",
                            summary = "severity=${resolvedTherm.name};batTempC=$batTemp"
                        )
                        if (resolvedTherm.rank >= ThermalSeverity.MODERATE.rank) {
                            recordSessionWarning("Thermal ${resolvedTherm.name} (${String.format(Locale.US, "%.1f", batTemp)}°C)")
                        }
                    }
                    currentThermalSeverity = resolvedTherm
                }
                if (sessionRecorder.currentSession != null) {
                    if (resolvedTherm.rank > peakThermalSeverity.rank) peakThermalSeverity = resolvedTherm
                    if (batTemp > peakBatteryTempC) peakBatteryTempC = batTemp
                }
                tickCount++

                val fix = gnssLocationManager.latestFix.value
                val (liveSvUsed, liveSvInView) = gnssLocationManager.satelliteStatus.value
                val gState = gnssLocationManager.gnssState.value
                val rConnState = connectionManager.connectionState.value
                val cEngineState = cameraEngine.engineState.value
                val iHz = imuManager.imuHz.value
                val isImuMonitoring = imuManager.telemetryState.value.isMonitoring
                val isRec = sessionRecorder.currentSession != null
                if (isRec) {
                    if (rConnState is RadarConnectionState.Connected) {
                        radarWasConnectedInSession = true
                    }
                    if (rHz > 0f) {
                        radarStreamActiveInSession = true
                    }
                }
                val radarDroppedMidRec = isRec && (
                    (radarWasConnectedInSession && (rConnState is RadarConnectionState.Disconnected || rConnState is RadarConnectionState.Reconnecting)) ||
                    (radarStreamActiveInSession && rHz <= 0f)
                )

                val sync = syncAssociator.liveSyncTelemetry.value
                val can = canedgeSyncStats.value
                val radarPill = SensorReadinessEvaluator.evaluateRadar(
                    connState = rConnState,
                    radarHz = rHz,
                    isRecording = isRec,
                    radarDroppedMidRecording = radarDroppedMidRec
                )
                val camPill = SensorReadinessEvaluator.evaluateCamera(
                    engineState = cEngineState,
                    cameraFps = cFps
                )
                val gpsPill = SensorReadinessEvaluator.evaluateGnss(
                    gnssState = gState,
                    gnssHz = gHz
                )
                val imuPill = SensorReadinessEvaluator.evaluateImu(
                    imuHz = iHz,
                    isMonitoring = isImuMonitoring,
                    isRecording = isRec
                )
                val canPill = SensorReadinessEvaluator.evaluateCan(can)

                val newSnapshot = LiveMetricsSnapshot(
                    radarHz = rHz,
                    cameraFps = cFps,
                    gnssHz = gHz,
                    imuHz = iHz,
                    satellitesUsed = if (liveSvInView > 0) liveSvUsed else (fix?.satellitesUsed ?: 0),
                    satellitesInView = if (liveSvInView > 0) liveSvInView else (fix?.satellitesInView ?: 0),
                    accuracyMeters = fix?.accuracyMeters ?: 0f,
                    batteryPct = batPct,
                    batteryTempC = batTemp,
                    cpuUsagePct = usage,
                    cpuTempC = cTemp,
                    syncGrade = sync.liveGrade,
                    syncDeltaMs = sync.camToImuDeltaMs,
                    isSyncActive = sync.isCameraActive && sync.isImuActive,
                    canHudPhase = can.hudPhase,
                    canHudLabel = canPill.fullValue,
                    radarPill = radarPill,
                    camPill = camPill,
                    gpsPill = gpsPill,
                    imuPill = imuPill,
                    canPill = canPill,
                    thermalSeverity = resolvedTherm,
                    storageStatus = diskStatus
                )
                if (_liveMetricsSnapshot.value != newSnapshot) {
                    _liveMetricsSnapshot.value = newSnapshot
                }
            }
        }

        // Dedicated 5 Hz (200 ms) ticker for throttled textual UI telemetry metrics (relieves Main Thread from 20 Hz recomposition)
        viewModelScope.launch(Dispatchers.Default) {
            var lastBytes = 0L
            var lastTimeMs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(200)
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - lastTimeMs).coerceAtLeast(1L)) / 1000f
                val curBytes = rawBytesReceivedCounter
                val curPackets = rawPacketsReceivedCounter
                val rateKbps = (((curBytes - lastBytes) / 1024f) / dt).coerceAtLeast(0f)
                lastBytes = curBytes
                lastTimeMs = now

                if (_totalBytes.value != curBytes) _totalBytes.value = curBytes
                if (_totalPackets.value != curPackets) _totalPackets.value = curPackets

                val nextTelemetry = RadarUiTelemetry(
                    totalBytes = curBytes,
                    totalPackets = curPackets,
                    pointsCount = latestPointsNum,
                    clustersCount = latestClustersNum,
                    tracksCount = latestTracksNum,
                    dataRateKbps = rateKbps,
                    lastFrameSeq = latestFrameSeqNum
                )
                if (_radarUiTelemetry.value != nextTelemetry) {
                    _radarUiTelemetry.value = nextTelemetry
                }
            }
        }

        // Dedicated worker thread for processing raw UART chunks, disk recording, and TLV decoding
        radarProcessingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            val tid = android.os.Process.myTid()
            val osPriority = android.os.Process.getThreadPriority(tid)
            com.bajajauto.roadsense.logging.AppLogger.i(
                "RadarWorker",
                "RadarProcessingWorker online (TID=$tid, OS Priority=$osPriority, TargetPriority=${android.os.Process.THREAD_PRIORITY_AUDIO} [-16 / Linux Pri 104], QueueCapacity=2048)"
            )
            var lastPreviewMs = 0L

            while (isRadarWorkerRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val chunk = rawByteQueue.take()
                    val bytes = chunk.bytes
                    val hostMonoNs = chunk.hostMonoNs
                    val hostWallMs = chunk.hostWallMs

                    rawBytesReceivedCounter += bytes.size

                    // Throttled UI hex preview: sampled at ~4 Hz only when enabled
                    if (_isHexPreviewEnabled.value) {
                        val now = System.currentTimeMillis()
                        if (now - lastPreviewMs > 250) {
                            lastPreviewMs = now
                            val preview = bytes.take(24).joinToString(" ") { "%02X".format(it) }
                            val line = if (bytes.size > 24) "$preview ... (${bytes.size} B)\n" else "$preview (${bytes.size} B)\n"
                            hexBuilder.append(line)
                            if (hexBuilder.length > MAX_HEX_CHARS) {
                                hexBuilder.delete(0, hexBuilder.length - MAX_HEX_CHARS)
                            }
                            _rawHexData.value = hexBuilder.toString()
                        }
                    }

                    rawRecorder.write(bytes)
                    sessionRecorder.writeRawBytes(bytes)

                    android.os.Trace.beginSection("RS_Worker_Assemble")
                    val assembledPackets = packetAssembler.appendBytes(bytes, hostMonoNs, hostWallMs)
                    android.os.Trace.endSection()

                    if (assembledPackets.isNotEmpty()) {
                        radarFramesThisSec += assembledPackets.size
                        rawPacketsReceivedCounter += assembledPackets.size

                        val disciplinedPackets = ArrayList<RawRadarPacket>(assembledPackets.size)
                        for (packet in assembledPackets) {
                            val seq = packet.header.frameNumber
                            if (packet.isSalvaged) {
                                totalRadarSalvagedFrames++
                                com.bajajauto.roadsense.logging.AppLogger.i(
                                    "RadarSession",
                                    "RADAR_FRAME_SALVAGED: Frame #$seq salvaged (recovered ${packet.payload.size} B payload, 0 sequence gap, total salvaged: $totalRadarSalvagedFrames)"
                                )
                            }
                            if (sessionRecorder.currentSession != null) {
                                if (lastObservedRadarSeq != -1L && seq != lastObservedRadarSeq + 1L) {
                                    val gap = seq - lastObservedRadarSeq - 1L
                                    totalRadarSequenceGaps++
                                    totalRadarDroppedFrames += gap
                                    com.bajajauto.roadsense.logging.AppLogger.w(
                                        "RadarSession",
                                        "RADAR_FRAME_DROP: Expected #${lastObservedRadarSeq + 1}, got #$seq (Gap of $gap frames, Total dropped: $totalRadarDroppedFrames)"
                                    )
                                }
                                lastObservedRadarSeq = seq
                            }

                            // Discipline hardware DSP cycle counter to elapsedRealtimeNanos, eliminating CP2105/USB bulk batching jitter
                            val disciplined = clockDiscipliner.discipline(
                                header = packet.header,
                                rawHostMonoNs = packet.hostTimestampNs,
                                rawHostWallMs = packet.wallClockTimestampMs
                            )
                            val disciplinedPacket = packet.copy(
                                hostTimestampNs = disciplined.monotonicNs,
                                wallClockTimestampMs = disciplined.wallClockMs,
                                rawHostTimestampNs = packet.hostTimestampNs
                            )
                            disciplinedPackets.add(disciplinedPacket)

                            android.os.Trace.beginSection("RS_Worker_WriteFrame")
                            sessionRecorder.writeFrame(disciplinedPacket, disciplined.monotonicNs, disciplined.wallClockMs)
                            android.os.Trace.endSection()
                        }

                        val lastPacket = disciplinedPackets.last()
                        _latestPacket.value = lastPacket

                        // Decode real-time TLVs into structured RadarFrame
                        android.os.Trace.beginSection("RS_Worker_DecodeTLV")
                        val decoded = tlvDecoder.decode(lastPacket)
                        android.os.Trace.endSection()
                        latestFrameSeqNum = decoded.header.frameNumber
                        latestPointsNum = decoded.points.size
                        latestClustersNum = decoded.clusters.size
                        latestTracksNum = decoded.tracks.size
                        displayPacer.enqueueFrame(decoded)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.e("RadarViewModel", "Error in radar processing worker", e)
                }
            }
        }, "RadarProcessingWorker").apply {
            isDaemon = true
            start()
        }

        // Non-blocking zero-loss callback on USB IO thread: enqueues directly into rawByteQueue (< 2 µs)
        connectionManager.addDataListener { bytes ->
            val hostMonoNs = SystemClock.elapsedRealtimeNanos()
            val hostWallMs = System.currentTimeMillis()
            val currentSize = rawByteQueue.size
            if (currentSize > peakRawByteQueueDepth) {
                peakRawByteQueueDepth = currentSize
            }
            if (!rawByteQueue.offer(RawUartChunk(bytes, hostMonoNs, hostWallMs))) {
                totalRawByteChunksDropped++
                com.bajajauto.roadsense.logging.AppLogger.e(
                    "RadarQueue",
                    "Radar rawByteQueue FULL! Dropped chunk of ${bytes.size} B (Total dropped: $totalRawByteChunksDropped)"
                )
            } else {
                totalRawByteChunksEnqueued++
            }
        }

        // GNSS listener: automatically records fixes if session recording is active
        gnssLocationManager.addFixListener { fix ->
            gnssFixesThisSec++
            gnssSessionRecorder.recordFix(fix)
            syncAssociator.updateGnss(fix)
        }

        // GNSS raw NMEA listener: stream raw sentences to gnss_raw.nmea during active recording
        gnssLocationManager.addNmeaListener { sentence, timestamp ->
            if (gnssSessionRecorder.isRecording.value) {
                gnssSessionRecorder.recordNmea(sentence, timestamp)
            }
        }

        // Camera frame shutter listener: automatically records frame metadata if session recording is active and MediaRecorder is running
        cameraEngine.addFrameListener { frame ->
            val ts = frame.shutterTimestampNs
            if (firstCameraFrameInSecMonoNs == 0L) {
                firstCameraFrameInSecMonoNs = ts
            }
            lastCameraFrameInSecMonoNs = ts
            cameraFramesThisSec++
            if (frame.isRecording) {
                cameraSessionRecorder.recordFrame(frame)
            }
        }



        // Auto-warm up GNSS on launch if permission is available
        if (hasLocationPermission()) {
            startGnssUpdates()
        }

        // Auto-probe CANedge logger on launch (checks cached IP first)
        startCanedgeDiscovery()
    }

    fun setRadarMaxRange(range: Float) {
        _radarMaxRange.value = range
        appPreferences.radarMaxRange = range
    }

    fun setRadarDynamicOnly(enabled: Boolean) {
        _radarDynamicOnly.value = enabled
        appPreferences.radarDynamicOnly = enabled
    }

    fun setRadarMinSnr(enabled: Boolean) {
        _radarMinSnr.value = enabled
        appPreferences.radarMinSnrFilter = enabled
    }

    fun setBevShowRawPoints(enabled: Boolean) {
        DebugToggles.setBevLayerToggles(showRawPoints = enabled)
        appPreferences.bevShowRawPoints = enabled
    }

    fun setBevShowTracks(enabled: Boolean) {
        DebugToggles.setBevLayerToggles(showTracks = enabled)
        appPreferences.bevShowTracks = enabled
    }

    fun setBevShowClusters(enabled: Boolean) {
        DebugToggles.setBevLayerToggles(showClusters = enabled)
        appPreferences.bevShowClusters = enabled
    }

    fun setBevShowGrid(enabled: Boolean) {
        DebugToggles.setBevLayerToggles(showGrid = enabled)
        appPreferences.bevShowGrid = enabled
    }

    fun setBenchTimingLogging(enabled: Boolean) {
        DebugToggles.setBenchTimingLogging(enabled)
    }

    fun setCanedgeVerboseLogs(enabled: Boolean) {
        DebugToggles.setCanedgeVerboseLogs(enabled)
        com.bajajauto.roadsense.logging.AppLogger.i(
            "CanedgeIngestion",
            "[CAN_DIAG] VERBOSE_LOGS enabled=$enabled"
        )
    }

    fun setHudOverlayMode(mode: HudOverlayMode) {
        DebugToggles.setHudOverlayMode(mode)
        appPreferences.hudOverlayMode = mode
    }

    fun cycleHudOverlayMode() {
        val modes = HudOverlayMode.entries
        val next = modes[(DebugToggles.hudOverlayMode.ordinal + 1) % modes.size]
        setHudOverlayMode(next)
    }

    fun setCameraPreviewMuted(muted: Boolean) {
        _isCameraPreviewMuted.value = muted
    }

    fun setHexPreviewEnabled(enabled: Boolean) {
        _isHexPreviewEnabled.value = enabled
        if (!enabled) {
            hexBuilder.clear()
            _rawHexData.value = "Hex preview paused to conserve CPU."
        }
    }

    fun hasLocationPermission(): Boolean {
        return gnssLocationManager.hasLocationPermission()
    }

    fun startGnssUpdates(): Boolean {
        return gnssLocationManager.startLocationUpdates()
    }

    fun stopGnssUpdates() {
        gnssLocationManager.stopLocationUpdates()
    }

    val availableCameras: StateFlow<List<com.bajajauto.roadsense.camera.CameraDeviceInfo>> = cameraEngine.availableCameras
    val selectedCamera: StateFlow<com.bajajauto.roadsense.camera.CameraDeviceInfo?> = cameraEngine.selectedCamera

    fun selectCamera(deviceInfo: com.bajajauto.roadsense.camera.CameraDeviceInfo) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User selected camera lens: ${deviceInfo.displayName} (id=${deviceInfo.id})")
        appPreferences.cameraLensId = deviceInfo.id
        cameraEngine.selectCamera(deviceInfo)
    }

    fun updateCameraDisplayRotation(rotation: Int, textureView: android.view.TextureView? = null, width: Int = 0, height: Int = 0) {
        cameraEngine.updateDisplayRotation(rotation, textureView, width, height)
    }

    fun hasCameraPermission(): Boolean {
        return cameraEngine.hasCameraPermission()
    }

    fun setCameraResolution(resolution: CameraResolution) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User selected resolution: ${resolution.label}")
        appPreferences.cameraResolution = resolution
        cameraEngine.setResolution(resolution)
    }

    fun setCameraFrameRate(fps: CameraFrameRate) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User selected frame rate: ${fps.label}")
        appPreferences.cameraFrameRate = fps
        cameraEngine.setFrameRate(fps)
    }

    fun toggleInfinityFocus() {
        val newState = !cameraEngine.isInfinityFocusLocked.value
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User toggled infinity focus lock -> $newState")
        cameraEngine.setInfinityFocus(newState)
    }

    val isAfAeLocked: StateFlow<Boolean> = cameraEngine.isAfAeLocked
    val tapFocusPoint: StateFlow<Pair<Float, Float>?> = cameraEngine.tapFocusPoint

    fun triggerCameraAf(normX: Float? = null, normY: Float? = null) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User triggered camera AF (tap: x=$normX, y=$normY)")
        cameraEngine.triggerAutoFocus(normX, normY)
    }

    val isOisEnabled: StateFlow<Boolean> = cameraEngine.isOisEnabled
    val isOisSupported: StateFlow<Boolean> = cameraEngine.isOisSupported

    fun toggleOis() {
        val newState = !cameraEngine.isOisEnabled.value
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User toggled OIS -> $newState")
        cameraEngine.setOisEnabled(newState)
    }

    // Dynamic Road Auto-Exposure state flows & controls
    val roadAeMode: StateFlow<com.bajajauto.roadsense.camera.RoadAeMode> = cameraEngine.roadAeMode
    val roadAeState: StateFlow<com.bajajauto.roadsense.camera.RoadAeState> = cameraEngine.roadAeState
    val roadAeContrastRatio: StateFlow<Float> = cameraEngine.contrastRatio
    val roadAeSkyLuminance: StateFlow<Float> = cameraEngine.skyLuminance
    val roadAeRoadLuminance: StateFlow<Float> = cameraEngine.roadLuminance

    fun toggleRoadAeMode() {
        val current = cameraEngine.roadAeMode.value
        val next = if (current == com.bajajauto.roadsense.camera.RoadAeMode.AUTO_ROAD) {
            com.bajajauto.roadsense.camera.RoadAeMode.FULL_MATRIX
        } else {
            com.bajajauto.roadsense.camera.RoadAeMode.AUTO_ROAD
        }
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User toggled Road AE mode -> $next")
        cameraEngine.setRoadAeMode(next)
    }

    fun setRoadAeMode(mode: com.bajajauto.roadsense.camera.RoadAeMode) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User set Road AE mode -> $mode")
        cameraEngine.setRoadAeMode(mode)
    }

    fun startSessionRecording(skipWarmup: Boolean = false) {
        val currentState = sessionRecorder.recordingState.value
        if (sessionRecorder.currentSession != null ||
            currentState is SessionRecordingState.WarmingUp ||
            currentState is SessionRecordingState.Finalizing
        ) {
            return
        }

        // Dismiss any lingering post-drive summary immediately when starting a new drive
        _postDriveSummary.value = null

        val storageStatus = StorageGuard.evaluate(StorageGuard.queryFreeMb(sessionManager.sessionsBaseDir))
        currentStorageStatus = storageStatus
        if (storageStatus.health == StorageHealth.CRITICAL_STOP) {
            val msg = "Storage critically low (${storageStatus.freeMb} MB < ${StorageGuard.CRITICAL_STOP_MB} MB) — free space before recording"
            _storageAlert.value = msg
            com.bajajauto.roadsense.logging.AppLogger.e("Storage", msg)
            return
        }

        val cameraWarm = cameraEngine.isWarmForRecording() || !cameraEngine.hasCameraPermission()
        val imuWarm = imuManager.telemetryState.value.isMonitoring && imuManager.imuHz.value >= 20f

        if (skipWarmup || (cameraWarm && imuWarm)) {
            if (DebugToggles.isSessionLifecycleDebugActive) {
                com.bajajauto.roadsense.logging.AppLogger.i(
                    "LifecycleDiag",
                    "[LIFECYCLE_DIAG] WARMUP_SKIPPED skipWarmup=$skipWarmup camWarm=$cameraWarm imuWarm=$imuWarm"
                )
            }
            sessionRecorder.setWarmingUp("Starting…")
            viewModelScope.launch(Dispatchers.IO) {
                beginSessionRecordingInternal()
            }
            return
        }

        sessionRecorder.setWarmingUp("Warming sensors…")
        warmupJob?.cancel()
        warmupJob = viewModelScope.launch(Dispatchers.Default) {
            val tWarm0 = SystemClock.elapsedRealtime()
            if (DebugToggles.isSessionLifecycleDebugActive) {
                com.bajajauto.roadsense.logging.AppLogger.i(
                    "LifecycleDiag",
                    "[LIFECYCLE_DIAG] WARMUP_START camWarm=$cameraWarm imuWarm=$imuWarm"
                )
            }

            if (hasLocationPermission()) {
                startGnssUpdates()
            }

            val initialImuMonoNs = imuManager.telemetryState.value.lastUpdateMonoNs
            if (!imuWarm) {
                imuStartedByWarmup = !imuManager.telemetryState.value.isMonitoring
                imuManager.startLiveMonitoring()
            }

            val camDeferred = CompletableDeferred<Boolean>()
            if (cameraWarm) {
                camDeferred.complete(true)
            } else {
                cameraEngine.warmUpForRecording(targetFrames = 8, timeoutMs = 2000L) { ready ->
                    camDeferred.complete(ready)
                }
            }

            val camReady = withTimeoutOrNull(2000L) { camDeferred.await() } ?: false

            // Wait briefly for IMU samples if IMU was cold, bounded to the same 2,000 ms overall budget
            if (!imuWarm) {
                while (isActive && (SystemClock.elapsedRealtime() - tWarm0) < 2000L) {
                    val hasSamples = imuManager.telemetryState.value.lastUpdateMonoNs != initialImuMonoNs ||
                        imuManager.imuHz.value >= 10f
                    if (hasSamples && (SystemClock.elapsedRealtime() - tWarm0) >= 200L) break
                    delay(40L)
                }
            }

            if (!isActive) return@launch

            val elapsedWarmMs = SystemClock.elapsedRealtime() - tWarm0
            if (DebugToggles.isSessionLifecycleDebugActive) {
                com.bajajauto.roadsense.logging.AppLogger.i(
                    "LifecycleDiag",
                    "[LIFECYCLE_DIAG] WARMUP_DONE elapsedMs=$elapsedWarmMs camReady=$camReady imuHz=${imuManager.imuHz.value}"
                )
            }

            imuStartedByWarmup = false
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                beginSessionRecordingInternal()
            }
        }
    }

    fun cancelWarmup() {
        warmupJob?.cancel()
        warmupJob = null
        cameraEngine.cancelWarmup()
        if (imuStartedByWarmup && !isImuRecording.value) {
            imuManager.stopLiveMonitoring()
        }
        imuStartedByWarmup = false
        if (sessionRecorder.recordingState.value is SessionRecordingState.WarmingUp) {
            sessionRecorder.setIdle()
        }
        if (DebugToggles.isSessionLifecycleDebugActive) {
            com.bajajauto.roadsense.logging.AppLogger.i("LifecycleDiag", "[LIFECYCLE_DIAG] WARMUP_CANCELLED")
        }
    }

    private fun beginSessionRecordingInternal(): SessionInfo? = synchronized(sessionLifecycleLock) {
        // Ensure any previous session's async RadarSessionRecorder-Worker flush/close has finished
        // before opening new file streams for a back-to-back recording.
        sessionRecorder.awaitFlush(1500L)
        if (sessionRecorder.currentSession != null) {
            return@synchronized sessionRecorder.currentSession
        }
        val storageStatus = StorageGuard.evaluate(StorageGuard.queryFreeMb(sessionManager.sessionsBaseDir))
        currentStorageStatus = storageStatus
        if (storageStatus.health == StorageHealth.CRITICAL_STOP) {
            val msg = "Storage critically low (${storageStatus.freeMb} MB < ${StorageGuard.CRITICAL_STOP_MB} MB) — free space before recording"
            _storageAlert.value = msg
            sessionRecorder.setIdle()
            com.bajajauto.roadsense.logging.AppLogger.e("Storage", msg)
            return@synchronized null
        }

        isAutoStoppingForStorage = false
        lowStorageWarnLoggedInSession = (storageStatus.health == StorageHealth.LOW_WARN)
        _storageAlert.value = if (storageStatus.health == StorageHealth.LOW_WARN) {
            "Low storage warning: ${storageStatus.freeMb} MB free (< 1.5 GB)"
        } else {
            null
        }
        val initialThermal = thermalMonitor.currentSeverity(_batteryTempC.value)
        currentThermalSeverity = initialThermal
        peakThermalSeverity = initialThermal
        peakBatteryTempC = _batteryTempC.value
        activeSessionWarnings.clear()
        _sessionBookmarkCount.value = 0
        _lastBookmarkToast.value = null
        if (storageStatus.health == StorageHealth.LOW_WARN) {
            activeSessionWarnings.add("Low storage (${storageStatus.freeMb} MB free)")
        }

        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Starting session recording")
        syncAssociator.reset()
        clockDiscipliner.reset()
        if (hasLocationPermission()) {
            startGnssUpdates()
        }
        val session = sessionRecorder.startSession()
        if (session != null) {
            session.sessionTags = _sessionTags.value
            RecordingForegroundService.start(
                context = getApplication(),
                sessionId = session.sessionId,
                hasRadarUsb = connectionManager.connectionState.value is RadarConnectionState.Connected
            )
            radarWasConnectedInSession = connectionManager.connectionState.value is RadarConnectionState.Connected
            radarStreamActiveInSession = false
            lastObservedRadarSeq = -1L
            totalRadarSequenceGaps = 0L
            totalRadarDroppedFrames = 0L
            totalRadarSalvagedFrames = 0L
            peakRawByteQueueDepth = 0
            totalRawByteChunksEnqueued = 0L
            totalRawByteChunksDropped = 0L
            val res = cameraEngine.selectedResolution.value
            val camId = cameraEngine.selectedCamera.value?.id
            val streamIntrinsics = CameraIntrinsicsProvider.getStreamIntrinsics(
                context = getApplication(),
                cameraId = camId,
                streamWidth = res.width,
                streamHeight = res.height
            )
            val calibWithIntrinsics = _calibrationParams.value.copy(
                streamWidth = res.width,
                streamHeight = res.height,
                fx = streamIntrinsics.fx,
                fy = streamIntrinsics.fy,
                cx = streamIntrinsics.cx,
                cy = streamIntrinsics.cy,
                hfovDeg = streamIntrinsics.hfovDeg,
                vfovDeg = streamIntrinsics.vfovDeg
            )
            _calibrationParams.value = calibWithIntrinsics
            sessionManager.saveSessionCalibration(session, calibWithIntrinsics)
            gnssSessionRecorder.startRecording(session)
            val videoFile = cameraSessionRecorder.startRecording(session, res)
            if (videoFile != null) {
                cameraEngine.startVideoRecording(videoFile)
            }
            canedgeIngestionManager.onSessionStarted(session)
            imuSessionRecorder.startRecording(session)
            imuManager.startSessionRecording(imuSessionRecorder)
        }
        return@synchronized session
    }

    /**
     * Non-blocking session stop:
     * 1. Transitions UI state to [SessionRecordingState.Finalizing] within < 16 ms so the Stop button never freezes.
     * 2. Stops MediaRecorder, IMU, GNSS, and flushes session files on [Dispatchers.IO] (~250-500 ms).
     * 3. Transitions UI back to [SessionRecordingState.Finished] immediately so a new drive can start right away.
     * 4. Runs [SessionSyncAuditor] asynchronously on a dedicated background worker and emits [postDriveSummary] when done.
     */
    fun stopSessionRecording() {
        val currentState = sessionRecorder.recordingState.value
        if (currentState is SessionRecordingState.WarmingUp) {
            cancelWarmup()
            return
        }
        if (currentState is SessionRecordingState.Finalizing) {
            return
        }
        val activeSession = sessionRecorder.currentSession ?: return
        val tapTs = SystemClock.elapsedRealtime()
        if (DebugToggles.isSessionLifecycleDebugActive) {
            com.bajajauto.roadsense.logging.AppLogger.i(
                "LifecycleDiag",
                "[LIFECYCLE_DIAG] STOP_TAPPED session=${activeSession.sessionId}"
            )
        }
        sessionRecorder.markFinalizing("SAVING")
        // Set audit token & auditing flag BEFORE launching deferred audit to prevent race on very short (<5 ms audit) logs
        val auditToken = activeAuditToken.incrementAndGet()
        _isSessionAuditing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val stopped = stopSessionRecordingInternal(deferAudit = true) { completedSession, audit ->
                _latestSessionAudit.value = audit
                if (activeAuditToken.get() == auditToken) {
                    _isSessionAuditing.value = false
                }
                if (DebugToggles.isSessionLifecycleDebugActive) {
                    com.bajajauto.roadsense.logging.AppLogger.i(
                        "LifecycleDiag",
                        "[LIFECYCLE_DIAG] AUDIT_COMPLETE session=${completedSession.sessionId} grade=${audit?.certificationGrade ?: "N/A"} camDrops=${audit?.cameraStats?.anomalyCount ?: -1} imuSpikes=${audit?.imuStats?.anomalyCount ?: -1}"
                    )
                }
                // Only present Post-Drive Summary if user has not already started a new recording
                if (sessionRecorder.currentSession == null &&
                    sessionRecorder.recordingState.value !is SessionRecordingState.WarmingUp
                ) {
                    val canStats = canedgeSyncStats.value
                    _postDriveSummary.value = PostDriveSummaryData(
                        sessionId = completedSession.sessionId,
                        durationMs = ((completedSession.stopTimeWallMs ?: System.currentTimeMillis()) - completedSession.startTimeWallMs).coerceAtLeast(0L),
                        radarFrames = completedSession.totalRadarFrames,
                        radarSalvaged = completedSession.totalRadarSalvagedFrames,
                        radarDrops = completedSession.totalRadarSequenceDrops,
                        cameraFrames = completedSession.totalCameraFrames,
                        imuFrames = completedSession.totalImuFrames,
                        gnssFixes = completedSession.totalGnssFixes,
                        bookmarksCount = completedSession.totalEventBookmarks,
                        audit = audit,
                        warnings = completedSession.recordedWarnings.toList(),
                        canPendingCount = canStats.pendingSessionsCount,
                        canStatusLabel = when {
                            canStats.pendingSessionsCount > 0 -> "Syncing ${canStats.pendingSessionsCount} pending session(s) in background — safe to start next drive"
                            canStats.hudPhase == com.bajajauto.roadsense.canedge.model.CanedgeHudPhase.CONNECTED_SYNCED ||
                                canStats.hudPhase == com.bajajauto.roadsense.canedge.model.CanedgeHudPhase.SYNCING -> "CANedge2 online & syncing"
                            else -> "CANedge2 offline — queued for next connect"
                        },
                        peakThermalSeverity = completedSession.peakThermalSeverity,
                        peakBatteryTempC = completedSession.peakBatteryTempC,
                        sessionTags = completedSession.sessionTags
                    )
                }
            }
            if (stopped == null) {
                if (activeAuditToken.get() == auditToken) {
                    _isSessionAuditing.value = false
                }
            } else if (DebugToggles.isSessionLifecycleDebugActive) {
                val elapsedMs = SystemClock.elapsedRealtime() - tapTs
                com.bajajauto.roadsense.logging.AppLogger.i(
                    "LifecycleDiag",
                    "[LIFECYCLE_DIAG] STOP_IO_COMPLETE session=${stopped.sessionId} totalMs=$elapsedMs auditQueued=true"
                )
            }
        }
    }

    private fun stopSessionRecordingInternal(
        deferAudit: Boolean,
        onAuditCompleted: ((SessionInfo, SessionSyncAuditor.AuditResult?) -> Unit)? = null
    ): SessionInfo? = synchronized(sessionLifecycleLock) {
        val activeSession = sessionRecorder.currentSession ?: return@synchronized null
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Stopping session recording (${activeSession.sessionId})")
        sessionManager.saveSessionCalibration(activeSession, _calibrationParams.value)
        cameraEngine.stopVideoRecording()
        cameraSessionRecorder.stopRecording()
        gnssSessionRecorder.stopRecording()
        val imuFrames = imuSessionRecorder.stopRecording()
        imuManager.stopSessionRecording()
        activeSession.totalImuFrames = imuFrames
        activeSession.totalRadarSalvagedFrames = totalRadarSalvagedFrames
        activeSession.totalRadarSequenceDrops = totalRadarDroppedFrames
        activeSession.peakThermalSeverity = peakThermalSeverity.name
        activeSession.peakBatteryTempC = peakBatteryTempC
        activeSession.sessionTags = _sessionTags.value
        val warningsCopy = activeSessionWarnings.toMutableList()
        if (totalRadarDroppedFrames >= 5L) {
            warningsCopy.add("Radar dropped $totalRadarDroppedFrames frame(s)")
        }
        activeSession.recordedWarnings = warningsCopy

        val stopped = sessionRecorder.stopSession(
            deferAudit = deferAudit,
            onAuditCompleted = onAuditCompleted
        )
        radarWasConnectedInSession = false
        radarStreamActiveInSession = false
        RecordingForegroundService.stop(getApplication())
        canedgeIngestionManager.onSessionStopped(stopped)
        val totalRecorded = stopped?.totalRadarFrames ?: 0L
        com.bajajauto.roadsense.logging.AppLogger.i(
            "RadarSession",
            "Radar Session Telemetry: framesRecorded=$totalRecorded, salvagedFrames=$totalRadarSalvagedFrames, " +
            "droppedFrames=$totalRadarDroppedFrames, sequenceGaps=$totalRadarSequenceGaps, " +
            "queuePeakDepth=$peakRawByteQueueDepth/2048 (${peakRawByteQueueDepth * 100 / 2048}%), " +
            "chunksDropped=$totalRawByteChunksDropped, bookmarks=${stopped?.totalEventBookmarks ?: 0}"
        )
        if (!deferAudit) {
            _latestSessionAudit.value = stopped?.syncAuditResult
        }
        return@synchronized stopped
    }

    private fun emergencyFlushActiveSession() {
        val activeSession = sessionRecorder.currentSession ?: return
        activeSession.terminatedByCrash = true
        val flushed = stopSessionRecordingInternal(deferAudit = false)
        if (flushed != null) {
            sessionManager.writeMetadata(flushed)
        }
        sessionRecorder.awaitFlush(1200L)
    }

    fun startCanedgeDiscovery() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User triggered CANedge discovery")
        canedgeIngestionManager.setAutoReconnectEnabled(true)
        canedgeDiscovery.startDiscovery { success ->
            if (success) {
                canedgeIngestionManager.triggerSync()
            }
        }
    }

    fun disconnectCanedge() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User disconnected CANedge")
        canedgeIngestionManager.setAutoReconnectEnabled(false)
        canedgeDiscovery.disconnect()
    }

    fun triggerCanedgeSync() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User triggered manual CANedge sync")
        canedgeIngestionManager.triggerSync()
    }

    fun startRawRecording(): File? {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User started raw radar recording")
        return rawRecorder.startRecording()
    }

    fun stopRawRecording(): File? {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User stopped raw radar recording")
        return rawRecorder.stopRecording()
    }

    fun scanAndConnect() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User tapped Scan & Connect USB radar")
        rawByteQueue.clear()
        packetAssembler.reset()
        clockDiscipliner.reset()
        displayPacer.reset()
        connectionManager.scanAndConnect()
    }

    fun disconnect() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User tapped Disconnect radar")
        connectionManager.disconnect()
        syncAssociator.markRadarDisconnected()
        rawByteQueue.clear()
        packetAssembler.reset()
        clockDiscipliner.reset()
        displayPacer.reset()
    }

    /**
     * Dynamically updates the data port UART baud rate on the hardware port
     * and recalculates physical Start-of-Frame byte timing in the assembler.
     */
    fun setDataBaudRate(baudRate: Int) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Setting radar data baud rate to $baudRate bps")
        connectionManager.setDataBaudRate(baudRate)
        packetAssembler.baudRate = baudRate
        clockDiscipliner.reset()
        displayPacer.reset()
    }

    fun sendConfig(command: String) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User sent CLI config command: $command")
        connectionManager.sendConfigCommand(command)
    }

    // --- Radar-Camera Viewfinder Calibration & Sensor Fusion Methods ---

    fun setCalibrationFullScreen(enabled: Boolean) {
        _isCalibrationFullScreen.value = enabled
        if (enabled) {
            // Auto-start IMU monitoring for live telemetry if not already active
            if (!imuManager.telemetryState.value.isMonitoring) {
                imuMonitoringStartedByCalibration = true
                startImuMonitoring()
            }
            // Turn on Live IMU horizon follow by default during calibration
            _isLiveImuHorizonEnabled.value = true
        } else {
            _isLiveImuHorizonEnabled.value = false
            // Turn off IMU monitoring if it was automatically started for calibration
            if (imuMonitoringStartedByCalibration && !isImuRecording.value) {
                stopImuMonitoring()
                imuMonitoringStartedByCalibration = false
            }
        }
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Calibration fullscreen set to $enabled (liveImu=${_isLiveImuHorizonEnabled.value})")
    }

    fun setCameraFullScreen(enabled: Boolean) {
        _isCameraFullScreen.value = enabled
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Camera fullscreen set to $enabled")
    }

    fun setSbsFullScreen(enabled: Boolean) {
        _isSbsFullScreen.value = enabled
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "SBS fullscreen set to $enabled")
    }

    fun toggleRadarOverlay() {
        val next = !_isRadarOverlayEnabled.value
        _isRadarOverlayEnabled.value = next
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Radar overlay toggled to $next")
    }

    fun setRadarOverlayEnabled(enabled: Boolean) {
        _isRadarOverlayEnabled.value = enabled
    }

    fun setRadarArcsEnabled(enabled: Boolean) {
        _isRadarArcsEnabled.value = enabled
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Radar arcs toggled to $enabled")
    }

    fun updateCameraIntrinsics(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val selectedCamId = cameraEngine.selectedCamera.value?.id
        val res = cameraEngine.selectedResolution.value
        val rot = cameraEngine.currentDisplayRotation
        val newIntrinsics = CameraIntrinsicsProvider.getIntrinsics(
            context = getApplication(),
            cameraId = selectedCamId,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            streamWidth = res.width,
            streamHeight = res.height,
            displayRotation = rot
        )
        if (_activeIntrinsics.value != newIntrinsics) {
            _activeIntrinsics.value = newIntrinsics
        }
    }

    fun updateExtrinsics(
        setbackM: Float? = null,
        heightOffsetM: Float? = null,
        lateralOffsetM: Float? = null,
        targetDistanceM: Float? = null,
        targetWidthM: Float? = null,
        targetHeightM: Float? = null,
        radarHeightM: Float? = null,
        rollDeg: Float? = null
    ) {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            setbackM = setbackM ?: current.setbackM,
            heightOffsetM = heightOffsetM ?: current.heightOffsetM,
            lateralOffsetM = lateralOffsetM ?: current.lateralOffsetM,
            targetDistanceM = targetDistanceM ?: current.targetDistanceM,
            targetWidthM = targetWidthM ?: current.targetWidthM,
            targetHeightM = targetHeightM ?: current.targetHeightM,
            radarHeightM = radarHeightM ?: current.radarHeightM,
            rollDeg = rollDeg ?: current.rollDeg
        )
    }

    fun applyCalibrationDrag(normX: Float, normY: Float, viewWidth: Int, viewHeight: Int) {
        val intrinsics = _activeIntrinsics.value ?: CameraIntrinsicsProvider.fallbackIntrinsics(viewWidth, viewHeight)
        val current = _calibrationParams.value
        val (newPitch, newYaw) = SpatialProjectionEngine.solveExtrinsicsFromTouch(
            normX = normX,
            normY = normY,
            params = current,
            intrinsics = intrinsics,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
        _calibrationParams.value = current.copy(
            pitchDeg = newPitch,
            yawDeg = newYaw,
            nudgePitchDeg = 0f,
            nudgeYawDeg = 0f
        )
    }

    /**
     * Relative delta dragging (trackpad style): Moves the reticle incrementally
     * by (deltaXPx, deltaYPx) from its current screen position.
     * Prevents the user's finger from covering/blocking the target vehicle or reticle.
     */
    fun applyCalibrationDelta(deltaXPx: Float, deltaYPx: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val intrinsics = _activeIntrinsics.value ?: CameraIntrinsicsProvider.fallbackIntrinsics(viewWidth, viewHeight)
        val current = _calibrationParams.value
        val currentPt = SpatialProjectionEngine.project2DRadarToScreen(
            xRadar = 0f,
            yRadar = current.targetDistanceM,
            params = current,
            intrinsics = intrinsics,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
        val curX = currentPt?.xPx ?: (viewWidth / 2f)
        val curY = currentPt?.yPx ?: (viewHeight / 2f)

        val newX = (curX + deltaXPx).coerceIn(0f, viewWidth.toFloat())
        val newY = (curY + deltaYPx).coerceIn(0f, viewHeight.toFloat())

        val normX = newX / viewWidth.toFloat()
        val normY = newY / viewHeight.toFloat()

        val (newPitch, newYaw) = SpatialProjectionEngine.solveExtrinsicsFromTouch(
            normX = normX,
            normY = normY,
            params = current,
            intrinsics = intrinsics,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
        _calibrationParams.value = current.copy(
            pitchDeg = newPitch,
            yawDeg = newYaw,
            nudgePitchDeg = 0f,
            nudgeYawDeg = 0f
        )
    }

    fun nudgePitch(deltaDeg: Float) {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            nudgePitchDeg = (current.nudgePitchDeg + deltaDeg).coerceIn(-15f, 15f)
        )
    }

    fun nudgeYaw(deltaDeg: Float) {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            nudgeYawDeg = (current.nudgeYawDeg + deltaDeg).coerceIn(-15f, 15f)
        )
    }

    fun saveBaselineCalibration(profileName: String = "Default Mount") {
        val current = _calibrationParams.value
        val res = cameraEngine.selectedResolution.value
        val camId = cameraEngine.selectedCamera.value?.id
        val streamIntrinsics = CameraIntrinsicsProvider.getStreamIntrinsics(
            context = getApplication(),
            cameraId = camId,
            streamWidth = res.width,
            streamHeight = res.height
        )
        val consolidated = current.copy(
            pitchDeg = current.effectivePitchDeg,
            yawDeg = current.effectiveYawDeg,
            nudgePitchDeg = 0f,
            nudgeYawDeg = 0f,
            streamWidth = res.width,
            streamHeight = res.height,
            fx = streamIntrinsics.fx,
            fy = streamIntrinsics.fy,
            cx = streamIntrinsics.cx,
            cy = streamIntrinsics.cy,
            hfovDeg = streamIntrinsics.hfovDeg,
            vfovDeg = streamIntrinsics.vfovDeg,
            profileName = profileName,
            lastCalibratedTimestampMs = System.currentTimeMillis()
        )
        _calibrationParams.value = consolidated
        _savedBaselineParams.value = consolidated
        viewModelScope.launch {
            calibrationStorage.saveCalibration(consolidated)
            sessionRecorder.currentSession?.let { activeSession ->
                sessionManager.saveSessionCalibration(activeSession, consolidated)
            }
        }
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Saved baseline calibration: Pitch=${consolidated.pitchDeg}°, Yaw=${consolidated.yawDeg}°, fx=${consolidated.fx}, HFOV=${consolidated.hfovDeg}°")
    }

    fun loadSavedCalibration() {
        val saved = _savedBaselineParams.value ?: return
        _calibrationParams.value = saved
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Restored saved baseline calibration: Pitch=${saved.pitchDeg}°, Yaw=${saved.yawDeg}°")
    }

    fun resetAnglesToZero() {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            pitchDeg = 0f,
            yawDeg = 0f,
            nudgePitchDeg = 0f,
            nudgeYawDeg = 0f
        )
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Reset Pitch & Yaw angles to zero (0.0°)")
    }

    fun resetPitchToZero() {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            pitchDeg = 0f,
            nudgePitchDeg = 0f
        )
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Reset Pitch angle to zero (0.0°)")
    }

    fun resetYawToZero() {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            yawDeg = 0f,
            nudgeYawDeg = 0f
        )
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Reset Yaw angle to zero (0.0°)")
    }

    fun resetRadarYawToZero() {
        resetYawToZero()
    }

    fun toggleLiveImuHorizon() {
        val next = !_isLiveImuHorizonEnabled.value
        _isLiveImuHorizonEnabled.value = next
        if (next && !imuManager.telemetryState.value.isMonitoring) {
            imuMonitoringStartedByCalibration = true
            startImuMonitoring()
        }
        com.bajajauto.roadsense.logging.AppLogger.i("Calibration", "Live IMU Horizon Follow toggled: $next")
    }

    fun setLiveImuHorizonEnabled(enabled: Boolean) {
        _isLiveImuHorizonEnabled.value = enabled
        if (enabled && !imuManager.telemetryState.value.isMonitoring) {
            imuMonitoringStartedByCalibration = true
            startImuMonitoring()
        }
    }

    /**
     * Feature A: One-Tap Sync Pitch & Roll from IMU.
     * Instantly transfers the smartphone's gravitational pitch and clamp roll
     * into the camera calibration baseline.
     */
    fun syncPitchFromImu() {
        if (!imuManager.telemetryState.value.isMonitoring) {
            startImuMonitoring()
        }
        val imuState = imuTelemetry.value
        val current = _calibrationParams.value
        val updated = current.copy(
            pitchDeg = imuState.pitchDeg,
            rollDeg = imuState.rollDeg,
            nudgePitchDeg = 0f
        )
        _calibrationParams.value = updated
        com.bajajauto.roadsense.logging.AppLogger.i("Calibration", "Synced camera pitch from IMU: ${imuState.pitchDeg}°, roll: ${imuState.rollDeg}°")
    }

    /**
     * Feature C: Lock Live IMU Pitch.
     * Freezes the active live-followed pitch and roll into the calibration baseline
     * and terminates live follow mode.
     */
    fun lockLiveImuPitch() {
        syncPitchFromImu()
        _isLiveImuHorizonEnabled.value = false
        com.bajajauto.roadsense.logging.AppLogger.i("Calibration", "Locked live IMU pitch into calibration baseline")
    }

    fun revertNudges() {
        val current = _calibrationParams.value
        _calibrationParams.value = current.copy(
            nudgePitchDeg = 0f,
            nudgeYawDeg = 0f
        )
    }

    fun resetCalibrationToDefaults() {
        val defaults = CalibrationParameters()
        _calibrationParams.value = defaults
        viewModelScope.launch {
            calibrationStorage.saveCalibration(defaults)
        }
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "Reset calibration to defaults")
    }

    // --- IMU Subsystem Actions ---
    fun startImuBenchmark(sensorType: Int, preset: ImuRatePreset): Boolean {
        return imuManager.startBenchmark(sensorType, preset)
    }

    fun stopImuBenchmark() {
        imuManager.stopBenchmark()
    }

    fun startImuMonitoring(delayUs: Int = com.bajajauto.roadsense.imu.ImuManager.DEFAULT_SAMPLING_DELAY_US): Boolean {
        return imuManager.startLiveMonitoring(delayUs)
    }

    fun stopImuMonitoring() {
        imuManager.stopLiveMonitoring()
    }

    fun auditImuSensors() {
        imuManager.auditSensors()
    }

    override fun onCleared() {
        super.onCleared()
        isRadarWorkerRunning = false
        radarProcessingThread?.interrupt()
        thermalMonitor.release()
        CrashRecoveryHandler.emergencyFlushCallback = null
        RecordingForegroundService.onStopRequested = null
        RecordingForegroundService.stop(getApplication())
        displayPacer.release()
        imuSessionRecorder.stopRecording()
        imuManager.release()
        canedgeIngestionManager.shutdown()
        canedgeDiscovery.disconnect()
        cameraEngine.release()
        cameraSessionRecorder.release()
        gnssLocationManager.release()
        gnssSessionRecorder.release()
        sessionRecorder.release()
        rawRecorder.release()
        connectionManager.release()
    }
}
