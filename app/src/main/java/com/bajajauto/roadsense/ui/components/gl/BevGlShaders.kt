package com.bajajauto.roadsense.ui.components.gl

import android.opengl.GLES30
import com.bajajauto.roadsense.logging.AppLogger

/**
 * GLSL ES 3.00 (`#version 300 es`) shader suite for the native OpenGL ES 3.0 Radar BEV Renderer
 * (supporting both 2D Orthographic BEV and 3D Perspective Orbit modes).
 *
 * Six lightweight programs:
 * 1. `GRID_2D` — Fullscreen analytical polar rings, ±60° FOV rays, centerline, and 5 m baseline ticks (0 textures).
 * 2. `GRID_3D` — World-space ground plane (`z=0`) with `fwidth()` AA polar arcs, FOV rays, Cartesian grid & horizon fade.
 * 3. `SOLID`   — 2D/3D colored triangles & wireframes (ego silhouette, 3D track bounding boxes, velocity arrows & shafts).
 * 4. `POINT`   — Single-draw-call point cloud supporting 2D Doppler colors & 3D Longitudinal-Distance gradient + trail alpha.
 * 5. `RING`    — Single-pass multi-ring radial SDF for 2D Clusters, EKF Tracks, ACC, FCW & Fallback FCW.
 * 6. `TEXT`    — Batched textured-quad renderer sampling the 512×256 `GL_R8` monospace glyph atlas.
 */
object BevGlShaders {

    private const val TAG = "RadarGlBev"

    // -------------------------------------------------------------------------
    // 1. 2D Procedural Polar Grid & FOV Overlay (Fullscreen triangle via gl_VertexID)
    // -------------------------------------------------------------------------
    const val GRID_VERTEX_SHADER = """#version 300 es
precision highp float;
uniform vec2 uViewport;
out vec2 vPx;
void main() {
    vec2 ndc;
    if (gl_VertexID == 0) {
        ndc = vec2(-1.0, -1.0);
    } else if (gl_VertexID == 1) {
        ndc = vec2(3.0, -1.0);
    } else {
        ndc = vec2(-1.0, 3.0);
    }
    gl_Position = vec4(ndc, 0.0, 1.0);
    vPx = vec2((ndc.x * 0.5 + 0.5) * uViewport.x, (0.5 - ndc.y * 0.5) * uViewport.y);
}
"""

