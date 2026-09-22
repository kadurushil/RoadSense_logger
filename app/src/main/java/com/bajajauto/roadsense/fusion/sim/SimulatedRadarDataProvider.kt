package com.bajajauto.roadsense.fusion.sim

import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RadarHeader
import com.bajajauto.roadsense.models.RadarPoint
import com.bajajauto.roadsense.models.RadarTrack
import com.bajajauto.roadsense.models.RawRadarPacket

/**
 * Generates realistic 10-frame synthetic radar point cloud data for a target vehicle
 * positioned at [targetDist] (default 10.0m) with width [targetWidth] (default 1.30m).
 *
 * Simulates physical automotive millimeter-wave radar reflections:
 * - High-SNR bumper and license plate clusters
 * - Taillight and rear quarter-panel corner reflectors
 * - Tire/wheel contact scatter near road level
 * - Trunk and roof line reflections with true elevation
 * - Natural frame-to-frame scintillation and micro-jitter across 10 distinct frames
 */
object SimulatedRadarDataProvider {

    private val JITTER_X = floatArrayOf(
        0.00f, 0.03f, -0.02f, 0.04f, -0.03f, 0.01f, -0.04f, 0.02f, -0.01f, 0.03f
    )
    private val JITTER_Y = floatArrayOf(
        0.00f, -0.04f, 0.03f, -0.02f, 0.05f, -0.03f, 0.02f, -0.05f, 0.04f, -0.01f
    )
    private val JITTER_SNR = floatArrayOf(
        0.0f, 1.8f, -1.5f, 2.2f, -2.0f, 0.8f, -1.2f, 1.5f, -0.9f, 2.0f
    )

