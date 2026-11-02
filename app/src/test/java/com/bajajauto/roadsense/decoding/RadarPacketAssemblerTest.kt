package com.bajajauto.roadsense.decoding

import com.bajajauto.roadsense.models.RadarHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RadarPacketAssemblerTest {

    private val assembler = RadarPacketAssembler()

    @Test
    fun appendBytes_validFrame_assemblesPacketSuccessfully() {
        val totalLen = 48
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003) // version
        buf.putInt(totalLen) // totalPacketLen
        buf.putInt(0xA1843) // platform
        buf.putInt(1001) // frameNumber
        buf.putInt(50000) // timeCpuCycles
        buf.putInt(0) // numDetectedObj
        buf.putInt(1) // numTLVs
        buf.putInt(0) // subFrameNumber
        buf.putLong(0L) // 8 bytes dummy payload

        val packets = assembler.appendBytes(buf.array())
        assertEquals(1, packets.size)
        assertEquals(1001L, packets[0].header.frameNumber)
        assertEquals(totalLen, packets[0].header.totalPacketLen)
    }

    @Test
    fun appendBytes_corruptedHeaderNumTLVsOutOfBounds_rejectsFrame() {
        val totalLen = 48
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003)
        buf.putInt(totalLen)
        buf.putInt(0xA1843)
        buf.putInt(1002)
        buf.putInt(50000)
        buf.putInt(0)
        buf.putInt(99) // numTLVs > 10 out of bounds!
        buf.putInt(0)
        buf.putLong(0L)

        val packets = assembler.appendBytes(buf.array())
        assertEquals(0, packets.size)
    }

    @Test
    fun appendBytes_corruptedHeaderSubframeOutOfBounds_rejectsFrame() {
        val totalLen = 48
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003)
        buf.putInt(totalLen)
        buf.putInt(0xA1843)
        buf.putInt(1003)
        buf.putInt(50000)
        buf.putInt(0)
        buf.putInt(1)
        buf.putInt(15) // subFrameNumber > 7 out of bounds!
        buf.putLong(0L)

        val packets = assembler.appendBytes(buf.array())
        assertEquals(0, packets.size)
    }

    @Test
    fun appendBytes_corruptedHeaderDetectedObjOutOfBounds_rejectsFrame() {
        val totalLen = 48
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003)
        buf.putInt(totalLen)
        buf.putInt(0xA1843)
        buf.putInt(1004)
        buf.putInt(50000)
        buf.putInt(5000) // numDetectedObj > 2048 out of bounds!
        buf.putInt(1)
        buf.putInt(0)
        buf.putLong(0L)

        val packets = assembler.appendBytes(buf.array())
        assertEquals(0, packets.size)
    }

    @Test
    fun appendBytes_corruptedHeaderUnalignedLength_rejectsFrame() {
        val totalLen = 45 // 45 % 4 != 0
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003)
        buf.putInt(totalLen)
        buf.putInt(0xA1843)
        buf.putInt(1005)
        buf.putInt(50000)
        buf.putInt(0)
        buf.putInt(1)
        buf.putInt(0)
        buf.put(ByteArray(5))

        val packets = assembler.appendBytes(buf.array())
        assertEquals(0, packets.size)
    }

    @Test
    fun appendBytes_corruptedFrameFollowedByValidFrame_resynchronizesSuccessfully() {
        // Corrupted frame (numTLVs = 50)
        val badBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        badBuf.put(RadarHeader.MAGIC_WORD)
        badBuf.putInt(0x01020003)
        badBuf.putInt(48)
        badBuf.putInt(0xA1843)
        badBuf.putInt(1006)
        badBuf.putInt(50000)
        badBuf.putInt(0)
        badBuf.putInt(50) // bad numTLVs
        badBuf.putInt(0)
        badBuf.putLong(0L)

        // Valid frame (frame 1007)
        val goodBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        goodBuf.put(RadarHeader.MAGIC_WORD)
        goodBuf.putInt(0x01020003)
        goodBuf.putInt(48)
        goodBuf.putInt(0xA1843)
        goodBuf.putInt(1007)
        goodBuf.putInt(60000)
        goodBuf.putInt(0)
        goodBuf.putInt(1)
        goodBuf.putInt(0)
        goodBuf.putLong(0L)

        val stream = badBuf.array() + goodBuf.array()
        val packets = assembler.appendBytes(stream)

        assertEquals(1, packets.size)
        assertEquals(1007L, packets[0].header.frameNumber)
    }

    @Test
    fun appendBytes_truncatedFrameWithPrematureNextMagic_recoversNextFrameWithoutSwallowing() {
        // Frame 1 has a valid header claiming totalPacketLen = 120 bytes,
        // but only 64 bytes were received over UART before the tail was lost to buffer overflow.
        val truncatedFrame1 = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        truncatedFrame1.put(RadarHeader.MAGIC_WORD)
        truncatedFrame1.putInt(0x01020003)
        truncatedFrame1.putInt(120) // Claims 120 bytes, but only 64 bytes present!
        truncatedFrame1.putInt(0xA1843)
        truncatedFrame1.putInt(2001) // Frame 2001
        truncatedFrame1.putInt(50000)
        truncatedFrame1.putInt(0)
        truncatedFrame1.putInt(1)
        truncatedFrame1.putInt(0)
        truncatedFrame1.put(ByteArray(24)) // 40 + 24 = 64 bytes

        // Frame 2 follows immediately at byte 64 with a complete valid 48-byte packet
        val completeFrame2 = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        completeFrame2.put(RadarHeader.MAGIC_WORD)
        completeFrame2.putInt(0x01020003)
        completeFrame2.putInt(48)
        completeFrame2.putInt(0xA1843)
        completeFrame2.putInt(2002) // Frame 2002
        completeFrame2.putInt(60000)
        completeFrame2.putInt(0)
        completeFrame2.putInt(1)
        completeFrame2.putInt(0)
        completeFrame2.putLong(0L) // 40 + 8 = 48 bytes

        // Stream concatenates truncated Frame 1 (64 bytes) with full Frame 2 (48 bytes) = 112 bytes total
        val stream = truncatedFrame1.array() + completeFrame2.array()
        val packets = assembler.appendBytes(stream)

        // Preemptive Magic Word Guard must detect Frame 2's magic word inside Frame 1's expected 120-byte window,
        // discard truncated Frame 1, and parse Frame 2 cleanly without swallowing its magic word or header!
        assertEquals(1, packets.size)
        assertEquals(2002L, packets[0].header.frameNumber)
        assertEquals(48, packets[0].header.totalPacketLen)
    }

    @Test
    fun appendBytes_frameSplitAcrossMultipleChunks_assemblesSuccessfullyWhenComplete() {
        val totalLen = 64
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003)
        buf.putInt(totalLen)
        buf.putInt(0xA1843)
        buf.putInt(3001)
        buf.putInt(50000)
        buf.putInt(0)
        buf.putInt(1)
        buf.putInt(0)
        buf.put(ByteArray(24))

        val allBytes = buf.array()
        val chunk1 = allBytes.copyOfRange(0, 30) // partial header/payload
        val chunk2 = allBytes.copyOfRange(30, totalLen) // remainder

        val packets1 = assembler.appendBytes(chunk1)
        assertEquals(0, packets1.size) // incomplete, should wait

        val packets2 = assembler.appendBytes(chunk2)
        assertEquals(1, packets2.size)
        assertEquals(3001L, packets2[0].header.frameNumber)
        assertEquals(totalLen, packets2[0].header.totalPacketLen)
    }

    @Test
    fun appendBytes_consecutiveValidFramesInSingleStream_assemblesAllCleanly() {
        val buf1 = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buf1.put(RadarHeader.MAGIC_WORD)
        buf1.putInt(0x01020003)
        buf1.putInt(48)
        buf1.putInt(0xA1843)
        buf1.putInt(4001)
        buf1.putInt(50000)
        buf1.putInt(0)
        buf1.putInt(1)
        buf1.putInt(0)
        buf1.putLong(0L)

        val buf2 = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buf2.put(RadarHeader.MAGIC_WORD)
        buf2.putInt(0x01020003)
        buf2.putInt(48)
        buf2.putInt(0xA1843)
        buf2.putInt(4002)
        buf2.putInt(60000)
        buf2.putInt(0)
        buf2.putInt(1)
        buf2.putInt(0)
        buf2.putLong(0L)

        val stream = buf1.array() + buf2.array()
        val packets = assembler.appendBytes(stream)

        assertEquals(2, packets.size)
        assertEquals(4001L, packets[0].header.frameNumber)
        assertEquals(4002L, packets[1].header.frameNumber)
    }
}
