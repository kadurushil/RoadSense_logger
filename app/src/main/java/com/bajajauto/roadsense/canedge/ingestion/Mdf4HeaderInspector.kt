package com.bajajauto.roadsense.canedge.ingestion

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Lightweight, zero-dependency MDF4 header inspector.
 *
 * Reads the 64-byte IDBLOCK and the HDBLOCK header prefix from a CSS Electronics CANedge2
 * `.MF4` file to determine whether the file is finalized ("MDF     " vs "UnFinMF ") and extract
 * the exact HDBLOCK `hd_start_time_ns` (nanoseconds since Unix epoch, little-endian uint64).
 */
data class Mdf4HeaderInfo(
    val magic: String,
    val isFinalized: Boolean,
    val linkCount: Long,
    val startTimeNs: Long,
    val startTimeMs: Long
)

object Mdf4HeaderInspector {
    private const val IDBLOCK_SIZE = 64
    private const val HDBLOCK_MAGIC = "##HD"
    private const val MIN_INSPECT_BYTES = 256
    private const val MAX_REASONABLE_LINKS = 64L
    // Jan 1, 2000 00:00:00 UTC in ms — guards against uninitialized 0 or corrupt RTC timestamps.
    private const val MIN_VALID_EPOCH_MS = 946_684_800_000L

    /**
     * Inspect an MDF4 header directly from a local file without loading the full recording.
     */
    fun inspect(file: File): Mdf4HeaderInfo? {
        if (!file.exists() || !file.isFile || file.length() < IDBLOCK_SIZE + 32) {
            return null
        }
        return try {
            val len = file.length().coerceAtMost(MIN_INSPECT_BYTES.toLong()).toInt()
            val buf = ByteArray(len)
            RandomAccessFile(file, "r").use { raf ->
                val read = raf.read(buf)
                if (read < IDBLOCK_SIZE + 32) return null
                inspect(buf, read)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Inspect an MDF4 header from an in-memory byte array (pure JVM testable).
     */
    fun inspect(bytes: ByteArray, validLength: Int = bytes.size): Mdf4HeaderInfo? {
        if (validLength < IDBLOCK_SIZE + 32 || bytes.size < validLength) return null

        val magic = String(bytes, 0, 8, StandardCharsets.US_ASCII)
        val isFinalized = when (magic) {
            "MDF     " -> true
            "UnFinMF " -> false
            else -> return null
        }

        val hdMagic = String(bytes, IDBLOCK_SIZE, 4, StandardCharsets.US_ASCII)
        if (hdMagic != HDBLOCK_MAGIC) return null

        // MDF4 HDBLOCK layout starting at byte 64:
        // +0..3   : "##HD"
        // +4..7   : reserved (4 bytes)
        // +8..15  : length (uint64 LE)
        // +16..23 : link_count (uint64 LE)
        // +24..   : link_count * 8 bytes of link pointers
        // +24 + link_count * 8 : hd_start_time_ns (uint64 LE, ns since 1970-01-01 UTC)
        val linkCount = readInt64LE(bytes, IDBLOCK_SIZE + 16)
        if (linkCount < 0L || linkCount > MAX_REASONABLE_LINKS) return null

        val timeOffset = IDBLOCK_SIZE + 24 + (linkCount.toInt() * 8)
        if (timeOffset + 8 > validLength) return null

        val startTimeNs = readInt64LE(bytes, timeOffset)
        if (startTimeNs <= 0L) return null

        val startTimeMs = startTimeNs / 1_000_000L
        if (startTimeMs < MIN_VALID_EPOCH_MS) return null

        return Mdf4HeaderInfo(
            magic = magic,
            isFinalized = isFinalized,
            linkCount = linkCount,
            startTimeNs = startTimeNs,
            startTimeMs = startTimeMs
        )
    }

    private fun readInt64LE(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return result
    }
}
