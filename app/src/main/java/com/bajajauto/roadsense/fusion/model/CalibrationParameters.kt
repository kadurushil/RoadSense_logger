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
    val profileName: String = "IRVM Bonnet Mount",
    val lastCalibratedTimestampMs: Long = System.currentTimeMillis()
) {
    val effectivePitchDeg: Float get() = pitchDeg + nudgePitchDeg
    val effectiveYawDeg: Float get() = yawDeg + nudgeYawDeg

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
            put("profileName", profileName)
            put("lastCalibratedTimestampMs", lastCalibratedTimestampMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CalibrationParameters {
            return CalibrationParameters(
                setbackM = json.optDouble("setbackM", 0.30).toFloat(),
                heightOffsetM = json.optDouble("heightOffsetM", 0.50).toFloat(),
                lateralOffsetM = json.optDouble("lateralOffsetM", 0.00).toFloat(),
                targetDistanceM = json.optDouble("targetDistanceM", 10.0).toFloat(),
                targetWidthM = json.optDouble("targetWidthM", 1.30).toFloat(),
                targetHeightM = json.optDouble("targetHeightM", 0.00).toFloat(),
                radarHeightM = json.optDouble("radarHeightM", 0.95).toFloat(),
                pitchDeg = json.optDouble("pitchDeg", 7.0).toFloat(),
                yawDeg = json.optDouble("yawDeg", -1.0).toFloat(),
                rollDeg = json.optDouble("rollDeg", 0.0).toFloat(),
                nudgePitchDeg = json.optDouble("nudgePitchDeg", 0.0).toFloat(),
                nudgeYawDeg = json.optDouble("nudgeYawDeg", 0.0).toFloat(),
                profileName = json.optString("profileName", "IRVM Bonnet Mount"),
                lastCalibratedTimestampMs = json.optLong("lastCalibratedTimestampMs", System.currentTimeMillis())
            )
        }
    }
}
