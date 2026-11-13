package com.bajajauto.roadsense.logging

import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic Flight Recorder / Black Box Logger for RoadSense.
 *
 * Features:
 * - Keeps an in-memory ring buffer of the latest pre-session boot & UI breadcrumbs.
 * - When a recording session starts, immediately flushes pre-session history and streams
 *   all subsequent diagnostic events to `session_debug.log` in the session directory.
 * - Dual outputs to standard Android Logcat and session file.
 * - Non-blocking async single writer thread (`RS-LogWriter`) so real-time callers
 *   (`main`, `RadarProcessingWorker`, `DisplayPacer`, `UiFrameMetricsProfiler`) never
 *   block on flash I/O locks or synchronous `writer.flush()`.
 */
object AppLogger {

    private const val MAX_RING_BUFFER_SIZE = 500
    private const val MAX_APP_RUN_RETENTION_DAYS = 5
    private const val MAX_APP_RUN_DIRS = 10
    private const val MAX_QUEUE_CAPACITY = 4096
    private const val BATCH_DRAIN_LIMIT = 64
    private const val FLUSH_INTERVAL_MS = 250L
    private const val CONTROL_TIMEOUT_MS = 5000L

    data class LogEntry(
        val timestampMs: Long,
        val level: String,
        val tag: String,
        val message: String,
        val threadName: String
    )

    private class ControlOp(
        val action: () -> Unit,
        val latch: CountDownLatch = CountDownLatch(1)
    )

    private val WAKEUP_SENTINEL = LogEntry(
        timestampMs = -1L,
        level = "",
        tag = "",
        message = "",
        threadName = ""
    )

    private val ringBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val ringBufferCount = AtomicInteger(0)
    private val logQueue = LinkedBlockingQueue<LogEntry>(MAX_QUEUE_CAPACITY)
    private val controlQueue = ConcurrentLinkedQueue<ControlOp>()
    private val droppedLogCount = AtomicLong(0L)
    private var lastReportedDroppedCount = 0L

    private val writerStarted = AtomicBoolean(false)
    private val writerThreadInitLock = Any()

    // Accessed strictly from the single RS-LogWriter thread
    private var appLogWriter: BufferedWriter? = null
    private var activeWriter: BufferedWriter? = null

    @Volatile
    private var currentAppRunDir: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val runStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun ensureWriterThreadStarted() {
        if (writerStarted.get()) return
        synchronized(writerThreadInitLock) {
            if (writerStarted.get()) return
            val thread = Thread({ writerLoop() }, "RS-LogWriter").apply {
                isDaemon = true
            }
            writerStarted.set(true)
            thread.start()
        }
    }

    private fun writerLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {
            // Safe fallback under JVM unit tests
        }

        val batch = ArrayList<LogEntry>(BATCH_DRAIN_LIMIT)
        var lastFlushMs = System.currentTimeMillis()
        var hasUnflushedWrites = false

