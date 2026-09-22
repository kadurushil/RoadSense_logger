package com.bajajauto.roadsense.decoding

import android.util.Log
import com.bajajauto.roadsense.models.RadarAccTarget
import com.bajajauto.roadsense.models.RadarBsdAlert
import com.bajajauto.roadsense.models.RadarCanInputs
import com.bajajauto.roadsense.models.RadarCanOutputs
import com.bajajauto.roadsense.models.RadarCluster
import com.bajajauto.roadsense.models.RadarFcwAlert
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarPoint
import com.bajajauto.roadsense.models.RadarTrack
import com.bajajauto.roadsense.models.RadarTrackerDiagnostics
import com.bajajauto.roadsense.models.RawRadarPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the TLV (Type-Length-Value) payload of a RawRadarPacket into structured
 * domain models (RadarFrame, RadarPoint, RadarTrack, RadarCluster, etc.).
 *
 * Implements the TI AWR1843 Custom MRR (v2.1 / v2.2) binary specifications:
 * - TLV 1: Extended Point Cloud (12 bytes per point with clusterID & outlier, or 10 bytes legacy)
 * - TLV 2: Extended Clusters (16 bytes per cluster, 10 bytes with CID, or 8 bytes standard)
 * - TLV 3: Extended Tracks (28 bytes v2.2, 20 bytes <7h3H, 14 bytes with TID, or 12 bytes legacy)
 * - TLV 4: Tracker Diagnostics (48 bytes: RANSAC, ego EKF, road boundaries)
 * - TLV 5: Vehicle CAN Inputs (56 bytes: speed, yaw rate, IMU, pedals, VCU status)
 * - TLV 6: Vehicle Safety ADAS Outputs (24 bytes: 8B FCW 0x320, 8B BSD 0x328, 8B ACC 0x327)
 */
class RadarTlvDecoder {

    companion object {
        private const val TAG = "RadarTlvDecoder"

        const val TLV_DETECTED_POINTS = 1
        const val TLV_CLUSTERS = 2
        const val TLV_TRACKS = 3
        const val TLV_TRACKER_DIAGS = 4
        const val TLV_CAN_INPUTS = 5
        const val TLV_CAN_OUTPUTS = 6

        // Legacy / Alternative aliases
        const val TLV_PARKING_ASSIST = 4
        const val TLV_STATS = 6
        const val TLV_SIDE_INFO = 7

        private const val TLV_HEADER_SIZE = 8 // 4 bytes type + 4 bytes length
    }

