package com.bajajauto.roadsense.fusion

import com.bajajauto.roadsense.fusion.engine.CameraIntrinsics
import com.bajajauto.roadsense.fusion.engine.SpatialProjectionEngine
import com.bajajauto.roadsense.fusion.model.CalibrationParameters
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RadarPoint
import com.bajajauto.roadsense.models.RadarTrack
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.*
import org.junit.Test

class SpatialProjectionEngineTest {

    private val viewW = 1920
    private val viewH = 1080
    // Simple reference intrinsics: fx = fy = 1400, cx = 960, cy = 540
    private val testIntrinsics = CameraIntrinsics(
        fx = 1400f,
        fy = 1400f,
        cx = 960f,
        cy = 540f,
        imageWidth = viewW,
        imageHeight = viewH,
        hfovDeg = 68f,
        vfovDeg = 42f
    )

    @Test
    fun testForwardProjectionAtZeroAngles() {
        val params = CalibrationParameters(
            setbackM = 0.0f,
            heightOffsetM = 0.0f,
            lateralOffsetM = 0.0f,
            targetDistanceM = 10.0f,
            targetHeightM = 0.0f,
            pitchDeg = 0.0f,
            yawDeg = 0.0f,
            rollDeg = 0.0f
        )

        val pt = SpatialProjectionEngine.project2DRadarToScreen(
            xRadar = 0.0f,
            yRadar = 10.0f,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH
        )

        assertNotNull("Projected point should not be null", pt)
        assertEquals("Target straight ahead should project to cx", 960f, pt!!.xPx, 0.01f)
        assertEquals("Target at elevation 0 should project to cy", 540f, pt.yPx, 0.01f)
        assertEquals("Normalized X should be 0.5", 0.5f, pt.normX, 0.001f)
        assertEquals("Normalized Y should be 0.5", 0.5f, pt.normY, 0.001f)
    }

    @Test
    fun testInverseSolverRecoversAngles() {
        val truePitch = -3.5f
        val trueYaw = 2.0f

        val params = CalibrationParameters(
            setbackM = 1.80f,
            heightOffsetM = 0.60f,
            lateralOffsetM = 0.0f,
            targetDistanceM = 10.0f,
            targetHeightM = 0.50f,
            pitchDeg = truePitch,
            yawDeg = trueYaw,
            rollDeg = 0.0f
        )

        // 1. Forward project target
        val pt = SpatialProjectionEngine.project2DRadarToScreen(
            xRadar = 0.0f,
            yRadar = params.targetDistanceM,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH
        )

        assertNotNull("Target point must project on screen", pt)

        // 2. Feed screen touch position into inverse solver
        val solvedAngles = SpatialProjectionEngine.solveExtrinsicsFromTouch(
            normX = pt!!.normX,
            normY = pt.normY,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH
        )

        assertEquals("Solved Pitch must match ground truth", truePitch, solvedAngles.first, 0.1f)
        assertEquals("Solved Yaw must match ground truth", trueYaw, solvedAngles.second, 0.1f)
    }

    @Test
    fun testNudgeBehavior() {
        val baseParams = CalibrationParameters(
            setbackM = 1.80f,
            heightOffsetM = 0.60f,
            targetDistanceM = 10.0f,
            pitchDeg = 0.0f,
            yawDeg = 0.0f
        )

        val ptBase = SpatialProjectionEngine.project2DRadarToScreen(
            0f, 10f, baseParams, testIntrinsics, viewW, viewH
        )!!

        // Apply pitch nudge (+1.0°) -> camera tilts down, point appears higher/lower
        val pitchNudgedParams = baseParams.copy(nudgePitchDeg = 1.0f)
        val ptPitchNudged = SpatialProjectionEngine.project2DRadarToScreen(
            0f, 10f, pitchNudgedParams, testIntrinsics, viewW, viewH
        )!!

        assertTrue("Pitch down should translate pixels vertically", ptPitchNudged.yPx > ptBase.yPx)

        // Apply yaw nudge (+1.0°) -> camera pans right, point shifts left on sensor
        val yawNudgedParams = baseParams.copy(nudgeYawDeg = 1.0f)
        val ptYawNudged = SpatialProjectionEngine.project2DRadarToScreen(
            0f, 10f, yawNudgedParams, testIntrinsics, viewW, viewH
        )!!

        assertTrue("Yaw right should shift target left in pixels", ptYawNudged.xPx < ptBase.xPx)
    }