    const val GRID_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec2 vPx;
uniform vec2 uViewport;
uniform vec2 uOrigin;
uniform float uScale;
uniform float uRingStep;
uniform float uMaxVisibleDist;
out vec4 fragColor;

vec4 over(vec4 dst, vec4 src) {
    float outA = src.a + dst.a * (1.0 - src.a);
    if (outA <= 0.0001) return vec4(0.0);
    vec3 outRgb = (src.rgb * src.a + dst.rgb * dst.a * (1.0 - src.a)) / outA;
    return vec4(outRgb, outA);
}

void main() {
    vec2 relPx = vec2(vPx.x - uOrigin.x, uOrigin.y - vPx.y);
    vec4 col = vec4(0.0);

    if (relPx.y >= -1.0) {
        // 1. ±60° FOV boundary rays (#334DD0E1, stroke 1.5 px)
        float distFovPx = abs(abs(relPx.x) * 0.5 - relPx.y * 0.8660254);
        float covFov = (relPx.y >= 0.0) ? (1.0 - smoothstep(0.45, 1.35, distFovPx)) : 0.0;
        if (covFov > 0.0) {
            col = over(col, vec4(0.302, 0.816, 0.882, 0.20 * covFov));
        }

        // 2. Forward centerline (#5500E5FF, stroke 1.5 px, y in [10, originY])
        if (vPx.y >= 10.0 && relPx.y >= 0.0) {
            float covCenter = 1.0 - smoothstep(0.45, 1.35, abs(relPx.x));
            if (covCenter > 0.0) {
                col = over(col, vec4(0.0, 0.898, 1.0, 0.333 * covCenter));
            }
        }

        // 3. Concentric polar range rings (#FF37474F, stroke 1.2 px)
        float distMeters = length(relPx) / max(uScale, 0.0001);
        if (relPx.y >= 0.0 && distMeters >= uRingStep * 0.5 && distMeters <= uMaxVisibleDist + 1.0 / max(uScale, 0.0001)) {
            float nearestIdx = floor(distMeters / uRingStep + 0.5);
            float ringMeters = nearestIdx * uRingStep;
            if (ringMeters >= uRingStep * 0.9 && ringMeters <= uMaxVisibleDist) {
                float distRingPx = abs(distMeters - ringMeters) * uScale;
                float covRing = 1.0 - smoothstep(0.35, 1.15, distRingPx);
                if (covRing > 0.0) {
                    col = over(col, vec4(0.216, 0.278, 0.310, covRing));
                }
            }
        }

        // 4. Lateral 5 m baseline ticks (#FF263238, stroke 1.0 px, height 12 px)
        if (relPx.y >= 0.0 && relPx.y <= 12.0 && vPx.x >= 20.0 && vPx.x <= uViewport.x - 20.0) {
            float absX = abs(relPx.x);
            float tickStepPx = 5.0 * uScale;
            if (tickStepPx > 1.0 && absX >= tickStepPx * 0.5) {
                float tickIdx = floor(absX / tickStepPx + 0.5);
                if (tickIdx >= 1.0 && tickIdx <= 64.0) {
                    float distTickPx = abs(absX - tickIdx * tickStepPx);
                    float covTick = 1.0 - smoothstep(0.25, 1.0, distTickPx);
                    if (covTick > 0.0) {
                        col = over(col, vec4(0.149, 0.196, 0.220, covTick));
                    }
                }
            }
        }
    }

    if (col.a <= 0.001) discard;
    fragColor = col;
}
"""

    // -------------------------------------------------------------------------
    // 2. 3D World Ground Plane Grid + Polar Arcs + ±60° FOV Rays (z = 0)
    // -------------------------------------------------------------------------
    const val GRID_3D_VERTEX_SHADER = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aWorldXY;
uniform mat4 uMvp;
out vec2 vWorldXY;
void main() {
    vWorldXY = aWorldXY;
    gl_Position = uMvp * vec4(aWorldXY, 0.0, 1.0);
}
"""

