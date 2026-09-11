package com.bajajauto.roadsense.canedge.ingestion

import android.os.SystemClock
import com.bajajauto.roadsense.canedge.discovery.CanedgeDiscovery
import com.bajajauto.roadsense.canedge.model.CanedgeConnectionState
import com.bajajauto.roadsense.canedge.model.CanedgeDevice
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import com.bajajauto.roadsense.canedge.model.CanedgeFileStatus
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.canedge.model.CanedgeUnifiedFileItem
import com.bajajauto.roadsense.canedge.network.CanedgeHttpClient
import com.bajajauto.roadsense.canedge.repository.CanedgeRepository
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.recording.SessionInfo
import com.bajajauto.roadsense.recording.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Autonomous file sync and ingestion manager:
 * Manages the local staging pool (canedge_pool/), prioritizes Radar & Camera during recording,
 * defers file staging to drive completion, and stamps physical monotonic recording times in session_timeline.csv.
 */
class CanedgeIngestionManager(
    private val discovery: CanedgeDiscovery,
    private val repository: CanedgeRepository,
    private val httpClient: CanedgeHttpClient,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "CanedgeIngestion"
        private const val CAN_DIR_NAME = "can"
        private const val POOL_DIR_NAME = "canedge_pool"
        private const val LEGACY_CACHE_DIR_NAME = "canedge_cache"
        private const val SESSION_WINDOW_GRACE_MS = 15_000L // 15s grace before session start
        private const val MAX_POOL_SIZE_BYTES = 300 * 1024 * 1024L // 300 MB cap
        private const val TOP_RECENT_FOLDERS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var syncJob: Job? = null

    private val _stats = MutableStateFlow(CanedgeSyncStats())
    val stats: StateFlow<CanedgeSyncStats> = _stats.asStateFlow()

    // Reactive lists for UI drill-down & unified explorer
    private val _remoteFiles = MutableStateFlow<List<CanedgeFile>>(emptyList())
    val remoteFiles: StateFlow<List<CanedgeFile>> = _remoteFiles.asStateFlow()

    private val _localSyncedFiles = MutableStateFlow<List<File>>(emptyList())
    val localSyncedFiles: StateFlow<List<File>> = _localSyncedFiles.asStateFlow()

    private val _localSessionFiles = MutableStateFlow<List<File>>(emptyList())
    val localSessionFiles: StateFlow<List<File>> = _localSessionFiles.asStateFlow()

    private val _unifiedFiles = MutableStateFlow<List<CanedgeUnifiedFileItem>>(emptyList())
    val unifiedFiles: StateFlow<List<CanedgeUnifiedFileItem>> = _unifiedFiles.asStateFlow()

    @Volatile
    private var activeSession: SessionInfo? = null

    @Volatile
    private var lastCompletedSession: SessionInfo? = null

    val poolDir: File
        get() = File(sessionManager.sessionsBaseDir, POOL_DIR_NAME).apply { mkdirs() }

    init {
        refreshLocalFileList()
        pruneStagingPool()
    }

    /**
     * Constructs a collision-free local filename using folder prefix (e.g. 00000045_00000001.MF4).
     */
    fun getLocalFileName(file: CanedgeFile): String {
        val cleanPathParts = file.path.trim('/').split('/')
        return if (cleanPathParts.size >= 2) {
            "${cleanPathParts[cleanPathParts.size - 2]}_${file.name}"
        } else {
            file.name
        }
    }

    /**
     * Gathers all .MF4 files safely preserved across ALL recorded session directories on the device.
     */
    fun getAllRecordedSessionCanFiles(): List<File> {
        return try {
            sessionManager.sessionsBaseDir.listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
                ?.flatMap { sDir ->
                    val canDir = File(sDir, CAN_DIR_NAME)
                    if (canDir.exists()) {
                        canDir.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) }?.toList() ?: emptyList()
                    } else emptyList()
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Refreshes the local file lists for cached pool and active session files,
     * updating both the telemetry stats and unified explorer items.
     * Uses a robust name-by-name lookup table against planned remote files,
     * checking both the local staging pool and all recorded session directories.
     */
    fun refreshLocalFileList() {
        val pool = poolDir
        val legacy = File(sessionManager.sessionsBaseDir, LEGACY_CACHE_DIR_NAME)

        val poolFiles = (pool.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) }?.toList() ?: emptyList()) +
                (if (legacy.exists()) legacy.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) }?.toList() ?: emptyList() else emptyList())

        val distinctPoolFiles = poolFiles.distinctBy { it.name }.sortedByDescending { it.lastModified() }

        val targetSession = activeSession ?: lastCompletedSession
        val sessionCanDir = targetSession?.let { File(it.sessionDir, CAN_DIR_NAME) }
        val sessionFiles = if (sessionCanDir != null && sessionCanDir.exists()) {
            sessionCanDir.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) }?.toList() ?: emptyList()
        } else emptyList()

        val sortedSessionFiles = sessionFiles.sortedByDescending { it.lastModified() }

        _localSyncedFiles.value = distinctPoolFiles
        _localSessionFiles.value = sortedSessionFiles

        // All files safely stored across ANY session on device
        val allSessionCanFiles = getAllRecordedSessionCanFiles()
        val allSessionFilesByName = allSessionCanFiles.associateBy { it.name }
        val poolFilesByName = distinctPoolFiles.associateBy { it.name }
        val plannedFiles = _remoteFiles.value

        // Exact name-by-name lookup table against planned target scope
        // Verified count: A file is verified on phone if it exists in canedge_pool/ OR any recorded session!
        val verifiedSyncedCount = if (plannedFiles.isNotEmpty()) {
            plannedFiles.count { rFile ->
                val localName = getLocalFileName(rFile)
                val pFile = poolFilesByName[localName] ?: allSessionFilesByName[localName]
                pFile != null && (pFile.length() == rFile.sizeBytes || rFile.sizeBytes == 0L)
            }
        } else {
            distinctPoolFiles.size
        }

        // Update stats
        _stats.update { current ->
            current.copy(
                targetScopeFiles = plannedFiles.size,
                poolSyncedFiles = verifiedSyncedCount,
                currentSessionFiles = sortedSessionFiles.size
            )
        }

        rebuildUnifiedFiles(allSessionFilesByName)
    }

    private fun rebuildUnifiedFiles(allSessionFilesByName: Map<String, File> = getAllRecordedSessionCanFiles().associateBy { it.name }) {
        val remote = _remoteFiles.value
        val poolFilesByName = _localSyncedFiles.value.associateBy { it.name }

        val unified = remote.map { rFile ->
            val localName = getLocalFileName(rFile)
            val sessionFile = allSessionFilesByName[localName]
            val poolFile = poolFilesByName[localName]

            val folderName = rFile.path.trim('/').split('/').let { parts ->
                if (parts.size >= 2) parts[parts.size - 2] else "ROOT"
            }

            val status = when {
                sessionFile != null && sessionFile.length() > 0 -> CanedgeFileStatus.IN_SESSION
                poolFile != null && (poolFile.length() == rFile.sizeBytes || rFile.sizeBytes == 0L) -> CanedgeFileStatus.IN_POOL
                else -> CanedgeFileStatus.REMOTE_ONLY
            }

            val localRef = sessionFile ?: poolFile

            CanedgeUnifiedFileItem(
                file = rFile,
                folderName = folderName,
                status = status,
                localFile = localRef
            )
        }

        _unifiedFiles.value = unified
    }

    /**
     * Attaches an active RoadSense recording session.
     * High-volume CAN copying is immediately deferred to guarantee 100% CPU/bandwidth for Radar and Camera.
     */
    fun onSessionStarted(sessionInfo: SessionInfo) {
        activeSession = sessionInfo
        lastCompletedSession = null
        // Preempt any running idle sync job immediately
        syncJob?.cancel()
        syncJob = null

        _stats.update {
            it.copy(
                currentSessionFiles = 0,
                sessionTargetFiles = 0,
                isFinalizingSession = false,
                isSyncing = false
            )
        }
        refreshLocalFileList()
        AppLogger.i(TAG, "Attached to active session: ${sessionInfo.sessionId}. In-drive CAN sync deferred for Radar & Camera priority.")
    }

    /**
     * Detaches active recording session and initiates post-recording staging finalizer.
     * Pulls remaining closed chunks from CANedge and stages matching drive chunks into session/can/.
     */
    fun onSessionStopped() {
        val completedSession = activeSession ?: return
        activeSession = null
        lastCompletedSession = completedSession

        AppLogger.i(TAG, "Session stopped: ${completedSession.sessionId}. Launching post-recording staging finalizer...")

        scope.launch {
            try {
                _stats.update { it.copy(isFinalizingSession = true) }
                finalizeSessionIngestion(completedSession)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in post-recording staging finalizer: ${e.message}", e)
                _stats.update { it.copy(lastError = "Finalizing error: ${e.message}") }
            } finally {
                _stats.update { it.copy(isFinalizingSession = false, lastSyncTimeMs = System.currentTimeMillis()) }
                refreshLocalFileList()
                pruneStagingPool()
            }
        }
    }

    /**
     * Finalizes session ingestion: pulls drive chunks into pool, copies into session/can/,
     * and logs chronological physical monotonic events into session_timeline.csv.
     */
    private suspend fun finalizeSessionIngestion(session: SessionInfo) = withContext(Dispatchers.IO) {
        val device = discovery.currentDevice
        if (device == null) {
            AppLogger.w(TAG, "Cannot finalize CAN logs: Device disconnected")
            return@withContext
        }

        AppLogger.i(TAG, "Scanning CANedge recent folders for session ${session.sessionId}...")
        delay(1200) // Brief delay to give logger time to finalize closing split chunk

        val recentFiles = repository.listRecentSessionMf4Files(device, TOP_RECENT_FOLDERS)
        _remoteFiles.value = recentFiles
        _stats.update { it.copy(targetScopeFiles = recentFiles.size) }

        val windowStartMs = session.startTimeWallMs - SESSION_WINDOW_GRACE_MS
        val windowStopMs = (session.stopTimeWallMs ?: System.currentTimeMillis()) + SESSION_WINDOW_GRACE_MS

        val sessionCandidates = recentFiles.filter { file ->
            val isWithinWindow = file.lastWrittenMs in windowStartMs..windowStopMs
            val isFromLatest = recentFiles.isNotEmpty() &&
                    file.path.contains("/${recentFiles.first().path.trim('/').split('/').dropLast(1).last()}/") &&
                    file.lastWrittenMs >= (session.startTimeWallMs - 30_000L)
            isWithinWindow || isFromLatest
        }.sortedBy { it.path }

        _stats.update { it.copy(sessionTargetFiles = sessionCandidates.size, currentSessionFiles = 0) }
        AppLogger.i(TAG, "Identified ${sessionCandidates.size} CAN chunks belonging to session ${session.sessionId}")

        val sessionCanDir = File(session.sessionDir, CAN_DIR_NAME).apply { mkdirs() }
        var stagedCount = 0

        for ((index, file) in sessionCandidates.withIndex()) {
            val localName = getLocalFileName(file)
            val poolFile = File(poolDir, localName)

            // Step 1: Ensure chunk is downloaded into local staging pool
            if (!poolFile.exists() || poolFile.length() != file.sizeBytes) {
                if (repository.isFileStable(file, recentFiles)) {
                    val tempFile = File(poolDir, "$localName.download")
                    val remoteUrl = "${device.apiBaseUrl}${file.path.trimStart('/')}"
                    AppLogger.i(TAG, "Pulling remaining chunk for drive: ${file.path} (${file.sizeBytes} bytes)...")
                    val downloaded = httpClient.downloadToFile(remoteUrl, tempFile)
                    if (downloaded && tempFile.exists() && (tempFile.length() == file.sizeBytes || file.sizeBytes == 0L)) {
                        if (poolFile.exists()) poolFile.delete()
                        tempFile.renameTo(poolFile)
                    } else {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
            }

            // Step 2: Copy from pool into session folder
            if (poolFile.exists() && poolFile.length() > 0) {
                val sessionDestFile = File(sessionCanDir, localName)
                if (!sessionDestFile.exists() || sessionDestFile.length() != poolFile.length()) {
                    poolFile.copyTo(sessionDestFile, overwrite = true)
                }

                stagedCount++
                _stats.update { it.copy(currentSessionFiles = stagedCount) }

                // Step 3: Record accurate physical recording monotonic timestamp
                // 10s split chunk was logged between (file.lastWrittenMs - 10_000) and file.lastWrittenMs
                val chunkStartOffsetMs = (file.lastWrittenMs - 10_000L) - session.startTimeWallMs
                val chunkMonoNs = session.startTimeMonotonicNs + (chunkStartOffsetMs.coerceAtLeast(0L) * 1_000_000L)

                sessionManager.recordTimelineEvent(
                    elapsedRealtimeNs = chunkMonoNs,
                    sensor = "CAN",
                    event = "MF4_FILE",
                    sequenceId = (index + 1).toLong(),
                    relativePath = "$CAN_DIR_NAME/${sessionDestFile.name}",
                    summary = "size=${sessionDestFile.length()};remotePath=${file.path};lastWrittenMs=${file.lastWrittenMs}"
                )
                AppLogger.i(TAG, "Staged chunk #${index + 1} (${sessionDestFile.name}) into session at offset +${chunkStartOffsetMs / 1000f}s")
            }
        }

        // Step 4: Finalize session metadata
        sessionManager.writeMetadata(session)
        AppLogger.i(TAG, "Session CAN staging completed: $stagedCount / ${sessionCandidates.size} files staged.")
    }

    /**
     * Triggers an immediate one-shot sync cycle for idle pool caching.
     */
    fun triggerSync() {
        if (activeSession != null) {
            AppLogger.d(TAG, "Recording in progress. Deferring heavy sync until stop.")
            return
        }

        if (_stats.value.isSyncing) {
            AppLogger.d(TAG, "Sync already in progress. Skipping duplicate trigger.")
            return
        }

        syncJob?.cancel()
        syncJob = scope.launch {
            val device = discovery.currentDevice
            if (device == null) {
                AppLogger.w(TAG, "Cannot sync: No CANedge device currently connected")
                _stats.update { it.copy(lastError = "Device not connected") }
                return@launch
            }

            try {
                _stats.update { it.copy(isSyncing = true, lastError = null) }
                performPoolSync(device)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sync error: ${e.message}", e)
                _stats.update { it.copy(lastError = e.message) }
            } finally {
                _stats.update { it.copy(isSyncing = false, lastSyncTimeMs = System.currentTimeMillis()) }
                refreshLocalFileList()
                pruneStagingPool()
            }
        }
    }

    /**
     * Downloads stable chunks from top 5 recent folders into canedge_pool/ while idle.
     */
    private suspend fun performPoolSync(device: CanedgeDevice) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Scanning top $TOP_RECENT_FOLDERS recent session folders on ${device.deviceId}...")
        val recentFiles = repository.listRecentSessionMf4Files(device, TOP_RECENT_FOLDERS)

        _remoteFiles.value = recentFiles
        _stats.update { it.copy(targetScopeFiles = recentFiles.size) }

        var newFilesCount = 0

        for (file in recentFiles) {
            if (activeSession != null) {
                AppLogger.i(TAG, "Session started during sync. Preempting idle sync immediately.")
                break
            }

            if (!repository.isFileStable(file, recentFiles)) {
                AppLogger.d(TAG, "Skipping active/unstable file: ${file.path}")
                continue
            }

            val localFileName = getLocalFileName(file)
            val poolFile = File(poolDir, localFileName)

            if (poolFile.exists() && poolFile.length() == file.sizeBytes) {
                continue
            }

            val tempFile = File(poolDir, "$localFileName.download")
            val remoteUrl = "${device.apiBaseUrl}${file.path.trimStart('/')}"

            AppLogger.i(TAG, "Downloading to pool: ${file.path} (${file.sizeBytes} bytes)...")
            val success = httpClient.downloadToFile(remoteUrl, tempFile)

            if (success && tempFile.exists() && (tempFile.length() == file.sizeBytes || file.sizeBytes == 0L)) {
                if (poolFile.exists()) poolFile.delete()
                if (tempFile.renameTo(poolFile)) {
                    newFilesCount++
                    refreshLocalFileList()
                    AppLogger.i(TAG, "SUCCESS: Cached in pool -> ${poolFile.name}")
                } else {
                    tempFile.delete()
                }
            } else {
                if (tempFile.exists()) tempFile.delete()
            }
        }

        AppLogger.i(TAG, "Idle pool sync complete. Ingested $newFilesCount new files into staging pool.")
    }

    /**
     * Smart Auto-Pruning Engine:
     * - Scans all recorded sessions to ensure no needed files are lost.
     * - Auto-rescues/backfills any un-staged CAN chunks into matching recorded sessions.
     * - Clears redundant duplicates from canedge_pool/ that are already safely stored in recorded sessions.
     * - Deletes stale accumulated pool files that belong to no recorded session and fall outside the active top-5 scope.
     * - Enforces FIFO storage cap (MAX_POOL_SIZE_BYTES = 300 MB).
     * - Cleans incomplete .download temp files and wipes legacy canedge_cache.
     */
    fun pruneStagingPool() {
        scope.launch(Dispatchers.IO) {
            try {
                // Safety guard: Do not run pruning while a session is actively finalizing CAN logs
                if (_stats.value.isFinalizingSession) {
                    AppLogger.d(TAG, "Auto-pruning deferred: Session finalizer is actively running.")
                    return@launch
                }

                val pool = poolDir
                val legacy = File(sessionManager.sessionsBaseDir, LEGACY_CACHE_DIR_NAME)

                // 1. Wipe legacy cache completely if it exists
                if (legacy.exists()) {
                    val legacyFiles = legacy.listFiles()
                    legacyFiles?.forEach { it.delete() }
                    if (legacy.delete()) {
                        AppLogger.i(TAG, "Legacy cache directory wiped: ${legacy.absolutePath}")
                    }
                }

                // 2. Clean up any partial download temp files
                pool.listFiles { f -> f.name.endsWith(".download") }?.forEach { temp ->
                    AppLogger.d(TAG, "Deleting orphaned download temp file: ${temp.name}")
                    temp.delete()
                }

                val poolFiles = pool.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) } ?: emptyArray()
                if (poolFiles.isEmpty()) {
                    refreshLocalFileList()
                    return@launch
                }

                // 3. Index all recorded sessions on the device
                val recordedSessions = sessionManager.sessionsBaseDir.listFiles { f ->
                    f.isDirectory && f.name.startsWith("session_")
                } ?: emptyArray()

                class RecordedSessionMeta(
                    val sessionDir: File,
                    val canDir: File,
                    val startMs: Long,
                    val stopMs: Long,
                    val stagedFileNames: MutableSet<String>
                )

                val sessionMetas = mutableListOf<RecordedSessionMeta>()
                val allStagedFileNames = mutableSetOf<String>()

                for (sDir in recordedSessions) {
                    val canDir = File(sDir, CAN_DIR_NAME).apply { mkdirs() }
                    val staged = canDir.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) } ?: emptyArray()
                    val stagedNames = staged.map { it.name }.toMutableSet()
                    allStagedFileNames.addAll(stagedNames)

                    // Extract start/stop timestamps from session metadata or directory name
                    val metaFile = File(sDir, SessionManager.METADATA_FILE_NAME)
                    var startMs = 0L
                    var stopMs = 0L

                    if (metaFile.exists()) {
                        try {
                            val json = JSONObject(metaFile.readText())
                            startMs = json.optLong("startTimeWallMs", 0L)
                            stopMs = json.optLong("stopTimeWallMs", 0L)
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed reading metadata for ${sDir.name}: ${e.message}")
                        }
                    }

                    if (startMs == 0L) {
                        // Fallback to directory name timestamp
                        val stamp = sDir.name.removePrefix("session_")
                        val parsedDate = try {
                            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(stamp)
                        } catch (e: Exception) { null }
                        startMs = parsedDate?.time ?: sDir.lastModified()
                    }
                    if (stopMs == 0L) {
                        stopMs = sDir.lastModified().coerceAtLeast(startMs + 60_000L)
                    }

                    sessionMetas.add(RecordedSessionMeta(sDir, canDir, startMs, stopMs, stagedNames))
                }

                // 4. Remote scope files mapping
                val planned = _remoteFiles.value
                val plannedByLocalName = planned.associateBy { getLocalFileName(it) }
                val validPlannedFileNames = plannedByLocalName.keys
                val activeSessionStartMs = activeSession?.startTimeWallMs

                var rescuedCount = 0
                var prunedCount = 0
                var bytesReclaimed = 0L

                // 5. Inspect pool files: Backfill un-staged files or mark for pruning
                for (pFile in poolFiles) {
                    val pName = pFile.name

                    // NEVER prune chunks that might belong to the active recording session!
                    if (activeSessionStartMs != null) {
                        val fileTime = plannedByLocalName[pName]?.lastWrittenMs ?: pFile.lastModified()
                        if (fileTime >= activeSessionStartMs - SESSION_WINDOW_GRACE_MS) {
                            continue
                        }
                    }

                    val fileTime = plannedByLocalName[pName]?.lastWrittenMs ?: pFile.lastModified()

                    // A: Check if this file is needed by any recorded session that hasn't staged it yet
                    var neededBySession: RecordedSessionMeta? = null
                    for (sInfo in sessionMetas) {
                        if (!sInfo.stagedFileNames.contains(pName)) {
                            val inWindow = fileTime in (sInfo.startMs - SESSION_WINDOW_GRACE_MS)..(sInfo.stopMs + SESSION_WINDOW_GRACE_MS)
                            if (inWindow) {
                                neededBySession = sInfo
                                break
                            }
                        }
                    }

                    // If needed by an existing recorded session, backfill it now!
                    if (neededBySession != null && pFile.length() > 0) {
                        val targetFile = File(neededBySession.canDir, pName)
                        try {
                            pFile.copyTo(targetFile, overwrite = true)
                            neededBySession.stagedFileNames.add(pName)
                            allStagedFileNames.add(pName)
                            rescuedCount++
                            AppLogger.i(TAG, "Smart auto-pruner: Rescued un-staged CAN chunk $pName into ${neededBySession.sessionDir.name}")
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed rescuing chunk $pName: ${e.message}", e)
                        }
                    }

                    // B: Decide if pFile should be cleared from pool
                    val isAlreadyStagedInSession = allStagedFileNames.contains(pName)
                    val isInActiveScope = validPlannedFileNames.contains(pName)

                    val shouldPrune = when {
                        // Empty/corrupted file
                        pFile.length() == 0L -> true

                        // Already permanently stored in a recorded session's can/ folder -> remove pool duplicate!
                        isAlreadyStagedInSession -> true

                        // Accumulated older file: NOT needed in any session AND not in current device scope
                        !isInActiveScope && validPlannedFileNames.isNotEmpty() -> true

                        // If offline/no remote scope, but file is older than 48 hours and not in any session
                        validPlannedFileNames.isEmpty() && (System.currentTimeMillis() - fileTime) > 48 * 3600_000L -> true

                        else -> false
                    }

                    if (shouldPrune) {
                        val len = pFile.length()
                        if (pFile.delete()) {
                            prunedCount++
                            bytesReclaimed += len
                            AppLogger.i(TAG, "Smart auto-pruner: Cleared pool file: $pName (${len / 1024} KB). [Reason: ${if (isAlreadyStagedInSession) "already staged in session" else "stale out-of-scope"}]")
                        }
                    }
                }

                // 6. Enforce storage cap (MAX_POOL_SIZE_BYTES = 300 MB)
                var currentPoolSize = pool.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) }
                    ?.sumOf { it.length() } ?: 0L

                if (currentPoolSize > MAX_POOL_SIZE_BYTES) {
                    val remainingFiles = pool.listFiles { f -> f.isFile && f.name.endsWith(".mf4", ignoreCase = true) }
                        ?.sortedBy { it.lastModified() } ?: emptyList()

                    for (f in remainingFiles) {
                        if (currentPoolSize <= MAX_POOL_SIZE_BYTES) break
                        if (activeSessionStartMs != null && f.lastModified() >= activeSessionStartMs - SESSION_WINDOW_GRACE_MS) {
                            continue
                        }
                        val len = f.length()
                        if (f.delete()) {
                            prunedCount++
                            bytesReclaimed += len
                            currentPoolSize -= len
                            AppLogger.i(TAG, "Smart auto-pruner: Trimmed pool file to stay under 300 MB cap: ${f.name}")
                        }
                    }
                }

                AppLogger.i(TAG, "Smart auto-pruning complete: $prunedCount files cleared (${bytesReclaimed / 1024 / 1024} MB reclaimed), $rescuedCount files rescued into sessions. Remaining pool size: ${currentPoolSize / 1024 / 1024} MB.")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error during smart auto-pruning: ${e.message}")
            } finally {
                refreshLocalFileList()
            }
        }
    }
}
