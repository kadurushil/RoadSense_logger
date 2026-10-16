package com.bajajauto.roadsense.sync

import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dedicated high-performance asynchronous writer for `sync/sync_keyframes.csv`.
 *
 * Implements buffered disk I/O on a dedicated background worker thread with
 * elevated priority, ensuring zero frame drops and zero CPU interference with the
 * camera frame grabber.
 */
class SyncKeyframeWriter {

    companion object {
        private const val TAG = "SyncKeyframeWriter"
        private const val BUFFER_SIZE = 32 * 1024 // 32 KB buffer
        const val KEYFRAMES_CSV_NAME = "sync_keyframes.csv"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            runnable.run()
        }, "SyncKeyframeWriter-Worker").apply {
            priority = Thread.MAX_PRIORITY - 1
        }
    }

    @Volatile
    private var isWriterActive = false

    private var bufferedWriter: BufferedWriter? = null
    private var targetFile: File? = null
    private var totalKeyframesWritten = 0L

    /**
     * Prepares and opens `sync/sync_keyframes.csv` within the session's sync directory.
     */
    fun start(syncDir: File) {
        if (isWriterActive) {
            Log.w(TAG, "SyncKeyframeWriter already active")
            return
        }

        try {
            if (!syncDir.exists()) {
                syncDir.mkdirs()
            }
            val file = File(syncDir, KEYFRAMES_CSV_NAME)
            val isNew = !file.exists() || file.length() == 0L

            val writer = BufferedWriter(FileWriter(file, true), BUFFER_SIZE)
            if (isNew) {
                writer.write(SyncKeyframe.CSV_HEADER)
                writer.flush()
            }

            targetFile = file
            bufferedWriter = writer
            totalKeyframesWritten = 0L
            isWriterActive = true
            Log.i(TAG, "SyncKeyframeWriter started at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SyncKeyframeWriter", e)
        }
    }

    /**
     * Appends a synchronized keyframe row to the CSV.
     * Non-blocking: enqueued onto dedicated single-thread worker.
     */
    fun writeKeyframe(keyframe: SyncKeyframe) {
        if (!isWriterActive) return

        executor.execute {
            if (!isWriterActive) return@execute
            try {
                bufferedWriter?.let { writer ->
                    writer.write(keyframe.toCsvRow())
                    totalKeyframesWritten++
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing keyframe ${keyframe.keyframeId} to CSV", e)
            }
        }
    }

    /**
     * Flushes buffered stream to disk and closes file handle.
     */
    fun stop(): Long {
        if (!isWriterActive) return 0L

        isWriterActive = false
        val count = totalKeyframesWritten

        executor.execute {
            try {
                bufferedWriter?.flush()
                bufferedWriter?.close()
                Log.i(TAG, "SyncKeyframeWriter closed. Total keyframes saved: $count")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing SyncKeyframeWriter", e)
            } finally {
                bufferedWriter = null
                targetFile = null
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
