package com.bajajauto.roadsense.recording

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata descriptor for a multi-sensor recording session.
 * Tracks wall-clock and monotonic timestamps for synchronization across Radar, GNSS, and Camera.
 */
data class SessionInfo(
    val sessionId: String,
    val sessionDir: File,
    val radarDir: File,
    val gnssDir: File = File(sessionDir, "gnss"),
    val cameraDir: File = File(sessionDir, "camera"),
    val canDir: File = File(sessionDir, "can"),
    val startTimeWallMs: Long,
    val startTimeMonotonicNs: Long,
    var stopTimeWallMs: Long? = null,
    var stopTimeMonotonicNs: Long? = null,
    var totalRadarFrames: Long = 0L,
    var totalRadarBytes: Long = 0L,
    var totalGnssFixes: Long = 0L,
    var totalCameraFrames: Long = 0L
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("sessionId", sessionId)
        json.put("startTimeWallMs", startTimeWallMs)
        json.put("startTimeIso", formatIso(startTimeWallMs))
        json.put("startTimeMonotonicNs", startTimeMonotonicNs)

        stopTimeWallMs?.let {
            json.put("stopTimeWallMs", it)
            json.put("stopTimeIso", formatIso(it))
            json.put("durationMs", it - startTimeWallMs)
        }
        stopTimeMonotonicNs?.let {
            json.put("stopTimeMonotonicNs", it)
        }

        val deviceObj = JSONObject()
        deviceObj.put("manufacturer", Build.MANUFACTURER)
        deviceObj.put("model", Build.MODEL)
        deviceObj.put("device", Build.DEVICE)
        deviceObj.put("sdkInt", Build.VERSION.SDK_INT)
        json.put("device", deviceObj)

        val radarObj = JSONObject()
        radarObj.put("sensor", "TI AWR1843BOOST")
        radarObj.put("baudRateDataPort", 3125000)
        radarObj.put("baudRateCliPort", 115200)
        radarObj.put("totalFrames", totalRadarFrames)
        radarObj.put("totalBytes", totalRadarBytes)
        json.put("radar", radarObj)

        val gnssObj = JSONObject()
        gnssObj.put("sensor", "Android GNSS Location Provider")
        gnssObj.put("totalFixes", totalGnssFixes)
        json.put("gnss", gnssObj)

        val cameraObj = JSONObject()
        cameraObj.put("sensor", "Android Camera Video Encoder (H.264)")
        cameraObj.put("totalFrames", totalCameraFrames)
        json.put("camera", cameraObj)

        val streamsArray = JSONArray()
        // If radar frames or bytes were recorded (or files exist), register radar streams
        if (totalRadarFrames > 0 || totalRadarBytes > 0 || File(radarDir, "radar_frames.bin").exists()) {
            streamsArray.put("radar/radar_frames.bin")
            streamsArray.put("radar/radar_raw_stream.bin")
        }
        if (totalGnssFixes > 0 || File(gnssDir, "gnss_fixes.csv").exists()) {
            streamsArray.put("gnss/gnss_fixes.csv")
        }
        if (totalCameraFrames > 0 || File(cameraDir, "camera_frames.csv").exists()) {
            streamsArray.put("camera/camera_video.mp4")
            streamsArray.put("camera/camera_frames.csv")
        }
        val canFiles = canDir.listFiles { _, name -> name.endsWith(".mf4", ignoreCase = true) }
        if (!canFiles.isNullOrEmpty()) {
            val canObj = JSONObject()
            canObj.put("sensor", "CSS Electronics CANedge2")
            canObj.put("totalFiles", canFiles.size)
            json.put("can", canObj)
            for (cf in canFiles) {
                streamsArray.put("can/${cf.name}")
            }
        }
        val timelineFile = File(sessionDir, "session_timeline.csv")
        if (timelineFile.exists()) {
            streamsArray.put("session_timeline.csv")
        }
        val debugLogFile = File(sessionDir, "session_debug.log")
        if (debugLogFile.exists()) {
            streamsArray.put("session_debug.log")
        }
        json.put("activeStreams", streamsArray)

        return json.toString(2)
    }

    companion object {
        fun formatIso(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            return sdf.format(Date(epochMs))
        }
    }
}
