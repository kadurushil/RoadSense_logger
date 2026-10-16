package com.bajajauto.roadsense.sync

import android.os.SystemClock
import com.bajajauto.roadsense.camera.CameraFrameMetadata
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.imu.ImuFrame
import com.bajajauto.roadsense.models.RadarHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

/**
 * High-performance, lock-free, zero-allocation Cross-Sensor Synchronization Engine.
 *
 * Implements continuous hardware monotonic time association across Camera (30 FPS),
 * High-Rate IMU (100–104 Hz), TI mmWave Radar (20 Hz), and GNSS (1 Hz).
 *
 * All high-frequency insertions run in O(1) time on pre-allocated primitive arrays with
 * zero runtime heap allocations, completely eliminating Garbage Collection stutter.
 */
class CrossSensorSyncAssociator {

    companion object {
        private const val IMU_CAPACITY = 256
        private const val IMU_MASK = IMU_CAPACITY - 1

        private const val RADAR_CAPACITY = 64
        private const val RADAR_MASK = RADAR_CAPACITY - 1

        private const val SENSOR_TIMEOUT_NS = 2_000_000_000L // 2.0 seconds
        private const val UI_EMIT_FRAME_INTERVAL = 6L // Throttle UI StateFlow updates to ~5 Hz @ 30 FPS
    }

    // --- High-Rate IMU Pre-allocated Ring Buffer (256 slots ~ 2.5s history) ---
    private val imuMonoNs = LongArray(IMU_CAPACITY)
    private val imuWallMs = LongArray(IMU_CAPACITY)
    private val imuDeliveryNs = LongArray(IMU_CAPACITY)
    private val imuSeqIds = LongArray(IMU_CAPACITY)
    private val imuAx = FloatArray(IMU_CAPACITY)
    private val imuAy = FloatArray(IMU_CAPACITY)
    private val imuAz = FloatArray(IMU_CAPACITY)
    private val imuLinAx = FloatArray(IMU_CAPACITY)
    private val imuLinAy = FloatArray(IMU_CAPACITY)
    private val imuLinAz = FloatArray(IMU_CAPACITY)
    private val imuGx = FloatArray(IMU_CAPACITY)
    private val imuGy = FloatArray(IMU_CAPACITY)
    private val imuGz = FloatArray(IMU_CAPACITY)
    private val imuQw = FloatArray(IMU_CAPACITY)
    private val imuQx = FloatArray(IMU_CAPACITY)
    private val imuQy = FloatArray(IMU_CAPACITY)
    private val imuQz = FloatArray(IMU_CAPACITY)
    private val imuPitchDeg = FloatArray(IMU_CAPACITY)
    private val imuRollDeg = FloatArray(IMU_CAPACITY)
    private val imuYawDeg = FloatArray(IMU_CAPACITY)
    private val imuWriteCursor = AtomicLong(0L)

    // --- Radar Pre-allocated Ring Buffer (64 slots ~ 3.2s history) ---
    private val radHostMonoNs = LongArray(RADAR_CAPACITY)
    private val radFrameNum = LongArray(RADAR_CAPACITY)
    private val radNumObj = IntArray(RADAR_CAPACITY)
    private val radNumTlvs = IntArray(RADAR_CAPACITY)
    private val radSubFrame = IntArray(RADAR_CAPACITY)
    private val radWriteCursor = AtomicLong(0L)

    // --- GNSS Reference ---
    private val latestGnssFix = AtomicReference<GnssFix?>(null)

    // --- Sensor Heartbeats for Presence Awareness ---
    private val lastImuHeartbeatNs = AtomicLong(0L)
    private val lastRadarHeartbeatNs = AtomicLong(0L)
    private val lastCameraHeartbeatNs = AtomicLong(0L)

    // --- Real-time Rolling Statistics ---
    private val keyframeCounter = AtomicLong(0L)
    private val cameraDropCounter = AtomicLong(0L)
    private val imuJitterCounter = AtomicLong(0L)
    private val radarDropCounter = AtomicLong(0L)

    private var lastCamShutterNs = 0L
    private var rollingCamImuDeltaMs = 2.8f
    private var rollingRadarCamDeltaMs = 8.4f
    private var peakCamImuDeltaMs = 0f
    private var peakRadarCamDeltaMs = 0f

    // --- Telemetry StateFlow for UI ---
    private val _liveSyncTelemetry = MutableStateFlow(LiveSyncTelemetry())
    val liveSyncTelemetry: StateFlow<LiveSyncTelemetry> = _liveSyncTelemetry.asStateFlow()

