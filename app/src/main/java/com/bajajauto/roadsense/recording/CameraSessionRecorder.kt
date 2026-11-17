package com.bajajauto.roadsense.recording

import android.os.Process
import android.util.Log
import com.bajajauto.roadsense.camera.CameraFrameMetadata
import com.bajajauto.roadsense.DebugToggles
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
import java.util.concurrent.atomic.AtomicLong

import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.sync.CrossSensorSyncAssociator
import com.bajajauto.roadsense.sync.SyncKeyframeWriter

/**
 * Autonomous recorder for camera video metadata and microsecond frame shutter timestamps.
 * Writes frame numbers and exposure start nanoseconds to "camera/camera_frames.csv",
 * generates synchronized multimodal keyframes in "sync/sync_keyframes.csv",
 * and indexes each frame in the master cross-sensor "session_timeline.csv".
 */
class CameraSessionRecorder(
    private val sessionManager: SessionManager? = null,
    private val syncAssociator: CrossSensorSyncAssociator? = null
) {

    companion object {
        private const val TAG = "CameraSessionRecorder"
        private const val BUFFER_SIZE = 16 * 1024 // 16 KB buffer
        private const val UI_EMIT_FRAME_INTERVAL = 30L // ~1 Hz UI StateFlow throttle to eliminate Semantics IPC choke
        const val CAMERA_DIR_NAME = "camera"
        const val CAMERA_CSV_NAME = "camera_frames.csv"
        const val CAMERA_VIDEO_NAME = "camera_video.mp4"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if (DebugToggles.isDiskWriterPriorityLogsActive) {
                val tid = Process.myTid()
                val osPriority = Process.getThreadPriority(tid)
                AppLogger.i(TAG, "CameraSessionRecorder-Worker started (TID=$tid, OS=$osPriority, Target=${Process.THREAD_PRIORITY_BACKGROUND}, Java=${Thread.currentThread().priority})")
            }
            runnable.run()
        }, "CameraSessionRecorder-Worker").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _framesRecorded = MutableStateFlow(0L)
    val framesRecorded: StateFlow<Long> = _framesRecorded.asStateFlow()

    private val totalFramesCounter = AtomicLong(0L)

    @Volatile
    private var isRecordingActive = false
    private var isFirstVideoFrame = true

    private var activeSession: SessionInfo? = null
    private var videoFile: File? = null
    private var currentResolution: String = "720p"
    private val keyframeWriter = SyncKeyframeWriter()
    private val timelineSummarySb = StringBuilder(160)

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
            val vFile = File(cameraDir, CAMERA_VIDEO_NAME)

            keyframeWriter.start(sessionInfo.syncDir)

            activeSession = sessionInfo
            videoFile = vFile
            currentResolution = resolution.label
            totalFramesCounter.set(0L)
            _framesRecorded.value = 0L
            isFirstVideoFrame = true
            isRecordingActive = true
            _isRecording.value = true

            Log.i(TAG, "Started Camera metadata recording (timeline-indexed, skipped redundant camera_frames.csv) -> ${vFile.absolutePath}")
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
                // Emit explicit VIDEO_START event on the very first frame to anchor ground truth
                if (isFirstVideoFrame) {
                    sessionManager?.recordTimelineEvent(
                        elapsedRealtimeNs = frame.shutterTimestampNs,
                        sensor = "CAMERA",
                        event = "VIDEO_START",
                        sequenceId = frame.frameNumber,
                        relativePath = "camera/$CAMERA_VIDEO_NAME",
                        summary = "first_hw_frame=${frame.frameNumber};res=$currentResolution"
                    )
                    Log.i(TAG, "Tagged VIDEO_START at frameNumber=${frame.frameNumber}, monoNs=${frame.shutterTimestampNs}")
                    isFirstVideoFrame = false
                }

                // Associate with nearest IMU, Radar, and GNSS in O(log N)
                val keyframe = syncAssociator?.associateKeyframe(frame)
                if (keyframe != null) {
                    keyframeWriter.writeKeyframe(keyframe)
                }

                val count = totalFramesCounter.incrementAndGet()
                activeSession?.totalCameraFrames = count
                if (count % UI_EMIT_FRAME_INTERVAL == 0L) {
                    _framesRecorded.value = count
                }

                val sb = timelineSummarySb
                sb.setLength(0)
                sb.append("res=").append(currentResolution)
                    .append(";exp=").append(frame.exposureTimeNs).append("ns")
                    .append(";iso=").append(frame.iso)
                if (keyframe != null) {
                    if (keyframe.imuSequenceId >= 0L) {
                        val imuDtHundredths = Math.round(keyframe.deltaCamImuMs * 100f) / 100f
                        sb.append(";imu_dt=").append(imuDtHundredths).append("ms;imu_seq=").append(keyframe.imuSequenceId)
                    }
                    if (keyframe.isRadarActive) {
                        val radDtHundredths = Math.round(keyframe.deltaCamRadarMs * 100f) / 100f
                        sb.append(";rad_dt=").append(radDtHundredths).append("ms;rad_seq=").append(keyframe.radarFrameNumber)
                    }
                    sb.append(";grade=").append(keyframe.syncGrade)
                }

                sessionManager?.recordTimelineEvent(
                    elapsedRealtimeNs = frame.shutterTimestampNs,
                    sensor = "CAMERA",
                    event = "FRAME",
                    sequenceId = frame.frameNumber,
                    relativePath = "camera/$CAMERA_VIDEO_NAME",
                    summary = sb.toString()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error logging camera frame", e)
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
        val total = totalFramesCounter.get()
        _framesRecorded.value = total
        keyframeWriter.stop()

        executor.execute {
            Log.i(TAG, "Stopped Camera frame recording. Total frames logged: $total")
            activeSession = null
            videoFile = null
        }

        return total
    }

    fun release() {
        if (isRecordingActive) {
            stopRecording()
        }
        keyframeWriter.release()
        executor.shutdown()
    }
}