    const val GRID_3D_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec2 vWorldXY;
uniform float uMaxRange;
uniform float uRingStep;
out vec4 fragColor;

vec4 over(vec4 dst, vec4 src) {
    float outA = src.a + dst.a * (1.0 - src.a);
    if (outA <= 0.0001) return vec4(0.0);
    vec3 outRgb = (src.rgb * src.a + dst.rgb * dst.a * (1.0 - src.a)) / outA;
    return vec4(outRgb, outA);
}

void main() {
    float r = length(vWorldXY);
    // Smooth radial fadeout beyond maxRange so the 3D ground plane blends cleanly into #090B10
    float fade = 1.0 - smoothstep(uMaxRange * 0.92, uMaxRange * 1.28, r);
    if (fade <= 0.005) discard;

    vec4 col = vec4(0.0);

    // 1. Subtle 10m Cartesian ground reference grid (or 5m when maxRange <= 30m)
    float cellStep = (uMaxRange <= 30.0) ? 5.0 : 10.0;
    vec2 gridCoord = vWorldXY / cellStep;
    vec2 gridDist = abs(fract(gridCoord - 0.5) - 0.5) / max(fwidth(gridCoord), vec2(0.0001));
    float gridLine = 1.0 - smoothstep(0.6, 1.5, min(gridDist.x, gridDist.y));
    if (gridLine > 0.0) {
        col = over(col, vec4(0.149, 0.212, 0.255, 0.42 * gridLine * fade));
    }

    if (vWorldXY.y >= -0.05) {
        // 2. ±60° FOV rays (#444DD0E1)
        float fovMetric = abs(abs(vWorldXY.x) * 0.5 - vWorldXY.y * 0.8660254);
        float fovWidth = max(fwidth(fovMetric), 0.001);
        float covFov = (vWorldXY.y >= 0.0 && r <= uMaxRange * 1.15)
            ? (1.0 - smoothstep(0.6, 1.6, fovMetric / fovWidth))
            : 0.0;
        if (covFov > 0.0) {
            col = over(col, vec4(0.302, 0.816, 0.882, 0.34 * covFov * fade));
        }

        // 3. Forward centerline (x = 0, y >= 0, #6600E5FF)
        float centerWidth = max(fwidth(vWorldXY.x), 0.001);
        float covCenter = (vWorldXY.y >= 0.0 && vWorldXY.y <= uMaxRange * 1.15)
            ? (1.0 - smoothstep(0.65, 1.75, abs(vWorldXY.x) / centerWidth))
            : 0.0;
        if (covCenter > 0.0) {
            col = over(col, vec4(0.0, 0.898, 1.0, 0.45 * covCenter * fade));
        }

        // 4. Concentric polar range arcs (#FF455A64)
        if (vWorldXY.y >= 0.0 && r >= uRingStep * 0.5 && r <= uMaxRange + uRingStep * 0.25) {
            float ringIdx = floor(r / uRingStep + 0.5);
            float ringM = ringIdx * uRingStep;
            if (ringM >= uRingStep * 0.9 && ringM <= uMaxRange + 0.05) {
                float rWidth = max(fwidth(r), 0.001);
                float covRing = 1.0 - smoothstep(0.6, 1.6, abs(r - ringM) / rWidth);
                if (covRing > 0.0) {
                    col = over(col, vec4(0.285, 0.375, 0.425, 0.88 * covRing * fade));
                }
            }
        }
    }

    if (col.a <= 0.005) discard;
    fragColor = col;
}
"""

    // -------------------------------------------------------------------------
    // 3. Solid Colored 2D/3D Triangles & Wireframe Lines
    // -------------------------------------------------------------------------
    const val SOLID_VERTEX_SHADER = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
uniform mat4 uMvp;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vColor = aColor;
}
"""

