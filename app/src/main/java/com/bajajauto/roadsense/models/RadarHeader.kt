package com.bajajauto.roadsense.models

/**
 * Represents the 40-byte TI AWR1843 radar frame header.
 */
data class RadarHeader(
    val version: Long,
    val totalPacketLen: Int,
    val platform: Long,
    val frameNumber: Long,
    val timeCpuCycles: Long,
    val numDetectedObj: Int,
    val numTLVs: Int,
    val subFrameNumber: Int
) {
    companion object {
        const val HEADER_SIZE_BYTES = 40
        val MAGIC_WORD = byteArrayOf(
            0x02.toByte(), 0x01.toByte(), 0x04.toByte(), 0x03.toByte(),
            0x06.toByte(), 0x05.toByte(), 0x08.toByte(), 0x07.toByte()
        )
    }
}

/**
 * Holds an assembled raw radar packet containing the header, raw payload bytes, and host timestamp.
 */
data class RawRadarPacket(
    val header: RadarHeader,
    val payload: ByteArray,
    val hostTimestampNs: Long = System.nanoTime(),
    val fullPacketBytes: ByteArray = byteArrayOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        other as RawRadarPacket

        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        if (hostTimestampNs != other.hostTimestampNs) return false
        if (!fullPacketBytes.contentEquals(other.fullPacketBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hostTimestampNs.hashCode()
        result = 31 * result + fullPacketBytes.contentHashCode()
        return result
    }
}
