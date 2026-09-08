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

            // Validate packet length
            if (totalPacketLen < RadarHeader.HEADER_SIZE_BYTES || totalPacketLen > MAX_PACKET_SIZE) {
                // Invalid packet length in header, discard magic word byte and continue searching
                val remaining = bytes.copyOfRange(magicIndex + 1, bytes.size)
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

            packets.add(RawRadarPacket(header, payload))

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

    private fun findMagicWord(bytes: ByteArray): Int {
        val magic = RadarHeader.MAGIC_WORD
        for (i in 0..(bytes.size - magic.size)) {
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

    fun reset() {
        buffer.reset()
    }
}
