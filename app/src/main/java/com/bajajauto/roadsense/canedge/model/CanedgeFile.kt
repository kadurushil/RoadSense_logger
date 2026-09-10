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
 * High-level statistics displayed on the CANedge UI card.
 */
data class CanedgeSyncStats(
    val totalFilesOnDevice: Int = 0,
    val totalSyncedFiles: Int = 0,
    val currentSessionFiles: Int = 0,
    val lastSyncTimeMs: Long = 0L,
    val isSyncing: Boolean = false,
    val lastError: String? = null
)
