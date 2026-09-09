package com.bajajauto.roadsense.decoding

import android.util.Log
import com.bajajauto.roadsense.models.RadarCluster
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarPoint
import com.bajajauto.roadsense.models.RadarTrack
import com.bajajauto.roadsense.models.RawRadarPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the TLV (Type-Length-Value) payload of a RawRadarPacket into structured
 * domain models (RadarFrame, RadarPoint, RadarTrack, RadarCluster).
 *
 * Implements the TI AWR1843 / MRR binary specifications empirically verified on hardware:
 * - TLV 1: Detected Points / Point Cloud (10 bytes per point + 4 bytes descriptor)
 * - TLV 2: Clusters (8 bytes per cluster + 4 bytes descriptor)
 * - TLV 3: Tracks (14 bytes per track + 4 bytes descriptor)
 */
class RadarTlvDecoder {

    companion object {
        private const val TAG = "RadarTlvDecoder"

        const val TLV_DETECTED_POINTS = 1
        const val TLV_CLUSTERS = 2
        const val TLV_TRACKS = 3
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
                    parseDetectedPoints(valueBuffer, tlvLength, points)
                }
                TLV_CLUSTERS -> {
                    parseClusters(valueBuffer, tlvLength, clusters)
                }
                TLV_TRACKS -> {
                    parseTracks(valueBuffer, tlvLength, tracks)
                }
                else -> {
                    // Other TLVs (Stats, Side Info, etc.) - skipped cleanly
                }
            }

            // Advance outer buffer past this TLV payload
            buffer.position(buffer.position() + tlvLength)
        }

        return RadarFrame(
            header = packet.header,
            points = points,
            tracks = tracks,
            clusters = clusters,
            rawPacket = packet
        )
    }

    /**
     * Parses TLV 1: Detected Points (Point Cloud).
     * Descriptor: 4 bytes (numDetectedObj: uint16, xyzQFormat: uint16)
     * Points: 10 bytes each (doppler: int16, peakVal: uint16, x: int16, y: int16, z: int16)
     */
    private fun parseDetectedPoints(buffer: ByteBuffer, length: Int, outPoints: MutableList<RadarPoint>) {
        if (length < 4) return

        val numPoints = buffer.short.toInt() and 0xFFFF
        val xyzQFormat = buffer.short.toInt() and 0xFFFF

        val q = if (xyzQFormat in 0..31) xyzQFormat else 15
        val invQ = 1.0f / (1 shl q).toFloat()

        val pointSize = 10
        val maxPointsPossible = (length - 4) / pointSize
        val pointsToRead = minOf(numPoints, maxPointsPossible)

        for (i in 0 until pointsToRead) {
            val dopplerRaw = buffer.short
            val peakValRaw = buffer.short.toInt() and 0xFFFF
            val xRaw = buffer.short
            val yRaw = buffer.short
            val zRaw = buffer.short

            // Metric conversion
            val x = xRaw * invQ
            val y = yRaw * invQ
            val z = zRaw * invQ
            val doppler = dopplerRaw * invQ
            // SNR in dB for TI AWR1843 MRR: (peakVal / 512.0) * 6.0206 dB
            val snrDb = (peakValRaw.toFloat() / 512.0f) * 6.0206f

            outPoints.add(RadarPoint(x = x, y = y, z = z, doppler = doppler, snrDb = snrDb))
        }
    }

    /**
     * Parses TLV 2: Clusters.
     * Descriptor: 4 bytes (numClusters: uint16, xyzQFormat: uint16)
     * Clusters: 8 bytes each (x: int16, y: int16, xSize: int16, ySize: int16)
     */
    private fun parseClusters(buffer: ByteBuffer, length: Int, outClusters: MutableList<RadarCluster>) {
        if (length < 4) return

        val numClusters = buffer.short.toInt() and 0xFFFF
        val xyzQFormat = buffer.short.toInt() and 0xFFFF

        val q = if (xyzQFormat in 0..31) xyzQFormat else 15
        val invQ = 1.0f / (1 shl q).toFloat()

        val clusterSize = 8
        val maxClustersPossible = (length - 4) / clusterSize
        val clustersToRead = minOf(numClusters, maxClustersPossible)

        for (i in 0 until clustersToRead) {
            val xRaw = buffer.short
            val yRaw = buffer.short
            val xSizeRaw = buffer.short
            val ySizeRaw = buffer.short

            val x = xRaw * invQ
            val y = yRaw * invQ
            val xSize = xSizeRaw * invQ
            val ySize = ySizeRaw * invQ

            outClusters.add(RadarCluster(x = x, y = y, xSize = xSize, ySize = ySize))
        }
    }

    /**
     * Parses TLV 3: Tracks.
     * Descriptor: 4 bytes (numTracks: uint16, xyzQFormat: uint16)
     * Tracks: 14 bytes each in Custom MRR (x: int16, y: int16, vx: int16, vy: int16, xSize: int16, ySize: int16, tid: uint16)
     * Fallback: 12 bytes each in Standard MRR (without tid field)
     */
    private fun parseTracks(buffer: ByteBuffer, length: Int, outTracks: MutableList<RadarTrack>) {
        if (length < 4) return

        val numTracks = buffer.short.toInt() and 0xFFFF
        val xyzQFormat = buffer.short.toInt() and 0xFFFF

        val q = if (xyzQFormat in 0..31) xyzQFormat else 15
        val invQ = 1.0f / (1 shl q).toFloat()

        val payloadSize = length - 4

        // Check if 14-byte track (Custom MRR with TID) or 12-byte track (Standard MRR)
        val is14Byte = (payloadSize % 14 == 0) && (payloadSize / 14 >= numTracks)
        val trackSize = if (is14Byte) 14 else 12
        val maxTracksPossible = payloadSize / trackSize
        val tracksToRead = minOf(numTracks, maxTracksPossible)

        for (i in 0 until tracksToRead) {
            val xRaw = buffer.short
            val yRaw = buffer.short
            val vxRaw = buffer.short
            val vyRaw = buffer.short
            val xSizeRaw = buffer.short
            val ySizeRaw = buffer.short
            val tid = if (is14Byte) {
                buffer.short.toInt() and 0xFFFF
            } else {
                i + 1
            }

            val x = xRaw * invQ
            val y = yRaw * invQ
            val vx = vxRaw * invQ
            val vy = vyRaw * invQ
            val xSize = xSizeRaw * invQ
            val ySize = ySizeRaw * invQ

            outTracks.add(
                RadarTrack(
                    tid = tid,
                    x = x,
                    y = y,
                    vx = vx,
                    vy = vy,
                    xSize = xSize,
                    ySize = ySize
                )
            )
        }
    }
}
