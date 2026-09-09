package com.bajajauto.roadsense.recording

import android.util.Log
import com.bajajauto.roadsense.gnss.GnssFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Autonomous recorder for GNSS location fixes.
 * Writes high-precision coordinates, velocity, accuracy, and monotonic timestamps
 * to "gnss/gnss_fixes.csv" within the active session folder.
 * Operates completely decoupled from radar recording.
 */
class GnssSessionRecorder(
    private val sessionManager: SessionManager? = null
) {

    companion object {
        private const val TAG = "GnssSessionRecorder"
        private const val BUFFER_SIZE = 16 * 1024 // 16 KB buffer
        const val GNSS_DIR_NAME = "gnss"
        const val GNSS_CSV_NAME = "gnss_fixes.csv"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GnssSessionRecorder-Worker").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _fixesRecorded = MutableStateFlow(0L)
    val fixesRecorded: StateFlow<Long> = _fixesRecorded.asStateFlow()

    @Volatile
    private var isRecordingActive = false

    private var activeSession: SessionInfo? = null
    private var csvWriter: BufferedWriter? = null
    private var gnssFile: File? = null

    /**
     * Starts GNSS recording within the provided session folder.
     * Creates the `gnss/` subfolder if it does not already exist and writes the CSV header.
     */
    fun startRecording(sessionInfo: SessionInfo) {
        if (isRecordingActive) {
            Log.w(TAG, "GNSS recording already active in session: ${activeSession?.sessionId}")
            return
        }

        try {
            val gnssDir = File(sessionInfo.sessionDir, GNSS_DIR_NAME).apply { mkdirs() }
            val file = File(gnssDir, GNSS_CSV_NAME)
            val writer = BufferedWriter(FileWriter(file, true), BUFFER_SIZE)

            // Write CSV header if file is newly created or empty
            if (file.length() == 0L) {
                writer.write(GnssFix.CSV_HEADER)
                writer.flush()
            }

            activeSession = sessionInfo
            csvWriter = writer
            gnssFile = file
            _fixesRecorded.value = 0L
            isRecordingActive = true
            _isRecording.value = true

            Log.i(TAG, "Started GNSS recording to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GNSS recording", e)
        }
    }

    /**
     * Appends a GNSS fix to the CSV file.
     * Non-blocking: processed on a dedicated background I/O thread.
     */
    fun recordFix(fix: GnssFix) {
        if (!isRecordingActive) return

        executor.execute {
            if (!isRecordingActive) return@execute
            try {
                csvWriter?.let { writer ->
                    writer.write(fix.toCsvRow())
                    val count = _fixesRecorded.value + 1
                    _fixesRecorded.value = count
                    activeSession?.totalGnssFixes = count

                    sessionManager?.recordTimelineEvent(
                        elapsedRealtimeNs = fix.elapsedRealtimeNs,
                        sensor = "GNSS",
                        event = "FIX",
                        sequenceId = count,
                        relativePath = "gnss/$GNSS_CSV_NAME",
                        summary = "lat=%.6f;lon=%.6f;speed=%.1f;acc=%.1fm;sats=%d/%d".format(
                            Locale.US,
                            fix.latitude,
                            fix.longitude,
                            fix.speedKmh,
                            fix.accuracyMeters,
                            fix.satellitesUsed,
                            fix.satellitesInView
                        )
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing GNSS fix to CSV", e)
            }
        }
    }

    /**
     * Stops GNSS recording, flushes buffered data to disk, and closes the writer.
     */
    fun stopRecording(): Long {
        if (!isRecordingActive) return 0L

        isRecordingActive = false
        _isRecording.value = false
        val count = _fixesRecorded.value

        executor.execute {
            try {
                csvWriter?.flush()
                csvWriter?.close()
                Log.i(TAG, "Stopped GNSS recording. Total fixes written: $count")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing GNSS CSV writer", e)
            } finally {
                csvWriter = null
                activeSession = null
                gnssFile = null
            }
        }

        return count
    }

    fun release() {
        if (isRecordingActive) {
            stopRecording()
        }
        executor.shutdown()
    }
}
