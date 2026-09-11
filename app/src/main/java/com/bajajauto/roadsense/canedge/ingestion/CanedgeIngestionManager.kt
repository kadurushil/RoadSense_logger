package com.bajajauto.roadsense.canedge.ingestion

import android.os.SystemClock
import com.bajajauto.roadsense.canedge.discovery.CanedgeDiscovery
import com.bajajauto.roadsense.canedge.model.CanedgeConnectionState
import com.bajajauto.roadsense.canedge.model.CanedgeDevice
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
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
import java.io.File

/**
 * Autonomous file sync and ingestion manager:
 * Discovers completed MF4 files on the CANedge2 SD card, downloads them safely into
 * the active session's "can/" folder, and logs each file into "session_timeline.csv".
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
        private const val POLL_INTERVAL_MS = 8_000L // Poll every 8s while recording
        private const val SESSION_WINDOW_GRACE_MS = 15_000L // 15s grace period before session start
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var syncJob: Job? = null
    private var pollingJob: Job? = null

    private val _stats = MutableStateFlow(CanedgeSyncStats())
    val stats: StateFlow<CanedgeSyncStats> = _stats.asStateFlow()

    // Tracks set of already downloaded remote paths to prevent duplicate downloads
    private val downloadedPaths = mutableSetOf<String>()

    @Volatile
    private var activeSession: SessionInfo? = null

    /**
     * Attaches an active RoadSense recording session and launches background polling.
     * Newly completed MF4 files during this session will be downloaded into session/can/.
     */
    fun onSessionStarted(sessionInfo: SessionInfo) {
        activeSession = sessionInfo
        _stats.update { it.copy(currentSessionFiles = 0) }
        AppLogger.i(TAG, "Attached to active session: ${sessionInfo.sessionId} (startWallMs=${sessionInfo.startTimeWallMs})")

        startPolling()
    }

    /**
     * Detaches active recording session, stops background polling, and triggers a final sync.
     */
    fun onSessionStopped() {
        val completedSession = activeSession
        stopPolling()
        AppLogger.i(TAG, "Session stopped: ${completedSession?.sessionId}. Triggering final sync cycle...")
        
        // Run a final sync to grab any chunk closed as recording stopped
        scope.launch {
            delay(1500) // Brief delay to give CANedge time to flush current split if needed
            triggerSync()
            activeSession = null
        }
    }

    private fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            AppLogger.i(TAG, "Starting background CANedge sync polling loop (interval=${POLL_INTERVAL_MS}ms)")
            while (activeSession != null) {
                try {
                    triggerSync()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Exception during polling tick: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Triggers an immediate one-shot sync cycle.
     */
    fun triggerSync() {
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
                performSync(device)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sync error: ${e.message}", e)
                _stats.update { it.copy(lastError = e.message) }
            } finally {
                _stats.update { it.copy(isSyncing = false, lastSyncTimeMs = System.currentTimeMillis()) }
            }
        }
    }

    private suspend fun performSync(device: CanedgeDevice) = withContext(Dispatchers.IO) {
        val targetSession = activeSession

        // When a session is active, scan only the latest session directories for maximum responsiveness
        // When idle, list all files to count them for the UI dashboard, but do not download large historical archives
        val remoteFilesToProcess = if (targetSession != null) {
            AppLogger.i(TAG, "Scanning latest MF4 session files on ${device.deviceId}...")
            repository.listLatestSessionMf4Files(device)
        } else {
            AppLogger.i(TAG, "Scanning remote MF4 files on ${device.deviceId}...")
            repository.listAllMf4Files(device)
        }

        if (targetSession == null) {
            _stats.update { it.copy(totalFilesOnDevice = remoteFilesToProcess.size) }
        }

        val targetDir = if (targetSession != null) {
            File(targetSession.sessionDir, CAN_DIR_NAME).apply { mkdirs() }
        } else {
            File(sessionManager.sessionsBaseDir, "canedge_cache").apply { mkdirs() }
        }

        var newFilesCount = 0

        for (file in remoteFilesToProcess) {
            if (downloadedPaths.contains(file.path)) {
                continue
            }

            // Check if file is stable and safe to download
            if (!repository.isFileStable(file, remoteFilesToProcess)) {
                AppLogger.d(TAG, "Skipping active/unstable file: ${file.path} (${file.sizeBytes} bytes)")
                continue
            }

            // Filter for session window:
            // 1. If timestamp matches session start window (lastWrittenMs >= sessionStart - grace)
            // 2. OR if file is in the highest-numbered session directory discovered on the device
            val isFromLatestFolder = remoteFilesToProcess.isNotEmpty() &&
                file.path.contains("/${remoteFilesToProcess.first().path.trim('/').split('/').dropLast(1).last()}/")
            val isWithinTimeWindow = targetSession != null &&
                file.lastWrittenMs >= (targetSession.startTimeWallMs - SESSION_WINDOW_GRACE_MS)

            val belongsToActiveSession = targetSession != null && (isWithinTimeWindow || isFromLatestFolder)

            if (targetSession != null && !belongsToActiveSession) {
                // Do not download old historical files into current session's folder
                continue
            }

            // When IDLE (no active recording session), only allow syncing if file is reasonably sized (< 5 MB)
            // to avoid locking the MCU with 17 MB legacy archives
            if (targetSession == null && file.sizeBytes > 5 * 1024 * 1024L) {
                AppLogger.d(TAG, "Skipping large historical archive file during idle sync: ${file.name} (${file.sizeBytes} bytes)")
                continue
            }

            // Local filename uses parent folder name prefix if available (e.g. 00000001_00000001.MF4) to prevent collisions
            val cleanPathParts = file.path.trim('/').split('/')
            val localFileName = if (cleanPathParts.size >= 2) {
                "${cleanPathParts[cleanPathParts.size - 2]}_${file.name}"
            } else {
                file.name
            }

            val finalFile = File(targetDir, localFileName)
            if (finalFile.exists() && finalFile.length() == file.sizeBytes) {
                AppLogger.d(TAG, "File $localFileName already exists locally and matches size. Marking downloaded.")
                downloadedPaths.add(file.path)
                if (belongsToActiveSession) {
                    _stats.update { it.copy(currentSessionFiles = it.currentSessionFiles + 1) }
                }
                continue
            }

            val tempFile = File(targetDir, "$localFileName.download")
            val remoteUrl = "${device.apiBaseUrl}${file.path.trimStart('/')}"

            AppLogger.i(TAG, "Downloading ${file.path} (${file.sizeBytes} bytes) to $localFileName from $remoteUrl...")
            val success = httpClient.downloadToFile(remoteUrl, tempFile)

            if (success && tempFile.exists() && (tempFile.length() == file.sizeBytes || file.sizeBytes == 0L)) {
                if (finalFile.exists()) finalFile.delete()
                if (tempFile.renameTo(finalFile)) {
                    downloadedPaths.add(file.path)
                    newFilesCount++

                    _stats.update { current ->
                        current.copy(
                            totalSyncedFiles = downloadedPaths.size,
                            currentSessionFiles = if (belongsToActiveSession) current.currentSessionFiles + 1 else current.currentSessionFiles
                        )
                    }

                    AppLogger.i(TAG, "SUCCESS: Synced $localFileName -> ${finalFile.absolutePath}")

                    // Log event to master session timeline if recording is active and file belongs to session
                    if (targetSession != null && belongsToActiveSession) {
                        sessionManager.recordTimelineEvent(
                            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                            sensor = "CAN",
                            event = "MF4_FILE",
                            sequenceId = downloadedPaths.size.toLong(),
                            relativePath = "$CAN_DIR_NAME/${finalFile.name}",
                            summary = "size=${finalFile.length()};remotePath=${file.path};lastWrittenMs=${file.lastWrittenMs}"
                        )
                        // Refresh session_metadata.json so activeStreams includes new can/*.mf4
                        sessionManager.writeMetadata(targetSession)
                    }
                } else {
                    AppLogger.e(TAG, "Failed to rename temp file to ${finalFile.name}")
                    tempFile.delete()
                }
            } else {
                AppLogger.w(TAG, "Failed downloading ${file.name}; cleaning up temp file.")
                if (tempFile.exists()) tempFile.delete()
            }
        }

        AppLogger.i(TAG, "Sync cycle complete. Ingested $newFilesCount new files.")
    }
}
