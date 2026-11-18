package com.bajajauto.roadsense.canedge.repository

import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.canedge.model.CanedgeDevice
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import com.bajajauto.roadsense.canedge.network.CanedgeHttpClient
import com.bajajauto.roadsense.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Repository responsible for navigating CANedge2 SD card directories
 * and evaluating file completion/stability states.
 */
class CanedgeRepository(
    private val httpClient: CanedgeHttpClient
) {

    companion object {
        private const val TAG = "CanedgeRepository"
        private const val FILE_STABILITY_AGE_MS = 12_000L // 12 seconds (> 10s split)
        private const val NEWEST_CHUNK_IDLE_AGE_MS = 75_000L // Guards active 60s chunk + residual RTC skew
        private const val SIZE_STABILITY_MIN_INTERVAL_MS = 3_000L
        private val NUMERIC_DIR_REGEX = Regex("\\d+")
    }

    private data class ObservedSizeSample(val sizeBytes: Long, val observedAtMs: Long)

    // Cache of (remotePath -> observed size & timestamp) to detect active file growth across polls
    private val lastObservedSizes = mutableMapOf<String, ObservedSizeSample>()

    @Volatile
    private var cachedDeviceLogRootPath: String? = null

    /**
     * Lists files and folders directly inside `path` on the CANedge2 SD card.
     * Always ensures the API endpoint contains a proper trailing slash for directories.
     */
    suspend fun listDirectory(device: CanedgeDevice, path: String = "/"): List<CanedgeFile> =
        withContext(Dispatchers.IO) {
            val cleanPath = path.trim().trim('/')
            val endpoint = if (cleanPath.isEmpty()) {
                device.apiBaseUrl
            } else {
                "${device.apiBaseUrl}$cleanPath/"
            }
            if (DebugToggles.isCanedgeVerboseActive) {
                AppLogger.d(TAG, "[CAN_DIAG] HTTP_LIST endpoint=$endpoint")
            }

            val jsonStr = httpClient.getJson(endpoint) ?: return@withContext emptyList()
            val filesList = mutableListOf<CanedgeFile>()

            try {
                val root = JSONObject(jsonStr)
                val rawBasePath = root.optString("path", if (cleanPath.isEmpty()) "/" else "/$cleanPath/")
                val formattedBasePath = if (rawBasePath.endsWith("/")) rawBasePath else "$rawBasePath/"
                val filesArray = root.optJSONArray("files")

                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        val name = item.getString("name")
                        val isDir = item.optInt("isDirectory", 0) == 1
                        val size = item.optLong("size", 0L)
                        val lastWritten = item.optLong("lastWritten", 0L)

                        val itemPath = "$formattedBasePath$name"

                        filesList.add(
                            CanedgeFile(
                                name = name,
                                path = itemPath,
                                isDirectory = isDir,
                                sizeBytes = size,
                                lastWrittenEpochSec = lastWritten
                            )
                        )
                    }
                }
                if (DebugToggles.isCanedgeVerboseActive) {
                    AppLogger.d(TAG, "[CAN_DIAG] HTTP_LIST_OK endpoint=$endpoint items=${filesList.size}")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error parsing directory JSON from $endpoint: ${e.message}", e)
            }
            filesList
        }

    /**
     * Traverses down subdirectories in descending (newest-first) order
     * and returns discovered .MF4 log files on the CANedge SD card.
     */
    suspend fun listAllMf4Files(device: CanedgeDevice): List<CanedgeFile> =
        withContext(Dispatchers.IO) {
            val allMf4Files = mutableListOf<CanedgeFile>()
            val dirQueue = ArrayDeque<String>()
            dirQueue.add("/") // Start at root

            var dirsScanned = 0

            while (dirQueue.isNotEmpty()) {
                val currentDir = dirQueue.removeFirst()
                dirsScanned++
                val entries = listDirectory(device, currentDir)

                // Subdirectories sorted in reverse (e.g. 00000041 before 00000018) so newest sessions process first
                val subDirs = entries.filter { it.isDirectory }
                    .sortedByDescending { it.name }

                for (dir in subDirs) {
                    val nameUpper = dir.name.uppercase()
                    // Ignore system folders like LOST.DIR, Android, and hidden . folders
                    if (nameUpper != "LOST.DIR" && nameUpper != "ANDROID" && !dir.name.startsWith(".")) {
                        dirQueue.add(dir.path)
                    }
                }

                // Files sorted ascending by name (e.g. 00000001.MF4, 00000002.MF4)
                val mf4Files = entries.filter { it.isMf4 }.sortedBy { it.name }
                for (file in mf4Files) {
                    allMf4Files.add(file)
                    AppLogger.d(TAG, "Discovered MF4 file: ${file.path} (${file.sizeBytes} bytes, lastWritten=${file.lastWrittenEpochSec})")
                }
            }

            AppLogger.i(TAG, "Recursive discovery completed: ${allMf4Files.size} MF4 files found across $dirsScanned directories")
            allMf4Files
        }

    /**
     * Discovers MF4 log files across the top recent session folders on the device
     * under /LOG/<DEVICE_ID>/ (default top 10 folders descending).
     * Captures multi-session / delayed reconnections without scanning the entire SD card.
     */
    suspend fun listRecentSessionMf4Files(device: CanedgeDevice, maxFolders: Int = 10): List<CanedgeFile> =
        withContext(Dispatchers.IO) {
            var targetDevicePath = cachedDeviceLogRootPath
            if (targetDevicePath == null) {
                val logEntries = listDirectory(device, "/LOG/")
                val deviceDir = logEntries.firstOrNull {
                    it.isDirectory && (it.name.equals(device.deviceId, ignoreCase = true) || it.name.length == 8)
                }
                targetDevicePath = deviceDir?.path ?: "/LOG/${device.deviceId}/"
                cachedDeviceLogRootPath = targetDevicePath
            }

            var sessionDirs = listDirectory(device, targetDevicePath)
                .filter { it.isDirectory && !it.name.startsWith(".") && NUMERIC_DIR_REGEX.matches(it.name) }
                .sortedByDescending { it.name }

            if (sessionDirs.isEmpty()) {
                // Invalidate cached device path once and retry discovery under /LOG/
                cachedDeviceLogRootPath = null
                val logEntries = listDirectory(device, "/LOG/")
                val deviceDir = logEntries.firstOrNull {
                    it.isDirectory && (it.name.equals(device.deviceId, ignoreCase = true) || it.name.length == 8)
                }
                val fallbackPath = deviceDir?.path ?: "/LOG/${device.deviceId}/"
                if (fallbackPath != targetDevicePath) {
                    sessionDirs = listDirectory(device, fallbackPath)
                        .filter { it.isDirectory && !it.name.startsWith(".") && NUMERIC_DIR_REGEX.matches(it.name) }
                        .sortedByDescending { it.name }
                    if (sessionDirs.isNotEmpty()) cachedDeviceLogRootPath = fallbackPath
                }
            }

            if (sessionDirs.isEmpty()) {
                AppLogger.w(TAG, "No session directories found under $targetDevicePath")
                return@withContext emptyList()
            }

            val dirsToScan = sessionDirs.take(maxFolders)
            val files = mutableListOf<CanedgeFile>()
            for (dir in dirsToScan) {
                try {
                    val dirFiles = listDirectory(device, dir.path).filter { it.isMf4 }.sortedBy { it.name }
                    files.addAll(dirFiles)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Transient error scanning directory ${dir.path}: ${e.message}")
                }
            }
            if (DebugToggles.isCanedgeVerboseActive) {
                AppLogger.d(TAG, "[CAN_DIAG] SCAN_SCOPE folders=${dirsToScan.size} remoteFiles=${files.size} maxFolders=$maxFolders")
            }
            files
        }

    /**
     * Optimized session scan: Discovers only the latest 2 session folders on the device.
     */
    suspend fun listLatestSessionMf4Files(device: CanedgeDevice): List<CanedgeFile> =
        listRecentSessionMf4Files(device, maxFolders = 2)

    /**
     * Determines whether an MF4 file is safe to download (i.e. not actively being written).
     * Rule 1 (Sibling): If another MF4 file exists in the same folder with higher timestamp or name, this file is complete.
     * Rule 2 (Age): If its lastWritten timestamp is older than FILE_STABILITY_AGE_MS, it is finished.
     * Rule 3 (Size stability): If size > 0 and unchanged across poll cycles.
     */
    /**
     * Determines whether an MF4 file is safe to download (i.e. not actively being written).
     * Rule 1 (Sibling): Another MF4 file exists in the same folder with higher timestamp or name.
     * Rule 2 (Skew-adjusted age): If compensated age exceeds the stability threshold (75s for the
     *         newest chunk in the newest folder to tolerate CANedge RTC lag, 12s otherwise).
     * Rule 3 (Timed size stability): Size > 0 and unchanged across two polls >= 3s apart.
     */
    fun isFileStable(
        file: CanedgeFile,
        allFilesInSession: List<CanedgeFile>,
        estimatedClockOffsetMs: Long = 0L,
        nowWallMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (file.sizeBytes <= 0) return false

        // Rule 1: Sibling check within the same directory
        val parentDir = file.path.substringBeforeLast('/')
        val siblingFiles = allFilesInSession.filter { it.path.substringBeforeLast('/') == parentDir && it.isMf4 }
        val hasNewerSibling = siblingFiles.any {
            it.lastWrittenEpochSec > file.lastWrittenEpochSec || it.name > file.name
        }
        if (hasNewerSibling) {
            return true
        }

        // Rule 2: Clock-offset-compensated age check
        val newestFolderPath = allFilesInSession.filter { it.isMf4 }
            .maxOfOrNull { it.path.substringBeforeLast('/') }
        val isInNewestFolder = newestFolderPath == null || parentDir == newestFolderPath
        val requiredAgeMs = if (isInNewestFolder) NEWEST_CHUNK_IDLE_AGE_MS else FILE_STABILITY_AGE_MS
        val correctedWrittenMs = file.lastWrittenMs + estimatedClockOffsetMs
        val fileAgeMs = nowWallMs - correctedWrittenMs
        if (fileAgeMs > requiredAgeMs) {
            return true
        }

        // Rule 3: Size stability across two spaced checks (>= 3s apart)
        val previous = lastObservedSizes[file.path]
        if (previous == null || previous.sizeBytes != file.sizeBytes) {
            lastObservedSizes[file.path] = ObservedSizeSample(file.sizeBytes, nowWallMs)
            return false
        }
        if (previous.sizeBytes > 0 && (nowWallMs - previous.observedAtMs) >= SIZE_STABILITY_MIN_INTERVAL_MS) {
            return true
        }

        return false
    }
}