        while (true) {
            try {
                if (controlQueue.isNotEmpty()) {
                    if (processPendingControls()) {
                        hasUnflushedWrites = false
                        lastFlushMs = System.currentTimeMillis()
                    }
                }

                val first = logQueue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                var batchHasPriority = false

                if (first != null) {
                    if (first !== WAKEUP_SENTINEL) {
                        batch.add(first)
                    }
                    logQueue.drainTo(batch, BATCH_DRAIN_LIMIT - batch.size)

                    for (i in batch.indices) {
                        val entry = batch[i]
                        if (entry === WAKEUP_SENTINEL) continue
                        if (entry.level == "WARN" || entry.level == "ERROR") {
                            batchHasPriority = true
                        }
                        writeFormattedEntry(formatEntry(entry))
                        hasUnflushedWrites = true
                    }
                    batch.clear()
                }

                if (controlQueue.isNotEmpty()) {
                    if (processPendingControls()) {
                        hasUnflushedWrites = false
                        lastFlushMs = System.currentTimeMillis()
                    }
                }

                val nowMs = System.currentTimeMillis()
                if (hasUnflushedWrites && (batchHasPriority || first == null || (nowMs - lastFlushMs) >= FLUSH_INTERVAL_MS)) {
                    reportDropsIfAny()
                    flushAllWriters()
                    hasUnflushedWrites = false
                    lastFlushMs = nowMs
                }
            } catch (_: InterruptedException) {
                // Keep daemon alive
            } catch (t: Throwable) {
                try {
                    Log.e("AppLogger", "Unexpected error in RS-LogWriter loop", t)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun drainLogQueueToWriters(): Boolean {
        val temp = ArrayList<LogEntry>(BATCH_DRAIN_LIMIT)
        var wroteAny = false
        while (true) {
            temp.clear()
            val drained = logQueue.drainTo(temp, BATCH_DRAIN_LIMIT)
            if (drained == 0) break
            for (i in temp.indices) {
                val entry = temp[i]
                if (entry === WAKEUP_SENTINEL) continue
                writeFormattedEntry(formatEntry(entry))
                wroteAny = true
            }
        }
        return wroteAny
    }

    private fun processPendingControls(): Boolean {
        var ranAny = false
        while (true) {
            val op = controlQueue.poll() ?: break
            ranAny = true
            drainLogQueueToWriters()
            reportDropsIfAny()
            flushAllWriters()
            try {
                op.action()
            } catch (t: Throwable) {
                try {
                    Log.e("AppLogger", "Control op failed on RS-LogWriter", t)
                } catch (_: Throwable) {}
            } finally {
                op.latch.countDown()
            }
        }
        return ranAny
    }

    private fun writeFormattedEntry(formatted: String) {
        appLogWriter?.let { writer ->
            try {
                writer.write(formatted)
                writer.newLine()
            } catch (e: Exception) {
                try {
                    Log.e("AppLogger", "Failed to write to app_system.log", e)
                } catch (_: Throwable) {}
            }
        }

        activeWriter?.let { writer ->
            try {
                writer.write(formatted)
                writer.newLine()
            } catch (e: Exception) {
                try {
                    Log.e("AppLogger", "Failed to write log entry to session file", e)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun reportDropsIfAny() {
        val currentDrops = droppedLogCount.get()
        if (currentDrops > lastReportedDroppedCount) {
            val delta = currentDrops - lastReportedDroppedCount
            lastReportedDroppedCount = currentDrops
            val warnEntry = LogEntry(
                timestampMs = System.currentTimeMillis(),
                level = "WARN",
                tag = "AppLogger",
                message = "[LOGGER_OVERFLOW] Dropped $delta async log entries (totalDropped=$currentDrops)",
                threadName = "RS-LogWriter"
            )
            writeFormattedEntry(formatEntry(warnEntry))
        }
    }

    private fun flushAllWriters() {
        try {
            appLogWriter?.flush()
        } catch (_: Exception) {}
        try {
            activeWriter?.flush()
        } catch (_: Exception) {}
    }

    private fun runOnWriterAndAwait(action: () -> Unit) {
        ensureWriterThreadStarted()
        val op = ControlOp(action)
        controlQueue.offer(op)
        logQueue.offer(WAKEUP_SENTINEL)
        try {
            op.latch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Initializes continuous app-wide flight recorder logging upon application start.
     * Creates: app_logs/app_run_YYYYMMDD_HHMMSS/app_system.log
     * Auto-prunes older run folders exceeding retention limits.
     */
    fun initAppLogging(context: android.content.Context) {
        runOnWriterAndAwait {
            if (appLogWriter != null) return@runOnWriterAndAwait

            try {
                val externalFiles = context.getExternalFilesDir(null) ?: context.filesDir
                val appLogsBaseDir = File(externalFiles, "app_logs").apply { mkdirs() }

                // 1. Auto-prune stale app runs
                pruneOldAppRuns(appLogsBaseDir)

                // 2. Create timestamped run directory
                val runStamp = runStampFormat.format(Date())
                val runDir = File(appLogsBaseDir, "app_run_$runStamp").apply { mkdirs() }
                currentAppRunDir = runDir

                val logFile = File(runDir, "app_system.log")
                val writer = BufferedWriter(FileWriter(logFile, true))

                // 3. Write diagnostic run header
                val nowStr = dateFormat.format(Date())
                writer.write("===========================================================================\n")
                writer.write("RoadSense Continuous Diagnostics Flight Recorder\n")
                writer.write("App Run: app_run_$runStamp\n")
                writer.write("Started At: $nowStr\n")
                writer.write("Package: ${context.packageName}\n")
                writer.write("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})\n")
                writer.write("Log File: ${logFile.absolutePath}\n")
                writer.write("===========================================================================\n\n")

                // 4. Flush any pre-init ring buffer entries
                if (ringBuffer.isNotEmpty()) {
                    writer.write("--- PRE-INIT LOG ENTRIES ---\n")
                    for (entry in ringBuffer) {
                        writer.write(formatEntry(entry))
                        writer.newLine()
                    }
                    writer.write("--- CONTINUOUS LOGGING ACTIVE ---\n\n")
                }
                writer.flush()

                appLogWriter = writer
                Log.i("AppLogger", "Continuous app-wide diagnostics logging initialized: ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to initialize continuous app logging", e)
            }
        }
    }

    /**
     * Closes the continuous app run log writer upon app shutdown.
     */
    fun closeAppLogging() {
        runOnWriterAndAwait {
            appLogWriter?.let { writer ->
                try {
                    writer.write("\n===========================================================================\n")
                    writer.write("RoadSense App Run Closed At: ${dateFormat.format(Date())}\n")
                    writer.write("===========================================================================\n")
                    writer.flush()
                    writer.close()
                } catch (e: Exception) {
                    Log.e("AppLogger", "Error closing app log writer", e)
                } finally {
                    appLogWriter = null
                }
            }
        }
    }

    /**
     * Returns the current app run directory, if initialized.
     */
    fun getCurrentAppRunDir(): File? = currentAppRunDir

    private fun pruneOldAppRuns(baseDir: File) {
        try {
            val runDirs = baseDir.listFiles { f -> f.isDirectory && f.name.startsWith("app_run_") }
                ?.sortedBy { it.lastModified() }
                ?: return

            val now = System.currentTimeMillis()
            val maxAgeMs = MAX_APP_RUN_RETENTION_DAYS * 24L * 60L * 60L * 1000L

            for (dir in runDirs) {
                val isExpired = (now - dir.lastModified()) > maxAgeMs
                val exceedsDirCount = (runDirs.size - runDirs.indexOf(dir)) > MAX_APP_RUN_DIRS

                if (isExpired || exceedsDirCount) {
                    Log.i("AppLogger", "Pruning old app run logs: ${dir.name}")
                    dir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.w("AppLogger", "Failed during app runs pruning: ${e.message}")
        }
    }

    fun v(tag: String, message: String) = log("VERBOSE", tag, message)
    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        log("ERROR", tag, fullMsg)
    }

    private fun log(level: String, tag: String, message: String) {
        // 1. Android Logcat
        when (level) {
            "VERBOSE" -> Log.v(tag, message)
            "DEBUG" -> Log.d(tag, message)
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
        }

        val nowMs = System.currentTimeMillis()
        val entry = LogEntry(
            timestampMs = nowMs,
            level = level,
            tag = tag,
            message = message,
            threadName = Thread.currentThread().name
        )

        // 2. Bounded In-Memory Ring Buffer
        ringBuffer.add(entry)
        if (ringBufferCount.incrementAndGet() > MAX_RING_BUFFER_SIZE) {
            if (ringBuffer.poll() != null) {
                ringBufferCount.decrementAndGet()
            }
        }

        // 3. Non-blocking enqueue to dedicated RS-LogWriter thread
        ensureWriterThreadStarted()
        if (!logQueue.offer(entry)) {
            droppedLogCount.incrementAndGet()
        }
    }

    /**
     * Attaches an active session directory and starts streaming logs to session_debug.log.
     * Dumps all historical pre-session breadcrumbs first.
     */
    fun attachSession(sessionDir: File) {
        runOnWriterAndAwait {
            try {
                val logFile = File(sessionDir, "session_debug.log")
                val writer = BufferedWriter(FileWriter(logFile, true))

                writer.write("===========================================================================\n")
                writer.write("RoadSense Session Diagnostic Flight Recorder Log\n")
                writer.write("Session Directory: ${sessionDir.name}\n")
                writer.write("Attached At: ${dateFormat.format(Date())}\n")
                writer.write("===========================================================================\n\n")
                writer.write("--- PRE-SESSION BREADCRUMBS DUMP (App boot to session start) ---\n")

                // Dump ring buffer
                for (entry in ringBuffer) {
                    writer.write(formatEntry(entry))
                    writer.newLine()
                }
                writer.write("--- ACTIVE SESSION DIAGNOSTIC STREAM STARTED ---\n\n")
                writer.flush()

                activeWriter = writer
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to attach session debug logger", e)
            }
        }
        i("AppLogger", "Attached session flight recorder: ${File(sessionDir, "session_debug.log").absolutePath}")
    }

    /**
     * Flushes and detaches the session log writer upon recording stop.
     */
    fun detachSession() {
        runOnWriterAndAwait {
            activeWriter?.let { writer ->
                try {
                    writer.write("\n--- ACTIVE SESSION RECORDING FINISHED AT ${dateFormat.format(Date())} ---\n")
                    writer.flush()
                    writer.close()
                } catch (e: Exception) {
                    Log.e("AppLogger", "Error closing session debug log writer", e)
                } finally {
                    activeWriter = null
                }
            }
        }
    }

    /**
     * Drains queued entries and flushes active writers synchronously (for tests and graceful flush).
     */
    internal fun flushAndSync() {
        runOnWriterAndAwait {
            flushAllWriters()
        }
    }

    /**
     * Attaches a custom session log file directly (intended for JVM unit tests).
     */
    internal fun attachSessionFileForTesting(logFile: File) {
        runOnWriterAndAwait {
            activeWriter?.let {
                try {
                    it.flush()
                    it.close()
                } catch (_: Exception) {}
            }
            activeWriter = BufferedWriter(FileWriter(logFile, false))
        }
    }

    /**
     * Resets queued entries and writers between JVM unit tests.
     */
    internal fun resetForTesting() {
        runOnWriterAndAwait {
            try {
                activeWriter?.close()
            } catch (_: Exception) {}
            activeWriter = null
            try {
                appLogWriter?.close()
            } catch (_: Exception) {}
            appLogWriter = null
            currentAppRunDir = null
            logQueue.clear()
            ringBuffer.clear()
            ringBufferCount.set(0)
            droppedLogCount.set(0L)
            lastReportedDroppedCount = 0L
        }
    }

    fun getDroppedLogCount(): Long = droppedLogCount.get()

    private fun formatEntry(entry: LogEntry): String {
        val dateStr = dateFormat.format(Date(entry.timestampMs))
        return "[$dateStr] [${entry.level.padEnd(5)}] [${entry.tag}] (${entry.threadName}) ${entry.message}"
    }
}
