package com.bajajauto.roadsense.ui.components.gl

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Immutable camera pose for the OpenGL ES 3.0 Radar BEV viewport (both 2D Orthographic and 3D Perspective).
 * Owned lock-free inside [BevCameraController] so 60 FPS touch gestures incur 0 Compose recompositions.
 */
data class BevCameraState(
    val is3D: Boolean = false,
    val zoom2D: Float = DEFAULT_ZOOM_2D,
    val panX2DMeters: Float = 0f,
    val panY2DMeters: Float = 0f,
    val yaw3DDeg: Float = DEFAULT_YAW_3D_DEG,
    val pitch3DDeg: Float = DEFAULT_PITCH_3D_DEG,
    val orbitDistScale: Float = DEFAULT_ORBIT_DIST_SCALE,
    val targetX3DMeters: Float = 0f,
    val targetY3DOffsetMeters: Float = 0f
) {
    companion object {
        const val DEFAULT_ZOOM_2D = 1.0f
        const val MIN_ZOOM_2D = 0.5f
        const val MAX_ZOOM_2D = 6.0f

        const val DEFAULT_YAW_3D_DEG = 0f
        const val DEFAULT_PITCH_3D_DEG = 20f
        const val MIN_PITCH_3D_DEG = -10f
        const val MAX_PITCH_3D_DEG = 85f

        const val DEFAULT_ORBIT_DIST_SCALE = 0.45f
        const val MIN_ORBIT_DIST_SCALE = 0.08f
        const val MAX_ORBIT_DIST_SCALE = 2.60f
        const val DEFAULT_TARGET_Y_FRACTION = 0.28f
        const val DEFAULT_FOV_Y_DEG = 60f
    }

    val isModifiedFromDefault: Boolean
        get() = if (is3D) {
            kotlin.math.abs(yaw3DDeg - DEFAULT_YAW_3D_DEG) > 0.25f ||
                kotlin.math.abs(pitch3DDeg - DEFAULT_PITCH_3D_DEG) > 0.25f ||
                kotlin.math.abs(orbitDistScale - DEFAULT_ORBIT_DIST_SCALE) > 0.01f ||
                kotlin.math.abs(targetX3DMeters) > 0.05f ||
                kotlin.math.abs(targetY3DOffsetMeters) > 0.05f
        } else {
            kotlin.math.abs(zoom2D - DEFAULT_ZOOM_2D) > 0.01f ||
                kotlin.math.abs(panX2DMeters) > 0.05f ||
                kotlin.math.abs(panY2DMeters) > 0.05f
        }
}

/**
 * Thread-safe interactive camera controller + pure-Kotlin 3D perspective matrix math.
 * Completely independent of Android SDK `Matrix` so all camera transforms, orbit limits,
 * and 3D-to-screen billboard projections are deterministically tested on JVM.
 */
