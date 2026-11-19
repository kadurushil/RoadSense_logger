package com.bajajauto.roadsense.ui

import androidx.compose.runtime.Immutable
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.canedge.model.CanedgeHudPhase
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.gnss.GnssLocationManager
import com.bajajauto.roadsense.gnss.GnssState
import java.util.Locale

/**
 * Traffic-light severity used across the top HUD and sensor readiness badges.
 */
enum class SensorHudTone {
    GREEN,
    YELLOW,
    CYAN,
    RED
}

/**
 * Rendered pill text variants for a single sensor.
 * [fullValue] is attempted first; [compactValue] is used automatically when horizontal space is tight.
 */
@Immutable
data class SensorPillStatus(
    val fullValue: String,
    val compactValue: String,
    val tone: SensorHudTone
)

/**
 * Pure traffic-light readiness evaluator for all 5 vehicle sensors (RADAR, CAM, GPS, IMU, CAN).
 */
object SensorReadinessEvaluator {

    fun evaluateRadar(
        connState: RadarConnectionState,
        radarHz: Float,
        isRecording: Boolean,
        radarDroppedMidRecording: Boolean
    ): SensorPillStatus {
        return when (connState) {
            is RadarConnectionState.Error -> SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
            is RadarConnectionState.Disconnected -> {
                if (isRecording && radarDroppedMidRecording) {
                    SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
                } else {
                    SensorPillStatus("OFF", "OFF", SensorHudTone.RED)
                }
            }
            is RadarConnectionState.Connecting -> SensorPillStatus("WAIT", "WAIT", SensorHudTone.YELLOW)
            is RadarConnectionState.Connected -> {
                when {
                    radarDroppedMidRecording && radarHz <= 0f -> SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
                    radarHz >= 15f -> {
                        val hz = String.format(Locale.US, "%.1fHz", radarHz)
                        SensorPillStatus("$hz OK", hz, SensorHudTone.GREEN)
                    }
                    radarHz > 0f -> {
                        val hz = String.format(Locale.US, "%.1fHz", radarHz)
                        SensorPillStatus(hz, hz, SensorHudTone.YELLOW)
                    }
                    else -> SensorPillStatus("WAIT", "WAIT", SensorHudTone.YELLOW)
                }
            }
        }
    }

    fun evaluateCamera(
        engineState: CameraEngineState,
        cameraFps: Float
    ): SensorPillStatus {
        if (engineState is CameraEngineState.Error) {
            return SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
        }
        if (cameraFps >= 20f) {
            val fps = String.format(Locale.US, "%.0ffps", cameraFps)
            return SensorPillStatus("$fps OK", fps, SensorHudTone.GREEN)
        }
        if (cameraFps > 0f) {
            val fps = String.format(Locale.US, "%.0ffps", cameraFps)
            return SensorPillStatus(fps, fps, SensorHudTone.YELLOW)
        }
        return when (engineState) {
            is CameraEngineState.Opening,
            is CameraEngineState.Previewing,
            is CameraEngineState.Recording -> SensorPillStatus("WAIT", "WAIT", SensorHudTone.YELLOW)
            is CameraEngineState.Standby -> SensorPillStatus("STBY", "STBY", SensorHudTone.YELLOW)
            is CameraEngineState.Closed -> SensorPillStatus("OFF", "OFF", SensorHudTone.RED)
            is CameraEngineState.Error -> SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
        }
    }

    fun evaluateGnss(
        gnssState: GnssState,
        gnssHz: Float
    ): SensorPillStatus {
        return when (gnssState) {
            is GnssState.Error -> SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
            is GnssState.Disabled -> SensorPillStatus("OFF", "OFF", SensorHudTone.RED)
            is GnssState.Searching -> SensorPillStatus("SRCH", "SRCH", SensorHudTone.YELLOW)
            is GnssState.Active -> {
                val fix = gnssState.fix
                when {
                    GnssLocationManager.hasTrueSatelliteLock(fix.provider, fix.satellitesUsed, gnssHz) -> {
                        val hz = String.format(Locale.US, "%.1fHz", gnssHz)
                        SensorPillStatus("$hz OK", hz, SensorHudTone.GREEN)
                    }
                    fix.provider.equals("network", ignoreCase = true) ||
                        fix.provider.equals("fused", ignoreCase = true) -> {
                        SensorPillStatus("NET", "NET", SensorHudTone.YELLOW)
                    }
                    gnssHz > 0f -> {
                        val hz = String.format(Locale.US, "%.1fHz", gnssHz)
                        SensorPillStatus(hz, hz, SensorHudTone.YELLOW)
                    }
                    else -> SensorPillStatus("SRCH", "SRCH", SensorHudTone.YELLOW)
                }
            }
        }
    }

    fun evaluateImu(
        imuHz: Float,
        isMonitoring: Boolean,
        isRecording: Boolean
    ): SensorPillStatus {
        return when {
            imuHz >= 80f -> {
                val hz = String.format(Locale.US, "%.0fHz", imuHz)
                SensorPillStatus("$hz OK", hz, SensorHudTone.GREEN)
            }
            imuHz > 0f -> {
                val hz = String.format(Locale.US, "%.0fHz", imuHz)
                SensorPillStatus(hz, hz, SensorHudTone.YELLOW)
            }
            isRecording -> SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
            isMonitoring -> SensorPillStatus("WAIT", "WAIT", SensorHudTone.YELLOW)
            else -> SensorPillStatus("OFF", "OFF", SensorHudTone.RED)
        }
    }

    fun evaluateCan(stats: CanedgeSyncStats): SensorPillStatus {
        return when (stats.hudPhase) {
            CanedgeHudPhase.OFFLINE -> SensorPillStatus("OFF", "OFF", SensorHudTone.RED)
            CanedgeHudPhase.ERROR -> SensorPillStatus("ERR", "ERR", SensorHudTone.RED)
            CanedgeHudPhase.OFFLINE_PENDING -> {
                val lbl = "${stats.pendingSessionsCount}P"
                SensorPillStatus(lbl, lbl, SensorHudTone.YELLOW)
            }
            CanedgeHudPhase.SCANNING -> SensorPillStatus("SCAN", "SCAN", SensorHudTone.YELLOW)
            CanedgeHudPhase.CALIBRATING -> SensorPillStatus("CAL", "CAL", SensorHudTone.YELLOW)
            CanedgeHudPhase.PAUSED_RECORDING -> SensorPillStatus("PAUSE", "PAUSE", SensorHudTone.YELLOW)
            CanedgeHudPhase.WAITING_FINAL_CHUNK -> SensorPillStatus("WAIT", "WAIT", SensorHudTone.YELLOW)
            CanedgeHudPhase.SYNCING -> {
                val pct = (stats.activeFileProgress * 100).toInt().coerceIn(0, 99)
                val full = if (stats.queueTotalFiles > 1) {
                    "$pct% ${stats.queueCompletedFiles + 1}/${stats.queueTotalFiles}"
                } else {
                    "$pct%"
                }
                SensorPillStatus(full, "$pct%", SensorHudTone.CYAN)
            }
            CanedgeHudPhase.CONNECTED_SYNCED -> SensorPillStatus("OK", "OK", SensorHudTone.GREEN)
        }
    }
}
