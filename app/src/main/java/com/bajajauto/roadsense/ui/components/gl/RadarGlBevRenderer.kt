package com.bajajauto.roadsense.ui.components.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.models.RadarFrame
import java.nio.FloatBuffer
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Immutable snapshot of BEV display configuration passed lock-free from Compose UI to GLThread.
 */
data class BevRenderConfig(
    val maxRangeMeters: Float = 100f,
    val dynamicOnly: Boolean = false,
    val minSnrFilter: Boolean = false,
    val showGrid: Boolean = true,
    val showClusters: Boolean = false,
    val showRawPoints: Boolean = true,
    val showTracks: Boolean = true,
    val is3DMode: Boolean = false,
    val trailEnabled: Boolean = false,
    val pointColorMode: BevPointColorMode = BevPointColorMode.AUTO,
    val colorMap: BevColorMap = BevColorMap.TURBO,
    val pointShape: BevPointShape = BevPointShape.CIRCLE,
    val pointSizePreset: BevPointSizePreset = BevPointSizePreset.MEDIUM,
    val decayPreset: BevDecayPreset = BevDecayPreset.OFF,
    val stixelsEnabled: Boolean = true,
    val showRoadLanes: Boolean = true,
    val hideOutliers: Boolean = false,
    val flipZ: Boolean = false,
    val enableTimingLog: Boolean = false,
    val enablePerfWindowLog: Boolean = false
)

/**
 * Native OpenGL ES 3.0 (`GLES30`) renderer for the RoadSense Radar Bird's-Eye View (BEV) scope.
 *
 * Supports both:
 * - **2D Orthographic BEV** (Doppler point coloring, concentric polar rings, 2D pinch-zoom/pan), and
 * - **3D Perspective Orbit BEV** (longitudinal-distance point coloring, 3D elevation `pt.z`,
 *   3D ground grid, 3D ego silhouette, oriented 3D EKF track boxes & screen-projected billboard labels).
 */
