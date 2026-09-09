package com.bajajauto.roadsense.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bajajauto.roadsense.acquisition.RadarConnectionManager
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.decoding.RadarPacketAssembler
import com.bajajauto.roadsense.decoding.RadarTlvDecoder
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.models.RawRadarPacket
import com.bajajauto.roadsense.recording.RawUartRecorder
import com.bajajauto.roadsense.recording.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val connectionManager = RadarConnectionManager(application)
    private val packetAssembler = RadarPacketAssembler()
    private val tlvDecoder = RadarTlvDecoder()
    private val rawRecorder = RawUartRecorder(application)

    val connectionState: StateFlow<RadarConnectionState> = connectionManager.connectionState
    val recordingState: StateFlow<RecordingState> = rawRecorder.recordingState

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

    private val hexBuilder = StringBuilder()
    private val MAX_HEX_CHARS = 4000

    init {
        // Direct zero-loss callback on IO thread: feeds raw recorder and packet assembler
        connectionManager.addDataListener { bytes ->
            rawRecorder.write(bytes)

            val assembledPackets = packetAssembler.appendBytes(bytes)
            if (assembledPackets.isNotEmpty()) {
                _totalPackets.value += assembledPackets.size
                val lastPacket = assembledPackets.last()
                _latestPacket.value = lastPacket

                // Decode real-time TLVs into structured RadarFrame
                val decoded = tlvDecoder.decode(lastPacket)
                _latestFrame.value = decoded
            }
        }

        // Throttled UI hex preview: sampled at ~4 Hz to prevent UI thread lockups
        viewModelScope.launch(Dispatchers.Default) {
            var lastPreviewMs = 0L
            connectionManager.dataBytes.collect { bytes ->
                if (bytes.isNotEmpty()) {
                    _totalBytes.value += bytes.size

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

    fun startRawRecording(): File? {
        return rawRecorder.startRecording()
    }

    fun stopRawRecording(): File? {
        return rawRecorder.stopRecording()
    }

    fun scanAndConnect() {
        packetAssembler.reset()
        connectionManager.scanAndConnect()
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun sendConfig(command: String) {
        connectionManager.sendConfigCommand(command)
    }

    override fun onCleared() {
        super.onCleared()
        rawRecorder.release()
        connectionManager.release()
    }
}
