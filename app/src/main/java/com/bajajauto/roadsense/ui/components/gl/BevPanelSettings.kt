package com.bajajauto.roadsense.ui.components.gl

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foxglove-inspired point coloring modes for the OpenGL ES 3.0 BEV renderer.
 *
 * [AUTO] matches RoadSense's historic convention (2D = Doppler Velocity, 3D = Longitudinal Range).
 * Any explicit mode chosen via the `Col:...` footer chip or Panel drawer applies consistently to
 * both 2D and 3D views.
 */
enum class BevPointColorMode(
    val shortLabel: String,
    val fullLabel: String
) {
    AUTO("Auto", "Auto (2D Vel / 3D Rng)"),
    VELOCITY("Vel", "Doppler Velocity"),
    RANGE("Rng", "Longitudinal Range"),
    SNR("SNR", "Signal-to-Noise (dB)"),
    ELEVATION_Z("Elev", "Elevation Z (m)"),
    OUTLIER("In/Out", "Inlier / Outlier"),
    CLUSTER_ID("Clst", "Cluster ID");

    /** Resolves [AUTO] to the view-appropriate physical metric while leaving explicit overrides intact. */
    fun resolveForView(is3d: Boolean): BevPointColorMode =
        if (this == AUTO) {
            if (is3d) RANGE else VELOCITY
        } else {
            this
        }

    fun next(): BevPointColorMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    /** True when this mode uses the selectable continuous colormap ([BevColorMap]). */
    fun usesColormap(is3d: Boolean): Boolean = when (resolveForView(is3d)) {
        RANGE, SNR, ELEVATION_Z -> true
        AUTO, VELOCITY, OUTLIER, CLUSTER_ID -> false
    }
}

/**
 * Continuous shader colormap LUTs evaluated directly in the GLSL vertex shader
 * (`foxgloveTurbo` uses the exact Google/Foxglove Turbo 5th-degree polynomial).
 */
enum class BevColorMap(val shortLabel: String) {
    TURBO("Turbo"),
    RAINBOW("Rain"),
    CLASSIC("Clsc");

