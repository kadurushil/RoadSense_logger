package com.bajajauto.roadsense.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bajajauto.roadsense.R
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.ui.MainActivity

/**
 * Foreground service keeping camera, radar USB, IMU, and GNSS acquisition alive
 * whenever the screen turns off or the driver switches apps mid-drive.
 * Dynamically builds its foregroundServiceType mask from granted runtime permissions
 * so Android 14+ (targetSdk 37) never raises SecurityException.
 */
class RecordingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            AppLogger.i(TAG, "Stop action tapped from persistent recording notification")
            mainHandler.post {
                onStopRequested?.invoke()
            }
            stopForegroundSafely()
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: "active_session"
        val hasRadarUsb = intent?.getBooleanExtra(EXTRA_HAS_RADAR_USB, false) ?: false

        acquireWakeLock()
        val notification = buildNotification(sessionId)
        startForegroundSafely(notification, hasRadarUsb)
        AppLogger.i(TAG, "RecordingForegroundService started for $sessionId (hasRadarUsb=$hasRadarUsb)")
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopForegroundSafely()
        AppLogger.i(TAG, "RecordingForegroundService stopped and WakeLock released")
        super.onDestroy()
    }

    private fun startForegroundSafely(notification: Notification, hasRadarUsb: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }

        val typeMask = resolveServiceTypeMask(hasRadarUsb)
        try {
            startForeground(NOTIFICATION_ID, notification, typeMask)
        } catch (se: SecurityException) {
            AppLogger.w(TAG, "FGS type mask 0x${typeMask.toString(16)} rejected (${se.message}); falling back to DATA_SYNC")
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Fallback DATA_SYNC startForeground failed: ${e.message}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to promote foreground service: ${e.message}", e)
        }
    }

    private fun resolveServiceTypeMask(hasRadarUsb: Boolean): Int {
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            val hasFineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasFineLoc || hasCoarseLoc) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (hasRadarUsb) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        }
        return mask
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not acquire PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "RoadSense Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps multi-sensor recording active in background"
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(sessionId: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RoadSense Recording Active")
            .setContentText("Session: $sessionId")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(0, "Stop Recording", stopPending)
            .build()
    }

    companion object {
        private const val TAG = "RecordingFGS"
        private const val CHANNEL_ID = "roadsense_recording"
        private const val NOTIFICATION_ID = 4101
        private const val WAKELOCK_TAG = "RoadSense:RecordingWakeLock"
        private const val MAX_WAKELOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L // 4 hours max guard
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_HAS_RADAR_USB = "extra_has_radar_usb"
        private const val ACTION_STOP_RECORDING = "com.bajajauto.roadsense.action.STOP_RECORDING"

        @Volatile
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, sessionId: String, hasRadarUsb: Boolean) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_HAS_RADAR_USB, hasRadarUsb)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start RecordingForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RecordingForegroundService::class.java))
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to stop RecordingForegroundService: ${e.message}")
            }
        }
    }
}
