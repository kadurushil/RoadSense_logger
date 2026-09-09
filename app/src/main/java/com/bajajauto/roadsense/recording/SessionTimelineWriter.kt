package com.bajajauto.roadsense.recording

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Master cross-sensor synchronization index writer.
 *
 * Writes a lightweight, high-performance timeline index (`session_timeline.csv`)
 * at the root of each recording session.
 *
 * Each row associates:
 * 1. elapsed_realtime_ns: Android monotonic hardware nanoseconds (SystemClock.elapsedRealtimeNanos())
 * 2. sensor: Sensor identifier ("RADAR", "GNSS", "CAMERA", "IMU")
 * 3. event: Event type ("FRAME", "FIX", "DROPPED", "SYNC")
 * 4. index: Monotonic sequence index for that sensor stream
 * 5. rel_path: Relative path to the underlying sensor data payload file
 * 6. summary: Lightweight metadata snippet (e.g., "points=42,tracks=3" or "lat=18.52,lon=73.85,acc=2.1m")
 */
class SessionTimelineWriter {

    companion object {
        private const val TAG = "SessionTimelineWriter"
        private const val BUFFER_SIZE = 32 * 1024 // 32 KB buffer
        const val TIMELINE_FILE_NAME = "session_timeline.csv"
        const val CSV_HEADER = "elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary\n"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TimelineWriter-Worker").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    @Volatile
    private var isWriterActive = false

    private var bufferedWriter: BufferedWriter? = null
    private var timelineFile: File? = null
    private var totalEntries: Long = 0L

    /**
     * Starts writing the session timeline in the session directory.
     */
    fun start(sessionDir: File) {
        if (isWriterActive) {
            Log.w(TAG, "Timeline writer already active")
            return
        }

        try {
            val file = File(sessionDir, TIMELINE_FILE_NAME)
            val writer = BufferedWriter(FileWriter(file, false), BUFFER_SIZE)
            writer.write(CSV_HEADER)
            writer.flush()

            timelineFile = file
            bufferedWriter = writer
            totalEntries = 0L
            isWriterActive = true
            Log.i(TAG, "Session timeline opened at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create timeline file", e)
        }
    }

    /**
     * Records a sensor event in the timeline.
     * Non-blocking: enqueued onto background single-thread executor.
     */
    fun recordEvent(
        elapsedRealtimeNs: Long,
        sensor: String,
        event: String,
        sequenceId: Long,
        relativePath: String,
        summary: String
    ) {
        if (!isWriterActive) return

        // Clean summary string from comma/newline to preserve strict CSV column boundaries
        val sanitizedSummary = summary.replace(",", ";").replace("\n", " ").trim()

        executor.execute {
            if (!isWriterActive) return@execute
            try {
                bufferedWriter?.let { writer ->
                    writer.write(
                        String.format(
                            Locale.US,
                            "%d,%s,%s,%d,%s,%s\n",
                            elapsedRealtimeNs,
                            sensor,
                            event,
                            sequenceId,
                            relativePath,
                            sanitizedSummary
                        )
                    )
                    totalEntries++
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing timeline event", e)
            }
        }
    }

    /**
     * Flushes and closes the timeline file.
     */
    fun stop(): Long {
        if (!isWriterActive) return 0L

        isWriterActive = false
        val count = totalEntries

        executor.execute {
            try {
                bufferedWriter?.flush()
                bufferedWriter?.close()
                Log.i(TAG, "Session timeline closed. Total synchronized events: $count")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing timeline writer", e)
            } finally {
                bufferedWriter = null
                timelineFile = null
            }
        }

        return count
    }

    fun release() {
        if (isWriterActive) {
            stop()
        }
        executor.shutdown()
    }
}
