package com.bajajauto.roadsense.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bajajauto.roadsense.acquisition.RadarConnectionManager
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val connectionManager = RadarConnectionManager(application)

    val connectionState: StateFlow<RadarConnectionState> = connectionManager.connectionState

    private val _rawHexData = MutableStateFlow("No data received yet. Connect to radar and start stream.")
    val rawHexData: StateFlow<String> = _rawHexData.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val hexBuilder = StringBuilder()
    private val MAX_HEX_CHARS = 10000 // Limit UI buffer size

    init {
        viewModelScope.launch {
            connectionManager.dataBytes.collect { bytes ->
                if (bytes.isNotEmpty()) {
                    _totalBytes.value += bytes.size
                    val hexString = bytes.joinToString(" ") { "%02X".format(it) }
                    hexBuilder.append(hexString).append(" ")
                    if (hexBuilder.length > MAX_HEX_CHARS) {
                        hexBuilder.delete(0, hexBuilder.length - MAX_HEX_CHARS)
                    }
                    _rawHexData.value = hexBuilder.toString()
                }
            }
        }
    }

    fun scanAndConnect() {
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
        connectionManager.release()
    }
}