    /**
     * Decodes a raw assembled packet into a RadarFrame.
     */
    fun decode(packet: RawRadarPacket): RadarFrame {
        val payload = packet.payload
        if (payload.isEmpty() || packet.header.numTLVs <= 0) {
            return RadarFrame(header = packet.header, rawPacket = packet)
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val points = mutableListOf<RadarPoint>()
        val tracks = mutableListOf<RadarTrack>()
        val clusters = mutableListOf<RadarCluster>()
        var diagnostics: RadarTrackerDiagnostics? = null
        var canInputs: RadarCanInputs? = null
        var canOutputs: RadarCanOutputs? = null

        var hasV22 = false
        var hasV21 = false
        var hasLegacy = false

        for (tlvIndex in 0 until packet.header.numTLVs) {
            if (buffer.remaining() < TLV_HEADER_SIZE) {
                break
            }

            val tlvType = buffer.int
            val tlvLength = buffer.int

            if (tlvLength < 0 || buffer.remaining() < tlvLength) {
                Log.w(TAG, "TLV #$tlvIndex (Type $tlvType) length ($tlvLength) exceeds remaining payload (${buffer.remaining()})")
                break
            }

            // Slice value bytes to ensure parser cannot read past this TLV boundary
            val valueBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            valueBuffer.limit(tlvLength)

            when (tlvType) {
                TLV_DETECTED_POINTS -> {
                    val stride = parseDetectedPoints(valueBuffer, tlvLength, points)
                    if (stride >= 12) hasV22 = true
                    else if (stride > 0) hasLegacy = true
                }
                TLV_CLUSTERS -> {
                    val stride = parseClusters(valueBuffer, tlvLength, clusters)
                    if (stride >= 16) hasV22 = true
                    else if (stride >= 10) hasV21 = true
                    else if (stride > 0) hasLegacy = true
                }
                TLV_TRACKS -> {
                    val stride = parseTracks(valueBuffer, tlvLength, tracks)
                    if (stride >= 28) hasV22 = true
                    else if (stride >= 20) hasV21 = true
                    else if (stride > 0) hasLegacy = true
                }
                TLV_TRACKER_DIAGS -> {
                    if (tlvLength == 48) {
                        diagnostics = parseTrackerDiagnostics(valueBuffer, tlvLength)
                        hasV22 = true
                    }
                }
                TLV_CAN_INPUTS -> {
                    if (tlvLength >= 56) {
                        canInputs = parseCanInputs(valueBuffer, tlvLength)
                        hasV22 = true
                    }
                }
                TLV_CAN_OUTPUTS -> {
                    if (tlvLength >= 24) {
                        canOutputs = parseCanOutputs(valueBuffer, tlvLength)
                        hasV22 = true
                    }
                }
                else -> {
                    // Other TLVs - skipped cleanly
                }
            }

            // Advance outer buffer past this TLV payload
            buffer.position(buffer.position() + tlvLength)
        }

        val tlvVersion = when {
            hasV22 -> "v2.2"
            hasV21 -> "v2.1"
            hasLegacy -> "v1.0"
            else -> "v2.2"
        }

        return RadarFrame(
            header = packet.header,
            points = points,
            tracks = tracks,
            clusters = clusters,
            diagnostics = diagnostics,
            canInputs = canInputs,
            canOutputs = canOutputs,
            rawPacket = packet,
            tlvVersion = tlvVersion
        )
    }

    /**
     * Parses TLV 1: Detected Points (Point Cloud).
     * Descriptor: 4 bytes (numDetectedObj: uint16, xyzQFormat: uint16)
     * Supports:
     * - 12 bytes per point (v2.1 Extended): doppler(h), peakVal(H), x(h), y(h), z(h), clusterID(u1), isOutlier(u1)
     * - 10 bytes per point (Legacy): doppler(h), peakVal(H), x(h), y(h), z(h)
     */
    private fun parseDetectedPoints(buffer: ByteBuffer, length: Int, outPoints: MutableList<RadarPoint>): Int {
        if (length < 4) return 0

        val numPoints = buffer.short.toInt() and 0xFFFF
        val xyzQFormat = buffer.short.toInt() and 0xFFFF

        val q = if (xyzQFormat in 0..31) xyzQFormat else 15
        val invQ = 1.0f / (1 shl q).toFloat()

        val payloadSize = length - 4
        if (numPoints <= 0 || payloadSize <= 0) return 0

        val actualStride = payloadSize / numPoints
        val pointSize = if (actualStride >= 12) 12 else 10
        val maxPointsPossible = payloadSize / pointSize
        val pointsToRead = minOf(numPoints, maxPointsPossible)

        for (i in 0 until pointsToRead) {
            val dopplerRaw = buffer.short
            val peakValRaw = buffer.short.toInt() and 0xFFFF
            val xRaw = buffer.short
            val yRaw = buffer.short
            val zRaw = buffer.short

            var clusterId = 0
            var isOutlier = false

            if (pointSize >= 12) {
                clusterId = buffer.get().toInt() and 0xFF
                val outlierRaw = buffer.get().toInt() and 0xFF
                isOutlier = (outlierRaw != 0)
                if (pointSize > 12) {
                    buffer.position(buffer.position() + (pointSize - 12))
                }
            }

            val x = xRaw * invQ
            val y = yRaw * invQ
            val z = zRaw * invQ
            val doppler = dopplerRaw * invQ
            val snrDb = (peakValRaw.toFloat() / 512.0f) * 6.0206f

            outPoints.add(
                RadarPoint(
                    x = x,
                    y = y,
                    z = z,
                    doppler = doppler,
                    snrDb = snrDb,
                    clusterId = clusterId,
                    isOutlier = isOutlier
                )
            )
        }
        return actualStride
    }

    /**
     * Parses TLV 2: Clusters.
     * Descriptor: 4 bytes (numClusters: uint16, xyzQFormat: uint16)
     * Supports:
     * - 16 bytes (<hhhhHHBBBB): xCenter, yCenter, xSize, ySize, clusterID (u2), numPoints (u2), isOutlier (u1), isStationary (u1), isDeadZone (u1), reserved (u1)
     * - 10 bytes (<4h2B): xCenter, yCenter, vx, vy, clusterID (u2)
     * - 8 bytes (<4h): xCenter, yCenter, xSize, ySize
     */
    private fun parseClusters(buffer: ByteBuffer, length: Int, outClusters: MutableList<RadarCluster>): Int {
        if (length < 4) return 0

        val numClusters = buffer.short.toInt() and 0xFFFF
        val xyzQFormat = buffer.short.toInt() and 0xFFFF

        val q = if (xyzQFormat in 0..31) xyzQFormat else 15
        val invQ = 1.0f / (1 shl q).toFloat()

        val payloadSize = length - 4
        if (numClusters <= 0 || payloadSize < 8) return 0

        val actualStride = payloadSize / numClusters
        val clusterSize = when {
            actualStride >= 16 -> 16
            actualStride >= 10 -> 10
            else -> 8
        }
        val maxClustersPossible = payloadSize / clusterSize
        val clustersToRead = minOf(numClusters, maxClustersPossible)

        for (i in 0 until clustersToRead) {
            val xRaw = buffer.short
            val yRaw = buffer.short
            val f2Raw = buffer.short
            val f3Raw = buffer.short

            val cid: Int
            val vx: Float
            val vy: Float
            val xSize: Float
            val ySize: Float
            var numPoints = 0
            var isOutlier = false
            var isStationary = false
            var isDeadZone = false

            when (clusterSize) {
                16 -> {
                    xSize = f2Raw * invQ
                    ySize = f3Raw * invQ
                    vx = 0f
                    vy = 0f
                    cid = buffer.short.toInt() and 0xFFFF
                    numPoints = buffer.short.toInt() and 0xFFFF
                    isOutlier = (buffer.get().toInt() and 0xFF) != 0
                    isStationary = (buffer.get().toInt() and 0xFF) != 0
                    isDeadZone = (buffer.get().toInt() and 0xFF) != 0
                    buffer.get() // reserved padding byte
                    if (clusterSize > 16) {
                        buffer.position(buffer.position() + (clusterSize - 16))
                    }
                }
                10 -> {
                    cid = buffer.short.toInt() and 0xFFFF
                    vx = f2Raw * invQ
                    vy = f3Raw * invQ
                    xSize = 1.2f
                    ySize = 1.2f
                }
                else -> {
                    cid = i + 1
                    vx = 0f
                    vy = 0f
                    xSize = f2Raw * invQ
                    ySize = f3Raw * invQ
                }
            }

            val x = xRaw * invQ
            val y = yRaw * invQ

            if (x != 0f || y != 0f) {
                outClusters.add(
                    RadarCluster(
                        x = x,
                        y = y,
                        vx = vx,
                        vy = vy,
                        cid = cid,
                        xSize = xSize,
                        ySize = ySize,
                        numPoints = numPoints,
                        isOutlier = isOutlier,
                        isStationary = isStationary,
                        isDeadZone = isDeadZone
                    )
                )
            }
        }
        return actualStride
    }

    /**
     * Parses TLV 3: Tracks (EKF Tracker Table).
     * Descriptor: 4 bytes (numTracks: uint16, xyzQFormat: uint16)
     * Supports:
     * - 28 bytes (v2.2 Extended): x, y, vx, vy, majorSize, minorSize, orientation, tid, state, clusterId, tti, risk, isStationary, ttcCategory, confidence, reserved
     * - 20 bytes (<7h3H): x, y, vx, vy, majorSize, minorSize, orientation, tid, state, reserved
     * - 14 bytes (<6hH): x, y, vx, vy, xSize, ySize, tid
     * - 12 bytes (<6h): x, y, vx, vy, xSize, ySize
     */
    private fun parseTracks(buffer: ByteBuffer, length: Int, outTracks: MutableList<RadarTrack>): Int {
        if (length < 4) return 0

        val numTracks = buffer.short.toInt() and 0xFFFF
        val xyzQFormat = buffer.short.toInt() and 0xFFFF

        val q = if (xyzQFormat in 0..31) xyzQFormat else 15
        val invQ = 1.0f / (1 shl q).toFloat()

        val payloadSize = length - 4
        if (numTracks <= 0 || payloadSize < 12) return 0

        val actualStride = payloadSize / numTracks
        val trackSize = when {
            actualStride >= 28 -> 28
            actualStride >= 20 -> 20
            actualStride >= 14 -> 14
            else -> 12
        }

        val maxTracksPossible = payloadSize / trackSize
        val tracksToRead = minOf(numTracks, maxTracksPossible)

        for (i in 0 until tracksToRead) {
            val xRaw = buffer.short
            val yRaw = buffer.short
            val vxRaw = buffer.short
            val vyRaw = buffer.short

            val x = xRaw * invQ
            val y = yRaw * invQ
            val vx = vxRaw * invQ
            val vy = vyRaw * invQ

            val xSize: Float
            val ySize: Float
            val majorSize: Float
            val minorSize: Float
            var orientationDeg = 0f
            val tid: Int
            var state = 3 // Confirmed
            var clusterId = 0
            var ttiSec = Float.POSITIVE_INFINITY
            var risk = 0
            var isStationary = false
            var ttcCategory = 0
            var confidencePct = 100

            when (trackSize) {
                28 -> {
                    val majRaw = buffer.short
                    val minRaw = buffer.short
                    val oriRaw = buffer.short
                    tid = buffer.short.toInt() and 0xFFFF
                    state = buffer.short.toInt() and 0xFFFF
                    clusterId = buffer.short.toInt() and 0xFFFF
                    val ttiRaw = buffer.short.toInt()
                    risk = buffer.get().toInt() and 0xFF
                    isStationary = (buffer.get().toInt() and 0xFF) != 0
                    ttcCategory = buffer.get().toInt() and 0xFF
                    confidencePct = buffer.get().toInt() and 0xFF
                    buffer.short // reserved 2B

                    majorSize = majRaw * invQ
                    minorSize = minRaw * invQ
                    xSize = majorSize
                    ySize = minorSize
                    orientationDeg = oriRaw * 0.1f
                    ttiSec = if (ttiRaw >= 0) (ttiRaw * 0.01f) else Float.POSITIVE_INFINITY

                    if (trackSize > 28) {
                        buffer.position(buffer.position() + (trackSize - 28))
                    }
                }
                20 -> {
                    // <7h3H: x, y, vx, vy, majorSize, minorSize, orientation, tid, state, reserved
                    val majRaw = buffer.short
                    val minRaw = buffer.short
                    val oriRaw = buffer.short
                    tid = buffer.short.toInt() and 0xFFFF
                    state = buffer.short.toInt() and 0xFFFF
                    buffer.short // reserved

                    majorSize = majRaw * invQ
                    minorSize = minRaw * invQ
                    xSize = majorSize
                    ySize = minorSize
                    orientationDeg = oriRaw * 0.1f
                }
                14 -> {
                    // <6hH: x, y, vx, vy, xSize, ySize, tid
                    val xsRaw = buffer.short
                    val ysRaw = buffer.short
                    tid = buffer.short.toInt() and 0xFFFF
                    xSize = xsRaw * invQ
                    ySize = ysRaw * invQ
                    majorSize = xSize
                    minorSize = ySize
                }
                else -> {
                    // 12-byte legacy: x, y, vx, vy, xSize, ySize
                    val xsRaw = buffer.short
                    val ysRaw = buffer.short
                    tid = i + 1
                    xSize = xsRaw * invQ
                    ySize = ysRaw * invQ
                    majorSize = xSize
                    minorSize = ySize
                    if (trackSize > 12) {
                        buffer.position(buffer.position() + (trackSize - 12))
                    }
                }
            }

            // Filter out empty / unallocated tracker table slots
            val isInactiveSlot = (state == 0) || (x == 0f && y == 0f && vx == 0f && vy == 0f)

            if (!isInactiveSlot) {
                outTracks.add(
                    RadarTrack(
                        tid = tid,
                        x = x,
                        y = y,
                        vx = vx,
                        vy = vy,
                        xSize = xSize,
                        ySize = ySize,
                        majorSize = majorSize,
                        minorSize = minorSize,
                        orientationDeg = orientationDeg,
                        state = state,
                        clusterId = clusterId,
                        ttiSec = ttiSec,
                        risk = risk,
                        isStationary = isStationary,
                        ttcCategory = ttcCategory,
                        confidencePct = confidencePct
                    )
                )
            }
        }
        return actualStride
    }

    /**
     * Parses TLV 4: Tracker Diagnostics (48 bytes).
     * Format: <HBb10fHBB
     */
    private fun parseTrackerDiagnostics(buffer: ByteBuffer, length: Int): RadarTrackerDiagnostics? {
        if (length < 48) return null

        val numInliers = buffer.short.toInt() and 0xFFFF
        val ransacSuccessful = (buffer.get().toInt() and 0xFF) != 0
        val motionState = buffer.get().toInt()
        val filteredVxIir = buffer.float
        val filteredVyIir = buffer.float
        val egoEkfVy = buffer.float
        val egoEkfVx = buffer.float
        val egoEkfAx = buffer.float
        val egoEkfAy = buffer.float
        val egoEkfYawRate = buffer.float
        val roadBoundaryLeftX = buffer.float
        val roadBoundaryRightX = buffer.float
        val axDynamics = buffer.float
        val trackerProcTimeUs = buffer.short.toInt() and 0xFFFF
        val imuStuckFlag = (buffer.get().toInt() and 0xFF) != 0
        buffer.get() // padding byte

        return RadarTrackerDiagnostics(
            numInliers = numInliers,
            ransacSuccessful = ransacSuccessful,
            motionState = motionState,
            filteredVxIir = filteredVxIir,
            filteredVyIir = filteredVyIir,
            egoEkfVy = egoEkfVy,
            egoEkfVx = egoEkfVx,
            egoEkfAx = egoEkfAx,
            egoEkfAy = egoEkfAy,
            egoEkfYawRate = egoEkfYawRate,
            roadBoundaryLeftX = roadBoundaryLeftX,
            roadBoundaryRightX = roadBoundaryRightX,
            axDynamics = axDynamics,
            trackerProcTimeUs = trackerProcTimeUs,
            imuStuckFlag = imuStuckFlag
        )
    }

    /**
     * Parses TLV 5: Vehicle CAN Inputs (56 bytes).
     * Format: <fffffffffffIBBbBB3s
     */
    private fun parseCanInputs(buffer: ByteBuffer, length: Int): RadarCanInputs? {
        if (length < 56) return null

        val speedKmph = buffer.float
        val yawRateRadps = buffer.float
        val pitchRateRadps = buffer.float
        val rollRateRadps = buffer.float
        val accelXMps2 = buffer.float
        val accelYMps2 = buffer.float
        val accelAvgMps2 = buffer.float
        val roadGradeDeg = buffer.float
        val motorTorqueNm = buffer.float
        val rollCfDeg = buffer.float
        val yawCfDeg = buffer.float
        val timestampMs = buffer.int.toLong() and 0xFFFFFFFFL
        val gear = buffer.get().toInt()
        val brakeStatus = buffer.get().toInt() and 0xFF
        val motionState = buffer.get().toInt()
        val isVcuCanValid = (buffer.get().toInt() and 0xFF) != 0
        val imuStuckFlag = (buffer.get().toInt() and 0xFF) != 0
        buffer.get(); buffer.get(); buffer.get() // 3 bytes alignment padding

        return RadarCanInputs(
            speedKmph = speedKmph,
            yawRateRadps = yawRateRadps,
            pitchRateRadps = pitchRateRadps,
            rollRateRadps = rollRateRadps,
            accelXMps2 = accelXMps2,
            accelYMps2 = accelYMps2,
            accelAvgMps2 = accelAvgMps2,
            roadGradeDeg = roadGradeDeg,
            motorTorqueNm = motorTorqueNm,
            rollCfDeg = rollCfDeg,
            yawCfDeg = yawCfDeg,
            timestampMs = timestampMs,
            gear = gear,
            brakeStatus = brakeStatus,
            motionState = motionState,
            isVcuCanValid = isVcuCanValid,
            imuStuckFlag = imuStuckFlag
        )
    }

    /**
     * Parses TLV 6: Vehicle Safety ADAS Outputs (24 bytes).
     * Contains 3 contiguous 8-byte frames:
     * - Bytes 0..7: FCW (0x320)
     * - Bytes 8..15: BSD (0x328 / 0x321)
     * - Bytes 16..23: ACC (0x327)
     */
    private fun parseCanOutputs(buffer: ByteBuffer, length: Int): RadarCanOutputs? {
        if (length < 24) return null

        val fcwBytes = ByteArray(8)
        buffer.get(fcwBytes)
        val bsdBytes = ByteArray(8)
        buffer.get(bsdBytes)
        val accBytes = ByteArray(8)
        buffer.get(accBytes)

        // 1. Decode FCW Frame (0x320)
        val b0 = fcwBytes[0].toInt() and 0xFF
        val b1 = fcwBytes[1].toInt() and 0xFF
        val b2 = fcwBytes[2].toInt() and 0xFF
        val b3 = fcwBytes[3].toInt() and 0xFF
        val b4 = fcwBytes[4].toInt() and 0xFF
        val b5 = fcwBytes[5].toInt() and 0xFF
        val b6 = fcwBytes[6].toInt() and 0xFF

        val fcwStage = b0 and 0x03
        val fcwTrackId = ((b0 ushr 2) shl 8) or b1
        val fcwTtc = b2 * 0.1f
        val fcwTargetY = b3 * 0.5f
        val fcwTargetX = b4 * 0.2f - 25.6f
        val fcwTargetVy = b5 * 0.5f - 64.0f
        val fcwTargetVx = b6 * 0.2f - 25.6f

        val fcw = RadarFcwAlert(
            stage = fcwStage,
            trackId = fcwTrackId,
            ttcSec = fcwTtc,
            targetY = fcwTargetY,
            targetX = fcwTargetX,
            targetVy = fcwTargetVy,
            targetVx = fcwTargetVx
        )

        // 2. Decode BSD Frame (0x328 / 0x321)
        val bsd0 = bsdBytes[0].toInt() and 0xFF
        val bsd1 = bsdBytes[1].toInt() and 0xFF
        val bsd2 = bsdBytes[2].toInt() and 0xFF
        val bsd3 = bsdBytes[3].toInt() and 0xFF

        val bsd = RadarBsdAlert(
            leftActive = (bsd0 != 0),
            rightActive = (bsd1 != 0),
            warningLevel = bsd2,
            approachTtcSec = if (bsd3 != 255) bsd3 * 0.1f else 25.5f
        )

        // 3. Decode ACC Frame (0x327)
        val acc0 = accBytes[0].toInt() and 0xFF
        val acc1 = accBytes[1].toInt() and 0xFF
        val acc2 = accBytes[2].toInt() and 0xFF
        val acc3 = accBytes[3].toInt() and 0xFF
        val acc4 = accBytes[4].toInt() and 0xFF
        val acc5 = accBytes[5].toInt() and 0xFF
        val acc6 = accBytes[6].toInt() and 0xFF
        val acc7 = accBytes[7].toInt() and 0xFF

        val acc = RadarAccTarget(
            poiId = (acc0 shl 8) or acc1,
            targetY = acc2 * 0.5f,
            targetX = acc3 * 0.2f - 25.6f,
            targetVy = acc4 * 0.5f - 64.0f,
            ttiSec = acc5 * 0.1f,
            vSafeMps = acc6 * 0.1f,
            aRefMps2 = acc7 * 0.02f - 2.56f
        )

        val hexChars = "0123456789abcdef"
        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(hexChars[v ushr 4])
                sb.append(hexChars[v and 0x0F])
            }
            return sb.toString()
        }

        return RadarCanOutputs(
            fcw = fcw,
            bsd = bsd,
            acc = acc,
            fcwRawHex = bytesToHex(fcwBytes),
            bsdRawHex = bytesToHex(bsdBytes),
            accRawHex = bytesToHex(accBytes)
        )
    }
}
