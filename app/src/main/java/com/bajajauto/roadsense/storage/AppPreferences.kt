package com.bajajauto.roadsense.storage

import android.content.Context
import android.content.SharedPreferences
import com.bajajauto.roadsense.HudOverlayMode
import com.bajajauto.roadsense.camera.CameraFrameRate
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.ui.components.gl.BevColorMap
import com.bajajauto.roadsense.ui.components.gl.BevDecayPreset
import com.bajajauto.roadsense.ui.components.gl.BevPointColorMode
import com.bajajauto.roadsense.ui.components.gl.BevPointShape
import com.bajajauto.roadsense.ui.components.gl.BevPointSizePreset

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

        private const val KEY_BEV_SHOW_RAW_POINTS = "bev_show_raw_points"
        private const val KEY_BEV_SHOW_TRACKS = "bev_show_tracks"
        private const val KEY_BEV_SHOW_CLUSTERS = "bev_show_clusters_v2"
        private const val KEY_BEV_SHOW_GRID = "bev_show_grid"
        private const val KEY_BEV_POINT_COLOR_MODE = "bev_point_color_mode"
        private const val KEY_BEV_COLOR_MAP = "bev_color_map"
        private const val KEY_BEV_POINT_SHAPE = "bev_point_shape"
        private const val KEY_BEV_POINT_SIZE = "bev_point_size_preset"
        private const val KEY_BEV_DECAY_PRESET = "bev_decay_preset"
        private const val KEY_BEV_STIXELS_ENABLED = "bev_stixels_enabled"
        private const val KEY_BEV_SHOW_ROAD_LANES = "bev_show_road_lanes"
        private const val KEY_BEV_HIDE_OUTLIERS = "bev_hide_outliers"
        private const val KEY_BEV_FLIP_Z = "bev_flip_z"
        private const val KEY_HUD_OVERLAY_MODE = "hud_overlay_mode"

        private const val KEY_CAMERA_RES = "camera_resolution"
        private const val KEY_CAMERA_FPS = "camera_fps"
        private const val KEY_CAMERA_LENS_ID = "camera_lens_id"

        private const val KEY_CANEDGE_LAST_IP = "canedge_last_ip"
        private const val KEY_CANEDGE_DEVICE_ID = "canedge_device_id"
        private const val KEY_CANEDGE_LAST_CLOCK_OFFSET_MS = "canedge_last_clock_offset_ms"
        private const val KEY_CANEDGE_LAST_CLOCK_MEASURED_AT_MS = "canedge_last_clock_measured_at_ms"

        private const val KEY_SESSION_VEHICLE_ID = "session_vehicle_id"
        private const val KEY_SESSION_DSP_FIRMWARE = "session_dsp_firmware"
        private const val KEY_SESSION_MOUNT_TYPE = "session_mount_type"
        private const val KEY_SESSION_LIGHTING = "session_lighting_condition"
        private const val KEY_BOOKMARK_HAPTICS = "bookmark_haptics_enabled"
        private const val KEY_PROMPT_PREFLIGHT = "prompt_preflight_before_rec"
    }

    var canedgeLastIp: String
        get() = prefs.getString(KEY_CANEDGE_LAST_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CANEDGE_LAST_IP, value).apply()

    var canedgeDeviceId: String
        get() = prefs.getString(KEY_CANEDGE_DEVICE_ID, "7AC5E17F") ?: "7AC5E17F"
        set(value) = prefs.edit().putString(KEY_CANEDGE_DEVICE_ID, value).apply()

    /**
     * Last measured CANedge2 RTC drift relative to Android wall-clock time:
     * clockOffsetMs = phoneWallMs - canedgeEpochMs.
     * Positive means CANedge RTC lags behind Android wall clock.
     */
    var canedgeLastClockOffsetMs: Long
        get() = prefs.getLong(KEY_CANEDGE_LAST_CLOCK_OFFSET_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_CANEDGE_LAST_CLOCK_OFFSET_MS, value).apply()

    var canedgeLastClockMeasuredAtMs: Long
        get() = prefs.getLong(KEY_CANEDGE_LAST_CLOCK_MEASURED_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_CANEDGE_LAST_CLOCK_MEASURED_AT_MS, value).apply()

    var radarMaxRange: Float
        get() = prefs.getFloat(KEY_RADAR_MAX_RANGE, 30f).let { if (it == 130f) 150f else it }
        set(value) = prefs.edit().putFloat(KEY_RADAR_MAX_RANGE, value).apply()

    var radarDynamicOnly: Boolean
        get() = prefs.getBoolean(KEY_RADAR_DYNAMIC_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_RADAR_DYNAMIC_ONLY, value).apply()

    var radarMinSnrFilter: Boolean
        get() = prefs.getBoolean(KEY_RADAR_MIN_SNR, false)
        set(value) = prefs.edit().putBoolean(KEY_RADAR_MIN_SNR, value).apply()

    var bevShowRawPoints: Boolean
        get() = prefs.getBoolean(KEY_BEV_SHOW_RAW_POINTS, true)
        set(value) = prefs.edit().putBoolean(KEY_BEV_SHOW_RAW_POINTS, value).apply()

    var bevShowTracks: Boolean
        get() = prefs.getBoolean(KEY_BEV_SHOW_TRACKS, true)
        set(value) = prefs.edit().putBoolean(KEY_BEV_SHOW_TRACKS, value).apply()

    var bevShowClusters: Boolean
        get() = prefs.getBoolean(KEY_BEV_SHOW_CLUSTERS, false)
        set(value) = prefs.edit().putBoolean(KEY_BEV_SHOW_CLUSTERS, value).apply()

    var bevShowGrid: Boolean
        get() = prefs.getBoolean(KEY_BEV_SHOW_GRID, true)
        set(value) = prefs.edit().putBoolean(KEY_BEV_SHOW_GRID, value).apply()

    var hudOverlayMode: HudOverlayMode
        get() {
            val name = prefs.getString(KEY_HUD_OVERLAY_MODE, HudOverlayMode.TRACKS_LOLLIPOP_80M.name)
            return try {
                HudOverlayMode.valueOf(name ?: HudOverlayMode.TRACKS_LOLLIPOP_80M.name)
            } catch (_: Exception) {
                HudOverlayMode.TRACKS_LOLLIPOP_80M
            }
        }
        set(value) = prefs.edit().putString(KEY_HUD_OVERLAY_MODE, value.name).apply()

    var bevPointColorMode: BevPointColorMode
        get() {
            val name = prefs.getString(KEY_BEV_POINT_COLOR_MODE, BevPointColorMode.AUTO.name)
            return try {
                BevPointColorMode.valueOf(name ?: BevPointColorMode.AUTO.name)
            } catch (_: Exception) {
                BevPointColorMode.AUTO
            }
        }
        set(value) = prefs.edit().putString(KEY_BEV_POINT_COLOR_MODE, value.name).apply()

    var bevColorMap: BevColorMap
        get() {
            val name = prefs.getString(KEY_BEV_COLOR_MAP, BevColorMap.TURBO.name)
            return try {
                BevColorMap.valueOf(name ?: BevColorMap.TURBO.name)
            } catch (_: Exception) {
                BevColorMap.TURBO
            }
        }
        set(value) = prefs.edit().putString(KEY_BEV_COLOR_MAP, value.name).apply()

    var bevPointShape: BevPointShape
        get() {
            val name = prefs.getString(KEY_BEV_POINT_SHAPE, BevPointShape.CIRCLE.name)
            return try {
                BevPointShape.valueOf(name ?: BevPointShape.CIRCLE.name)
            } catch (_: Exception) {
                BevPointShape.CIRCLE
            }
        }
        set(value) = prefs.edit().putString(KEY_BEV_POINT_SHAPE, value.name).apply()

    var bevPointSizePreset: BevPointSizePreset
        get() {
            val name = prefs.getString(KEY_BEV_POINT_SIZE, BevPointSizePreset.MEDIUM.name)
            return try {
                BevPointSizePreset.valueOf(name ?: BevPointSizePreset.MEDIUM.name)
            } catch (_: Exception) {
                BevPointSizePreset.MEDIUM
            }
        }
        set(value) = prefs.edit().putString(KEY_BEV_POINT_SIZE, value.name).apply()

    var bevDecayPreset: BevDecayPreset
        get() {
            val name = prefs.getString(KEY_BEV_DECAY_PRESET, BevDecayPreset.OFF.name)
            return try {
                BevDecayPreset.valueOf(name ?: BevDecayPreset.OFF.name)
            } catch (_: Exception) {
                BevDecayPreset.OFF
            }
        }
        set(value) = prefs.edit().putString(KEY_BEV_DECAY_PRESET, value.name).apply()

    var bevStixelsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEV_STIXELS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BEV_STIXELS_ENABLED, value).apply()

    var bevShowRoadLanes: Boolean
        get() = prefs.getBoolean(KEY_BEV_SHOW_ROAD_LANES, true)
        set(value) = prefs.edit().putBoolean(KEY_BEV_SHOW_ROAD_LANES, value).apply()

    var bevHideOutliers: Boolean
        get() = prefs.getBoolean(KEY_BEV_HIDE_OUTLIERS, false)
        set(value) = prefs.edit().putBoolean(KEY_BEV_HIDE_OUTLIERS, value).apply()

    var bevFlipZ: Boolean
        get() = prefs.getBoolean(KEY_BEV_FLIP_Z, false)
        set(value) = prefs.edit().putBoolean(KEY_BEV_FLIP_Z, value).apply()

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

    var sessionVehicleId: String
        get() = prefs.getString(KEY_SESSION_VEHICLE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_VEHICLE_ID, value).apply()

    var sessionDspFirmware: String
        get() = prefs.getString(KEY_SESSION_DSP_FIRMWARE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_DSP_FIRMWARE, value).apply()

    var sessionMountType: String
        get() = prefs.getString(KEY_SESSION_MOUNT_TYPE, "Hard Mount") ?: "Hard Mount"
        set(value) = prefs.edit().putString(KEY_SESSION_MOUNT_TYPE, value).apply()

    var sessionLightingCondition: String
        get() = prefs.getString(KEY_SESSION_LIGHTING, "Daylight") ?: "Daylight"
        set(value) = prefs.edit().putString(KEY_SESSION_LIGHTING, value).apply()

    var bookmarkHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOOKMARK_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOKMARK_HAPTICS, value).apply()

    var promptPreflightBeforeRec: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_PREFLIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_PREFLIGHT, value).apply()
}
