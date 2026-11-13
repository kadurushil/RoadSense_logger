package com.bajajauto.roadsense.acquisition

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Process
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

fun interface RawDataListener {
    fun onRawData(data: ByteArray)
}

class RadarConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "RadarConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.bajajauto.roadsense.USB_PERMISSION"
        const val CONFIG_BAUD_RATE = 115200
        const val DATA_BAUD_RATE = 3125000
        const val DATA_BAUD_RATE_2M = 2000000
    }

    @Volatile
    var currentDataBaudRate: Int = DATA_BAUD_RATE
        private set

    fun setDataBaudRate(baudRate: Int) {
        if (baudRate <= 0 || baudRate == currentDataBaudRate) return
        currentDataBaudRate = baudRate
        dataPort?.let { port ->
            if (port.isOpen) {
                try {
                    port.setParameters(baudRate, 8, com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
                    com.bajajauto.roadsense.logging.AppLogger.i(TAG, "Dynamically updated data port baud rate to $baudRate bps")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update baud rate to $baudRate: ${e.message}", e)
                }
            }
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()
    private val dataListeners = java.util.concurrent.CopyOnWriteArrayList<RawDataListener>()

    fun addDataListener(listener: RawDataListener) {
        dataListeners.add(listener)
    }

    fun removeDataListener(listener: RawDataListener) {
        dataListeners.remove(listener)
    }

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
            // Ensure any previous IO manager/ports are cleanly stopped before re-opening
            dataIoManager?.stop()
            dataIoManager = null
            configIoManager?.stop()
            configIoManager = null

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
                    port.setParameters(currentDataBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    try {
                        port.dtr = true
                        port.rts = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set DTR/RTS on data port: ${e.message}")
                    }
                }
            }

            // Purge stale pre-connect RX/TX bytes from CP2105 silicon FIFOs before starting async reads
            dataPort?.let { port ->
                purgeStaleHwBuffers(port, "Data")
                startDataIoManager(port)
            }

            _connectionState.value = RadarConnectionState.Connected(
                "Connected: ${ports.size} port(s) [Config: $CONFIG_BAUD_RATE, Data: $currentDataBaudRate]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            _connectionState.value = RadarConnectionState.Error(e.message ?: "Connection Failed")
        }
    }

    private fun startDataIoManager(port: UsbSerialPort, retryWithBulkTransfer: Boolean = false) {
        var threadPrioritySet = false

        dataIoManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (!threadPrioritySet) {
                    try {
                        Thread.currentThread().name = "RS-SerialIO"
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                        Thread.currentThread().priority = Thread.MAX_PRIORITY
                        threadPrioritySet = true
                        Log.i(TAG, "Boosted RS-SerialIO thread to THREAD_PRIORITY_URGENT_AUDIO (-19 / Linux Pri 101)")
                        com.bajajauto.roadsense.logging.AppLogger.i(
                            TAG,
                            "RS-SerialIO thread online: ThreadPriority=THREAD_PRIORITY_URGENT_AUDIO (-19 / Linux Pri 101), ThreadName=RS-SerialIO"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set RS-SerialIO thread priority: ${e.message}")
                    }
                }
                if (data.isEmpty()) return

                android.os.Trace.beginSection("RS_Serial_onNewData")
                try {
                    // Direct dispatch to listeners on IO thread (zero dropped bytes)
                    for (listener in dataListeners) {
                        try {
                            listener.onRawData(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in data listener", e)
                        }
                    }

                    // Conflated flow for UI indicator/preview only
                    _dataBytes.value = data
                } finally {
                    android.os.Trace.endSection()
                }
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "SerialInputOutputManager error: ${e.message}", e)
                com.bajajauto.roadsense.logging.AppLogger.w(TAG, "SerialInputOutputManager error: ${e.message}")
                if (port.isOpen && _connectionState.value is RadarConnectionState.Connected) {
                    // Attempt clean auto-recovery rather than permanently failing connection
                    com.bajajauto.roadsense.logging.AppLogger.i(TAG, "Attempting auto-recovery for data serial IO manager...")
                    executor.execute {
                        try {
                            Thread.sleep(100)
                            if (port.isOpen) {
                                startDataIoManager(port, retryWithBulkTransfer = true)
                            }
                        } catch (recEx: Exception) {
                            Log.e(TAG, "Auto-recovery failed", recEx)
                            _connectionState.value = RadarConnectionState.Error(e.message ?: "Unknown IO Error")
                        }
                    }
                } else {
                    _connectionState.value = RadarConnectionState.Error(e.message ?: "Unknown IO Error")
                }
            }
        }).apply {
            readBufferSize = 8192
            try {
                setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.i(TAG, "Configured pre-launch threadPriority = THREAD_PRIORITY_URGENT_AUDIO")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set pre-launch threadPriority on IO manager: ${e.message}")
            }
            if (!retryWithBulkTransfer) {
                try {
                    // Allocate 32 asynchronous UsbRequests in the Linux kernel DMA ring (256 KB pool)
                    setReadQueue(32)
                    val activeQueue = readQueueBufferCount
                    val activeSize = readBufferSize
                    val poolKb = (activeQueue * activeSize) / 1024
                    val headroomMs = (activeQueue * activeSize) / 312.5f
                    Log.i(TAG, "VERIFIED_KERNEL_DMA: readQueue=$activeQueue buffers ($activeSize B each). Total DMA pool = ${poolKb} KB (~${String.format(java.util.Locale.US, "%.1f", headroomMs)}ms absorption headroom)")
                    com.bajajauto.roadsense.logging.AppLogger.i(
                        TAG,
                        "VERIFIED_KERNEL_DMA: readQueue=$activeQueue buffers ($activeSize B each). Total DMA pool = ${poolKb} KB (~${String.format(java.util.Locale.US, "%.1f", headroomMs)}ms absorption headroom) to eliminate CP2105 FIFO overflow"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set readQueue on USB serial port: ${e.message}")
                    com.bajajauto.roadsense.logging.AppLogger.w(
                        TAG,
                        "Could not set readQueue on USB serial port: ${e.message}"
                    )
                }
            } else {
                Log.i(TAG, "Starting SerialInputOutputManager in standard synchronous mode (auto-recovered)")
            }
            start()
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

    private fun purgeStaleHwBuffers(port: UsbSerialPort, label: String) {
        var hwPurged = false
        try {
            port.purgeHwBuffers(true, true)
            hwPurged = true
            com.bajajauto.roadsense.logging.AppLogger.i(
                TAG,
                "[CONNECT_PURGE] HW buffer purge on $label port (tx+rx succeeded)"
            )
        } catch (e: Exception) {
            try {
                port.purgeHwBuffers(false, true)
                hwPurged = true
                com.bajajauto.roadsense.logging.AppLogger.i(
                    TAG,
                    "[CONNECT_PURGE] HW RX-only purge fallback on $label port (rx succeeded)"
                )
            } catch (e2: Exception) {
                com.bajajauto.roadsense.logging.AppLogger.w(
                    TAG,
                    "[CONNECT_PURGE] purgeHwBuffers unsupported on $label port (${e2.message}); running SW RX drain"
                )
            }
        }

        // Software drain for any stale bytes sitting in CP2105/kernel buffers before SerialInputOutputManager starts
        try {
            val scratch = ByteArray(2048)
            var totalDrained = 0
            val deadlineMs = System.currentTimeMillis() + 20L
            while (totalDrained < 8192 && System.currentTimeMillis() < deadlineMs) {
                val n = port.read(scratch, 5)
                if (n <= 0) break
                totalDrained += n
            }
            com.bajajauto.roadsense.logging.AppLogger.i(
                TAG,
                "[CONNECT_PURGE] SW RX drain complete on $label port: drained=${totalDrained} B (hwPurged=$hwPurged)"
            )
        } catch (e: Exception) {
            com.bajajauto.roadsense.logging.AppLogger.w(
                TAG,
                "[CONNECT_PURGE] SW RX drain skipped/ended on $label port: ${e.message}"
            )
        }
    }

    fun disconnect() {
        try {
            dataIoManager?.stop()
            dataIoManager = null
            configIoManager?.stop()
            configIoManager = null
            dataPort?.let {
                try { it.purgeHwBuffers(true, true) } catch (_: Exception) {}
                it.close()
            }
            configPort?.close()
            dataPort = null
            configPort = null
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
