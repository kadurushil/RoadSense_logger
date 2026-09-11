package com.bajajauto.roadsense.logging

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Diagnostic Flight Recorder / Black Box Logger for RoadSense.
 *
 * Features:
 * - Keeps an in-memory ring buffer of the latest pre-session boot & UI breadcrumbs.
 * - When a recording session starts, immediately flushes pre-session history and streams
 *   all subsequent diagnostic events to `session_debug.log` in the session directory.
 * - Dual outputs to standard Android Logcat and session file.
 */
object AppLogger {

    private const val MAX_RING_BUFFER_SIZE = 500
    private const val MAX_APP_RUN_RETENTION_DAYS = 5
    private const val MAX_APP_RUN_DIRS = 10

    data class LogEntry(
        val timestampMs: Long,
        val level: String,
        val tag: String,
        val message: String,
        val threadName: String
    )

    private val ringBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val lock = Any()

    @Volatile
    private var appLogWriter: BufferedWriter? = null

    @Volatile
    private var currentAppRunDir: File? = null

    @Volatile
    private var activeWriter: BufferedWriter? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val runStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /**
     * Initializes continuous app-wide flight recorder logging upon application start.
     * Creates: app_logs/app_run_YYYYMMDD_HHMMSS/app_system.log
     * Auto-prunes older run folders exceeding retention limits.
     */
    fun initAppLogging(context: android.content.Context) {
        synchronized(lock) {
            if (appLogWriter != null) return // Already initialized

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
        synchronized(lock) {
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

        // 2. In-Memory Ring Buffer
        ringBuffer.add(entry)
        while (ringBuffer.size > MAX_RING_BUFFER_SIZE) {
            ringBuffer.poll()
        }

        // 3. Write to Continuous App Log and/or Active Session Log
        synchronized(lock) {
            val formatted = formatEntry(entry)

            // Continuous App Log
            appLogWriter?.let { writer ->
                try {
                    writer.write(formatted)
                    writer.newLine()
                    writer.flush()
                } catch (e: Exception) {
                    Log.e("AppLogger", "Failed to write to app_system.log", e)
                }
            }

            // Active Recording Session Log
            activeWriter?.let { writer ->
                try {
                    writer.write(formatted)
                    writer.newLine()
                    writer.flush()
                } catch (e: Exception) {
                    Log.e("AppLogger", "Failed to write log entry to session file", e)
                }
            }
        }
    }

    /**
     * Attaches an active session directory and starts streaming logs to session_debug.log.
     * Dumps all historical pre-session breadcrumbs first.
     */
    fun attachSession(sessionDir: File) {
        synchronized(lock) {
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
                i("AppLogger", "Attached session flight recorder: ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to attach session debug logger", e)
            }
        }
    }

    /**
     * Flushes and detaches the session log writer upon recording stop.
     */
    fun detachSession() {
        synchronized(lock) {
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

    private fun formatEntry(entry: LogEntry): String {
        val dateStr = dateFormat.format(Date(entry.timestampMs))
        return "[$dateStr] [${entry.level.padEnd(5)}] [${entry.tag}] (${entry.threadName}) ${entry.message}"
    }
}
