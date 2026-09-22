package com.bajajauto.roadsense.fusion.storage

import android.content.Context
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Thread-safe file persistence manager for radar-camera calibration parameters.
 * Stores calibration under files/calibration/radar_camera_calib.json.
 */
class CalibrationStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "CalibrationStorage"
        private const val CALIBRATION_DIR = "calibration"
        private const val CALIBRATION_FILE = "radar_camera_calib.json"
    }

    private fun getStorageFile(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val calibDir = File(baseDir, CALIBRATION_DIR)
        if (!calibDir.exists()) {
            calibDir.mkdirs()
        }
        return File(calibDir, CALIBRATION_FILE)
    }

    /**
     * Loads calibration parameters from disk asynchronously.
     * Returns default parameters if no calibration has been saved yet.
     */
    suspend fun loadCalibration(): CalibrationParameters = withContext(Dispatchers.IO) {
        val file = getStorageFile()
        if (!file.exists() || file.length() == 0L) {
            AppLogger.d(TAG, "No calibration file found at ${file.absolutePath}, returning defaults.")
            return@withContext CalibrationParameters()
        }

        try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val params = CalibrationParameters.fromJson(json)
            AppLogger.i(TAG, "Loaded calibration profile '${params.profileName}' from ${file.name}: Pitch=${params.pitchDeg}°, Yaw=${params.yawDeg}°")
            params
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse calibration file, reverting to defaults", e)
            CalibrationParameters()
        }
    }

    /**
     * Saves calibration parameters to disk asynchronously.
     */
    suspend fun saveCalibration(params: CalibrationParameters): Boolean = withContext(Dispatchers.IO) {
        val file = getStorageFile()
        try {
            val jsonString = params.toJson().toString(2)
            file.writeText(jsonString, Charsets.UTF_8)
            AppLogger.i(TAG, "Saved calibration profile '${params.profileName}' to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save calibration file", e)
            false
        }
    }
}
