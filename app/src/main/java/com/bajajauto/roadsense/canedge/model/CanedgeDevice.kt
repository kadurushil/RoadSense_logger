package com.bajajauto.roadsense.canedge.model

/**
 * Represents a verified physical CSS Electronics CANedge2 device on the local network.
 */
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
sealed interface CanedgeConnectionState {
    data object Disconnected : CanedgeConnectionState

    data class Scanning(
        val message: String,
        val progress: Float = 0f
    ) : CanedgeConnectionState

    data class Connected(
        val device: CanedgeDevice,
        val latencyMs: Long = 0L
    ) : CanedgeConnectionState

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : CanedgeConnectionState
}
