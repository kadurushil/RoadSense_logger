package com.bajajauto.roadsense.decoding

import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    @Test
    fun decode_12BytePoints_parsesClusterIdAndOutlierFlagCorrectly() {
        // TLV 1: Type=1 (4B), Length=28 (4B), Descriptor (4B: numPoints=2, qFormat=7), 2x 12-byte points
        val buf = ByteBuffer.allocate(8 + 4 + 2 * 12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(1) // Type: Points
        buf.putInt(4 + 2 * 12) // Length: 28 bytes
        buf.putShort(2.toShort()) // numPoints: 2
        buf.putShort(7.toShort()) // qFormat: 7 (factor = 128)

        // Point 0: doppler=128 (1.0m/s), peakVal=5120 (60.2dB), x=256 (2.0m), y=512 (4.0m), z=128 (1.0m), clusterId=5, isOutlier=0
        buf.putShort(128.toShort())
        buf.putShort(5120.toShort())
        buf.putShort(256.toShort())
        buf.putShort(512.toShort())
        buf.putShort(128.toShort())
        buf.put(5.toByte())
        buf.put(0.toByte())

        // Point 1: doppler=-128 (-1.0m/s), peakVal=2560 (30.1dB), x=-128 (-1.0m), y=1024 (8.0m), z=0 (0m), clusterId=12, isOutlier=1
        buf.putShort((-128).toShort())
        buf.putShort(2560.toShort())
        buf.putShort((-128).toShort())
        buf.putShort(1024.toShort())
        buf.putShort(0.toShort())
        buf.put(12.toByte())
        buf.put(1.toByte())

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 100L,
            timeCpuCycles = 123456L,
            numDetectedObj = 2,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        assertEquals(2, frame.points.size)

        val p0 = frame.points[0]
        assertEquals(2.0f, p0.x, 0.001f)
        assertEquals(4.0f, p0.y, 0.001f)
        assertEquals(1.0f, p0.z, 0.001f)
        assertEquals(1.0f, p0.doppler, 0.001f)
        assertEquals(5, p0.clusterId)
        assertFalse(p0.isOutlier)
        assertEquals(60.206f, p0.snrDb, 0.1f)

        val p1 = frame.points[1]
        assertEquals(-1.0f, p1.x, 0.001f)
        assertEquals(8.0f, p1.y, 0.001f)
        assertEquals(0.0f, p1.z, 0.001f)
        assertEquals(-1.0f, p1.doppler, 0.001f)
        assertEquals(12, p1.clusterId)
        assertTrue(p1.isOutlier)
    }

    @Test
    fun decode_16ByteClusters_parsesAllMetadataCorrectly() {
        // TLV 2: Type=2 (4B), Length=36 (4B), Descriptor (4B: numClusters=2, qFormat=7), 2x 16-byte clusters
        val buf = ByteBuffer.allocate(8 + 4 + 2 * 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(2) // Type: Clusters
        buf.putInt(4 + 2 * 16) // Length: 36 bytes
        buf.putShort(2.toShort()) // numClusters: 2
        buf.putShort(7.toShort()) // qFormat: 7

        // Cluster 0: x=256 (2m), y=1280 (10m), xSize=128 (1m), ySize=256 (2m), cid=12, numPoints=18, outlier=0, stat=1, dz=0, pad=0
        buf.putShort(256.toShort())
        buf.putShort(1280.toShort())
        buf.putShort(128.toShort())
        buf.putShort(256.toShort())
        buf.putShort(12.toShort())
        buf.putShort(18.toShort())
        buf.put(0.toByte()) // isOutlier
        buf.put(1.toByte()) // isStationary
        buf.put(0.toByte()) // isDeadZone
        buf.put(0.toByte()) // reserved

        // Cluster 1: x=-384 (-3m), y=1920 (15m), xSize=256 (2m), ySize=512 (4m), cid=15, numPoints=25, outlier=1, stat=0, dz=1, pad=0
        buf.putShort((-384).toShort())
        buf.putShort(1920.toShort())
        buf.putShort(256.toShort())
        buf.putShort(512.toShort())
        buf.putShort(15.toShort())
        buf.putShort(25.toShort())
        buf.put(1.toByte()) // isOutlier
        buf.put(0.toByte()) // isStationary
        buf.put(1.toByte()) // isDeadZone
        buf.put(0.toByte()) // reserved

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 101L,
            timeCpuCycles = 123456L,
            numDetectedObj = 0,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        assertEquals(2, frame.clusters.size)

        val c0 = frame.clusters[0]
        assertEquals(2.0f, c0.x, 0.01f)
        assertEquals(10.0f, c0.y, 0.01f)
        assertEquals(1.0f, c0.xSize, 0.01f)
        assertEquals(2.0f, c0.ySize, 0.01f)
        assertEquals(12, c0.cid)
        assertEquals(18, c0.numPoints)
        assertFalse(c0.isOutlier)
        assertTrue(c0.isStationary)
        assertFalse(c0.isDeadZone)

        val c1 = frame.clusters[1]
        assertEquals(-3.0f, c1.x, 0.01f)
        assertEquals(15.0f, c1.y, 0.01f)
        assertEquals(2.0f, c1.xSize, 0.01f)
        assertEquals(4.0f, c1.ySize, 0.01f)
        assertEquals(15, c1.cid)
        assertEquals(25, c1.numPoints)
        assertTrue(c1.isOutlier)
        assertFalse(c1.isStationary)
        assertTrue(c1.isDeadZone)
    }

    @Test
    fun decode_28ByteTracks_parsesRichAttributesCorrectly() {
        // TLV 3: Type=3 (4B), Length=32 (4B), Descriptor (4B: numTracks=1, qFormat=7), 1x 28-byte track
        val buf = ByteBuffer.allocate(8 + 4 + 28).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(3) // Type: Tracks
        buf.putInt(4 + 28) // Length: 32 bytes
        buf.putShort(1.toShort()) // numTracks: 1
        buf.putShort(7.toShort()) // qFormat: 7

        // Track:
        // x=128 (1.0m), y=2560 (20.0m), vx=-64 (-0.5m/s), vy=-1280 (-10.0m/s)
        // majorSize=512 (4.0m), minorSize=256 (2.0m), orientation=450 (45.0 deg)
        // tid=42, state=3, clusterId=7, tti=250 (2.50s), risk=2, isStationary=0, ttcCategory=3, confidence=95%, reserved=0
        buf.putShort(128.toShort())
        buf.putShort(2560.toShort())
        buf.putShort((-64).toShort())
        buf.putShort((-1280).toShort())
        buf.putShort(512.toShort())
        buf.putShort(256.toShort())
        buf.putShort(450.toShort())
        buf.putShort(42.toShort())
        buf.putShort(3.toShort())
        buf.putShort(7.toShort())
        buf.putShort(250.toShort())
        buf.put(2.toByte()) // risk
        buf.put(0.toByte()) // isStationary
        buf.put(3.toByte()) // ttcCategory
        buf.put(95.toByte()) // confidencePct
        buf.putShort(0.toShort()) // reserved

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 102L,
            timeCpuCycles = 123456L,
            numDetectedObj = 0,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        assertEquals(1, frame.tracks.size)

        val t = frame.tracks[0]
        assertEquals(42, t.tid)
        assertEquals(1.0f, t.x, 0.01f)
        assertEquals(20.0f, t.y, 0.01f)
        assertEquals(-0.5f, t.vx, 0.01f)
        assertEquals(-10.0f, t.vy, 0.01f)
        assertEquals(4.0f, t.majorSize, 0.01f)
        assertEquals(2.0f, t.minorSize, 0.01f)
        assertEquals(45.0f, t.orientationDeg, 0.01f)
        assertEquals(3, t.state)
        assertEquals(7, t.clusterId)
        assertEquals(2.50f, t.ttiSec, 0.01f)
        assertEquals(2, t.risk)
        assertFalse(t.isStationary)
        assertEquals(3, t.ttcCategory)
        assertEquals(95, t.confidencePct)
    }

    @Test
    fun decode_48ByteDiagnostics_parsesAllFieldsCorrectly() {
        // TLV 4: Type=4 (4B), Length=48 (4B), followed by 48 bytes of payload
        val buf = ByteBuffer.allocate(8 + 48).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(4) // Type: Tracker Diagnostics
        buf.putInt(48) // Length: 48 bytes

        buf.putShort(15.toShort()) // numInliers: 15
        buf.put(1.toByte()) // ransacSuccessful: true
        buf.put(2.toByte()) // motionState: 2 (Forward Moving)
        buf.putFloat(0.15f) // filteredVxIir
        buf.putFloat(12.34f) // filteredVyIir
        buf.putFloat(12.5f) // egoEkfVy
        buf.putFloat(0.2f) // egoEkfVx
        buf.putFloat(0.05f) // egoEkfAx
        buf.putFloat(-0.3f) // egoEkfAy
        buf.putFloat(0.012f) // egoEkfYawRate
        buf.putFloat(-3.75f) // roadBoundaryLeftX
        buf.putFloat(3.75f) // roadBoundaryRightX
        buf.putFloat(0.1f) // axDynamics
        buf.putShort(2350.toShort()) // trackerProcTimeUs: 2350 us
        buf.put(0.toByte()) // imuStuckFlag: false
        buf.put(0.toByte()) // padding

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 103L,
            timeCpuCycles = 123456L,
            numDetectedObj = 0,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        val diag = frame.diagnostics
        assertNotNull(diag)
        assertEquals(15, diag!!.numInliers)
        assertTrue(diag.ransacSuccessful)
        assertEquals(2, diag.motionState)
        assertEquals(0.15f, diag.filteredVxIir, 0.001f)
        assertEquals(12.34f, diag.filteredVyIir, 0.001f)
        assertEquals(12.5f, diag.egoEkfVy, 0.001f)
        assertEquals(0.2f, diag.egoEkfVx, 0.001f)
        assertEquals(0.05f, diag.egoEkfAx, 0.001f)
        assertEquals(-0.3f, diag.egoEkfAy, 0.001f)
        assertEquals(0.012f, diag.egoEkfYawRate, 0.001f)
        assertEquals(-3.75f, diag.roadBoundaryLeftX, 0.001f)
        assertEquals(3.75f, diag.roadBoundaryRightX, 0.001f)
        assertEquals(0.1f, diag.axDynamics, 0.001f)
        assertEquals(2350, diag.trackerProcTimeUs)
        assertFalse(diag.imuStuckFlag)
    }

    @Test
    fun decode_56ByteCanInputs_parsesAllVehicleDynamicsCorrectly() {
        // TLV 5: Type=5 (4B), Length=56 (4B), followed by 56 bytes of payload
        val buf = ByteBuffer.allocate(8 + 56).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(5) // Type: CAN Inputs
        buf.putInt(56) // Length: 56 bytes

        buf.putFloat(45.5f) // speedKmph
        buf.putFloat(0.015f) // yawRateRadps
        buf.putFloat(0.002f) // pitchRateRadps
        buf.putFloat(-0.001f) // rollRateRadps
        buf.putFloat(0.05f) // accelXMps2
        buf.putFloat(1.25f) // accelYMps2
        buf.putFloat(1.20f) // accelAvgMps2
        buf.putFloat(1.5f) // roadGradeDeg
        buf.putFloat(32.0f) // motorTorqueNm
        buf.putFloat(0.8f) // rollCfDeg
        buf.putFloat(0.9f) // yawCfDeg
        buf.putInt(987654321) // timestampMs
        buf.put(3.toByte()) // gear: 3
        buf.put(1.toByte()) // brakeStatus: 1
        buf.put(2.toByte()) // motionState: 2
        buf.put(1.toByte()) // isVcuCanValid: 1
        buf.put(0.toByte()) // imuStuckFlag: 0
        buf.put(0.toByte()) // padding 1
        buf.put(0.toByte()) // padding 2
        buf.put(0.toByte()) // padding 3

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 104L,
            timeCpuCycles = 123456L,
            numDetectedObj = 0,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        val canIn = frame.canInputs
        assertNotNull(canIn)
        assertEquals(45.5f, canIn!!.speedKmph, 0.01f)
        assertEquals(0.015f, canIn.yawRateRadps, 0.001f)
        assertEquals(0.002f, canIn.pitchRateRadps, 0.001f)
        assertEquals(-0.001f, canIn.rollRateRadps, 0.001f)
        assertEquals(0.05f, canIn.accelXMps2, 0.001f)
        assertEquals(1.25f, canIn.accelYMps2, 0.001f)
        assertEquals(1.20f, canIn.accelAvgMps2, 0.001f)
        assertEquals(1.5f, canIn.roadGradeDeg, 0.01f)
        assertEquals(32.0f, canIn.motorTorqueNm, 0.01f)
        assertEquals(0.8f, canIn.rollCfDeg, 0.01f)
        assertEquals(0.9f, canIn.yawCfDeg, 0.01f)
        assertEquals(987654321L, canIn.timestampMs)
        assertEquals(3, canIn.gear)
        assertEquals(1, canIn.brakeStatus)
        assertEquals(2, canIn.motionState)
        assertTrue(canIn.isVcuCanValid)
        assertFalse(canIn.imuStuckFlag)
    }

    @Test
    fun decode_24ByteCanOutputs_parsesFcwBsdAccCorrectly() {
        // TLV 6: Type=6 (4B), Length=24 (4B), followed by 3x 8-byte frames
        val buf = ByteBuffer.allocate(8 + 24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(6) // Type: CAN Outputs
        buf.putInt(24) // Length: 24 bytes

        // FCW (8 bytes): stage=2 (b0=2), trackId=105 (b1=105), ttc=2.5s (b2=25), targetY=20m (b3=40),
        // targetX=0m (b4=128), targetVy=0m/s (b5=128), targetVx=0m/s (b6=128), reserved=0 (b7=0)
        buf.put(2.toByte())
        buf.put(105.toByte())
        buf.put(25.toByte())
        buf.put(40.toByte())
        buf.put(128.toByte())
        buf.put(128.toByte())
        buf.put(128.toByte())
        buf.put(0.toByte())

        // BSD (8 bytes): leftActive=1, rightActive=0, warningLevel=2, approachTtc=1.8s (b3=18), reserved 4 bytes
        buf.put(1.toByte())
        buf.put(0.toByte())
        buf.put(2.toByte())
        buf.put(18.toByte())
        buf.put(0.toByte())
        buf.put(0.toByte())
        buf.put(0.toByte())
        buf.put(0.toByte())

        // ACC (8 bytes): poiId=42 (b0=0, b1=42), targetY=25m (b2=50), targetX=0m (b3=128),
        // targetVy=-10m/s (b4=108 -> 108*0.5 - 64 = -10), tti=2.5s (b5=25), vSafe=15m/s (b6=150), aRef=0m/s2 (b7=128)
        buf.put(0.toByte())
        buf.put(42.toByte())
        buf.put(50.toByte())
        buf.put(128.toByte())
        buf.put(108.toByte())
        buf.put(25.toByte())
        buf.put(150.toByte())
        buf.put(128.toByte())

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 105L,
            timeCpuCycles = 123456L,
            numDetectedObj = 0,
            numTLVs = 1,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        val canOut = frame.canOutputs
        assertNotNull(canOut)

        // FCW
        assertEquals(2, canOut!!.fcw.stage)
        assertEquals(105, canOut.fcw.trackId)
        assertEquals(2.5f, canOut.fcw.ttcSec, 0.01f)
        assertEquals(20.0f, canOut.fcw.targetY, 0.01f)
        assertEquals(0.0f, canOut.fcw.targetX, 0.01f)
        assertEquals(0.0f, canOut.fcw.targetVy, 0.01f)
        assertEquals(0.0f, canOut.fcw.targetVx, 0.01f)

        // BSD
        assertTrue(canOut.bsd.leftActive)
        assertFalse(canOut.bsd.rightActive)
        assertEquals(2, canOut.bsd.warningLevel)
        assertEquals(1.8f, canOut.bsd.approachTtcSec, 0.01f)

        // ACC
        assertEquals(42, canOut.acc.poiId)
        assertEquals(25.0f, canOut.acc.targetY, 0.01f)
        assertEquals(0.0f, canOut.acc.targetX, 0.01f)
        assertEquals(-10.0f, canOut.acc.targetVy, 0.01f)
        assertEquals(2.5f, canOut.acc.ttiSec, 0.01f)
        assertEquals(15.0f, canOut.acc.vSafeMps, 0.01f)
        assertEquals(0.0f, canOut.acc.aRefMps2, 0.01f)

        // Raw hex representations present
        assertEquals(16, canOut.fcwRawHex.length)
        assertEquals(16, canOut.bsdRawHex.length)
        assertEquals(16, canOut.accRawHex.length)
    }

    @Test
    fun decode_completeMultiTlvPacket_parsesAll6TlvsSimultaneously() {
        // Build a combined packet with TLVs 1, 2, 3, 4, 5, 6
        val tlv1Len = 4 + 1 * 12 // 1 point
        val tlv2Len = 4 + 1 * 16 // 1 cluster
        val tlv3Len = 4 + 1 * 28 // 1 track
        val tlv4Len = 48
        val tlv5Len = 56
        val tlv6Len = 24

        val totalLen = (8 + tlv1Len) + (8 + tlv2Len) + (8 + tlv3Len) + (8 + tlv4Len) + (8 + tlv5Len) + (8 + tlv6Len)
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

        // TLV 1
        buf.putInt(1); buf.putInt(tlv1Len)
        buf.putShort(1.toShort()); buf.putShort(7.toShort())
        buf.putShort(0.toShort()); buf.putShort(5120.toShort()); buf.putShort(128.toShort()); buf.putShort(256.toShort()); buf.putShort(0.toShort())
        buf.put(1.toByte()); buf.put(0.toByte())

        // TLV 2
        buf.putInt(2); buf.putInt(tlv2Len)
        buf.putShort(1.toShort()); buf.putShort(7.toShort())
        buf.putShort(128.toShort()); buf.putShort(256.toShort()); buf.putShort(128.toShort()); buf.putShort(128.toShort())
        buf.putShort(1.toShort()); buf.putShort(10.toShort()); buf.put(0.toByte()); buf.put(1.toByte()); buf.put(0.toByte()); buf.put(0.toByte())

        // TLV 3
        buf.putInt(3); buf.putInt(tlv3Len)
        buf.putShort(1.toShort()); buf.putShort(7.toShort())
        buf.putShort(128.toShort()); buf.putShort(256.toShort()); buf.putShort(0.toShort()); buf.putShort((-128).toShort())
        buf.putShort(256.toShort()); buf.putShort(128.toShort()); buf.putShort(0.toShort())
        buf.putShort(99.toShort()); buf.putShort(3.toShort()); buf.putShort(1.toShort()); buf.putShort(150.toShort())
        buf.put(1.toByte()); buf.put(0.toByte()); buf.put(2.toByte()); buf.put(90.toByte()); buf.putShort(0.toShort())

        // TLV 4
        buf.putInt(4); buf.putInt(tlv4Len)
        buf.putShort(10.toShort()); buf.put(1.toByte()); buf.put(1.toByte())
        for (i in 0 until 10) buf.putFloat(1.0f)
        buf.putShort(1500.toShort()); buf.put(0.toByte()); buf.put(0.toByte())

        // TLV 5
        buf.putInt(5); buf.putInt(tlv5Len)
        for (i in 0 until 11) buf.putFloat(2.0f)
        buf.putInt(55555); buf.put(1.toByte()); buf.put(0.toByte()); buf.put(1.toByte()); buf.put(1.toByte()); buf.put(0.toByte())
        buf.put(0.toByte()); buf.put(0.toByte()); buf.put(0.toByte())

        // TLV 6
        buf.putInt(6); buf.putInt(tlv6Len)
        for (i in 0 until 24) buf.put(0.toByte())

        val payload = buf.array()
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 40 + payload.size,
            platform = 0xA1843L,
            frameNumber = 200L,
            timeCpuCycles = 999999L,
            numDetectedObj = 1,
            numTLVs = 6,
            subFrameNumber = 0
        )

        val frame = decoder.decode(RawRadarPacket(header, payload))
        assertEquals(1, frame.points.size)
        assertEquals(1, frame.clusters.size)
        assertEquals(1, frame.tracks.size)
        assertNotNull(frame.diagnostics)
        assertNotNull(frame.canInputs)
        assertNotNull(frame.canOutputs)

        assertEquals(99, frame.tracks[0].tid)
        assertEquals(10, frame.diagnostics!!.numInliers)
        assertEquals(55555L, frame.canInputs!!.timestampMs)
        assertEquals(0, frame.canOutputs!!.fcw.stage)
        assertEquals("v2.2", frame.tlvVersion)
    }
}

