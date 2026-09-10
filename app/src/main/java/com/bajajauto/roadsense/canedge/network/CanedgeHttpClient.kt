package com.bajajauto.roadsense.canedge.network

import com.bajajauto.roadsense.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Low-level HTTP client tailored to CSS Electronics CANedge2 web server constraints:
 * 1. Single concurrent connection: Mutex ensures strictly one active HTTP request at a time.
 * 2. Automatic socket close: CANedge closes socket after each response; Keep-Alive is disabled.
 * 3. Cooldown delay: Enforces inter-request pause to prevent MCU socket exhaustion.
 */
class CanedgeHttpClient {

    companion object {
        private const val TAG = "CanedgeHttpClient"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 3000
        private const val DEFAULT_READ_TIMEOUT_MS = 10000
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 30000 // 30s timeout for MF4 downloads
        private const val INTER_REQUEST_COOLDOWN_MS = 120L
        private const val ERROR_BACKOFF_COOLDOWN_MS = 1500L // Let CANedge single-socket MCU reset after error
    }

    private val connectionMutex = Mutex()

    /**
     * Executes a HEAD request against the specified endpoint.
     * Extracts response headers (such as "Device-id" and "Allow").
     */
    suspend fun head(urlStr: String, timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): Map<String, String>? =
        withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL(urlStr)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        instanceFollowRedirects = false
                        setRequestProperty("Connection", "close")
                        setRequestProperty("User-Agent", "RoadSense-Android")
                    }

                    val code = conn.responseCode
                    if (code in 200..299) {
                        val headerMap = mutableMapOf<String, String>()
                        for ((k, v) in conn.headerFields) {
                            if (k != null && v.isNotEmpty()) {
                                headerMap[k.lowercase()] = v[0]
                                headerMap[k] = v[0]
                            }
                        }
                        headerMap
                    } else {
                        AppLogger.d(TAG, "HEAD $urlStr returned status $code")
                        null
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "HEAD $urlStr failed: ${e.message}")
                    null
                } finally {
                    conn?.disconnect()
                    delay(INTER_REQUEST_COOLDOWN_MS)
                }
            }
        }

    /**
     * Probes candidate IP or hostname. Returns verified device ID string if successful.
     */
    suspend fun verifyDeviceAt(host: String, timeoutMs: Int = 1500): String? {
        val testUrl = "http://$host/api/"
        val headers = head(testUrl, timeoutMs) ?: return null
        val deviceId = headers["device-id"] ?: headers["Device-id"]
        if (!deviceId.isNullOrBlank()) {
            AppLogger.i(TAG, "Verified CANedge device at $host: Device-id=$deviceId")
            return deviceId
        }
        return null
    }

    /**
     * Performs GET on /api/<path> and returns the response body as a String (JSON).
     */
    suspend fun getJson(urlStr: String, timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS): String? =
        withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL(urlStr)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
                        readTimeout = timeoutMs
                        instanceFollowRedirects = false
                        setRequestProperty("Connection", "close")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "RoadSense-Android")
                    }

                    val code = conn.responseCode
                    if (code in 200..299) {
                        val reader = InputStreamReader(BufferedInputStream(conn.inputStream), Charsets.UTF_8)
                        val text = reader.readText()
                        reader.close()
                        text
                    } else {
                        AppLogger.w(TAG, "GET $urlStr returned status $code")
                        null
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "GET $urlStr error: ${e.message}", e)
                    null
                } finally {
                    conn?.disconnect()
                    delay(INTER_REQUEST_COOLDOWN_MS)
                }
            }
        }

    /**
     * Streams a remote file to a local destination file.
     */
    suspend fun downloadToFile(
        urlStr: String,
        destinationFile: File,
        timeoutMs: Int = DEFAULT_DOWNLOAD_TIMEOUT_MS,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            var conn: HttpURLConnection? = null
            var success = false
            var hadError = false
            try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("Connection", "close")
                    setRequestProperty("User-Agent", "RoadSense-Android")
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val totalLength = conn.contentLengthLong
                    destinationFile.parentFile?.mkdirs()

                    val buffer = ByteArray(16 * 1024)
                    var bytesCopied = 0L

                    BufferedInputStream(conn.inputStream).use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesCopied += read
                                onProgress?.invoke(bytesCopied, totalLength)
                            }
                            output.flush()
                        }
                    }
                    success = true
                    AppLogger.i(TAG, "Downloaded $urlStr (${bytesCopied} bytes) to ${destinationFile.name}")
                } else {
                    AppLogger.w(TAG, "Download failed for $urlStr: HTTP $code")
                    hadError = true
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Download exception for $urlStr: ${e.message}", e)
                success = false
                hadError = true
            } finally {
                conn?.disconnect()
                if (hadError) {
                    delay(ERROR_BACKOFF_COOLDOWN_MS)
                } else {
                    delay(INTER_REQUEST_COOLDOWN_MS)
                }
            }
            success
        }
    }
}
