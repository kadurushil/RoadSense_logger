package com.bajajauto.roadsense.storage

import android.content.Context
import android.content.SharedPreferences
import com.bajajauto.roadsense.camera.CameraFrameRate
import com.bajajauto.roadsense.camera.CameraResolution

/**
 * Thread-safe persistent preference store for RoadSense UI/card settings.
 * Persists user configurations across card navigation and application restarts.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "roadsense_user_settings"

        private const val KEY_RADAR_MAX_RANGE = "radar_max_range"
        private const val KEY_RADAR_DYNAMIC_ONLY = "radar_dynamic_only"
        private const val KEY_RADAR_MIN_SNR = "radar_min_snr"

        private const val KEY_CAMERA_RES = "camera_resolution"
        private const val KEY_CAMERA_FPS = "camera_fps"
        private const val KEY_CAMERA_LENS_ID = "camera_lens_id"

        private const val KEY_CANEDGE_LAST_IP = "canedge_last_ip"
        private const val KEY_CANEDGE_DEVICE_ID = "canedge_device_id"
    }

    var canedgeLastIp: String
        get() = prefs.getString(KEY_CANEDGE_LAST_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CANEDGE_LAST_IP, value).apply()

    var canedgeDeviceId: String
        get() = prefs.getString(KEY_CANEDGE_DEVICE_ID, "7AC5E17F") ?: "7AC5E17F"
        set(value) = prefs.edit().putString(KEY_CANEDGE_DEVICE_ID, value).apply()

    var radarMaxRange: Float
        get() = prefs.getFloat(KEY_RADAR_MAX_RANGE, 30f)
        set(value) = prefs.edit().putFloat(KEY_RADAR_MAX_RANGE, value).apply()

    var radarDynamicOnly: Boolean
        get() = prefs.getBoolean(KEY_RADAR_DYNAMIC_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_RADAR_DYNAMIC_ONLY, value).apply()

    var radarMinSnrFilter: Boolean
        get() = prefs.getBoolean(KEY_RADAR_MIN_SNR, false)
        set(value) = prefs.edit().putBoolean(KEY_RADAR_MIN_SNR, value).apply()

    var cameraResolution: CameraResolution
        get() {
            val name = prefs.getString(KEY_CAMERA_RES, CameraResolution.RES_720P.name)
            return try {
                CameraResolution.valueOf(name ?: CameraResolution.RES_720P.name)
            } catch (e: Exception) {
                CameraResolution.RES_720P
            }
        }
        set(value) = prefs.edit().putString(KEY_CAMERA_RES, value.name).apply()

    var cameraFrameRate: CameraFrameRate
        get() {
            val name = prefs.getString(KEY_CAMERA_FPS, CameraFrameRate.FPS_30.name)
            return try {
                CameraFrameRate.valueOf(name ?: CameraFrameRate.FPS_30.name)
            } catch (e: Exception) {
                CameraFrameRate.FPS_30
            }
        }
        set(value) = prefs.edit().putString(KEY_CAMERA_FPS, value.name).apply()

    var cameraLensId: String?
        get() = prefs.getString(KEY_CAMERA_LENS_ID, null)
        set(value) = prefs.edit().putString(KEY_CAMERA_LENS_ID, value).apply()
}
