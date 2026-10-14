package com.bajajauto.roadsense.fusion.model

import org.json.JSONObject

/**
 * Encapsulates the physical vehicle mounting offsets, target geometry, and 
 * 6-DOF extrinsic calibration parameters between the front TI mmWave radar and 
 * windshield smartphone camera.
 *
 * All radar detections are 2D planar (Z_radar = 0).
 */
data class CalibrationParameters(
    val setbackM: Float = 0.30f,          // Longitudinal setback (ΔY) from radar to phone lens (30 cm)
    val heightOffsetM: Float = 0.50f,     // Elevation difference (ΔZ = PhoneHeight - RadarHeight) (50 cm)
    val lateralOffsetM: Float = 0.00f,    // Lateral offset (ΔX) relative to vehicle centerline
    val targetDistanceM: Float = 10.0f,   // Known distance (D) to target car in front
    val targetWidthM: Float = 1.30f,      // Known physical width (W) of target car in front (e.g. 1.3m)
    val targetHeightM: Float = 0.00f,     // Target feature height relative to radar plane (0.0m = bonnet level)
    val radarHeightM: Float = 0.95f,      // Radar elevation above road surface (bonnet mount ~ 95 cm)
    val pitchDeg: Float = 7.0f,           // Baseline camera tilt (positive = +7.0°)
    val yawDeg: Float = -1.0f,            // Baseline camera heading pan (-1.0°)
    val rollDeg: Float = 0.0f,            // Clamp mount roll level
    val nudgePitchDeg: Float = 0.0f,      // Live in-field session nudge for pitch
    val nudgeYawDeg: Float = 0.0f,        // Live in-field session nudge for yaw
    val cameraYawDeg: Float = 0.0f,       // Windshield camera heading relative to vehicle centerline
    val streamWidth: Int = 1920,          // Camera video stream width in pixels (1080p standard)
    val streamHeight: Int = 1080,         // Camera video stream height in pixels (1080p standard)
    val fx: Float = 1486.5f,              // Video stream focal length X (Galaxy M30 1080p standard)
    val fy: Float = 1486.5f,              // Video stream focal length Y (square pixels)
    val cx: Float = 960.0f,               // Video stream principal center X
    val cy: Float = 540.0f,               // Video stream principal center Y
    val hfovDeg: Float = 65.7f,           // Camera horizontal FOV (degrees)
    val vfovDeg: Float = 39.8f,           // Camera vertical FOV (degrees)
    val profileName: String = "IRVM Bonnet Mount",
    val lastCalibratedTimestampMs: Long = System.currentTimeMillis()
) {
    val effectivePitchDeg: Float get() = pitchDeg + nudgePitchDeg
    val effectiveYawDeg: Float get() = yawDeg + nudgeYawDeg

    // Semantic Physical Aliases
    val cameraPitchDeg: Float get() = effectivePitchDeg
    val radarYawOffsetDeg: Float get() = effectiveYawDeg
    val isClampLevel: Boolean get() = kotlin.math.abs(rollDeg) <= 0.8f

    val pitchQuality: PitchQuality get() = when {
        effectivePitchDeg < 0.0f -> PitchQuality.FACING_UP
        effectivePitchDeg < 3.0f -> PitchQuality.TOO_SHALLOW
        effectivePitchDeg > 12.0f -> PitchQuality.TOO_STEEP
        else -> PitchQuality.OPTIMAL
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("setbackM", setbackM.toDouble())
            put("heightOffsetM", heightOffsetM.toDouble())
            put("lateralOffsetM", lateralOffsetM.toDouble())
            put("targetDistanceM", targetDistanceM.toDouble())
            put("targetWidthM", targetWidthM.toDouble())
            put("targetHeightM", targetHeightM.toDouble())
            put("radarHeightM", radarHeightM.toDouble())
            put("pitchDeg", pitchDeg.toDouble())
            put("yawDeg", yawDeg.toDouble())
            put("rollDeg", rollDeg.toDouble())
            put("nudgePitchDeg", nudgePitchDeg.toDouble())
            put("nudgeYawDeg", nudgeYawDeg.toDouble())
            put("cameraYawDeg", cameraYawDeg.toDouble())
            put("radarYawOffsetDeg", radarYawOffsetDeg.toDouble())
            put("streamWidth", streamWidth)
            put("streamHeight", streamHeight)
            put("fx", fx.toDouble())
            put("fy", fy.toDouble())
            put("cx", cx.toDouble())
            put("cy", cy.toDouble())
            put("hfovDeg", hfovDeg.toDouble())
            put("vfovDeg", vfovDeg.toDouble())
            put("profileName", profileName)
            put("lastCalibratedTimestampMs", lastCalibratedTimestampMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CalibrationParameters {
            val pDeg = json.optDouble("pitchDeg", 7.0).toFloat()
            val yDeg = json.optDouble("yawDeg", -1.0).toFloat()
            val streamW = json.optInt("streamWidth", 1920)
            val streamH = json.optInt("streamHeight", 1080)
            val hfov = json.optDouble("hfovDeg", 65.7).toFloat()
            val vfov = json.optDouble("vfovDeg", 39.8).toFloat()
            val defaultFx = (streamW / (2.0 * kotlin.math.tan(Math.toRadians(hfov.toDouble() / 2.0)))).toFloat()

            return CalibrationParameters(
                setbackM = json.optDouble("setbackM", 0.30).toFloat(),
                heightOffsetM = json.optDouble("heightOffsetM", 0.50).toFloat(),
                lateralOffsetM = json.optDouble("lateralOffsetM", 0.00).toFloat(),
                targetDistanceM = json.optDouble("targetDistanceM", 10.0).toFloat(),
                targetWidthM = json.optDouble("targetWidthM", 1.30).toFloat(),
                targetHeightM = json.optDouble("targetHeightM", 0.00).toFloat(),
                radarHeightM = json.optDouble("radarHeightM", 0.95).toFloat(),
                pitchDeg = pDeg,
                yawDeg = yDeg,
                rollDeg = json.optDouble("rollDeg", 0.0).toFloat(),
                nudgePitchDeg = json.optDouble("nudgePitchDeg", 0.0).toFloat(),
                nudgeYawDeg = json.optDouble("nudgeYawDeg", 0.0).toFloat(),
                cameraYawDeg = json.optDouble("cameraYawDeg", 0.0).toFloat(),
                streamWidth = streamW,
                streamHeight = streamH,
                fx = json.optDouble("fx", defaultFx.toDouble()).toFloat(),
                fy = json.optDouble("fy", defaultFx.toDouble()).toFloat(),
                cx = json.optDouble("cx", (streamW / 2.0)).toFloat(),
                cy = json.optDouble("cy", (streamH / 2.0)).toFloat(),
                hfovDeg = hfov,
                vfovDeg = vfov,
                profileName = json.optString("profileName", "IRVM Bonnet Mount"),
                lastCalibratedTimestampMs = json.optLong("lastCalibratedTimestampMs", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Pitch angle quality status for automotive ADAS windshield mounting.
 * Typical forward ADAS operates best with 3° to 12° downward tilt (+3.0° to +12.0°).
 */
enum class PitchQuality {
    OPTIMAL,       // 3.0° .. 12.0° (ideal road-to-sky ratio)
    TOO_SHALLOW,   // < 3.0° (too much sky, near bumper clipped)
    TOO_STEEP,     // > 12.0° (too much bonnet/dashboard)
    FACING_UP      // < 0.0° (tilted upwards toward sky)
}
