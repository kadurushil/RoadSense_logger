package com.bajajauto.roadsense.recording

/**
 * Lifecycle states of an active multi-sensor recording session.
 */
sealed class SessionRecordingState {
    object Idle : SessionRecordingState()

    data class Recording(
        val sessionInfo: SessionInfo,
        val framesRecorded: Long,
        val bytesRecorded: Long,
        val durationMs: Long,
        val gnssFixesRecorded: Long = 0L
    ) : SessionRecordingState()

    data class Finished(
        val sessionInfo: SessionInfo,
        val totalFrames: Long,
        val totalBytes: Long,
        val durationMs: Long,
        val totalGnssFixes: Long = 0L
    ) : SessionRecordingState()

    data class Error(val message: String) : SessionRecordingState()
}
