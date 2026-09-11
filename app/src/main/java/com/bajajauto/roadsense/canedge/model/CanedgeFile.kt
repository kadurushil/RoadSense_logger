package com.bajajauto.roadsense.canedge.model

import java.io.File

/**
 * Represents a file or directory on the CANedge2 SD card storage.
 */
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
data class CanedgeUnifiedFileItem(
    val file: CanedgeFile,
    val folderName: String,
    val status: CanedgeFileStatus,
    val localFile: File? = null
)

/**
 * High-level statistics displayed on the CANedge UI card.
 */
data class CanedgeSyncStats(
    val targetScopeFiles: Int = 0,            // Total planned files in top-5 scope
    val poolSyncedFiles: Int = 0,             // Total files synced in local pool
    val currentSessionFiles: Int = 0,         // Files currently copied into this session
    val sessionTargetFiles: Int = 0,          // Total files identified for this session
    val isFinalizingSession: Boolean = false, // True while staging files after stop
    val lastSyncTimeMs: Long = 0L,
    val isSyncing: Boolean = false,
    val lastError: String? = null
) {
    // Backwards compatibility accessors
    val totalFilesOnDevice: Int get() = targetScopeFiles
    val totalSyncedFiles: Int get() = poolSyncedFiles
}
