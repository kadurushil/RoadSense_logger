package com.bajajauto.roadsense.canedge

import com.bajajauto.roadsense.canedge.ingestion.Mdf4HeaderInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class Mdf4HeaderInspectorTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun buildSyntheticMdf4Header(
        magic: String = "UnFinMF ",
        linkCount: Long = 6L,
        startTimeNs: Long = 1_795_000_000_123_000_000L
    ): ByteArray {
        val total = 64 + 24 + (linkCount.toInt() * 8) + 32
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(magic.toByteArray(StandardCharsets.US_ASCII))
        // Remainder of 64-byte IDBLOCK
        buf.position(64)
        // HDBLOCK magic + reserved + length + linkCount
        buf.put("##HD".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(0)
        buf.putLong(104L)
        buf.putLong(linkCount)
        repeat(linkCount.toInt()) { buf.putLong(0L) }
        buf.putLong(startTimeNs)
        return buf.array()
    }

    @Test
    fun inspectParsesUnfinalizedAndFinalizedHeaders() {
        val tsNs = 1_795_012_345_678_000_000L
        val unfinBytes = buildSyntheticMdf4Header(magic = "UnFinMF ", linkCount = 6L, startTimeNs = tsNs)
        val unfin = Mdf4HeaderInspector.inspect(unfinBytes)
        assertNotNull(unfin)
        assertFalse(unfin!!.isFinalized)
        assertEquals(6L, unfin.linkCount)
        assertEquals(tsNs, unfin.startTimeNs)
        assertEquals(tsNs / 1_000_000L, unfin.startTimeMs)

        val finBytes = buildSyntheticMdf4Header(magic = "MDF     ", linkCount = 6L, startTimeNs = tsNs)
        val fin = Mdf4HeaderInspector.inspect(finBytes)
        assertNotNull(fin)
        assertTrue(fin!!.isFinalized)
        assertEquals(tsNs / 1_000_000L, fin.startTimeMs)
    }

    @Test
    fun inspectReadsFromDiskFile() {
        val tsNs = 1_795_111_222_333_000_000L
        val file: File = tempFolder.newFile("00000115_00000008.MF4")
        file.writeBytes(buildSyntheticMdf4Header(startTimeNs = tsNs))

        val info = Mdf4HeaderInspector.inspect(file)
        assertNotNull(info)
        assertEquals(tsNs / 1_000_000L, info!!.startTimeMs)
    }

    @Test
    fun inspectRejectsCorruptOrTruncatedHeaders() {
        assertNull(Mdf4HeaderInspector.inspect(ByteArray(32)))
        val badMagic = buildSyntheticMdf4Header(magic = "NOTAMDF ")
        assertNull(Mdf4HeaderInspector.inspect(badMagic))

        val uninitializedClock = buildSyntheticMdf4Header(startTimeNs = 0L)
        assertNull(Mdf4HeaderInspector.inspect(uninitializedClock))

        val invalidLinks = buildSyntheticMdf4Header(linkCount = 999L, startTimeNs = 1_795_000_000_000_000_000L)
        // Truncate right after linkCount header field
        assertNull(Mdf4HeaderInspector.inspect(invalidLinks.copyOf(100)))
    }
}
