package com.bajajauto.roadsense.ui

import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.camera.CameraEngineState
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.canedge.model.CanedgeHudPhase
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.gnss.GnssState
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorReadinessEvaluatorTest {

    @Test
    fun evaluateRadarDistinguishesIntentionalOfflineFromMidRecordingDropout() {
        // Intentional disconnection -> RED OFF
        val offlineIdle = SensorReadinessEvaluator.evaluateRadar(
            connState = RadarConnectionState.Disconnected,
            radarHz = 0f,
            isRecording = false,
            radarDroppedMidRecording = false
        )
        assertEquals(SensorHudTone.RED, offlineIdle.tone)
        assertEquals("OFF", offlineIdle.compactValue)

        // Recording Camera+CAN intentionally without Radar -> RED OFF
        val offlineIntentionalRec = SensorReadinessEvaluator.evaluateRadar(
            connState = RadarConnectionState.Disconnected,
            radarHz = 0f,
            isRecording = true,
            radarDroppedMidRecording = false
        )
        assertEquals(SensorHudTone.RED, offlineIntentionalRec.tone)
        assertEquals("OFF", offlineIntentionalRec.compactValue)

        // Radar dropped mid-recording -> RED ERR
        val droppedRec = SensorReadinessEvaluator.evaluateRadar(
            connState = RadarConnectionState.Disconnected,
            radarHz = 0f,
            isRecording = true,
            radarDroppedMidRecording = true
        )
        assertEquals(SensorHudTone.RED, droppedRec.tone)
        assertEquals("ERR", droppedRec.compactValue)

        // Reconnecting after hot-plug detach -> YELLOW RECONN
        val reconnecting = SensorReadinessEvaluator.evaluateRadar(
            connState = RadarConnectionState.Reconnecting("USB detached"),
            radarHz = 0f,
            isRecording = true,
            radarDroppedMidRecording = true
        )
        assertEquals(SensorHudTone.YELLOW, reconnecting.tone)
        assertEquals("RECONN", reconnecting.fullValue)
        assertEquals("REC", reconnecting.compactValue)

        // Connected 20 Hz -> GREEN with full and compact labels
        val ready = SensorReadinessEvaluator.evaluateRadar(
            connState = RadarConnectionState.Connected("COM"),
            radarHz = 20.0f,
            isRecording = true,
            radarDroppedMidRecording = false
        )
        assertEquals(SensorHudTone.GREEN, ready.tone)
        assertEquals("20.0Hz OK", ready.fullValue)
        assertEquals("20.0Hz", ready.compactValue)
    }

    @Test
    fun evaluateCameraAndImuFollowTrafficLightSemantics() {
        assertEquals(
            SensorPillStatus("OFF", "OFF", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateCamera(CameraEngineState.Closed, 0f)
        )
        assertEquals(
            SensorPillStatus("STBY", "STBY", SensorHudTone.YELLOW),
            SensorReadinessEvaluator.evaluateCamera(CameraEngineState.Standby, 0f)
        )
        assertEquals(
            SensorPillStatus("WAIT", "WAIT", SensorHudTone.YELLOW),
            SensorReadinessEvaluator.evaluateCamera(CameraEngineState.Previewing(CameraResolution.RES_720P, 30), 0f)
        )
        assertEquals(
            SensorPillStatus("30fps OK", "30fps", SensorHudTone.GREEN),
            SensorReadinessEvaluator.evaluateCamera(CameraEngineState.Previewing(CameraResolution.RES_720P, 30), 30f)
        )
        assertEquals(
            SensorPillStatus("ERR", "ERR", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateCamera(CameraEngineState.Error("HAL"), 0f)
        )

        assertEquals(
            SensorPillStatus("OFF", "OFF", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateImu(0f, isMonitoring = false, isRecording = false)
        )
        assertEquals(
            SensorPillStatus("ERR", "ERR", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateImu(0f, isMonitoring = true, isRecording = true)
        )
        assertEquals(
            SensorPillStatus("200Hz OK", "200Hz", SensorHudTone.GREEN),
            SensorReadinessEvaluator.evaluateImu(200.0f, isMonitoring = true, isRecording = true)
        )
    }

    @Test
    fun evaluateGnssDistinguishesSearchingNetworkAndSatelliteLock() {
        assertEquals(
            SensorPillStatus("OFF", "OFF", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateGnss(GnssState.Disabled, 0f)
        )
        assertEquals(
            SensorPillStatus("SRCH", "SRCH", SensorHudTone.YELLOW),
            SensorReadinessEvaluator.evaluateGnss(GnssState.Searching, 0f)
        )

        val networkFix = GnssFix(
            latitude = 18.5,
            longitude = 73.8,
            elapsedRealtimeNs = 1L,
            utcTimeMs = 1L,
            accuracyMeters = 35f,
            satellitesUsed = 0,
            satellitesInView = 12,
            provider = "network"
        )
        assertEquals(
            SensorPillStatus("NET", "NET", SensorHudTone.YELLOW),
            SensorReadinessEvaluator.evaluateGnss(GnssState.Active(networkFix, 1L), 1.0f)
        )

        val gpsFix = networkFix.copy(provider = "gps", accuracyMeters = 2.1f, satellitesUsed = 8)
        assertEquals(
            SensorPillStatus("1.0Hz OK", "1.0Hz", SensorHudTone.GREEN),
            SensorReadinessEvaluator.evaluateGnss(GnssState.Active(gpsFix, 2L), 1.0f)
        )
    }

    @Test
    fun evaluateCanCoversAllHudPhases() {
        assertEquals(
            SensorPillStatus("OFF", "OFF", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateCan(CanedgeSyncStats(hudPhase = CanedgeHudPhase.OFFLINE))
        )
        assertEquals(
            SensorPillStatus("ERR", "ERR", SensorHudTone.RED),
            SensorReadinessEvaluator.evaluateCan(CanedgeSyncStats(hudPhase = CanedgeHudPhase.ERROR))
        )
        assertEquals(
            SensorPillStatus("CAL", "CAL", SensorHudTone.YELLOW),
            SensorReadinessEvaluator.evaluateCan(CanedgeSyncStats(hudPhase = CanedgeHudPhase.CALIBRATING))
        )
        assertEquals(
            SensorPillStatus("OK", "OK", SensorHudTone.GREEN),
            SensorReadinessEvaluator.evaluateCan(CanedgeSyncStats(hudPhase = CanedgeHudPhase.CONNECTED_SYNCED))
        )
        assertEquals(
            SensorPillStatus("45% 2/3", "45%", SensorHudTone.CYAN),
            SensorReadinessEvaluator.evaluateCan(
                CanedgeSyncStats(
                    hudPhase = CanedgeHudPhase.SYNCING,
                    activeFileProgress = 0.45f,
                    queueCompletedFiles = 1,
                    queueTotalFiles = 3
                )
            )
        )
    }
}
