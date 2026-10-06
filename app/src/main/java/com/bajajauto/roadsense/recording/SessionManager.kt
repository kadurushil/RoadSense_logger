package com.bajajauto.roadsense.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.logging.AppLogger
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
        const val CALIBRATION_FILE_NAME = "radar_camera_calib.json"
    }

    private val timelineWriter = SessionTimelineWriter()

    val sessionsBaseDir: File
        get() {
            val base = File(context.getExternalFilesDir(null) ?: context.filesDir, SESSIONS_DIR_NAME)
            if (!base.exists()) {
                base.mkdirs()
            }
            return base
        }

    /**
     * Initializes a new session folder with subdirectories (radar/, gnss/)
     * and generates the initial session_metadata.json and session_timeline.csv.
     */
    fun createSession(): SessionInfo {
        val nowMs = System.currentTimeMillis()
        val nowMonoNs = SystemClock.elapsedRealtimeNanos()
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val sessionId = "session_$timestampStr"

        val sessionDir = File(sessionsBaseDir, sessionId).apply { mkdirs() }
        val radarDir = File(sessionDir, "radar").apply { mkdirs() }
        val gnssDir = File(sessionDir, "gnss").apply { mkdirs() }
        val cameraDir = File(sessionDir, "camera").apply { mkdirs() }
        val canDir = File(sessionDir, "can").apply { mkdirs() }
        val imuDir = File(sessionDir, "imu").apply { mkdirs() }

        val sessionInfo = SessionInfo(
            sessionId = sessionId,
            sessionDir = sessionDir,
            radarDir = radarDir,
            gnssDir = gnssDir,
            cameraDir = cameraDir,
            canDir = canDir,
            imuDir = imuDir,
            startTimeWallMs = nowMs,
            startTimeMonotonicNs = nowMonoNs
        )

        // Initialize master timeline writer
        timelineWriter.start(sessionDir)
        timelineWriter.recordEvent(
            elapsedRealtimeNs = nowMonoNs,
            sensor = "SESSION",
            event = "START",
            sequenceId = 0L,
            relativePath = METADATA_FILE_NAME,
            summary = "Session initialized: $sessionId"
        )

        // Initialize session diagnostic flight recorder
        com.bajajauto.roadsense.logging.AppLogger.attachSession(sessionDir)
        com.bajajauto.roadsense.logging.AppLogger.i(TAG, "Created session directory: ${sessionDir.absolutePath}")

        // Copy global calibration snapshot if available
        try {
            val globalCalibFile = File(File(context.getExternalFilesDir(null) ?: context.filesDir, "calibration"), CALIBRATION_FILE_NAME)
            if (globalCalibFile.exists() && globalCalibFile.length() > 0L) {
                val sessionCalibFile = File(sessionDir, CALIBRATION_FILE_NAME)
                globalCalibFile.copyTo(sessionCalibFile, overwrite = true)
                AppLogger.i(TAG, "Initialized session with active calibration snapshot from ${globalCalibFile.name}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not copy active calibration snapshot: ${e.message}")
        }

        // Write initial session metadata
        writeMetadata(sessionInfo)
        return sessionInfo
    }

    /**
     * Logs a sensor event into the master cross-sensor timeline.
     */
    fun recordTimelineEvent(
        elapsedRealtimeNs: Long,
        sensor: String,
        event: String,
        sequenceId: Long,
        relativePath: String,
        summary: String
    ) {
        timelineWriter.recordEvent(elapsedRealtimeNs, sensor, event, sequenceId, relativePath, summary)
    }

    /**
     * Finalizes session timestamps, closes timeline, and updates session_metadata.json.
     */
    fun closeSession(sessionInfo: SessionInfo) {
        val nowMs = System.currentTimeMillis()
        val nowMonoNs = SystemClock.elapsedRealtimeNanos()
        sessionInfo.stopTimeWallMs = nowMs
        sessionInfo.stopTimeMonotonicNs = nowMonoNs

        timelineWriter.recordEvent(
            elapsedRealtimeNs = nowMonoNs,
            sensor = "SESSION",
            event = "STOP",
            sequenceId = 0L,
            relativePath = METADATA_FILE_NAME,
            summary = "Session finished: totalFrames=${sessionInfo.totalRadarFrames};totalFixes=${sessionInfo.totalGnssFixes}"
        )
        timelineWriter.stop()

        writeMetadata(sessionInfo)
        com.bajajauto.roadsense.logging.AppLogger.i(TAG, "Closed session ${sessionInfo.sessionId}, total frames: ${sessionInfo.totalRadarFrames}, total bytes: ${sessionInfo.totalRadarBytes}, total fixes: ${sessionInfo.totalGnssFixes}")
        com.bajajauto.roadsense.logging.AppLogger.detachSession()
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
     * Serializes or updates calibration snapshot in radar_camera_calib.json.
     */
    fun saveSessionCalibration(sessionInfo: SessionInfo, params: CalibrationParameters) {
        try {
            val calibFile = File(sessionInfo.sessionDir, CALIBRATION_FILE_NAME)
            calibFile.writeText(params.toJson().toString(2), Charsets.UTF_8)
            AppLogger.i(TAG, "Saved active calibration snapshot to ${calibFile.name} (Pitch=${params.effectivePitchDeg}°, Yaw=${params.effectiveYawDeg}°)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write session calibration to ${sessionInfo.sessionDir.name}", e)
        }
    }

    /**
     * Returns all existing session folders sorted with the newest first.
     */
    fun listSessions(): List<File> {
        return sessionsBaseDir.listFiles { file -> file.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    }
}
