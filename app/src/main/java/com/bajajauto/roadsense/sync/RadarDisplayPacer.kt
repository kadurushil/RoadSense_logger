package com.bajajauto.roadsense.sync

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Choreographer
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
 * Eliminates USB transport jitter and VSync beat-frequency sawtooths (e.g. 22 Hz radar on a 60 Hz display)
 * by buffering incoming decoded RadarFrames in a bounded 1-frame jitter cushion and emitting on Android's
 * Choreographer VSync (`frameTimeNanos`) with adaptive target interval selection (`20 Hz` / `30 Hz`).
 *
 * Pitfall Mitigations:
 * 1. 60/90/120 Hz displays: Gates on `frameTimeNanos` with a 4 ms VSync snap tolerance instead of counting ticks.
 * 2. USB 60 ms bulk stalls: Maintains a bounded 1-frame cushion (`bufferDelayMs`) + `processSingleTick`.
 * 3. Variable radar rates (10/20/22/30+ Hz): Tracks `emaArrivalNs` with 20-frame hysteresis (`50 ms` vs `33.3 ms`).
 * 4. JVM unit tests: Falls back to `effectiveScope` coroutine pacing when `Looper.getMainLooper()` is unavailable.
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
        private const val VSYNC_SNAP_TOLERANCE_NS = 4_000_000L
        private const val INTERVAL_20HZ_NS = 50_000_000L
        private const val INTERVAL_30HZ_NS = 33_333_333L
        private const val HYSTERESIS_HIGH_RATE_NS = 46_500_000L // ~21.5 Hz (accommodates 22+ Hz DSP without queue bloat)
        private const val HYSTERESIS_LOW_RATE_NS = 48_000_000L  // ~20.8 Hz (1.5 ms hysteresis deadband back to 20 Hz)
        private const val HYSTERESIS_FRAMES = 20
    }

    private val hasAndroidMainLooper: Boolean = if (scope != null) {
        false
    } else {
        try {
            Looper.getMainLooper() != null
        } catch (_: Throwable) {
            false
        }
    }

    private val mainHandler: Handler? = if (hasAndroidMainLooper) {
        Handler(Looper.getMainLooper())
    } else {
        null
    }

    private val ownedExecutor: ExecutorService? = if (scope == null && !hasAndroidMainLooper) {
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
    @Volatile
    private var emaArrivalNs = frameIntervalMs * 1_000_000L
    @Volatile
    private var adaptiveTargetIntervalNs = frameIntervalMs * 1_000_000L
    @Volatile
    private var rateSwitchStreak = 0
    @Volatile
    private var firstEnqueueWallMs = 0L
    @Volatile
    private var lastChoreographerEmitNs = 0L
    private var pacingJob: Job? = null

    private val vsyncCallback: Choreographer.FrameCallback? = if (hasAndroidMainLooper) {
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!isRunning.get()) return

                val nowWall = System.currentTimeMillis()
                if (queue.isEmpty() && (nowWall - lastEnqueuedMs > IDLE_TIMEOUT_MS)) {
                    isRunning.set(false)
                    sustainedBacklogTicks = 0
                    singleFrameLagTicks = 0
                    return
                }

                // Wait initial cushion delay on first burst
                if (bufferDelayMs > 0L && (nowWall - firstEnqueueWallMs) < bufferDelayMs) {
                    Choreographer.getInstance().postFrameCallback(this)
                    return
                }

                val targetNs = adaptiveTargetIntervalNs
                if (lastChoreographerEmitNs == 0L || (frameTimeNanos - lastChoreographerEmitNs) >= (targetNs - VSYNC_SNAP_TOLERANCE_NS)) {
                    val emitted = processSingleTick(frameTimeNanos)
                    if (emitted != null) {
                        lastChoreographerEmitNs = frameTimeNanos
                    }
                }

                if (isRunning.get()) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
    } else {
        null
    }

    /**
     * Enqueue a newly decoded radar frame from the high-priority worker thread.
     */
    fun enqueueFrame(frame: RadarFrame) {
        val nowWall = System.currentTimeMillis()
        val nowNs = System.nanoTime()
        val deltaArrNs = if (lastEnqueueNs > 0L) nowNs - lastEnqueueNs else 0L
        val deltaArrMs = deltaArrNs / 1_000_000.0
        lastEnqueuedMs = nowWall
        lastEnqueueNs = nowNs

        // Adaptive rate detection with hysteresis (only when using default 50ms pacing)
        if (hasAndroidMainLooper && frameIntervalMs == 50L) {
            updateAdaptiveRate(deltaArrNs)
        }

        queue.offer(frame)
        val seq = frame.header.frameNumber
        if (DebugToggles.isDisplayPacerVerboseActive) {
            val qSize = queue.size
            AppLogger.d("DisplayPacer", "[ENQUEUE] seq=#$seq | deltaArrMs=${"%.2f".format(deltaArrMs)} | qDepth=$qSize")
        }

        // Ensure pacing loop / Choreographer VSync callback is actively running
        if (isRunning.compareAndSet(false, true)) {
            firstEnqueueWallMs = nowWall
            lastChoreographerEmitNs = 0L
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
        val cb = vsyncCallback
        if (cb != null && mainHandler != null) {
            mainHandler.post { Choreographer.getInstance().removeFrameCallback(cb) }
        }
        queue.clear()
        _latestFrame.value = null
        lastEnqueueNs = 0L
        lastEmitNs = 0L
        lastChoreographerEmitNs = 0L
        totalDroppedFrames = 0L
        sustainedBacklogTicks = 0
        singleFrameLagTicks = 0
        emaArrivalNs = frameIntervalMs * 1_000_000L
        adaptiveTargetIntervalNs = frameIntervalMs * 1_000_000L
        rateSwitchStreak = 0
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

    fun getTargetIntervalNs(): Long = adaptiveTargetIntervalNs

    internal fun updateAdaptiveRate(deltaArrNs: Long) {
        if (deltaArrNs !in 10_000_000L..200_000_000L) return
        emaArrivalNs = ((emaArrivalNs * 0.85) + (deltaArrNs * 0.15)).toLong()
        if (adaptiveTargetIntervalNs == INTERVAL_20HZ_NS && emaArrivalNs <= HYSTERESIS_HIGH_RATE_NS) {
            if (++rateSwitchStreak >= HYSTERESIS_FRAMES) {
                adaptiveTargetIntervalNs = INTERVAL_30HZ_NS
                rateSwitchStreak = 0
                if (DebugToggles.isDisplayPacerVerboseActive) {
                    AppLogger.i("DisplayPacer", "[VSYNC_RATE_ADAPT] Switched VSync target -> 30.0 Hz (33.3 ms), emaArrMs=${"%.2f".format(emaArrivalNs / 1e6)}")
                }
            }
        } else if (adaptiveTargetIntervalNs == INTERVAL_30HZ_NS && emaArrivalNs >= HYSTERESIS_LOW_RATE_NS) {
            if (++rateSwitchStreak >= HYSTERESIS_FRAMES) {
                adaptiveTargetIntervalNs = INTERVAL_20HZ_NS
                rateSwitchStreak = 0
                if (DebugToggles.isDisplayPacerVerboseActive) {
                    AppLogger.i("DisplayPacer", "[VSYNC_RATE_ADAPT] Switched VSync target -> 20.0 Hz (50.0 ms), emaArrMs=${"%.2f".format(emaArrivalNs / 1e6)}")
                }
            }
        } else {
            rateSwitchStreak = 0
        }
    }

    /**
     * Starts VSync-locked Choreographer pacing on Android MainLooper, or coroutine fallback under JVM tests.
     */
    private fun startPacingLoop() {
        val cb = vsyncCallback
        if (cb != null && mainHandler != null) {
            mainHandler.post {
                if (isRunning.get()) {
                    Choreographer.getInstance().removeFrameCallback(cb)
                    Choreographer.getInstance().postFrameCallback(cb)
                }
            }
            return
        }

        pacingJob?.cancel()
        pacingJob = effectiveScope.launch {
            if (bufferDelayMs > 0L) {
                delay(bufferDelayMs)
            }

            var nextTargetNs = System.nanoTime()

            while (isActive && isRunning.get()) {
                val nowWall = System.currentTimeMillis()
                if (queue.isEmpty() && (nowWall - lastEnqueuedMs > IDLE_TIMEOUT_MS)) {
                    isRunning.set(false)
                    sustainedBacklogTicks = 0
                    singleFrameLagTicks = 0
                    break
                }

                processSingleTick(nextTargetNs)

                val intervalNs = frameIntervalMs * 1_000_000L
                val skewAdjustNs = if (singleFrameLagTicks >= SUSTAINED_BLOAT_TICKS) CLOCK_SKEW_CATCHUP_NS else 0L
                nextTargetNs += (intervalNs - skewAdjustNs)
                val currentNs = System.nanoTime()
                val sleepNs = nextTargetNs - currentNs

                if (sleepNs > 1_000_000L) {
                    delay(sleepNs / 1_000_000L)
                } else if (sleepNs < -intervalNs) {
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
                    } | targetMs=${adaptiveTargetIntervalNs / 1_000_000} | qDepth=$remaining | totalDrops=$totalDroppedFrames"
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
