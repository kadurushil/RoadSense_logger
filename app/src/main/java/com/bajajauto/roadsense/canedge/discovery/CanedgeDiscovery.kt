package com.bajajauto.roadsense.canedge.discovery

import android.content.Context
import com.bajajauto.roadsense.DebugToggles
import com.bajajauto.roadsense.canedge.model.CanedgeConnectionState
import com.bajajauto.roadsense.canedge.model.CanedgeDevice
import com.bajajauto.roadsense.canedge.network.CanedgeHttpClient
import com.bajajauto.roadsense.logging.AppLogger
import com.bajajauto.roadsense.storage.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Autonomous layered discovery engine for CSS Electronics CANedge2:
 * Priority 1: Fast-probe last known working IP from AppPreferences
 * Priority 2: Probe device hostname (e.g. "http://7AC5E17F/")
 * Priority 3: Asynchronous port-80 subnet sweep on the active local Wi-Fi interface
 */
class CanedgeDiscovery(
    private val context: Context,
    private val httpClient: CanedgeHttpClient,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "CanedgeDiscovery"
        private const val PORT = 80
        private const val SOCKET_PROBE_TIMEOUT_MS = 800
        private const val PARALLEL_PROBE_CHUNK = 24
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var scanJob: Job? = null

    private val _connectionState = MutableStateFlow<CanedgeConnectionState>(CanedgeConnectionState.Disconnected)
    val connectionState: StateFlow<CanedgeConnectionState> = _connectionState.asStateFlow()

    val currentDevice: CanedgeDevice?
        get() = (_connectionState.value as? CanedgeConnectionState.Connected)?.device

    /**
     * Initiates asynchronous layered device discovery.
     */
    fun startDiscovery(onCompleted: ((Boolean) -> Unit)? = null) {
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                AppLogger.i(TAG, "Starting CANedge layered discovery...")
                _connectionState.value = CanedgeConnectionState.Scanning("Checking cached IP...", 0.1f)

                // Step 1: Try cached IP from AppPreferences
                val cachedIp = appPreferences.canedgeLastIp
                if (cachedIp.isNotBlank()) {
                    AppLogger.i(TAG, "Step 1: Probing cached IP $cachedIp")
                    val deviceId = httpClient.verifyDeviceAt(cachedIp, timeoutMs = 1500)
                    if (deviceId != null) {
                        onDeviceFound(cachedIp, deviceId)
                        onCompleted?.invoke(true)
                        return@launch
                    }
                }

                // Step 2: Try default hostname (7AC5E17F)
                val targetDeviceId = appPreferences.canedgeDeviceId
                _connectionState.value = CanedgeConnectionState.Scanning("Probing hostname $targetDeviceId...", 0.25f)
                AppLogger.i(TAG, "Step 2: Probing hostname $targetDeviceId")
                val hostDeviceId = httpClient.verifyDeviceAt(targetDeviceId, timeoutMs = 1500)
                if (hostDeviceId != null) {
                    onDeviceFound(targetDeviceId, hostDeviceId)
                    onCompleted?.invoke(true)
                    return@launch
                }

                // Step 3: Scan active local IPv4 subnet
                _connectionState.value = CanedgeConnectionState.Scanning("Scanning local Wi-Fi subnet...", 0.4f)
                val localIpv4 = getLocalIpv4Address()
                if (localIpv4 == null) {
                    AppLogger.w(TAG, "No active Wi-Fi IPv4 interface detected.")
                    _connectionState.value = CanedgeConnectionState.Error("No active Wi-Fi connection detected")
                    onCompleted?.invoke(false)
                    return@launch
                }

                AppLogger.i(TAG, "Step 3: Discovered local IP: $localIpv4. Scanning subnet...")
                val parts = localIpv4.split(".")
                if (parts.size != 4) {
                    _connectionState.value = CanedgeConnectionState.Error("Invalid subnet: $localIpv4")
                    onCompleted?.invoke(false)
                    return@launch
                }

                val subnetPrefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                val myHostNum = parts[3].toIntOrNull() ?: -1

                val candidateIps = (1..254)
                    .filter { it != myHostNum }
                    .map { "$subnetPrefix$it" }

                var foundDevice: Pair<String, String>? = null

                // Chunked parallel scanning to balance speed and socket overhead
                val chunks = candidateIps.chunked(PARALLEL_PROBE_CHUNK)
                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    val progress = 0.4f + (0.55f * (chunkIdx.toFloat() / chunks.size))
                    _connectionState.value = CanedgeConnectionState.Scanning("Scanning subnet (${chunkIdx + 1}/${chunks.size})...", progress)

                    val deferredChecks = chunk.map { ip ->
                        async {
                            if (isPortOpen(ip, PORT, SOCKET_PROBE_TIMEOUT_MS)) {
                                AppLogger.d(TAG, "Port $PORT open at $ip. Verifying CANedge...")
                                val devId = httpClient.verifyDeviceAt(ip, timeoutMs = 2000)
                                if (devId != null) {
                                    return@async Pair(ip, devId)
                                }
                            }
                            null
                        }
                    }

                    val results = deferredChecks.awaitAll()
                    foundDevice = results.firstOrNull { it != null }
                    if (foundDevice != null) {
                        break
                    }
                }

                if (foundDevice != null) {
                    val (ip, devId) = foundDevice
                    onDeviceFound(ip, devId)
                    onCompleted?.invoke(true)
                } else {
                    AppLogger.w(TAG, "CANedge not found on subnet $subnetPrefix*")
                    _connectionState.value = CanedgeConnectionState.Error("CANedge not found on local network")
                    onCompleted?.invoke(false)
                }
            } catch (e: CancellationException) {
                AppLogger.i(TAG, "Discovery scan cancelled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Discovery scan failed: ${e.message}", e)
                _connectionState.value = CanedgeConnectionState.Error("Scan failed: ${e.message}", e)
                onCompleted?.invoke(false)
            }
        }
    }

    private fun onDeviceFound(ip: String, deviceId: String) {
        AppLogger.i(TAG, "SUCCESS: CANedge2 connected! Device=$deviceId, IP=$ip")
        appPreferences.canedgeLastIp = ip
        appPreferences.canedgeDeviceId = deviceId
        val device = CanedgeDevice(deviceId = deviceId, ipAddress = ip)
        _connectionState.value = CanedgeConnectionState.Connected(device)
    }

    /**
     * Fast, low-overhead single-host probe for background watchdog auto-connect.
     * Probes only the cached IP and configured `<deviceId>` hostname without performing a
     * 254-address subnet sweep or disturbing UI discovery states.
     */
    suspend fun probeKnownHost(): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value is CanedgeConnectionState.Connected) return@withContext true
        if (_connectionState.value is CanedgeConnectionState.Scanning) return@withContext false

        val cachedIp = appPreferences.canedgeLastIp.trim()
        if (cachedIp.isNotEmpty()) {
            if (DebugToggles.isCanedgeVerboseActive) {
                AppLogger.d(TAG, "[CAN_DIAG] PROBE_HOST target=$cachedIp port=$PORT kind=cachedIp")
            }
            if (isPortOpen(cachedIp, PORT, SOCKET_PROBE_TIMEOUT_MS)) {
                val devId = httpClient.verifyDeviceAt(cachedIp, timeoutMs = 1800)
                if (devId != null) {
                    AppLogger.i(TAG, "[CAN_DIAG] PROBE_OK kind=cachedIp ip=$cachedIp device=$devId")
                    onDeviceFound(cachedIp, devId)
                    return@withContext true
                }
            }
        }

        val targetId = appPreferences.canedgeDeviceId.trim()
        if (targetId.isNotEmpty()) {
            if (DebugToggles.isCanedgeVerboseActive) {
                AppLogger.d(TAG, "[CAN_DIAG] PROBE_HOST target=$targetId port=$PORT kind=hostname")
            }
            if (isPortOpen(targetId, PORT, SOCKET_PROBE_TIMEOUT_MS)) {
                val devId = httpClient.verifyDeviceAt(targetId, timeoutMs = 1800)
                if (devId != null) {
                    val resolvedIp = try {
                        java.net.InetAddress.getByName(targetId).hostAddress ?: targetId
                    } catch (_: Exception) {
                        targetId
                    }
                    AppLogger.i(TAG, "[CAN_DIAG] PROBE_OK kind=hostname host=$targetId ip=$resolvedIp device=$devId")
                    onDeviceFound(resolvedIp, devId)
                    return@withContext true
                }
            }
        }
        if (DebugToggles.isCanedgeVerboseActive) {
            AppLogger.d(TAG, "[CAN_DIAG] PROBE_MISS cachedIp='$cachedIp' host='$targetId'")
        }
        false
    }

    /**
     * Marks an active connection as lost (e.g. Wi-Fi hotspot toggled or CANedge powered off)
     * so the background watchdog can resume lightweight reconnect probes.
     */
    fun markConnectionLost(reason: String = "Link lost") {
        if (_connectionState.value is CanedgeConnectionState.Connected) {
            AppLogger.w(TAG, "CANedge link marked offline: $reason")
            _connectionState.value = CanedgeConnectionState.Disconnected
        }
    }

    fun disconnect() {
        scanJob?.cancel()
        _connectionState.value = CanedgeConnectionState.Disconnected
        AppLogger.i(TAG, "CANedge disconnected")
    }

    private suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

    private fun getLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error enumerating network interfaces: ${e.message}", e)
        }
        return null
    }
}
