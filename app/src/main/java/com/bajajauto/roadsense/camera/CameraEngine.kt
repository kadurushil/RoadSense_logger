package com.bajajauto.roadsense.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Autonomous Camera2 video recording and frame timestamp extraction service.
 * Operates independently from Radar and GNSS.
 *
 * Capabilities:
 * - Records continuous H.264 MP4 video via hardware-accelerated MediaRecorder surface.
 * - Extracts exact camera sensor shutter exposure start timestamps (SystemClock.elapsedRealtimeNanos())
 *   via CameraCaptureSession.CaptureCallback.onCaptureStarted.
 * - Supports user-configurable resolutions (480p, 720p, 1080p) and target FPS (15, 30, 60).
 * - Allows dynamic preview attaching/detaching to conserve CPU/battery when viewing other tabs.
 */
class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "CameraEngine"
        private const val VIDEO_BITRATE_480P = 2_500_000   // 2.5 Mbps
        private const val VIDEO_BITRATE_720P = 6_000_000   // 6.0 Mbps
        private const val VIDEO_BITRATE_1080P = 12_000_000 // 12.0 Mbps
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null

    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    private val _engineState = MutableStateFlow<CameraEngineState>(CameraEngineState.Closed)
    val engineState: StateFlow<CameraEngineState> = _engineState.asStateFlow()

    private val _selectedResolution = MutableStateFlow(CameraResolution.RES_720P)
    val selectedResolution: StateFlow<CameraResolution> = _selectedResolution.asStateFlow()

    private val _selectedFps = MutableStateFlow(CameraFrameRate.FPS_30)
    val selectedFps: StateFlow<CameraFrameRate> = _selectedFps.asStateFlow()

    private val _totalFramesLogged = MutableStateFlow(0L)
    val totalFramesLogged: StateFlow<Long> = _totalFramesLogged.asStateFlow()

    private val frameListeners = CopyOnWriteArrayList<(CameraFrameMetadata) -> Unit>()

    @Volatile
    private var isRecordingVideo = false

    @Volatile
    private var isPreviewActive = false

    private var videoOutputFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraEngine-Worker").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping camera background thread", e)
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun addFrameListener(listener: (CameraFrameMetadata) -> Unit) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: (CameraFrameMetadata) -> Unit) {
        frameListeners.remove(listener)
    }

    fun setResolution(resolution: CameraResolution) {
        if (_selectedResolution.value != resolution) {
            _selectedResolution.value = resolution
            if (cameraDevice != null && !isRecordingVideo) {
                // Reconfigure session with new resolution
                restartSession()
            }
        }
    }

    fun setFrameRate(fps: CameraFrameRate) {
        if (_selectedFps.value != fps) {
            _selectedFps.value = fps
            if (cameraDevice != null && !isRecordingVideo) {
                restartSession()
            }
        }
    }

    /**
     * Attaches the live viewfinder surface texture from Compose AndroidView.
     */
    fun attachPreviewSurface(surfaceTexture: SurfaceTexture) {
        previewSurfaceTexture = surfaceTexture
        val res = _selectedResolution.value
        surfaceTexture.setDefaultBufferSize(res.width, res.height)
        previewSurface = Surface(surfaceTexture)
        isPreviewActive = true

        if (cameraDevice != null) {
            restartSession()
        } else {
            openCamera()
        }
    }

    /**
     * Detaches preview surface when the user swipes away to another tab to save battery.
     */
    fun detachPreviewSurface() {
        isPreviewActive = false
        previewSurface?.release()
        previewSurface = null
        previewSurfaceTexture = null

        if (cameraDevice != null && isRecordingVideo) {
            // Keep recording surface alive, detach preview only
            restartSession()
        } else if (!isRecordingVideo) {
            closeCamera()
        }
    }

    /**
     * Starts camera device acquisition.
     */
    @SuppressLint("MissingPermission")
    fun openCamera() {
        if (!hasCameraPermission()) {
            _engineState.value = CameraEngineState.Error("Camera permission not granted")
            return
        }

        if (cameraDevice != null) return

        try {
            val backCameraId = getBackCameraId() ?: cameraManager.cameraIdList.firstOrNull()
            if (backCameraId == null) {
                _engineState.value = CameraEngineState.Error("No back-facing camera available")
                return
            }

            _engineState.value = CameraEngineState.Opening
            cameraManager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _engineState.value = CameraEngineState.Closed
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _engineState.value = CameraEngineState.Error("CameraDevice error code: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            _engineState.value = CameraEngineState.Error("Failed to open camera: ${e.message}")
        }
    }

    private fun getBackCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return null
    }

    /**
     * Prepares MediaRecorder with standard H.264 / MP4 settings for silent video capture.
     */
    private fun setupMediaRecorder(outputFile: File) {
        val res = _selectedResolution.value
        val fps = _selectedFps.value.fps
        val bitrate = when (res) {
            CameraResolution.RES_480P -> VIDEO_BITRATE_480P
            CameraResolution.RES_720P -> VIDEO_BITRATE_720P
            CameraResolution.RES_1080P -> VIDEO_BITRATE_1080P
        }

        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(res.width, res.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            prepare()
        }
        recorderSurface = mediaRecorder?.surface
    }

    /**
     * Reconstructs the CameraCaptureSession with the appropriate surfaces
     * (Preview Surface, MediaRecorder Surface, or both).
     */
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val surfaces = mutableListOf<Surface>()

        previewSurface?.let { surfaces.add(it) }
        recorderSurface?.let { surfaces.add(it) }

        if (surfaces.isEmpty()) {
            Log.i(TAG, "No surfaces attached yet; waiting for preview or recording")
            return
        }

        try {
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session

                    try {
                        val requestBuilder = device.createCaptureRequest(
                            if (isRecordingVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        )

                        previewSurface?.let { requestBuilder.addTarget(it) }
                        recorderSurface?.let { requestBuilder.addTarget(it) }

                        // Target FPS range
                        val targetFps = _selectedFps.value.fps
                        requestBuilder.set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(targetFps, targetFps)
                        )
                        requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                        // Shutter exposure timestamp callback for microsecond cross-sensor sync
                        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureStarted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                timestamp: Long,
                                frameNumber: Long
                            ) {
                                val meta = CameraFrameMetadata(
                                    frameNumber = frameNumber,
                                    shutterTimestampNs = timestamp, // Exposure start in monotonic nanoseconds
                                    utcTimestampMs = System.currentTimeMillis()
                                )
                                _totalFramesLogged.value++
                                frameListeners.forEach { it.invoke(meta) }
                            }
                        }

                        session.setRepeatingRequest(requestBuilder.build(), captureCallback, backgroundHandler)

                        if (isRecordingVideo) {
                            _engineState.value = CameraEngineState.Recording(
                                resolution = _selectedResolution.value,
                                fps = _selectedFps.value.fps,
                                framesRecorded = _totalFramesLogged.value,
                                durationMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
                            )
                        } else {
                            _engineState.value = CameraEngineState.Previewing(
                                resolution = _selectedResolution.value,
                                fps = _selectedFps.value.fps
                            )
                        }
                        Log.i(TAG, "CameraCaptureSession configured successfully with ${surfaces.size} surfaces")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating capture request", e)
                        _engineState.value = CameraEngineState.Error("Capture request error: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "CameraCaptureSession configuration failed")
                    _engineState.value = CameraEngineState.Error("Camera configuration failed")
                }
            }

            device.createCaptureSession(surfaces, sessionCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera capture session", e)
            _engineState.value = CameraEngineState.Error("Session error: ${e.message}")
        }
    }

    private fun restartSession() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            createCaptureSession()
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting capture session", e)
        }
    }

    /**
     * Starts video recording directly to the specified MP4 file.
     */
    fun startVideoRecording(outputFile: File): Boolean {
        if (isRecordingVideo) return true

        try {
            videoOutputFile = outputFile
            setupMediaRecorder(outputFile)
            isRecordingVideo = true
            recordingStartTimeMs = SystemClock.elapsedRealtime()
            _totalFramesLogged.value = 0L

            if (cameraDevice == null) {
                openCamera()
            } else {
                restartSession()
            }

            // Start hardware media recorder after session configuration
            backgroundHandler?.postDelayed({
                try {
                    mediaRecorder?.start()
                    Log.i(TAG, "Started MediaRecorder H.264 video recording to ${outputFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaRecorder", e)
                }
            }, 300)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording", e)
            isRecordingVideo = false
            return false
        }
    }

    /**
     * Stops video recording and releases MediaRecorder.
     */
    fun stopVideoRecording(): Long {
        if (!isRecordingVideo) return 0L

        isRecordingVideo = false
        val total = _totalFramesLogged.value

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            recorderSurface = null

            Log.i(TAG, "Stopped video recording. Total frames captured: $total")
            if (isPreviewActive) {
                restartSession()
            } else {
                closeCamera()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }

        return total
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
            recorderSurface = null
            _engineState.value = CameraEngineState.Closed
            Log.i(TAG, "Closed CameraDevice")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    fun release() {
        if (isRecordingVideo) {
            stopVideoRecording()
        }
        closeCamera()
        stopBackgroundThread()
    }
}
