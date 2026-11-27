package com.bajajauto.roadsense.ui.components.gl

import com.bajajauto.roadsense.models.RadarAccTarget
import com.bajajauto.roadsense.models.RadarBsdAlert
import com.bajajauto.roadsense.models.RadarCanOutputs
import com.bajajauto.roadsense.models.RadarCluster
import com.bajajauto.roadsense.models.RadarFcwAlert
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RadarPoint
import com.bajajauto.roadsense.models.RadarTrack
import com.bajajauto.roadsense.models.RadarTrackerDiagnostics
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BevGlVertexPackerTest {

    private fun makeHeader(frameNum: Long = 42L) = RadarHeader(
        version = 0x01020003L,
        totalPacketLen = 256,
        platform = 0xA1843L,
        frameNumber = frameNum,
        timeCpuCycles = 12345L,
        numDetectedObj = 3,
        numTLVs = 4,
        subFrameNumber = 0
    )

    private fun makeFrame(
        frameNum: Long = 42L,
        points: List<RadarPoint> = emptyList(),
        clusters: List<RadarCluster> = emptyList(),
        tracks: List<RadarTrack> = emptyList(),
        canOutputs: RadarCanOutputs? = null,
        diagnostics: RadarTrackerDiagnostics? = null
    ): RadarFrame {
        val header = makeHeader(frameNum)
        return RadarFrame(
            header = header,
            points = points,
            tracks = tracks,
            clusters = clusters,
            canOutputs = canOutputs,
            diagnostics = diagnostics,
            rawPacket = RawRadarPacket(header = header, payload = byteArrayOf(), hostTimestampNs = frameNum * 50_000_000L)
        )
    }

    @Test
    fun filtersPointsByDopplerAndSnrAndAssignsCorrectColorIds() {
        val frame = makeFrame(
            points = listOf(
                RadarPoint(x = -2f, y = 20f, z = 0f, doppler = -3.0f, snrDb = 22f), // Approaching, high SNR
                RadarPoint(x = 2f, y = 30f, z = 0f, doppler = 2.5f, snrDb = 18f),   // Receding, high SNR
                RadarPoint(x = 0f, y = 15f, z = 0f, doppler = 0.1f, snrDb = 25f),   // Stationary, high SNR
                RadarPoint(x = 1f, y = 25f, z = 0f, doppler = -4.0f, snrDb = 10f),  // Low SNR (<15 dB)
                RadarPoint(x = 0f, y = -5f, z = 0f, doppler = -5.0f, snrDb = 30f)   // Behind origin (culled)
            ),
            clusters = listOf(
                RadarCluster(x = 0f, y = 22f, cid = 1, xSize = 1f, ySize = 2f)
            )
        )

        val metrics = BevProjectionMath.computeMetrics(1200f, 800f, 100f)
        val atlas = BevGlyphAtlasMetrics.defaultForTests()
        val packer = BevGlVertexPacker()

        packer.packFrame(
            frame = frame,
            metrics = metrics,
            atlas = atlas,
            dynamicOnly = false,
            minSnrFilter = false,
            showGrid = true,
            showClusters = true,
            showRawPoints = true,
            showTracks = true
        )
        assertEquals(4, packer.pointCount)
        assertEquals(1, packer.clusterRingCount)
        val arr = packer.pointsBackingArray()
        val stride = BevGlVertexPacker.POINT_FLOATS
        // Layout per point: [x, y, z, normalizedScalar, alpha]
        // Doppler VELOCITY span=10 m/s: -3.0 -> 0.35 (Appr), +2.5 -> 0.625 (Rec), +0.1 -> 0.505 (Stat), -4.0 -> 0.30 (Appr)
        assertEquals(0.35f, arr[0 * stride + 3], 1e-4f)
        assertEquals(0.625f, arr[1 * stride + 3], 1e-4f)
        assertEquals(0.505f, arr[2 * stride + 3], 1e-4f)
        assertEquals(0.30f, arr[3 * stride + 3], 1e-4f)
        assertEquals(1.0f, arr[0 * stride + 4], 1e-5f)
        val rgb = FloatArray(3)
        BevPanelColorizer.evalPointRgb(BevPanelColorizer.SCHEME_DOPPLER, arr[0 * stride + 3], rgb)
        assertTrue(rgb[0] > 0.9f && rgb[1] < 0.4f) // Approaching #FF5252
        BevPanelColorizer.evalPointRgb(BevPanelColorizer.SCHEME_DOPPLER, arr[1 * stride + 3], rgb)
        assertTrue(rgb[2] > 0.9f && rgb[0] < 0.3f) // Receding #40C4FF
        BevPanelColorizer.evalPointRgb(BevPanelColorizer.SCHEME_DOPPLER, arr[2 * stride + 3], rgb)
        assertTrue(rgb[1] > 0.9f)                  // Stationary #69F0AE

        packer.packFrame(
            frame = frame,
            metrics = metrics,
            atlas = atlas,
            dynamicOnly = true,
            minSnrFilter = true,
            showGrid = false,
            showClusters = false,
            showRawPoints = true,
            showTracks = false
        )
        assertEquals(2, packer.pointCount)
        assertEquals(0, packer.clusterRingCount)
        assertEquals(0, packer.textVertexCount)
        assertEquals(3, packer.solidVertexCount)
    }

    @Test
    fun packsTracksVelocityVectorsFcwBannerAndFallbackTarget() {
        val frame = makeFrame(
            tracks = listOf(
                RadarTrack(
                    tid = 12, x = 0.5f, y = 20f, vx = 0.2f, vy = -8.0f,
                    xSize = 1.8f, ySize = 4.2f, clusterId = 1, risk = 0
                )
            ),
            canOutputs = RadarCanOutputs(
                fcw = RadarFcwAlert(stage = 2, trackId = 99, ttcSec = 1.4f, targetY = 45.0f, targetX = -1.0f, targetVy = -6f, targetVx = 0f),
                bsd = RadarBsdAlert(false, false, 0, 0f),
                acc = RadarAccTarget(poiId = 12, targetY = 20f, targetX = 0.5f, targetVy = -8f, ttiSec = 2.5f, vSafeMps = 10f)
            )
        )

        val metrics = BevProjectionMath.computeMetrics(1200f, 800f, 100f)
        val atlas = BevGlyphAtlasMetrics.defaultForTests()
        val packer = BevGlVertexPacker()

        packer.packFrame(
            frame = frame,
            metrics = metrics,
            atlas = atlas,
            dynamicOnly = false,
            minSnrFilter = false,
            showGrid = true,
            showClusters = false,
            showRawPoints = false,
            showTracks = true
        )

        assertEquals(2, packer.trackRingCount)
        val rings = packer.ringsBackingArray()
        assertEquals(BevGlVertexPacker.STYLE_TRACK_ACC, rings[3], 1e-5f)
        assertEquals(BevGlVertexPacker.STYLE_FCW_FALLBACK, rings[7], 1e-5f)
        assertEquals(30, packer.solidVertexCount)
        assertTrue("Expected text quads > 0, got ${packer.textVertexCount}", packer.textVertexCount > 0)
        assertEquals(0, packer.textVertexCount % BevGlVertexPacker.VERTS_PER_GLYPH)
    }

    @Test
    fun packFrame3DEncodesLongitudinalDistanceColorElevationBoxesAndBillboardLabels() {
        val frame = makeFrame(
            points = listOf(
                RadarPoint(x = -1.0f, y = 10f, z = -4.5f, doppler = -2f, snrDb = 20f),
                RadarPoint(x = 2.0f, y = 50f, z = 28.5f, doppler = 0f, snrDb = 22f),
                RadarPoint(x = 0.0f, y = 120f, z = 0.0f, doppler = 3f, snrDb = 19f) // >1.25x @ 30m still kept in 3D
            ),
            clusters = listOf(RadarCluster(x = 1.0f, y = 25f, cid = 1)),
            tracks = listOf(
                RadarTrack(
                    tid = 7, x = 0.5f, y = 30f, vx = 0f, vy = -5f,
                    xSize = 2.0f, ySize = 0.9f, majorSize = 2.0f, minorSize = 0.9f,
                    orientationDeg = 15f, risk = 2
                )
            ),
            canOutputs = RadarCanOutputs(
                fcw = RadarFcwAlert(stage = 2, trackId = 7, ttcSec = 1.2f, targetY = 30f, targetX = 0.5f, targetVy = -5f, targetVx = 0f),
                bsd = RadarBsdAlert(false, false, 0, 0f),
                acc = RadarAccTarget(0, 0f, 0f, 0f, 0f, 0f)
            )
        )
        val mvp3d = FloatArray(16)
        BevCameraController.buildPerspectiveMvpMatrix(BevCameraState(is3D = true), 1200f, 800f, 30f, mvp3d)
        val atlas = BevGlyphAtlasMetrics.defaultForTests()
        val packer = BevGlVertexPacker()

        packer.packFrame3D(
            frame = frame,
            widthPx = 1200f,
            heightPx = 800f,
            maxRangeMeters = 30f,
            mvp3d16 = mvp3d,
            atlas = atlas,
            dynamicOnly = false,
            minSnrFilter = false,
            showGrid = true,
            showClusters = true,
            showRawPoints = true,
            showTracks = true
        )

        assertEquals(6, packer.grid3dVertexCount)
        assertEquals(3, packer.pointCount)
        assertEquals(1, packer.clusterRingCount)
        assertEquals(1, packer.trackRingCount)
        assertTrue("3D wireframes (ego + track box + halo + velocity) must emit lines", packer.lineVertexCount > 40)
        assertTrue("3D billboard labels + FCW banner must emit text quads", packer.textVertexCount > 0)

        // Verify normalized longitudinal distance (y / 30m clamped [0..1]) and raw unclamped Z preserved
        val pts = packer.pointsBackingArray()
        val stride = BevGlVertexPacker.POINT_FLOATS
        assertEquals(10f / 30f, pts[0 * stride + 3], 1e-4f)
        assertEquals(-4.5f, pts[0 * stride + 2], 1e-4f) // negative elevation preserved without clamping
        assertEquals(1.0f, pts[1 * stride + 3], 1e-4f)      // > maxRange clamped to 1.0 in color ramp
        assertEquals(28.5f, pts[1 * stride + 2], 1e-4f) // high elevation preserved without ceiling clipping
        assertEquals(1.0f, pts[2 * stride + 3], 1e-4f)      // far 120m point preserved at 30m range scale
        assertEquals(0.05f, pts[2 * stride + 2], 1e-4f) // exact z=0 lifted slightly above ground grid
    }

    @Test
    fun persistenceTrailsAccumulateUpToFourFramesWithAgeAlphaDecay() {
        val metrics = BevProjectionMath.computeMetrics(1200f, 800f, 100f)
        val atlas = BevGlyphAtlasMetrics.defaultForTests()
        val packer = BevGlVertexPacker()

        for (seq in 1L..5L) {
            val f = makeFrame(
                frameNum = seq,
                points = listOf(RadarPoint(x = 0f, y = 10f * seq, z = 0f, doppler = -2f, snrDb = 20f))
            )
            packer.packFrame(
                frame = f,
                metrics = metrics,
                atlas = atlas,
                dynamicOnly = false,
                minSnrFilter = false,
                showGrid = false,
                showClusters = false,
                showRawPoints = true,
                showTracks = false,
                trailEnabled = true
            )
        }

        // Default trailEnabled=true retains 4 frames with increasing alpha oldest -> newest
        assertEquals(4, packer.pointCount)
        val pts = packer.pointsBackingArray()
        val stride = BevGlVertexPacker.POINT_FLOATS
        val a0 = pts[0 * stride + 4]
        val a3 = pts[3 * stride + 4]
        assertTrue("Oldest trail point alpha ($a0) must be dimmer than newest ($a3)", a0 < a3)
        assertEquals(1.0f, a3, 1e-5f)

        // Full 1.0s preset (20 frames) caps cleanly at MAX_TRAIL_FRAMES = 20
        val packer20 = BevGlVertexPacker()
        for (seq in 1L..25L) {
            val f = makeFrame(
                frameNum = seq,
                points = listOf(RadarPoint(x = 0f, y = 4f * seq, z = 0f, doppler = -2f, snrDb = 20f))
            )
            packer20.packFrame(
                frame = f,
                metrics = metrics,
                atlas = atlas,
                dynamicOnly = false,
                minSnrFilter = false,
                showGrid = false,
                showClusters = false,
                showRawPoints = true,
                showTracks = false,
                decayFrameCount = BevDecayPreset.SEC_1.frameCount
            )
        }
        assertEquals(BevGlVertexPacker.MAX_TRAIL_FRAMES, packer20.pointCount)
        val p20 = packer20.pointsBackingArray()
        assertTrue(p20[4] < p20[(BevGlVertexPacker.MAX_TRAIL_FRAMES - 1) * stride + 4])
    }

    @Test
    fun foxgloveStixelsTlv4LanesOutlierColorAndAdaptiveSnrWorkIn3D() {
        val frame = makeFrame(
            points = listOf(
                RadarPoint(x = -0.8f, y = 12f, z = 2.4f, doppler = -1f, snrDb = 2.0f, clusterId = 3, isOutlier = false),
                RadarPoint(x = 1.6f, y = 24f, z = 1.2f, doppler = 0.1f, snrDb = 58.0f, clusterId = 0, isOutlier = true)
            ),
            diagnostics = RadarTrackerDiagnostics(
                numInliers = 1, ransacSuccessful = true, motionState = 1,
                filteredVxIir = 0f, filteredVyIir = 10f,
                egoEkfVy = 10f, egoEkfVx = 0f, egoEkfAx = 0f, egoEkfAy = 0f, egoEkfYawRate = 0f,
                roadBoundaryLeftX = -3.8f, roadBoundaryRightX = 4.2f,
                axDynamics = 0f, trackerProcTimeUs = 500, imuStuckFlag = false
            )
        )
        val mvp3d = FloatArray(16)
        BevCameraController.buildPerspectiveMvpMatrix(BevCameraState(is3D = true), 1200f, 800f, 60f, mvp3d)
        val atlas = BevGlyphAtlasMetrics.defaultForTests()
        val packer = BevGlVertexPacker()

        // 1. Stixels + Road Lanes + Smart SNR outside 5..35 dB
        packer.packFrame3D(
            frame = frame, widthPx = 1200f, heightPx = 800f, maxRangeMeters = 60f,
            mvp3d16 = mvp3d, atlas = atlas,
            dynamicOnly = false, minSnrFilter = false, showGrid = false, showClusters = false,
            showRawPoints = true, showTracks = false,
            colorMode = BevPointColorMode.SNR, colorMap = BevColorMap.TURBO,
            stixelsEnabled = true, hideOutliers = false, showRoadLanes = true
        )
        assertEquals(2, packer.pointCount)
        assertEquals(2.0f, packer.currentSnrMinDb, 1e-4f)
        assertEquals(58.0f, packer.currentSnrMaxDb, 1e-4f)
        val ptsSnr = packer.pointsBackingArray()
        assertEquals(0.0f, ptsSnr[0 * BevGlVertexPacker.POINT_FLOATS + 3], 1e-4f)
        assertEquals(1.0f, ptsSnr[1 * BevGlVertexPacker.POINT_FLOATS + 3], 1e-4f)
        // Ego box (24 verts) + 4 lane/boundary lines (±1.5m FCW + 2 TLV4 = 8 verts) + 2 stixels (4 verts) = 36 line vertices
        val linesWithStixelsAndLanes = packer.lineVertexCount

        // Disable stixels & lanes + verify FlipZ + HideOutliers
        packer.packFrame3D(
            frame = frame, widthPx = 1200f, heightPx = 800f, maxRangeMeters = 60f,
            mvp3d16 = mvp3d, atlas = atlas,
            dynamicOnly = false, minSnrFilter = false, showGrid = false, showClusters = false,
            showRawPoints = true, showTracks = false,
            colorMode = BevPointColorMode.OUTLIER,
            stixelsEnabled = false, hideOutliers = true, showRoadLanes = false, flipZ = true
        )
        assertEquals(1, packer.pointCount) // Outlier hidden cleanly
        assertEquals(-2.4f, packer.pointsBackingArray()[2], 1e-4f) // Z flipped from +2.4f -> -2.4f
        assertEquals(0.0f, packer.pointsBackingArray()[3], 1e-4f)  // Inlier scalar = 0.0
        assertEquals(linesWithStixelsAndLanes - 12, packer.lineVertexCount)
    }

    @Test
    fun dynamicBuffersGrowAutomaticallyWithoutOverflowForDensePointClouds() {
        val densePoints = List(2500) { idx ->
            RadarPoint(
                x = ((idx % 40) - 20) * 0.5f,
                y = 2f + (idx / 40) * 1.2f,
                z = 0f,
                doppler = if (idx % 2 == 0) -2.0f else 2.0f,
                snrDb = 20f
            )
        }
        val frame = makeFrame(points = densePoints)
        val metrics = BevProjectionMath.computeMetrics(1200f, 800f, 100f)
        val atlas = BevGlyphAtlasMetrics.defaultForTests()
        val packer = BevGlVertexPacker(initialPointCapacity = 64)

        packer.packFrame(
            frame = frame,
            metrics = metrics,
            atlas = atlas,
            dynamicOnly = false,
            minSnrFilter = false,
            showGrid = false,
            showClusters = false,
            showRawPoints = true,
            showTracks = false
        )

        assertEquals(2500, packer.pointCount)
        assertEquals(2500 * BevGlVertexPacker.POINT_FLOATS * 4, packer.pointsByteSize())
        assertTrue(packer.pointsCapacityBytes() >= packer.pointsByteSize())
        assertEquals(2500 * BevGlVertexPacker.POINT_FLOATS, packer.pointsBuffer().remaining())
    }
}