class RadarGlBevRenderer(
    private val cameraController: BevCameraController = BevCameraController(),
    private val onGlInitError: () -> Unit = {}
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "RadarGlBev"
        private const val PERF_WINDOW_NS = 5_000_000_000L
        private const val MAX_PERF_SAMPLES = 512
        private const val POINT_SIZE_2D_PX = 9.2f
        private const val POINT_SIZE_3D_BASE_PX = 9.8f
    }

    private val latestFrameRef = AtomicReference<RadarFrame?>(null)
    private val configRef = AtomicReference(BevRenderConfig())

    private val packer = BevGlVertexPacker()
    private val mvp2dMatrix = FloatArray(16)
    private val mvp3dMatrix = FloatArray(16)
    private val scratchProj16 = FloatArray(16)
    private val scratchView16 = FloatArray(16)

    private var viewportW = 0
    private var viewportH = 0
    private var cachedRange = -1f
    private var cachedBaseMetrics = BevProjectionMath.computeMetrics(0f, 0f, 100f)

    // GL state (owned strictly by GLThread)
    private var isReady = false
    private var gridProgram = 0
    private var grid3dProgram = 0
    private var solidProgram = 0
    private var pointProgram = 0
    private var ringProgram = 0
    private var textProgram = 0

    // Cached uniform locations
    private var uGridViewport = -1
    private var uGridOrigin = -1
    private var uGridScale = -1
    private var uGridRingStep = -1
    private var uGridMaxVisibleDist = -1
    private var uGrid3dMvp = -1
    private var uGrid3dMaxRange = -1
    private var uGrid3dRingStep = -1
    private var uSolidMvp = -1
    private var uPointMvp = -1
    private var uPointSizePx = -1
    private var uPointIs3D = -1
    private var uPointColorScheme = -1
    private var uPointShape = -1
    private var uRingMvp = -1
    private var uTextMvp = -1
    private var uTextAtlas = -1

    // VAOs & dynamic VBOs
    private var vaoGrid = 0
    private var vaoGrid3d = 0
    private var vaoPoints = 0
    private var vaoRings = 0
    private var vaoSolid = 0
    private var vaoLines = 0
    private var vaoText = 0

    private var vboGrid3d = 0
    private var vboPoints = 0
    private var vboRings = 0
    private var vboSolid = 0
    private var vboLines = 0
    private var vboText = 0

    private var vboGrid3dCapBytes = 0
    private var vboPointsCapBytes = 0
    private var vboRingsCapBytes = 0
    private var vboSolidCapBytes = 0
    private var vboLinesCapBytes = 0
    private var vboTextCapBytes = 0

    private var atlasTextureId = 0
    private var atlasMetrics = BevGlyphAtlasMetrics.defaultForTests()

    private var lastDrawStartNs = 0L
    private var windowStartNs = 0L
    private var sampleCount = 0
    private val sampleDurationsMs = FloatArray(MAX_PERF_SAMPLES)

    fun updateFrame(frame: RadarFrame?) {
        latestFrameRef.set(frame)
    }

    fun updateConfig(config: BevRenderConfig) {
        configRef.set(config)
        cameraController.set3DMode(config.is3DMode)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        isReady = false
        vboGrid3dCapBytes = 0
        vboPointsCapBytes = 0
        vboRingsCapBytes = 0
        vboSolidCapBytes = 0
        vboLinesCapBytes = 0
        vboTextCapBytes = 0
        windowStartNs = 0L
        sampleCount = 0

        GLES30.glClearColor(0.0353f, 0.0431f, 0.0627f, 1.0f) // #090B10
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        gridProgram = BevGlShaders.compileProgram("grid2d", BevGlShaders.GRID_VERTEX_SHADER, BevGlShaders.GRID_FRAGMENT_SHADER)
        grid3dProgram = BevGlShaders.compileProgram("grid3d", BevGlShaders.GRID_3D_VERTEX_SHADER, BevGlShaders.GRID_3D_FRAGMENT_SHADER)
        solidProgram = BevGlShaders.compileProgram("solid", BevGlShaders.SOLID_VERTEX_SHADER, BevGlShaders.SOLID_FRAGMENT_SHADER)
        pointProgram = BevGlShaders.compileProgram("point", BevGlShaders.POINT_VERTEX_SHADER, BevGlShaders.POINT_FRAGMENT_SHADER)
        ringProgram = BevGlShaders.compileProgram("ring", BevGlShaders.RING_VERTEX_SHADER, BevGlShaders.RING_FRAGMENT_SHADER)
        textProgram = BevGlShaders.compileProgram("text", BevGlShaders.TEXT_VERTEX_SHADER, BevGlShaders.TEXT_FRAGMENT_SHADER)

        if (gridProgram == 0 || grid3dProgram == 0 || solidProgram == 0 || pointProgram == 0 || ringProgram == 0 || textProgram == 0) {
            AppLogger.e(TAG, "Shader compilation failed — falling back to Skia RadarBevCanvas")
            onGlInitError()
            return
        }

        uGridViewport = GLES30.glGetUniformLocation(gridProgram, "uViewport")
        uGridOrigin = GLES30.glGetUniformLocation(gridProgram, "uOrigin")
        uGridScale = GLES30.glGetUniformLocation(gridProgram, "uScale")
        uGridRingStep = GLES30.glGetUniformLocation(gridProgram, "uRingStep")
        uGridMaxVisibleDist = GLES30.glGetUniformLocation(gridProgram, "uMaxVisibleDist")

        uGrid3dMvp = GLES30.glGetUniformLocation(grid3dProgram, "uMvp")
        uGrid3dMaxRange = GLES30.glGetUniformLocation(grid3dProgram, "uMaxRange")
        uGrid3dRingStep = GLES30.glGetUniformLocation(grid3dProgram, "uRingStep")

        uSolidMvp = GLES30.glGetUniformLocation(solidProgram, "uMvp")
        uPointMvp = GLES30.glGetUniformLocation(pointProgram, "uMvp")
        uPointSizePx = GLES30.glGetUniformLocation(pointProgram, "uPointSizePx")
        uPointIs3D = GLES30.glGetUniformLocation(pointProgram, "uIs3D")
        uPointColorScheme = GLES30.glGetUniformLocation(pointProgram, "uColorScheme")
        uPointShape = GLES30.glGetUniformLocation(pointProgram, "uPointShape")
        uRingMvp = GLES30.glGetUniformLocation(ringProgram, "uMvp")
        uTextMvp = GLES30.glGetUniformLocation(textProgram, "uMvp")
        uTextAtlas = GLES30.glGetUniformLocation(textProgram, "uAtlas")

        val vaos = IntArray(7)
        GLES30.glGenVertexArrays(7, vaos, 0)
        vaoGrid = vaos[0]
        vaoGrid3d = vaos[1]
        vaoPoints = vaos[2]
        vaoRings = vaos[3]
        vaoSolid = vaos[4]
        vaoLines = vaos[5]
        vaoText = vaos[6]

        val vbos = IntArray(6)
        GLES30.glGenBuffers(6, vbos, 0)
        vboGrid3d = vbos[0]
        vboPoints = vbos[1]
        vboRings = vbos[2]
        vboSolid = vbos[3]
        vboLines = vbos[4]
        vboText = vbos[5]

        // 3D Ground Plane VAO (location 0: vec2 aWorldXY)
        GLES30.glBindVertexArray(vaoGrid3d)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboGrid3d)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, BevGlVertexPacker.GRID3D_FLOATS * 4, 0)

        // Point Cloud VAO (location 0: vec3 aPos, location 1: vec2 aMeta)
        val pointStride = BevGlVertexPacker.POINT_FLOATS * 4
        GLES30.glBindVertexArray(vaoPoints)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboPoints)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, pointStride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, pointStride, 3 * 4)

        // 2D Rings VAO (location 0: vec4 aMarker)
        GLES30.glBindVertexArray(vaoRings)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboRings)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, BevGlVertexPacker.RING_FLOATS * 4, 0)

        // Solid Triangles VAO (location 0: vec3 aPos, location 1: vec4 aColor)
        val solidStride = BevGlVertexPacker.SOLID_VERTEX_FLOATS * 4
        GLES30.glBindVertexArray(vaoSolid)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSolid)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, solidStride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, solidStride, 3 * 4)

        // 3D Wireframe Lines VAO (location 0: vec3 aPos, location 1: vec4 aColor)
        val lineStride = BevGlVertexPacker.LINE_VERTEX_FLOATS * 4
        GLES30.glBindVertexArray(vaoLines)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboLines)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, lineStride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, lineStride, 3 * 4)

        // Text Atlas VAO (location 0: vec2 aPos, location 1: vec2 aUv, location 2: vec4 aColor)
        val textStride = BevGlVertexPacker.TEXT_VERTEX_FLOATS * 4
        GLES30.glBindVertexArray(vaoText)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboText)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, textStride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, textStride, 2 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, textStride, 4 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val uploaded = BevGlyphAtlasUploader.createAndUpload()
        atlasTextureId = uploaded.textureId
        atlasMetrics = uploaded.metrics

        val glErr = GLES30.glGetError()
        if (glErr != GLES30.GL_NO_ERROR || atlasTextureId == 0) {
            AppLogger.e(TAG, "GLES30 initialization error=0x${glErr.toString(16)} atlasTex=$atlasTextureId — falling back to Skia")
            onGlInitError()
            return
        }

        isReady = true
        val rendererStr = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unknown"
        val versionStr = GLES30.glGetString(GLES30.GL_VERSION) ?: "unknown"
        AppLogger.i(TAG, "OpenGL ES 3.0 BEV initialized ($versionStr | $rendererStr | atlas=${atlasMetrics.atlasWidth}x${atlasMetrics.atlasHeight} GL_R8)")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(0)
        viewportH = height.coerceAtLeast(0)
        cachedRange = -1f
        if (viewportW > 0 && viewportH > 0) {
            GLES30.glViewport(0, 0, viewportW, viewportH)
            BevProjectionMath.buildOrthoMvpMatrix(viewportW.toFloat(), viewportH.toFloat(), mvp2dMatrix)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!isReady || viewportW <= 0 || viewportH <= 0) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        val drawStartNs = System.nanoTime()
        val cfg = configRef.get()
        val frame = latestFrameRef.get()
        val camState = cameraController.state

        if (camState.is3D) {
            draw3DFrame(cfg, frame, camState)
        } else {
            draw2DFrame(cfg, frame, camState)
        }

        GLES30.glBindVertexArray(0)
        recordTelemetry(drawStartNs, cfg, frame, camState.is3D)
    }

    private fun draw2DFrame(cfg: BevRenderConfig, frame: RadarFrame?, camState: BevCameraState) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (cachedRange != cfg.maxRangeMeters ||
            cachedBaseMetrics.widthPx != viewportW.toFloat() ||
            cachedBaseMetrics.heightPx != viewportH.toFloat()
        ) {
            cachedBaseMetrics = BevProjectionMath.computeMetrics(
                widthPx = viewportW.toFloat(),
                heightPx = viewportH.toFloat(),
                maxRangeMeters = cfg.maxRangeMeters
            )
            cachedRange = cfg.maxRangeMeters
        }
        val metrics = BevCameraController.apply2DCamera(cachedBaseMetrics, camState)
        if (!metrics.isValid) return

        val effectiveDecayFrames = if (cfg.decayPreset != BevDecayPreset.OFF) {
            cfg.decayPreset.frameCount
        } else if (cfg.trailEnabled) {
            BevDecayPreset.MS_200.frameCount
        } else {
            1
        }
        val resolvedColorMode = cfg.pointColorMode.resolveForView(is3d = false)
        val shaderScheme = BevPanelColorizer.resolveShaderScheme(resolvedColorMode, cfg.colorMap)

        packer.packFrame(
            frame = frame,
            metrics = metrics,
            atlas = atlasMetrics,
            dynamicOnly = cfg.dynamicOnly,
            minSnrFilter = cfg.minSnrFilter,
            showGrid = cfg.showGrid,
            showClusters = cfg.showClusters,
            showRawPoints = cfg.showRawPoints,
            showTracks = cfg.showTracks,
            trailEnabled = cfg.trailEnabled,
            decayFrameCount = effectiveDecayFrames,
            colorMode = cfg.pointColorMode,
            colorMap = cfg.colorMap,
            hideOutliers = cfg.hideOutliers,
            showRoadLanes = cfg.showRoadLanes,
            flipZ = cfg.flipZ
        )

        if (packer.solidVertexCount > 0) {
            vboSolidCapBytes = uploadDynamicVbo(vboSolid, vboSolidCapBytes, packer.solidBuffer(), packer.solidByteSize(), packer.solidCapacityBytes())
        }
        if (packer.lineVertexCount > 0) {
            vboLinesCapBytes = uploadDynamicVbo(vboLines, vboLinesCapBytes, packer.linesBuffer(), packer.linesByteSize(), packer.linesCapacityBytes())
        }
        if (packer.totalRingCount > 0) {
            vboRingsCapBytes = uploadDynamicVbo(vboRings, vboRingsCapBytes, packer.ringsBuffer(), packer.ringsByteSize(), packer.ringsCapacityBytes())
        }
        if (packer.pointCount > 0) {
            vboPointsCapBytes = uploadDynamicVbo(vboPoints, vboPointsCapBytes, packer.pointsBuffer(), packer.pointsByteSize(), packer.pointsCapacityBytes())
        }
        if (packer.textVertexCount > 0) {
            vboTextCapBytes = uploadDynamicVbo(vboText, vboTextCapBytes, packer.textBuffer(), packer.textByteSize(), packer.textCapacityBytes())
        }

        if (cfg.showGrid) {
            GLES30.glUseProgram(gridProgram)
            GLES30.glUniform2f(uGridViewport, metrics.widthPx, metrics.heightPx)
            GLES30.glUniform2f(uGridOrigin, metrics.originX, metrics.originY)
            GLES30.glUniform1f(uGridScale, metrics.scalePxPerMeter)
            GLES30.glUniform1f(uGridRingStep, metrics.ringStepMeters)
            GLES30.glUniform1f(uGridMaxVisibleDist, metrics.maxVisibleDistMeters)
            GLES30.glBindVertexArray(vaoGrid)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }

        if (packer.lineVertexCount > 0) {
            GLES30.glLineWidth(2.0f)
            GLES30.glUseProgram(solidProgram)
            GLES30.glUniformMatrix4fv(uSolidMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glBindVertexArray(vaoLines)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, packer.lineVertexCount)
        }

        if (packer.solidVertexCount >= 3) {
            GLES30.glUseProgram(solidProgram)
            GLES30.glUniformMatrix4fv(uSolidMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glBindVertexArray(vaoSolid)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }

        if (packer.clusterRingCount > 0) {
            GLES30.glUseProgram(ringProgram)
            GLES30.glUniformMatrix4fv(uRingMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glBindVertexArray(vaoRings)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, packer.clusterRingCount)
        }

        if (packer.pointCount > 0) {
            GLES30.glUseProgram(pointProgram)
            GLES30.glUniformMatrix4fv(uPointMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glUniform1f(uPointSizePx, POINT_SIZE_2D_PX * cfg.pointSizePreset.scale)
            GLES30.glUniform1i(uPointIs3D, 0)
            GLES30.glUniform1i(uPointColorScheme, shaderScheme)
            GLES30.glUniform1i(uPointShape, cfg.pointShape.shaderId)
            GLES30.glBindVertexArray(vaoPoints)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, packer.pointCount)
        }

        if (packer.trackRingCount > 0) {
            GLES30.glUseProgram(ringProgram)
            GLES30.glUniformMatrix4fv(uRingMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glBindVertexArray(vaoRings)
            GLES30.glDrawArrays(GLES30.GL_POINTS, packer.clusterRingCount, packer.trackRingCount)
        }

        if (packer.solidVertexCount > 3) {
            GLES30.glUseProgram(solidProgram)
            GLES30.glUniformMatrix4fv(uSolidMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glBindVertexArray(vaoSolid)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 3, packer.solidVertexCount - 3)
        }

        drawTextPass()
    }

    private fun draw3DFrame(cfg: BevRenderConfig, frame: RadarFrame?, camState: BevCameraState) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        BevCameraController.buildPerspectiveMvpMatrix(
            state = camState,
            widthPx = viewportW.toFloat(),
            heightPx = viewportH.toFloat(),
            maxRangeMeters = cfg.maxRangeMeters,
            outMvp16 = mvp3dMatrix,
            scratchProj16 = scratchProj16,
            scratchView16 = scratchView16
        )

        val effectiveDecayFrames = if (cfg.decayPreset != BevDecayPreset.OFF) {
            cfg.decayPreset.frameCount
        } else if (cfg.trailEnabled) {
            BevDecayPreset.MS_200.frameCount
        } else {
            1
        }
        val resolvedColorMode = cfg.pointColorMode.resolveForView(is3d = true)
        val shaderScheme = BevPanelColorizer.resolveShaderScheme(resolvedColorMode, cfg.colorMap)

        packer.packFrame3D(
            frame = frame,
            widthPx = viewportW.toFloat(),
            heightPx = viewportH.toFloat(),
            maxRangeMeters = cfg.maxRangeMeters,
            mvp3d16 = mvp3dMatrix,
            atlas = atlasMetrics,
            dynamicOnly = cfg.dynamicOnly,
            minSnrFilter = cfg.minSnrFilter,
            showGrid = cfg.showGrid,
            showClusters = cfg.showClusters,
            showRawPoints = cfg.showRawPoints,
            showTracks = cfg.showTracks,
            trailEnabled = cfg.trailEnabled,
            decayFrameCount = effectiveDecayFrames,
            colorMode = cfg.pointColorMode,
            colorMap = cfg.colorMap,
            stixelsEnabled = cfg.stixelsEnabled,
            hideOutliers = cfg.hideOutliers,
            showRoadLanes = cfg.showRoadLanes,
            flipZ = cfg.flipZ
        )

        if (packer.grid3dVertexCount > 0) {
            vboGrid3dCapBytes = uploadDynamicVbo(vboGrid3d, vboGrid3dCapBytes, packer.grid3dBuffer(), packer.grid3dByteSize(), packer.grid3dCapacityBytes())
        }
        if (packer.solidVertexCount > 0) {
            vboSolidCapBytes = uploadDynamicVbo(vboSolid, vboSolidCapBytes, packer.solidBuffer(), packer.solidByteSize(), packer.solidCapacityBytes())
        }
        if (packer.lineVertexCount > 0) {
            vboLinesCapBytes = uploadDynamicVbo(vboLines, vboLinesCapBytes, packer.linesBuffer(), packer.linesByteSize(), packer.linesCapacityBytes())
        }
        if (packer.pointCount > 0) {
            vboPointsCapBytes = uploadDynamicVbo(vboPoints, vboPointsCapBytes, packer.pointsBuffer(), packer.pointsByteSize(), packer.pointsCapacityBytes())
        }
        if (packer.textVertexCount > 0) {
            vboTextCapBytes = uploadDynamicVbo(vboText, vboTextCapBytes, packer.textBuffer(), packer.textByteSize(), packer.textCapacityBytes())
        }

        // Pass 1: 3D Ground Plane Grid (z = 0, depth-masked off so footprints/points near z=0 never Z-fight)
        if (cfg.showGrid && packer.grid3dVertexCount > 0) {
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(grid3dProgram)
            GLES30.glUniformMatrix4fv(uGrid3dMvp, 1, false, mvp3dMatrix, 0)
            GLES30.glUniform1f(uGrid3dMaxRange, cfg.maxRangeMeters)
            GLES30.glUniform1f(uGrid3dRingStep, BevProjectionMath.computeRingStep(cfg.maxRangeMeters))
            GLES30.glBindVertexArray(vaoGrid3d)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, packer.grid3dVertexCount)
        }

        // Pass 2: 3D Translucent Footprints, Ego Chevron & Velocity Arrowheads
        if (packer.solidVertexCount > 0) {
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(solidProgram)
            GLES30.glUniformMatrix4fv(uSolidMvp, 1, false, mvp3dMatrix, 0)
            GLES30.glBindVertexArray(vaoSolid)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, packer.solidVertexCount)
        }

        // Pass 3: 3D Point Cloud (Foxglove Color Schemes + Hardware Depth Test)
        if (packer.pointCount > 0) {
            val shaderScheme3d = BevPanelColorizer.resolveShaderScheme(
                cfg.pointColorMode.resolveForView(is3d = true),
                cfg.colorMap
            )
            GLES30.glDepthMask(true)
            GLES30.glUseProgram(pointProgram)
            GLES30.glUniformMatrix4fv(uPointMvp, 1, false, mvp3dMatrix, 0)
            GLES30.glUniform1f(uPointSizePx, POINT_SIZE_3D_BASE_PX * cfg.pointSizePreset.scale)
            GLES30.glUniform1i(uPointIs3D, 1)
            GLES30.glUniform1i(uPointColorScheme, shaderScheme3d)
            GLES30.glUniform1i(uPointShape, cfg.pointShape.shaderId)
            GLES30.glBindVertexArray(vaoPoints)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, packer.pointCount)
        }

        // Pass 4: 3D Wireframe Bounding Boxes (Ego Hull, EKF Track Boxes, Velocity Shafts, Fallback FCW)
        if (packer.lineVertexCount > 0) {
            GLES30.glDepthMask(false)
            GLES30.glLineWidth(1.5f)
            GLES30.glUseProgram(solidProgram)
            GLES30.glUniformMatrix4fv(uSolidMvp, 1, false, mvp3dMatrix, 0)
            GLES30.glBindVertexArray(vaoLines)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, packer.lineVertexCount)
        }

        // Pass 5: Crisp Screen-Space Billboard Labels & FCW Banner (depth test disabled so always legible)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        drawTextPass()
    }

    private fun drawTextPass() {
        if (packer.textVertexCount > 0 && atlasTextureId != 0) {
            GLES30.glUseProgram(textProgram)
            GLES30.glUniformMatrix4fv(uTextMvp, 1, false, mvp2dMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTextureId)
            GLES30.glUniform1i(uTextAtlas, 0)
            GLES30.glBindVertexArray(vaoText)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, packer.textVertexCount)
        }
    }

    private fun uploadDynamicVbo(
        vboId: Int,
        currentCapBytes: Int,
        buffer: FloatBuffer,
        usedBytes: Int,
        poolCapBytes: Int
    ): Int {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        val nextCap = if (usedBytes > currentCapBytes || poolCapBytes > currentCapBytes) {
            val allocBytes = maxOf(usedBytes, poolCapBytes)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, allocBytes, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, usedBytes, buffer)
            allocBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, usedBytes, buffer)
            currentCapBytes
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return nextCap
    }

    private fun recordTelemetry(drawStartNs: Long, cfg: BevRenderConfig, frame: RadarFrame?, is3D: Boolean) {
        val drawEndNs = System.nanoTime()
        val durationMs = (drawEndNs - drawStartNs) / 1_000_000f

        if (cfg.enableTimingLog) {
            val deltaDrawMs = if (lastDrawStartNs > 0L) (drawStartNs - lastDrawStartNs) / 1_000_000.0 else 0.0
            val seqNum = frame?.header?.frameNumber ?: -1L
            AppLogger.d(
                "RadarBevPlot",
                String.format(
                    Locale.US,
                    "[DRAW_BEV] backend=GLES30_%s seq=#%d | deltaDrawMs=%.2f | durationMs=%.2f | pts=%d | trk=%d",
                    if (is3D) "3D" else "2D",
                    seqNum,
                    deltaDrawMs,
                    durationMs.toDouble(),
                    packer.pointCount,
                    packer.trackRingCount
                )
            )
        }
        lastDrawStartNs = drawStartNs

        if (cfg.enablePerfWindowLog) {
            if (windowStartNs == 0L) windowStartNs = drawStartNs
            if (sampleCount < MAX_PERF_SAMPLES) {
                sampleDurationsMs[sampleCount++] = durationMs
            }
            val elapsedNs = drawEndNs - windowStartNs
            if (elapsedNs >= PERF_WINDOW_NS && sampleCount > 0) {
                Arrays.sort(sampleDurationsMs, 0, sampleCount)
                val p50 = sampleDurationsMs[((sampleCount - 1) * 50) / 100]
                val p95 = sampleDurationsMs[((sampleCount - 1) * 95) / 100]
                val maxMs = sampleDurationsMs[sampleCount - 1]
                val fps = sampleCount / (elapsedNs / 1_000_000_000.0)
                AppLogger.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "[GL_BEV_5S] mode=%s | frames=%d | fps=%.1f | p50=%.2fms | p95=%.2fms | max=%.2fms | pts=%d | trk=%d",
                        if (is3D) "3D" else "2D",
                        sampleCount,
                        fps,
                        p50.toDouble(),
                        p95.toDouble(),
                        maxMs.toDouble(),
                        packer.pointCount,
                        packer.trackRingCount
                    )
                )
                windowStartNs = drawEndNs
                sampleCount = 0
            }
        } else {
            windowStartNs = 0L
            sampleCount = 0
        }
    }
}
