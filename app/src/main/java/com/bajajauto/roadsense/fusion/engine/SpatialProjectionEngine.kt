package com.bajajauto.roadsense.fusion.engine

import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarPoint
import com.bajajauto.roadsense.models.RadarTrack
import kotlin.math.*

/**
 * 2D point representation in screen pixel and normalized coordinates.
 */
data class ScreenPoint(
    val xPx: Float,
    val yPx: Float,
    val normX: Float,
    val normY: Float,
    val depthM: Float
)

/**
 * High-performance geometric projection engine for mapping 2D planar radar points
 * (Z_radar = 0) onto the smartphone camera viewfinder.
 */
object SpatialProjectionEngine {

    /**
     * Projects a 2D radar point (X_r, Y_r) onto screen coordinates using the current
     * calibration parameters and camera intrinsics.
     *
     * @param xRadar Lateral offset in meters (+right, -left)
     * @param yRadar Longitudinal range in meters (+forward)
     * @param targetHeightM Optional elevation of the target above the radar plane
     */
    fun project2DRadarToScreen(
        xRadar: Float,
        yRadar: Float,
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int,
        targetHeightM: Float = params.targetHeightM
    ): ScreenPoint? {
        if (yRadar <= 0.2f) return null // Behind radar or too close

        // 1. Vehicle Mounting Offsets
        val xRel = xRadar - params.lateralOffsetM
        val yRel = yRadar - params.setbackM
        val zRel = targetHeightM - params.heightOffsetM

        if (yRel <= 0.1f) return null // Behind phone camera lens

        // 2. Base Optical Coordinates [X_c0, Y_c0, Z_c0]
        val x0 = xRel
        val y0 = -zRel
        val z0 = yRel

        // 3. Extrinsic Rotations
        val pitchRad = Math.toRadians(params.effectivePitchDeg.toDouble()).toFloat()
        val yawRad = Math.toRadians(params.effectiveYawDeg.toDouble()).toFloat()
        val rollRad = Math.toRadians(params.rollDeg.toDouble()).toFloat()

        // Rotate around Y_c (Yaw - pan left/right)
        val cosY = cos(yawRad)
        val sinY = sin(yawRad)
        val x1 = x0 * cosY - z0 * sinY
        val y1 = y0
        val z1 = x0 * sinY + z0 * cosY

        // Rotate around X_c (Pitch - tilt up/down)
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)
        val x2 = x1
        val y2 = y1 * cosP + z1 * sinP
        val z2 = -y1 * sinP + z1 * cosP

