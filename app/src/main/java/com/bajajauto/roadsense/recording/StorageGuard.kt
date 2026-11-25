package com.bajajauto.roadsense.recording

import android.os.StatFs
import java.io.File

/**
 * Storage health classification for internal recording directory protection.
 * Prevents partial/corrupt MP4 `moov` atoms when disk space is exhausted mid-drive.
 */
enum class StorageHealth {
    OK,
    LOW_WARN,
    CRITICAL_STOP
}

data class StorageStatus(
    val freeMb: Long,
    val health: StorageHealth
)

object StorageGuard {
    const val WARN_THRESHOLD_MB = 1500L
    const val CRITICAL_STOP_MB = 500L

    fun evaluate(freeMb: Long): StorageStatus {
        if (freeMb < 0L) {
            return StorageStatus(freeMb = freeMb, health = StorageHealth.OK)
        }
        val health = when {
            freeMb < CRITICAL_STOP_MB -> StorageHealth.CRITICAL_STOP
            freeMb < WARN_THRESHOLD_MB -> StorageHealth.LOW_WARN
            else -> StorageHealth.OK
        }
        return StorageStatus(freeMb = freeMb, health = health)
    }

    fun queryFreeMb(dir: File): Long {
        return try {
            val target = if (dir.exists()) dir else (dir.parentFile ?: dir)
            val stat = StatFs(target.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        } catch (_: Exception) {
            -1L
        }
    }
}
