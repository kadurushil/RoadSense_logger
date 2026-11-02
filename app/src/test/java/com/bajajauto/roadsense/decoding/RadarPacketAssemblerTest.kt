package com.bajajauto.roadsense.decoding

import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RadarPacketAssemblerTest {

    private val assembler = RadarPacketAssembler()

    private fun createFrameBytes(frameNumber: Long, totalPacketLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(totalPacketLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(RadarHeader.MAGIC_WORD)
        buf.putInt(0x01020003) // version
        buf.putInt(totalPacketLen)
        buf.putInt(0xA1843)
        buf.putInt(frameNumber.toInt())
        buf.putInt(50000)
        buf.putInt(0)
        buf.putInt(1)
        buf.putInt(0)
        val payloadLen = totalPacketLen - RadarHeader.HEADER_SIZE_BYTES
        if (payloadLen > 0) {
            buf.put(ByteArray(payloadLen))
        }
        return buf.array()
    }

    @Test
    fun appendBytes_validFrame_assemblesPacketSuccessfully() {
        val totalLen = 48
        val bytes = createFrameBytes(1001L, totalLen)

        val packets = assembler.appendBytes(bytes)
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
        val goodBytes = createFrameBytes(1007L, 48)

        val stream = badBuf.array() + goodBytes
        val packets = assembler.appendBytes(stream)

        assertEquals(1, packets.size)
        assertEquals(1007L, packets[0].header.frameNumber)
    }

    @Test
    fun appendBytes_truncatedFrameWithPrematureNextMagic_recoversNextFrameWithoutSwallowing() {
        // Frame 1 has a valid header claiming totalPacketLen = 120 bytes,
        // but only 64 bytes were received over UART before the tail was lost to buffer overflow.
        val truncatedFrame1 = createFrameBytes(2001L, 120).copyOfRange(0, 64)

        // Frame 2 follows immediately at byte 64 with a complete valid 48-byte packet
        val completeFrame2 = createFrameBytes(2002L, 48)

        // Stream concatenates truncated Frame 1 (64 bytes) with full Frame 2 (48 bytes) = 112 bytes total
        val stream = truncatedFrame1 + completeFrame2
        val packets = assembler.appendBytes(stream)

        assertEquals(1, packets.size)
        assertEquals(2002L, packets[0].header.frameNumber)
        assertEquals(48, packets[0].header.totalPacketLen)
    }

    @Test
    fun appendBytes_frameSplitAcrossMultipleChunks_assemblesSuccessfullyWhenComplete() {
        val totalLen = 64
        val allBytes = createFrameBytes(3001L, totalLen)
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
        val f1 = createFrameBytes(4001L, 48)
        val f2 = createFrameBytes(4002L, 48)

        val stream = f1 + f2
        val packets = assembler.appendBytes(stream)

        assertEquals(2, packets.size)
        assertEquals(4001L, packets[0].header.frameNumber)
        assertEquals(4002L, packets[1].header.frameNumber)
    }

    @Test
    fun appendBytes_splitMagicWordAcrossChunkBoundaryInTruncatedStream_recoversCleanly() {
        val f1Trunc = createFrameBytes(5001L, 120).copyOfRange(0, 50)
        val f2Full = createFrameBytes(5002L, 48)

        // Split Frame 2's magic word across chunk boundary (first 4 bytes in chunk1, next 4 bytes in chunk2)
        val chunk1 = f1Trunc + f2Full.copyOfRange(0, 4)
        val chunk2 = f2Full.copyOfRange(4, 48)

        val p1 = assembler.appendBytes(chunk1)
        assertEquals(0, p1.size)

        val p2 = assembler.appendBytes(chunk2)
        assertEquals(1, p2.size)
        assertEquals(5002L, p2[0].header.frameNumber)
        assertEquals(48, p2[0].header.totalPacketLen)
    }

    @Test
    fun appendBytes_extremeOneByteStreamingFragmentation_assemblesPacketSuccessfully() {
        val frame = createFrameBytes(6001L, 48)
        val packets = mutableListOf<RawRadarPacket>()

        for (b in frame) {
            packets.addAll(assembler.appendBytes(byteArrayOf(b)))
        }

        assertEquals(1, packets.size)
        assertEquals(6001L, packets[0].header.frameNumber)
    }

    @Test
    fun appendBytes_garbageBytesBetweenTruncatedAndNextFrame_skipsGarbageAndRecoversNextFrame() {
        val f1 = createFrameBytes(7001L, 120).copyOfRange(0, 45)
        val garbage = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x11, 0x22, 0x33)
        val f2 = createFrameBytes(7002L, 48)

        val packets = assembler.appendBytes(f1 + garbage + f2)
        assertEquals(1, packets.size)
        assertEquals(7002L, packets[0].header.frameNumber)
    }

    @Test
    fun appendBytes_multipleConsecutiveTruncatedFrames_survivesCascadeAndRecoversNextValidFrame() {
        val f1 = createFrameBytes(8001L, 120).copyOfRange(0, 50)
        val f2 = createFrameBytes(8002L, 120).copyOfRange(0, 60)
        val f3 = createFrameBytes(8003L, 48)

        val packets = assembler.appendBytes(f1 + f2 + f3)
        assertEquals(1, packets.size)
        assertEquals(8003L, packets[0].header.frameNumber)
    }

    @Test
    fun appendBytes_exactPacketBoundaryFollowedByNextFrame_doesNotFalselyTriggerTruncation() {
        val f1 = createFrameBytes(9001L, 48)
        val f2 = createFrameBytes(9002L, 48)

        val packets = assembler.appendBytes(f1 + f2)
        assertEquals(2, packets.size)
        assertEquals(9001L, packets[0].header.frameNumber)
        assertEquals(9002L, packets[1].header.frameNumber)
    }
}