class BevCameraController(
    initialState: BevCameraState = BevCameraState()
) {
    private val stateRef = AtomicReference(initialState)

    val state: BevCameraState
        get() = stateRef.get()

    fun set3DMode(enabled: Boolean): BevCameraState {
        while (true) {
            val cur = stateRef.get()
            if (cur.is3D == enabled) return cur
            val next = cur.copy(is3D = enabled)
            if (stateRef.compareAndSet(cur, next)) return next
        }
    }

    /** Resets view back to canonical behind-ego pose (preserving current 2D vs 3D mode). */
    fun resetView(): BevCameraState {
        while (true) {
            val cur = stateRef.get()
            val next = BevCameraState(is3D = cur.is3D)
            if (stateRef.compareAndSet(cur, next)) return next
        }
    }

    /** Applies a quick 3D Foxglove-style perspective angle preset (enables 3D mode if off). */
    fun applyPreset(preset: BevCameraPreset): BevCameraState {
        while (true) {
            val cur = stateRef.get()
            val next = BevCameraState(
                is3D = true,
                yaw3DDeg = wrapAngleDeg(preset.azimuthDeg),
                pitch3DDeg = preset.pitchDeg.coerceIn(
                    BevCameraState.MIN_PITCH_3D_DEG,
                    BevCameraState.MAX_PITCH_3D_DEG
                ),
                orbitDistScale = (BevCameraState.DEFAULT_ORBIT_DIST_SCALE * preset.distanceScale).coerceIn(
                    BevCameraState.MIN_ORBIT_DIST_SCALE,
                    BevCameraState.MAX_ORBIT_DIST_SCALE
                )
            )
            if (stateRef.compareAndSet(cur, next)) return next
        }
    }

    /** 1-finger drag: orbits yaw/pitch in 3D mode, or pans viewport in 2D mode. */
    fun onSingleFingerDrag(
        dxPx: Float,
        dyPx: Float,
        viewportW: Float,
        viewportH: Float,
        maxRangeMeters: Float
    ): BevCameraState {
        if (viewportW <= 1f || viewportH <= 1f) return state
        while (true) {
            val cur = stateRef.get()
            val next = if (cur.is3D) {
                val rawYaw = cur.yaw3DDeg + dxPx * 0.28f
                val wrappedYaw = wrapAngleDeg(rawYaw)
                val nextPitch = (cur.pitch3DDeg + dyPx * 0.24f)
                    .coerceIn(BevCameraState.MIN_PITCH_3D_DEG, BevCameraState.MAX_PITCH_3D_DEG)
                cur.copy(yaw3DDeg = wrappedYaw, pitch3DDeg = nextPitch)
            } else {
                val base = BevProjectionMath.computeMetrics(viewportW, viewportH, maxRangeMeters)
                val effScale = (base.scalePxPerMeter * cur.zoom2D).coerceAtLeast(0.5f)
                val maxPan = maxRangeMeters * 1.25f
                cur.copy(
                    panX2DMeters = (cur.panX2DMeters + dxPx / effScale).coerceIn(-maxPan, maxPan),
                    panY2DMeters = (cur.panY2DMeters - dyPx / effScale).coerceIn(-maxPan, maxPan)
                )
            }
            if (stateRef.compareAndSet(cur, next)) return next
        }
    }

    /** 2-finger drag: pans the 3D ground look-at target (rotated by current yaw) or 2D pan. */
    fun onTwoFingerPan(
        dxPx: Float,
        dyPx: Float,
        viewportW: Float,
        viewportH: Float,
        maxRangeMeters: Float
    ): BevCameraState {
        if (viewportW <= 1f || viewportH <= 1f) return state
        while (true) {
            val cur = stateRef.get()
            val next = if (cur.is3D) {
                val orbitDist = (maxRangeMeters * cur.orbitDistScale).coerceAtLeast(4f)
                val metersPerPx = (orbitDist * 1.15f) / viewportH.coerceAtLeast(1f)
                val yawRad = (cur.yaw3DDeg * PI / 180.0).toFloat()
                val cosY = cos(yawRad)
                val sinY = sin(yawRad)
                // Screen right maps to (+cosY, -sinY) in world XY; screen up maps to (+sinY, +cosY)
                val dWorldX = (-dxPx * cosY + dyPx * sinY) * metersPerPx
                val dWorldY = (dxPx * sinY + dyPx * cosY) * metersPerPx
                val maxPan = maxRangeMeters * 1.2f
                cur.copy(
                    targetX3DMeters = (cur.targetX3DMeters + dWorldX).coerceIn(-maxPan, maxPan),
                    targetY3DOffsetMeters = (cur.targetY3DOffsetMeters + dWorldY).coerceIn(-maxPan, maxPan)
                )
            } else {
                val base = BevProjectionMath.computeMetrics(viewportW, viewportH, maxRangeMeters)
                val effScale = (base.scalePxPerMeter * cur.zoom2D).coerceAtLeast(0.5f)
                val maxPan = maxRangeMeters * 1.25f
                cur.copy(
                    panX2DMeters = (cur.panX2DMeters + dxPx / effScale).coerceIn(-maxPan, maxPan),
                    panY2DMeters = (cur.panY2DMeters - dyPx / effScale).coerceIn(-maxPan, maxPan)
                )
            }
            if (stateRef.compareAndSet(cur, next)) return next
        }
    }

    /** Pinch-to-zoom: scales 2D magnification or adjusts 3D orbit distance. */
    fun onPinchZoom(scaleFactor: Float): BevCameraState {
        if (!scaleFactor.isFinite() || scaleFactor <= 0.05f) return state
        val clampedFactor = scaleFactor.coerceIn(0.75f, 1.35f)
        while (true) {
            val cur = stateRef.get()
            val next = if (cur.is3D) {
                val nextScale = (cur.orbitDistScale / clampedFactor)
                    .coerceIn(BevCameraState.MIN_ORBIT_DIST_SCALE, BevCameraState.MAX_ORBIT_DIST_SCALE)
                cur.copy(orbitDistScale = nextScale)
            } else {
                val nextZoom = (cur.zoom2D * clampedFactor)
                    .coerceIn(BevCameraState.MIN_ZOOM_2D, BevCameraState.MAX_ZOOM_2D)
                cur.copy(zoom2D = nextZoom)
            }
            if (stateRef.compareAndSet(cur, next)) return next
        }
    }

    companion object {
        fun wrapAngleDeg(deg: Float): Float {
            var a = deg % 360f
            if (a > 180f) a -= 360f
            if (a < -180f) a += 360f
            return a
        }

        /**
         * Applies 2D interactive zoom & pan to base [BevViewportMetrics].
         * Returns [base] unchanged when at default 1.0x zoom and zero pan (zero-cost fast path).
         */
        fun apply2DCamera(base: BevViewportMetrics, state: BevCameraState): BevViewportMetrics {
            if (!base.isValid || state.is3D || !state.isModifiedFromDefault) return base
            val newScale = base.scalePxPerMeter * state.zoom2D
            val newOriginX = base.originX + state.panX2DMeters * newScale
            val newOriginY = base.originY - state.panY2DMeters * newScale
            val maxVisDist = maxOf(
                base.maxVisibleDistMeters,
                (base.heightPx / newScale.coerceAtLeast(0.1f)) + kotlin.math.abs(state.panY2DMeters) + 25f
            )
            return base.copy(
                originX = newOriginX,
                originY = newOriginY,
                scalePxPerMeter = newScale,
                maxVisibleDistMeters = maxVisDist
            )
        }

        /**
         * Builds a column-major 4x4 3D Perspective View-Projection matrix (`proj * view`) in ISO radar
         * world coordinates (`+X` right, `+Y` forward, `+Z` up).
         */
        fun buildPerspectiveMvpMatrix(
            state: BevCameraState,
            widthPx: Float,
            heightPx: Float,
            maxRangeMeters: Float,
            outMvp16: FloatArray,
            scratchProj16: FloatArray = FloatArray(16),
            scratchView16: FloatArray = FloatArray(16)
        ) {
            require(outMvp16.size >= 16)
            val aspect = (widthPx.coerceAtLeast(1f)) / (heightPx.coerceAtLeast(1f))
            val far = maxOf(450f, maxRangeMeters * 6.0f)
            buildPerspective(BevCameraState.DEFAULT_FOV_Y_DEG, aspect, 0.5f, far, scratchProj16)

            val targetX = state.targetX3DMeters
            val targetY = maxRangeMeters * BevCameraState.DEFAULT_TARGET_Y_FRACTION + state.targetY3DOffsetMeters
            val targetZ = 0f

            val orbitDist = (maxRangeMeters * state.orbitDistScale).coerceAtLeast(4.0f)
            val yawRad = (state.yaw3DDeg * PI / 180.0).toFloat()
            val pitchRad = (state.pitch3DDeg * PI / 180.0).toFloat()
            val horizDist = orbitDist * cos(pitchRad)
            val eyeX = targetX - sin(yawRad) * horizDist
            val eyeY = targetY - cos(yawRad) * horizDist
            val eyeZ = orbitDist * sin(pitchRad)

            buildLookAt(
                eyeX = eyeX, eyeY = eyeY, eyeZ = eyeZ,
                centerX = targetX, centerY = targetY, centerZ = targetZ,
                upX = 0f, upY = 0f, upZ = 1f,
                out16 = scratchView16
            )
            multiply4x4(scratchProj16, scratchView16, outMvp16)
        }

        /**
         * Projects a 3D world point `(wx, wy, wz)` through [mvp16] into Android top-left pixel
         * coordinates `(outScreenXY[0], outScreenXY[1])`. Returns `false` if behind camera or clipped.
         */
        fun projectWorldToScreen(
            wx: Float,
            wy: Float,
            wz: Float,
            mvp16: FloatArray,
            widthPx: Float,
            heightPx: Float,
            outScreenXY: FloatArray
        ): Boolean {
            val cx = mvp16[0] * wx + mvp16[4] * wy + mvp16[8] * wz + mvp16[12]
            val cy = mvp16[1] * wx + mvp16[5] * wy + mvp16[9] * wz + mvp16[13]
            val cz = mvp16[2] * wx + mvp16[6] * wy + mvp16[10] * wz + mvp16[14]
            val cw = mvp16[3] * wx + mvp16[7] * wy + mvp16[11] * wz + mvp16[15]
            if (cw <= 0.1f) return false
            val invW = 1f / cw
            val ndcX = cx * invW
            val ndcY = cy * invW
            val ndcZ = cz * invW
            if (ndcZ < -1f || ndcZ > 1f || ndcX < -1.15f || ndcX > 1.15f || ndcY < -1.15f || ndcY > 1.15f) {
                return false
            }
            outScreenXY[0] = (ndcX * 0.5f + 0.5f) * widthPx
            outScreenXY[1] = (0.5f - ndcY * 0.5f) * heightPx
            return true
        }

        private fun buildPerspective(
            fovYDeg: Float,
            aspect: Float,
            near: Float,
            far: Float,
            out16: FloatArray
        ) {
            for (i in 0 until 16) out16[i] = 0f
            val f = 1f / tan((fovYDeg * PI / 360.0).toFloat())
            val nf = 1f / (near - far)
            out16[0] = f / aspect
            out16[5] = f
            out16[10] = (far + near) * nf
            out16[11] = -1f
            out16[14] = (2f * far * near) * nf
        }

        private fun buildLookAt(
            eyeX: Float, eyeY: Float, eyeZ: Float,
            centerX: Float, centerY: Float, centerZ: Float,
            upX: Float, upY: Float, upZ: Float,
            out16: FloatArray
        ) {
            var fx = centerX - eyeX
            var fy = centerY - eyeY
            var fz = centerZ - eyeZ
            val fLen = hypot(hypot(fx, fy), fz).coerceAtLeast(1e-6f)
            fx /= fLen; fy /= fLen; fz /= fLen

            // s = normalize(f × up)
            var sx = fy * upZ - fz * upY
            var sy = fz * upX - fx * upZ
            var sz = fx * upY - fy * upX
            val sLen = hypot(hypot(sx, sy), sz).coerceAtLeast(1e-6f)
            sx /= sLen; sy /= sLen; sz /= sLen

            // u = s × f
            val ux = sy * fz - sz * fy
            val uy = sz * fx - sx * fz
            val uz = sx * fy - sy * fx

            // Column-major view matrix
            out16[0] = sx;  out16[4] = sy;  out16[8] = sz;   out16[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
            out16[1] = ux;  out16[5] = uy;  out16[9] = uz;   out16[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
            out16[2] = -fx; out16[6] = -fy; out16[10] = -fz; out16[14] = (fx * eyeX + fy * eyeY + fz * eyeZ)
            out16[3] = 0f;  out16[7] = 0f;  out16[11] = 0f;  out16[15] = 1f
        }

        fun multiply4x4(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (col in 0 until 4) {
                val c4 = col * 4
                val b0 = b[c4]
                val b1 = b[c4 + 1]
                val b2 = b[c4 + 2]
                val b3 = b[c4 + 3]
                out[c4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
                out[c4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
                out[c4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
                out[c4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
            }
        }
    }
}
