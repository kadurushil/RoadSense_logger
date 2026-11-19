package com.bajajauto.roadsense.canedge.model

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * Represents a file or directory on the CANedge2 SD card storage.
 */
@Immutable
data class CanedgeFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastWrittenEpochSec: Long
) {
    val isMf4: Boolean
        get() = name.endsWith(".mf4", ignoreCase = true)

    val lastWrittenMs: Long
        get() = lastWrittenEpochSec * 1000L

    val folderName: String
        get() {
            val parts = path.trim('/').split('/')
            return if (parts.size >= 2) parts[parts.size - 2] else "root"
        }

    val localName: String
        get() {
            val parts = path.trim('/').split('/')
            return if (parts.size >= 2) "${parts[parts.size - 2]}_${parts.last()}" else name
        }
}

/**
 * OneDrive-style lifecycle status for a CANedge log file.
 */
enum class CanedgeFileStatus {
    REMOTE_ONLY,   // Only exists on logger SD card
    SYNCING,       // Actively downloading
    IN_POOL,       // Cached in local staging pool
    IN_SESSION     // Staged in active / completed session folder
}

/**
 * Unified representation of a CANedge file across remote SD, local pool, and session.
 */
@Immutable
data class CanedgeUnifiedFileItem(
    val file: CanedgeFile,
    val folderName: String,
    val status: CanedgeFileStatus,
    val localFile: File? = null,
    val isOnCanedge: Boolean = true,
    val isCopiedToDevice: Boolean = false,
    val isAssignedToSession: Boolean = false
)

/**
 * High-level statistics displayed on the CANedge UI card.
 */
/**
 * High-level CANedge2 ingestion lifecycle phase surfaced in the HUD and dashboard.
 */
enum class CanedgeHudPhase {
    OFFLINE,
    OFFLINE_PENDING,
    SCANNING,
    CALIBRATING,
    CONNECTED_SYNCED,
    SYNCING,
    PAUSED_RECORDING,
    WAITING_FINAL_CHUNK,
    ERROR
}

@Immutable
data class CanedgeSyncStats(
    val targetScopeFiles: Int = 0,            // Total planned files in recent-folder scope
    val poolSyncedFiles: Int = 0,             // Total files synced in local pool within scope
    val poolFilesCount: Int = 0,              // Total distinct MF4 files cached in local pool
    val currentSessionFiles: Int = 0,         // Files currently copied into this session
    val sessionTargetFiles: Int = 0,          // Total files identified for this session
    val isFinalizingSession: Boolean = false, // True while staging files after stop
    val lastSyncTimeMs: Long = 0L,
    val isSyncing: Boolean = false,
    val lastError: String? = null,

    // Real-time file transfer telemetry
    val activeFileName: String? = null,
    val activeFileBytesTransferred: Long = 0L,
    val activeFileTotalBytes: Long = 0L,
    val activeFileProgress: Float = 0f,
    val transferSpeedBytesPerSec: Long = 0L,
    val etaSeconds: Int = 0,

    // Continuous watchdog, multi-session reconciliation & clock-skew telemetry
    val hudPhase: CanedgeHudPhase = CanedgeHudPhase.OFFLINE,
    val pendingSessionsCount: Int = 0,
    val measuredClockOffsetMs: Long? = null,
    val isClockOffsetVerified: Boolean = false,
    val queueCompletedFiles: Int = 0,
    val queueTotalFiles: Int = 0,
    val isPausedDuringRecording: Boolean = false,
    val isWaitingFinalChunk: Boolean = false
) {
    // Backwards compatibility accessors
    val totalFilesOnDevice: Int get() = targetScopeFiles
    val totalSyncedFiles: Int get() = poolSyncedFiles

    val formattedSpeed: String
        get() = when {
            transferSpeedBytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", transferSpeedBytesPerSec / (1024f * 1024f))
            transferSpeedBytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%.0f KB/s", transferSpeedBytesPerSec / 1024f)
            transferSpeedBytesPerSec > 0 -> "$transferSpeedBytesPerSec B/s"
            else -> "-- KB/s"
        }

    val formattedTransferSize: String
        get() = when {
            activeFileTotalBytes > 0 -> {
                val transferredMb = activeFileBytesTransferred / (1024f * 1024f)
                val totalMb = activeFileTotalBytes / (1024f * 1024f)
                String.format(java.util.Locale.US, "%.1f / %.1f MB", transferredMb, totalMb)
            }
            activeFileBytesTransferred > 0 -> {
                String.format(java.util.Locale.US, "%.1f MB", activeFileBytesTransferred / (1024f * 1024f))
            }
            else -> ""
        }
}
