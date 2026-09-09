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
    private var activeWriter: BufferedWriter? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
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

        // 3. Active Session File Writer (if recording)
        synchronized(lock) {
            activeWriter?.let { writer ->
                try {
                    val formatted = formatEntry(entry)
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
