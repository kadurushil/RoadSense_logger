package com.bajajauto.roadsense.diagnostics

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.bajajauto.roadsense.logging.AppLogger
import java.util.Locale

/**
 * Hardware-backed UI FrameTiming Profiler (`Window.OnFrameMetricsAvailableListener`):
 * Runs on a dedicated low-overhead background HandlerThread so it adds 0 µs to the UI Main Thread.
 * Reports per-phase durations (Input, Animation, Measure/Layout, Draw, Sync, GPU Issue, Total)
 * for every janky frame (> 16.67 ms) and emits a 5-second P50/P95/Max rolling summary.
 */
class UiFrameMetricsProfiler {

    companion object {
        private const val TAG = "UiPerf"
        private const val JANK_THRESHOLD_MS = 16.67
        private const val SUMMARY_INTERVAL_MS = 5000L
    }

    private var handlerThread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val frameDurationsMs = FloatArray(1024)
    private var frameCount = 0
    private var jankCount = 0
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

            if (frameCount < frameDurationsMs.size) {
                frameDurationsMs[frameCount] = totalMs.toFloat()
            }
            frameCount++
            if (totalMs > JANK_THRESHOLD_MS) {
                jankCount++
                if (totalMs > 24.0) {
                    val msg = String.format(
                        Locale.US,
                        "[JANK_FRAME] total=%.2fms | anim=%.2fms | layout=%.2fms | draw=%.2fms | sync=%.2fms | gpu=%.2fms | input=%.2fms | drops=%d",
                        totalMs, animMs, layoutMs, drawMs, syncMs, gpuIssueMs, inputMs, dropCountSinceLastInvocation
                    )
                    Log.w(TAG, msg)
                }
            }

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - windowStartMs >= SUMMARY_INTERVAL_MS && frameCount > 0) {
                val sampleLen = frameCount.coerceAtMost(frameDurationsMs.size)
                val sorted = frameDurationsMs.copyOfRange(0, sampleLen).apply { sort() }
                val p50 = sorted[(sampleLen * 0.50).toInt().coerceIn(0, sampleLen - 1)]
                val p95 = sorted[(sampleLen * 0.95).toInt().coerceIn(0, sampleLen - 1)]
                val max = sorted[sampleLen - 1]
                val jankPct = (jankCount * 100.0) / frameCount

                val summary = String.format(
                    Locale.US,
                    "[WINDOW_5S] frames=%d | jank=%d (%.1f%%) | p50=%.2fms | p95=%.2fms | max=%.2fms",
                    frameCount, jankCount, jankPct, p50, p95, max
                )
                Log.i(TAG, summary)
                AppLogger.d(TAG, summary)

                frameCount = 0
                jankCount = 0
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

