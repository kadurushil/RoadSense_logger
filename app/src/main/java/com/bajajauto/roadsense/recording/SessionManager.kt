package com.bajajauto.roadsense.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages structured multi-sensor recording directories and metadata files under:
 * /Android/data/com.bajajauto.roadsense/files/sessions/session_YYYYMMDD_HHMMSS/
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"
        const val SESSIONS_DIR_NAME = "sessions"
        const val METADATA_FILE_NAME = "session_metadata.json"
    }

    val sessionsBaseDir: File
        get() {
            val base = File(context.getExternalFilesDir(null) ?: context.filesDir, SESSIONS_DIR_NAME)
            if (!base.exists()) {
                base.mkdirs()
            }
            return base
        }

    /**
     * Initializes a new session folder with subdirectories (e.g., radar/)
     * and generates the initial session_metadata.json file.
     */
    fun createSession(): SessionInfo {
        val nowMs = System.currentTimeMillis()
        val nowMonoNs = SystemClock.elapsedRealtimeNanos()
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val sessionId = "session_$timestampStr"

        val sessionDir = File(sessionsBaseDir, sessionId).apply { mkdirs() }
        val radarDir = File(sessionDir, "radar").apply { mkdirs() }

        val sessionInfo = SessionInfo(
            sessionId = sessionId,
            sessionDir = sessionDir,
            radarDir = radarDir,
            startTimeWallMs = nowMs,
            startTimeMonotonicNs = nowMonoNs
        )

        // Write initial session metadata
        writeMetadata(sessionInfo)
        Log.i(TAG, "Created session directory: ${sessionDir.absolutePath}")
        return sessionInfo
    }

    /**
     * Finalizes session timestamps and updates session_metadata.json.
     */
    fun closeSession(sessionInfo: SessionInfo) {
        sessionInfo.stopTimeWallMs = System.currentTimeMillis()
        sessionInfo.stopTimeMonotonicNs = SystemClock.elapsedRealtimeNanos()
        writeMetadata(sessionInfo)
        Log.i(TAG, "Closed session ${sessionInfo.sessionId}, total frames: ${sessionInfo.totalRadarFrames}, total bytes: ${sessionInfo.totalRadarBytes}")
    }

    /**
     * Serializes session info to session_metadata.json.
     */
    fun writeMetadata(sessionInfo: SessionInfo) {
        try {
            val metadataFile = File(sessionInfo.sessionDir, METADATA_FILE_NAME)
            FileWriter(metadataFile).use { writer ->
                writer.write(sessionInfo.toJson())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write session metadata to ${sessionInfo.sessionDir.name}", e)
        }
    }

    /**
     * Returns all existing session folders sorted with the newest first.
     */
    fun listSessions(): List<File> {
        return sessionsBaseDir.listFiles { file -> file.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    }
}
