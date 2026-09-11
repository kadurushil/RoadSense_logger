package com.bajajauto.roadsense.canedge.repository

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
    }

    // Cache of (remotePath -> observed size) to detect growth
    private val lastObservedSizes = mutableMapOf<String, Long>()

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
            AppLogger.d(TAG, "Listing directory at $endpoint")

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
                AppLogger.d(TAG, "Directory $endpoint returned ${filesList.size} items: ${filesList.map { "${it.name}${if (it.isDirectory) "/" else ""}" }}")
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
     * under /LOG/<DEVICE_ID>/ (e.g. top 5 folders descending).
     * This captures power-cycled segments while avoiding scanning dozens of historical archives.
     */
    suspend fun listRecentSessionMf4Files(device: CanedgeDevice, maxFolders: Int = 5): List<CanedgeFile> =
        withContext(Dispatchers.IO) {
            val logEntries = listDirectory(device, "/LOG/")
            val deviceDir = logEntries.firstOrNull { it.isDirectory && (it.name.equals(device.deviceId, ignoreCase = true) || it.name.length == 8) }
            val targetDevicePath = deviceDir?.path ?: "/LOG/${device.deviceId}/"

            val sessionDirs = listDirectory(device, targetDevicePath)
                .filter { it.isDirectory && !it.name.startsWith(".") && it.name.matches(Regex("\\d+")) }
                .sortedByDescending { it.name }

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
            AppLogger.i(TAG, "Targeted recent session scan (${dirsToScan.map { it.name }}): ${files.size} MF4 files found")
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
    fun isFileStable(file: CanedgeFile, allFilesInSession: List<CanedgeFile>): Boolean {
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

        // Rule 2: Age check
        val nowMs = System.currentTimeMillis()
        val fileAgeMs = nowMs - file.lastWrittenMs
        if (fileAgeMs > FILE_STABILITY_AGE_MS) {
            return true
        }

        // Rule 3: Size stability across checks
        val previousSize = lastObservedSizes[file.path]
        lastObservedSizes[file.path] = file.sizeBytes
        if (previousSize != null && previousSize == file.sizeBytes && previousSize > 0) {
            return true
        }

        return false
    }
}
