package com.bajajauto.roadsense.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.bajajauto.roadsense.acquisition.RadarConnectionManager
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.camera.CameraEngine
import com.bajajauto.roadsense.camera.CameraFrameRate
import com.bajajauto.roadsense.camera.CameraResolution
import com.bajajauto.roadsense.decoding.RadarPacketAssembler
import com.bajajauto.roadsense.decoding.RadarTlvDecoder
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.gnss.GnssLocationManager
import com.bajajauto.roadsense.gnss.GnssState
import com.bajajauto.roadsense.recording.CameraSessionRecorder
import com.bajajauto.roadsense.recording.GnssSessionRecorder
import com.bajajauto.roadsense.recording.RadarSessionRecorder
import com.bajajauto.roadsense.recording.RawUartRecorder
import com.bajajauto.roadsense.recording.RecordingState
import com.bajajauto.roadsense.recording.SessionInfo
import com.bajajauto.roadsense.recording.SessionManager
import com.bajajauto.roadsense.recording.SessionRecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)
    private val connectionManager = RadarConnectionManager(application)
    private val packetAssembler = RadarPacketAssembler()
    private val tlvDecoder = RadarTlvDecoder()
    private val rawRecorder = RawUartRecorder(application)
    private val sessionRecorder = RadarSessionRecorder(application, sessionManager)
    private val gnssLocationManager = GnssLocationManager(application)
    private val gnssSessionRecorder = GnssSessionRecorder(sessionManager)
    val cameraEngine = CameraEngine(application)
    private val cameraSessionRecorder = CameraSessionRecorder(sessionManager)

    val connectionState: StateFlow<RadarConnectionState> = connectionManager.connectionState
    val recordingState: StateFlow<RecordingState> = rawRecorder.recordingState
    val sessionRecordingState: StateFlow<SessionRecordingState> = sessionRecorder.recordingState

    val gnssState: StateFlow<GnssState> = gnssLocationManager.gnssState
    val latestGnssFix: StateFlow<GnssFix?> = gnssLocationManager.latestFix
    val totalGnssFixes: StateFlow<Long> = gnssLocationManager.totalFixes
    val isGnssRecording: StateFlow<Boolean> = gnssSessionRecorder.isRecording
    val gnssSessionFixes: StateFlow<Long> = gnssSessionRecorder.fixesRecorded

    val cameraEngineState = cameraEngine.engineState
    val selectedResolution = cameraEngine.selectedResolution
    val selectedFps = cameraEngine.selectedFps
    val cameraSessionFrames: StateFlow<Long> = cameraSessionRecorder.framesRecorded

    private val _rawHexData = MutableStateFlow("No data received yet. Connect to radar and start stream.")
    val rawHexData: StateFlow<String> = _rawHexData.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val _totalPackets = MutableStateFlow(0L)
    val totalPackets: StateFlow<Long> = _totalPackets.asStateFlow()

    private val _latestPacket = MutableStateFlow<RawRadarPacket?>(null)
    val latestPacket: StateFlow<RawRadarPacket?> = _latestPacket.asStateFlow()

    private val _latestFrame = MutableStateFlow<RadarFrame?>(null)
    val latestFrame: StateFlow<RadarFrame?> = _latestFrame.asStateFlow()

    private val _isHexPreviewEnabled = MutableStateFlow(false)
    val isHexPreviewEnabled: StateFlow<Boolean> = _isHexPreviewEnabled.asStateFlow()

    private val hexBuilder = StringBuilder()
    private val MAX_HEX_CHARS = 4000

    init {
        // Direct zero-loss callback on IO thread: feeds raw recorder and packet assembler
        connectionManager.addDataListener { bytes ->
            val hostMonoNs = SystemClock.elapsedRealtimeNanos()
            val hostWallMs = System.currentTimeMillis()

            rawRecorder.write(bytes)
            sessionRecorder.writeRawBytes(bytes)

            val assembledPackets = packetAssembler.appendBytes(bytes)
            if (assembledPackets.isNotEmpty()) {
                _totalPackets.value += assembledPackets.size
                val lastPacket = assembledPackets.last()
                _latestPacket.value = lastPacket

                for (packet in assembledPackets) {
                    sessionRecorder.writeFrame(packet, hostMonoNs, hostWallMs)
                }

                // Decode real-time TLVs into structured RadarFrame
                val decoded = tlvDecoder.decode(lastPacket)
                _latestFrame.value = decoded
            }
        }

        // Throttled UI hex preview: sampled at ~4 Hz only when enabled by user to prevent CPU/GC churn
        viewModelScope.launch(Dispatchers.Default) {
            var lastPreviewMs = 0L
            connectionManager.dataBytes.collect { bytes ->
                if (bytes.isNotEmpty()) {
                    _totalBytes.value += bytes.size

                    if (_isHexPreviewEnabled.value) {
                        val now = System.currentTimeMillis()
                        if (now - lastPreviewMs > 250) {
                            lastPreviewMs = now
                            val preview = bytes.take(24).joinToString(" ") { "%02X".format(it) }
                            val line = if (bytes.size > 24) "$preview ... (${bytes.size} B)\n" else "$preview (${bytes.size} B)\n"
                            hexBuilder.append(line)
                            if (hexBuilder.length > MAX_HEX_CHARS) {
                                hexBuilder.delete(0, hexBuilder.length - MAX_HEX_CHARS)
                            }
                            _rawHexData.value = hexBuilder.toString()
                        }
                    }
                }
            }
        }

        // GNSS listener: automatically records fixes if session recording is active
        gnssLocationManager.addFixListener { fix ->
            gnssSessionRecorder.recordFix(fix)
        }

        // Camera frame shutter listener: automatically records frame metadata if session recording is active
        cameraEngine.addFrameListener { frame ->
            cameraSessionRecorder.recordFrame(frame)
        }

        // Auto-warm up GNSS on launch if permission is available
        if (hasLocationPermission()) {
            startGnssUpdates()
        }
    }

    fun setHexPreviewEnabled(enabled: Boolean) {
        _isHexPreviewEnabled.value = enabled
        if (!enabled) {
            hexBuilder.clear()
            _rawHexData.value = "Hex preview paused to conserve CPU."
        }
    }

    fun hasLocationPermission(): Boolean {
        return gnssLocationManager.hasLocationPermission()
    }

    fun startGnssUpdates(): Boolean {
        return gnssLocationManager.startLocationUpdates()
    }

    fun stopGnssUpdates() {
        gnssLocationManager.stopLocationUpdates()
    }

    val availableCameras: StateFlow<List<com.bajajauto.roadsense.camera.CameraDeviceInfo>> = cameraEngine.availableCameras
    val selectedCamera: StateFlow<com.bajajauto.roadsense.camera.CameraDeviceInfo?> = cameraEngine.selectedCamera

    fun selectCamera(deviceInfo: com.bajajauto.roadsense.camera.CameraDeviceInfo) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User selected camera lens: ${deviceInfo.displayName} (id=${deviceInfo.id})")
        cameraEngine.selectCamera(deviceInfo)
    }

    fun updateCameraDisplayRotation(rotation: Int, textureView: android.view.TextureView? = null, width: Int = 0, height: Int = 0) {
        cameraEngine.updateDisplayRotation(rotation, textureView, width, height)
    }

    fun hasCameraPermission(): Boolean {
        return cameraEngine.hasCameraPermission()
    }

    fun setCameraResolution(resolution: CameraResolution) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User selected resolution: ${resolution.label}")
        cameraEngine.setResolution(resolution)
    }

    fun setCameraFrameRate(fps: CameraFrameRate) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User selected frame rate: ${fps.label}")
        cameraEngine.setFrameRate(fps)
    }

    fun startSessionRecording(): SessionInfo? {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User tapped START session recording")
        if (hasLocationPermission()) {
            startGnssUpdates()
        }
        val session = sessionRecorder.startSession()
        if (session != null) {
            gnssSessionRecorder.startRecording(session)
            val videoFile = cameraSessionRecorder.startRecording(session, cameraEngine.selectedResolution.value)
            if (videoFile != null) {
                cameraEngine.startVideoRecording(videoFile)
            }
        }
        return session
    }

    fun stopSessionRecording(): SessionInfo? {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User tapped STOP session recording")
        cameraEngine.stopVideoRecording()
        cameraSessionRecorder.stopRecording()
        gnssSessionRecorder.stopRecording()
        return sessionRecorder.stopSession()
    }

    fun startRawRecording(): File? {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User started raw radar recording")
        return rawRecorder.startRecording()
    }

    fun stopRawRecording(): File? {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User stopped raw radar recording")
        return rawRecorder.stopRecording()
    }

    fun scanAndConnect() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User tapped Scan & Connect USB radar")
        packetAssembler.reset()
        connectionManager.scanAndConnect()
    }

    fun disconnect() {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User tapped Disconnect radar")
        connectionManager.disconnect()
    }

    fun sendConfig(command: String) {
        com.bajajauto.roadsense.logging.AppLogger.i("UI", "User sent CLI config command: $command")
        connectionManager.sendConfigCommand(command)
    }

    override fun onCleared() {
        super.onCleared()
        cameraEngine.release()
        cameraSessionRecorder.release()
        gnssLocationManager.release()
        gnssSessionRecorder.release()
        sessionRecorder.release()
        rawRecorder.release()
        connectionManager.release()
    }
}
