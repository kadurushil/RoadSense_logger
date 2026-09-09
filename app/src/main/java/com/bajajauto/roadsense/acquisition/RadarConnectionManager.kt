package com.bajajauto.roadsense.acquisition

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

sealed class RadarConnectionState {
    object Disconnected : RadarConnectionState()
    object Connecting : RadarConnectionState()
    data class Connected(val portInfo: String) : RadarConnectionState()
    data class Error(val message: String) : RadarConnectionState()
}

class RadarConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "RadarConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.bajajauto.roadsense.USB_PERMISSION"
        const val CONFIG_BAUD_RATE = 115200
        const val DATA_BAUD_RATE = 3125000
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()

    private val _connectionState = MutableStateFlow<RadarConnectionState>(RadarConnectionState.Disconnected)
    val connectionState: StateFlow<RadarConnectionState> = _connectionState.asStateFlow()

    private val _dataBytes = MutableStateFlow(byteArrayOf())
    val dataBytes: StateFlow<ByteArray> = _dataBytes.asStateFlow()

    private var configPort: UsbSerialPort? = null
    private var dataPort: UsbSerialPort? = null

    private var configIoManager: SerialInputOutputManager? = null
    private var dataIoManager: SerialInputOutputManager? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            connectToDevice(it)
                        }
                    } else {
                        Log.d(TAG, "permission denied for device $device")
                        _connectionState.value = RadarConnectionState.Error("USB Permission Denied")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbPermissionReceiver, filter)
        }
    }

    fun scanAndConnect() {
        _connectionState.value = RadarConnectionState.Connecting
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            _connectionState.value = RadarConnectionState.Error("No USB Serial drivers found")
            return
        }

        // Typically AWR1843 BOOST has multiple ports (Config and Data) or multiple drivers
        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            connectToDevice(device)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null || driver.ports.isEmpty()) {
                _connectionState.value = RadarConnectionState.Error("Invalid USB serial driver or ports")
                return
            }

            val ports = driver.ports
            Log.d(TAG, "Found ${ports.size} serial port(s) on device ${device.deviceName}")

            // If dual ports (e.g. CP2105), port 0 is usually Config (115200) and port 1 is Data (3125000)
            if (ports.size >= 2) {
                configPort = ports[0]
                dataPort = ports[1]
            } else {
                configPort = ports[0]
                dataPort = ports[0] // Fallback or single port usage
            }

            // Open and configure Config Port
            configPort?.let { port ->
                val connection = usbManager.openDevice(driver.device)
                if (connection == null) {
                    _connectionState.value = RadarConnectionState.Error("Failed to open USB device for Config port")
                    return
                }
                port.open(connection)
                port.setParameters(CONFIG_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }

            // Open and configure Data Port (if distinct)
            if (dataPort != configPort) {
                dataPort?.let { port ->
                    val connection = usbManager.openDevice(driver.device)
                    if (connection == null) {
                        _connectionState.value = RadarConnectionState.Error("Failed to open USB device for Data port")
                        return
                    }
                    port.open(connection)
                    port.setParameters(DATA_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                }
            }

            // Setup IO Manager for Data Port
            dataPort?.let { port ->
                dataIoManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        _dataBytes.value = data
                    }

                    override fun onRunError(e: Exception) {
                        Log.e(TAG, "Runner error", e)
                        _connectionState.value = RadarConnectionState.Error(e.message ?: "Unknown IO Error")
                    }
                }).apply {
                    start()
                }
            }

            _connectionState.value = RadarConnectionState.Connected(
                "Connected: ${ports.size} port(s) [Config: $CONFIG_BAUD_RATE, Data: $DATA_BAUD_RATE]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            _connectionState.value = RadarConnectionState.Error(e.message ?: "Connection Failed")
        }
    }

    fun sendConfigCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fullCommand = if (command.endsWith("\n")) command else "$command\n"
                configPort?.write(fullCommand.toByteArray(Charsets.US_ASCII), 1000)
                Log.d(TAG, "Sent config: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send config command", e)
            }
        }
    }

    fun disconnect() {
        try {
            dataIoManager?.stop()
            configIoManager?.stop()
            dataPort?.close()
            configPort?.close()
            _connectionState.value = RadarConnectionState.Disconnected
        } catch (e: IOException) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        disconnect()
        executor.shutdown()
    }
}
