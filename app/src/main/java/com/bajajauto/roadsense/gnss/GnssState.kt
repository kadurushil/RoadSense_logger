package com.bajajauto.roadsense.gnss

/**
 * Lifecycle states of the GNSS location acquisition service.
 */
sealed class GnssState {
    object Disabled : GnssState()
    object Searching : GnssState()
    data class Active(
        val fix: GnssFix,
        val totalFixes: Long
    ) : GnssState()
    data class Error(val message: String) : GnssState()
}
