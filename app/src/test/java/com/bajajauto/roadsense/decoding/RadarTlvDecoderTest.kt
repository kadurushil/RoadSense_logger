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

    @Test
    fun decode_20ByteTracksFrame6312_parsesAll4TracksCorrectlyWithoutPhantomOriginTracks() {
        // TLV Type 3 (Tracks): Type=3 (4B), Length=84 (4B), Descriptor (4B), 4x 20-byte tracks
        val hexTlv = "0300000054000000" + "04000700" +
                "7701a61a4d0007fd200134011f00600c03000000" +
                "c8fee212000073ff65001401dcff6d0c01000000" +
                "9200b322180005fd0801d7000900690c03000000" +
                "c1019811620037fd9d00d9003800630c03000000"

        val payload = ByteArray(hexTlv.length / 2) { i ->
            hexTlv.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 6312L,
            timeCpuCycles = 12345678L,
            numDetectedObj = 0,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))

        assertEquals(4, frame.tracks.size)

        // Track 0: x=2.93m, y=53.30m, tid=3168
        val t0 = frame.tracks[0]
        assertEquals(3168, t0.tid)
        assertEquals(2.93f, t0.x, 0.05f)
        assertEquals(53.30f, t0.y, 0.05f)
        assertEquals(-5.95f, t0.vy, 0.05f)

        // Track 1: x=-2.44m, y=37.77m, tid=3181
        val t1 = frame.tracks[1]
        assertEquals(3181, t1.tid)
        assertEquals(-2.44f, t1.x, 0.05f)
        assertEquals(37.77f, t1.y, 0.05f)

        // Track 2: x=1.14m, y=69.40m, tid=3177
        val t2 = frame.tracks[2]
        assertEquals(3177, t2.tid)
        assertEquals(1.14f, t2.x, 0.05f)
        assertEquals(69.40f, t2.y, 0.05f)

        // Track 3: x=3.51m, y=35.19m, tid=3171
        val t3 = frame.tracks[3]
        assertEquals(3171, t3.tid)
        assertEquals(3.51f, t3.x, 0.05f)
        assertEquals(35.19f, t3.y, 0.05f)

        // Ensure NO phantom tracks at origin (0, 0)
        for (track in frame.tracks) {
            assertTrue("Track #${track.tid} should not be near origin", Math.abs(track.x) > 0.5f || Math.abs(track.y) > 0.5f)
        }
    }
}