    /**
     * Resets the associator state for a new recording session.
     */
    fun reset() {
        imuWriteCursor.set(0L)
        radWriteCursor.set(0L)
        keyframeCounter.set(0L)
        cameraDropCounter.set(0L)
        imuJitterCounter.set(0L)
        radarDropCounter.set(0L)
        lastCamShutterNs = 0L
        rollingCamImuDeltaMs = 2.8f
        rollingRadarCamDeltaMs = 8.4f
        peakCamImuDeltaMs = 0f
        peakRadarCamDeltaMs = 0f
        latestGnssFix.set(null)
        _liveSyncTelemetry.value = LiveSyncTelemetry()
    }

    /**
     * Ingests a high-rate IMU frame (100–104 Hz) in O(1) time without heap allocation.
     */
    fun pushImu(frame: ImuFrame, sequenceId: Long) {
        val cursor = imuWriteCursor.getAndIncrement()
        val idx = (cursor and IMU_MASK.toLong()).toInt()

        imuMonoNs[idx] = frame.elapsedRealtimeNs
        imuWallMs[idx] = frame.wallTimeMs
        imuDeliveryNs[idx] = frame.deliveryNs
        imuSeqIds[idx] = sequenceId
        imuAx[idx] = frame.ax
        imuAy[idx] = frame.ay
        imuAz[idx] = frame.az
        imuLinAx[idx] = frame.linAx
        imuLinAy[idx] = frame.linAy
        imuLinAz[idx] = frame.linAz
        imuGx[idx] = frame.gx
        imuGy[idx] = frame.gy
        imuGz[idx] = frame.gz
        imuQw[idx] = frame.qw
        imuQx[idx] = frame.qx
        imuQy[idx] = frame.qy
        imuQz[idx] = frame.qz

        val (pitch, roll, yaw) = SyncKeyframe.quaternionToEulerDeg(frame.qw, frame.qx, frame.qy, frame.qz)
        imuPitchDeg[idx] = pitch
        imuRollDeg[idx] = roll
        imuYawDeg[idx] = yaw

        lastImuHeartbeatNs.set(frame.elapsedRealtimeNs)
    }

    /**
     * Primitive overload for zero-allocation push directly from sensor callbacks.
     */
    fun pushImu(
        elapsedRealtimeNs: Long,
        wallTimeMs: Long,
        deliveryNs: Long,
        sequenceId: Long,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        linAx: Float, linAy: Float, linAz: Float
    ) {
        val cursor = imuWriteCursor.getAndIncrement()
        val idx = (cursor and IMU_MASK.toLong()).toInt()

        imuMonoNs[idx] = elapsedRealtimeNs
        imuWallMs[idx] = wallTimeMs
        imuDeliveryNs[idx] = deliveryNs
        imuSeqIds[idx] = sequenceId
        imuAx[idx] = ax
        imuAy[idx] = ay
        imuAz[idx] = az
        imuLinAx[idx] = linAx
        imuLinAy[idx] = linAy
        imuLinAz[idx] = linAz
        imuGx[idx] = gx
        imuGy[idx] = gy
        imuGz[idx] = gz
        imuQw[idx] = qw
        imuQx[idx] = qx
        imuQy[idx] = qy
        imuQz[idx] = qz

        val (pitch, roll, yaw) = SyncKeyframe.quaternionToEulerDeg(qw, qx, qy, qz)
        imuPitchDeg[idx] = pitch
        imuRollDeg[idx] = roll
        imuYawDeg[idx] = yaw

        lastImuHeartbeatNs.set(elapsedRealtimeNs)
    }

    /**
     * Ingests a parsed Radar packet header (20 Hz) in O(1) time without heap allocation.
     */
    fun pushRadar(hostMonoNs: Long, header: RadarHeader) {
        val cursor = radWriteCursor.getAndIncrement()
        val idx = (cursor and RADAR_MASK.toLong()).toInt()

        radHostMonoNs[idx] = hostMonoNs
        radFrameNum[idx] = header.frameNumber
        radNumObj[idx] = header.numDetectedObj
        radNumTlvs[idx] = header.numTLVs
        radSubFrame[idx] = header.subFrameNumber

        lastRadarHeartbeatNs.set(hostMonoNs)
    }

    /**
     * Updates the latest GNSS location fix.
     */
    fun updateGnss(fix: GnssFix) {
        latestGnssFix.set(fix)
    }

