package com.bajajauto.roadsense.diagnostics

import android.util.Log
import com.bajajauto.roadsense.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global uncaught-exception safety net that performs a bounded emergency flush of
 * active recording streams (MediaRecorder, timeline, sensor files, and AppLogger)
 * before delegating to the platform crash handler.
 */
object CrashRecoveryHandler {

    private const val TAG = "CrashRecovery"
    private const val EMERGENCY_FLUSH_TIMEOUT_MS = 2000L

    private val installed = AtomicBoolean(false)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    var emergencyFlushCallback: (() -> Unit)? = null

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val trace = Log.getStackTraceString(throwable)
            AppLogger.e(TAG, "FATAL UNCAUGHT EXCEPTION on thread='${thread.name}':\n$trace")
            val flush = emergencyFlushCallback
            if (flush != null) {
                val worker = Thread({
                    try {
                        flush.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Emergency flush failed: ${e.message}", e)
                    }
                }, "RS-EmergencyFlush").apply { isDaemon = true }
                worker.start()
                worker.join(EMERGENCY_FLUSH_TIMEOUT_MS)
            }
            AppLogger.closeAppLogging()
        } catch (e: Exception) {
            Log.e(TAG, "Error inside crash recovery handler: ${e.message}", e)
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
