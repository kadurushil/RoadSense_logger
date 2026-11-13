package com.bajajauto.roadsense.sync

import android.os.Process
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.models.RadarFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RadarDisplayPacer:
 * Eliminates USB transport jitter (such as the Silicon Labs CP2105 71ms/28ms bimodal bulk boundary coalescing)
 * by buffering incoming decoded RadarFrames in a bounded low-latency jitter queue and dispatching them to the UI
 * at a rock-solid, constant frame rate (default 20.0 Hz / 50.0 ms period).
 *
 * Architecture:
 * - Dedicated single-thread executor running at `Process.THREAD_PRIORITY_DISPLAY` (-4) so background
 *   HTTP/disk/logging work never induces 100+ ms Dispatchers.Default wakeup stalls.
 * - Low-latency queue bound (`maxQueueDepth = 2` default) with sustained-backlog self-healing: if queue
 *   remains non-empty across >= 3 consecutive ticks (`> 150 ms` of sustained lag), drops the older head frame
 *   before emitting the fresher frame so display lag never permanently latches at 100-250 ms.
 */
class RadarDisplayPacer(
    val frameIntervalMs: Long = 50L,
    val bufferDelayMs: Long = 50L,
    val maxQueueDepth: Int = 3,
    private val scope: CoroutineScope? = null
) {
    companion object {
        private const val SUSTAINED_BLOAT_TICKS = 4
        private const val CLOCK_SKEW_CATCHUP_NS = 2_000_000L
        private const val IDLE_TIMEOUT_MS = 500L
    }

    private val ownedExecutor: ExecutorService? = if (scope == null) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                } catch (_: Throwable) {
                    // Safe fallback under JVM unit tests
                }
                runnable.run()
            }, "RS-DisplayPacer").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    } else {
        null
    }

    private val ownedDispatcher: ExecutorCoroutineDispatcher? =
        ownedExecutor?.asCoroutineDispatcher()

    private val effectiveScope: CoroutineScope =
        scope ?: CoroutineScope((ownedDispatcher ?: Dispatchers.Default) + SupervisorJob())

    private val queue = ConcurrentLinkedQueue<RadarFrame>()
    private val _latestFrame = MutableStateFlow<RadarFrame?>(null)
    val latestFrame: StateFlow<RadarFrame?> = _latestFrame.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var lastEnqueuedMs = 0L
    @Volatile
    private var lastEnqueueNs = 0L
    @Volatile
    private var lastEmitNs = 0L
    @Volatile
    private var totalDroppedFrames = 0L
    @Volatile
    private var sustainedBacklogTicks = 0
    @Volatile
    private var singleFrameLagTicks = 0
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
        sustainedBacklogTicks = 0
        singleFrameLagTicks = 0
    }

    fun release() {
        reset()
        effectiveScope.cancel()
        try {
            ownedDispatcher?.close()
        } catch (_: Throwable) {}
        try {
            ownedExecutor?.shutdownNow()
        } catch (_: Throwable) {}
    }

    fun getTotalDroppedFrames(): Long = totalDroppedFrames

    fun getBufferedCount(): Int = queue.size

    /**
     * Internal pacing loop: runs at constant 50.0 ms target intervals on RS-DisplayPacer.
     */
    private fun startPacingLoop() {
        pacingJob?.cancel()
        pacingJob = effectiveScope.launch {
            // Initial buffer delay (50 ms) to build up a 1-frame jitter absorption cushion
            if (bufferDelayMs > 0L) {
                delay(bufferDelayMs)
            }

            var nextTargetNs = System.nanoTime()

            while (isActive && isRunning.get()) {
                // 1. Check for idle timeout (>500 ms without new frames)
                val nowWall = System.currentTimeMillis()
                if (queue.isEmpty() && (nowWall - lastEnqueuedMs > IDLE_TIMEOUT_MS)) {
                    isRunning.set(false)
                    sustainedBacklogTicks = 0
                    singleFrameLagTicks = 0
                    break
                }

                // 2. Process one tick (overflow pruning + sustained bloat heal + emit)
                processSingleTick(nextTargetNs)

                // 3. Precise target-time sleep with soft clock-skew catchup (-2 ms) when 1 cushion frame
                //    persists across ticks, absorbing 20.03 Hz -> 20.00 Hz crystal drift with 0 dropped frames.
                val intervalNs = frameIntervalMs * 1_000_000L
                val skewAdjustNs = if (singleFrameLagTicks >= SUSTAINED_BLOAT_TICKS) CLOCK_SKEW_CATCHUP_NS else 0L
                nextTargetNs += (intervalNs - skewAdjustNs)
                val currentNs = System.nanoTime()
                val sleepNs = nextTargetNs - currentNs

                if (sleepNs > 1_000_000L) {
                    delay(sleepNs / 1_000_000L)
                } else if (sleepNs < -intervalNs) {
                    // Fell behind by more than 1 whole frame: re-anchor cleanly instead of spin-bursting
                    nextTargetNs = System.nanoTime()
                }
            }
        }
    }

    private fun processSingleTick(targetNs: Long): RadarFrame? {
        // 1. Overflow bound: drop oldest frames whenever queue exceeds maxQueueDepth
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

        // 2. Sustained multi-frame backlog trim: only drop when >= 2 frames remain backed up after emit
        //    for >= 4 ticks AND >= 3 frames are queued now (leaves healthy 1-frame jitter cushion untouched).
        if (sustainedBacklogTicks >= SUSTAINED_BLOAT_TICKS && queue.size >= 3) {
            val bloatDropped = queue.poll()
            if (bloatDropped != null) {
                totalDroppedFrames++
                sustainedBacklogTicks = 0
                if (DebugToggles.displayPacerDropWarnings || DebugToggles.isDisplayPacerVerboseActive) {
                    val droppedSeq = bloatDropped.header.frameNumber
                    AppLogger.w(
                        "DisplayPacer",
                        "[BLOAT_TRIM] seq=#$droppedSeq | qDepth=${queue.size} | totalDrops=$totalDroppedFrames"
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

            val remaining = queue.size
            if (remaining >= 2) {
                sustainedBacklogTicks++
                singleFrameLagTicks++
            } else if (remaining == 1) {
                sustainedBacklogTicks = 0
                singleFrameLagTicks++
            } else {
                sustainedBacklogTicks = 0
                singleFrameLagTicks = 0
            }

            if (DebugToggles.isDisplayPacerVerboseActive) {
                val timerJitterMs = (nowNs - targetNs) / 1_000_000.0
                val seq = frameToEmit.header.frameNumber
                AppLogger.d(
                    "DisplayPacer",
                    "[EMIT] seq=#$seq | deltaEmitMs=${"%.2f".format(deltaEmitMs)} | jitterMs=${
                        "%.2f".format(timerJitterMs)
                    } | qDepth=$remaining | totalDrops=$totalDroppedFrames"
                )
            }
        } else {
            sustainedBacklogTicks = 0
            singleFrameLagTicks = 0
            if (DebugToggles.isDisplayPacerVerboseActive) {
                val holdSeq = _latestFrame.value?.header?.frameNumber ?: -1L
                AppLogger.d("DisplayPacer", "[STARVATION] queue empty at tick | holdSeq=#$holdSeq")
            }
        }

        return frameToEmit
    }

    internal fun enqueueForTesting(frame: RadarFrame) {
        queue.offer(frame)
    }

    /**
     * Synchronous drain step for deterministic JVM unit testing without wall-clock waits.
     */
    fun step(targetFrame: RadarFrame? = null): RadarFrame? {
        if (targetFrame != null) {
            queue.offer(targetFrame)
        }
        return processSingleTick(System.nanoTime())
    }
}
