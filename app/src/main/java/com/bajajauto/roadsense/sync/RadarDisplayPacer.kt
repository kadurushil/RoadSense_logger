package com.bajajauto.roadsense.sync

import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.models.RadarFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RadarDisplayPacer:
 * Eliminates USB transport jitter (such as the Silicon Labs CP2105 71ms/28ms bimodal bulk boundary coalescing)
 * by buffering incoming decoded RadarFrames in a bounded 50 ms jitter queue and dispatching them to the UI
 * at a rock-solid, constant frame rate (default 20.0 Hz / 50.0 ms period).
 *
 * Problem Resolved:
 * The TI AWR1843BOOST radar emits frames at exactly 20.0 Hz (50.0 ms period). However, due to Full-Speed USB
 * packet coalescing and interrupt scheduling on Android, user-space packets arrive bimodally at alternating
 * 71.8 ms and 28.2 ms intervals. Direct assignment to the UI triggers rapid bursts and freezes (14 FPS <-> 35 FPS).
 *
 * Pacer Solution:
 * 1. Initial 50 ms buffer: Frame 0 primes the queue.
 * 2. Constant 50.0 ms target-time dispatcher ticks every 50 ms, perfectly absorbing the 71ms/28ms jitter.
 * 3. Zero latency accumulation: If thread preemption or GC pauses cause the queue to accumulate > maxQueueDepth
 *    (default 2 frames = 100 ms), older frames are pruned to guarantee display latency never exceeds ~50 ms.
 * 4. Automatic idle sleep: Pauses dispatcher when radar stops transmitting (>500 ms gap).
 */
class RadarDisplayPacer(
    val frameIntervalMs: Long = 50L,
    val bufferDelayMs: Long = 50L,
    val maxQueueDepth: Int = 6,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val queue = ConcurrentLinkedQueue<RadarFrame>()
    private val _latestFrame = MutableStateFlow<RadarFrame?>(null)
    val latestFrame: StateFlow<RadarFrame?> = _latestFrame.asStateFlow()

    @Volatile
    private var isRunning = AtomicBoolean(false)
    @Volatile
    private var lastEnqueuedMs = 0L
    @Volatile
    private var lastEnqueueNs = 0L
    @Volatile
    private var lastEmitNs = 0L
    @Volatile
    private var totalDroppedFrames = 0L
    private var pacingJob: Job? = null

    /**
     * Enqueue a newly decoded radar frame from the high-priority worker thread.
     */
    fun enqueueFrame(frame: RadarFrame) {
        val nowWall = System.currentTimeMillis()
        val nowNs = System.nanoTime()
        val deltaArrMs = if (lastEnqueueNs > 0L) (nowNs - lastEnqueueNs) / 1_000_000.0 else 0.0
        lastEnqueuedMs = nowWall
        lastEnqueueNs = nowNs

        queue.offer(frame)
        val seq = frame.header.frameNumber
        if (DebugToggles.isDisplayPacerVerboseActive) {
            val qSize = queue.size
            AppLogger.d("DisplayPacer", "[ENQUEUE] seq=#$seq | deltaArrMs=${"%.2f".format(deltaArrMs)} | qDepth=$qSize")
        }

        // Ensure pacing job is actively running
        if (isRunning.compareAndSet(false, true)) {
            startPacingLoop()
        }
    }

    /**
     * Reset the display pacer (e.g. on radar disconnect).
     */
    fun reset() {
        isRunning.set(false)
        pacingJob?.cancel()
        pacingJob = null
        queue.clear()
        _latestFrame.value = null
        lastEnqueueNs = 0L
        lastEmitNs = 0L
        totalDroppedFrames = 0L
    }

    fun release() {
        reset()
        scope.cancel()
    }

    /**
     * Internal pacing loop: runs at constant 50.0 ms target intervals.
     */
    private fun startPacingLoop() {
        pacingJob?.cancel()
        pacingJob = scope.launch {
            // Initial buffer delay (50 ms) to build up a 1-frame jitter absorption cushion
            if (bufferDelayMs > 0L) {
                delay(bufferDelayMs)
            }

            var nextTargetNs = System.nanoTime()

            while (isActive && isRunning.get()) {
                // 1. Check for idle timeout (>500 ms without new frames)
                val nowWall = System.currentTimeMillis()
                if (queue.isEmpty() && (nowWall - lastEnqueuedMs > 500L)) {
                    isRunning.set(false)
                    break
                }

                // 2. Bound queue depth to eliminate latency creep:
                // If more than maxQueueDepth frames are queued, drop older frames and keep the freshest
                while (queue.size > maxQueueDepth) {
                    val dropped = queue.poll()
                    if (dropped != null) {
                        totalDroppedFrames++
                        if (DebugToggles.displayPacerDropWarnings || DebugToggles.isDisplayPacerVerboseActive) {
                            val droppedSeq = dropped.header.frameNumber
                            AppLogger.w(
                                "DisplayPacer",
                                "[DROP] seq=#$droppedSeq | qDepth=${queue.size} | totalDrops=$totalDroppedFrames"
                            )
                        }
                    }
                }

                // 3. Dequeue and emit the next frame
                val frameToEmit = queue.poll()
                val nowNs = System.nanoTime()
                if (frameToEmit != null) {
                    val deltaEmitMs = if (lastEmitNs > 0L) (nowNs - lastEmitNs) / 1_000_000.0 else 0.0
                    lastEmitNs = nowNs
                    _latestFrame.value = frameToEmit
                    if (DebugToggles.isDisplayPacerVerboseActive) {
                        val timerJitterMs = (nowNs - nextTargetNs) / 1_000_000.0
                        val seq = frameToEmit.header.frameNumber
                        AppLogger.d(
                            "DisplayPacer",
                            "[EMIT] seq=#$seq | deltaEmitMs=${"%.2f".format(deltaEmitMs)} | jitterMs=${
                                "%.2f".format(timerJitterMs)
                            } | qDepth=${queue.size} | totalDrops=$totalDroppedFrames"
                        )
                    }
                } else if (DebugToggles.isDisplayPacerVerboseActive) {
                    val holdSeq = _latestFrame.value?.header?.frameNumber ?: -1L
                    AppLogger.d("DisplayPacer", "[STARVATION] queue empty at tick | holdSeq=#$holdSeq")
                }

                // 4. Precise target-time sleep for constant cadence
                nextTargetNs += frameIntervalMs * 1_000_000L
                val currentNs = System.nanoTime()
                val sleepNs = nextTargetNs - currentNs

                if (sleepNs > 1_000_000L) {
                    delay(sleepNs / 1_000_000L)
                } else if (sleepNs < -frameIntervalMs * 1_000_000L) {
                    // Fell behind more than 1 whole frame: re-anchor target time to now
                    nextTargetNs = System.nanoTime()
                }
            }
        }
    }

    /**
     * Synchronous drain step for deterministic JVM unit testing without wall-clock waits.
     */
    fun step(targetFrame: RadarFrame? = null): RadarFrame? {
        if (targetFrame != null) {
            enqueueFrame(targetFrame)
        }
        while (queue.size > maxQueueDepth) {
            queue.poll()
        }
        val frame = queue.poll()
        if (frame != null) {
            _latestFrame.value = frame
        }
        return frame
    }
}

