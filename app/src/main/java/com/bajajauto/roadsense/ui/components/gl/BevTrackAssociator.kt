package com.bajajauto.roadsense.ui.components.gl

import com.bajajauto.roadsense.models.RadarFrame
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Result of associating firmware TLV 6 ADAS alerts (FCW & ACC) with active TLV 3 EKF tracks.
 * Indices are `-1` when no matching track exists in `frame.tracks`.
 */
data class BevAdasMatch(
    val fcwIndex: Int = -1,
    val accIndex: Int = -1
)

/**
 * Zero-allocation ADAS FCW/ACC track associator shared by both OpenGL ES 3.0 and Skia Canvas BEV renderers.
 *
 * Matching cascade (preserving exact historical behavior):
 * 1. Direct ID match (`track.tid == id || track.clusterId == id || (track.tid % 256) == id`).
 * 2. Spatial proximity (`|track.y - targetY| < 4.0 m` OR Euclidean `< 5.0 m`) when `targetY > 0.5 m`.
 * 3. FCW corridor fallback (`|x| <= 4.5 m`): highest `risk * 100 - y` for `risk >= 2 && y <= 50 m`,
 *    then `risk >= 1 && vy < -1.0 m/s`.
 */
object BevTrackAssociator {

    fun pack(fcwIndex: Int, accIndex: Int): Long =
        (fcwIndex.toLong() shl 32) or (accIndex.toLong() and 0xFFFFFFFFL)

    fun unpackFcwIndex(packed: Long): Int = (packed shr 32).toInt()

    fun unpackAccIndex(packed: Long): Int = packed.toInt()

    fun match(frame: RadarFrame?): BevAdasMatch {
        val packed = matchPacked(frame)
        return BevAdasMatch(
            fcwIndex = unpackFcwIndex(packed),
            accIndex = unpackAccIndex(packed)
        )
    }

    /**
     * Allocation-free packed `(fcwIndex, accIndex)` lookup for hot 20–30 Hz draw loops.
     */
    fun matchPacked(frame: RadarFrame?): Long {
        if (frame == null) return pack(-1, -1)
        val tracksList = frame.tracks
        if (tracksList.isEmpty()) return pack(-1, -1)

        val fcwAlert = frame.canOutputs?.fcw
        val accTarget = frame.canOutputs?.acc
        val fcwTrackId = if ((fcwAlert?.trackId ?: 0) > 0) fcwAlert!!.trackId else -1
        val accPoiId = if ((accTarget?.poiId ?: 0) > 0) accTarget!!.poiId else -1

        var matchedFcwIndex = -1
        if (fcwAlert != null && fcwAlert.stage > 0) {
            if (fcwTrackId > 0) {
                for (i in tracksList.indices) {
                    val t = tracksList[i]
                    if (t.tid == fcwTrackId || t.clusterId == fcwTrackId || (t.tid % 256) == fcwTrackId) {
                        matchedFcwIndex = i
                        break
                    }
                }
            }

            if (matchedFcwIndex < 0 && fcwAlert.targetY > 0.5f) {
                for (i in tracksList.indices) {
                    val t = tracksList[i]
                    if (abs(t.y - fcwAlert.targetY) < 4.0f ||
                        hypot(t.x - fcwAlert.targetX, t.y - fcwAlert.targetY) < 5.0f
                    ) {
                        matchedFcwIndex = i
                        break
                    }
                }
            }

            if (matchedFcwIndex < 0) {
                var bestRiskScore = -Float.MAX_VALUE
                var bestIdx = -1
                for (idx in tracksList.indices) {
                    val track = tracksList[idx]
                    if (track.risk >= 2 && abs(track.x) <= 4.5f && track.y <= 50.0f) {
                        val score = track.risk * 100f - track.y
                        if (score > bestRiskScore) {
                            bestRiskScore = score
                            bestIdx = idx
                        }
                    }
                }
                if (bestIdx < 0) {
                    for (idx in tracksList.indices) {
                        val track = tracksList[idx]
                        if (track.risk >= 1 && track.vy < -1.0f && abs(track.x) <= 4.5f) {
                            val score = track.risk * 100f - track.y
                            if (score > bestRiskScore) {
                                bestRiskScore = score
                                bestIdx = idx
                            }
                        }
                    }
                }
                if (bestIdx >= 0) {
                    matchedFcwIndex = bestIdx
                }
            }
        }

        var matchedAccIndex = -1
        if (accTarget != null && (accPoiId > 0 || accTarget.targetY > 0.5f)) {
            if (accPoiId > 0) {
                for (i in tracksList.indices) {
                    val t = tracksList[i]
                    if (t.tid == accPoiId || t.clusterId == accPoiId || (t.tid % 256) == accPoiId) {
                        matchedAccIndex = i
                        break
                    }
                }
            }

            if (matchedAccIndex < 0 && accTarget.targetY > 0.5f) {
                for (i in tracksList.indices) {
                    val t = tracksList[i]
                    if (abs(t.y - accTarget.targetY) < 4.0f ||
                        hypot(t.x - accTarget.targetX, t.y - accTarget.targetY) < 5.0f
                    ) {
                        matchedAccIndex = i
                        break
                    }
                }
            }
        }

        return pack(matchedFcwIndex, matchedAccIndex)
    }
}
