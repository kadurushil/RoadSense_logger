package com.bajajauto.roadsense.canedge.ingestion

import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.canedge.discovery.CanedgeDiscovery
import com.bajajauto.roadsense.canedge.model.CanedgeConnectionState
import com.bajajauto.roadsense.canedge.model.CanedgeDevice
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import com.bajajauto.roadsense.canedge.model.CanedgeFileStatus
import com.bajajauto.roadsense.canedge.model.CanedgeHudPhase
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.canedge.model.CanedgeUnifiedFileItem
import com.bajajauto.roadsense.canedge.network.CanedgeHttpClient
import com.bajajauto.roadsense.canedge.repository.CanedgeRepository
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.recording.SessionInfo
import com.bajajauto.roadsense.recording.SessionManager
import com.bajajauto.roadsense.storage.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Autonomous, idempotent CANedge2 ingestion engine:
 * - Runs a 1-second background watchdog that auto-connects to known hosts (5s cadence), pulls
 *   closed `.MF4` chunks whenever idle, pauses immediately when recording starts, and resumes
 *   1.5s after recording stops (including waiting for the tail 60s chunk to close).
 * - Maintains `can_sync_manifest.json` + `pool_index.json` + `Mdf4HeaderInspector` metadata so
 *   delayed/offline sessions ("Sync Later") reconcile accurately without `File.lastModified()`.
 * - Deduplicates downloads across BOTH `canedge_pool/` and all `sessions/<session_id>/can/` dirs.
 */
