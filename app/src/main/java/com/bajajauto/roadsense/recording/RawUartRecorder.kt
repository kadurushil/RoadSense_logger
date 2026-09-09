package com.bajajauto.roadsense.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(
        val file: File,
        val bytesWritten: Long,
        val startTimeMs: Long
    ) : RecordingState()
    data class Finished(
        val file: File,
        val totalBytes: Long,
        val durationMs: Long
    ) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * Dedicated high-throughput raw binary recorder that writes incoming UART bytes
 * directly to a binary file (.bin) on a background thread without dropping packets.
 */
class RawUartRecorder(private val context: Context) {

    companion object {
        private const val TAG = "RawUartRecorder"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer for high-speed UART streaming
        private const val UI_UPDATE_INTERVAL_MS = 250L // Throttle UI state updates to 4 Hz
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawUartRecorder-Worker").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    @Volatile
    private var isRecordingActive = false
    private var fos: FileOutputStream? = null
    private var bos: BufferedOutputStream? = null
    private var currentFile: File? = null
    private var bytesWritten: Long = 0L
    private var startTimeMs: Long = 0L
    private var lastUiUpdateMs: Long = 0L

    /**
     * Starts recording to a timestamped file in the app-specific external files dir:
     * e.g., /sdcard/Android/data/com.bajajauto.roadsense/files/raw_uart_YYYYMMDD_HHMMSS.bin
     */
    fun startRecording(): File? {
        if (isRecordingActive) {
            Log.w(TAG, "Recording is already active")
            return currentFile
        }

        try {
            val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(outputDir, "raw_uart_$timestamp.bin")
            val fileOutputStream = FileOutputStream(file)
            val bufferedOutputStream = BufferedOutputStream(fileOutputStream, BUFFER_SIZE)

            fos = fileOutputStream
            bos = bufferedOutputStream
            currentFile = file
            bytesWritten = 0L
            startTimeMs = SystemClock.elapsedRealtime()
            lastUiUpdateMs = startTimeMs
            isRecordingActive = true

            _recordingState.value = RecordingState.Recording(
                file = file,
                bytesWritten = 0L,
                startTimeMs = startTimeMs
            )

            Log.i(TAG, "Started raw UART binary dump to: ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _recordingState.value = RecordingState.Error("Failed to start recording: ${e.message}")
            return null
        }
    }

    /**
     * Enqueues raw bytes for immediate writing to the file stream.
     * Safe to call from any thread, including serial read callbacks.
     */
    fun write(data: ByteArray) {
        if (!isRecordingActive || data.isEmpty()) return

        val chunk = data.clone()
        executor.execute {
            if (!isRecordingActive) return@execute
            try {
                bos?.write(chunk)
                bytesWritten += chunk.size

                val now = SystemClock.elapsedRealtime()
                if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
                    lastUiUpdateMs = now
                    currentFile?.let { file ->
                        _recordingState.value = RecordingState.Recording(
                            file = file,
                            bytesWritten = bytesWritten,
                            startTimeMs = startTimeMs
                        )
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing raw UART chunk", e)
                _recordingState.value = RecordingState.Error("Write error: ${e.message}")
            }
        }
    }

    /**
     * Flushes, syncs file descriptor to flash storage, and closes the file stream.
     */
    fun stopRecording(): File? {
        if (!isRecordingActive) {
            Log.w(TAG, "Recording is not active")
            return null
        }

        isRecordingActive = false
        val file = currentFile
        val durationMs = SystemClock.elapsedRealtime() - startTimeMs
        val totalBytes = bytesWritten

        executor.execute {
            try {
                bos?.flush()
                fos?.fd?.sync()
                bos?.close()
                fos?.close()
                Log.i(TAG, "Finished raw UART dump: ${file?.name}, total bytes: $totalBytes, duration: ${durationMs}ms")
                if (file != null) {
                    _recordingState.value = RecordingState.Finished(
                        file = file,
                        totalBytes = totalBytes,
                        durationMs = durationMs
                    )
                } else {
                    _recordingState.value = RecordingState.Idle
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error closing raw recording file", e)
                _recordingState.value = RecordingState.Error("Error closing file: ${e.message}")
            } finally {
                bos = null
                fos = null
                currentFile = null
            }
        }
        return file
    }

    fun release() {
        if (isRecordingActive) {
            stopRecording()
        }
        executor.shutdown()
    }
}
