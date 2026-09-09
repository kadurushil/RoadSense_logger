package com.bajajauto.roadsense.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Autonomous high-rate GNSS service manager.
 * Operates independently from radar acquisition.
 * Extracts WGS84 positions, speed, course, accuracy, satellite health,
 * and Android monotonic elapsedRealtimeNanos for cross-sensor microsecond synchronization.
 */
class GnssLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "GnssLocationManager"
        private const val MIN_TIME_MS = 200L // Request up to 5 Hz GNSS updates
        private const val MIN_DISTANCE_M = 0.0f
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gnssState = MutableStateFlow<GnssState>(GnssState.Disabled)
    val gnssState: StateFlow<GnssState> = _gnssState.asStateFlow()

    private val _latestFix = MutableStateFlow<GnssFix?>(null)
    val latestFix: StateFlow<GnssFix?> = _latestFix.asStateFlow()

    private val _totalFixes = MutableStateFlow(0L)
    val totalFixes: StateFlow<Long> = _totalFixes.asStateFlow()

    private val fixListeners = CopyOnWriteArrayList<(GnssFix) -> Unit>()

    @Volatile
    private var isRunning = false

    private var satellitesInView = 0
    private var satellitesUsed = 0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = mapToGnssFix(location)
            _latestFix.value = fix
            _totalFixes.value += 1
            _gnssState.value = GnssState.Active(fix = fix, totalFixes = _totalFixes.value)

            // Direct non-blocking callback to listeners (e.g. Session Recorder)
            for (listener in fixListeners) {
                try {
                    listener(fix)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in GNSS fix listener callback", e)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "GNSS Provider enabled: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "GNSS Provider disabled: $provider")
            if (provider == LocationManager.GPS_PROVIDER) {
                _gnssState.value = GnssState.Error("GPS Provider is disabled in system settings")
            }
        }
    }

    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                satellitesInView = status.satelliteCount
                var usedCount = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        usedCount++
                    }
                }
                satellitesUsed = usedCount
            }
        }
    } else null

    fun addFixListener(listener: (GnssFix) -> Unit) {
        fixListeners.add(listener)
    }

    fun removeFixListener(listener: (GnssFix) -> Unit) {
        fixListeners.remove(listener)
    }

    /**
     * Checks if location permission has been granted by user.
     */
    fun hasLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    /**
     * Starts receiving high-rate location updates from GPS provider.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(): Boolean {
        if (isRunning) return true

        if (!hasLocationPermission()) {
            _gnssState.value = GnssState.Error("Location permission not granted")
            return false
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _gnssState.value = GnssState.Error("GPS is turned off in Android settings")
            return false
        }

        try {
            _gnssState.value = GnssState.Searching

            // 1. Request primary GPS satellite updates
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener,
                Looper.getMainLooper()
            )

            // 2. Request network provider as backup for faster initial TTFF
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS * 2,
                    MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            // 3. Register GNSS satellite status callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
            }

            // Check if last known location is immediately available
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLoc ->
                val fix = mapToGnssFix(lastLoc)
                _latestFix.value = fix
            }

            isRunning = true
            Log.i(TAG, "Started GNSS location updates successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GNSS updates", e)
            _gnssState.value = GnssState.Error("Failed to start location: ${e.message}")
            return false
        }
    }

    /**
     * Stops location updates and satellite monitoring.
     */
    fun stopLocationUpdates() {
        if (!isRunning) return

        try {
            locationManager.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        } finally {
            isRunning = false
            _gnssState.value = GnssState.Disabled
            Log.i(TAG, "Stopped GNSS location updates")
        }
    }

    private fun mapToGnssFix(location: Location): GnssFix {
        return GnssFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            speedMps = if (location.hasSpeed()) location.speed else 0.0f,
            bearingDegrees = if (location.hasBearing()) location.bearing else 0.0f,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 0.0f,
            elapsedRealtimeNs = location.elapsedRealtimeNanos,
            utcTimeMs = location.time,
            provider = location.provider ?: "gps",
            satellitesInView = satellitesInView,
            satellitesUsed = satellitesUsed
        )
    }

    fun release() {
        stopLocationUpdates()
        fixListeners.clear()
    }
}
