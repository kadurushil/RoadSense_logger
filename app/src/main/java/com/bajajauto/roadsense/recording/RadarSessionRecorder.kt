package com.bajajauto.roadsense.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bajajauto.roadsense.models.RawRadarPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * High-throughput session recorder that writes:
 * 1. Framed binary packets with monotonic & wall-clock timestamps into "radar_frames.bin"
 * 2. Raw uninterrupted byte stream into "radar_raw_stream.bin"
 * 3. Updates session metadata on session closure.
 */
class RadarSessionRecorder(
    private val context: Context,
    private val sessionManager: SessionManager = SessionManager(context)
) {

    companion object {
        private const val TAG = "RadarSessionRecorder"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer for high-throughput streaming
        private const val UI_UPDATE_INTERVAL_MS = 250L // 4 Hz UI telemetry throttling

        // Sync marker for framed radar binary: "ROAD" (0x52, 0x4F, 0x41, 0x44)
        val FRAME_MAGIC = byteArrayOf(0x52, 0x4F, 0x41, 0x44)
        const val HEADER_SIZE = 24 // 4 (magic) + 8 (monoNs) + 8 (wallMs) + 4 (len)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RadarSessionRecorder-Worker").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    private val _recordingState = MutableStateFlow<SessionRecordingState>(SessionRecordingState.Idle)
    val recordingState: StateFlow<SessionRecordingState> = _recordingState.asStateFlow()

    @Volatile
    private var isRecordingActive = false

    private var activeSession: SessionInfo? = null

    // Framed packets stream
    private var framesFos: FileOutputStream? = null
    private var framesDos: DataOutputStream? = null

    // Raw bytes stream
    private var rawFos: FileOutputStream? = null
    private var rawBos: BufferedOutputStream? = null

    private var framesRecorded: Long = 0L
    private var rawBytesRecorded: Long = 0L
    private var startTimeRealtimeMs: Long = 0L
    private var lastUiUpdateMs: Long = 0L

    /**
     * Initializes a new session folder (or reuses provided session) and opens file streams for recording.
     */
    fun startSession(existingSession: SessionInfo? = null): SessionInfo? {
        if (isRecordingActive) {
            Log.w(TAG, "Recording session already in progress: ${activeSession?.sessionId}")
            return activeSession
        }

        try {
            val session = existingSession ?: sessionManager.createSession()
            val framesFile = File(session.radarDir, "radar_frames.bin")
            val rawFile = File(session.radarDir, "radar_raw_stream.bin")

            val fFos = FileOutputStream(framesFile)
            val fDos = DataOutputStream(BufferedOutputStream(fFos, BUFFER_SIZE))

            val rFos = FileOutputStream(rawFile)
            val rBos = BufferedOutputStream(rFos, BUFFER_SIZE)

            framesFos = fFos
            framesDos = fDos
            rawFos = rFos
            rawBos = rBos

            activeSession = session
            framesRecorded = 0L
            rawBytesRecorded = 0L
            startTimeRealtimeMs = SystemClock.elapsedRealtime()
            lastUiUpdateMs = startTimeRealtimeMs
            isRecordingActive = true

            _recordingState.value = SessionRecordingState.Recording(
                sessionInfo = session,
                framesRecorded = 0L,
                bytesRecorded = 0L,
                durationMs = 0L
            )

            Log.i(TAG, "Started session ${session.sessionId} at ${session.sessionDir.absolutePath}")
            return session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording session", e)
            _recordingState.value = SessionRecordingState.Error("Failed to start session: ${e.message}")
            return null
        }
    }

    /**
     * Appends raw UART bytes directly to "radar_raw_stream.bin".
     * Thread-safe and non-blocking for callers.
     */
    fun writeRawBytes(bytes: ByteArray) {
        if (!isRecordingActive || bytes.isEmpty()) return

        val chunk = bytes.clone()
        executor.execute {
            if (!isRecordingActive) return@execute
            try {
                rawBos?.write(chunk)
                rawBytesRecorded += chunk.size
                updateProgressIfNeeded()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing raw bytes", e)
                _recordingState.value = SessionRecordingState.Error("Raw write error: ${e.message}")
            }
        }
    }

    /**
     * Appends an assembled radar packet with host timestamps to "radar_frames.bin".
     * Header format (24 bytes):
     * - Magic: "ROAD" (4 bytes)
     * - Host monotonic nanoseconds (8 bytes Long)
     * - Host wall-clock milliseconds (8 bytes Long)
     * - Packet payload length N (4 bytes Int)
     * Followed by N bytes of raw radar packet.
     */
    fun writeFrame(packet: RawRadarPacket, hostMonoNs: Long, hostWallMs: Long) {
        if (!isRecordingActive) return

        val payload = (if (packet.fullPacketBytes.isNotEmpty()) packet.fullPacketBytes else packet.payload).clone()
        executor.execute {
            if (!isRecordingActive) return@execute
            try {
                framesDos?.let { dos ->
                    dos.write(FRAME_MAGIC)
                    dos.writeLong(hostMonoNs)
                    dos.writeLong(hostWallMs)
                    dos.writeInt(payload.size)
                    dos.write(payload)
                    framesRecorded++
                    sessionManager.recordTimelineEvent(
                        elapsedRealtimeNs = hostMonoNs,
                        sensor = "RADAR",
                        event = "FRAME",
                        sequenceId = packet.header.frameNumber,
                        relativePath = "radar/radar_frames.bin",
                        summary = "subframe=${packet.header.subFrameNumber};tlvs=${packet.header.numTLVs};objs=${packet.header.numDetectedObj};len=${payload.size}"
                    )
                    updateProgressIfNeeded()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing framed packet", e)
                _recordingState.value = SessionRecordingState.Error("Frame write error: ${e.message}")
            }
        }
    }

    private fun updateProgressIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdateMs = now
            activeSession?.let { session ->
                _recordingState.value = SessionRecordingState.Recording(
                    sessionInfo = session,
                    framesRecorded = framesRecorded,
                    bytesRecorded = rawBytesRecorded,
                    durationMs = now - startTimeRealtimeMs,
                    gnssFixesRecorded = session.totalGnssFixes
                )
            }
        }
    }

    /**
     * Flushes, syncs to disk, updates session metadata, and closes files.
     */
    fun stopSession(): SessionInfo? {
        if (!isRecordingActive) {
            Log.w(TAG, "Recording is not active")
            return null
        }

        isRecordingActive = false
        val session = activeSession
        val durationMs = SystemClock.elapsedRealtime() - startTimeRealtimeMs
        val totalFrames = framesRecorded
        val totalBytes = rawBytesRecorded

        executor.execute {
            try {
                // Flush and sync framed stream
                framesDos?.flush()
                framesFos?.fd?.sync()
                framesDos?.close()
                framesFos?.close()

                // Flush and sync raw stream
                rawBos?.flush()
                rawFos?.fd?.sync()
                rawBos?.close()
                rawFos?.close()

                if (session != null) {
                    session.totalRadarFrames = totalFrames
                    session.totalRadarBytes = totalBytes
                    sessionManager.closeSession(session)

                    _recordingState.value = SessionRecordingState.Finished(
                        sessionInfo = session,
                        totalFrames = totalFrames,
                        totalBytes = totalBytes,
                        durationMs = durationMs,
                        totalGnssFixes = session.totalGnssFixes
                    )
                } else {
                    _recordingState.value = SessionRecordingState.Idle
                }
                Log.i(TAG, "Session ${session?.sessionId} finished: $totalFrames frames, $totalBytes bytes, ${durationMs}ms")
            } catch (e: IOException) {
                Log.e(TAG, "Error finalizing recording session", e)
                _recordingState.value = SessionRecordingState.Error("Error closing session: ${e.message}")
            } finally {
                framesDos = null
                framesFos = null
                rawBos = null
                rawFos = null
                activeSession = null
            }
        }

        return session
    }

    fun release() {
        if (isRecordingActive) {
            stopSession()
        }
        executor.shutdown()
    }
}
