package com.bajajauto.roadsense.gnss

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a high-precision geographic location fix captured from GNSS.
 *
 * @param latitude Latitude in degrees (WGS84)
 * @param longitude Longitude in degrees (WGS84)
 * @param altitudeMeters Altitude above WGS84 ellipsoid in meters
 * @param speedMps Speed over ground in meters/second
 * @param bearingDegrees Course over ground in degrees [0, 360)
 * @param accuracyMeters Horizontal 1-sigma estimated accuracy in meters
 * @param elapsedRealtimeNs Monotonic hardware timestamp (SystemClock.elapsedRealtimeNanos()) for microsecond radar/camera sync
 * @param utcTimeMs UTC epoch timestamp in milliseconds
 * @param provider Location provider ("gps", "network", "fused")
 * @param satellitesInView Number of satellites in view
 * @param satellitesUsed Number of satellites used in fix calculation
 */
data class GnssFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
    val speedMps: Float = 0.0f,
    val bearingDegrees: Float = 0.0f,
    val accuracyMeters: Float = 0.0f,
    val elapsedRealtimeNs: Long,
    val utcTimeMs: Long,
    val provider: String = "gps",
    val satellitesInView: Int = 0,
    val satellitesUsed: Int = 0
) {
    val speedKmh: Float
        get() = speedMps * 3.6f

    /**
     * Serializes this GNSS fix to a CSV row for logging.
     */
    fun toCsvRow(): String {
        val iso = formatIso(utcTimeMs)
        return String.format(
            Locale.US,
            "%d,%d,%s,%.7f,%.7f,%.2f,%.2f,%.2f,%.1f,%.1f,%s,%d/%d\n",
            elapsedRealtimeNs,
            utcTimeMs,
            iso,
            latitude,
            longitude,
            altitudeMeters,
            speedMps,
            speedKmh,
            bearingDegrees,
            accuracyMeters,
            provider,
            satellitesUsed,
            satellitesInView
        )
    }

    companion object {
        const val CSV_HEADER = "elapsed_realtime_ns,utc_time_ms,utc_iso,latitude,longitude,altitude_m,speed_mps,speed_kmh,bearing_deg,accuracy_m,provider,satellites\n"

        fun formatIso(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            return sdf.format(Date(epochMs))
        }
    }
}
