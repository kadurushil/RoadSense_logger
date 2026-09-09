package com.bajajauto.roadsense.models

/**
 * Represents a single 3D point detection from the radar point cloud (TLV Type 1).
 *
 * Coordinates follow the standard vehicle ISO / radar sensor reference frame:
 * - x: Lateral offset in meters (positive = right, negative = left)
 * - y: Longitudinal range in meters (positive = forward away from sensor)
 * - z: Vertical elevation in meters (positive = upward)
 * - doppler: Radial velocity in meters/second (negative = approaching, positive = receding)
 * - snrDb: Signal-to-Noise Ratio in dB (derived from TI peakVal)
 */
data class RadarPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val doppler: Float,
    val snrDb: Float
)

/**
 * Represents an active tracked obstacle output by the TI AWR1843 hardware tracker (TLV Type 3).
 *
 * @param tid Unique tracking identifier assigned to this target by the firmware
 * @param x Lateral position in meters
 * @param y Longitudinal distance in meters
 * @param vx Lateral velocity in m/s
 * @param vy Longitudinal relative velocity in m/s
 * @param xSize Estimated target width / bounding box spread in meters
 * @param ySize Estimated target length / bounding box spread in meters
 */
data class RadarTrack(
    val tid: Int,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val xSize: Float,
    val ySize: Float
)

/**
 * Represents a group of clustered radar reflections (TLV Type 2).
 *
 * @param x Cluster centroid lateral position in meters
 * @param y Cluster centroid longitudinal distance in meters
 * @param vx Cluster lateral velocity in m/s
 * @param vy Cluster longitudinal velocity in m/s
 * @param cid Cluster identification number assigned by firmware
 * @param xSize Cluster lateral spread in meters
 * @param ySize Cluster longitudinal spread in meters
 */
data class RadarCluster(
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val cid: Int = 0,
    val xSize: Float = 1.2f,
    val ySize: Float = 1.2f
)

/**
 * Represents a fully decoded radar frame combining the frame header, point cloud,
 * hardware tracks, and clusters, along with the original raw packet for logging fidelity.
 */
data class RadarFrame(
    val header: RadarHeader,
    val points: List<RadarPoint> = emptyList(),
    val tracks: List<RadarTrack> = emptyList(),
    val clusters: List<RadarCluster> = emptyList(),
    val rawPacket: RawRadarPacket
)
