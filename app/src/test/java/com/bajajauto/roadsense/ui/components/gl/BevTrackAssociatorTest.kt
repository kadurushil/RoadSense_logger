package com.bajajauto.roadsense.ui.components.gl

import com.bajajauto.roadsense.models.RadarAccTarget
import com.bajajauto.roadsense.models.RadarBsdAlert
import com.bajajauto.roadsense.models.RadarCanOutputs
import com.bajajauto.roadsense.models.RadarFcwAlert
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RadarTrack
import com.bajajauto.roadsense.models.RawRadarPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class BevTrackAssociatorTest {

    private fun makeFrame(
        tracks: List<RadarTrack>,
        fcw: RadarFcwAlert = RadarFcwAlert(0, 0, 0f, 0f, 0f, 0f, 0f),
        acc: RadarAccTarget = RadarAccTarget(0, 0f, 0f, 0f, 0f, 0f)
    ): RadarFrame {
        val header = RadarHeader(
            version = 0x01020003L,
            totalPacketLen = 128,
            platform = 0xA1843L,
            frameNumber = 1L,
            timeCpuCycles = 1000L,
            numDetectedObj = 0,
            numTLVs = 2,
            subFrameNumber = 0
        )
        return RadarFrame(
            header = header,
            points = emptyList(),
            tracks = tracks,
            clusters = emptyList(),
            canOutputs = RadarCanOutputs(
                fcw = fcw,
                bsd = RadarBsdAlert(false, false, 0, 0f),
                acc = acc
            ),
            rawPacket = RawRadarPacket(header = header, payload = byteArrayOf(), hostTimestampNs = 1000L)
        )
    }

    private fun track(
        tid: Int,
        x: Float,
        y: Float,
        vy: Float = 0f,
        clusterId: Int = 0,
        risk: Int = 0
    ) = RadarTrack(
        tid = tid,
        x = x,
        y = y,
        vx = 0f,
        vy = vy,
        xSize = 1.5f,
        ySize = 3.0f,
        clusterId = clusterId,
        risk = risk
    )

    @Test
    fun matchesFcwAndAccByDirectTrackIdAndModulo256() {
        val frame = makeFrame(
            tracks = listOf(
                track(tid = 10, x = -2f, y = 30f),
                track(tid = 263, x = 0.5f, y = 18f), // 263 % 256 == 7
                track(tid = 42, x = 1.0f, y = 40f, clusterId = 99)
            ),
            fcw = RadarFcwAlert(stage = 2, trackId = 7, ttcSec = 1.5f, targetY = 0f, targetX = 0f, targetVy = 0f, targetVx = 0f),
            acc = RadarAccTarget(poiId = 99, targetY = 0f, targetX = 0f, targetVy = 0f, ttiSec = 0f, vSafeMps = 10f)
        )
        val match = BevTrackAssociator.match(frame)
        assertEquals(1, match.fcwIndex)
        assertEquals(2, match.accIndex)
    }

    @Test
    fun fallsBackToSpatialProximityWhenIdsDoNotMatch() {
        val frame = makeFrame(
            tracks = listOf(
                track(tid = 1, x = 0.2f, y = 12.5f),
                track(tid = 2, x = 1.0f, y = 35.0f)
            ),
            fcw = RadarFcwAlert(stage = 1, trackId = 0, ttcSec = 2.1f, targetY = 14.0f, targetX = 0.0f, targetVy = -4f, targetVx = 0f),
            acc = RadarAccTarget(poiId = 0, targetY = 34.0f, targetX = 1.2f, targetVy = -2f, ttiSec = 3f, vSafeMps = 12f)
        )
        val match = BevTrackAssociator.match(frame)
        assertEquals(0, match.fcwIndex)
        assertEquals(1, match.accIndex)
    }

    @Test
    fun fallsBackToRiskCorridorWhenFcwHasNoIdOrPosition() {
        val frame = makeFrame(
            tracks = listOf(
                track(tid = 1, x = 8.0f, y = 10f, vy = -5f, risk = 3),  // outside ±4.5 m corridor
                track(tid = 2, x = 1.5f, y = 28f, vy = -4f, risk = 2),  // corridor risk=2 (score=172)
                track(tid = 3, x = -1.0f, y = 15f, vy = -6f, risk = 2)  // corridor risk=2 closer (score=185)
            ),
            fcw = RadarFcwAlert(stage = 2, trackId = 0, ttcSec = 1.2f, targetY = 0f, targetX = 0f, targetVy = 0f, targetVx = 0f)
        )
        val match = BevTrackAssociator.match(frame)
        assertEquals(2, match.fcwIndex)
        assertEquals(-1, match.accIndex)
    }

    @Test
    fun inactiveFcwStageZeroProducesNoFcwMatch() {
        val frame = makeFrame(
            tracks = listOf(track(tid = 7, x = 0f, y = 10f, risk = 3)),
            fcw = RadarFcwAlert(stage = 0, trackId = 7, ttcSec = 0f, targetY = 10f, targetX = 0f, targetVy = 0f, targetVx = 0f)
        )
        val match = BevTrackAssociator.match(frame)
        assertEquals(-1, match.fcwIndex)
    }
}
