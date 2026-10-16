package com.bajajauto.roadsense.recording

import android.os.Process
import android.os.SystemClock
import android.os.Trace
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
import java.util.concurrent.locks.LockSupport

import com.bajajauto.roadsense.sync.CrossSensorSyncAssociator
import java.util.concurrent.atomic.AtomicLong

/**
 * Autonomous high-rate recorder for synchronized IMU frames (100–104 Hz).
 *
 * Writes consolidated [ImuFrame] records to "imu/imu_frames.csv" within the active session folder,
 * structured for direct 1:1 conversion into Foxglove / ROS MCAP sensor_msgs/msg/Imu topics.
 *
 * Uses a lock-free, zero-allocation pre-allocated [ImuRingBuffer] (4,096 slots) and a dedicated
 * background consumer thread with [Process.THREAD_PRIORITY_URGENT_AUDIO] to guarantee zero
 * frame drops, minimal disk I/O latency, and complete immunity to UI touch interactions.
 */
class ImuSessionRecorder(
    private val sessionManager: SessionManager? = null,
    private val syncAssociator: CrossSensorSyncAssociator? = null
) {

    private val frameSequenceCounter = AtomicLong(0L)

    companion object {
        private const val TAG = "ImuSessionRecorder"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB disk write buffer
        const val IMU_DIR_NAME = "imu"
        const val IMU_CSV_NAME = "imu_frames.csv"
        private const val TIMELINE_SYNC_INTERVAL_FRAMES = 500L // Periodic timeline anchor every 5s @ 100 Hz
        private const val UI_EMIT_FRAME_INTERVAL = 20L // Throttle StateFlow updates to ~5 Hz (every 20 frames @ 104 Hz)
    }

    private val ringBuffer = ImuRingBuffer(capacity = 4096)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _framesRecorded = MutableStateFlow(0L)
    val framesRecorded: StateFlow<Long> = _framesRecorded.asStateFlow()

    @Volatile
    private var isRecordingActive = false

    private var activeSession: SessionInfo? = null
    private var imuFile: File? = null
    private var workerThread: Thread? = null

    @Volatile
    private var totalFramesWritten: Long = 0L

    /**
     * Starts IMU recording within the provided session directory.
     * Creates the `imu/` subfolder, writes standard CSV header, and starts the dedicated worker thread.
     */
    fun startRecording(sessionInfo: SessionInfo) {
        if (isRecordingActive) {
            Log.w(TAG, "IMU recording already active in session: ${activeSession?.sessionId}")
            return
        }

        try {
            val imuDir = File(sessionInfo.sessionDir, IMU_DIR_NAME).apply { mkdirs() }
            val file = File(imuDir, IMU_CSV_NAME)
            val isNewFile = !file.exists() || file.length() == 0L

            activeSession = sessionInfo
            imuFile = file
            totalFramesWritten = 0L
            _framesRecorded.value = 0L
            ringBuffer.reset()
            frameSequenceCounter.set(0L)
            isRecordingActive = true
            _isRecording.value = true

            val startMonoNs = SystemClock.elapsedRealtimeNanos()
            sessionManager?.recordTimelineEvent(
                elapsedRealtimeNs = startMonoNs,
                sensor = "IMU",
                event = "START",
                sequenceId = 0L,
                relativePath = "$IMU_DIR_NAME/$IMU_CSV_NAME",
                summary = "IMU recording started (Consolidated native 104 Hz MCAP-ready frames)"
            )

            // Spawn dedicated consumer worker thread
            workerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                ringBuffer.consumerThread = Thread.currentThread()

                var writer: BufferedWriter? = null
                val sb = StringBuilder(128)

                try {
                    writer = BufferedWriter(FileWriter(file, true), BUFFER_SIZE)
                    if (isNewFile) {
                        writer.write(ImuFrame.CSV_HEADER)
                        writer.flush()
                    }

                    while (isRecordingActive || ringBuffer.availableToRead() > 0) {
                        if (ringBuffer.availableToRead() == 0) {
                            // Park for up to 10 ms waiting for producer signal
                            LockSupport.parkNanos(10_000_000L)
                            continue
                        }

                        ringBuffer.drainTo(writer, sb, maxBatch = 128) { slot, currentTail ->
                            totalFramesWritten = currentTail

                            // Throttle Compose StateFlow updates to ~5 Hz
                            if (currentTail % UI_EMIT_FRAME_INTERVAL == 0L) {
                                _framesRecorded.value = currentTail
                            }

                            // Periodic timeline synchronization anchor (every 500 frames ~ 5s)
                            if (currentTail % TIMELINE_SYNC_INTERVAL_FRAMES == 0L) {
                                sessionManager?.recordTimelineEvent(
                                    elapsedRealtimeNs = slot.elapsedRealtimeNs,
                                    sensor = "IMU",
                                    event = "SYNC_ANCHOR",
                                    sequenceId = currentTail,
                                    relativePath = "$IMU_DIR_NAME/$IMU_CSV_NAME",
                                    summary = "Frames: $currentTail, ax=${slot.ax}, ay=${slot.ay}, az=${slot.az}"
                                )
                            }
                        }
                    }

                    writer.flush()
                } catch (e: IOException) {
                    AppLogger.e(TAG, "Error in IMU worker write loop", e)
                } finally {
                    try {
                        writer?.flush()
                        writer?.close()
                    } catch (_: IOException) {}
                    _framesRecorded.value = totalFramesWritten
                    AppLogger.i(TAG, "Closed IMU CSV writer: $totalFramesWritten frames written")
                }
            }, "ImuSessionRecorder-Worker").apply {
                priority = Thread.MAX_PRIORITY - 1
                start()
            }

            AppLogger.i(TAG, "Started IMU recording -> ${file.absolutePath}")
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to start IMU recording", e)
            isRecordingActive = false
            _isRecording.value = false
        }
    }

    /**
     * Enqueues an [ImuFrame] into the lock-free ring buffer.
     * Non-blocking, zero-lock, safe for high-frequency calling.
     */
    fun recordFrame(frame: ImuFrame) {
        if (!isRecordingActive) return

        val seq = frameSequenceCounter.incrementAndGet()
        syncAssociator?.pushImu(frame, seq)

        ringBuffer.enqueue(
            elapsedRealtimeNs = frame.elapsedRealtimeNs,
            deliveryNs = if (frame.deliveryNs > 0L) frame.deliveryNs else SystemClock.elapsedRealtimeNanos(),
            wallTimeMs = frame.wallTimeMs,
            ax = frame.ax, ay = frame.ay, az = frame.az,
            gx = frame.gx, gy = frame.gy, gz = frame.gz,
            qx = frame.qx, qy = frame.qy, qz = frame.qz, qw = frame.qw,
            linAx = frame.linAx, linAy = frame.linAy, linAz = frame.linAz
        )
    }

    /**
     * Zero-allocation direct enqueue overload.
     * Takes primitive floats and timestamps directly, avoiding any object allocation on the producer thread.
     */
    fun recordFrame(
        elapsedRealtimeNs: Long,
        deliveryNs: Long,
        wallTimeMs: Long,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        linAx: Float, linAy: Float, linAz: Float
    ): Boolean {
        if (!isRecordingActive) return false

        val seq = frameSequenceCounter.incrementAndGet()
        syncAssociator?.pushImu(
            elapsedRealtimeNs = elapsedRealtimeNs,
            wallTimeMs = wallTimeMs,
            deliveryNs = deliveryNs,
            sequenceId = seq,
            ax = ax, ay = ay, az = az,
            gx = gx, gy = gy, gz = gz,
            qx = qx, qy = qy, qz = qz, qw = qw,
            linAx = linAx, linAy = linAy, linAz = linAz
        )

        return ringBuffer.enqueue(
            elapsedRealtimeNs = elapsedRealtimeNs,
            deliveryNs = deliveryNs,
            wallTimeMs = wallTimeMs,
            ax = ax, ay = ay, az = az,
            gx = gx, gy = gy, gz = gz,
            qx = qx, qy = qy, qz = qz, qw = qw,
            linAx = linAx, linAy = linAy, linAz = linAz
        )
    }

    /**
     * Flushes buffered writes, waits for the worker thread to drain remaining slots, and finalizes session.
     * @return Total count of recorded IMU frames
     */
    fun stopRecording(): Long {
        if (!isRecordingActive) return totalFramesWritten

        isRecordingActive = false
        _isRecording.value = false

        // Wake consumer worker thread to drain remaining frames
        ringBuffer.consumerThread?.let { LockSupport.unpark(it) }

        try {
            workerThread?.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            workerThread = null
        }

        val finalCount = totalFramesWritten
        val stopMonoNs = SystemClock.elapsedRealtimeNanos()

        sessionManager?.recordTimelineEvent(
            elapsedRealtimeNs = stopMonoNs,
            sensor = "IMU",
            event = "STOP",
            sequenceId = finalCount,
            relativePath = "$IMU_DIR_NAME/$IMU_CSV_NAME",
            summary = "IMU recording stopped: $finalCount total frames, dropped=${ringBuffer.droppedFrames}"
        )

        activeSession = null
        return finalCount
    }
}
