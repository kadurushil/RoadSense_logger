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
    val snrDb: Float,
    val clusterId: Int = 0,
    val isOutlier: Boolean = false
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
 * @param majorSize Oriented bounding box major axis extent in meters
 * @param minorSize Oriented bounding box minor axis extent in meters
 * @param orientationDeg Bounding box heading orientation in degrees (-180° to +180°)
 * @param state EKF filter lifecycle state (0: Free, 1: Tentative, 3: Confirmed, 4: Coasted, 5: Dead)
 * @param clusterId Associated parent cluster ID
 * @param ttiSec Time-to-Interception in seconds
 * @param risk Collision risk rating (0: Safe, 1: Warning, 2: Critical)
 * @param isStationary Target stationary persistence flag (true = static obstacle, false = moving vehicle)
 * @param ttcCategory Time-to-Collision severity category (0: Safe, 1: Warning, 2: Critical)
 * @param confidencePct Estimation confidence percentage (0 to 100%)
 */
data class RadarTrack(
    val tid: Int,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val xSize: Float,
    val ySize: Float,
    val majorSize: Float = xSize,
    val minorSize: Float = ySize,
    val orientationDeg: Float = 0f,
    val state: Int = 3,
    val clusterId: Int = 0,
    val ttiSec: Float = Float.POSITIVE_INFINITY,
    val risk: Int = 0,
    val isStationary: Boolean = false,
    val ttcCategory: Int = 0,
    val confidencePct: Int = 100
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
 * @param numPoints Number of constituent points assigned to this cluster
 * @param isOutlier Dynamic motion outlier flag
 * @param isStationary Ground stationary in-corridor flag
 * @param isDeadZone Blind-spot / near-field bumper deadzone flag
 */
data class RadarCluster(
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val cid: Int = 0,
    val xSize: Float = 1.2f,
    val ySize: Float = 1.2f,
    val numPoints: Int = 0,
    val isOutlier: Boolean = false,
    val isStationary: Boolean = false,
    val isDeadZone: Boolean = false
)

/**
 * Real-time tracker runtime diagnostics emitted by TI Custom MRR firmware (TLV Type 4).
 */
data class RadarTrackerDiagnostics(
    val numInliers: Int,
    val ransacSuccessful: Boolean,
    val motionState: Int, // -2: Left Peak, -1: Left, 0: Straight, +1: Right, +2: Right Peak
    val filteredVxIir: Float,
    val filteredVyIir: Float,
    val egoEkfVy: Float,
    val egoEkfVx: Float,
    val egoEkfAx: Float,
    val egoEkfAy: Float,
    val egoEkfYawRate: Float,
    val roadBoundaryLeftX: Float,
    val roadBoundaryRightX: Float,
    val axDynamics: Float,
    val trackerProcTimeUs: Int,
    val imuStuckFlag: Boolean
)

/**
 * Vehicle CAN bus and powertrain telemetry ingested by radar MCU (TLV Type 5).
 */
data class RadarCanInputs(
    val speedKmph: Float,
    val yawRateRadps: Float,
    val pitchRateRadps: Float,
    val rollRateRadps: Float,
    val accelXMps2: Float,
    val accelYMps2: Float,
    val accelAvgMps2: Float,
    val roadGradeDeg: Float,
    val motorTorqueNm: Float,
    val rollCfDeg: Float,
    val yawCfDeg: Float,
    val timestampMs: Long,
    val gear: Int,
    val brakeStatus: Int,
    val motionState: Int,
    val isVcuCanValid: Boolean,
    val imuStuckFlag: Boolean
)

/**
 * Forward Collision Warning alert state decoded from radar CAN output (TLV Type 6, frame 0x320).
 */
data class RadarFcwAlert(
    val stage: Int, // 0: None, 1: Visual, 2: Audible
    val trackId: Int,
    val ttcSec: Float,
    val targetY: Float,
    val targetX: Float,
    val targetVy: Float,
    val targetVx: Float
)

/**
 * Blind Spot Detection alert state decoded from radar CAN output (TLV Type 6, frame 0x328 / 0x321).
 */
data class RadarBsdAlert(
    val leftActive: Boolean,
    val rightActive: Boolean,
    val warningLevel: Int,
    val approachTtcSec: Float
)

/**
 * Adaptive Cruise Control target state decoded from radar CAN output (TLV Type 6, frame 0x327).
 */
data class RadarAccTarget(
    val poiId: Int,
    val targetY: Float,
    val targetX: Float,
    val targetVy: Float,
    val ttiSec: Float,
    val vSafeMps: Float,
    val aRefMps2: Float
)

/**
 * Complete safety ADAS CAN output package (TLV Type 6).
 */
data class RadarCanOutputs(
    val fcw: RadarFcwAlert,
    val bsd: RadarBsdAlert,
    val acc: RadarAccTarget,
    val fcwRawHex: String = "",
    val bsdRawHex: String = "",
    val accRawHex: String = ""
)

/**
 * Represents a fully decoded radar frame combining the frame header, point cloud,
 * hardware tracks, clusters, diagnostics, and CAN telemetry, along with the raw packet.
 */
data class RadarFrame(
    val header: RadarHeader,
    val points: List<RadarPoint> = emptyList(),
    val tracks: List<RadarTrack> = emptyList(),
    val clusters: List<RadarCluster> = emptyList(),
    val diagnostics: RadarTrackerDiagnostics? = null,
    val canInputs: RadarCanInputs? = null,
    val canOutputs: RadarCanOutputs? = null,
    val rawPacket: RawRadarPacket,
    val tlvVersion: String = "v2.2"
)