    @Test
    fun testJsonSerialization() {
        val original = CalibrationParameters(
            setbackM = 0.30f,
            heightOffsetM = 0.50f,
            lateralOffsetM = 0.02f,
            targetDistanceM = 12.5f,
            targetHeightM = 0.00f,
            radarHeightM = 0.95f,
            pitchDeg = -4.2f,
            yawDeg = 1.1f,
            rollDeg = -0.5f,
            profileName = "IRVM Bonnet Mount"
        )

        val json = original.toJson()
        val restored = CalibrationParameters.fromJson(json)

        assertEquals(original.setbackM, restored.setbackM, 0.001f)
        assertEquals(original.heightOffsetM, restored.heightOffsetM, 0.001f)
        assertEquals(original.lateralOffsetM, restored.lateralOffsetM, 0.001f)
        assertEquals(original.targetDistanceM, restored.targetDistanceM, 0.001f)
        assertEquals(original.targetHeightM, restored.targetHeightM, 0.001f)
        assertEquals(original.radarHeightM, restored.radarHeightM, 0.001f)
        assertEquals(original.pitchDeg, restored.pitchDeg, 0.001f)
        assertEquals(original.yawDeg, restored.yawDeg, 0.001f)
        assertEquals(original.rollDeg, restored.rollDeg, 0.001f)
        assertEquals(original.profileName, restored.profileName)
    }

    @Test
    fun testCalculateHorizonLineMatchesFarPoint() {
        val params = CalibrationParameters(
            pitchDeg = -2.0f,
            rollDeg = 0.0f
        )
        val horizon = SpatialProjectionEngine.calculateHorizonLine(params, testIntrinsics, viewW, viewH)
        
        // At roll = 0, horizon line is horizontal: left.yPx == right.yPx == vCenter
        assertEquals(horizon.first.yPx, horizon.second.yPx, 0.001f)

        // Project a virtual point on the horizon far away (yRadar = 5000m, targetHeightM = heightOffsetM so zRel = 0)
        val farPt = SpatialProjectionEngine.project2DRadarToScreen(
            xRadar = 0f,
            yRadar = 5000f,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH,
            targetHeightM = params.heightOffsetM
        )
        assertNotNull(farPt)
        assertEquals("Horizon vCenter must match projection of far point", farPt!!.yPx, horizon.first.yPx, 0.5f)
    }

    @Test
    fun testCalculateGroundGuideLinesGeneratesRungsAndTracks() {
        val params = CalibrationParameters(
            setbackM = 0.30f,
            heightOffsetM = 0.50f,
            targetDistanceM = 10.0f,
            targetWidthM = 1.30f,
            targetHeightM = 0.00f,
            pitchDeg = 0.0f,
            yawDeg = 0.0f
        )

        val guides = SpatialProjectionEngine.calculateGroundGuideLines(params, testIntrinsics, viewW, viewH)

        assertTrue("Should produce left track points", guides.leftTrack.isNotEmpty())
        assertTrue("Should produce right track points", guides.rightTrack.isNotEmpty())
        assertTrue("Should produce centerline points", guides.centerline.isNotEmpty())
        assertTrue("Should produce distance rungs", guides.rungs.isNotEmpty())

        val targetRung = guides.rungs.find { it.isTarget }
        assertNotNull("Must contain target distance rung", targetRung)
        assertEquals(10.0f, targetRung!!.distanceM, 0.01f)
        assertTrue(targetRung.label.contains("TARGET"))
        assertTrue("Rung should span horizontally across center", targetRung.leftPt.xPx < targetRung.rightPt.xPx)

        // Verify theoretical optical pixel width: Δu = fx * (W / Z_cam)
        // With params setback = 0.30m, Z_cam = 10.0m - 0.30m = 9.70m, W = 1.30m, fx = 1400
        assertNotNull(targetRung.targetLeftPt)
        assertNotNull(targetRung.targetRightPt)
        val expectedTargetWidthPx = testIntrinsics.fx * (params.targetWidthM / (params.targetDistanceM - params.setbackM))
        val actualTargetWidthPx = targetRung.targetRightPt!!.xPx - targetRung.targetLeftPt!!.xPx
        assertEquals("Target inner caliper pixel width must match pinhole formula fx * (W / Z)", expectedTargetWidthPx, actualTargetWidthPx, 1.5f)

        // Verify full ladder perspective width: W_ladder = 2.30m (rungHalfWidthM = 1.15m)
        val expectedLadderWidthPx = testIntrinsics.fx * (2.30f / (params.targetDistanceM - params.setbackM))
        val actualLadderWidthPx = targetRung.rightPt.xPx - targetRung.leftPt.xPx
        assertEquals("Target outer ladder rung width must match 2.30m perspective", expectedLadderWidthPx, actualLadderWidthPx, 1.5f)
    }

