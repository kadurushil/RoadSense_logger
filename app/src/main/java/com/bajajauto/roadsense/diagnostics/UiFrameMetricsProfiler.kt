package com.bajajauto.roadsense.diagnostics

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.logging.AppLogger
import java.util.Arrays
import java.util.Locale

/**
 * Lock-free primitive accumulator for 5-second UI frame & phase percentile rollups.
 * Reuses a pre-allocated scratch array so periodic summaries allocate no heap arrays.
 */
internal class FrameWindowStats(private val capacity: Int = 1024) {

    private val totalDurationsMs = FloatArray(capacity)
    private val layoutDurationsMs = FloatArray(capacity)
    private val drawDurationsMs = FloatArray(capacity)
    private val syncDurationsMs = FloatArray(capacity)
    private val gpuDurationsMs = FloatArray(capacity)
    private val unkDurationsMs = FloatArray(capacity)
    private val swapDurationsMs = FloatArray(capacity)
    private val sortScratch = FloatArray(capacity)

    var frameCount: Int = 0
        private set
    var jankCount: Int = 0
        private set

    fun record(
        totalMs: Float,
        layoutMs: Float,
        drawMs: Float,
        syncMs: Float,
        gpuMs: Float,
        unkMs: Float = 0f,
        swapMs: Float = 0f,
        jankThresholdMs: Double = 16.67
    ) {
        if (frameCount < capacity) {
            totalDurationsMs[frameCount] = totalMs
            layoutDurationsMs[frameCount] = layoutMs
            drawDurationsMs[frameCount] = drawMs
            syncDurationsMs[frameCount] = syncMs
            gpuDurationsMs[frameCount] = gpuMs
            unkDurationsMs[frameCount] = unkMs
            swapDurationsMs[frameCount] = swapMs
        }
        frameCount++
        if (totalMs > jankThresholdMs) {
            jankCount++
        }
    }

    fun buildWindowSummaryAndReset(): String? {
        if (frameCount <= 0) return null
        val sampleLen = frameCount.coerceAtMost(capacity)

        val p50Idx = (sampleLen * 0.50).toInt().coerceIn(0, sampleLen - 1)
        val p95Idx = (sampleLen * 0.95).toInt().coerceIn(0, sampleLen - 1)

        System.arraycopy(totalDurationsMs, 0, sortScratch, 0, sampleLen)
        Arrays.sort(sortScratch, 0, sampleLen)
        val totalP50 = sortScratch[p50Idx]
        val totalP95 = sortScratch[p95Idx]
        val totalMax = sortScratch[sampleLen - 1]

        val drawP50 = percentile(drawDurationsMs, sampleLen, p50Idx)
        val drawP95 = percentile(drawDurationsMs, sampleLen, p95Idx)
        val layoutP95 = percentile(layoutDurationsMs, sampleLen, p95Idx)
        val syncP95 = percentile(syncDurationsMs, sampleLen, p95Idx)
        val gpuP95 = percentile(gpuDurationsMs, sampleLen, p95Idx)
        val unkP95 = percentile(unkDurationsMs, sampleLen, p95Idx)
        val swapP95 = percentile(swapDurationsMs, sampleLen, p95Idx)

        val jankPct = (jankCount * 100.0) / frameCount
        val summary = String.format(
            Locale.US,
            "[WINDOW_5S] frames=%d | jank=%d (%.1f%%) | p50=%.2fms | p95=%.2fms | max=%.2fms | drawP50=%.2fms | drawP95=%.2fms | layoutP95=%.2fms | syncP95=%.2fms | gpuP95=%.2fms | unkP95=%.2fms | swapP95=%.2fms",
            frameCount, jankCount, jankPct, totalP50, totalP95, totalMax,
            drawP50, drawP95, layoutP95, syncP95, gpuP95, unkP95, swapP95
        )

        frameCount = 0
        jankCount = 0
        return summary
    }

    private fun percentile(source: FloatArray, len: Int, index: Int): Float {
        System.arraycopy(source, 0, sortScratch, 0, len)
        Arrays.sort(sortScratch, 0, len)
        return sortScratch[index]
    }
}

/**
 * Hardware-backed UI FrameTiming Profiler (`Window.OnFrameMetricsAvailableListener`):
 * Runs on a dedicated low-overhead background HandlerThread so it adds 0 µs to the UI Main Thread.
 * Reports per-phase durations (Input, Animation, Measure/Layout, Draw, Sync, GPU Issue, Total)
 * for janky frames and emits a 5-second P50/P95/Max rolling summary including draw/layout/sync/gpu breakdown.
 */
class UiFrameMetricsProfiler {

    companion object {
        private const val TAG = "UiPerf"
        private const val JANK_THRESHOLD_MS = 16.67
        private const val SUMMARY_INTERVAL_MS = 5000L
    }

    private var handlerThread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val windowStats = FrameWindowStats()
    private var windowStartMs = 0L

    fun attach(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (listener != null) return

        val thread = HandlerThread("UiFrameMetricsProfiler", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
        }
        handlerThread = thread
        val handler = Handler(thread.looper)
        windowStartMs = SystemClock.elapsedRealtime()

        val metricsListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, dropCountSinceLastInvocation ->
            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalNs <= 0L) return@OnFrameMetricsAvailableListener

            val totalMs = totalNs / 1_000_000.0
            val inputMs = frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val animMs = frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val layoutMs = frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val drawMs = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val syncMs = frameMetrics.getMetric(FrameMetrics.SYNC_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val gpuIssueMs = frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val unkDelayMs = frameMetrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION).coerceAtLeast(0L) / 1_000_000.0
            val swapMs = frameMetrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION).coerceAtLeast(0L) / 1_000_000.0

            windowStats.record(
                totalMs = totalMs.toFloat(),
                layoutMs = layoutMs.toFloat(),
                drawMs = drawMs.toFloat(),
                syncMs = syncMs.toFloat(),
                gpuMs = gpuIssueMs.toFloat(),
                unkMs = unkDelayMs.toFloat(),
                swapMs = swapMs.toFloat(),
                jankThresholdMs = JANK_THRESHOLD_MS
            )

            if (totalMs > 24.0) {
                val msg = String.format(
                    Locale.US,
                    "[JANK_FRAME] total=%.2fms | anim=%.2fms | layout=%.2fms | draw=%.2fms | sync=%.2fms | gpu=%.2fms | unk=%.2fms | swap=%.2fms | input=%.2fms | drops=%d",
                    totalMs, animMs, layoutMs, drawMs, syncMs, gpuIssueMs, unkDelayMs, swapMs, inputMs, dropCountSinceLastInvocation
                )
                Log.w(TAG, msg)
                if (DebugToggles.isUiPerfJankToFileActive) {
                    AppLogger.w(TAG, msg)
                }
            }

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - windowStartMs >= SUMMARY_INTERVAL_MS && windowStats.frameCount > 0) {
                val summary = windowStats.buildWindowSummaryAndReset()
                if (summary != null) {
                    Log.i(TAG, summary)
                    AppLogger.d(TAG, summary)
                }
                windowStartMs = nowMs
            }
        }

        listener = metricsListener
        window.addOnFrameMetricsAvailableListener(metricsListener, handler)
    }

    fun detach(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        listener?.let {
            try {
                window.removeOnFrameMetricsAvailableListener(it)
            } catch (_: Exception) {}
        }
        listener = null
        handlerThread?.quitSafely()
        handlerThread = null
    }
}
