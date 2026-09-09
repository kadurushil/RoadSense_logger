package com.bajajauto.roadsense.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata record for an individual camera frame exposed by the camera sensor.
 *
 * @param frameNumber Monotonic hardware frame sequence index
 * @param shutterTimestampNs Monotonic hardware timestamp (SystemClock.elapsedRealtimeNanos()) of sensor exposure start
 * @param exposureTimeNs Duration the sensor was exposed to light in nanoseconds
 * @param iso Sensor sensitivity setting
 * @param utcTimestampMs Approximate UTC wall-clock epoch time in milliseconds
 */
data class CameraFrameMetadata(
    val frameNumber: Long,
    val shutterTimestampNs: Long,
    val exposureTimeNs: Long = 0L,
    val iso: Int = 0,
    val utcTimestampMs: Long = System.currentTimeMillis()
) {
    /**
     * Serializes this frame metadata to a CSV row for logging in camera_frames.csv.
     */
    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%d,%d,%d,%d,%d,%s\n",
            frameNumber,
            shutterTimestampNs,
            exposureTimeNs,
            iso,
            utcTimestampMs,
            formatIso(utcTimestampMs)
        )
    }

    companion object {
        const val CSV_HEADER = "frame_number,shutter_monotonic_ns,exposure_time_ns,iso,utc_time_ms,utc_iso\n"

        fun formatIso(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            return sdf.format(Date(epochMs))
        }
    }
}