    @Test
    fun testSimulatedRadarDataProviderGeneratesFramesAndProjects() {
        val params = CalibrationParameters(
            setbackM = 0.30f,
            heightOffsetM = 0.50f,
            targetDistanceM = 10.0f,
            targetWidthM = 1.30f
        )

        // Verify 10 distinct frames can be generated
        for (f in 0 until 10) {
            val frame = com.bajajauto.roadsense.fusion.sim.SimulatedRadarDataProvider.getFrame(
                frameIndex = f,
                targetDist = params.targetDistanceM,
                targetWidth = params.targetWidthM
            )
            assertTrue("Frame $f must contain detection points", frame.points.size >= 14)
            assertTrue("Frame $f must contain tracked car target", frame.tracks.isNotEmpty())

            // Test that bumper point projects onto screen near center
            val bumperPt = frame.points.first()
            val screenPt = SpatialProjectionEngine.project2DRadarToScreen(
                xRadar = bumperPt.x,
                yRadar = bumperPt.y,
                params = params,
                intrinsics = testIntrinsics,
                viewWidth = viewW,
                viewHeight = viewH,
                targetHeightM = bumperPt.z
            )
            assertNotNull("Bumper point in frame $f must project onto viewport", screenPt)
            assertTrue("Point must be within horizontal bounds", screenPt!!.xPx in 0f..viewW.toFloat())
            assertTrue("Point must be within vertical bounds", screenPt.yPx in 0f..viewH.toFloat())
        }
    }

    @Test
    fun testCalculateRadarRangeOverlayGeneratesValidArcs() {
        val params = CalibrationParameters(
            setbackM = 0.30f,
            heightOffsetM = 0.50f,
            targetDistanceM = 10.0f,
            radarHeightM = 0.95f,
            pitchDeg = 0.0f,
            yawDeg = 0.0f
        )

        val overlay = SpatialProjectionEngine.calculateRadarRangeOverlay(
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH,
            ranges = listOf(10f, 30f, 60f, 120f)
        )

        assertEquals("Must generate 4 range arcs", 4, overlay.arcs.size)
        assertTrue("Boresight ray must contain points", overlay.boresightRay.isNotEmpty())
        assertTrue("FOV diagonal rays are omitted for clean display", overlay.leftFovRay.isEmpty())
        assertTrue("FOV diagonal rays are omitted for clean display", overlay.rightFovRay.isEmpty())

        // Verify vertical perspective monotonicity:
        // Closer ranges (10m) appear lower on screen (higher Y pixel coordinate) than distant ranges (120m)
        val arc10Center = overlay.arcs.find { it.rangeM == 10f }?.labelPoint
        val arc30Center = overlay.arcs.find { it.rangeM == 30f }?.labelPoint
        val arc60Center = overlay.arcs.find { it.rangeM == 60f }?.labelPoint
        val arc120Center = overlay.arcs.find { it.rangeM == 120f }?.labelPoint

        assertNotNull(arc10Center)
        assertNotNull(arc30Center)
        assertNotNull(arc60Center)
        assertNotNull(arc120Center)

        assertTrue("10m Y must be greater (lower on screen) than 30m Y", arc10Center!!.yPx > arc30Center!!.yPx)
        assertTrue("30m Y must be greater (lower on screen) than 60m Y", arc30Center.yPx > arc60Center!!.yPx)
        assertTrue("60m Y must be greater (lower on screen) than 120m Y", arc60Center.yPx > arc120Center!!.yPx)

        // Verify horizontal staggering of labels to prevent overlapping text near the horizon
        assertFalse("10m and 30m labels must have staggered X positions", arc10Center.xPx == arc30Center.xPx)
        assertFalse("30m and 60m labels must have staggered X positions", arc30Center.xPx == arc60Center.xPx)
        assertFalse("60m and 120m labels must have staggered X positions", arc60Center.xPx == arc120Center.xPx)

        // Verify arcs stay within visible viewport horizontal bounds
        overlay.arcs.forEach { arc ->
            assertTrue("Arc points for ${arc.rangeM}m must not be empty", arc.points.isNotEmpty())
            arc.points.forEach { pt ->
                assertTrue("Arc point X must be within or near viewport bounds", pt.xPx in -20f..(viewW + 20f))
            }
        }

        // Verify labels
        assertEquals("10m", overlay.arcs[0].label)
        assertEquals("30m", overlay.arcs[1].label)
        assertEquals("60m", overlay.arcs[2].label)
        assertEquals("120m", overlay.arcs[3].label)
    }

