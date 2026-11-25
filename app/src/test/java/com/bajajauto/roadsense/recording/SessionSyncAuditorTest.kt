package com.bajajauto.roadsense.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionSyncAuditorTest {

    @Test
    fun auditDetects200HzImuAdaptiveRadarCadenceAndSerializesRadarDiagnostics() {
        val tempDir = Files.createTempDirectory("auditor_test_").toFile()
        try {
            val radarDir = File(tempDir, "radar").apply { mkdirs() }
            val gnssDir = File(tempDir, "gnss").apply { mkdirs() }
            val cameraDir = File(tempDir, "camera").apply { mkdirs() }
            val imuDir = File(tempDir, "imu").apply { mkdirs() }
            val syncDir = File(tempDir, "sync").apply { mkdirs() }

            val sessionInfo = SessionInfo(
                sessionId = "session_test_200hz",
                sessionDir = tempDir,
                radarDir = radarDir,
                gnssDir = gnssDir,
                cameraDir = cameraDir,
                imuDir = imuDir,
                syncDir = syncDir,
                startTimeWallMs = 1_700_000_000_000L,
                startTimeMonotonicNs = 1_000_000_000L,
                totalRadarFrames = 40L,
                totalRadarSalvagedFrames = 3L,
                totalRadarSequenceDrops = 2L
            )

            // 200 Hz IMU samples (5 ms spacing = 5,000,000 ns)
            File(imuDir, "imu_frames.csv").bufferedWriter().use { w ->
                w.write("elapsed_realtime_ns,ax,ay,az\n")
                var t = 1_000_000_000L
                repeat(200) {
                    w.write("$t,0,0,9.81\n")
                    t += 5_000_000L
                }
            }

            // Timeline with 30 FPS camera + 22.2 Hz (45 ms) radar frames + 1 sequence gap (seq 10 -> 13)
            File(tempDir, "session_timeline.csv").bufferedWriter().use { w ->
                w.write("elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary\n")
                var camT = 1_000_000_000L
                repeat(60) { i ->
                    w.write("$camT,CAMERA,FRAME,$i,camera/camera_video.mp4,\n")
                    camT += 33_333_333L
                }
                var radT = 1_000_000_000L
                var seq = 0L
                repeat(40) { idx ->
                    if (idx == 10) seq += 2L // inject 2 dropped sequence numbers
                    w.write("$radT,RADAR,FRAME,$seq,radar/radar_frames.bin,len=1024\n")
                    radT += 45_000_000L
                    seq++
                }
            }

            val result = SessionSyncAuditor.auditSession(sessionInfo)
            assertTrue(result.imuStats.status.startsWith("PASS"))
            assertTrue("Expected ~200 Hz IMU, got ${result.imuStats.effectiveRateHz}", result.imuStats.effectiveRateHz > 180f)
            assertNotNull(result.radarStats)
            assertEquals(2, result.radarStats?.anomalyCount)

            val json = result.toJson()
            val radarObj = json.getJSONObject("radar")
            assertEquals(2, radarObj.getInt("sequenceDrops"))
            assertEquals(3L, radarObj.getLong("salvagedFrames"))
            assertTrue(radarObj.has("p95IntervalMs"))
            assertTrue(radarObj.getDouble("meanIntervalMs") > 40.0)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun auditUses25MsNyquistThresholdFor20HzRadarCameraProximity() {
        val tempDir = Files.createTempDirectory("auditor_20hz_test_").toFile()
        try {
            val radarDir = File(tempDir, "radar").apply { mkdirs() }
            val gnssDir = File(tempDir, "gnss").apply { mkdirs() }
            val cameraDir = File(tempDir, "camera").apply { mkdirs() }
            val imuDir = File(tempDir, "imu").apply { mkdirs() }
            val syncDir = File(tempDir, "sync").apply { mkdirs() }

            val sessionInfo = SessionInfo(
                sessionId = "session_test_20hz",
                sessionDir = tempDir,
                radarDir = radarDir,
                gnssDir = gnssDir,
                cameraDir = cameraDir,
                imuDir = imuDir,
                syncDir = syncDir,
                startTimeWallMs = 1_700_000_000_000L,
                startTimeMonotonicNs = 1_000_000_000L,
                totalRadarFrames = 20L
            )

            File(imuDir, "imu_frames.csv").bufferedWriter().use { w ->
                w.write("elapsed_realtime_ns,ax,ay,az\n")
                var t = 1_000_000_000L
                repeat(100) {
                    w.write("$t,0,0,9.81\n")
                    t += 5_000_000L
                }
            }

            File(tempDir, "session_timeline.csv").bufferedWriter().use { w ->
                w.write("elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary\n")
                var camT = 1_000_000_000L
                repeat(30) { i ->
                    w.write("$camT,CAMERA,FRAME,$i,camera/camera_video.mp4,\n")
                    camT += 33_333_333L
                }
                var radT = 1_000_000_000L
                repeat(20) { seq ->
                    w.write("$radT,RADAR,FRAME,$seq,radar/radar_frames.bin,len=1024\n")
                    radT += 50_000_000L // 20 Hz radar -> 50 ms p50 -> 25 ms Nyquist threshold
                }
            }

            // Write 34 columns matching SyncKeyframeWriter schema (col 11 = deltaCamImuMs, col 28 = radar_status, col 32 = deltaCamRadarMs)
            File(syncDir, "sync_keyframes.csv").bufferedWriter().use { w ->
                w.write((0 until 34).joinToString(",") { "c$it" } + "\n")
                val deltasRad = listOf(2.0f, 12.0f, 22.0f, 24.5f)
                repeat(20) { idx ->
                    val cols = Array(34) { "0" }
                    cols[11] = "1.2"
                    cols[28] = "CONNECTED"
                    cols[32] = deltasRad[idx % deltasRad.size].toString()
                    w.write(cols.joinToString(",") + "\n")
                }
            }

            val result = SessionSyncAuditor.auditSession(sessionInfo)
            val radCam = result.radarCameraProximity
            assertNotNull(radCam)
            assertEquals(25.0f, radCam!!.complianceThresholdMs, 0.2f)
            assertEquals(100.0f, radCam.pctCompliant, 0.1f)
            assertTrue(radCam.status.startsWith("PASS"))
            assertEquals("GOLD", result.certificationGrade)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun zeroAllocAuditorProcessesLargeSessionQuicklyAndAccurately() {
        val tempDir = Files.createTempDirectory("auditor_large_test_").toFile()
        try {
            val radarDir = File(tempDir, "radar").apply { mkdirs() }
            val gnssDir = File(tempDir, "gnss").apply { mkdirs() }
            val cameraDir = File(tempDir, "camera").apply { mkdirs() }
            val imuDir = File(tempDir, "imu").apply { mkdirs() }
            val syncDir = File(tempDir, "sync").apply { mkdirs() }

            val sessionInfo = SessionInfo(
                sessionId = "session_test_large",
                sessionDir = tempDir,
                radarDir = radarDir,
                gnssDir = gnssDir,
                cameraDir = cameraDir,
                imuDir = imuDir,
                syncDir = syncDir,
                startTimeWallMs = 1_700_000_000_000L,
                startTimeMonotonicNs = 1_000_000_000L,
                totalRadarFrames = 12_000L
            )

            // 10-minute synthetic stream: 60,000 IMU (100 Hz), 18,000 Camera (30 Hz), 12,000 Radar (20 Hz)
            File(imuDir, "imu_frames.csv").bufferedWriter().use { w ->
                w.write("elapsed_realtime_ns,ax,ay,az\n")
                var t = 1_000_000_000L
                repeat(60_000) {
                    w.write("$t,0,0,9.81\n")
                    t += 10_000_000L
                }
            }

            File(tempDir, "session_timeline.csv").bufferedWriter().use { w ->
                w.write("elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary\n")
                var camT = 1_000_000_000L
                repeat(18_000) { i ->
                    w.write("$camT,CAMERA,FRAME,$i,camera/camera_video.mp4,\n")
                    camT += 33_333_333L
                }
                var radT = 1_000_000_000L
                repeat(12_000) { seq ->
                    w.write("$radT,RADAR,FRAME,$seq,radar/radar_frames.bin,len=1024\n")
                    radT += 50_000_000L
                }
            }

            val t0 = System.nanoTime()
            val result = SessionSyncAuditor.auditSession(sessionInfo)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L

            assertEquals(18_000L, result.cameraStats.totalCount)
            assertEquals(0, result.cameraStats.anomalyCount)
            assertEquals(60_000L, result.imuStats.totalCount)
            assertEquals(0, result.imuStats.anomalyCount)
            assertEquals(12_000L, result.radarStats?.totalCount)
            assertEquals(0, result.radarStats?.anomalyCount)
            assertTrue("Expected zero-alloc audit < 2500ms on JVM, took ${elapsedMs}ms", elapsedMs < 2500L)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
