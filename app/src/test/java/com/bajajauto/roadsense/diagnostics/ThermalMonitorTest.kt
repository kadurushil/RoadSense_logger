package com.bajajauto.roadsense.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalMonitorTest {

    @Test
    fun classifyCombinesPowerManagerStatusAndBatteryTempFallback() {
        assertEquals(ThermalSeverity.NOMINAL, ThermalMonitor.classify(0, 34.0f))
        assertEquals(ThermalSeverity.LIGHT, ThermalMonitor.classify(1, 36.0f))
        assertEquals(ThermalSeverity.MODERATE, ThermalMonitor.classify(2, 35.0f))
        assertEquals(ThermalSeverity.SEVERE, ThermalMonitor.classify(3, 35.0f))
        assertEquals(ThermalSeverity.CRITICAL, ThermalMonitor.classify(4, 35.0f))

        // API 28 or NONE OEM fallback using battery temperature thresholds
        assertEquals(ThermalSeverity.NOMINAL, ThermalMonitor.classify(null, 39.9f))
        assertEquals(ThermalSeverity.MODERATE, ThermalMonitor.classify(null, 40.0f))
        assertEquals(ThermalSeverity.SEVERE, ThermalMonitor.classify(0, 45.0f))
        assertEquals(ThermalSeverity.CRITICAL, ThermalMonitor.classify(4, 41.0f))
    }
}
