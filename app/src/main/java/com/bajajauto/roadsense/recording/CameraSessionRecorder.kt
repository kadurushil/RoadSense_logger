package com.bajajauto.roadsense.recording

import android.util.Log
import com.bajajauto.roadsense.camera.CameraFrameMetadata
import com.bajajauto.roadsense.camera.CameraResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Autonomous recorder for camera video metadata and microsecond frame shutter timestamps.
 * Writes frame numbers and exposure start nanoseconds to "camera/camera_frames.csv"
 * and indexes each frame in the master cross-sensor "session_timeline.csv".
 */
class CameraSessionRecorder(
    private val sessionManager: SessionManager? = null
) {

    companion object {
        private const val TAG = "CameraSessionRecorder"
        private const val BUFFER_SIZE = 16 * 1024 // 16 KB buffer
        const val CAMERA_DIR_NAME = "camera"
        const val CAMERA_CSV_NAME = "camera_frames.csv"
        const val CAMERA_VIDEO_NAME = "camera_video.mp4"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CameraSessionRecorder-Worker").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _framesRecorded = MutableStateFlow(0L)
    val framesRecorded: StateFlow<Long> = _framesRecorded.asStateFlow()

    @Volatile
    private var isRecordingActive = false

    private var activeSession: SessionInfo? = null
    private var csvWriter: BufferedWriter? = null
    private var videoFile: File? = null
    private var currentResolution: String = "720p"

    /**
     * Prepares camera recording within the active session folder.
     * Returns the target File for video recording (camera/camera_video.mp4).
     */
    fun startRecording(sessionInfo: SessionInfo, resolution: CameraResolution): File? {
        if (isRecordingActive) {
            Log.w(TAG, "Camera recording already active in session: ${activeSession?.sessionId}")
            return videoFile
        }

        try {
            val cameraDir = File(sessionInfo.sessionDir, CAMERA_DIR_NAME).apply { mkdirs() }
            val csvFile = File(cameraDir, CAMERA_CSV_NAME)
            val vFile = File(cameraDir, CAMERA_VIDEO_NAME)

            val writer = BufferedWriter(FileWriter(csvFile, true), BUFFER_SIZE)
            if (csvFile.length() == 0L) {
                writer.write(CameraFrameMetadata.CSV_HEADER)
                writer.flush()
            }

            activeSession = sessionInfo
            csvWriter = writer
            videoFile = vFile
            currentResolution = resolution.label
            _framesRecorded.value = 0L
            isRecordingActive = true
            _isRecording.value = true

            Log.i(TAG, "Started Camera metadata recording to ${csvFile.absolutePath}")
            return vFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera session recorder", e)
            return null
        }
    }

    /**
     * Logs an individual frame shutter event with exact monotonic exposure timestamp.
     * Thread-safe and non-blocking: executed on dedicated single-thread executor.
     */
    fun recordFrame(frame: CameraFrameMetadata) {
        if (!isRecordingActive) return

        executor.execute {
            if (!isRecordingActive) return@execute
            try {
                csvWriter?.let { writer ->
                    writer.write(frame.toCsvRow())
                    val count = _framesRecorded.value + 1
                    _framesRecorded.value = count
                    activeSession?.totalCameraFrames = count

                    sessionManager?.recordTimelineEvent(
                        elapsedRealtimeNs = frame.shutterTimestampNs,
                        sensor = "CAMERA",
                        event = "FRAME",
                        sequenceId = frame.frameNumber,
                        relativePath = "camera/$CAMERA_VIDEO_NAME",
                        summary = "res=$currentResolution;exp=${frame.exposureTimeNs}ns;iso=${frame.iso}"
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing camera frame to CSV", e)
            }
        }
    }

    /**
     * Stops camera metadata logging, flushes buffer, and closes writer.
     */
    fun stopRecording(): Long {
        if (!isRecordingActive) return 0L

        isRecordingActive = false
        _isRecording.value = false
        val total = _framesRecorded.value

        executor.execute {
            try {
                csvWriter?.flush()
                csvWriter?.close()
                Log.i(TAG, "Stopped Camera frame recording. Total frames logged: $total")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing camera CSV writer", e)
            } finally {
                csvWriter = null
                activeSession = null
                videoFile = null
            }
        }

        return total
    }

    fun release() {
        if (isRecordingActive) {
            stopRecording()
        }
        executor.shutdown()
    }
}