    private fun createTestRadarFrame(
        points: List<RadarPoint> = emptyList(),
        tracks: List<RadarTrack> = emptyList()
    ): RadarFrame {
        val dummyHeader = RadarHeader(
            version = 0L,
            totalPacketLen = 40,
            platform = 0L,
            frameNumber = 1L,
            timeCpuCycles = 0L,
            numDetectedObj = points.size,
            numTLVs = 0,
            subFrameNumber = 0
        )
        return RadarFrame(
            header = dummyHeader,
            points = points,
            tracks = tracks,
            clusters = emptyList(),
            rawPacket = RawRadarPacket(dummyHeader, byteArrayOf())
        )
    }

    @Test
    fun testCalculateGroundFootprintProducesConvexPolygon() {
        val params = CalibrationParameters(radarHeightM = 0.95f)
        val footprint = SpatialProjectionEngine.calculateGroundFootprintEllipse(
            xRadar = 0f,
            yRadar = 15f,
            radiusXM = 1.0f,
            radiusYM = 1.0f,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH,
            numSegments = 12
        )
        assertNotNull("Ground footprint should be generated", footprint)
        assertEquals("Polygon should have 12 segments", 12, footprint!!.polygon.size)
    }

    @Test
    fun testLollipopBaseIsStrictlyLowerOnScreenThanHead() {
        // Base is at road level (Z = -0.95), head is raised (Z = -0.15).
        // In camera perspective (looking forward/slightly downward), road contact (base)
        // must have a larger Y pixel value (lower on screen) than the elevated head.
        val frame = createTestRadarFrame(
            points = listOf(RadarPoint(x = 0f, y = 15f, z = 0f, doppler = -2f, snrDb = 30f)),
            tracks = listOf(RadarTrack(tid = 1, x = 0f, y = 15f, vx = 0f, vy = -2f, xSize = 1.8f, ySize = 4.0f))
        )
        val params = CalibrationParameters(radarHeightM = 0.95f)
        val lollipops = SpatialProjectionEngine.calculateRadarLollipops(
            radarFrame = frame,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH
        )
        assertTrue("Lollipops list should not be empty", lollipops.isNotEmpty())
        for (lp in lollipops) {
            assertTrue(
                "Base Y pixel (${lp.basePt.yPx}) must be > Head Y pixel (${lp.headPt.yPx})",
                lp.basePt.yPx > lp.headPt.yPx
            )
        }
    }

    @Test
    fun testLollipopDepthSortingIsMonotonicDescending() {
        // Targets at 10m, 30m, 50m must be sorted 50m first, 30m second, 10m last (Painter's algorithm)
        val frame = createTestRadarFrame(
            tracks = listOf(
                RadarTrack(tid = 1, x = 0f, y = 10f, vx = 0f, vy = 0f, xSize = 1.5f, ySize = 1.5f),
                RadarTrack(tid = 2, x = 0f, y = 50f, vx = 0f, vy = 0f, xSize = 1.5f, ySize = 1.5f),
                RadarTrack(tid = 3, x = 0f, y = 30f, vx = 0f, vy = 0f, xSize = 1.5f, ySize = 1.5f)
            )
        )
        val params = CalibrationParameters(radarHeightM = 0.95f)
        val lollipops = SpatialProjectionEngine.calculateRadarLollipops(
            radarFrame = frame,
            params = params,
            intrinsics = testIntrinsics,
            viewWidth = viewW,
            viewHeight = viewH
        )
        assertEquals(3, lollipops.size)
        assertTrue("First item must be deepest", lollipops[0].depthM >= lollipops[1].depthM)
        assertTrue("Second item must be deeper than third", lollipops[1].depthM >= lollipops[2].depthM)
        assertEquals("Farthest target drawn first", 2, lollipops[0].trackId)
        assertEquals("Nearest target drawn last", 1, lollipops[2].trackId)
    }
}