    /**
     * Associates the incoming camera frame shutter exposure with the nearest IMU sample,
     * nearest Radar frame, and latest GNSS fix, returning an ultra-rich [SyncKeyframe].
     */
    fun associateKeyframe(cameraFrame: CameraFrameMetadata): SyncKeyframe {
        val camNs = cameraFrame.shutterTimestampNs
        val nowMono = SystemClock.elapsedRealtimeNanos()
        lastCameraHeartbeatNs.set(camNs)

        val keyId = keyframeCounter.incrementAndGet()

        // 1. Detect camera frame drops (Interval > 50ms @ 30 FPS)
        if (lastCamShutterNs > 0L) {
            val camIntervalMs = (camNs - lastCamShutterNs) / 1_000_000f
            if (camIntervalMs > 50f) {
                cameraDropCounter.incrementAndGet()
            }
        }
        lastCamShutterNs = camNs

        // 2. Sensor Presence Checks
        val isImuActive = (nowMono - lastImuHeartbeatNs.get()) <= SENSOR_TIMEOUT_NS && imuWriteCursor.get() > 0L
        val isRadarActive = (nowMono - lastRadarHeartbeatNs.get()) <= SENSOR_TIMEOUT_NS && radWriteCursor.get() > 0L

        // 3. Find Nearest IMU Slot in Ring Buffer
        val imuCursor = imuWriteCursor.get()
        var bestImuIdx = 0
        var bestImuDeltaNs = Long.MAX_VALUE
        if (imuCursor > 0L) {
            val searchCount = min(imuCursor, IMU_CAPACITY.toLong()).toInt()
            for (i in 0 until searchCount) {
                val testCursor = imuCursor - 1L - i
                val slot = (testCursor and IMU_MASK.toLong()).toInt()
                val delta = abs(imuMonoNs[slot] - camNs)
                if (delta < bestImuDeltaNs) {
                    bestImuDeltaNs = delta
                    bestImuIdx = slot
                } else if (delta > bestImuDeltaNs && i > 15) {
                    // Passed the local minimum in the monotonic timeline
                    break
                }
            }
        }

        val imuMono = if (imuCursor > 0L) imuMonoNs[bestImuIdx] else 0L
        val signedCamImuDeltaNs = if (imuCursor > 0L) (imuMono - camNs) else 0L
        val camImuDeltaMs = signedCamImuDeltaNs / 1_000_000f

        // 4. Find Nearest Radar Slot in Ring Buffer
        val radCursor = radWriteCursor.get()
        var bestRadIdx = 0
        var bestRadDeltaNs = Long.MAX_VALUE
        if (isRadarActive && radCursor > 0L) {
            val searchCount = min(radCursor, RADAR_CAPACITY.toLong()).toInt()
            for (i in 0 until searchCount) {
                val testCursor = radCursor - 1L - i
                val slot = (testCursor and RADAR_MASK.toLong()).toInt()
                val delta = abs(radHostMonoNs[slot] - camNs)
                if (delta < bestRadDeltaNs) {
                    bestRadDeltaNs = delta
                    bestRadIdx = slot
                } else if (delta > bestRadDeltaNs && i > 8) {
                    break
                }
            }
        }

        val radMono = if (isRadarActive && radCursor > 0L) radHostMonoNs[bestRadIdx] else -1L
        val signedCamRadDeltaNs = if (isRadarActive && radCursor > 0L) (radMono - camNs) else -1L
        val camRadDeltaMs = if (signedCamRadDeltaNs != -1L) (signedCamRadDeltaNs / 1_000_000f) else 0.0f

        // 5. Evaluate Synchronization Grade
        val absCamImuMs = abs(camImuDeltaMs)
        val absCamRadMs = abs(camRadDeltaMs)
        val grade = when {
            !isImuActive -> "WARN (IMU Inactive)"
            !isRadarActive -> {
                if (absCamImuMs <= 4.0f) "GOLD" else if (absCamImuMs <= 7.5f) "SILVER" else "WARN"
            }
            else -> {
                if (absCamImuMs <= 4.0f && absCamRadMs <= 16.67f) "GOLD"
                else if (absCamImuMs <= 7.5f && absCamRadMs <= 33.33f) "SILVER"
                else "WARN"
            }
        }

        // 6. Update Rolling Telemetry
        rollingCamImuDeltaMs = (0.85f * rollingCamImuDeltaMs) + (0.15f * absCamImuMs)
        if (absCamImuMs > peakCamImuDeltaMs) peakCamImuDeltaMs = absCamImuMs

        if (isRadarActive) {
            rollingRadarCamDeltaMs = (0.85f * rollingRadarCamDeltaMs) + (0.15f * absCamRadMs)
            if (absCamRadMs > peakRadarCamDeltaMs) peakRadarCamDeltaMs = absCamRadMs
        }

        if (keyId % UI_EMIT_FRAME_INTERVAL == 0L) {
            _liveSyncTelemetry.value = LiveSyncTelemetry(
                isCameraActive = true,
                isImuActive = isImuActive,
                isRadarActive = isRadarActive,
                camToImuDeltaMs = rollingCamImuDeltaMs,
                camToImuMaxMs = peakCamImuDeltaMs,
                radarToCamDeltaMs = if (isRadarActive) rollingRadarCamDeltaMs else 0.0f,
                radarToCamMaxMs = peakRadarCamDeltaMs,
                liveGrade = grade,
                totalKeyframes = keyId,
                cameraDropCount = cameraDropCounter.get(),
                imuJitterCount = imuJitterCounter.get(),
                radarDropCount = radarDropCounter.get(),
                lastUpdateMonoNs = nowMono
            )
        }

        val gnss = latestGnssFix.get()

        return SyncKeyframe(
            keyframeId = keyId,
            wallTimeMs = cameraFrame.utcTimestampMs,
            syncGrade = grade,
            cameraFrameNumber = cameraFrame.frameNumber,
            cameraShutterMonoNs = cameraFrame.shutterTimestampNs,
            cameraExposureTimeNs = cameraFrame.exposureTimeNs,
            cameraIso = cameraFrame.iso,
            imuSequenceId = if (imuCursor > 0L) imuSeqIds[bestImuIdx] else -1L,
            imuMonoNs = imuMono,
            imuDeliveryNs = if (imuCursor > 0L) imuDeliveryNs[bestImuIdx] else 0L,
            deltaCamImuNs = signedCamImuDeltaNs,
            deltaCamImuMs = camImuDeltaMs,
            accelX = if (imuCursor > 0L) imuAx[bestImuIdx] else 0f,
            accelY = if (imuCursor > 0L) imuAy[bestImuIdx] else 0f,
            accelZ = if (imuCursor > 0L) imuAz[bestImuIdx] else 0f,
            linAccelX = if (imuCursor > 0L) imuLinAx[bestImuIdx] else 0f,
            linAccelY = if (imuCursor > 0L) imuLinAy[bestImuIdx] else 0f,
            linAccelZ = if (imuCursor > 0L) imuLinAz[bestImuIdx] else 0f,
            gyroX = if (imuCursor > 0L) imuGx[bestImuIdx] else 0f,
            gyroY = if (imuCursor > 0L) imuGy[bestImuIdx] else 0f,
            gyroZ = if (imuCursor > 0L) imuGz[bestImuIdx] else 0f,
            quatW = if (imuCursor > 0L) imuQw[bestImuIdx] else 1f,
            quatX = if (imuCursor > 0L) imuQx[bestImuIdx] else 0f,
            quatY = if (imuCursor > 0L) imuQy[bestImuIdx] else 0f,
            quatZ = if (imuCursor > 0L) imuQz[bestImuIdx] else 0f,
            pitchDeg = if (imuCursor > 0L) imuPitchDeg[bestImuIdx] else 0f,
            rollDeg = if (imuCursor > 0L) imuRollDeg[bestImuIdx] else 0f,
            yawDeg = if (imuCursor > 0L) imuYawDeg[bestImuIdx] else 0f,
            isRadarActive = isRadarActive,
            radarFrameNumber = if (isRadarActive && radCursor > 0L) radFrameNum[bestRadIdx] else -1L,
            radarHostMonoNs = radMono,
            deltaCamRadarNs = signedCamRadDeltaNs,
            deltaCamRadarMs = camRadDeltaMs,
            radarNumDetectedObj = if (isRadarActive && radCursor > 0L) radNumObj[bestRadIdx] else 0,
            radarNumTLVs = if (isRadarActive && radCursor > 0L) radNumTlvs[bestRadIdx] else 0,
            radarSubFrameNumber = if (isRadarActive && radCursor > 0L) radSubFrame[bestRadIdx] else 0,
            hasGnssFix = gnss != null,
            gnssUtcTimeMs = gnss?.utcTimeMs ?: 0L,
            gnssLatitude = gnss?.latitude ?: 0.0,
            gnssLongitude = gnss?.longitude ?: 0.0,
            gnssAltitudeMeters = gnss?.altitudeMeters ?: 0.0,
            gnssSpeedMps = gnss?.speedMps ?: 0.0f,
            gnssBearingDegrees = gnss?.bearingDegrees ?: 0.0f,
            gnssAccuracyMeters = gnss?.accuracyMeters ?: 0.0f
        )
    }
}
