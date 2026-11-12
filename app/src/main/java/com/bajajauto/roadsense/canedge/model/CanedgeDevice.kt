package com.bajajauto.roadsense.canedge.model

import androidx.compose.runtime.Immutable

/**
 * Represents a verified physical CSS Electronics CANedge2 device on the local network.
 */
@Immutable
data class CanedgeDevice(
    val deviceId: String,
    val ipAddress: String,
    val hostname: String = deviceId,
    val port: Int = 80,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    val baseUrl: String
        get() = "http://$ipAddress:$port/"

    val apiBaseUrl: String
        get() = "http://$ipAddress:$port/api/"
}

/**
 * Reactive connection lifecycle state for CANedge2 logger.
 */
@Immutable
sealed interface CanedgeConnectionState {
    @Immutable
    data object Disconnected : CanedgeConnectionState

    @Immutable
    data class Scanning(
        val message: String,
        val progress: Float = 0f
    ) : CanedgeConnectionState

    @Immutable
    data class Connected(
        val device: CanedgeDevice,
        val latencyMs: Long = 0L
    ) : CanedgeConnectionState

    @Immutable
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : CanedgeConnectionState
}