class CanedgeIngestionManager(
    private val discovery: CanedgeDiscovery,
    private val repository: CanedgeRepository,
    private val httpClient: CanedgeHttpClient,
    private val sessionManager: SessionManager,
    private val appPreferences: AppPreferences? = null
) {

    companion object {
        private const val TAG = "CanedgeIngestion"
        private const val POOL_DIR_NAME = "canedge_pool"
        private const val MANIFEST_FILE_NAME = "can_sync_manifest.json"
        private const val POOL_INDEX_FILE_NAME = "pool_index.json"

        private const val IDLE_FOLDER_SCOPE = 2
        private const val PENDING_FOLDER_SCOPE = 10
        private const val MAX_IDLE_POOL_FILES = 25
        private const val MAX_POOL_FILES = 50
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val OFFLINE_PROBE_INTERVAL_MS = 5_000L
        private const val WAITING_TAIL_POLL_INTERVAL_MS = 5_000L
        private const val PENDING_SESSION_POLL_INTERVAL_MS = 8_000L
        private const val IDLE_POLL_INTERVAL_MS = 12_000L
        private const val POST_STOP_SETTLE_MS = 1_500L
        private const val CLOCK_PREF_MAX_AGE_MS = 24L * 3_600_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var watchdogJob: Job? = null

    private val poolDir: File = File(sessionManager.sessionsBaseDir, POOL_DIR_NAME).apply { mkdirs() }
    private val manifestFile: File = File(sessionManager.sessionsBaseDir, MANIFEST_FILE_NAME)
    private val poolIndexFile: File = File(poolDir, POOL_INDEX_FILE_NAME)

    private val stateLock = Any()
    private val recentSessionsByDir = ConcurrentHashMap<String, SessionInfo>()
    private val prevRemoteFilesByPath = ConcurrentHashMap<String, CanedgeFile>()

    @Volatile
    private var prevPollWallMs: Long = 0L

    @Volatile
    private var lastRemotePollMs: Long = 0L

    @Volatile
    private var lastOfflineProbeMs: Long = 0L

    @Volatile
    private var postStopSettleUntilMs: Long = 0L

    @Volatile
    private var pendingSyncRequested: Boolean = false

    @Volatile
    private var autoReconnectEnabled: Boolean = true

    @Volatile
    private var activeSession: SessionInfo? = null

    @Volatile
    private var measuredClockOffsetMs: Long? = null

    @Volatile
    private var cachedManifest: CanSyncManifest = CanSyncManifest.load(manifestFile)

    @Volatile
    private var cachedPoolCount: Int = 0

    @Volatile
    private var localSnapshotDirty: Boolean = true

    private val _stats = MutableStateFlow(CanedgeSyncStats())
    val stats: StateFlow<CanedgeSyncStats> = _stats.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<CanedgeFile>>(emptyList())
    val remoteFiles: StateFlow<List<CanedgeFile>> = _remoteFiles.asStateFlow()

    private val _localSyncedFiles = MutableStateFlow<List<File>>(emptyList())
    val localSyncedFiles: StateFlow<List<File>> = _localSyncedFiles.asStateFlow()

    private val _localSessionFiles = MutableStateFlow<List<File>>(emptyList())
    val localSessionFiles: StateFlow<List<File>> = _localSessionFiles.asStateFlow()

    private val _unifiedFiles = MutableStateFlow<List<CanedgeUnifiedFileItem>>(emptyList())
    val unifiedFiles: StateFlow<List<CanedgeUnifiedFileItem>> = _unifiedFiles.asStateFlow()

    init {
        cleanOrphanDownloads()
        backfillPoolIndexFromDisk()
        loadInitialPersistedOffset()
        importUnreconciledDiskSessions()
        refreshLocalFileList()
        recomputeIdleStats()
    }

    /**
     * Starts the 1-second continuous watchdog loop (safe to call repeatedly; starts only once).
     */
    fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            AppLogger.i(TAG, "Started 1s CANedge2 background sync watchdog")
            while (isActive) {
                try {
                    tickWatchdog()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Watchdog tick error: ${e.message}")
                }
                delay(WATCHDOG_TICK_MS)
            }
        }
    }

    /**
     * Shuts down background coroutines when the ViewModel is cleared.
     */
    fun shutdown() {
        watchdogJob?.cancel()
        syncJob?.cancel()
        scope.cancel()
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
    }

    private suspend fun tickWatchdog() {
        val nowMs = System.currentTimeMillis()
        val recording = activeSession != null
        val connState = discovery.connectionState.value

        if (recording) {
            if (syncJob?.isActive == true) {
                AppLogger.i(TAG, "Pausing CANedge sync while drive recording is active")
                syncJob?.cancel()
            }
            if (!_stats.value.isPausedDuringRecording || _stats.value.hudPhase != CanedgeHudPhase.PAUSED_RECORDING) {
                updateStatsSnapshot(
                    connState,
                    overridePhase = CanedgeHudPhase.PAUSED_RECORDING,
                    isSyncing = false,
                    isPaused = true
                )
            }
            return
        }

        when (connState) {
            is CanedgeConnectionState.Disconnected,
            is CanedgeConnectionState.Error -> {
                if (autoReconnectEnabled && (nowMs - lastOfflineProbeMs) >= OFFLINE_PROBE_INTERVAL_MS) {
                    lastOfflineProbeMs = nowMs
                    val found = discovery.probeKnownHost()
                    if (found) {
                        pendingSyncRequested = true
                        return
                    }
                }
                val expectedPhase = when {
                    connState is CanedgeConnectionState.Error -> CanedgeHudPhase.ERROR
                    cachedManifest.pendingCount() > 0 -> CanedgeHudPhase.OFFLINE_PENDING
                    else -> CanedgeHudPhase.OFFLINE
                }
                if (_stats.value.hudPhase != expectedPhase || _stats.value.isPausedDuringRecording) {
                    updateStatsSnapshot(discovery.connectionState.value, isPaused = false)
                }
            }

            is CanedgeConnectionState.Scanning -> {
                if (_stats.value.hudPhase != CanedgeHudPhase.SCANNING || _stats.value.isPausedDuringRecording) {
                    updateStatsSnapshot(connState, isPaused = false)
                }
            }

            is CanedgeConnectionState.Connected -> {
                val device = connState.device
                if (syncJob?.isActive == true) return
                if (nowMs < postStopSettleUntilMs) {
                    if (_stats.value.isPausedDuringRecording) {
                        updateStatsSnapshot(connState, isPaused = false)
                    }
                    return
                }

                val manifest = cachedManifest
                val hasWaitingTail = manifest.sessions.any { it.status == CanSessionSyncStatus.WAITING_FINAL_CHUNK }
                val hasPending = manifest.pendingCount() > 0
                val intervalMs = when {
                    pendingSyncRequested -> 0L
                    hasWaitingTail -> WAITING_TAIL_POLL_INTERVAL_MS
                    hasPending -> PENDING_SESSION_POLL_INTERVAL_MS
                    else -> IDLE_POLL_INTERVAL_MS
                }

                if ((nowMs - lastRemotePollMs) >= intervalMs) {
                    pendingSyncRequested = false
                    launchSyncPass(device, isPostStopTrigger = hasWaitingTail || hasPending)
                } else {
                    val expectedConnectedPhase = CanedgeSyncPlanner.resolveHudPhase(
                        isPausedDuringRecording = false,
                        isSyncing = false,
                        connState = connState,
                        pendingSessionsCount = manifest.pendingCount(),
                        waitingAnyFinalChunk = hasWaitingTail,
                        hasVerifiedClockOffset = hasVerifiedClockOffset(nowMs)
                    )
                    if (_stats.value.hudPhase != expectedConnectedPhase || _stats.value.isPausedDuringRecording) {
                        updateStatsSnapshot(connState, isPaused = false)
                    }
                }
            }
        }
    }

    /**
     * Triggered when the user starts a RoadSense drive session.
     * Immediately pauses background CANedge transfers so Wi-Fi/CPU remain dedicated to sensors.
     */
    fun onSessionStarted(session: SessionInfo) {
        activeSession = session
        recentSessionsByDir[session.sessionDir.name] = session
        syncJob?.cancel()
        cleanOrphanDownloads()

        val offsetMs = resolveEffectiveClockOffsetMs(System.currentTimeMillis())
        synchronized(stateLock) {
            val updated = cachedManifest.upsertSession(
                CanSessionSyncRecord(
                    sessionId = session.sessionId,
                    sessionDirName = session.sessionDir.name,
                    startTimeWallMs = session.startTimeWallMs,
                    stopTimeWallMs = null,
                    status = CanSessionSyncStatus.PENDING,
                    clockOffsetMs = offsetMs,
                    lastAttemptMs = System.currentTimeMillis()
                )
            )
            saveManifestLocked(updated)
        }

        _localSessionFiles.value = emptyList()
        AppLogger.i(TAG, "[CAN_DIAG] RECORDING_PAUSED session=${session.sessionId} status=PENDING")
        if (DebugToggles.isCanedgeVerboseActive || DebugToggles.isSessionLifecycleDebugActive) {
            val queuedIds = synchronized(stateLock) {
                cachedManifest.sessions
                    .filter { it.status != CanSessionSyncStatus.COMPLETE }
                    .map { "${it.sessionId}(${it.status.name})" }
            }
            AppLogger.i(
                TAG,
                "[CAN_TRANSITION] PAUSE_FOR_NEW_SESSION new=${session.sessionId} pendingQueueCount=${queuedIds.size} queued=$queuedIds"
            )
        }
        updateStatsSnapshot(
            discovery.connectionState.value,
            overridePhase = CanedgeHudPhase.PAUSED_RECORDING,
            isSyncing = false,
            isPaused = true,
            currentSessionFiles = 0,
            sessionTargetFiles = 0
        )
    }

    /**
     * Triggered AFTER `sessionRecorder.stopSession()` completes so `stopTimeWallMs` is populated.
     * Queues the session in `can_sync_manifest.json` and arms automatic resumption after 1.5s settle.
     */
    fun onSessionStopped(stoppedSession: SessionInfo? = null) {
        val session = stoppedSession ?: activeSession
        activeSession = null
        val nowMs = System.currentTimeMillis()

        if (session != null) {
            recentSessionsByDir[session.sessionDir.name] = session
            val stopWallMs = session.stopTimeWallMs ?: nowMs
            val offsetMs = resolveEffectiveClockOffsetMs(nowMs)

            synchronized(stateLock) {
                val manifest = cachedManifest
                val existing = manifest.sessions.firstOrNull { it.sessionDirName == session.sessionDir.name }
                val rec = (existing ?: CanSessionSyncRecord(
                    sessionId = session.sessionId,
                    sessionDirName = session.sessionDir.name,
                    startTimeWallMs = session.startTimeWallMs
                )).copy(
                    stopTimeWallMs = stopWallMs,
                    status = CanSessionSyncStatus.PENDING,
                    clockOffsetMs = existing?.clockOffsetMs ?: offsetMs,
                    lastAttemptMs = nowMs
                )
                saveManifestLocked(manifest.upsertSession(rec))
            }

            session.canSyncStatus = CanSessionSyncStatus.PENDING.name
            session.canClockOffsetMs = offsetMs
            sessionManager.writeMetadata(session)

            rescueSessionFromPoolOffline(session.sessionDir.name)
            AppLogger.i(
                TAG,
                "[CAN_DIAG] SESSION_QUEUED session=${session.sessionId} start=${session.startTimeWallMs} stop=$stopWallMs offsetMs=$offsetMs"
            )
            if (DebugToggles.isCanedgeVerboseActive || DebugToggles.isSessionLifecycleDebugActive) {
                val pendingIds = synchronized(stateLock) {
                    cachedManifest.sessions
                        .filter { it.status != CanSessionSyncStatus.COMPLETE && it.stopTimeWallMs != null }
                        .map { "${it.sessionId}(${it.status.name})" }
                }
                AppLogger.i(
                    TAG,
                    "[CAN_TRANSITION] RESUME_POST_STOP settleMs=$POST_STOP_SETTLE_MS pendingCount=${pendingIds.size} pending=$pendingIds"
                )
            }
        }

        postStopSettleUntilMs = nowMs + POST_STOP_SETTLE_MS
        pendingSyncRequested = true
        updateStatsSnapshot(discovery.connectionState.value, isPaused = false, isFinalizing = (session != null))
    }

    /**
     * Manually triggers a sync pass (re-enables auto-reconnect if previously disconnected).
     */
    fun triggerSync() {
        autoReconnectEnabled = true
        localSnapshotDirty = true
        if (activeSession != null) {
            AppLogger.i(TAG, "Ignoring manual CANedge sync while drive recording is active")
            return
        }
        val device = discovery.currentDevice
        if (device == null) {
            pendingSyncRequested = true
            scope.launch {
                val found = discovery.probeKnownHost()
                if (found) {
                    discovery.currentDevice?.let { launchSyncPass(it, isPostStopTrigger = false) }
                } else {
                    discovery.startDiscovery { ok ->
                        if (ok) {
                            discovery.currentDevice?.let { launchSyncPass(it, isPostStopTrigger = false) }
                        }
                    }
                }
            }
            return
        }
        if (syncJob?.isActive == true) {
            pendingSyncRequested = true
            return
        }
        launchSyncPass(device, isPostStopTrigger = false)
    }

    private fun launchSyncPass(device: CanedgeDevice, isPostStopTrigger: Boolean) {
        syncJob?.cancel()
        syncJob = scope.launch {
            runSyncPass(device, isPostStopTrigger)
        }
    }

    private suspend fun runSyncPass(device: CanedgeDevice, isPostStopTrigger: Boolean) {
        val passStartMs = System.currentTimeMillis()
        lastRemotePollMs = passStartMs

        if (activeSession != null) return

        val pendingCountBefore = cachedManifest.pendingCount()
        if (pendingCountBefore > 0 && isPostStopTrigger) {
            updateStatsSnapshot(
                discovery.connectionState.value,
                isSyncing = true,
                isFinalizing = true,
                lastError = null
            )
        }

        try {
            // 1. Scan 2 newest folders when idle, or up to 10 folders when pending sessions need reconciliation
            val scanFolders = if (pendingCountBefore > 0) PENDING_FOLDER_SCOPE else IDLE_FOLDER_SCOPE
            val scannedFiles = repository.listRecentSessionMf4Files(device, maxFolders = scanFolders)
            if (activeSession != null) return

            if (scannedFiles.isEmpty()) {
                val stillAlive = httpClient.verifyDeviceAt(device.ipAddress, timeoutMs = 1500) != null
                if (!stillAlive) {
                    discovery.markConnectionLost("HTTP scan returned no data / device unreachable")
                    reconcilePendingSessionsOffline()
                    updateStatsSnapshot(discovery.connectionState.value, isSyncing = false, isFinalizing = false)
                    return
                }
            }

            // 2. Measure RTC offset strictly from observed live rollover between consecutive polls
            val pollNowMs = System.currentTimeMillis()
            val prevSnapshot = prevRemoteFilesByPath.toMap()
            val remoteChanged = prevSnapshot.isEmpty() ||
                scannedFiles.size != prevSnapshot.size ||
                scannedFiles.any { rf ->
                    val prev = prevSnapshot[rf.path]
                    prev == null || prev.sizeBytes != rf.sizeBytes || prev.lastWrittenEpochSec != rf.lastWrittenEpochSec
                }

            val measured = CanedgeSyncPlanner.measureClockOffsetFromLiveTransition(
                previousFilesByPath = prevSnapshot,
                currentFiles = scannedFiles,
                prevPollWallMs = prevPollWallMs,
                nowPollWallMs = pollNowMs
            )
            if (remoteChanged) {
                prevRemoteFilesByPath.clear()
                scannedFiles.forEach { prevRemoteFilesByPath[it.path] = it }
                _remoteFiles.value = scannedFiles
            }
            prevPollWallMs = pollNowMs

            if (measured != null) {
                measuredClockOffsetMs = measured
                appPreferences?.canedgeLastClockOffsetMs = measured
                appPreferences?.canedgeLastClockMeasuredAtMs = pollNowMs
                synchronized(stateLock) {
                    saveManifestLocked(
                        cachedManifest.copy(lastClockOffsetMs = measured, lastClockMeasuredAtMs = pollNowMs)
                    )
                }
                AppLogger.i(TAG, "Measured live CANedge2 clock offset from chunk rollover: ${measured} ms")
            }

            val manifestSnapshot = cachedManifest
            val pendingSessions = manifestSnapshot.sessions.filter {
                it.status != CanSessionSyncStatus.COMPLETE && it.stopTimeWallMs != null
            }

            // Fast-path exit when remote directory hasn't changed and there are no pending sessions:
            // skips all local filesystem scans, pruning, explorer rebuilds, and UI invalidations.
            if (!remoteChanged && pendingSessions.isEmpty() && !localSnapshotDirty && _stats.value.lastSyncTimeMs > 0L) {
                if (DebugToggles.isCanedgeVerboseActive) {
                    AppLogger.d(TAG, "[CAN_DIAG] SYNC_FASTPATH remoteChanged=false pending=0 pool=$cachedPoolCount skipLocal=true")
                }
                return
            }

            val effectiveOffsetMs = resolveEffectiveClockOffsetMs(pollNowMs)
            val isClockOffsetVerified = hasVerifiedClockOffset(pollNowMs)
            val poolIndex = readPoolIndexSafe()
            val headerStartByLocalName = poolIndex.entries.associate { it.localName to it.headerStartTimeMs }
            val prioritySessionFiles = mutableListOf<CanedgeFile>()
            var anyWaitingFinalChunk = false

            for (rec in pendingSessions) {
                val plan = CanedgeSyncPlanner.planSessionChunks(
                    session = rec,
                    remoteFiles = scannedFiles,
                    clockOffsetMs = effectiveOffsetMs,
                    nowWallMs = pollNowMs,
                    isSessionCurrentlyRecording = false,
                    headerStartTimeByLocalName = headerStartByLocalName,
                    isClockOffsetVerified = isClockOffsetVerified
                )
                if (plan.waitingForFinalChunk) {
                    anyWaitingFinalChunk = true
                }
                for (rf in plan.matchedRemoteFiles) {
                    if (repository.isFileStable(rf, scannedFiles, effectiveOffsetMs, pollNowMs)) {
                        prioritySessionFiles.add(rf)
                    } else {
                        anyWaitingFinalChunk = true
                    }
                }
            }

            // 4. Add bounded idle pre-sync candidates (max 25 newest closed chunks across top 2 folders)
            val allStableRemote = scannedFiles.filter { rf ->
                repository.isFileStable(rf, scannedFiles, effectiveOffsetMs, pollNowMs)
            }
            val idlePoolCandidates = CanedgeSyncPlanner.selectIdlePoolCandidates(
                stableRemoteFiles = allStableRemote,
                maxFolders = IDLE_FOLDER_SCOPE,
                maxFiles = MAX_IDLE_POOL_FILES
            )
            val orderedCandidates = (prioritySessionFiles + idlePoolCandidates).distinctBy { it.path }

            // 5. Strict idempotent dedup across BOTH canedge_pool/ AND all sessions/session_<id>/can/
            val poolCount = poolDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.size ?: 0
            val localSizesByName = collectAllLocalSizesByName()
            val filesToDownload = CanedgeSyncPlanner.filterFilesNeedingDownload(orderedCandidates, localSizesByName)

            val planSummary =
                "[CAN_DIAG] SYNC_PLAN remote=${scannedFiles.size} stable=${allStableRemote.size} scanFolders=$scanFolders " +
                        "sessionPrio=${prioritySessionFiles.size} idleCap=${idlePoolCandidates.size} " +
                        "localUnique=${localSizesByName.size} pool=$poolCount toDownload=${filesToDownload.size} offsetMs=${effectiveOffsetMs}"
            if (filesToDownload.isNotEmpty() || pendingSessions.isNotEmpty()) {
                AppLogger.i(TAG, planSummary)
                updateStatsSnapshot(
                    discovery.connectionState.value,
                    isSyncing = filesToDownload.isNotEmpty(),
                    isFinalizing = isPostStopTrigger && pendingSessions.isNotEmpty(),
                    lastError = null
                )
                if (filesToDownload.isNotEmpty() && DebugToggles.isCanedgeVerboseActive) {
                    val preview = filesToDownload.take(6).joinToString(", ") { it.localName }
                    AppLogger.d(
                        TAG,
                        "[CAN_DIAG] DOWNLOAD_QUEUE count=${filesToDownload.size} preview=[$preview${if (filesToDownload.size > 6) ", ..." else ""}]"
                    )
                }
            } else if (DebugToggles.isCanedgeVerboseActive) {
                AppLogger.d(TAG, planSummary)
            }

            // 6. Download needed chunks sequentially with zero-cost pause on recording start
            val totalQueue = filesToDownload.size
            var completedQueue = 0
            for (rf in filesToDownload) {
                if (activeSession != null) {
                    throw CancellationException("Paused for active drive recording")
                }

                val targetPoolFile = File(poolDir, rf.localName)
                val tempDownloadFile = File(poolDir, "${rf.localName}.download")

                val ok = downloadFileWithRetry(device, rf, tempDownloadFile, targetPoolFile, completedQueue, totalQueue)
                if (!ok) {
                    val probeAlive = httpClient.verifyDeviceAt(device.ipAddress, timeoutMs = 1500) != null
                    if (!probeAlive) {
                        discovery.markConnectionLost("Transfer interrupted: CANedge unreachable")
                        break
                    }
                    continue
                }

                completedQueue++
                localSnapshotDirty = true
                val headerInfo = Mdf4HeaderInspector.inspect(targetPoolFile)
                val entry = PoolIndexEntry(
                    localName = rf.localName,
                    remotePath = rf.path,
                    folderId = rf.folderName,
                    sizeBytes = targetPoolFile.length(),
                    remoteWrittenEpochSec = rf.lastWrittenEpochSec,
                    headerStartTimeMs = headerInfo?.startTimeMs ?: 0L,
                    downloadedAtMs = System.currentTimeMillis()
                )
                synchronized(stateLock) {
                    val idx = PoolIndex.load(poolIndexFile).upsert(entry)
                    PoolIndex.save(poolIndexFile, idx)
                }
            }

            // 7. Reconcile all pending/waiting/partial sessions using newly-pooled files
            val reconciliationResult = if (pendingSessions.isNotEmpty()) {
                localSnapshotDirty = true
                val reconcileNowMs = System.currentTimeMillis()
                reconcilePendingSessionsOnline(
                    scannedFiles = scannedFiles,
                    effectiveOffsetMs = resolveEffectiveClockOffsetMs(reconcileNowMs),
                    nowWallMs = reconcileNowMs,
                    isClockOffsetVerified = hasVerifiedClockOffset(reconcileNowMs)
                )
            } else {
                ReconcileSummary(false, _stats.value.currentSessionFiles, _stats.value.sessionTargetFiles)
            }

            // 8. Prune & refresh local view ONCE only if files changed, pending reconciled, or snapshot was dirty
            if (completedQueue > 0 || localSnapshotDirty || pendingSessions.isNotEmpty()) {
                pruneStagingPool(refreshUi = false)
                refreshLocalFileList()
                localSnapshotDirty = false
            }

            val updatedManifest = cachedManifest
            val finalWaiting = anyWaitingFinalChunk || reconciliationResult.waitingFinalChunk
            val targetScopeClosedCount = allStableRemote.size
            val updatedLocalSizes = if (completedQueue > 0 || pendingSessions.isNotEmpty()) {
                collectAllLocalSizesByName()
            } else {
                localSizesByName
            }
            val syncedScopeCount = scannedFiles.count { rf ->
                val sz = updatedLocalSizes[rf.localName]
                sz != null && sz == rf.sizeBytes
            }

            updateStatsSnapshot(
                connState = discovery.connectionState.value,
                targetScopeFiles = targetScopeClosedCount,
                poolSyncedFiles = syncedScopeCount,
                isSyncing = false,
                isFinalizing = false,
                isPaused = false,
                isWaitingTail = finalWaiting,
                lastSyncTimeMs = System.currentTimeMillis(),
                queueCompleted = completedQueue,
                queueTotal = totalQueue,
                activeFileName = null,
                activeBytes = 0L,
                activeTotal = 0L,
                speedBps = 0L,
                etaSec = 0,
                currentSessionFiles = reconciliationResult.latestSessionStagedCount,
                sessionTargetFiles = reconciliationResult.latestSessionMatchedCount,
                manifestOverride = updatedManifest
            )
        } catch (e: CancellationException) {
            cleanOrphanDownloads()
            updateStatsSnapshot(
                discovery.connectionState.value,
                isSyncing = false,
                isFinalizing = false,
                isPaused = (activeSession != null),
                activeFileName = null
            )
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "CANedge sync pass error: ${e.message}", e)
            cleanOrphanDownloads()
            updateStatsSnapshot(
                discovery.connectionState.value,
                isSyncing = false,
                isFinalizing = false,
                lastError = e.message ?: "Sync error",
                activeFileName = null
            )
        }
    }

    private suspend fun downloadFileWithRetry(
        device: CanedgeDevice,
        remoteFile: CanedgeFile,
        tempFile: File,
        destFile: File,
        completedQueue: Int,
        totalQueue: Int
    ): Boolean {
        for (attempt in 1..2) {
            if (activeSession != null) throw CancellationException("Paused for recording")
            tempFile.delete()
            var lastSpeedBps = 0L
            var lastTickAt = System.currentTimeMillis()
            var lastTickBytes = 0L

            val remoteUrl = "${device.apiBaseUrl}${remoteFile.path.trimStart('/')}"
            val downloaded = httpClient.downloadToFile(
                urlStr = remoteUrl,
                destinationFile = tempFile
            ) { bytesTransferred, totalBytes ->
                val now = System.currentTimeMillis()
                val dt = now - lastTickAt
                if (dt >= 350L || bytesTransferred >= totalBytes) {
                    val dBytes = bytesTransferred - lastTickBytes
                    if (dt > 0L && dBytes > 0L) {
                        lastSpeedBps = (dBytes * 1000L) / dt
                    }
                    lastTickAt = now
                    lastTickBytes = bytesTransferred
                    val effectiveTotal = if (totalBytes > 0L) totalBytes else remoteFile.sizeBytes
                    val remaining = (effectiveTotal - bytesTransferred).coerceAtLeast(0L)
                    val eta = if (lastSpeedBps > 0L) (remaining / lastSpeedBps).toInt() else 0
                    val progress =
                        if (effectiveTotal > 0L) (bytesTransferred.toFloat() / effectiveTotal).coerceIn(0f, 1f) else 0f

                    _stats.value = _stats.value.copy(
                        hudPhase = CanedgeHudPhase.SYNCING,
                        isSyncing = true,
                        activeFileName = remoteFile.localName,
                        activeFileBytesTransferred = bytesTransferred,
                        activeFileTotalBytes = effectiveTotal,
                        activeFileProgress = progress,
                        transferSpeedBytesPerSec = lastSpeedBps,
                        etaSeconds = eta,
                        queueCompletedFiles = completedQueue,
                        queueTotalFiles = totalQueue
                    )
                }
            }

            if (downloaded && tempFile.exists() && tempFile.length() > 0L) {
                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                AppLogger.i(TAG, "Downloaded ${remoteFile.localName} (${destFile.length()} bytes) on attempt $attempt")
                return true
            }

            tempFile.delete()
            if (attempt < 2) {
                delay(800L)
            }
        }
        AppLogger.w(TAG, "Failed downloading ${remoteFile.path} after retries")
        return false
    }

    private data class ReconcileSummary(
        val waitingFinalChunk: Boolean,
        val latestSessionStagedCount: Int,
        val latestSessionMatchedCount: Int
    )

    private fun reconcilePendingSessionsOnline(
        scannedFiles: List<CanedgeFile>,
        effectiveOffsetMs: Long,
        nowWallMs: Long,
        isClockOffsetVerified: Boolean = true
    ): ReconcileSummary {
        val manifest = readManifestSafe()
        val poolIndex = readPoolIndexSafe()
        val headerMap = poolIndex.entries.associate { it.localName to it.headerStartTimeMs }
        val localFilesByName = collectAllLocalFileRefsByName()

        var updatedManifest = manifest
        var anyWaitingTail = false
        var latestStaged = 0
        var latestMatched = 0

        val pending = manifest.sessions.filter {
            it.status != CanSessionSyncStatus.COMPLETE && it.stopTimeWallMs != null
        }

        for (rec in pending) {
            val sessionDir = File(sessionManager.sessionsBaseDir, rec.sessionDirName)
            if (!sessionDir.exists()) {
                updatedManifest = updatedManifest.upsertSession(
                    rec.copy(
                        status = CanSessionSyncStatus.COMPLETE,
                        notes = "Session directory missing locally",
                        lastAttemptMs = nowWallMs
                    )
                )
                continue
            }

            val plan = CanedgeSyncPlanner.planSessionChunks(
                session = rec,
                remoteFiles = scannedFiles,
                clockOffsetMs = effectiveOffsetMs,
                nowWallMs = nowWallMs,
                isSessionCurrentlyRecording = false,
                headerStartTimeByLocalName = headerMap,
                isClockOffsetVerified = isClockOffsetVerified
            )

            val canDir = File(sessionDir, "can").apply { mkdirs() }
            val matchedPaths = (rec.matchedRemotePaths + plan.matchedRemoteFiles.map { it.path }).distinct().sorted()
            val stagedNames = stageLocalFilesIntoSessionCanDir(
                canDir = canDir,
                targetLocalNames = plan.matchedRemoteFiles.map { it.localName },
                localFilesByName = localFilesByName
            )

            val allMatchedStaged = plan.matchedRemoteFiles.all { rf ->
                val dest = File(canDir, rf.localName)
                dest.exists() && dest.length() == rf.sizeBytes && rf.sizeBytes > 0L
            }

            val existingCanNames = canDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }
                ?.map { it.name }
                .orEmpty()
                .sorted()

            val newStatus = when {
                plan.waitingForFinalChunk -> {
                    anyWaitingTail = true
                    CanSessionSyncStatus.WAITING_FINAL_CHUNK
                }

                plan.matchedRemoteFiles.isNotEmpty() && allMatchedStaged -> CanSessionSyncStatus.COMPLETE
                plan.matchedRemoteFiles.isEmpty() -> CanSessionSyncStatus.COMPLETE
                else -> CanSessionSyncStatus.PARTIAL
            }
            if (DebugToggles.isCanedgeVerboseActive || DebugToggles.isSessionLifecycleDebugActive) {
                AppLogger.i(
                    TAG,
                    "[CAN_TRANSITION] RECONCILE_SESSION session=${rec.sessionId} matched=${plan.matchedRemoteFiles.size} staged=${existingCanNames.size} waitingTail=${plan.waitingForFinalChunk} status=${newStatus.name}"
                )
            }

            val updatedRec = rec.copy(
                status = newStatus,
                clockOffsetMs = effectiveOffsetMs,
                matchedRemotePaths = matchedPaths,
                stagedLocalNames = existingCanNames,
                lastAttemptMs = nowWallMs,
                notes = when {
                    plan.matchedRemoteFiles.isEmpty() && existingCanNames.isNotEmpty() && newStatus == CanSessionSyncStatus.COMPLETE ->
                        "Retained ${existingCanNames.size} pre-existing CAN files"
                    plan.matchedRemoteFiles.isEmpty() && newStatus == CanSessionSyncStatus.COMPLETE ->
                        "No matching CAN chunks recorded during drive window"
                    else -> null
                }
            )
            updatedManifest = updatedManifest.upsertSession(updatedRec)
            AppLogger.i(
                TAG,
                "[CAN_DIAG] RECONCILE session=${rec.sessionDirName} transition=${rec.status}->${updatedRec.status} matched=${plan.matchedRemoteFiles.size} staged=${updatedRec.stagedLocalNames.size} waitingTail=${plan.waitingForFinalChunk} offsetMs=${effectiveOffsetMs} offsetVerified=$isClockOffsetVerified"
            )

            val countChanged = updatedRec.stagedLocalNames.size != rec.stagedLocalNames.size
            val statusChanged = updatedRec.status != rec.status
            if (statusChanged || countChanged) {
                persistSessionMetadataAndTimeline(
                    sessionDir,
                    updatedRec,
                    newlyStaged = stagedNames,
                    offsetMs = effectiveOffsetMs
                )
            }

            latestStaged = updatedRec.stagedLocalNames.size
            latestMatched = updatedRec.matchedRemotePaths.size
        }

        synchronized(stateLock) {
            saveManifestLocked(updatedManifest)
        }
        return ReconcileSummary(anyWaitingTail, latestStaged, latestMatched)
    }

    private fun reconcilePendingSessionsOffline() {
        val manifest = readManifestSafe()
        manifest.sessions
            .filter { it.status != CanSessionSyncStatus.COMPLETE && it.stopTimeWallMs != null }
            .forEach { rec -> rescueSessionFromPoolOffline(rec.sessionDirName) }
    }

    private fun rescueSessionFromPoolOffline(sessionDirName: String) {
        val manifest = readManifestSafe()
        val rec = manifest.sessions.firstOrNull { it.sessionDirName == sessionDirName } ?: return
        if (rec.stopTimeWallMs == null) return

        backfillPoolIndexFromDisk()
        val poolIndex = readPoolIndexSafe()
        val artifacts = poolIndex.entries.mapNotNull { entry ->
            val file = File(poolDir, entry.localName)
            if (!file.exists() || file.length() <= 0L) null
            else PoolArtifact(
                localName = entry.localName,
                sizeBytes = file.length(),
                remoteWrittenEpochSec = entry.remoteWrittenEpochSec,
                headerStartTimeMs = entry.headerStartTimeMs
            )
        }
        val offsetMs = rec.clockOffsetMs ?: resolveEffectiveClockOffsetMs(System.currentTimeMillis())
        val matched = CanedgeSyncPlanner.planPoolRescueForSession(rec, artifacts, offsetMs)
        if (matched.isEmpty()) return

        val sessionDir = File(sessionManager.sessionsBaseDir, sessionDirName)
        val canDir = File(sessionDir, "can").apply { mkdirs() }
        val localFilesByName = collectAllLocalFileRefsByName()
        val staged = stageLocalFilesIntoSessionCanDir(canDir, matched.map { it.localName }, localFilesByName)
        if (staged.isNotEmpty()) {
            localSnapshotDirty = true
            val allInCan =
                canDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.map { it.name }.orEmpty().sorted()
            val updatedRec = rec.copy(
                stagedLocalNames = allInCan,
                lastAttemptMs = System.currentTimeMillis()
            )
            synchronized(stateLock) {
                saveManifestLocked(cachedManifest.upsertSession(updatedRec))
            }
            persistSessionMetadataAndTimeline(sessionDir, updatedRec, newlyStaged = staged, offsetMs = offsetMs)
        }
    }

    private fun stageLocalFilesIntoSessionCanDir(
        canDir: File,
        targetLocalNames: List<String>,
        localFilesByName: Map<String, File>
    ): List<String> {
        val newlyStaged = mutableListOf<String>()
        for (localName in targetLocalNames) {
            val dest = File(canDir, localName)
            val src = localFilesByName[localName] ?: continue
            if (src.absolutePath == dest.absolutePath) continue
            if (dest.exists() && dest.length() == src.length() && dest.length() > 0L) continue
            try {
                src.copyTo(dest, overwrite = true)
                newlyStaged.add(localName)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed staging $localName -> ${canDir.path}: ${e.message}")
            }
        }
        return newlyStaged
    }

    private fun persistSessionMetadataAndTimeline(
        sessionDir: File,
        rec: CanSessionSyncRecord,
        newlyStaged: List<String>,
        offsetMs: Long
    ) {
        val memSession = recentSessionsByDir[sessionDir.name]
        if (memSession != null) {
            memSession.canSyncStatus = rec.status.name
            memSession.canClockOffsetMs = offsetMs
            sessionManager.writeMetadata(memSession)
        } else {
            val metaFile = File(sessionDir, SessionManager.METADATA_FILE_NAME)
            if (metaFile.exists()) {
                try {
                    val root = JSONObject(metaFile.readText())
                    val canDir = File(sessionDir, "can")
                    val canFiles = canDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) } ?: emptyArray()
                    val canObj = JSONObject().apply {
                        put("sensor", "CSS Electronics CANedge2")
                        put("totalFiles", canFiles.size)
                        put("syncStatus", rec.status.name)
                        put("clockOffsetMs", offsetMs)
                    }
                    root.put("can", canObj)
                    metaFile.writeText(root.toString(2))
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed updating metadata for ${sessionDir.name}: ${e.message}")
                }
            }
        }

        if (newlyStaged.isNotEmpty() || rec.status == CanSessionSyncStatus.COMPLETE) {
            val timelineFile = File(sessionDir, "session_timeline.csv")
            val marker = "CAN_SYNC_${rec.status.name}:${rec.stagedLocalNames.size}"
            val alreadyRecorded = try {
                timelineFile.exists() && timelineFile.readText().contains(marker)
            } catch (_: Exception) {
                false
            }
            if (!alreadyRecorded) {
                val activeRef = recentSessionsByDir[sessionDir.name]
                if (activeRef != null && activeSession?.sessionDir?.name == sessionDir.name) {
                    sessionManager.recordTimelineEvent(
                        elapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
                        sensor = "CAN",
                        event = marker,
                        sequenceId = rec.stagedLocalNames.size.toLong(),
                        relativePath = "can/",
                        summary = "${rec.stagedLocalNames.size} MF4 files (offset=${offsetMs}ms)"
                    )
                } else if (timelineFile.exists()) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                        val wall = sdf.format(Date())
                        val monoNs = android.os.SystemClock.elapsedRealtimeNanos()
                        timelineFile.appendText("$wall,$monoNs,CAN,$marker,${rec.stagedLocalNames.size},can/,\"${rec.stagedLocalNames.size} MF4 files (offset=${offsetMs}ms)\"\n")
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun refreshLocalFileListAsync() {
        scope.launch {
            refreshLocalFileList()
        }
    }

    fun refreshLocalFileList() {
        val poolFiles = poolDir.listFiles { _, name ->
            name.endsWith(".mf4", ignoreCase = true)
        }?.sortedByDescending { it.name } ?: emptyList()
        cachedPoolCount = poolFiles.size
        _localSyncedFiles.value = poolFiles

        val latestDirName = activeSession?.sessionDir?.name
            ?: cachedManifest.sessions.lastOrNull()?.sessionDirName
        val canFiles = if (latestDirName != null) {
            File(File(sessionManager.sessionsBaseDir, latestDirName), "can")
                .listFiles { _, name -> name.endsWith(".mf4", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        _localSessionFiles.value = canFiles
        updateUnifiedExplorerView(_remoteFiles.value)
    }

    /**
     * Safely prunes redundant files from `canedge_pool/` ONLY after they are safely staged
     * inside `COMPLETE` sessions, protecting all pending/partial/waiting sessions and recent
     * unstaged pre-sync pool entries.
     */
    fun pruneStagingPool(refreshUi: Boolean = true) {
        val manifest = cachedManifest
        val stagedInAllSessions = collectAllSessionCanFileNames()
        val poolFiles = poolDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: return

        val prunePlan = CanedgeSyncPlanner.planPoolPrune(
            poolLocalNamesSortedOldestFirst = poolFiles.map { it.name },
            manifest = manifest,
            allStagedLocalNames = stagedInAllSessions,
            maxPoolFiles = MAX_POOL_FILES
        )
        val toDelete = prunePlan.allToDelete.toSet()

        if (toDelete.isEmpty()) {
            cachedPoolCount = poolFiles.size
            return
        }

        var prunedStaged = 0
        var prunedOverflow = 0
        val stagedSet = prunePlan.stagedAndCompleteToDelete.toSet()
        for (f in poolFiles) {
            if (f.name in toDelete && f.delete()) {
                if (f.name in stagedSet) prunedStaged++ else prunedOverflow++
            }
        }

        val remainingNames = poolDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        cachedPoolCount = remainingNames.size
        synchronized(stateLock) {
            val cleanedIndex = PoolIndex.load(poolIndexFile).retainOnly(remainingNames)
            PoolIndex.save(poolIndexFile, cleanedIndex)
        }

        val totalPruned = prunedStaged + prunedOverflow
        if (totalPruned > 0) {
            val sample = toDelete.take(5).joinToString(", ")
            AppLogger.i(
                TAG,
                "[CAN_DIAG] PRUNE_POOL total=$totalPruned stagedComplete=$prunedStaged overflow=$prunedOverflow retained=${remainingNames.size} protected=${prunePlan.protectedNames.size} sample=[$sample]"
            )
        }
        if (refreshUi) {
            refreshLocalFileList()
        }
    }

    private fun updateUnifiedExplorerView(remoteList: List<CanedgeFile>) {
        val sessionCanNames = collectAllSessionCanFileNames()
        val localFilesByName = collectAllLocalFileRefsByName()
        val remoteByLocalName = remoteList.filter { it.isMf4 }.associateBy { it.localName }

        val combinedKeys = (remoteByLocalName.keys + localFilesByName.keys).distinct().sortedDescending()
        val items = combinedKeys.map { localName ->
            val rf = remoteByLocalName[localName]
            val localFile = localFilesByName[localName]
            val onCanedge = rf != null
            val onDevice = localFile != null && localFile.length() > 0L
            val inSession = localName in sessionCanNames

            val fileModel = rf ?: CanedgeFile(
                name = localName.substringAfter("_", localName),
                path = "/${localName.replace("_", "/")}",
                isDirectory = false,
                sizeBytes = localFile?.length() ?: 0L,
                lastWrittenEpochSec = (localFile?.lastModified() ?: 0L) / 1000L
            )

            val status = when {
                inSession -> CanedgeFileStatus.IN_SESSION
                onDevice -> CanedgeFileStatus.IN_POOL
                else -> CanedgeFileStatus.REMOTE_ONLY
            }

            CanedgeUnifiedFileItem(
                file = fileModel,
                folderName = rf?.folderName ?: localName.substringBefore("_", "root"),
                status = status,
                localFile = localFile,
                isOnCanedge = onCanedge,
                isCopiedToDevice = onDevice,
                isAssignedToSession = inSession
            )
        }
        _unifiedFiles.value = items
    }

    private fun collectAllLocalSizesByName(): Map<String, Long> {
        val map = LinkedHashMap<String, Long>()
        poolDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.forEach { f ->
            if (f.length() > 0L) map[f.name] = f.length()
        }
        sessionManager.listSessions().forEach { sDir ->
            File(sDir, "can").listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.forEach { f ->
                if (f.length() > 0L) {
                    map.putIfAbsent(f.name, f.length())
                }
            }
        }
        return map
    }

    private fun collectAllLocalFileRefsByName(): Map<String, File> {
        val map = LinkedHashMap<String, File>()
        poolDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.forEach { f ->
            if (f.length() > 0L) map[f.name] = f
        }
        sessionManager.listSessions().forEach { sDir ->
            File(sDir, "can").listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.forEach { f ->
                if (f.length() > 0L) map.putIfAbsent(f.name, f)
            }
        }
        return map
    }

    private fun collectAllSessionCanFileNames(): Set<String> {
        val names = HashSet<String>()
        sessionManager.listSessions().forEach { sDir ->
            File(sDir, "can").listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }?.forEach { f ->
                if (f.length() > 0L) names.add(f.name)
            }
        }
        return names
    }

    private fun backfillPoolIndexFromDisk() {
        val existingIdx = readPoolIndexSafe()
        val byName = existingIdx.byLocalName().toMutableMap()
        var dirty = false

        val diskFiles = poolDir.listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) } ?: emptyArray()
        for (f in diskFiles) {
            if (!byName.containsKey(f.name) && f.length() > 0L) {
                val hdr = Mdf4HeaderInspector.inspect(f)
                val folder = CanedgeSyncPlanner.parseChunkKey(f.name)?.folderId ?: ""
                byName[f.name] = PoolIndexEntry(
                    localName = f.name,
                    remotePath = if (folder.isNotEmpty()) "/$folder/${f.name.substringAfter("_")}" else "/${f.name}",
                    folderId = folder,
                    sizeBytes = f.length(),
                    remoteWrittenEpochSec = 0L,
                    headerStartTimeMs = hdr?.startTimeMs ?: 0L,
                    downloadedAtMs = f.lastModified()
                )
                dirty = true
            }
        }
        if (dirty) {
            synchronized(stateLock) {
                PoolIndex.save(poolIndexFile, PoolIndex(byName.values.sortedBy { it.localName }))
            }
        }
    }

    private fun cleanOrphanDownloads() {
        poolDir.listFiles { _, n -> n.endsWith(".download", ignoreCase = true) }?.forEach { f ->
            if (f.delete()) {
                AppLogger.i(TAG, "Cleaned orphaned partial transfer: ${f.name}")
            }
        }
    }

    /**
     * Scans recent closed session folders on disk (including pre-manifest sessions recorded while
     * offline/disconnected) and registers any session missing `can.syncStatus == COMPLETE` into
     * `can_sync_manifest.json` so background idle passes backfill them from up to 10 CANedge folders.
     */
    private fun importUnreconciledDiskSessions(maxSessionsToInspect: Int = 15) {
        val diskSessions = sessionManager.listSessions()
            .sortedByDescending { it.name }
            .take(maxSessionsToInspect)
        if (diskSessions.isEmpty()) return

        synchronized(stateLock) {
            var manifest = CanSyncManifest.load(manifestFile)
            val existingDirs = manifest.sessions.map { it.sessionDirName }.toHashSet()
            var addedCount = 0

            for (sDir in diskSessions) {
                if (sDir.name == activeSession?.sessionDir?.name) continue
                if (sDir.name in existingDirs) continue

                val metaFile = File(sDir, SessionManager.METADATA_FILE_NAME)
                if (!metaFile.exists() || !metaFile.isFile) continue

                try {
                    val root = JSONObject(metaFile.readText())
                    val startMs = root.optLong("startTimeWallMs", 0L)
                    val stopMs = root.optLong("stopTimeWallMs", 0L)
                    if (startMs <= 0L || stopMs <= 0L) continue

                    val canObj = root.optJSONObject("can")
                    val existingStatus = canObj?.optString("syncStatus", "") ?: ""
                    if (existingStatus.equals(CanSessionSyncStatus.COMPLETE.name, ignoreCase = true)) continue

                    val canFiles = File(sDir, "can")
                        .listFiles { _, n -> n.endsWith(".mf4", ignoreCase = true) }
                        ?.map { it.name }
                        ?.sorted()
                        ?: emptyList()

                    val rec = CanSessionSyncRecord(
                        sessionId = root.optString("sessionId", sDir.name),
                        sessionDirName = sDir.name,
                        startTimeWallMs = startMs,
                        stopTimeWallMs = stopMs,
                        status = CanSessionSyncStatus.PENDING,
                        stagedLocalNames = canFiles,
                        lastAttemptMs = 0L,
                        notes = "Imported legacy/offline session for CAN reconciliation"
                    )
                    manifest = manifest.upsertSession(rec)
                    addedCount++
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Skipping metadata import for ${sDir.name}: ${e.message}")
                }
            }

            if (addedCount > 0) {
                saveManifestLocked(manifest)
                AppLogger.i(TAG, "Imported $addedCount unreconciled session(s) from disk into can_sync_manifest.json for background CAN backfill")
            }
        }
    }

    private fun loadInitialPersistedOffset() {
        val nowMs = System.currentTimeMillis()
        val prefMeasuredAt = appPreferences?.canedgeLastClockMeasuredAtMs ?: 0L
        if (prefMeasuredAt > 0L && (nowMs - prefMeasuredAt) < CLOCK_PREF_MAX_AGE_MS) {
            measuredClockOffsetMs = appPreferences?.canedgeLastClockOffsetMs
        } else {
            val manifest = readManifestSafe()
            val mAt = manifest.lastClockMeasuredAtMs ?: 0L
            if (mAt > 0L && (nowMs - mAt) < CLOCK_PREF_MAX_AGE_MS) {
                measuredClockOffsetMs = manifest.lastClockOffsetMs
            }
        }
    }

    private fun hasVerifiedClockOffset(nowMs: Long): Boolean {
        if (measuredClockOffsetMs != null) return true
        val prefMeasuredAt = appPreferences?.canedgeLastClockMeasuredAtMs ?: 0L
        return prefMeasuredAt > 0L && (nowMs - prefMeasuredAt) < CLOCK_PREF_MAX_AGE_MS
    }

    private fun resolveEffectiveClockOffsetMs(nowMs: Long): Long {
        measuredClockOffsetMs?.let { return it }
        val prefMeasuredAt = appPreferences?.canedgeLastClockMeasuredAtMs ?: 0L
        if (prefMeasuredAt > 0L && (nowMs - prefMeasuredAt) < CLOCK_PREF_MAX_AGE_MS) {
            return appPreferences?.canedgeLastClockOffsetMs ?: 0L
        }
        return 0L
    }

    private fun readManifestSafe(): CanSyncManifest = cachedManifest
    private fun saveManifestLocked(manifest: CanSyncManifest) {
        CanSyncManifest.save(manifestFile, manifest)
        cachedManifest = manifest
    }

    private fun readPoolIndexSafe(): PoolIndex = synchronized(stateLock) { PoolIndex.load(poolIndexFile) }

    private fun recomputeIdleStats() {
        updateStatsSnapshot(discovery.connectionState.value)
    }

    private fun updateStatsSnapshot(
        connState: CanedgeConnectionState,
        targetScopeFiles: Int = _stats.value.targetScopeFiles,
        poolSyncedFiles: Int = _stats.value.poolSyncedFiles,
        currentSessionFiles: Int = _stats.value.currentSessionFiles,
        sessionTargetFiles: Int = _stats.value.sessionTargetFiles,
        isSyncing: Boolean = _stats.value.isSyncing,
        isFinalizing: Boolean = _stats.value.isFinalizingSession,
        isPaused: Boolean = (activeSession != null),
        isWaitingTail: Boolean = _stats.value.isWaitingFinalChunk,
        lastSyncTimeMs: Long = _stats.value.lastSyncTimeMs,
        lastError: String? = _stats.value.lastError,
        queueCompleted: Int = _stats.value.queueCompletedFiles,
        queueTotal: Int = _stats.value.queueTotalFiles,
        activeFileName: String? = _stats.value.activeFileName,
        activeBytes: Long = _stats.value.activeFileBytesTransferred,
        activeTotal: Long = _stats.value.activeFileTotalBytes,
        speedBps: Long = _stats.value.transferSpeedBytesPerSec,
        etaSec: Int = _stats.value.etaSeconds,
        overridePhase: CanedgeHudPhase? = null,
        manifestOverride: CanSyncManifest? = null
    ) {
        val manifest = manifestOverride ?: cachedManifest
        val pendingCount = manifest.pendingCount()
        val poolCount = cachedPoolCount
        val waitingAny =
            isWaitingTail || manifest.sessions.any { it.status == CanSessionSyncStatus.WAITING_FINAL_CHUNK }

        val nowMs = System.currentTimeMillis()
        val clockOffsetVerified = hasVerifiedClockOffset(nowMs)
        val computedPhase = CanedgeSyncPlanner.resolveHudPhase(
            overridePhase = overridePhase,
            isPausedDuringRecording = activeSession != null || isPaused,
            isSyncing = isSyncing,
            connState = connState,
            pendingSessionsCount = pendingCount,
            waitingAnyFinalChunk = waitingAny,
            hasVerifiedClockOffset = clockOffsetVerified
        )

        val prevStats = _stats.value
        val resolvedOffsetMs = measuredClockOffsetMs ?: appPreferences?.canedgeLastClockOffsetMs?.takeIf {
            (appPreferences?.canedgeLastClockMeasuredAtMs ?: 0L) > 0L
        }
        if (prevStats.hudPhase != computedPhase || prevStats.isClockOffsetVerified != clockOffsetVerified) {
            AppLogger.i(
                TAG,
                "[CAN_DIAG] HUD_PHASE ${prevStats.hudPhase} -> $computedPhase offsetVerified=$clockOffsetVerified offsetMs=${resolvedOffsetMs ?: "null"}"
            )
        }

        _stats.value = CanedgeSyncStats(
            targetScopeFiles = targetScopeFiles,
            poolSyncedFiles = poolSyncedFiles,
            poolFilesCount = poolCount,
            currentSessionFiles = currentSessionFiles,
            sessionTargetFiles = sessionTargetFiles,
            isFinalizingSession = isFinalizing,
            lastSyncTimeMs = lastSyncTimeMs,
            isSyncing = isSyncing,
            lastError = lastError,
            activeFileName = activeFileName,
            activeFileBytesTransferred = activeBytes,
            activeFileTotalBytes = activeTotal,
            activeFileProgress = if (activeTotal > 0L) (activeBytes.toFloat() / activeTotal).coerceIn(0f, 1f) else 0f,
            transferSpeedBytesPerSec = speedBps,
            etaSeconds = etaSec,
            hudPhase = computedPhase,
            pendingSessionsCount = pendingCount,
            measuredClockOffsetMs = resolvedOffsetMs,
            isClockOffsetVerified = clockOffsetVerified,
            queueCompletedFiles = queueCompleted,
            queueTotalFiles = queueTotal,
            isPausedDuringRecording = activeSession != null || isPaused,
            isWaitingFinalChunk = waitingAny
        )
    }
}
