package com.bajajauto.roadsense.decoding

import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateful assembler that consumes raw UART byte chunks, searches for the TI magic word,
 * parses the 40-byte header, and emits complete RawRadarPackets.
 */
class RadarPacketAssembler {

    companion object {
        private const val MAX_PACKET_SIZE = 65536
    }

    private val buffer = ByteArrayOutputStream()

    /**
     * Appends new bytes and returns any fully assembled packets found in the stream.
     */
    @Synchronized
    fun appendBytes(incoming: ByteArray): List<RawRadarPacket> {
        buffer.write(incoming)
        val packets = mutableListOf<RawRadarPacket>()

        while (true) {
            val bytes = buffer.toByteArray()
            val magicIndex = findMagicWord(bytes)

            if (magicIndex == -1) {
                // Keep the last 7 bytes in case magic word was split across chunks
                if (bytes.size > 7) {
                    val keepBytes = bytes.copyOfRange(bytes.size - 7, bytes.size)
                    buffer.reset()
                    buffer.write(keepBytes)
                }
                break
            }

            // Discard any junk bytes prior to the magic word
            val availableAfterMagic = bytes.size - magicIndex
            if (availableAfterMagic < RadarHeader.HEADER_SIZE_BYTES) {
                // Need more bytes to complete the 40-byte header
                if (magicIndex > 0) {
                    val remaining = bytes.copyOfRange(magicIndex, bytes.size)
                    buffer.reset()
                    buffer.write(remaining)
                }
                break
            }

            // Parse header (40 bytes starting at magicIndex)
            val headerBuffer = ByteBuffer.wrap(bytes, magicIndex, RadarHeader.HEADER_SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)

            // Skip magic word (8 bytes)
            headerBuffer.long

            val version = headerBuffer.int.toLong() and 0xFFFFFFFFL
            val totalPacketLen = headerBuffer.int
            val platform = headerBuffer.int.toLong() and 0xFFFFFFFFL
            val frameNumber = headerBuffer.int.toLong() and 0xFFFFFFFFL
            val timeCpuCycles = headerBuffer.int.toLong() and 0xFFFFFFFFL
            val numDetectedObj = headerBuffer.int
            val numTLVs = headerBuffer.int
            val subFrameNumber = headerBuffer.int

            // Validate packet length and physical header boundaries (MSS v1.0.1 / Master Spec v4.0)
            if (totalPacketLen < RadarHeader.HEADER_SIZE_BYTES ||
                totalPacketLen > MAX_PACKET_SIZE ||
                totalPacketLen % 4 != 0 ||
                numTLVs !in 0..10 ||
                subFrameNumber !in 0..7 ||
                numDetectedObj !in 0..2048) {
                // Corrupted frame header rejected, discard magic word byte and continue searching
                val remaining = bytes.copyOfRange(magicIndex + 1, bytes.size)
                buffer.reset()
                buffer.write(remaining)
                continue
            }

            // Preemptive Magic Word Guard:
            // Check if another magic word arrives prematurely before totalPacketLen bytes.
            // If bytes were dropped on the UART bus, the tail of this frame is lost, and the next frame
            // has already started. We must discard the truncated frame immediately rather than swallowing
            // the next frame's header and magic word.
            val searchEnd = kotlin.math.min(bytes.size, magicIndex + totalPacketLen)
            val embeddedMagicIndex = findMagicWord(bytes, startIndex = magicIndex + RadarHeader.MAGIC_WORD.size)
            if (embeddedMagicIndex in (magicIndex + RadarHeader.MAGIC_WORD.size) until searchEnd) {
                val remaining = bytes.copyOfRange(embeddedMagicIndex, bytes.size)
                buffer.reset()
                buffer.write(remaining)
                continue
            }

            // Check if full packet is available
            if (availableAfterMagic < totalPacketLen) {
                // Need more bytes to complete the packet
                if (magicIndex > 0) {
                    val remaining = bytes.copyOfRange(magicIndex, bytes.size)
                    buffer.reset()
                    buffer.write(remaining)
                }
                break
            }

            // Extract header and payload
            val header = RadarHeader(
                version = version,
                totalPacketLen = totalPacketLen,
                platform = platform,
                frameNumber = frameNumber,
                timeCpuCycles = timeCpuCycles,
                numDetectedObj = numDetectedObj,
                numTLVs = numTLVs,
                subFrameNumber = subFrameNumber
            )

            val payloadSize = totalPacketLen - RadarHeader.HEADER_SIZE_BYTES
            val payload = if (payloadSize > 0) {
                bytes.copyOfRange(magicIndex + RadarHeader.HEADER_SIZE_BYTES, magicIndex + totalPacketLen)
            } else {
                byteArrayOf()
            }
            val fullPacket = bytes.copyOfRange(magicIndex, magicIndex + totalPacketLen)

            packets.add(RawRadarPacket(header, payload, hostTimestampNs = System.nanoTime(), fullPacketBytes = fullPacket))

            // Advance buffer past this completed packet
            val remainingOffset = magicIndex + totalPacketLen
            val remaining = if (remainingOffset < bytes.size) {
                bytes.copyOfRange(remainingOffset, bytes.size)
            } else {
                byteArrayOf()
            }
            buffer.reset()
            buffer.write(remaining)
        }

        return packets
    }

    fun findMagicWord(bytes: ByteArray, startIndex: Int = 0): Int {
        val magic = RadarHeader.MAGIC_WORD
        val limit = bytes.size - magic.size
        if (startIndex > limit) return -1
        for (i in startIndex..limit) {
            var match = true
            for (j in magic.indices) {
                if (bytes[i + j] != magic[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    @Synchronized
    fun reset() {
        buffer.reset()
    }
}