    fun next(): BevColorMap {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

enum class BevPointShape(
    val symbolLabel: String,
    val fullLabel: String,
    val shaderId: Int
) {
    CIRCLE("●", "Circle", 0),
    SQUARE("■", "Square", 1);

    fun toggle(): BevPointShape = if (this == CIRCLE) SQUARE else CIRCLE
}

enum class BevPointSizePreset(
    val shortLabel: String,
    val scale: Float
) {
    SMALL("S", 0.78f),
    MEDIUM("M", 1.0f),
    LARGE("L", 1.32f);

    fun next(): BevPointSizePreset {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Temporal persistence preset (20 Hz radar = 50 ms per frame).
 */
enum class BevDecayPreset(
    val shortLabel: String,
    val frameCount: Int
) {
    OFF("Off", 1),
    MS_200("0.2s", 4),
    MS_500("0.5s", 10),
    SEC_1("1.0s", 20);

    fun next(): BevDecayPreset {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Quick 3D perspective camera angles adapted from Foxglove 3D Panel viewpoints.
 */
enum class BevCameraPreset(
    val shortLabel: String,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val distanceScale: Float
) {
    CHASE("Chase", 0f, 20f, 1.0f),
    ISO("Iso", -38f, 38f, 1.05f),
    TOP("Top", 0f, 80f, 1.12f),
    LOW("Low", 0f, 7f, 0.74f);

    fun next(): BevCameraPreset {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Pure-Kotlin color & scalar normalization helpers shared between [BevGlVertexPacker]
 * (for CPU-colored Vertical Stixels & adaptive SNR ranges) and [RadarBevPlot] UI legends.
 */
object BevPanelColorizer {
    const val SCHEME_DOPPLER = 0
    const val SCHEME_CLASSIC_SCALAR = 1
    const val SCHEME_TURBO = 2
    const val SCHEME_RAINBOW = 3
    const val SCHEME_CLUSTER = 4
    const val SCHEME_OUTLIER = 5

    const val DEFAULT_SNR_MIN_DB = 5.0f
    const val DEFAULT_SNR_MAX_DB = 35.0f
    const val MIN_SNR_SPAN_DB = 10.0f
    const val ELEV_MIN_M = -20.0f
    const val ELEV_MAX_M = 20.0f
    const val DOPPLER_SPAN_MPS = 10.0f

    fun resolveShaderScheme(
        resolvedMode: BevPointColorMode,
        colorMap: BevColorMap
    ): Int = when (resolvedMode) {
        BevPointColorMode.VELOCITY -> SCHEME_DOPPLER
        BevPointColorMode.OUTLIER -> SCHEME_OUTLIER
        BevPointColorMode.CLUSTER_ID -> SCHEME_CLUSTER
        BevPointColorMode.RANGE,
        BevPointColorMode.SNR,
        BevPointColorMode.ELEVATION_Z,
        BevPointColorMode.AUTO -> when (colorMap) {
            BevColorMap.TURBO -> SCHEME_TURBO
            BevColorMap.RAINBOW -> SCHEME_RAINBOW
            BevColorMap.CLASSIC -> SCHEME_CLASSIC_SCALAR
        }
    }

    /**
     * Computes a flicker-free adaptive `[minSnrDb, maxSnrDb]` window from the currently visible
     * detections so points below 5 dB or well above 35 dB stay cleanly differentiated across the
     * full colormap without saturating at either end.
     */
    fun computeSmartSnrBounds(
        observedMinDb: Float,
        observedMaxDb: Float,
        prevMinDb: Float,
        prevMaxDb: Float,
        hasPrev: Boolean
    ): Pair<Float, Float> {
        if (!observedMinDb.isFinite() || !observedMaxDb.isFinite() || observedMaxDb < observedMinDb) {
            return if (hasPrev) prevMinDb to prevMaxDb else DEFAULT_SNR_MIN_DB to DEFAULT_SNR_MAX_DB
        }

        val rawSpan = observedMaxDb - observedMinDb
        val (targetMin, targetMax) = if (rawSpan < MIN_SNR_SPAN_DB) {
            val mid = 0.5f * (observedMinDb + observedMaxDb)
            (mid - 0.5f * MIN_SNR_SPAN_DB) to (mid + 0.5f * MIN_SNR_SPAN_DB)
        } else {
            observedMinDb to observedMaxDb
        }

        if (!hasPrev) {
            return targetMin to targetMax
        }

        val nextMin = if (targetMin < prevMinDb) {
            0.55f * prevMinDb + 0.45f * targetMin
        } else {
            0.88f * prevMinDb + 0.12f * targetMin
        }
        val nextMax = if (targetMax > prevMaxDb) {
            0.55f * prevMaxDb + 0.45f * targetMax
        } else {
            0.88f * prevMaxDb + 0.12f * targetMax
        }

        return if (nextMax - nextMin < MIN_SNR_SPAN_DB) {
            val mid = 0.5f * (nextMin + nextMax)
            (mid - 0.5f * MIN_SNR_SPAN_DB) to (mid + 0.5f * MIN_SNR_SPAN_DB)
        } else {
            nextMin to nextMax
        }
    }

    /**
     * Maps a point's selected metric into a normalized `[0, 1]` scalar matching `aMeta.x`
     * consumed by [BevGlShaders].
     */
    fun normalizeScalar(
        resolvedMode: BevPointColorMode,
        forwardY: Float,
        z: Float,
        dopplerMps: Float,
        snrDb: Float,
        clusterId: Int,
        isOutlier: Boolean,
        maxRangeM: Float,
        snrMinDb: Float,
        snrMaxDb: Float
    ): Float = when (resolvedMode) {
        BevPointColorMode.AUTO,
        BevPointColorMode.VELOCITY -> {
            val norm = (dopplerMps / DOPPLER_SPAN_MPS).coerceIn(-1f, 1f)
            norm * 0.5f + 0.5f
        }
        BevPointColorMode.RANGE -> {
            (forwardY / max(maxRangeM, 1f)).coerceIn(0f, 1f)
        }
        BevPointColorMode.SNR -> {
            val span = max(snrMaxDb - snrMinDb, 1e-3f)
            ((snrDb - snrMinDb) / span).coerceIn(0f, 1f)
        }
        BevPointColorMode.ELEVATION_Z -> {
            ((z - ELEV_MIN_M) / (ELEV_MAX_M - ELEV_MIN_M)).coerceIn(0f, 1f)
        }
        BevPointColorMode.OUTLIER -> {
            if (isOutlier) 1f else 0f
        }
        BevPointColorMode.CLUSTER_ID -> {
            if (clusterId <= 0 || clusterId == 255) 0f else (((clusterId - 1) % 12) + 1) / 13f
        }
    }

    /** Returns packed 0xAARRGGBB color stops matching the active GLSL colormap for Compose gradient legends. */
    fun evalColorMapStopsArgb(colorMap: BevColorMap, steps: Int = 9): List<Int> {
        val scheme = when (colorMap) {
            BevColorMap.TURBO -> SCHEME_TURBO
            BevColorMap.RAINBOW -> SCHEME_RAINBOW
            BevColorMap.CLASSIC -> SCHEME_CLASSIC_SCALAR
        }
        val rgb = FloatArray(3)
        val n = steps.coerceAtLeast(2)
        return List(n) { i ->
            evalPointRgb(scheme, i.toFloat() / (n - 1).toFloat(), rgb)
            val r = (rgb[0].coerceIn(0f, 1f) * 255f).toInt()
            val g = (rgb[1].coerceIn(0f, 1f) * 255f).toInt()
            val b = (rgb[2].coerceIn(0f, 1f) * 255f).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Exact CPU mirror of the GLSL color evaluation in [BevGlShaders] used for 3D Vertical
     * Stixels (`GL_LINES`) and UI colorbar legends. Writes `[R, G, B]` into [outRgb].
     */
    fun evalPointRgb(scheme: Int, scalar: Float, outRgb: FloatArray) {
        when (scheme) {
            SCHEME_DOPPLER -> {
                if (scalar <= 0.475f) {
                    // Approaching (-doppler < -0.5 m/s): RoadSense #FF5252
                    outRgb[0] = 1.0f
                    outRgb[1] = 0.322f
                    outRgb[2] = 0.322f
                } else if (scalar >= 0.525f) {
                    // Receding (+doppler > +0.5 m/s): RoadSense #40C4FF
                    outRgb[0] = 0.251f
                    outRgb[1] = 0.769f
                    outRgb[2] = 1.0f
                } else {
                    // Stationary (|doppler| <= 0.5 m/s): RoadSense #69F0AE
                    outRgb[0] = 0.412f
                    outRgb[1] = 0.941f
                    outRgb[2] = 0.682f
                }
            }
            SCHEME_CLASSIC_SCALAR -> {
                val t = scalar.coerceIn(0f, 1f)
                if (t < 0.35f) {
                    val u = t / 0.35f
                    outRgb[0] = lerp(0.13f, 0.96f, u)
                    outRgb[1] = lerp(0.89f, 0.78f, u)
                    outRgb[2] = lerp(0.68f, 0.15f, u)
                } else if (t < 0.70f) {
                    val u = (t - 0.35f) / 0.35f
                    outRgb[0] = lerp(0.96f, 0.98f, u)
                    outRgb[1] = lerp(0.78f, 0.38f, u)
                    outRgb[2] = lerp(0.15f, 0.24f, u)
                } else {
                    val u = (t - 0.70f) / 0.30f
                    outRgb[0] = lerp(0.98f, 0.74f, u)
                    outRgb[1] = lerp(0.38f, 0.24f, u)
                    outRgb[2] = lerp(0.24f, 0.92f, u)
                }
            }
            SCHEME_TURBO -> evalFoxgloveTurbo(scalar, outRgb)
            SCHEME_RAINBOW -> evalFoxgloveRainbow(scalar, outRgb)
            SCHEME_CLUSTER -> {
                if (scalar <= 0.01f) {
                    outRgb[0] = 0.56f
                    outRgb[1] = 0.60f
                    outRgb[2] = 0.68f
                } else {
                    val hue = (scalar * 6.0f) % 6.0f
                    evalHsv6(hue, 0.82f, 0.94f, outRgb)
                }
            }
            SCHEME_OUTLIER -> {
                if (scalar >= 0.5f) {
                    // Outlier / clutter: bright coral-red
                    outRgb[0] = 0.99f
                    outRgb[1] = 0.33f
                    outRgb[2] = 0.24f
                } else {
                    // Inlier / clean reflector: bright emerald-cyan
                    outRgb[0] = 0.14f
                    outRgb[1] = 0.91f
                    outRgb[2] = 0.65f
                }
            }
            else -> {
                outRgb[0] = 0.8f
                outRgb[1] = 0.85f
                outRgb[2] = 0.9f
            }
        }
    }

    /**
     * Foxglove `turbo()` polynomial matching `D:/ARAS/Foxglove_SRC/.../renderables/colorMode.ts`.
     */
    fun evalFoxgloveTurbo(pct: Float, outRgb: FloatArray) {
        val x = pct.coerceIn(0f, 1f) * 0.99f + 0.01f
        val x2 = x * x
        val x3 = x2 * x
        val x4 = x2 * x2
        val x5 = x3 * x2
        outRgb[0] = (0.13572138f + 4.6153926f * x - 42.66032258f * x2 + 132.13108234f * x3 - 152.94239396f * x4 + 59.28637943f * x5).coerceIn(0f, 1f)
        outRgb[1] = (0.09140261f + 2.19418839f * x + 4.84296658f * x2 - 14.18503333f * x3 + 4.27729857f * x4 + 2.82956604f * x5).coerceIn(0f, 1f)
        outRgb[2] = (0.10667330f + 12.64194608f * x - 60.58204836f * x2 + 110.36276771f * x3 - 89.90310912f * x4 + 27.34824973f * x5).coerceIn(0f, 1f)
    }

    /**
     * Foxglove `rainbowLinear` colormap (`hsv2rgb` sweep across red -> purple).
     */
    fun evalFoxgloveRainbow(pct: Float, outRgb: FloatArray) {
        val t = pct.coerceIn(0f, 1f)
        val h = t * 5.0f // 0..300 deg scaled to [0..5]
        evalHsv6(h, 0.92f, 0.96f, outRgb)
    }

    private fun evalHsv6(h6: Float, s: Float, v: Float, outRgb: FloatArray) {
        val h = ((h6 % 6f) + 6f) % 6f
        val c = v * s
        val x = c * (1f - abs((h % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 1f -> Triple(c, x, 0f)
            h < 2f -> Triple(x, c, 0f)
            h < 3f -> Triple(0f, c, x)
            h < 4f -> Triple(0f, x, c)
            h < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        outRgb[0] = (r1 + m).coerceIn(0f, 1f)
        outRgb[1] = (g1 + m).coerceIn(0f, 1f)
        outRgb[2] = (b1 + m).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = min(max(a + (b - a) * t, 0f), 1f)
}