        // Rotate around Z_c (Roll - level clamp)
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)
        val xCam = x2 * cosR + y2 * sinR
        val yCam = -x2 * sinR + y2 * cosR
        val zCam = z2

        if (zCam <= 0.1f) return null // Behind camera focal plane

        // 4. Pinhole Projection K
        // Scale intrinsics if view dimensions differ from reference
        val scaleX = viewWidth.toFloat() / intrinsics.imageWidth.toFloat()
        val scaleY = viewHeight.toFloat() / intrinsics.imageHeight.toFloat()
        val fx = intrinsics.fx * scaleX
        val fy = intrinsics.fy * scaleY
        val cx = intrinsics.cx * scaleX
        val cy = intrinsics.cy * scaleY

        val u = fx * (xCam / zCam) + cx
        val v = fy * (yCam / zCam) + cy

        val normX = u / viewWidth.toFloat()
        val normY = v / viewHeight.toFloat()

        return ScreenPoint(
            xPx = u,
            yPx = v,
            normX = normX,
            normY = normY,
            depthM = zCam
        )
    }

    /**
     * Direct closed-form inverse solver: Calculates the required Pitch and Yaw
     * angles from an interactive touch/drag point on screen.
     *
     * @param normX Normalized screen X [0.0 .. 1.0]
     * @param normY Normalized screen Y [0.0 .. 1.0]
     * @return Pair of (PitchDeg, YawDeg)
     */
    fun solveExtrinsicsFromTouch(
        normX: Float,
        normY: Float,
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Float, Float> {
        val u = normX * viewWidth.toFloat()
        val v = normY * viewHeight.toFloat()

        val scaleX = viewWidth.toFloat() / intrinsics.imageWidth.toFloat()
        val scaleY = viewHeight.toFloat() / intrinsics.imageHeight.toFloat()
        val fx = intrinsics.fx * scaleX
        val fy = intrinsics.fy * scaleY
        val cx = intrinsics.cx * scaleX
        val cy = intrinsics.cy * scaleY

        val rayX = (u - cx) / fx
        val rayY = (v - cy) / fy

        val alphaX = atan(rayX.toDouble())
        val alphaY = atan(rayY.toDouble())

        // Physical target coordinates (assumed parked straight ahead along vehicle centerline)
        val xRel = 0.0f - params.lateralOffsetM
        val yRel = params.targetDistanceM - params.setbackM
        val zRel = params.targetHeightM - params.heightOffsetM

        val targetYaw = atan2(xRel.toDouble(), yRel.toDouble())
        val dist2D = sqrt(xRel.toDouble().pow(2.0) + yRel.toDouble().pow(2.0))
        val targetPitch = atan2(-zRel.toDouble(), dist2D)

        // Solve angles: R_x(θ) · R_y(ψ)
        val yawRad = targetYaw - alphaX
        val pitchRad = alphaY - targetPitch

        val pitchDeg = Math.toDegrees(pitchRad).toFloat()
        val yawDeg = Math.toDegrees(yawRad).toFloat()

        return Pair(
            pitchDeg.coerceIn(-45f, 45f),
            yawDeg.coerceIn(-45f, 45f)
        )
    }

    /**
     * Calculates the two 2D screen endpoints of the artificial horizon line across the viewfinder.
     */
    fun calculateHorizonLine(
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<ScreenPoint, ScreenPoint> {
        val scaleX = viewWidth.toFloat() / intrinsics.imageWidth.toFloat()
        val scaleY = viewHeight.toFloat() / intrinsics.imageHeight.toFloat()
        val fy = intrinsics.fy * scaleY
        val cy = intrinsics.cy * scaleY

        val pitchRad = Math.toRadians(params.effectivePitchDeg.toDouble()).toFloat()
        val rollRad = Math.toRadians(params.rollDeg.toDouble()).toFloat()

        // Horizon vertical offset at center: v = cy + fy * tan(θ)
        // Camera tilted down (negative pitch) moves the horizon up on screen (< cy).
        val vCenter = cy + (fy * tan(pitchRad.toDouble())).toFloat()

        // Account for roll angle across the view width
        val halfW = viewWidth / 2f
        val tanRoll = tan(rollRad.toDouble()).toFloat()

        val leftY = vCenter - halfW * tanRoll
        val rightY = vCenter + halfW * tanRoll

        val left = ScreenPoint(0f, leftY, 0f, leftY / viewHeight, 100f)
        val right = ScreenPoint(viewWidth.toFloat(), rightY, 1f, rightY / viewHeight, 100f)

        return Pair(left, right)
    }

    /**
     * Computes dynamic reverse-camera style ground guidelines and distance ladder
     * anchored to the road plane (Z_road = -params.targetHeightM or ~ -0.50m below radar).
     *
     * Allows precise optical alignment of vehicle boresight, pitch, and roll even
     * when no physical TI mmWave radar is currently streaming packets.
     */
    fun calculateGroundGuideLines(
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int,
        trackHalfWidthM: Float = 0.90f,
        rungHalfWidthM: Float = 1.15f
    ): GroundGuideLines {
        // Road surface elevation relative to radar mounting plane.
        val roadZ = -abs(params.radarHeightM.takeIf { it > 0.1f } ?: 0.95f)

        // 1. Horizon Line
        val horizon = calculateHorizonLine(params, intrinsics, viewWidth, viewHeight)

        // 2. Vehicle Ground Wheel Tracks & Centerline (Y forward: 2m to 35m)
        val ySteps = floatArrayOf(
            2.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.5f, 8.0f, 10.0f, 12.5f, 15.0f, 20.0f, 25.0f, 30.0f, 35.0f
        )

        val leftTrack = mutableListOf<ScreenPoint>()
        val rightTrack = mutableListOf<ScreenPoint>()
        val centerline = mutableListOf<ScreenPoint>()

        for (y in ySteps) {
            val ptL = project2DRadarToScreen(-trackHalfWidthM, y, params, intrinsics, viewWidth, viewHeight, roadZ)
            val ptR = project2DRadarToScreen(trackHalfWidthM, y, params, intrinsics, viewWidth, viewHeight, roadZ)
            val ptC = project2DRadarToScreen(0.0f, y, params, intrinsics, viewWidth, viewHeight, roadZ)

            if (ptL != null) leftTrack.add(ptL)
            if (ptR != null) rightTrack.add(ptR)
            if (ptC != null) centerline.add(ptC)
        }

        // 3. Ground Distance Ladder Rungs
        val baseDistances = mutableListOf(3.0f, 5.0f, 10.0f, 15.0f, 20.0f, 30.0f)
        val targetDist = params.targetDistanceM
        if (baseDistances.none { abs(it - targetDist) < 0.4f }) {
            baseDistances.add(targetDist)
            baseDistances.sort()
        }

        val rungs = mutableListOf<GroundLadderRung>()
        for (d in baseDistances) {
            val isTarget = abs(d - targetDist) < 0.4f
            val halfW = rungHalfWidthM // Full rung width always follows perspective ladder geometry
            val heightZ = roadZ // Ground distance grid is strictly anchored to road plane
            val ptL = project2DRadarToScreen(-halfW, d, params, intrinsics, viewWidth, viewHeight, heightZ)
            val ptR = project2DRadarToScreen(halfW, d, params, intrinsics, viewWidth, viewHeight, heightZ)

            var targetPtL: ScreenPoint? = null
            var targetPtR: ScreenPoint? = null
            if (isTarget) {
                val targetHalfW = params.targetWidthM / 2f
                targetPtL = project2DRadarToScreen(-targetHalfW, d, params, intrinsics, viewWidth, viewHeight, heightZ)
                targetPtR = project2DRadarToScreen(targetHalfW, d, params, intrinsics, viewWidth, viewHeight, heightZ)
            }

            if (ptL != null && ptR != null) {
                val label = if (isTarget) {
                    "${String.format(java.util.Locale.US, "%.1fm TARGET", d)}"
                } else {
                    "${d.toInt()}m"
                }
                rungs.add(
                    GroundLadderRung(
                        distanceM = d,
                        leftPt = ptL,
                        rightPt = ptR,
                        isTarget = isTarget,
                        label = label,
                        targetLeftPt = targetPtL,
                        targetRightPt = targetPtR
                    )
                )
            }
        }

        return GroundGuideLines(
            leftTrack = leftTrack,
            rightTrack = rightTrack,
            centerline = centerline,
            rungs = rungs,
            horizonLine = horizon
        )
    }

    /**
     * Calculates perspective screen projections for radar range rings (concentric distance arcs)
     * at [ranges] (e.g. 10m, 30m, 60m, 120m), longitudinal boresight line (0° to 120m),
     * and ±60° azimuth FOV boundary rays.
     */
    fun calculateRadarRangeOverlay(
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int,
        ranges: List<Float> = listOf(10f, 30f, 60f, 120f)
    ): RadarRangeOverlay {
        val roadZ = -kotlin.math.abs(params.radarHeightM.takeIf { it > 0.1f } ?: 0.95f)

        // Clamp azimuth to the camera's visible horizontal field of view so arcs curve gently without plunging off-screen
        val maxAzimuthDeg = (intrinsics.hfovDeg / 2f).coerceIn(20f, 32f)

        // Stagger label azimuths so 30m, 60m, 120m never collide or overlap vertically near the horizon
        val labelAzimuths = mapOf(10f to 6f, 30f to 14f, 60f to -10f, 120f to -18f)

        // 1. Radar Concentric Range Arcs (Gentle perspective ground curves)
        val arcs = mutableListOf<RadarRangeArc>()
        for (range in ranges) {
            val pts = mutableListOf<ScreenPoint>()
            var labelPt: ScreenPoint? = null
            val targetLabelAz = labelAzimuths[range] ?: 8f

            var azDeg = -maxAzimuthDeg
            while (azDeg <= maxAzimuthDeg) {
                val azRad = Math.toRadians(azDeg.toDouble()).toFloat()
                val xR = range * sin(azRad)
                val yR = range * cos(azRad)
                val pt = project2DRadarToScreen(xR, yR, params, intrinsics, viewWidth, viewHeight, roadZ)
                if (pt != null) {
                    // Only include points within the camera viewport width (plus small margin for smooth edge exit)
                    if (pt.xPx >= -20f && pt.xPx <= viewWidth + 20f) {
                        pts.add(pt)
                    }
                    if (abs(azDeg - targetLabelAz) < 1.5f || (labelPt == null && azDeg >= 0)) {
                        labelPt = pt
                    }
                }
                azDeg += 1.5f
            }
            if (pts.isNotEmpty()) {
                if (labelPt == null) labelPt = pts[pts.size / 2]
                arcs.add(
                    RadarRangeArc(
                        rangeM = range,
                        points = pts,
                        labelPoint = labelPt,
                        label = "${range.toInt()}m"
                    )
                )
            }
        }

        // 2. Center Boresight Ray (0° Azimuth from 2m to 120m)
        val boresightPts = mutableListOf<ScreenPoint>()
        var d = 2.0f
        while (d <= 120.0f) {
            val pt = project2DRadarToScreen(0f, d, params, intrinsics, viewWidth, viewHeight, roadZ)
            if (pt != null) {
                boresightPts.add(pt)
            }
            d += if (d < 20f) 1.0f else 5.0f
        }

        return RadarRangeOverlay(
            arcs = arcs,
            boresightRay = boresightPts,
            leftFovRay = emptyList(), // Omit diagonal 60° rays that cut awkwardly across the camera corners
            rightFovRay = emptyList()
        )
    }

    /**
     * Projects a horizontal circle/ellipse on the road asphalt plane (Z = -H_radar)
     * centered at (xRadar, yRadar) with physical radii [radiusXM] and [radiusYM].
     *
     * In camera perspective, this horizontal circle foreshortens into an authentic 2D ellipse
     * lying flat on the asphalt surface.
     */
    fun calculateGroundFootprintEllipse(
        xRadar: Float,
        yRadar: Float,
        radiusXM: Float,
        radiusYM: Float,
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int,
        numSegments: Int = 12
    ): ProjectedGroundFootprint? {
        val roadZ = -abs(params.radarHeightM.takeIf { it > 0.1f } ?: 0.95f)
        val center = project2DRadarToScreen(xRadar, yRadar, params, intrinsics, viewWidth, viewHeight, roadZ) ?: return null

        val polygon = ArrayList<ScreenPoint>(numSegments)
        val stepRad = (2.0 * PI / numSegments).toFloat()

        for (i in 0 until numSegments) {
            val theta = i * stepRad
            val px = xRadar + radiusXM * cos(theta)
            val py = yRadar + radiusYM * sin(theta)
            if (py <= 0.3f) continue
            val screenPt = project2DRadarToScreen(px, py, params, intrinsics, viewWidth, viewHeight, roadZ)
            if (screenPt != null) {
                polygon.add(screenPt)
            }
        }

        if (polygon.size < 4) return null
        return ProjectedGroundFootprint(center = center, polygon = polygon)
    }

    /**
     * Projects hybrid RViz-style radar lollipops (vertical stems + ground footprints + head badges)
     * from a [RadarFrame], sorted descending by depth (Painter's Algorithm) to guarantee proper
     * visual occlusion ordering on the 2D viewfinder canvas.
     *
     * @param filterUntrackedClutter If true, untracked points are omitted or minimized to avoid visual clutter.
     * @param trackGatingRadiusM Distance in meters to associate raw points with tracked obstacles.
     */
    fun calculateRadarLollipops(
        radarFrame: RadarFrame,
        params: CalibrationParameters,
        intrinsics: CameraIntrinsics,
        viewWidth: Int,
        viewHeight: Int,
        filterUntrackedClutter: Boolean = false,
        trackGatingRadiusM: Float = 1.8f
    ): List<ProjectedRadarLollipop> {
        val roadZ = -abs(params.radarHeightM.takeIf { it > 0.1f } ?: 0.95f)
        val lollipops = mutableListOf<ProjectedRadarLollipop>()

        // 1. Process Active Tracked Targets (TLV Type 3)
        val trackedPositions = mutableListOf<Pair<Float, Float>>()
        for (track in radarFrame.tracks) {
            val x = track.x
            val y = track.y
            if (y <= 0.5f) continue

            trackedPositions.add(Pair(x, y))

            // Ground base anchor (road surface)
            val basePt = project2DRadarToScreen(x, y, params, intrinsics, viewWidth, viewHeight, roadZ) ?: continue

            // Head centroid (raised to vehicle body center of mass, ~0.80m above road)
            val headZ = roadZ + 0.80f
            val headPt = project2DRadarToScreen(x, y, params, intrinsics, viewWidth, viewHeight, headZ) ?: continue

            // Ground footprint ellipse
            val rx = (track.xSize / 2f).coerceIn(0.7f, 1.4f)
            val ry = (track.ySize / 2f).coerceIn(0.7f, 1.6f)
            val footprint = calculateGroundFootprintEllipse(x, y, rx, ry, params, intrinsics, viewWidth, viewHeight, 12)

            val range = sqrt(x * x + y * y)
            val label = "ID:${track.tid} • ${range.toInt()}m"

            lollipops.add(
                ProjectedRadarLollipop(
                    basePt = basePt,
                    headPt = headPt,
                    footprint = footprint,
                    depthM = basePt.depthM,
                    rangeM = range,
                    isTracked = true,
                    trackId = track.tid,
                    dopplerMps = track.vy,
                    snrDb = 35f,
                    label = label
                )
            )
        }

        // 2. Process Radar Points (TLV Type 1)
        for (pt in radarFrame.points) {
            if (pt.y <= 0.5f) continue

            // Check if point is associated with an existing track
            val isNearTrack = trackedPositions.any { (tx, ty) ->
                val dx = pt.x - tx
                val dy = pt.y - ty
                (dx * dx + dy * dy) <= (trackGatingRadiusM * trackGatingRadiusM)
            }

            if (filterUntrackedClutter && !isNearTrack) continue

            // Ground base anchor
            val basePt = project2DRadarToScreen(pt.x, pt.y, params, intrinsics, viewWidth, viewHeight, roadZ) ?: continue

            // Head point: use measured elevation if non-zero, otherwise default to slight vertical stem
            val headZ = if (abs(pt.z) > 0.05f) (roadZ + abs(pt.z)).coerceAtMost(roadZ + 1.2f) else (roadZ + 0.45f)
            val headPt = project2DRadarToScreen(pt.x, pt.y, params, intrinsics, viewWidth, viewHeight, headZ) ?: continue

            // Subtle micro footprint for points associated with tracks
            val footprint = if (isNearTrack) {
                calculateGroundFootprintEllipse(pt.x, pt.y, 0.30f, 0.30f, params, intrinsics, viewWidth, viewHeight, 8)
            } else null

            val range = sqrt(pt.x * pt.x + pt.y * pt.y)

            lollipops.add(
                ProjectedRadarLollipop(
                    basePt = basePt,
                    headPt = headPt,
                    footprint = footprint,
                    depthM = basePt.depthM,
                    rangeM = range,
                    isTracked = isNearTrack,
                    trackId = null,
                    dopplerMps = pt.doppler,
                    snrDb = pt.snrDb,
                    label = "${range.toInt()}m"
                )
            )
        }

        // 3. Painter's Algorithm Depth-Sorting: Farthest targets drawn first, nearest targets drawn last
        lollipops.sortByDescending { it.depthM }

        return lollipops
    }
}

/**
 * Data representation for a projected radar range arc at a specific radial distance [rangeM].
 */
data class RadarRangeArc(
    val rangeM: Float,
    val points: List<ScreenPoint>,
    val labelPoint: ScreenPoint?,
    val label: String
)

/**
 * Complete radar spatial frustum & range overlay model.
 */
data class RadarRangeOverlay(
    val arcs: List<RadarRangeArc>,
    val boresightRay: List<ScreenPoint>,
    val leftFovRay: List<ScreenPoint>,
    val rightFovRay: List<ScreenPoint>
)

/**
 * Data representation for a ground distance ladder rung (transverse bar).
 * [leftPt] and [rightPt] define the full perspective ladder bar (2.0m physical width).
 * When [isTarget] is true, [targetLeftPt] and [targetRightPt] define the inner target vehicle
 * caliper markings (e.g. 1.3m physical width).
 */
data class GroundLadderRung(
    val distanceM: Float,
    val leftPt: ScreenPoint,
    val rightPt: ScreenPoint,
    val isTarget: Boolean,
    val label: String,
    val targetLeftPt: ScreenPoint? = null,
    val targetRightPt: ScreenPoint? = null
)

/**
 * Complete ground guide lines model containing wheel track rails, centerline,
 * and distance ladder rungs.
 */
data class GroundGuideLines(
    val leftTrack: List<ScreenPoint>,
    val rightTrack: List<ScreenPoint>,
    val centerline: List<ScreenPoint>,
    val rungs: List<GroundLadderRung>,
    val horizonLine: Pair<ScreenPoint, ScreenPoint>
)

/**
 * Closed polygon footprint projected flat on the road asphalt plane (Z_road = -H_radar).
 */
data class ProjectedGroundFootprint(
    val center: ScreenPoint,
    val polygon: List<ScreenPoint>
)

/**
 * Representation of a hybrid radar detection (RViz Lollipop + Ground Plane Footprint).
 *
 * [basePt] is the ground contact point on the road asphalt (Z = -H_radar).
 * [headPt] is the target centroid/mass center (Z = Z_road + targetHeightM, or Z_pt).
 * [footprint] is the perspective-foreshortened ellipse flat on the asphalt (if available).
 * [depthM] is the forward optical distance along the camera Z axis for Painter's sorting.
 */
data class ProjectedRadarLollipop(
    val basePt: ScreenPoint,
    val headPt: ScreenPoint,
    val footprint: ProjectedGroundFootprint?,
    val depthM: Float,
    val rangeM: Float,
    val isTracked: Boolean,
    val trackId: Int?,
    val dopplerMps: Float,
    val snrDb: Float,
    val label: String
)
