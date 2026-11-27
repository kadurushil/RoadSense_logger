package com.bajajauto.roadsense.ui.components.gl

import com.bajajauto.roadsense.models.RadarFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Foxglove-inspired dynamic native buffer packer (`DynamicBufferGeometry` pattern) for OpenGL ES 3.0.
 *
 * Supports both:
 * - **2D Orthographic BEV** (Doppler point coloring, concentric SDF rings, pixel-exact Skia parity, optional zoom/pan & trails), and
 * - **3D Perspective Orbit BEV** (longitudinal-distance point coloring, 3D elevation `pt.z`, extruded 3D ego silhouette,
 *   oriented 3D EKF track bounding boxes, 3D velocity arrows, and screen-projected billboard labels).
 *
 * Pre-allocates direct `ByteBuffer` / `FloatBuffer` pools with `1.5×` growth slack so steady-state
 * 20–60 Hz frames incur **0 heap allocations** and **0 GC pauses**.
 */
class BevGlVertexPacker(
    initialPointCapacity: Int = 1024,
    initialClusterCapacity: Int = 128,
    initialRingCapacity: Int = 128,
    initialSolidVertexCapacity: Int = 1024,
    initialLineVertexCapacity: Int = 512,
    initialTextGlyphCapacity: Int = 256
) {
    companion object {
        // Interleaved float strides
        const val POINT_FLOATS = 5        // x, y, z, colorIdOrNormY, alpha
        const val RING_FLOATS = 4         // sx, sy, outerExtentPx, styleId
        const val SOLID_VERTEX_FLOATS = 7 // x, y, z, r, g, b, a
        const val LINE_VERTEX_FLOATS = 7  // x, y, z, r, g, b, a
        const val GRID3D_FLOATS = 2       // worldX, worldY (z = 0)
        const val TEXT_VERTEX_FLOATS = 8  // sx, sy, u, v, r, g, b, a
        const val VERTS_PER_GLYPH = 6

        const val COLOR_ID_APPR = 0f
        const val COLOR_ID_REC = 1f
        const val COLOR_ID_STAT = 2f

        const val STYLE_CLUSTER = -1f
        const val STYLE_TRACK_DEFAULT = 0f
        const val STYLE_TRACK_ACC = 1f
        const val STYLE_TRACK_FCW = 2f
        const val STYLE_FCW_FALLBACK = 3f

        const val DOPPLER_STATIONARY_THRESH = 0.35f
        const val MIN_SNR_DB = 15f
        const val MAX_TRAIL_FRAMES = 20

        private const val HEX_SEGMENTS = 6
        private val HEX_COS = FloatArray(HEX_SEGMENTS + 1) { i -> cos(2.0 * PI * i / HEX_SEGMENTS).toFloat() }
        private val HEX_SIN = FloatArray(HEX_SEGMENTS + 1) { i -> sin(2.0 * PI * i / HEX_SEGMENTS).toFloat() }
    }

    private class GrowableFloatPool(initialFloats: Int) {
        var byteBuffer: ByteBuffer = allocateDirectFloats(initialFloats)
            private set
        var floatBuffer: FloatBuffer = byteBuffer.asFloatBuffer()
            private set
        var capacityFloats: Int = initialFloats
            private set
        var floatCount: Int = 0
            private set
        var backingArray: FloatArray = FloatArray(initialFloats)
            private set

        fun clear() {
            floatCount = 0
        }

        fun ensureAdditional(extraFloats: Int) {
            val required = floatCount + extraFloats
            if (required <= capacityFloats) return
            val grown = maxOf(required, (capacityFloats * 3) / 2)
            backingArray = backingArray.copyOf(grown)
            byteBuffer = allocateDirectFloats(grown)
            floatBuffer = byteBuffer.asFloatBuffer()
            capacityFloats = grown
        }

        fun put2(a: Float, b: Float) {
            ensureAdditional(2)
            val arr = backingArray
            var i = floatCount
            arr[i++] = a; arr[i++] = b
            floatCount = i
        }

        fun put4(a: Float, b: Float, c: Float, d: Float) {
            ensureAdditional(4)
            val arr = backingArray
            var i = floatCount
            arr[i++] = a; arr[i++] = b; arr[i++] = c; arr[i++] = d
            floatCount = i
        }

        fun put5(a: Float, b: Float, c: Float, d: Float, e: Float) {
            ensureAdditional(5)
            val arr = backingArray
            var i = floatCount
            arr[i++] = a; arr[i++] = b; arr[i++] = c; arr[i++] = d; arr[i++] = e
            floatCount = i
        }

        fun put7(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float) {
            ensureAdditional(7)
            val arr = backingArray
            var i = floatCount
            arr[i++] = a; arr[i++] = b; arr[i++] = c; arr[i++] = d; arr[i++] = e; arr[i++] = f; arr[i++] = g
            floatCount = i
        }

        fun put8(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float, h: Float) {
            ensureAdditional(8)
            val arr = backingArray
            var i = floatCount
            arr[i++] = a; arr[i++] = b; arr[i++] = c; arr[i++] = d
            arr[i++] = e; arr[i++] = f; arr[i++] = g; arr[i++] = h
            floatCount = i
        }

        fun commitToDirectBuffer(): FloatBuffer {
            floatBuffer.clear()
            if (floatCount > 0) {
                floatBuffer.put(backingArray, 0, floatCount)
            }
            floatBuffer.flip()
            return floatBuffer
        }

        private fun allocateDirectFloats(count: Int): ByteBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
    }

    private val pointsPool = GrowableFloatPool(initialPointCapacity * POINT_FLOATS)
    private val ringsPool = GrowableFloatPool((initialClusterCapacity + initialRingCapacity) * RING_FLOATS)
    private val solidPool = GrowableFloatPool(initialSolidVertexCapacity * SOLID_VERTEX_FLOATS)
    private val linesPool = GrowableFloatPool(initialLineVertexCapacity * LINE_VERTEX_FLOATS)
    private val grid3dPool = GrowableFloatPool(6 * GRID3D_FLOATS)
    private val textPool = GrowableFloatPool(initialTextGlyphCapacity * VERTS_PER_GLYPH * TEXT_VERTEX_FLOATS)

    // Reusable scratch buffers (0 heap allocs per frame)
    private val labelSb = StringBuilder(64)
    private val projXY = FloatArray(2)
    private val scratchStixelRgb = FloatArray(3)

    // Adaptive SNR normalization window across visible points (supports SNR outside 5..35 dB)
    var currentSnrMinDb: Float = BevPanelColorizer.DEFAULT_SNR_MIN_DB
        private set
    var currentSnrMaxDb: Float = BevPanelColorizer.DEFAULT_SNR_MAX_DB
        private set
    private var hasSnrBounds = false

    // Lightweight ring buffer of recent frames for configurable decay persistence (up to 1.0s @ 20Hz)
    private val trailFrames = arrayOfNulls<RadarFrame>(MAX_TRAIL_FRAMES)
    private var trailCount = 0
    private var lastTrailSeq = Long.MIN_VALUE

    var pointCount: Int = 0
        private set
    var clusterRingCount: Int = 0
        private set
    var trackRingCount: Int = 0
        private set
    var totalRingCount: Int = 0
        private set
    var solidVertexCount: Int = 0
        private set
    var lineVertexCount: Int = 0
        private set
    var grid3dVertexCount: Int = 0
        private set
    var textVertexCount: Int = 0
        private set

    fun pointsBuffer(): FloatBuffer = pointsPool.floatBuffer
    fun pointsByteSize(): Int = pointsPool.floatCount * 4
    fun pointsCapacityBytes(): Int = pointsPool.capacityFloats * 4
    internal fun pointsBackingArray(): FloatArray = pointsPool.backingArray

    fun ringsBuffer(): FloatBuffer = ringsPool.floatBuffer
    fun ringsByteSize(): Int = ringsPool.floatCount * 4
    fun ringsCapacityBytes(): Int = ringsPool.capacityFloats * 4
    internal fun ringsBackingArray(): FloatArray = ringsPool.backingArray

    fun solidBuffer(): FloatBuffer = solidPool.floatBuffer
    fun solidByteSize(): Int = solidPool.floatCount * 4
    fun solidCapacityBytes(): Int = solidPool.capacityFloats * 4
    internal fun solidBackingArray(): FloatArray = solidPool.backingArray

    fun linesBuffer(): FloatBuffer = linesPool.floatBuffer
    fun linesByteSize(): Int = linesPool.floatCount * 4
    fun linesCapacityBytes(): Int = linesPool.capacityFloats * 4
    internal fun linesBackingArray(): FloatArray = linesPool.backingArray

    fun grid3dBuffer(): FloatBuffer = grid3dPool.floatBuffer
    fun grid3dByteSize(): Int = grid3dPool.floatCount * 4
    fun grid3dCapacityBytes(): Int = grid3dPool.capacityFloats * 4

    fun textBuffer(): FloatBuffer = textPool.floatBuffer
    fun textByteSize(): Int = textPool.floatCount * 4
    fun textCapacityBytes(): Int = textPool.capacityFloats * 4
    internal fun textBackingArray(): FloatArray = textPool.backingArray

    fun clearTrail() {
        for (i in 0 until MAX_TRAIL_FRAMES) trailFrames[i] = null
        trailCount = 0
        lastTrailSeq = Long.MIN_VALUE
    }

    private fun recordTrailFrame(frame: RadarFrame?, decayFrameCount: Int) {
        if (decayFrameCount <= 1 || frame == null) {
            clearTrail()
            return
        }
        val seq = frame.header.frameNumber
        if (seq == lastTrailSeq && trailCount > 0) return
        lastTrailSeq = seq
        if (trailCount < MAX_TRAIL_FRAMES) {
            trailFrames[trailCount++] = frame
        } else {
            for (i in 0 until MAX_TRAIL_FRAMES - 1) {
                trailFrames[i] = trailFrames[i + 1]
            }
            trailFrames[MAX_TRAIL_FRAMES - 1] = frame
        }
    }

    private fun computeTrailAlpha(slotIndex: Int, totalSlots: Int): Float {
        if (totalSlots <= 1) return 1.0f
        val t = (slotIndex + 1).toFloat() / totalSlots.toFloat()
        return 0.16f + 0.84f * t * t
    }

    private fun updateSmartSnrBoundsForFrames(
        frame: RadarFrame,
        decayFrameCount: Int,
        dynamicOnly: Boolean,
        minSnrFilter: Boolean,
        hideOutliers: Boolean
    ) {
        var obsMin = Float.POSITIVE_INFINITY
        var obsMax = Float.NEGATIVE_INFINITY
        var any = false

        fun scanFrame(f: RadarFrame) {
            val pts = f.points
            for (i in pts.indices) {
                val pt = pts[i]
                if (!pt.snrDb.isFinite()) continue
                if ((!dynamicOnly || abs(pt.doppler) > DOPPLER_STATIONARY_THRESH) &&
                    (!minSnrFilter || pt.snrDb >= MIN_SNR_DB) &&
                    (!hideOutliers || !pt.isOutlier)
                ) {
                    if (pt.snrDb < obsMin) obsMin = pt.snrDb
                    if (pt.snrDb > obsMax) obsMax = pt.snrDb
                    any = true
                }
            }
        }

        if (decayFrameCount > 1 && trailCount > 0) {
            val renderCount = minOf(decayFrameCount, trailCount)
            val startIdx = trailCount - renderCount
            for (tIdx in startIdx until trailCount) {
                val tf = trailFrames[tIdx] ?: continue
                scanFrame(tf)
            }
        } else {
            scanFrame(frame)
        }

        if (any) {
            val (nextMin, nextMax) = BevPanelColorizer.computeSmartSnrBounds(
                observedMinDb = obsMin,
                observedMaxDb = obsMax,
                prevMinDb = currentSnrMinDb,
                prevMaxDb = currentSnrMaxDb,
                hasPrev = hasSnrBounds
            )
            currentSnrMinDb = nextMin
            currentSnrMaxDb = nextMax
            hasSnrBounds = true
        }
    }

    /**
     * Packs a 2D Orthographic BEV frame into direct native buffers ready for OpenGL ES 3.0 upload.
     */
    fun packFrame(
        frame: RadarFrame?,
        metrics: BevViewportMetrics,
        atlas: BevGlyphAtlasMetrics,
        dynamicOnly: Boolean,
        minSnrFilter: Boolean,
        showGrid: Boolean,
        showClusters: Boolean,
        showRawPoints: Boolean,
        showTracks: Boolean,
        trailEnabled: Boolean = false,
        decayFrameCount: Int = if (trailEnabled) 4 else 1,
        colorMode: BevPointColorMode = BevPointColorMode.AUTO,
        colorMap: BevColorMap = BevColorMap.TURBO,
        hideOutliers: Boolean = false,
        showRoadLanes: Boolean = false,
        flipZ: Boolean = false
    ) {
        resetCounts()
        val effectiveDecayFrames = decayFrameCount.coerceIn(1, MAX_TRAIL_FRAMES)
        recordTrailFrame(frame, effectiveDecayFrames)

        if (!metrics.isValid) {
            commitAll()
            return
        }

        val originX = metrics.originX
        val originY = metrics.originY
        val scale = metrics.scalePxPerMeter
        val w = metrics.widthPx
        val h = metrics.heightPx
        val maxRangeMeters = metrics.maxRangeMeters
        val resolvedColorMode = colorMode.resolveForView(is3d = false)

        // 0. Road Lanes & TLV 4 Road Boundaries (2D linesPool rendered below ego & targets)
        if (showRoadLanes) {
            packRoadLanes2D(frame, originX, originY, scale, maxRangeMeters)
        }

        // 1. Ego vehicle triangle (first 3 vertices of solidPool, #00E5FF)
        val egoW = 24f
        val egoH = 32f
        pushTriangle2D(
            originX, originY - egoH,
            originX + egoW * 0.5f, originY,
            originX - egoW * 0.5f, originY,
            0.0f, 0.898f, 1.0f, 1.0f
        )

        // 2. Range ring labels (when grid is enabled)
        if (showGrid) {
            var ringDist = metrics.ringStepMeters
            var rCount = 0
            while (ringDist <= metrics.maxVisibleDistMeters && rCount < 16) {
                val labelY = originY - ringDist * scale + 28f
                if (labelY in 8f..(h - 4f)) {
                    labelSb.setLength(0)
                    labelSb.append(ringDist.toInt()).append('m')
                    pushCenteredText(
                        text = labelSb,
                        centerX = originX,
                        baselineY = labelY,
                        textSizePx = 28f,
                        atlas = atlas,
                        r = 176f / 255f, g = 190f / 255f, b = 197f / 255f, a = 160f / 255f
                    )
                }
                rCount++
                ringDist += metrics.ringStepMeters
            }
        }

        // 3. Clusters (TLV Type 2)
        if (showClusters && frame != null) {
            val clusters = frame.clusters
            for (i in clusters.indices) {
                val c = clusters[i]
                val cx = originX + c.x * scale
                val cy = originY - c.y * scale
                if (cy in 0f..originY && cx in 0f..w) {
                    ringsPool.put4(cx, cy, 18.0f, STYLE_CLUSTER)
                    clusterRingCount++
                }
            }
        }

        // 4. Point Cloud Reflections (TLV Type 1, Foxglove color scheme + optional up to 1.0s decay)
        if (showRawPoints && frame != null) {
            if (resolvedColorMode == BevPointColorMode.SNR) {
                updateSmartSnrBoundsForFrames(frame, effectiveDecayFrames, dynamicOnly, minSnrFilter, hideOutliers)
            }
            if (effectiveDecayFrames > 1 && trailCount > 0) {
                val renderCount = minOf(effectiveDecayFrames, trailCount)
                val startIdx = trailCount - renderCount
                for (tIdx in startIdx until trailCount) {
                    val tf = trailFrames[tIdx] ?: continue
                    val alpha = computeTrailAlpha(tIdx - startIdx, renderCount)
                    packPoints2D(
                        frame = tf,
                        originX = originX,
                        originY = originY,
                        scale = scale,
                        w = w,
                        maxRangeMeters = maxRangeMeters,
                        dynamicOnly = dynamicOnly,
                        minSnrFilter = minSnrFilter,
                        hideOutliers = hideOutliers,
                        resolvedColorMode = resolvedColorMode,
                        flipZ = flipZ,
                        alpha = alpha
                    )
                }
            } else {
                packPoints2D(
                    frame = frame,
                    originX = originX,
                    originY = originY,
                    scale = scale,
                    w = w,
                    maxRangeMeters = maxRangeMeters,
                    dynamicOnly = dynamicOnly,
                    minSnrFilter = minSnrFilter,
                    hideOutliers = hideOutliers,
                    resolvedColorMode = resolvedColorMode,
                    flipZ = flipZ,
                    alpha = 1.0f
                )
            }
        }

        // 5. Tracked Targets (TLV Type 3) + ADAS FCW/ACC highlighting (TLV Type 6)
        if (showTracks && frame != null) {
            val packedMatch = BevTrackAssociator.matchPacked(frame)
            val matchedFcwIndex = BevTrackAssociator.unpackFcwIndex(packedMatch)
            val matchedAccIndex = BevTrackAssociator.unpackAccIndex(packedMatch)
            val fcwAlert = frame.canOutputs?.fcw
            val tracks = frame.tracks

            for (idx in tracks.indices) {
                val track = tracks[idx]
                val tx = originX + track.x * scale
                val ty = originY - track.y * scale
                if (ty in 0f..originY && tx in 0f..w) {
                    val isFcw = idx == matchedFcwIndex
                    val isAcc = idx == matchedAccIndex
                    val trackRadius = if (isFcw || isAcc) 16f else 14f
                    val outerExtent = when {
                        isFcw -> trackRadius + 5f
                        isAcc -> trackRadius + 4.5f
                        else -> trackRadius + 2f
                    }
                    val style = when {
                        isFcw -> STYLE_TRACK_FCW
                        isAcc -> STYLE_TRACK_ACC
                        else -> STYLE_TRACK_DEFAULT
                    }
                    ringsPool.put4(tx, ty, outerExtent, style)
                    trackRingCount++

                    val vxPx = track.vx * scale * 0.75f
                    val vyPx = -track.vy * scale * 0.75f
                    val vSpeed = hypot(vxPx, vyPx)
                    if (vSpeed > 0.8f) {
                        val vr: Float
                        val vg: Float
                        val vb: Float
                        when {
                            isFcw -> { vr = 1.0f; vg = 0x52 / 255f; vb = 0x52 / 255f }
                            isAcc -> { vr = 0x80 / 255f; vg = 0xD8 / 255f; vb = 1.0f }
                            else -> { vr = 1.0f; vg = 0xD5 / 255f; vb = 0x4F / 255f }
                        }
                        val theta = atan2(vyPx, vxPx)
                        val ux = cos(theta)
                        val uy = sin(theta)
                        val px = -uy
                        val py = ux

                        val circX = tx + ux * trackRadius
                        val circY = ty + uy * trackRadius
                        val triLen = 6.5f
                        val triWidth = 4f
                        val tipX = circX + ux * triLen
                        val tipY = circY + uy * triLen
                        val b1X = circX - ux * 2f + px * triWidth
                        val b1Y = circY - uy * 2f + py * triWidth
                        val b2X = circX - ux * 2f - px * triWidth
                        val b2Y = circY - uy * 2f - py * triWidth
                        pushTriangle2D(tipX, tipY, b1X, b1Y, b2X, b2Y, vr, vg, vb, 1.0f)

                        if (vSpeed > trackRadius) {
                            val endX = tx + vxPx
                            val endY = ty + vyPx
                            pushThickSegment2D(tipX, tipY, endX, endY, 1.0f, vr, vg, vb, 1.0f)
                            pushDisc2D(endX, endY, 3.0f, vr, vg, vb, 1.0f)
                        }
                    }

                    appendTrackLabel(track.tid, isFcw, isAcc, tx, ty - trackRadius - 6f, atlas)
                }
            }

            // Fallback FCW target
            if (fcwAlert != null && fcwAlert.stage > 0 && matchedFcwIndex < 0 && fcwAlert.targetY > 0.5f) {
                val ftx = originX + fcwAlert.targetX * scale
                val fty = originY - fcwAlert.targetY * scale
                if (fty in 0f..originY && ftx in 0f..w) {
                    val fRadius = 16f
                    ringsPool.put4(ftx, fty, fRadius + 8f, STYLE_FCW_FALLBACK)
                    trackRingCount++

                    labelSb.setLength(0)
                    labelSb.append("[FCW S").append(fcwAlert.stage).append(']')
                    pushCenteredText(
                        text = labelSb,
                        centerX = ftx,
                        baselineY = fty - fRadius - 6f,
                        textSizePx = 24f,
                        atlas = atlas,
                        r = 1.0f, g = 82f / 255f, b = 82f / 255f, a = 1.0f
                    )
                }
            }

            if (fcwAlert != null && fcwAlert.stage > 0) {
                pushFcwBanner(fcwAlert.stage, fcwAlert.ttcSec, fcwAlert.targetY, metrics.widthPx * 0.5f, atlas)
            }
        }

        finalizeCountsAndCommit()
    }

    /**
     * Packs a 3D Perspective Orbit BEV frame in ISO radar world coordinates (`+X` right, `+Y` forward, `+Z` up):
     * - Point cloud colored by **longitudinal distance** (`clamp(pt.y / maxRangeMeters, 0..1)`),
     * - 3D ground plane quad (`z = 0`) for analytical range arcs, FOV rays & Cartesian grid,
     * - 3D extruded ego vehicle chevron + wireframe,
     * - 3D oriented EKF track bounding boxes (translucent ground footprint + 12-edge wireframe + 3D velocity arrow),
     * - Screen-projected billboard range & `#TID [FCW/ACC]` labels + FCW warning banner.
     */
    fun packFrame3D(
        frame: RadarFrame?,
        widthPx: Float,
        heightPx: Float,
        maxRangeMeters: Float,
        mvp3d16: FloatArray,
        atlas: BevGlyphAtlasMetrics,
        dynamicOnly: Boolean,
        minSnrFilter: Boolean,
        showGrid: Boolean,
        showClusters: Boolean,
        showRawPoints: Boolean,
        showTracks: Boolean,
        trailEnabled: Boolean = false,
        decayFrameCount: Int = if (trailEnabled) 4 else 1,
        colorMode: BevPointColorMode = BevPointColorMode.AUTO,
        colorMap: BevColorMap = BevColorMap.TURBO,
        stixelsEnabled: Boolean = true,
        hideOutliers: Boolean = false,
        showRoadLanes: Boolean = true,
        flipZ: Boolean = false
    ) {
        resetCounts()
        val effectiveDecayFrames = decayFrameCount.coerceIn(1, MAX_TRAIL_FRAMES)
        recordTrailFrame(frame, effectiveDecayFrames)

        if (widthPx <= 0f || heightPx <= 0f || maxRangeMeters <= 0f) {
            commitAll()
            return
        }

        val resolvedColorMode = colorMode.resolveForView(is3d = true)
        val shaderScheme = BevPanelColorizer.resolveShaderScheme(resolvedColorMode, colorMap)

        // 1. 3D Ground Plane Quad (z = 0)
        if (showGrid) {
            val extent = maxRangeMeters * 1.35f
            val minY = -maxRangeMeters * 0.30f
            val maxY = maxRangeMeters * 1.35f
            grid3dPool.put2(-extent, minY)
            grid3dPool.put2(extent, minY)
            grid3dPool.put2(extent, maxY)
            grid3dPool.put2(-extent, minY)
            grid3dPool.put2(extent, maxY)
            grid3dPool.put2(-extent, maxY)
            grid3dVertexCount = 6

            // Project ground range ring labels along centerline (x = 0, y = ringDist, z = 0.2 m)
            val ringStep = BevProjectionMath.computeRingStep(maxRangeMeters)
            var ringDist = ringStep
            var rCount = 0
            while (ringDist <= maxRangeMeters + 0.05f && rCount < 16) {
                if (BevCameraController.projectWorldToScreen(0f, ringDist, 0.15f, mvp3d16, widthPx, heightPx, projXY)) {
                    labelSb.setLength(0)
                    labelSb.append(ringDist.toInt()).append('m')
                    pushCenteredText(
                        text = labelSb,
                        centerX = projXY[0],
                        baselineY = projXY[1],
                        textSizePx = 22f,
                        atlas = atlas,
                        r = 176f / 255f, g = 190f / 255f, b = 197f / 255f, a = 185f / 255f
                    )
                }
                rCount++
                ringDist += ringStep
            }
        }

        // 2. 3D Ego Vehicle Chevron + Wireframe Prism at (0, 0, 0)
        val egoHalfW = 0.75f
        val egoLen = 2.1f
        val egoH = 1.15f
        // Filled cyan ground chevron pointing +Y
        pushTriangle3D(
            0f, egoLen * 0.65f, 0.06f,
            egoHalfW, -egoLen * 0.35f, 0.06f,
            -egoHalfW, -egoLen * 0.35f, 0.06f,
            0.0f, 0.898f, 1.0f, 0.90f
        )
        // Subtle 3D ego wireframe hull
        pushWireBox3D(
            cx = 0f, cy = 0.1f, halfW = egoHalfW, halfL = egoLen * 0.48f, height = egoH,
            yawRad = 0f,
            r = 0.0f, g = 0.898f, b = 1.0f, a = 0.85f
        )

        // 2b. Optional Ego Corridor Rails & TLV 4 Road Boundaries on ground plane (z = 0.04m)
        if (showRoadLanes) {
            packRoadLanes3D(frame, maxRangeMeters)
        }

        // 3. 3D Clusters (TLV 2) as translucent green ground hexagons at z = 0.08 m
        if (showClusters && frame != null) {
            val clusters = frame.clusters
            for (i in clusters.indices) {
                val c = clusters[i]
                if (c.y >= -2f && c.y <= maxRangeMeters * 1.25f) {
                    val rad = (maxOf(c.xSize, c.ySize) * 0.5f).coerceIn(0.6f, 2.2f)
                    pushGroundHex3D(c.x, c.y, 0.08f, rad, 0.0f, 0.902f, 0.463f, 0.38f)
                    pushGroundHexOutline3D(c.x, c.y, 0.10f, rad, 0.0f, 0.902f, 0.463f, 0.92f)
                    clusterRingCount++
                }
            }
        }

        // 4. 3D Point Cloud Reflections (TLV 1) + Optional Vertical Stixels + Configurable Decay
        if (showRawPoints && frame != null) {
            if (resolvedColorMode == BevPointColorMode.SNR) {
                updateSmartSnrBoundsForFrames(frame, effectiveDecayFrames, dynamicOnly, minSnrFilter, hideOutliers)
            }
            if (effectiveDecayFrames > 1 && trailCount > 0) {
                val renderCount = minOf(effectiveDecayFrames, trailCount)
                val startIdx = trailCount - renderCount
                for (tIdx in startIdx until trailCount) {
                    val tf = trailFrames[tIdx] ?: continue
                    val isNewest = tIdx == trailCount - 1
                    val alpha = computeTrailAlpha(tIdx - startIdx, renderCount)
                    packPoints3D(
                        frame = tf,
                        maxRangeMeters = maxRangeMeters,
                        dynamicOnly = dynamicOnly,
                        minSnrFilter = minSnrFilter,
                        hideOutliers = hideOutliers,
                        resolvedColorMode = resolvedColorMode,
                        shaderScheme = shaderScheme,
                        drawStixels = stixelsEnabled && isNewest,
                        flipZ = flipZ,
                        alpha = alpha
                    )
                }
            } else {
                packPoints3D(
                    frame = frame,
                    maxRangeMeters = maxRangeMeters,
                    dynamicOnly = dynamicOnly,
                    minSnrFilter = minSnrFilter,
                    hideOutliers = hideOutliers,
                    resolvedColorMode = resolvedColorMode,
                    shaderScheme = shaderScheme,
                    drawStixels = stixelsEnabled,
                    flipZ = flipZ,
                    alpha = 1.0f
                )
            }
        }

        // 5. 3D Tracked Targets (TLV 3) + ADAS FCW/ACC 3D Bounding Boxes & Billboard Labels
        if (showTracks && frame != null) {
            val packedMatch = BevTrackAssociator.matchPacked(frame)
            val matchedFcwIndex = BevTrackAssociator.unpackFcwIndex(packedMatch)
            val matchedAccIndex = BevTrackAssociator.unpackAccIndex(packedMatch)
            val fcwAlert = frame.canOutputs?.fcw
            val tracks = frame.tracks

            for (idx in tracks.indices) {
                val track = tracks[idx]
                val isFcw = idx == matchedFcwIndex
                val isAcc = idx == matchedAccIndex
                // Firmware TLV 3 sends majorSize/minorSize as semi-axes (length = 2*major, width = 2*minor);
                // xSize == majorSize & ySize == minorSize in RadarTlvDecoder, so map minor->halfW and major->halfL.
                val halfW = (if (track.minorSize > 0.2f) track.minorSize else 0.40f).coerceIn(0.40f, 3.0f)
                val halfL = (if (track.majorSize > 0.2f) track.majorSize else 0.75f).coerceIn(0.75f, 8.0f)
                val boxH = if (isFcw || isAcc) 1.75f else 1.50f
                // Radar heading is clockwise positive in (+X right, +Y forward); right-handed Z-up CCW needs -orientationDeg
                val yawRad = (-track.orientationDeg * PI / 180.0).toFloat()

                val r: Float; val g: Float; val b: Float
                when {
                    isFcw -> { r = 1.0f; g = 0.090f; b = 0.267f } // #FF1744
                    isAcc -> { r = 0.0f; g = 0.898f; b = 1.0f }   // #00E5FF
                    else -> { r = 1.0f; g = 0.702f; b = 0.0f }    // #FFB300
                }

                // Translucent ground footprint quad + crisp 12-edge 3D wireframe box
                pushFilledFootprint3D(track.x, track.y, 0.05f, halfW, halfL, yawRad, r, g, b, if (isFcw || isAcc) 0.36f else 0.24f)
                pushWireBox3D(track.x, track.y, halfW, halfL, boxH, yawRad, r, g, b, 0.96f)
                if (isFcw || isAcc) {
                    // Priority outer ground halo ring
                    pushGroundHexOutline3D(track.x, track.y, 0.09f, maxOf(halfW, halfL) + 0.65f, r, g, b, 0.80f)
                }
                trackRingCount++

                // 3D Velocity Vector Arrow (0.75s lookahead)
                val vxM = track.vx * 0.75f
                val vyM = track.vy * 0.75f
                val vLen = hypot(vxM, vyM)
                if (vLen > 0.4f) {
                    val vr: Float; val vg: Float; val vb: Float
                    when {
                        isFcw -> { vr = 1.0f; vg = 0x52 / 255f; vb = 0x52 / 255f }
                        isAcc -> { vr = 0x80 / 255f; vg = 0xD8 / 255f; vb = 1.0f }
                        else -> { vr = 1.0f; vg = 0xD5 / 255f; vb = 0x4F / 255f }
                    }
                    val zVec = 0.35f
                    val ex = track.x + vxM
                    val ey = track.y + vyM
                    pushLine3D(track.x, track.y, zVec, ex, ey, zVec, vr, vg, vb, 1.0f)
                    val ux = vxM / vLen
                    val uy = vyM / vLen
                    val px = -uy
                    val py = ux
                    val headLen = minOf(1.1f, vLen * 0.45f)
                    val headW = headLen * 0.45f
                    pushTriangle3D(
                        ex, ey, zVec,
                        ex - ux * headLen + px * headW, ey - uy * headLen + py * headW, zVec,
                        ex - ux * headLen - px * headW, ey - uy * headLen - py * headW, zVec,
                        vr, vg, vb, 1.0f
                    )
                }

                // Billboard track label floating above the 3D bounding box roof
                if (BevCameraController.projectWorldToScreen(track.x, track.y, boxH + 0.55f, mvp3d16, widthPx, heightPx, projXY)) {
                    appendTrackLabel(track.tid, isFcw, isAcc, projXY[0], projXY[1], atlas)
                }
            }

            // Fallback FCW 3D pillar box
            if (fcwAlert != null && fcwAlert.stage > 0 && matchedFcwIndex < 0 && fcwAlert.targetY > 0.5f) {
                pushFilledFootprint3D(fcwAlert.targetX, fcwAlert.targetY, 0.06f, 1.0f, 1.6f, 0f, 1.0f, 0.090f, 0.267f, 0.35f)
                pushWireBox3D(fcwAlert.targetX, fcwAlert.targetY, 1.0f, 1.6f, 1.85f, 0f, 1.0f, 0.090f, 0.267f, 1.0f)
                trackRingCount++
                if (BevCameraController.projectWorldToScreen(fcwAlert.targetX, fcwAlert.targetY, 2.35f, mvp3d16, widthPx, heightPx, projXY)) {
                    labelSb.setLength(0)
                    labelSb.append("[FCW S").append(fcwAlert.stage).append(']')
                    pushCenteredText(
                        text = labelSb,
                        centerX = projXY[0],
                        baselineY = projXY[1],
                        textSizePx = 24f,
                        atlas = atlas,
                        r = 1.0f, g = 82f / 255f, b = 82f / 255f, a = 1.0f
                    )
                }
            }

            if (fcwAlert != null && fcwAlert.stage > 0) {
                pushFcwBanner(fcwAlert.stage, fcwAlert.ttcSec, fcwAlert.targetY, widthPx * 0.5f, atlas)
            }
        }

        finalizeCountsAndCommit()
    }

    private fun packPoints2D(
        frame: RadarFrame,
        originX: Float,
        originY: Float,
        scale: Float,
        w: Float,
        maxRangeMeters: Float,
        dynamicOnly: Boolean,
        minSnrFilter: Boolean,
        hideOutliers: Boolean,
        resolvedColorMode: BevPointColorMode,
        flipZ: Boolean,
        alpha: Float
    ) {
        val points = frame.points
        for (i in points.indices) {
            val pt = points[i]
            if ((!dynamicOnly || abs(pt.doppler) > DOPPLER_STATIONARY_THRESH) &&
                (!minSnrFilter || pt.snrDb >= MIN_SNR_DB) &&
                (!hideOutliers || !pt.isOutlier)
            ) {
                val px = originX + pt.x * scale
                val py = originY - pt.y * scale
                if (py in 0f..originY && px in 0f..w) {
                    val rawZ = if (flipZ) -pt.z else pt.z
                    val scalar = BevPanelColorizer.normalizeScalar(
                        resolvedMode = resolvedColorMode,
                        forwardY = pt.y,
                        z = rawZ,
                        dopplerMps = pt.doppler,
                        snrDb = pt.snrDb,
                        clusterId = pt.clusterId,
                        isOutlier = pt.isOutlier,
                        maxRangeM = maxRangeMeters,
                        snrMinDb = currentSnrMinDb,
                        snrMaxDb = currentSnrMaxDb
                    )
                    pointsPool.put5(px, py, 0f, scalar, alpha)
                    pointCount++
                }
            }
        }
    }

    private fun packPoints3D(
        frame: RadarFrame,
        maxRangeMeters: Float,
        dynamicOnly: Boolean,
        minSnrFilter: Boolean,
        hideOutliers: Boolean,
        resolvedColorMode: BevPointColorMode,
        shaderScheme: Int,
        drawStixels: Boolean,
        flipZ: Boolean,
        alpha: Float
    ) {
        val points = frame.points
        for (i in points.indices) {
            val pt = points[i]
            if (!pt.x.isFinite() || !pt.y.isFinite() || !pt.z.isFinite()) continue
            if ((!dynamicOnly || abs(pt.doppler) > DOPPLER_STATIONARY_THRESH) &&
                (!minSnrFilter || pt.snrDb >= MIN_SNR_DB) &&
                (!hideOutliers || !pt.isOutlier)
            ) {
                val rawZ = if (flipZ) -pt.z else pt.z
                val scalar = BevPanelColorizer.normalizeScalar(
                    resolvedMode = resolvedColorMode,
                    forwardY = pt.y,
                    z = rawZ,
                    dopplerMps = pt.doppler,
                    snrDb = pt.snrDb,
                    clusterId = pt.clusterId,
                    isOutlier = pt.isOutlier,
                    maxRangeM = maxRangeMeters,
                    snrMinDb = currentSnrMinDb,
                    snrMaxDb = currentSnrMaxDb
                )
                // Preserve full raw 3D elevation (matching Foxglove, zero ceiling/floor clipping);
                // only nudge exact zero-plane detections slightly above z=0 to avoid Z-fighting with the ground grid.
                val elevZ = if (abs(rawZ) < 0.03f) 0.05f else rawZ
                pointsPool.put5(pt.x, pt.y, elevZ, scalar, alpha)
                pointCount++

                if (drawStixels && abs(rawZ) >= 0.15f) {
                    BevPanelColorizer.evalPointRgb(shaderScheme, scalar, scratchStixelRgb)
                    pushLine3D(
                        pt.x, pt.y, 0.04f,
                        pt.x, pt.y, elevZ,
                        scratchStixelRgb[0], scratchStixelRgb[1], scratchStixelRgb[2],
                        0.38f * alpha
                    )
                }
            }
        }
    }

    private fun packRoadLanes2D(
        frame: RadarFrame?,
        originX: Float,
        originY: Float,
        scale: Float,
        maxRangeMeters: Float
    ) {
        val laneEndM = minOf(maxRangeMeters, 80f)
        if (laneEndM <= 1.0f) return

        // FCW corridor rails (+/- 1.5 m from radar boresight centerline)
        val fcwHalfW = 1.5f * scale
        val y0 = originY - 1.0f * scale
        val y1 = originY - laneEndM * scale
        pushLine3D(originX - fcwHalfW, y0, 0f, originX - fcwHalfW, y1, 0f, 0.0f, 0.72f, 1.0f, 0.45f)
        pushLine3D(originX + fcwHalfW, y0, 0f, originX + fcwHalfW, y1, 0f, 0.0f, 0.72f, 1.0f, 0.45f)

        // TLV 4 road boundaries dynamically populated from radar board (leftX < 0, rightX > 0)
        val diag = frame?.diagnostics ?: return
        val leftX = diag.roadBoundaryLeftX
        if (leftX.isFinite() && leftX in -30.0f..-0.2f) {
            val sx = originX + leftX * scale
            pushLine3D(sx, originY, 0f, sx, y1, 0f, 0.463f, 1.0f, 0.012f, 0.78f)
        }
        val rightX = diag.roadBoundaryRightX
        if (rightX.isFinite() && rightX in 0.2f..30.0f) {
            val sx = originX + rightX * scale
            pushLine3D(sx, originY, 0f, sx, y1, 0f, 0.463f, 1.0f, 0.012f, 0.78f)
        }
    }

    private fun packRoadLanes3D(
        frame: RadarFrame?,
        maxRangeMeters: Float
    ) {
        val laneEndM = minOf(maxRangeMeters, 80f)
        if (laneEndM <= 1.0f) return

        // FCW corridor rails (+/- 1.5 m from radar boresight centerline) on ground plane
        pushLine3D(-1.5f, 1.0f, 0.04f, -1.5f, laneEndM, 0.04f, 0.0f, 0.72f, 1.0f, 0.50f)
        pushLine3D(1.5f, 1.0f, 0.04f, 1.5f, laneEndM, 0.04f, 0.0f, 0.72f, 1.0f, 0.50f)

        // TLV 4 road boundaries dynamically populated from radar board (#76FF03 bright green)
        val diag = frame?.diagnostics ?: return
        val leftX = diag.roadBoundaryLeftX
        if (leftX.isFinite() && leftX in -30.0f..-0.2f) {
            pushLine3D(leftX, 0.5f, 0.05f, leftX, laneEndM, 0.05f, 0.463f, 1.0f, 0.012f, 0.85f)
        }
        val rightX = diag.roadBoundaryRightX
        if (rightX.isFinite() && rightX in 0.2f..30.0f) {
            pushLine3D(rightX, 0.5f, 0.05f, rightX, laneEndM, 0.05f, 0.463f, 1.0f, 0.012f, 0.85f)
        }
    }

    private fun appendTrackLabel(
        tid: Int,
        isFcw: Boolean,
        isAcc: Boolean,
        centerX: Float,
        baselineY: Float,
        atlas: BevGlyphAtlasMetrics
    ) {
        labelSb.setLength(0)
        labelSb.append('#').append(tid)
        when {
            isFcw && isAcc -> labelSb.append(" [FCW+ACC]")
            isFcw -> labelSb.append(" [FCW]")
            isAcc -> labelSb.append(" [ACC]")
        }
        val lr: Float; val lg: Float; val lb: Float
        when {
            isFcw -> { lr = 1.0f; lg = 82f / 255f; lb = 82f / 255f }
            isAcc -> { lr = 128f / 255f; lg = 216f / 255f; lb = 1.0f }
            else -> { lr = 1.0f; lg = 1.0f; lb = 1.0f }
        }
        pushCenteredText(
            text = labelSb,
            centerX = centerX,
            baselineY = baselineY,
            textSizePx = 24f,
            atlas = atlas,
            r = lr, g = lg, b = lb, a = 1.0f
        )
    }

    private fun pushFcwBanner(
        stage: Int,
        ttcSec: Float,
        targetY: Float,
        centerX: Float,
        atlas: BevGlyphAtlasMetrics
    ) {
        labelSb.setLength(0)
        if (stage == 2) {
            labelSb.append("FCW HAZARD: AUDIBLE ALERT")
        } else {
            labelSb.append("FCW: VISUAL WARNING")
        }
        if (ttcSec > 0f) {
            labelSb.append(" \u2022 TTC ")
            appendOneDecimal(labelSb, ttcSec)
            labelSb.append('s')
        }
        if (targetY > 0f) {
            labelSb.append(" \u2022 ")
            appendOneDecimal(labelSb, targetY)
            labelSb.append('m')
        }
        pushCenteredText(
            text = labelSb,
            centerX = centerX,
            baselineY = 24f,
            textSizePx = 26f,
            atlas = atlas,
            r = 1.0f, g = 23f / 255f, b = 68f / 255f, a = 240f / 255f
        )
    }

    private fun resetCounts() {
        pointsPool.clear()
        ringsPool.clear()
        solidPool.clear()
        linesPool.clear()
        grid3dPool.clear()
        textPool.clear()
        pointCount = 0
        clusterRingCount = 0
        trackRingCount = 0
        totalRingCount = 0
        solidVertexCount = 0
        lineVertexCount = 0
        grid3dVertexCount = 0
        textVertexCount = 0
    }

    private fun finalizeCountsAndCommit() {
        totalRingCount = clusterRingCount + trackRingCount
        solidVertexCount = solidPool.floatCount / SOLID_VERTEX_FLOATS
        lineVertexCount = linesPool.floatCount / LINE_VERTEX_FLOATS
        textVertexCount = textPool.floatCount / TEXT_VERTEX_FLOATS
        commitAll()
    }

    private fun commitAll() {
        pointsPool.commitToDirectBuffer()
        ringsPool.commitToDirectBuffer()
        solidPool.commitToDirectBuffer()
        linesPool.commitToDirectBuffer()
        grid3dPool.commitToDirectBuffer()
        textPool.commitToDirectBuffer()
    }

    private fun pushTriangle2D(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        solidPool.put7(x0, y0, 0f, r, g, b, a)
        solidPool.put7(x1, y1, 0f, r, g, b, a)
        solidPool.put7(x2, y2, 0f, r, g, b, a)
    }

    private fun pushTriangle3D(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        solidPool.put7(x0, y0, z0, r, g, b, a)
        solidPool.put7(x1, y1, z1, r, g, b, a)
        solidPool.put7(x2, y2, z2, r, g, b, a)
    }

    private fun pushLine3D(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        linesPool.put7(x0, y0, z0, r, g, b, a)
        linesPool.put7(x1, y1, z1, r, g, b, a)
    }

    private fun pushFilledFootprint3D(
        cx: Float, cy: Float, z: Float,
        halfW: Float, halfL: Float,
        yawRad: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val c = cos(yawRad)
        val s = sin(yawRad)
        val x0 = cx + (-halfW * c - (-halfL) * s); val y0 = cy + (-halfW * s + (-halfL) * c)
        val x1 = cx + (halfW * c - (-halfL) * s);  val y1 = cy + (halfW * s + (-halfL) * c)
        val x2 = cx + (halfW * c - halfL * s);     val y2 = cy + (halfW * s + halfL * c)
        val x3 = cx + (-halfW * c - halfL * s);    val y3 = cy + (-halfW * s + halfL * c)
        pushTriangle3D(x0, y0, z, x1, y1, z, x2, y2, z, r, g, b, a)
        pushTriangle3D(x0, y0, z, x2, y2, z, x3, y3, z, r, g, b, a)
    }

    private fun pushWireBox3D(
        cx: Float, cy: Float,
        halfW: Float, halfL: Float, height: Float,
        yawRad: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val c = cos(yawRad)
        val s = sin(yawRad)
        val x0 = cx + (-halfW * c - (-halfL) * s); val y0 = cy + (-halfW * s + (-halfL) * c)
        val x1 = cx + (halfW * c - (-halfL) * s);  val y1 = cy + (halfW * s + (-halfL) * c)
        val x2 = cx + (halfW * c - halfL * s);     val y2 = cy + (halfW * s + halfL * c)
        val x3 = cx + (-halfW * c - halfL * s);    val y3 = cy + (-halfW * s + halfL * c)
        val z0 = 0.04f
        val z1 = height
        // Bottom rectangle (4 edges)
        pushLine3D(x0, y0, z0, x1, y1, z0, r, g, b, a)
        pushLine3D(x1, y1, z0, x2, y2, z0, r, g, b, a)
        pushLine3D(x2, y2, z0, x3, y3, z0, r, g, b, a)
        pushLine3D(x3, y3, z0, x0, y0, z0, r, g, b, a)
        // Top rectangle (4 edges)
        pushLine3D(x0, y0, z1, x1, y1, z1, r, g, b, a)
        pushLine3D(x1, y1, z1, x2, y2, z1, r, g, b, a)
        pushLine3D(x2, y2, z1, x3, y3, z1, r, g, b, a)
        pushLine3D(x3, y3, z1, x0, y0, z1, r, g, b, a)
        // Vertical pillars (4 edges)
        pushLine3D(x0, y0, z0, x0, y0, z1, r, g, b, a)
        pushLine3D(x1, y1, z0, x1, y1, z1, r, g, b, a)
        pushLine3D(x2, y2, z0, x2, y2, z1, r, g, b, a)
        pushLine3D(x3, y3, z0, x3, y3, z1, r, g, b, a)
    }

    private fun pushGroundHex3D(
        cx: Float, cy: Float, z: Float, radius: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        for (i in 0 until HEX_SEGMENTS) {
            pushTriangle3D(
                cx, cy, z,
                cx + HEX_COS[i] * radius, cy + HEX_SIN[i] * radius, z,
                cx + HEX_COS[i + 1] * radius, cy + HEX_SIN[i + 1] * radius, z,
                r, g, b, a
            )
        }
    }

    private fun pushGroundHexOutline3D(
        cx: Float, cy: Float, z: Float, radius: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        for (i in 0 until HEX_SEGMENTS) {
            pushLine3D(
                cx + HEX_COS[i] * radius, cy + HEX_SIN[i] * radius, z,
                cx + HEX_COS[i + 1] * radius, cy + HEX_SIN[i + 1] * radius, z,
                r, g, b, a
            )
        }
    }

    private fun pushThickSegment2D(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        halfWidthPx: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-4f) return
        val nx = (-dy / len) * halfWidthPx
        val ny = (dx / len) * halfWidthPx
        val ax = x0 + nx; val ay = y0 + ny
        val bx = x0 - nx; val by = y0 - ny
        val cx = x1 + nx; val cy = y1 + ny
        val dxp = x1 - nx; val dyp = y1 - ny
        pushTriangle2D(ax, ay, bx, by, cx, cy, r, g, b, a)
        pushTriangle2D(bx, by, dxp, dyp, cx, cy, r, g, b, a)
    }

    private fun pushDisc2D(
        cx: Float, cy: Float,
        radiusPx: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        for (i in 0 until HEX_SEGMENTS) {
            pushTriangle2D(
                cx, cy,
                cx + HEX_COS[i] * radiusPx, cy + HEX_SIN[i] * radiusPx,
                cx + HEX_COS[i + 1] * radiusPx, cy + HEX_SIN[i + 1] * radiusPx,
                r, g, b, a
            )
        }
    }

    private fun pushCenteredText(
        text: CharSequence,
        centerX: Float,
        baselineY: Float,
        textSizePx: Float,
        atlas: BevGlyphAtlasMetrics,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val len = text.length
        if (len == 0) return
        val scale = atlas.scaleForTextSize(textSizePx)
        val totalWidth = atlas.measureWidth(text, textSizePx)
        val cellScreenSize = atlas.cellPx * scale
        val topY = baselineY - atlas.baselineInCellPx * scale
        val bottomY = topY + cellScreenSize
        val halfCell = cellScreenSize * 0.5f

        var cursorX = centerX - totalWidth * 0.5f
        for (i in 0 until len) {
            val gIdx = BevGlyphAtlasMetrics.indexForChar(text[i])
            val adv = atlas.advancePx(gIdx, scale)
            val glyphCenterX = cursorX + adv * 0.5f
            val leftX = glyphCenterX - halfCell
            val rightX = glyphCenterX + halfCell
            val u0 = atlas.u0(gIdx)
            val v0 = atlas.v0(gIdx)
            val u1 = atlas.u1(gIdx)
            val v1 = atlas.v1(gIdx)

            textPool.put8(leftX, topY, u0, v0, r, g, b, a)
            textPool.put8(leftX, bottomY, u0, v1, r, g, b, a)
            textPool.put8(rightX, topY, u1, v0, r, g, b, a)
            textPool.put8(leftX, bottomY, u0, v1, r, g, b, a)
            textPool.put8(rightX, bottomY, u1, v1, r, g, b, a)
            textPool.put8(rightX, topY, u1, v0, r, g, b, a)

            cursorX += adv
        }
    }

    private fun appendOneDecimal(sb: StringBuilder, value: Float) {
        val scaled = (value * 10f).roundToInt()
        if (scaled < 0) sb.append('-')
        val absScaled = abs(scaled)
        sb.append(absScaled / 10).append('.').append(absScaled % 10)
    }
}
