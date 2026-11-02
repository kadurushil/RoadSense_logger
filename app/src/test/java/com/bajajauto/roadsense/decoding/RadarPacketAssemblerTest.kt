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
}

