package com.bajajauto.roadsense.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageGuardTest {

    @Test
    fun evaluateEnforcesWarnAndCriticalStopBoundaries() {
        assertEquals(StorageHealth.OK, StorageGuard.evaluate(-1L).health)
        assertEquals(StorageHealth.OK, StorageGuard.evaluate(1500L).health)
        assertEquals(StorageHealth.LOW_WARN, StorageGuard.evaluate(1499L).health)
        assertEquals(StorageHealth.LOW_WARN, StorageGuard.evaluate(500L).health)
        assertEquals(StorageHealth.CRITICAL_STOP, StorageGuard.evaluate(499L).health)
        assertEquals(StorageHealth.CRITICAL_STOP, StorageGuard.evaluate(0L).health)
    }
}
