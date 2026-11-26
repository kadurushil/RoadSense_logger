package com.bajajauto.roadsense.diagnostics

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free 5-second Jetpack Compose recomposition audit counter.
 * Active only when [DebugToggles.isUiPerfProfilingActive] is enabled so production drives incur ~0 overhead.
 */
object RecompAudit {
    private const val TAG = "RecompAudit"
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun record(tag: String) {
        val counter = counts[tag] ?: counts.computeIfAbsent(tag) { AtomicInteger(0) }
        counter.incrementAndGet()
    }

    fun recordIfActive(tag: String) {
        if (!DebugToggles.isUiPerfProfilingActive) return
        record(tag)
    }

    fun reset() {
        counts.values.forEach { it.set(0) }
    }

    fun buildSummaryAndReset(): String? {
        if (counts.isEmpty()) return null
        val sb = StringBuilder("[RECOMP_5S] ")
        var first = true
        for (entry in counts.entries.sortedBy { it.key }) {
            if (!first) sb.append(" | ")
            first = false
            sb.append(entry.key).append('=').append(entry.value.getAndSet(0))
        }
        return sb.toString()
    }

    internal fun emitSummaryIfActive() {
        if (!DebugToggles.isUiPerfProfilingActive) {
            reset()
            return
        }
        val summary = buildSummaryAndReset() ?: "[RECOMP_5S] NO_RECOMPOSITIONS=0"
        Log.i(TAG, summary)
        AppLogger.d(TAG, summary)
    }
}

/**
 * Non-restartable tracking hook so Compose Strong Skipping cannot bypass recording recompositions
 * of the enclosing Composable scope. Fast-paths to ~1 ns when profiling is OFF.
 */
@Composable
@NonRestartableComposable
fun TrackRecomposition(tag: String) {
    SideEffect {
        RecompAudit.recordIfActive(tag)
    }
}
