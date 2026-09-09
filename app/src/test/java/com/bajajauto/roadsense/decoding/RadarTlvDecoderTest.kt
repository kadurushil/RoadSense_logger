package com.bajajauto.roadsense.decoding

import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RadarTlvDecoderTest {

    private val decoder = RadarTlvDecoder()

    @Test
    fun decode_realAwr1843HardwarePayload_parsesPointsAndTracksCorrectly() {
        // Exact 464-byte payload from Frame #511 of real hardware capture raw_uart_20260909_094035.bin
        val base64Payload = "AQAAABgAAAACAAcAAADFF5n/nQEAAAAAKxek//kBAAADAAAAqAEAAB4ABwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val payloadBytes = Base64.getDecoder().decode(base64Payload)

        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 504,
            platform = 0xA1843L,
            frameNumber = 511L,
            timeCpuCycles = 12345678L,
            numDetectedObj = 2,
            numTLVs = 2,
            subFrameNumber = 0
        )

        val rawPacket = RawRadarPacket(header = header, payload = payloadBytes)
        val frame = decoder.decode(rawPacket)

        // 1. Validate Header preservation
        assertEquals(511L, frame.header.frameNumber)
        assertEquals(2, frame.header.numDetectedObj)

        // 2. Validate TLV 1 - Detected Points (Point Cloud)
        assertEquals(2, frame.points.size)

        // Point 0: doppler=0, peakVal=6085 -> SNR~71.5dB, xRaw=-103 -> x=-0.804m, yRaw=413 -> y=3.226m
        val p0 = frame.points[0]
        assertEquals(-0.8046875f, p0.x, 0.001f)
        assertEquals(3.2265625f, p0.y, 0.001f)
        assertEquals(0.0f, p0.z, 0.001f)
        assertEquals(0.0f, p0.doppler, 0.001f)
        assertTrue("Expected SNR > 70 dB, got ${p0.snrDb}", p0.snrDb > 70.0f)

        // Point 1: doppler=0, peakVal=5931 -> SNR~69.7dB, xRaw=-92 -> x=-0.718m, yRaw=505 -> y=3.945m
        val p1 = frame.points[1]
        assertEquals(-0.71875f, p1.x, 0.001f)
        assertEquals(3.9453125f, p1.y, 0.001f)
        assertEquals(0.0f, p1.z, 0.001f)
        assertEquals(0.0f, p1.doppler, 0.001f)
        assertTrue("Expected SNR > 68 dB, got ${p1.snrDb}", p1.snrDb > 68.0f)

        // 3. Validate TLV 3 - Inactive tracks filtered out
        assertEquals(0, frame.tracks.size)
    }

    @Test
    fun decode_frame545WithActiveTrackAndCluster_parsesSuccessfully() {
        // Frame #545 contains 4 points, 1 active track (TID=8), and 1 cluster
        val base64Payload = "AQAAACwAAAAEAAcAAADEF5f/nQEAAAAAJRek//kBAACl/4QQvP2/BgAApf+eEFj9+AYAAAMAAAC2AQAAHwAHAIr92wYAAKv/AAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAA4AAAABAAcAiv3bBh4Aq/8BAA=="
        val payloadBytes = Base64.getDecoder().decode(base64Payload)

        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 560,
            platform = 0xA1843L,
            frameNumber = 545L,
            timeCpuCycles = 12345678L,
            numDetectedObj = 4,
            numTLVs = 3,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payloadBytes))

        // 4 points in point cloud
        assertEquals(4, frame.points.size)

        // 1 active track (TID=8, X=-4.92m, Y=13.71m)
        assertEquals(1, frame.tracks.size)
        val track = frame.tracks[0]
        assertEquals(8, track.tid)
        assertEquals(-4.921875f, track.x, 0.01f)
        assertEquals(13.7109375f, track.y, 0.01f)
        assertEquals(-0.6640625f, track.vy, 0.01f)

        // 1 cluster
        assertEquals(1, frame.clusters.size)
        val cluster = frame.clusters[0]
        assertEquals(-4.921875f, cluster.x, 0.01f)
        assertEquals(13.7109375f, cluster.y, 0.01f)
    }

    @Test
    fun decode_emptyPayload_returnsEmptyListsGracefully() {
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40,
            platform = 0xA1843L,
            frameNumber = 1L,
            timeCpuCycles = 100L,
            numDetectedObj = 0,
            numTLVs = 0,
            subFrameNumber = 0
        )

        val rawPacket = RawRadarPacket(header = header, payload = byteArrayOf())
        val frame = decoder.decode(rawPacket)

        assertEquals(0, frame.points.size)
        assertEquals(0, frame.tracks.size)
        assertEquals(0, frame.clusters.size)
    }
}
