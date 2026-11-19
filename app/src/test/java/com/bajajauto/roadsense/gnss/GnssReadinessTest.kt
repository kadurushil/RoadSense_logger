package com.bajajauto.roadsense.gnss

import android.location.LocationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssReadinessTest {

    @Test
    fun networkProviderWithZeroSatellitesNeverCountsAsTrueGpsLock() {
        assertFalse(
            GnssLocationManager.hasTrueSatelliteLock(
                provider = LocationManager.NETWORK_PROVIDER,
                satellitesUsed = 0,
                gnssHz = 1.0f
            )
        )
    }

    @Test
    fun gpsProviderNeedsAtLeastFourSatellitesAndLiveRateForLock() {
        assertFalse(
            GnssLocationManager.hasTrueSatelliteLock(
                provider = LocationManager.GPS_PROVIDER,
                satellitesUsed = 2,
                gnssHz = 1.0f
            )
        )
        assertFalse(
            GnssLocationManager.hasTrueSatelliteLock(
                provider = LocationManager.GPS_PROVIDER,
                satellitesUsed = 8,
                gnssHz = 0.0f
            )
        )
        assertTrue(
            GnssLocationManager.hasTrueSatelliteLock(
                provider = LocationManager.GPS_PROVIDER,
                satellitesUsed = 6,
                gnssHz = 1.0f
            )
        )
    }
}