    /**
     * Returns a realistic synthetic [RadarFrame] for the given [frameIndex] (0..9).
     */
    fun getFrame(
        frameIndex: Int,
        targetDist: Float = 10.0f,
        targetWidth: Float = 1.30f
    ): RadarFrame {
        val f = (frameIndex % 10 + 10) % 10
        val halfW = targetWidth / 2f
        val jX = JITTER_X[f]
        val jY = JITTER_Y[f]
        val jS = JITTER_SNR[f]

        val points = mutableListOf<RadarPoint>()

        // 1. Rear License Plate & Bumper Center (Strongest return, ~34 dB)
        points.add(
            RadarPoint(
                x = 0.00f + (jX * 0.4f),
                y = targetDist + 0.02f + (jY * 0.3f),
                z = -0.15f,
                doppler = 0.00f,
                snrDb = 34.0f + jS
            )
        )
        // Bumper Lower Lip
        points.add(
            RadarPoint(
                x = 0.04f - (jX * 0.3f),
                y = targetDist + 0.05f + (jY * 0.4f),
                z = -0.40f,
                doppler = 0.01f,
                snrDb = 31.5f + (jS * 0.8f)
            )
        )

        // 2. Mid-Bumper Lateral Returns
        points.add(
            RadarPoint(
                x = -0.32f + (jX * 0.6f),
                y = targetDist + 0.06f + (jY * 0.5f),
                z = -0.28f,
                doppler = -0.02f,
                snrDb = 29.0f + jS
            )
        )
        points.add(
            RadarPoint(
                x = 0.32f - (jX * 0.5f),
                y = targetDist + 0.06f + (jY * 0.5f),
                z = -0.28f,
                doppler = 0.01f,
                snrDb = 28.5f - jS
            )
        )

        // 3. Left & Right Rear Corners / Taillights (Caliper edges)
        points.add(
            RadarPoint(
                x = -halfW + 0.02f + (jX * 0.5f),
                y = targetDist + 0.12f + (jY * 0.6f),
                z = 0.10f,
                doppler = 0.00f,
                snrDb = 26.5f + (jS * 0.7f)
            )
        )
        points.add(
            RadarPoint(
                x = halfW - 0.02f - (jX * 0.5f),
                y = targetDist + 0.12f + (jY * 0.6f),
                z = 0.10f,
                doppler = 0.00f,
                snrDb = 26.0f - (jS * 0.7f)
            )
        )

        // 4. Rear Quarter Panels
        points.add(
            RadarPoint(
                x = -halfW - 0.04f + (jX * 0.3f),
                y = targetDist + 0.38f + jY,
                z = -0.05f,
                doppler = 0.02f,
                snrDb = 23.5f + jS
            )
        )
        points.add(
            RadarPoint(
                x = halfW + 0.04f - (jX * 0.3f),
                y = targetDist + 0.38f + jY,
                z = -0.05f,
                doppler = -0.01f,
                snrDb = 24.0f - jS
            )
        )

        // 5. Rear Tires / Contact Patches
        points.add(
            RadarPoint(
                x = -halfW + 0.10f + (jX * 0.2f),
                y = targetDist + 0.65f + (jY * 0.4f),
                z = -0.52f,
                doppler = 0.00f,
                snrDb = 22.0f + (jS * 0.5f)
            )
        )
        points.add(
            RadarPoint(
                x = halfW - 0.10f - (jX * 0.2f),
                y = targetDist + 0.65f + (jY * 0.4f),
                z = -0.52f,
                doppler = 0.00f,
                snrDb = 22.5f - (jS * 0.5f)
            )
        )

        // 6. Trunk Lid & Rear Windshield Base
        points.add(
            RadarPoint(
                x = -0.15f + (jX * 0.4f),
                y = targetDist + 0.30f + (jY * 0.5f),
                z = 0.22f,
                doppler = 0.00f,
                snrDb = 25.0f + (jS * 0.6f)
            )
        )
        points.add(
            RadarPoint(
                x = 0.18f - (jX * 0.4f),
                y = targetDist + 0.30f + (jY * 0.5f),
                z = 0.22f,
                doppler = 0.00f,
                snrDb = 25.5f - (jS * 0.6f)
            )
        )

        // 7. Roof Line Peak
        points.add(
            RadarPoint(
                x = 0.00f + (jX * 0.2f),
                y = targetDist + 1.40f + (jY * 0.3f),
                z = 0.58f,
                doppler = 0.00f,
                snrDb = 18.5f + (jS * 0.4f)
            )
        )

        // 8. Exhaust Assembly
        points.add(
            RadarPoint(
                x = -0.38f + (jX * 0.3f),
                y = targetDist + 0.15f + (jY * 0.2f),
                z = -0.44f,
                doppler = 0.00f,
                snrDb = 27.5f + jS
            )
        )

        // 9. Frame-Dependent Scintillation & Ground Multipath (Blinks in and out)
        if (f % 2 == 0) {
            points.add(
                RadarPoint(
                    x = -0.10f + (jX * 0.8f),
                    y = targetDist - 0.20f + (jY * 0.5f),
                    z = -0.65f,
                    doppler = 0.03f,
                    snrDb = 16.0f + jS
                )
            )
        }
        if (f % 3 == 0) {
            points.add(
                RadarPoint(
                    x = 0.22f + (jX * 0.7f),
                    y = targetDist - 0.15f + (jY * 0.6f),
                    z = -0.62f,
                    doppler = -0.02f,
                    snrDb = 15.5f - jS
                )
            )
        }
        if (f % 4 == 0) {
            points.add(
                RadarPoint(
                    x = -halfW + 0.25f,
                    y = targetDist + 0.90f + jY,
                    z = -0.10f,
                    doppler = 0.00f,
                    snrDb = 19.0f + (jS * 0.5f)
                )
            )
        }

        // Hardware Tracker Bounding Box (TLV Type 3)
        val track = RadarTrack(
            tid = 1,
            x = 0.00f + (jX * 0.1f),
            y = targetDist + 1.20f,
            vx = 0.0f,
            vy = 0.0f,
            xSize = targetWidth,
            ySize = 3.80f
        )

        val header = RadarHeader(
            version = 0x01090000L,
            totalPacketLen = 256,
            platform = 0xA1843L,
            frameNumber = f.toLong(),
            timeCpuCycles = System.currentTimeMillis() * 1000L,
            numDetectedObj = points.size,
            numTLVs = 2,
            subFrameNumber = 0
        )

        return RadarFrame(
            header = header,
            points = points,
            tracks = listOf(track),
            rawPacket = RawRadarPacket(header = header, payload = byteArrayOf())
        )
    }
}