    const val SOLID_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec4 vColor;
out vec4 fragColor;
void main() {
    fragColor = vColor;
}
"""

    // -------------------------------------------------------------------------
    // 4. Point Cloud (Foxglove Colormaps: Doppler, Classic, Turbo, Rainbow, Cluster, Outlier)
    // -------------------------------------------------------------------------
    const val POINT_VERTEX_SHADER = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;  // 2D: (sx, sy, 0) | 3D: (xM, yM, zM)
layout(location = 1) in vec2 aMeta; // x = normalized [0..1] scalar; y = trail alpha [0..1]
uniform mat4 uMvp;
uniform float uPointSizePx;
uniform int uIs3D;
uniform int uColorScheme; // 0=Doppler, 1=Classic, 2=Turbo, 3=Rainbow, 4=Cluster, 5=Outlier
out vec4 vColor;

vec3 classicScalarColor(float t) {
    float c = clamp(t, 0.0, 1.0);
    vec3 c0 = vec3(0.130, 0.890, 0.680);
    vec3 c1 = vec3(0.960, 0.780, 0.150);
    vec3 c2 = vec3(0.980, 0.380, 0.240);
    vec3 c3 = vec3(0.740, 0.240, 0.920);
    if (c < 0.35) {
        return mix(c0, c1, c / 0.35);
    } else if (c < 0.70) {
        return mix(c1, c2, (c - 0.35) / 0.35);
    } else {
        return mix(c2, c3, (c - 0.70) / 0.30);
    }
}

// Foxglove Turbo polynomial (matches Foxglove ThreeDeeRender/renderables/colorMode.ts)
vec3 foxgloveTurbo(float pct) {
    float x = clamp(pct, 0.0, 1.0) * 0.99 + 0.01;
    vec4 v4 = vec4(1.0, x, x * x, x * x * x);
    vec2 v2 = vec2(v4.z, v4.w) * v4.z;
    float r = dot(v4, vec4(0.13572138, 4.6153926, -42.66032258, 132.13108234))
            + dot(v2, vec2(-152.94239396, 59.28637943));
    float g = dot(v4, vec4(0.09140261, 2.19418839, 4.84296658, -14.18503333))
            + dot(v2, vec2(4.27729857, 2.82956604));
    float b = dot(v4, vec4(0.10667330, 12.64194608, -60.58204836, 110.36276771))
            + dot(v2, vec2(-89.90310912, 27.34824973));
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

vec3 hsvToRgb6(float h6, float s, float v) {
    float h = mod(mod(h6, 6.0) + 6.0, 6.0);
    float c = v * s;
    float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
    float m = v - c;
    vec3 rgb;
    if (h < 1.0) {
        rgb = vec3(c, x, 0.0);
    } else if (h < 2.0) {
        rgb = vec3(x, c, 0.0);
    } else if (h < 3.0) {
        rgb = vec3(0.0, c, x);
    } else if (h < 4.0) {
        rgb = vec3(0.0, x, c);
    } else if (h < 5.0) {
        rgb = vec3(x, 0.0, c);
    } else {
        rgb = vec3(c, 0.0, x);
    }
    return clamp(rgb + vec3(m), 0.0, 1.0);
}

vec3 evalSchemeColor(int scheme, float scalar) {
    if (scheme == 0) {
        // Doppler velocity: <=0.475 approaching, >=0.525 receding, else stationary
        if (scalar <= 0.475) {
            return vec3(1.0, 0.322, 0.322);
        } else if (scalar >= 0.525) {
            return vec3(0.251, 0.769, 1.0);
        } else {
            return vec3(0.412, 0.941, 0.682);
        }
    } else if (scheme == 1) {
        return classicScalarColor(scalar);
    } else if (scheme == 2) {
        return foxgloveTurbo(scalar);
    } else if (scheme == 3) {
        return hsvToRgb6(clamp(scalar, 0.0, 1.0) * 5.0, 0.92, 0.96);
    } else if (scheme == 4) {
        if (scalar <= 0.01) {
            return vec3(0.56, 0.60, 0.68);
        }
        return hsvToRgb6(scalar * 6.0, 0.82, 0.94);
    } else {
        // Scheme 5: Inlier (<0.5 -> emerald/cyan) vs RANSAC Outlier (>=0.5 -> coral-red)
        return (scalar >= 0.5) ? vec3(0.99, 0.33, 0.24) : vec3(0.14, 0.91, 0.65);
    }
}

void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    if (uIs3D == 1) {
        float depth = max(gl_Position.w, 1.0);
        gl_PointSize = clamp(uPointSizePx * 28.0 / depth, 4.5, 24.0);
    } else {
        gl_PointSize = uPointSizePx;
    }
    vColor = vec4(evalSchemeColor(uColorScheme, aMeta.x), aMeta.y);
}
"""

    const val POINT_FRAGMENT_SHADER = """#version 300 es
precision highp float;
uniform int uPointShape; // 0 = Circle, 1 = Square
in vec4 vColor;
out vec4 fragColor;
void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float edge;
    if (uPointShape == 1) {
        float boxDist = max(abs(d.x), abs(d.y));
        edge = 1.0 - smoothstep(0.38, 0.49, boxDist);
    } else {
        float dist = length(d);
        edge = 1.0 - smoothstep(0.36, 0.50, dist);
    }
    if (edge <= 0.12) discard;
    float alpha = edge * vColor.a;
    if (alpha <= 0.02) discard;
    fragColor = vec4(vColor.rgb, alpha);
}
"""

    // -------------------------------------------------------------------------
    // 5. Unified 2D Multi-Ring Radial SDF (Clusters, EKF Tracks, ACC, FCW, Fallback FCW)
    // -------------------------------------------------------------------------
    const val RING_VERTEX_SHADER = """#version 300 es
precision highp float;
layout(location = 0) in vec4 aMarker; // xy = screen px, z = outerExtentPx, w = styleId
uniform mat4 uMvp;
out float vBoxSizePx;
out float vStyle;
void main() {
    gl_Position = uMvp * vec4(aMarker.xy, 0.0, 1.0);
    float boxSize = aMarker.z * 2.0 + 4.0;
    gl_PointSize = boxSize;
    vBoxSizePx = boxSize;
    vStyle = aMarker.w;
}
"""

