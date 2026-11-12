package com.bajajauto.roadsense.gnss

import androidx.compose.runtime.Immutable

/**
 * Lifecycle states of the GNSS location acquisition service.
 */
@Immutable
sealed class GnssState {
    @Immutable
    object Disabled : GnssState()
    @Immutable
    object Searching : GnssState()
    @Immutable
    data class Active(
        val fix: GnssFix,
        val totalFixes: Long
    ) : GnssState()
    @Immutable
    data class Error(val message: String) : GnssState()
}
