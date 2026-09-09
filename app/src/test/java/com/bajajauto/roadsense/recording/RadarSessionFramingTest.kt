package com.bajajauto.roadsense.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class RadarSessionFramingTest {

    @Test
    fun testFramedPacketBinaryFormat() {
        val magic = RadarSessionRecorder.FRAME_MAGIC
        val monoNs = 123456789012345L
        val wallMs = 1788935400123L
        val mockRadarPayload = byteArrayOf(0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07, 0x10, 0x20, 0x30, 0x40)

        // Write frame using DataOutputStream
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(magic)
        dos.writeLong(monoNs)
        dos.writeLong(wallMs)
        dos.writeInt(mockRadarPayload.size)
        dos.write(mockRadarPayload)
        dos.flush()

        val writtenBytes = baos.toByteArray()
        assertEquals(RadarSessionRecorder.HEADER_SIZE + mockRadarPayload.size, writtenBytes.size)

        // Read and verify frame using DataInputStream
        val bais = ByteArrayInputStream(writtenBytes)
        val dis = DataInputStream(bais)

        val readMagic = ByteArray(4)
        dis.readFully(readMagic)
        assertArrayEquals(magic, readMagic)

        val readMonoNs = dis.readLong()
        assertEquals(monoNs, readMonoNs)

        val readWallMs = dis.readLong()
        assertEquals(wallMs, readWallMs)

        val readLen = dis.readInt()
        assertEquals(mockRadarPayload.size, readLen)

        val readPayload = ByteArray(readLen)
        dis.readFully(readPayload)
        assertArrayEquals(mockRadarPayload, readPayload)
    }
}