    const val RING_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in float vBoxSizePx;
in float vStyle;
out vec4 fragColor;

vec4 over(vec4 dst, vec4 src) {
    float outA = src.a + dst.a * (1.0 - src.a);
    if (outA <= 0.0001) return vec4(0.0);
    vec3 outRgb = (src.rgb * src.a + dst.rgb * dst.a * (1.0 - src.a)) / outA;
    return vec4(outRgb, outA);
}

float discCov(float dPx, float radiusPx) {
    return 1.0 - smoothstep(radiusPx - 0.75, radiusPx + 0.75, dPx);
}

float strokeCov(float dPx, float radiusPx, float strokeWidthPx) {
    float halfW = strokeWidthPx * 0.5;
    float edgeDist = abs(dPx - radiusPx);
    return 1.0 - smoothstep(halfW - 0.5, halfW + 0.75, edgeDist);
}

void main() {
    float dPx = length(gl_PointCoord - vec2(0.5)) * vBoxSizePx;
    vec4 col = vec4(0.0);

    if (vStyle < -0.5) {
        vec3 green = vec3(0.0, 0.902, 0.463); // #00E676 Cluster (outer halo around 14px Track ring)
        col = over(col, vec4(green, 0.14 * discCov(dPx, 18.0)));
        col = over(col, vec4(green, strokeCov(dPx, 18.0, 1.8)));
    } else if (vStyle < 0.5) {
        vec3 gold = vec3(1.0, 0.702, 0.0);
        col = over(col, vec4(gold, 0.251 * discCov(dPx, 14.0)));
        col = over(col, vec4(gold, strokeCov(dPx, 14.0, 2.0)));
    } else if (vStyle < 1.5) {
        vec3 cyan = vec3(0.0, 0.898, 1.0);
        col = over(col, vec4(cyan, 0.60 * strokeCov(dPx, 19.5, 1.5)));
        col = over(col, vec4(cyan, 0.22 * discCov(dPx, 16.0)));
        col = over(col, vec4(cyan, strokeCov(dPx, 16.0, 2.5)));
    } else if (vStyle < 2.5) {
        vec3 red = vec3(1.0, 0.090, 0.267);
        col = over(col, vec4(red, 0.65 * strokeCov(dPx, 20.0, 1.8)));
        col = over(col, vec4(red, 0.251 * discCov(dPx, 16.0)));
        col = over(col, vec4(red, strokeCov(dPx, 16.0, 2.5)));
    } else {
        vec3 red = vec3(1.0, 0.090, 0.267);
        col = over(col, vec4(red, 0.251 * discCov(dPx, 24.0)));
        col = over(col, vec4(red, 0.702 * strokeCov(dPx, 20.0, 2.0)));
        col = over(col, vec4(red, strokeCov(dPx, 16.0, 2.5)));
        col = over(col, vec4(red, discCov(dPx, 4.0)));
    }

    if (col.a <= 0.005) discard;
    fragColor = col;
}
"""

    // -------------------------------------------------------------------------
    // 6. GPU Glyph Atlas Text Renderer (Single-Channel GL_R8 Texture)
    // -------------------------------------------------------------------------
    const val TEXT_VERTEX_SHADER = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
uniform mat4 uMvp;
out vec2 vUv;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
}
"""

    const val TEXT_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec2 vUv;
in vec4 vColor;
uniform sampler2D uAtlas;
out vec4 fragColor;
void main() {
    float mask = texture(uAtlas, vUv).r;
    float a = mask * vColor.a;
    if (a <= 0.01) discard;
    fragColor = vec4(vColor.rgb, a);
}
"""

    fun compileProgram(name: String, vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(name, GLES30.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(name, GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return 0
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linkStatus[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(program)
            AppLogger.e(TAG, "Failed to link GL program '$name': $info")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(name: String, type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val typeStr = if (type == GLES30.GL_VERTEX_SHADER) "VERT" else "FRAG"
            val info = GLES30.glGetShaderInfoLog(shader)
            AppLogger.e(TAG, "Failed to compile $typeStr shader '$name': $info")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
