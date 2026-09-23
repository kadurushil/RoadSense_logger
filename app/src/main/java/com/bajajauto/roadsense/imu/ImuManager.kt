package com.bajajauto.roadsense.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Autonomous high-rate IMU & motion sensor acquisition manager.
 *
 * Runs all hardware sensor listener callbacks on a dedicated background [HandlerThread]
 * with [Process.THREAD_PRIORITY_URGENT_DISPLAY] to eliminate any UI stutter or frame drops.
 *
 * Features:
 * 1. Automatic audit and enumeration of all device sensors.
 * 2. High-precision empirical sampling rate benchmarking and timing jitter analysis.
 * 3. Low-overhead multi-sensor live telemetry conflated at 25 Hz for Compose UI.
 */
class ImuManager(private val context: Context) {

    companion object {
        private const val TAG = "ImuManager"
        private const val JITTER_WINDOW_SIZE = 128
        private const val UI_UPDATE_INTERVAL_NS = 40_000_000L // 40 ms = 25 Hz
        private const val STATS_UPDATE_INTERVAL_NS = 200_000_000L // 200 ms = 5 Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Dedicated high-priority background worker thread
    private val handlerThread = HandlerThread("RoadSense-ImuThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
        start()
    }
    private val backgroundHandler = Handler(handlerThread.looper)

    // Discovered capabilities
    private val _capabilities = MutableStateFlow<List<ImuSensorCapability>>(emptyList())
    val capabilities: StateFlow<List<ImuSensorCapability>> = _capabilities.asStateFlow()

    // Benchmark state
    private val _benchmarkStats = MutableStateFlow(ImuSamplingBenchmark())
    val benchmarkStats: StateFlow<ImuSamplingBenchmark> = _benchmarkStats.asStateFlow()

    // Live telemetry state for UI
    private val _telemetryState = MutableStateFlow(ImuTelemetryState())
    val telemetryState: StateFlow<ImuTelemetryState> = _telemetryState.asStateFlow()

    // Overall live IMU frequency for top metrics bar
    private val _imuHz = MutableStateFlow(0f)
    val imuHz: StateFlow<Float> = _imuHz.asStateFlow()

    @Volatile
    private var isBenchmarking = false

    @Volatile
    private var isMonitoring = false

    // Benchmark tracking variables (accessed on ImuThread)
    private var benchmarkSensorType: Int = 0
    private var benchmarkTargetPreset: ImuRatePreset = ImuRatePreset.RATE_100HZ
    private var benchmarkStartMonoNs: Long = 0L
    private var benchmarkTotalSamples: Long = 0L
    private var benchmarkLastTimestampNs: Long = 0L
    private var benchmarkLastStatsEmitNs: Long = 0L

    // Circular ring buffer for inter-arrival times (in ms)
    private val intervalBuffer = FloatArray(JITTER_WINDOW_SIZE)
    private var intervalHead = 0
    private var intervalCount = 0

    // Telemetry tracking variables (accessed on ImuThread)
    private val currentRawAccel = FloatArray(4)
    private val currentLinearAccel = FloatArray(4)
    private val currentGyroRates = FloatArray(3) // deg/s for UI
    private val currentGyroRad = FloatArray(3) // rad/s for MCAP
    private val currentEulerAngles = FloatArray(3)
    private val currentQuat = floatArrayOf(0f, 0f, 0f, 1f) // qx, qy, qz, qw (identity default)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastUiEmitMonoNs: Long = 0L

    // High-rate synchronized frame listener for active session recording
    @Volatile
    private var onFrameListener: ((ImuFrame) -> Unit)? = null

    // Frequency counter for LiveMetricsBar
    private var hzCounterStartMonoNs: Long = 0L
    private var hzCounterEvents: Long = 0L

    private fun getDisplayRotation(): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_90
            } else {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_90
            }
        } catch (e: Exception) {
            Surface.ROTATION_90
        }
    }

    /**
     * Converts a 3x3 rotation matrix (row-major) to a normalized quaternion [qx, qy, qz, qw].
     */
    private fun rotationMatrixToQuaternion(r: FloatArray, outQuat: FloatArray) {
        val trace = r[0] + r[4] + r[8]
        if (trace > 0f) {
            val s = 0.5f / sqrt(trace + 1.0f)
            outQuat[3] = 0.25f / s // qw
            outQuat[0] = (r[7] - r[5]) * s // qx
            outQuat[1] = (r[2] - r[6]) * s // qy
            outQuat[2] = (r[3] - r[1]) * s // qz
        } else {
            if (r[0] > r[4] && r[0] > r[8]) {
                val s = 2.0f * sqrt(1.0f + r[0] - r[4] - r[8])
                outQuat[3] = (r[7] - r[5]) / s
                outQuat[0] = 0.25f * s
                outQuat[1] = (r[1] + r[3]) / s
                outQuat[2] = (r[2] + r[6]) / s
            } else if (r[4] > r[8]) {
                val s = 2.0f * sqrt(1.0f + r[4] - r[0] - r[8])
                outQuat[3] = (r[2] - r[6]) / s
                outQuat[0] = (r[1] + r[3]) / s
                outQuat[1] = 0.25f * s
                outQuat[2] = (r[5] + r[7]) / s
            } else {
                val s = 2.0f * sqrt(1.0f + r[8] - r[0] - r[4])
                outQuat[3] = (r[3] - r[1]) / s
                outQuat[0] = (r[2] + r[6]) / s
                outQuat[1] = (r[5] + r[7]) / s
                outQuat[2] = 0.25f * s
            }
        }
    }

    init {
        auditSensors()
    }

    /**
     * Audits and enumerates all physical and synthetic sensors present on the hardware.
     */
    fun auditSensors() {
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val caps = allSensors.map { ImuSensorCapability.fromSensor(it) }
            .sortedWith(compareBy({ it.category.ordinal }, { it.name }))
        _capabilities.value = caps
        Log.i(TAG, "Discovered ${caps.size} sensors on device")
    }

    // ==========================================
    // 1. Empirical Sampling Rate Benchmark Engine
    // ==========================================

    private val benchmarkListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isBenchmarking) return
            val nowNs = event.timestamp
            benchmarkTotalSamples++

            if (benchmarkLastTimestampNs > 0L) {
                val deltaNs = nowNs - benchmarkLastTimestampNs
                val deltaMs = deltaNs / 1_000_000f

                // Store in circular buffer
                intervalBuffer[intervalHead] = deltaMs
                intervalHead = (intervalHead + 1) % JITTER_WINDOW_SIZE
                if (intervalCount < JITTER_WINDOW_SIZE) intervalCount++
            }
            benchmarkLastTimestampNs = nowNs

            // Emit stats every 200 ms to avoid flooding StateFlow
            val monoRealtimeNs = SystemClock.elapsedRealtimeNanos()
            if (monoRealtimeNs - benchmarkLastStatsEmitNs >= STATS_UPDATE_INTERVAL_NS && intervalCount > 5) {
                benchmarkLastStatsEmitNs = monoRealtimeNs
                val durationMs = (monoRealtimeNs - benchmarkStartMonoNs) / 1_000_000L

                // Compute statistics over buffer
                var sum = 0f
                var min = Float.MAX_VALUE
                var max = Float.MIN_VALUE
                for (i in 0 until intervalCount) {
                    val dt = intervalBuffer[i]
                    sum += dt
                    if (dt < min) min = dt
                    if (dt > max) max = dt
                }
                val mean = sum / intervalCount
                val measuredHz = if (mean > 0f) 1000f / mean else 0f

                var varianceSum = 0f
                for (i in 0 until intervalCount) {
                    val diff = intervalBuffer[i] - mean
                    varianceSum += diff * diff
                }
                val stdDev = sqrt(varianceSum / intervalCount)

                _benchmarkStats.value = ImuSamplingBenchmark(
                    sensorName = event.sensor.name,
                    sensorType = event.sensor.type,
                    preset = benchmarkTargetPreset,
                    measuredHz = measuredHz,
                    totalSamples = benchmarkTotalSamples,
                    durationMs = durationMs,
                    meanIntervalMs = mean,
                    minIntervalMs = min,
                    maxIntervalMs = max,
                    jitterStdDevMs = stdDev,
                    isRunning = true
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Starts an empirical sampling rate benchmark on a specific sensor.
     */
    fun startBenchmark(sensorType: Int, preset: ImuRatePreset): Boolean {
        if (isBenchmarking) stopBenchmark()

        val sensor = sensorManager.getDefaultSensor(sensorType) ?: run {
            Log.w(TAG, "Sensor type $sensorType not available for benchmark")
            return false
        }

        benchmarkSensorType = sensorType
        benchmarkTargetPreset = preset
        benchmarkTotalSamples = 0L
        benchmarkLastTimestampNs = 0L
        intervalHead = 0
        intervalCount = 0
        benchmarkStartMonoNs = SystemClock.elapsedRealtimeNanos()
        benchmarkLastStatsEmitNs = benchmarkStartMonoNs

        _benchmarkStats.value = ImuSamplingBenchmark(
            sensorName = sensor.name,
            sensorType = sensorType,
            preset = preset,
            isRunning = true
        )

        isBenchmarking = true
        val registered = sensorManager.registerListener(
            benchmarkListener,
            sensor,
            preset.delayUs,
            backgroundHandler
        )
        if (!registered) {
            Log.e(TAG, "Failed to register benchmark listener for ${sensor.name}")
            isBenchmarking = false
            _benchmarkStats.value = ImuSamplingBenchmark(isRunning = false)
            return false
        }
        Log.i(TAG, "Started benchmark on ${sensor.name} with preset ${preset.label}")
        return true
    }

    /**
     * Stops the active empirical benchmark.
     */
    fun stopBenchmark() {
        if (!isBenchmarking) return
        isBenchmarking = false
        sensorManager.unregisterListener(benchmarkListener)
        _benchmarkStats.value = _benchmarkStats.value.copy(isRunning = false)
        Log.i(TAG, "Stopped benchmark")
    }

    // ==========================================
    // 2. Live Telemetry & Monitoring Engine
    // ==========================================

    private val liveTelemetryListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isMonitoring) return
            val nowNs = event.timestamp
            hzCounterEvents++

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    currentRawAccel[0] = event.values[0]
                    currentRawAccel[1] = event.values[1]
                    currentRawAccel[2] = event.values[2]
                    currentRawAccel[3] = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])

                    // Emit synchronized 100 Hz ImuFrame for active session recording / MCAP
                    onFrameListener?.let { listener ->
                        val frame = ImuFrame(
                            elapsedRealtimeNs = event.timestamp,
                            wallTimeMs = System.currentTimeMillis(),
                            ax = currentRawAccel[0],
                            ay = currentRawAccel[1],
                            az = currentRawAccel[2],
                            gx = currentGyroRad[0],
                            gy = currentGyroRad[1],
                            gz = currentGyroRad[2],
                            qx = currentQuat[0],
                            qy = currentQuat[1],
                            qz = currentQuat[2],
                            qw = currentQuat[3],
                            linAx = currentLinearAccel[0],
                            linAy = currentLinearAccel[1],
                            linAz = currentLinearAccel[2]
                        )
                        listener.invoke(frame)
                    }
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    currentLinearAccel[0] = event.values[0]
                    currentLinearAccel[1] = event.values[1]
                    currentLinearAccel[2] = event.values[2]
                    currentLinearAccel[3] = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Raw rad/s for MCAP / ROS sensor_msgs/msg/Imu
                    currentGyroRad[0] = event.values[0]
                    currentGyroRad[1] = event.values[1]
                    currentGyroRad[2] = event.values[2]
                    // Convert rad/s to deg/s for UI dashboard gauges
                    currentGyroRates[0] = Math.toDegrees(event.values[0].toDouble()).toFloat()
                    currentGyroRates[1] = Math.toDegrees(event.values[1].toDouble()).toFloat()
                    currentGyroRates[2] = Math.toDegrees(event.values[2].toDouble()).toFloat()
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    try {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        // Remap from phone body coordinates to vehicle windshield frame (Landscape ROTATION_90 standard):
                        // Vehicle Forward (Camera Boresight) = -Z_phone (AXIS_MINUS_Z)
                        // Vehicle Right = -Y_phone (AXIS_MINUS_Y, since +Y points left across dashboard in ROTATION_90)
                        // Vehicle Up = +X_phone (computed as (-Y) x (-Z) = +X)
                        val displayRot = getDisplayRotation()
                        val axisX = when (displayRot) {
                            Surface.ROTATION_90 -> SensorManager.AXIS_MINUS_Y
                            Surface.ROTATION_270 -> SensorManager.AXIS_Y
                            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X
                            else -> SensorManager.AXIS_X
                        }
                        val axisY = SensorManager.AXIS_MINUS_Z
                        val success = SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                        val targetMatrix = if (success) remappedMatrix else rotationMatrix

                        SensorManager.getOrientation(targetMatrix, orientationAngles)
                        currentEulerAngles[0] = Math.toDegrees(orientationAngles[1].toDouble()).toFloat() // Pitch (Elevation of camera boresight)
                        currentEulerAngles[1] = Math.toDegrees(orientationAngles[2].toDouble()).toFloat() // Roll (Bank of dashboard)
                        currentEulerAngles[2] = Math.toDegrees(orientationAngles[0].toDouble()).toFloat() // Yaw (Heading)

                        // Convert remapped matrix to vehicle attitude quaternion for MCAP
                        rotationMatrixToQuaternion(targetMatrix, currentQuat)
                    } catch (e: Exception) {
                        // ignore malformed vector
                    }
                }
            }

            // Rolling Hz calculation every 500 ms
            val monoNs = SystemClock.elapsedRealtimeNanos()
            val hzDurationNs = monoNs - hzCounterStartMonoNs
            if (hzDurationNs >= 500_000_000L) {
                val hz = (hzCounterEvents * 1_000_000_000f) / hzDurationNs
                // Normalize by number of active continuous sensors (typically 4: accel, lin_accel, gyro, game_rot)
                _imuHz.value = hz / 4f
                hzCounterStartMonoNs = monoNs
                hzCounterEvents = 0L
            }

            // Throttle UI emit to ~25 Hz (every 40 ms)
            if (monoNs - lastUiEmitMonoNs >= UI_UPDATE_INTERVAL_NS) {
                lastUiEmitMonoNs = monoNs
                _telemetryState.value = ImuTelemetryState(
                    rawAccel = currentRawAccel.clone(),
                    linearAccel = currentLinearAccel.clone(),
                    gyroRates = currentGyroRates.clone(),
                    pitchDeg = currentEulerAngles[0],
                    rollDeg = currentEulerAngles[1],
                    yawDeg = currentEulerAngles[2],
                    isMonitoring = true,
                    lastUpdateMonoNs = nowNs
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Starts live monitoring of primary vehicle motion sensors (Accel, Linear Accel, Gyro, Game Rotation Vector).
     */
    fun startLiveMonitoring(delayUs: Int = 10000): Boolean {
        if (isMonitoring) return true

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gameRot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        if (accel == null && gyro == null) {
            Log.w(TAG, "Neither Accelerometer nor Gyroscope available for live monitoring")
            return false
        }

        hzCounterStartMonoNs = SystemClock.elapsedRealtimeNanos()
        hzCounterEvents = 0L
        lastUiEmitMonoNs = 0L
        isMonitoring = true

        accel?.let { sensorManager.registerListener(liveTelemetryListener, it, delayUs, backgroundHandler) }
        linearAccel?.let { sensorManager.registerListener(liveTelemetryListener, it, delayUs, backgroundHandler) }
        gyro?.let { sensorManager.registerListener(liveTelemetryListener, it, delayUs, backgroundHandler) }
        gameRot?.let { sensorManager.registerListener(liveTelemetryListener, it, delayUs, backgroundHandler) }

        _telemetryState.value = _telemetryState.value.copy(isMonitoring = true)
        Log.i(TAG, "Started live IMU monitoring at $delayUs µs")
        return true
    }

    /**
     * Stops live monitoring of motion sensors.
     */
    fun stopLiveMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        sensorManager.unregisterListener(liveTelemetryListener)
        _telemetryState.value = _telemetryState.value.copy(isMonitoring = false)
        _imuHz.value = 0f
        Log.i(TAG, "Stopped live IMU monitoring")
    }

    /**
     * Sets or clears the high-rate synchronized [ImuFrame] listener.
     */
    fun setOnFrameListener(listener: ((ImuFrame) -> Unit)?) {
        this.onFrameListener = listener
    }

    /**
     * Connects an active recording session and ensures 100 Hz sensor acquisition is running.
     */
    fun startSessionRecording(recorder: com.bajajauto.roadsense.recording.ImuSessionRecorder) {
        setOnFrameListener { frame ->
            recorder.recordFrame(frame)
        }
        if (!isMonitoring) {
            startLiveMonitoring(delayUs = 10000)
        }
    }

    /**
     * Detaches the session recorder when recording completes.
     */
    fun stopSessionRecording() {
        setOnFrameListener(null)
    }

    /**
     * Cleans up background threads and active listeners.
     */
    fun release() {
        stopBenchmark()
        stopLiveMonitoring()
        setOnFrameListener(null)
        handlerThread.quitSafely()
    }
}
