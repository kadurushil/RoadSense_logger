package com.bajajauto.roadsense.decoding

import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance stateful assembler that consumes raw UART byte chunks, searches for the TI magic word,
 * parses the 40-byte header, and emits complete RawRadarPackets with Start-of-Frame (SOF) timestamps.
 *
 * Uses an in-memory linear sliding buffer to achieve zero-allocation byte ingestion and prevent ART GC sweeps.
 *
 * Dynamic Baud Rate Scaling:
 * Supports runtime baud rate updates (e.g., 3,125,000 bps -> 2,000,000 bps).
 * Automatically computes physical UART byte flight time (10 bits per byte in 8-N-1):
 * nanosPerByte = (10 * 1,000,000,000L) / baudRate
 * Microsecond-accurate Start-of-Frame interpolation is calculated from chunk arrival:
 * sofMonoNs = chunkMonoNs + (byteOffsetInChunk * nanosPerByte)
 */
class RadarPacketAssembler(
    initialBaudRate: Int = DEFAULT_BAUD_RATE
) {

    companion object {
        const val DEFAULT_BAUD_RATE = 3125000
        private const val MAX_PACKET_SIZE = 65536
        private const val INITIAL_BUFFER_CAPACITY = 131072 // 128 KB pre-allocated pool
    }

    var baudRate: Int = initialBaudRate
        set(value) {
            val valid = if (value > 0) value else DEFAULT_BAUD_RATE
            field = valid
            nanosPerByte = 10_000_000_000L / valid
        }

    var nanosPerByte: Long = 10_000_000_000L / initialBaudRate
        private set

    private var internalBuffer = ByteArray(INITIAL_BUFFER_CAPACITY)
    private var bufferSize = 0

    private class ChunkMeta(
        var startIndex: Int,
        var length: Int,
        var hostMonoNs: Long,
        var hostWallMs: Long
    )

    private val chunkWindows = java.util.ArrayDeque<ChunkMeta>()

    /**
     * Appends new bytes and returns any fully assembled packets found in the stream.
     * Each returned RawRadarPacket carries its physical Start-of-Frame (SOF) host timestamps.
     */
    @Synchronized
    fun appendBytes(
        incoming: ByteArray,
        chunkMonoNs: Long = android.os.SystemClock.elapsedRealtimeNanos(),
        chunkWallMs: Long = System.currentTimeMillis()
    ): List<RawRadarPacket> {
        if (incoming.isEmpty()) return emptyList()

        if (bufferSize + incoming.size > internalBuffer.size) {
            // Dynamically expand buffer if unusually large frame burst arrives
            val newCapacity = (bufferSize + incoming.size) * 2
            internalBuffer = internalBuffer.copyOf(newCapacity)
        }
        System.arraycopy(incoming, 0, internalBuffer, bufferSize, incoming.size)

        chunkWindows.addLast(ChunkMeta(bufferSize, incoming.size, chunkMonoNs, chunkWallMs))
        bufferSize += incoming.size

        val packets = mutableListOf<RawRadarPacket>()

        while (true) {
            val magicIndex = findMagicWord(internalBuffer, startIndex = 0, length = bufferSize)

            if (magicIndex == -1) {
                // Keep the last 7 bytes in case magic word was split across chunks
                if (bufferSize > 7) {
                    val discard = bufferSize - 7
                    System.arraycopy(internalBuffer, bufferSize - 7, internalBuffer, 0, 7)
                    bufferSize = 7
                    shiftChunkWindows(discard)
                }
                break
            }

            // Discard any junk bytes prior to the magic word
            val availableAfterMagic = bufferSize - magicIndex
            if (availableAfterMagic < RadarHeader.HEADER_SIZE_BYTES) {
                // Need more bytes to complete the 40-byte header
                if (magicIndex > 0) {
                    System.arraycopy(internalBuffer, magicIndex, internalBuffer, 0, availableAfterMagic)
                    bufferSize = availableAfterMagic
                    shiftChunkWindows(magicIndex)
                }
                break
            }

            // Parse header (40 bytes starting at magicIndex)
            val headerBuffer = ByteBuffer.wrap(internalBuffer, magicIndex, RadarHeader.HEADER_SIZE_BYTES)
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
                val remainingLen = bufferSize - (magicIndex + 1)
                System.arraycopy(internalBuffer, magicIndex + 1, internalBuffer, 0, remainingLen)
                bufferSize = remainingLen
                shiftChunkWindows(magicIndex + 1)
                continue
            }

            // Preemptive Magic Word Guard & Intelligent Frame Salvaging:
            val searchEnd = kotlin.math.min(bufferSize, magicIndex + totalPacketLen)
            val embeddedMagicIndex = findMagicWord(
                internalBuffer,
                startIndex = magicIndex + RadarHeader.MAGIC_WORD.size,
                length = searchEnd
            )
            if (embeddedMagicIndex in (magicIndex + RadarHeader.MAGIC_WORD.size) until searchEnd) {
                val salvagedLen = embeddedMagicIndex - magicIndex
                if (salvagedLen >= RadarHeader.HEADER_SIZE_BYTES) {
                    val salvagedPayload = internalBuffer.copyOfRange(
                        magicIndex + RadarHeader.HEADER_SIZE_BYTES,
                        embeddedMagicIndex
                    )
                    val salvagedFullPacket = internalBuffer.copyOfRange(
                        magicIndex,
                        embeddedMagicIndex
                    )
                    val salvagedHeader = RadarHeader(
                        version = version,
                        totalPacketLen = salvagedLen,
                        platform = platform,
                        frameNumber = frameNumber,
                        timeCpuCycles = timeCpuCycles,
                        numDetectedObj = numDetectedObj,
                        numTLVs = numTLVs,
                        subFrameNumber = subFrameNumber
                    )
                    val (sofMonoNs, sofWallMs) = getTimestampForIndex(magicIndex)
                    packets.add(
                        RawRadarPacket(
                            header = salvagedHeader,
                            payload = salvagedPayload,
                            hostTimestampNs = sofMonoNs,
                            wallClockTimestampMs = sofWallMs,
                            fullPacketBytes = salvagedFullPacket,
                            isSalvaged = true
                        )
                    )
                }

                val remainingLen = bufferSize - embeddedMagicIndex
                System.arraycopy(internalBuffer, embeddedMagicIndex, internalBuffer, 0, remainingLen)
                bufferSize = remainingLen
                shiftChunkWindows(embeddedMagicIndex)
                continue
            }

            // Check if full packet is available
            if (availableAfterMagic < totalPacketLen) {
                // Need more bytes to complete the packet
                if (magicIndex > 0) {
                    System.arraycopy(internalBuffer, magicIndex, internalBuffer, 0, availableAfterMagic)
                    bufferSize = availableAfterMagic
                    shiftChunkWindows(magicIndex)
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
                internalBuffer.copyOfRange(magicIndex + RadarHeader.HEADER_SIZE_BYTES, magicIndex + totalPacketLen)
            } else {
                byteArrayOf()
            }
            val fullPacket = internalBuffer.copyOfRange(magicIndex, magicIndex + totalPacketLen)

            val (sofMonoNs, sofWallMs) = getTimestampForIndex(magicIndex)
            packets.add(
                RawRadarPacket(
                    header = header,
                    payload = payload,
                    hostTimestampNs = sofMonoNs,
                    wallClockTimestampMs = sofWallMs,
                    fullPacketBytes = fullPacket
                )
            )

            // Advance buffer past this completed packet
            val remainingOffset = magicIndex + totalPacketLen
            val remainingLen = bufferSize - remainingOffset
            if (remainingLen > 0) {
                System.arraycopy(internalBuffer, remainingOffset, internalBuffer, 0, remainingLen)
                bufferSize = remainingLen
            } else {
                bufferSize = 0
            }
            shiftChunkWindows(remainingOffset)
        }

        return packets
    }

    fun findMagicWord(bytes: ByteArray, startIndex: Int = 0, length: Int = bytes.size): Int {
        val magic = RadarHeader.MAGIC_WORD
        val limit = length - magic.size
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

    private fun getTimestampForIndex(targetIndex: Int): Pair<Long, Long> {
        for (chunk in chunkWindows) {
            if (targetIndex in chunk.startIndex until (chunk.startIndex + chunk.length)) {
                val offsetInChunk = (targetIndex - chunk.startIndex).toLong()
                val interpolatedNs = chunk.hostMonoNs + (offsetInChunk * nanosPerByte)
                val interpolatedMs = chunk.hostWallMs + (offsetInChunk * nanosPerByte / 1_000_000L)
                return Pair(interpolatedNs, interpolatedMs)
            }
        }
        val first = chunkWindows.firstOrNull()
        return if (first != null) {
            Pair(first.hostMonoNs, first.hostWallMs)
        } else {
            Pair(android.os.SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        }
    }

    private fun shiftChunkWindows(discardBytes: Int) {
        if (discardBytes <= 0) return
        var remainingDiscard = discardBytes
        while (chunkWindows.isNotEmpty() && remainingDiscard > 0) {
            val first = chunkWindows.first()
            if (first.length <= remainingDiscard) {
                remainingDiscard -= first.length
                chunkWindows.removeFirst()
            } else {
                first.length -= remainingDiscard
                first.hostMonoNs += remainingDiscard.toLong() * nanosPerByte
                first.hostWallMs += (remainingDiscard.toLong() * nanosPerByte) / 1_000_000L
                remainingDiscard = 0
            }
        }
        var currentIdx = 0
        for (chunk in chunkWindows) {
            chunk.startIndex = currentIdx
            currentIdx += chunk.length
        }
    }

    @Synchronized
    fun reset() {
        bufferSize = 0
        chunkWindows.clear()
    }
}
