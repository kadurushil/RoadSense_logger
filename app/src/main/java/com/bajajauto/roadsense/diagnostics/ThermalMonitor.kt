package com.bajajauto.roadsense.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Thermal severity tiers derived from Android PowerManager Thermal API (API 29+)
 * with battery-temperature fallback for older HALs.
 * Policy: warn + log only (never alters camera preview or recording UX unexpectedly).
 */
enum class ThermalSeverity(val hudTag: String, val rank: Int) {
    NOMINAL("", 0),
    LIGHT("", 1),
    MODERATE("THRM", 2),
    SEVERE("HOT!", 3),
    CRITICAL("CRIT!", 4)
}

class ThermalMonitor(
    context: Context,
    private val onSeverityChanged: ((ThermalSeverity, Int?) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    var latestPmThermalStatus: Int? = null
        private set

    private var listener: Any? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                latestPmThermalStatus = powerManager.currentThermalStatus
                val cb = PowerManager.OnThermalStatusChangedListener { status ->
                    latestPmThermalStatus = status
                    val sev = classify(status, -1f)
                    onSeverityChanged?.invoke(sev, status)
                }
                powerManager.addThermalStatusListener(appContext.mainExecutor, cb)
                listener = cb
            } catch (_: Exception) {
            }
        }
    }

    fun currentSeverity(batteryTempC: Float): ThermalSeverity {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                powerManager.currentThermalStatus.also { latestPmThermalStatus = it }
            } catch (_: Exception) {
                latestPmThermalStatus
            }
        } else {
            latestPmThermalStatus
        }
        return classify(status, batteryTempC)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val cb = listener as? PowerManager.OnThermalStatusChangedListener
            if (cb != null) {
                try {
                    powerManager.removeThermalStatusListener(cb)
                } catch (_: Exception) {
                }
            }
        }
        listener = null
    }

    companion object {
        const val BATTERY_WARN_TEMP_C = 40.0f
        const val BATTERY_SEVERE_TEMP_C = 45.0f

        fun classify(pmThermalStatus: Int?, batteryTempC: Float): ThermalSeverity {
            val pmSeverity = when (pmThermalStatus) {
                null, 0 -> ThermalSeverity.NOMINAL // THERMAL_STATUS_NONE
                1 -> ThermalSeverity.LIGHT         // THERMAL_STATUS_LIGHT
                2 -> ThermalSeverity.MODERATE      // THERMAL_STATUS_MODERATE
                3 -> ThermalSeverity.SEVERE        // THERMAL_STATUS_SEVERE
                in 4..10 -> ThermalSeverity.CRITICAL // CRITICAL / EMERGENCY / SHUTDOWN
                else -> ThermalSeverity.NOMINAL
            }
            val batSeverity = when {
                batteryTempC >= BATTERY_SEVERE_TEMP_C -> ThermalSeverity.SEVERE
                batteryTempC >= BATTERY_WARN_TEMP_C -> ThermalSeverity.MODERATE
                else -> ThermalSeverity.NOMINAL
            }
            return if (pmSeverity.rank >= batSeverity.rank) pmSeverity else batSeverity
        }
    }
}
