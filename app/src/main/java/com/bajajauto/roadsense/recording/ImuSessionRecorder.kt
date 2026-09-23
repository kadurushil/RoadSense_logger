package com.bajajauto.roadsense.recording

import android.os.SystemClock
import android.util.Log
import com.bajajauto.roadsense.imu.ImuFrame
import com.bajajauto.roadsense.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Autonomous high-rate recorder for synchronized IMU frames (100 Hz).
 *
 * Writes consolidated [ImuFrame] records to "imu/imu_frames.csv" within the active session folder,
 * structured for direct 1:1 conversion into Foxglove / ROS MCAP sensor_msgs/msg/Imu topics.
 *
 * Runs on a dedicated single-thread background executor with a 32 KB buffer to guarantee zero
 * frame drops and minimal disk I/O overhead.
 */
class ImuSessionRecorder(
    private val sessionManager: SessionManager? = null
) {

    companion object {
        private const val TAG = "ImuSessionRecorder"
        private const val BUFFER_SIZE = 32 * 1024 // 32 KB buffer
        const val IMU_DIR_NAME = "imu"
        const val IMU_CSV_NAME = "imu_frames.csv"
        private const val TIMELINE_SYNC_INTERVAL_FRAMES = 500L // Periodic timeline anchor every 5s @ 100 Hz
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ImuSessionRecorder-Worker").apply {
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
    private var imuFile: File? = null
    private var frameCounter: Long = 0L

    /**
     * Starts IMU recording within the provided session directory.
     * Creates the `imu/` subfolder and writes the standard CSV header.
     */
    fun startRecording(sessionInfo: SessionInfo) {
        if (isRecordingActive) {
            Log.w(TAG, "IMU recording already active in session: ${activeSession?.sessionId}")
            return
        }

        try {
            val imuDir = File(sessionInfo.sessionDir, IMU_DIR_NAME).apply { mkdirs() }
            val file = File(imuDir, IMU_CSV_NAME)
            val writer = BufferedWriter(FileWriter(file, true), BUFFER_SIZE)

            // Write CSV header if newly created or empty
            if (file.length() == 0L) {
                writer.write(ImuFrame.CSV_HEADER)
                writer.flush()
            }

            activeSession = sessionInfo
            csvWriter = writer
            imuFile = file
            frameCounter = 0L
            _framesRecorded.value = 0L
            isRecordingActive = true
            _isRecording.value = true

            val startMonoNs = SystemClock.elapsedRealtimeNanos()
            sessionManager?.recordTimelineEvent(
                elapsedRealtimeNs = startMonoNs,
                sensor = "IMU",
                event = "START",
                sequenceId = 0L,
                relativePath = "$IMU_DIR_NAME/$IMU_CSV_NAME",
                summary = "IMU recording started (Consolidated 100 Hz MCAP-ready frames)"
            )

            AppLogger.i(TAG, "Started IMU recording -> ${file.absolutePath}")
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to start IMU recording", e)
            isRecordingActive = false
            _isRecording.value = false
            try {
                csvWriter?.close()
            } catch (_: IOException) {}
            csvWriter = null
        }
    }

    /**
     * Records a consolidated [ImuFrame] asynchronously on the background worker thread.
     */
    fun recordFrame(frame: ImuFrame) {
        if (!isRecordingActive) return

        executor.execute {
            if (!isRecordingActive) return@execute
            val writer = csvWriter ?: return@execute

            try {
                writer.write(frame.toCsvRow())
                frameCounter++
                _framesRecorded.value = frameCounter

                // Periodic timeline synchronization anchor (every 5 seconds)
                if (frameCounter % TIMELINE_SYNC_INTERVAL_FRAMES == 0L) {
                    sessionManager?.recordTimelineEvent(
                        elapsedRealtimeNs = frame.elapsedRealtimeNs,
                        sensor = "IMU",
                        event = "SYNC_ANCHOR",
                        sequenceId = frameCounter,
                        relativePath = "$IMU_DIR_NAME/$IMU_CSV_NAME",
                        summary = "Frames: $frameCounter, ax=${frame.ax}, ay=${frame.ay}, az=${frame.az}"
                    )
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "Failed writing IMU frame #$frameCounter", e)
            }
        }
    }

    /**
     * Flushes buffered writes, closes the CSV file, and finalizes the recording session.
     * @return Total count of recorded IMU frames
     */
    fun stopRecording(): Long {
        if (!isRecordingActive) return 0L

        isRecordingActive = false
        _isRecording.value = false

        val finalCount = frameCounter
        val stopMonoNs = SystemClock.elapsedRealtimeNanos()

        executor.execute {
            try {
                csvWriter?.flush()
                csvWriter?.close()
                AppLogger.i(TAG, "Stopped IMU recording: $finalCount frames written to ${imuFile?.name}")
            } catch (e: IOException) {
                AppLogger.e(TAG, "Error closing IMU CSV writer", e)
            } finally {
                csvWriter = null
                activeSession = null
            }
        }

        sessionManager?.recordTimelineEvent(
            elapsedRealtimeNs = stopMonoNs,
            sensor = "IMU",
            event = "STOP",
            sequenceId = finalCount,
            relativePath = "$IMU_DIR_NAME/$IMU_CSV_NAME",
            summary = "IMU recording stopped: $finalCount total frames"
        )

        return finalCount
    }
}
