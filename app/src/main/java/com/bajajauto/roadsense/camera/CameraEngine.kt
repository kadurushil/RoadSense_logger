package com.bajajauto.roadsense.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.bajajauto.roadsense.logging.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Operating mode for vehicular Auto-Exposure metering.
 */
enum class RoadAeMode {
    AUTO_ROAD,   // Autonomous Dual-Zone Photometric Contrast Metering (Default)
    FULL_MATRIX  // Traditional Camera HAL Matrix Metering
}

/**
 * Photometric condition state determined by real-time Sky vs Road contrast analysis.
 */
enum class RoadAeState {
    SKY_BLOOM,    // Sky is >= 1.8x brighter than road -> Road region metered with adaptive EV boost
    BALANCED,     // 0.9x <= Contrast < 1.8x -> Road region metered with neutral EV
    NIGHT_TUNNEL, // Contrast < 0.9x -> Full matrix metered to prevent blown headlights
    TAP_LOCKED    // Driver manually locked target via screen tap
}

/**
 * Autonomous Camera2 video recording and frame timestamp extraction service.
 * Operates independently from Radar and GNSS.
 *
 * Capabilities:
 * - Discovers and switches between available cameras (Main Wide, Ultra-Wide, Front).
 * - Records continuous H.264 MP4 video via hardware-accelerated MediaRecorder surface.
 * - Supports landscape & portrait orientation transforms and video rotation hints.
 * - Extracts exact camera sensor shutter exposure start timestamps (SystemClock.elapsedRealtimeNanos())
 *   via CameraCaptureSession.CaptureCallback.onCaptureStarted.
 * - Supports user-configurable resolutions (480p, 720p, 1080p) and target FPS (15, 30, 60).
 * - Allows dynamic preview attaching/detaching to conserve CPU/battery when viewing other tabs.
 * - Autonomous Dual-Zone Luminance Analysis for intelligent sky bloom rejection.
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

    private var repeatingRequestBuilder: CaptureRequest.Builder? = null
    private var repeatingCaptureCallback: CameraCaptureSession.CaptureCallback? = null

    private val _isInfinityFocusLocked = MutableStateFlow(false)
    val isInfinityFocusLocked: StateFlow<Boolean> = _isInfinityFocusLocked.asStateFlow()

    private val _isAfAeLocked = MutableStateFlow(false)
    val isAfAeLocked: StateFlow<Boolean> = _isAfAeLocked.asStateFlow()

    private val _tapFocusPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val tapFocusPoint: StateFlow<Pair<Float, Float>?> = _tapFocusPoint.asStateFlow()

    private var currentMeteringRegion: android.hardware.camera2.params.MeteringRectangle? = null

    private val _isOisEnabled = MutableStateFlow(true)
    val isOisEnabled: StateFlow<Boolean> = _isOisEnabled.asStateFlow()

    private val _isOisSupported = MutableStateFlow(false)
    val isOisSupported: StateFlow<Boolean> = _isOisSupported.asStateFlow()

    // Autonomous Dual-Zone Road Auto-Exposure State
    private val _roadAeMode = MutableStateFlow(RoadAeMode.AUTO_ROAD)
    val roadAeMode: StateFlow<RoadAeMode> = _roadAeMode.asStateFlow()

    private val _roadAeState = MutableStateFlow(RoadAeState.BALANCED)
    val roadAeState: StateFlow<RoadAeState> = _roadAeState.asStateFlow()

    private val _skyLuminance = MutableStateFlow(128f)
    val skyLuminance: StateFlow<Float> = _skyLuminance.asStateFlow()

    private val _roadLuminance = MutableStateFlow(128f)
    val roadLuminance: StateFlow<Float> = _roadLuminance.asStateFlow()

    private val _contrastRatio = MutableStateFlow(1.0f)
    val contrastRatio: StateFlow<Float> = _contrastRatio.asStateFlow()

    private var previewTextureViewRef: WeakReference<TextureView>? = null
    private var luminanceAnalysisJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var smoothedContrastRatio: Float = 1.0f
    private var lastAppliedAeState: RoadAeState? = null
    private var lastAppliedEvIndex: Int = 0

    private val _engineState = MutableStateFlow<CameraEngineState>(CameraEngineState.Closed)
    val engineState: StateFlow<CameraEngineState> = _engineState.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraDeviceInfo>> = _availableCameras.asStateFlow()

    private val _selectedCamera = MutableStateFlow<CameraDeviceInfo?>(null)
    val selectedCamera: StateFlow<CameraDeviceInfo?> = _selectedCamera.asStateFlow()

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

    @Volatile
    private var currentDisplayRotation: Int = Surface.ROTATION_0

    private var currentViewWidth: Int = 0
    private var currentViewHeight: Int = 0

    private var videoOutputFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    init {
        startBackgroundThread()
        discoverCameras()
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
            AppLogger.e(TAG, "Error stopping camera background thread", e)
        }
    }

    /**
     * Enumerate all standard and vendor/physical cameras available on this device.
     */
    fun discoverCameras() {
        try {
            val list = mutableListOf<CameraDeviceInfo>()
            val visitedIds = mutableSetOf<String>()

            // 1. Enumerate standard camera IDs
            val standardIds = cameraManager.cameraIdList
            AppLogger.i(TAG, "Standard Camera2 cameraIdList: ${standardIds.joinToString()}")
            for (id in standardIds) {
                visitedIds.add(id)
            }

            // 2. Probe Camera1 legacy API
            try {
                @Suppress("DEPRECATION")
                val numCamera1 = android.hardware.Camera.getNumberOfCameras()
                AppLogger.i(TAG, "Camera1 getNumberOfCameras: $numCamera1")
                for (i in 0 until numCamera1) {
                    @Suppress("DEPRECATION")
                    val info = android.hardware.Camera.CameraInfo()
                    @Suppress("DEPRECATION")
                    android.hardware.Camera.getCameraInfo(i, info)
                    AppLogger.i(TAG, "Camera1 info index $i: facing=${info.facing}, orientation=${info.orientation}")
                }
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Camera1 probe failed: ${e.message}")
            }

            // 3. Probe candidate vendor/physical IDs common on multi-camera devices (e.g. Samsung 2, 50, 52)
            val candidateIds = listOf("0", "1", "2", "3", "4", "50", "51", "52")
            for (candidate in candidateIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(candidate)
                    val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                    AppLogger.i(TAG, "Camera2 Candidate $candidate SUCCESS: focal=${focal}mm")
                    visitedIds.add(candidate)
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Camera2 Candidate $candidate REJECTED: ${e.javaClass.simpleName} - ${e.message}")
                }
            }

            for (id in visitedIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val focal = focalLengths?.firstOrNull() ?: 0f

                    // Ultra-wide lens focal lengths are typically <= 2.6mm (e.g. 2.20mm on Samsung)
                    val isUltra = (facing == CameraCharacteristics.LENS_FACING_BACK && focal > 0f && focal <= 2.6f)

                    val name = when {
                        isUltra -> "Ultra-Wide (${String.format(Locale.US, "%.1f", focal)}mm)"
                        facing == CameraCharacteristics.LENS_FACING_BACK -> "Main Wide (${String.format(Locale.US, "%.1f", focal)}mm)"
                        facing == CameraCharacteristics.LENS_FACING_FRONT -> "Front (${String.format(Locale.US, "%.1f", focal)}mm)"
                        else -> "Camera $id"
                    }

                    val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
                    val hasOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

                    val info = CameraDeviceInfo(
                        id = id,
                        displayName = name,
                        facing = facing,
                        focalLengthMm = focal,
                        isUltraWide = isUltra,
                        supportsOis = hasOis
                    )
                    list.add(info)
                    AppLogger.i(TAG, "Discovered Camera: id=$id, name=$name, facing=$facing, focal=${focal}mm, ultraWide=$isUltra, supportsOis=$hasOis")
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Skipping camera candidate $id: ${e.message}")
                }
            }

            _availableCameras.value = list

            // Default selection: Prefer Main Back camera, or first available
            if (_selectedCamera.value == null) {
                val defaultCam = list.firstOrNull { it.isBackFacing && !it.isUltraWide }
                    ?: list.firstOrNull { it.isBackFacing }
                    ?: list.firstOrNull()
                _selectedCamera.value = defaultCam
                _isOisSupported.value = defaultCam?.supportsOis == true
                AppLogger.i(TAG, "Selected default camera: ${defaultCam?.displayName} (id=${defaultCam?.id}), OIS supported: ${defaultCam?.supportsOis}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to enumerate cameras", e)
        }
    }

    fun selectCamera(deviceInfo: CameraDeviceInfo) {
        if (_selectedCamera.value?.id == deviceInfo.id) return

        if (isRecordingVideo) {
            AppLogger.w(TAG, "Cannot switch cameras during active video recording")
            return
        }

        AppLogger.i(TAG, "Switching camera from ${_selectedCamera.value?.displayName} to ${deviceInfo.displayName} (id=${deviceInfo.id})")
        _selectedCamera.value = deviceInfo
        _isOisSupported.value = deviceInfo.supportsOis

        if (cameraDevice != null) {
            closeCamera()
            if (isPreviewActive) {
                backgroundHandler?.postDelayed({
                    openCamera()
                }, 150)
            }
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
            AppLogger.i(TAG, "Resolution changed to: ${resolution.label} (${resolution.width}x${resolution.height})")
            previewSurfaceTexture?.setDefaultBufferSize(resolution.width, resolution.height)
            previewSurface?.release()
            previewSurface = previewSurfaceTexture?.let { Surface(it) }
            if (cameraDevice != null && !isRecordingVideo) {
                restartSession()
            }
        }
    }

    fun setFrameRate(fps: CameraFrameRate) {
        if (_selectedFps.value != fps) {
            _selectedFps.value = fps
            AppLogger.i(TAG, "Frame rate changed to: ${fps.label}")
            if (cameraDevice != null && !isRecordingVideo) {
                restartSession()
            }
        }
    }

    /**
     * Attaches the live viewfinder surface texture from Compose AndroidView.
     */
    fun attachPreviewSurface(surfaceTexture: SurfaceTexture, width: Int = 0, height: Int = 0) {
        if (previewSurfaceTexture === surfaceTexture && isPreviewActive && cameraDevice != null && _engineState.value !is CameraEngineState.Closed && _engineState.value !is CameraEngineState.Error) {
            AppLogger.d(TAG, "attachPreviewSurface: surface already active; updating dimensions ${width}x${height}")
            currentViewWidth = width
            currentViewHeight = height
            return
        }
        previewSurfaceTexture = surfaceTexture
        currentViewWidth = width
        currentViewHeight = height
        val res = _selectedResolution.value
        surfaceTexture.setDefaultBufferSize(res.width, res.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        isPreviewActive = true

        AppLogger.i(TAG, "Attached preview surface: ${res.width}x${res.height}, view size: ${width}x${height}")

        if (cameraDevice != null) {
            restartSession()
        } else {
            openCamera()
        }
    }

    /**
     * Checks if the given SurfaceTexture is currently attached to this engine.
     */
    fun isSurfaceAttached(st: SurfaceTexture?): Boolean {
        return st != null && previewSurfaceTexture === st && isPreviewActive && cameraDevice != null
    }

    /**
     * Updates display rotation and recalculates matrix transformation on the TextureView.
     */
    fun updateDisplayRotation(rotation: Int, textureView: TextureView? = null, width: Int = 0, height: Int = 0) {
        currentDisplayRotation = rotation
        if (width > 0) currentViewWidth = width
        if (height > 0) currentViewHeight = height

        if (textureView != null) {
            previewTextureViewRef = WeakReference(textureView)
            if (_roadAeMode.value == RoadAeMode.AUTO_ROAD && isPreviewActive) {
                startLuminanceAnalyzer()
            }
        }

        if (textureView != null && currentViewWidth > 0 && currentViewHeight > 0) {
            configureTransform(textureView, currentViewWidth, currentViewHeight, rotation)
        }
    }

    /**
     * Computes the matrix transform so the preview matches aspect ratio and orientation
     * without distortion in both portrait and landscape.
     */
    fun configureTransform(textureView: TextureView, viewWidth: Int, viewHeight: Int, displayRotation: Int) {
        if (viewWidth == 0 || viewHeight == 0) return

        val res = _selectedResolution.value
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, res.height.toFloat(), res.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            // LANDSCAPE (Rotation 90 or 270)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / res.height,
                viewWidth.toFloat() / res.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else if (displayRotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        } else {
            // PORTRAIT (Rotation 0)
            // Camera HAL delivers buffer oriented at 90 deg (effective aspect = res.height / res.width)
            val bufferAspect = res.height.toFloat() / res.width.toFloat()
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            if (viewAspect > bufferAspect) {
                val scale = viewAspect / bufferAspect
                matrix.postScale(1f, scale, centerX, centerY)
            } else if (viewAspect < bufferAspect) {
                val scale = bufferAspect / viewAspect
                matrix.postScale(scale, 1f, centerX, centerY)
            }
        }

        textureView.setTransform(matrix)
        AppLogger.d(TAG, "configureTransform: view=${viewWidth}x${viewHeight}, buf=${res.width}x${res.height}, rot=$displayRotation")
    }

    /**
     * Detaches preview surface when the user swipes away to another tab to save battery.
     */
    fun detachPreviewSurface(surfaceTexture: SurfaceTexture? = null) {
        if (surfaceTexture != null && previewSurfaceTexture != null && previewSurfaceTexture !== surfaceTexture) {
            AppLogger.d(TAG, "Ignoring detachPreviewSurface for stale surfaceTexture")
            return
        }
        AppLogger.i(TAG, "Detaching preview surface (user swiped away or paused)")
        stopLuminanceAnalyzer()
        previewTextureViewRef = null
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

        if (cameraDevice != null || _engineState.value is CameraEngineState.Opening) return

        try {
            val targetCameraId = _selectedCamera.value?.id
                ?: getBackCameraId()
                ?: cameraManager.cameraIdList.firstOrNull()

            if (targetCameraId == null) {
                _engineState.value = CameraEngineState.Error("No camera device available")
                return
            }

            AppLogger.i(TAG, "Opening camera ID: $targetCameraId (${_selectedCamera.value?.displayName ?: "Default"})")
            _engineState.value = CameraEngineState.Opening
            cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isPreviewActive && !isRecordingVideo) {
                        AppLogger.i(TAG, "Camera opened after preview was cancelled; closing immediately")
                        camera.close()
                        cameraDevice = null
                        _engineState.value = CameraEngineState.Closed
                        return
                    }
                    cameraDevice = camera
                    AppLogger.i(TAG, "CameraDevice onOpened: id=${camera.id}")
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    AppLogger.w(TAG, "CameraDevice onDisconnected: id=${camera.id}")
                    camera.close()
                    cameraDevice = null
                    _engineState.value = CameraEngineState.Closed
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    AppLogger.e(TAG, "CameraDevice onError: id=${camera.id}, code=$error")
                    camera.close()
                    cameraDevice = null
                    _engineState.value = CameraEngineState.Error("CameraDevice error code: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error opening camera", e)
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
     * Incorporates orientation hints so video plays upright in landscape or portrait.
     */
    private fun setupMediaRecorder(outputFile: File) {
        val res = _selectedResolution.value
        val fps = _selectedFps.value.fps
        val bitrate = when (res) {
            CameraResolution.RES_480P -> VIDEO_BITRATE_480P
            CameraResolution.RES_720P -> VIDEO_BITRATE_720P
            CameraResolution.RES_1080P -> VIDEO_BITRATE_1080P
        }

        // Calculate rotation hint for video container
        val targetId = _selectedCamera.value?.id ?: "0"
        val chars = try { cameraManager.getCameraCharacteristics(targetId) } catch (e: Exception) { null }
        val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val isFront = chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        val rotationHint = if (isFront) {
            (sensorOrientation + when (currentDisplayRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }) % 360
        } else {
            (sensorOrientation - when (currentDisplayRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            } + 360) % 360
        }

        AppLogger.i(TAG, "MediaRecorder setup: ${res.width}x${res.height} @ ${fps}fps, bitrate=${bitrate}, orientationHint=${rotationHint}deg")

        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(res.width, res.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOrientationHint(rotationHint)
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
            AppLogger.i(TAG, "No surfaces attached yet; waiting for preview or recording")
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

                        // Apply current focus mode (infinity lock vs continuous AF)
                        applyFocusSettings(requestBuilder)

                        // Apply optical image stabilization (OIS) settings
                        applyStabilizationSettings(requestBuilder)

                        // Apply autonomous road auto-exposure settings
                        applyAeMeteringSettings(requestBuilder)

                        repeatingRequestBuilder = requestBuilder

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

                        repeatingCaptureCallback = captureCallback
                        session.setRepeatingRequest(requestBuilder.build(), captureCallback, backgroundHandler)
                        startLuminanceAnalyzer()

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
                        AppLogger.i(TAG, "CameraCaptureSession configured successfully with ${surfaces.size} surfaces")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to start repeating capture request", e)
                        _engineState.value = CameraEngineState.Error("Capture request error: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    AppLogger.e(TAG, "CameraCaptureSession configuration failed")
                    _engineState.value = CameraEngineState.Error("Camera configuration failed")
                }
            }

            device.createCaptureSession(surfaces, sessionCallback, backgroundHandler)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create camera capture session", e)
            _engineState.value = CameraEngineState.Error("Session error: ${e.message}")
        }
    }

    private fun restartSession() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            repeatingRequestBuilder = null
            repeatingCaptureCallback = null
            createCaptureSession()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error restarting capture session", e)
        }
    }

    /**
     * Applies focus settings to a CaptureRequest.Builder based on infinity lock state.
     */
    private fun applyFocusSettings(builder: CaptureRequest.Builder) {
        val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
        val chars = try { cameraManager.getCameraCharacteristics(targetCameraId) } catch (e: Exception) { null }
        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        if (_isInfinityFocusLocked.value) {
            // Lock focus to optical infinity (0 diopters)
            if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                AppLogger.d(TAG, "Applied AF_MODE_OFF with lens focus distance = 0.0f (Infinity)")
            }
        } else if (_isAfAeLocked.value && currentMeteringRegion != null) {
            // Tap-to-focus & AE locked
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(currentMeteringRegion))
            AppLogger.d(TAG, "Applied AF Lock on region: $currentMeteringRegion")
        } else {
            // Default Continuous Auto-Focus for video/preview
            val preferredAfMode = when {
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> CameraMetadata.CONTROL_AF_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, preferredAfMode)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            AppLogger.d(TAG, "Applied AF mode: $preferredAfMode (continuous)")
        }
    }

    /**
     * Calculates the effective sensor rotation from screen space to sensor space.
     */
    private fun getEffectiveSensorRotation(chars: CameraCharacteristics?): Int {
        val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotationDegrees = when (currentDisplayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val isFacingFront = chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        return if (isFacingFront) {
            (sensorOrientation + rotationDegrees) % 360
        } else {
            (sensorOrientation - rotationDegrees + 360) % 360
        }
    }

    /**
     * Maps a normalized point [0..1] in screen space to normalized sensor space.
     */
    private fun mapNormalizedPointToSensor(x: Float, y: Float, effectiveRotation: Int): Pair<Float, Float> {
        return when (effectiveRotation) {
            90 -> Pair(y, 1f - x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(1f - y, x)
            else -> Pair(x, y)
        }
    }

    /**
     * Maps a normalized bounding box [0..1] in screen space to the hardware sensor active array.
     */
    private fun mapNormalizedRectToSensor(
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        activeArray: Rect,
        effectiveRotation: Int
    ): Rect {
        val p1 = mapNormalizedPointToSensor(normLeft, normTop, effectiveRotation)
        val p2 = mapNormalizedPointToSensor(normRight, normTop, effectiveRotation)
        val p3 = mapNormalizedPointToSensor(normLeft, normBottom, effectiveRotation)
        val p4 = mapNormalizedPointToSensor(normRight, normBottom, effectiveRotation)

        val sMinX = minOf(p1.first, p2.first, p3.first, p4.first)
        val sMaxX = maxOf(p1.first, p2.first, p3.first, p4.first)
        val sMinY = minOf(p1.second, p2.second, p3.second, p4.second)
        val sMaxY = maxOf(p1.second, p2.second, p3.second, p4.second)

        val left = (activeArray.left + sMinX * activeArray.width()).toInt().coerceIn(activeArray.left, activeArray.right)
        val right = (activeArray.left + sMaxX * activeArray.width()).toInt().coerceIn(activeArray.left, activeArray.right)
        val top = (activeArray.top + sMinY * activeArray.height()).toInt().coerceIn(activeArray.top, activeArray.bottom)
        val bottom = (activeArray.top + sMaxY * activeArray.height()).toInt().coerceIn(activeArray.top, activeArray.bottom)

        return Rect(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )
    }

    /**
     * Creates a metering rectangle covering the bottom 65% (road/traffic) and middle 80% width.
     */
    private fun createRoadMeteringRectangle(activeArray: Rect, effectiveRotation: Int): MeteringRectangle {
        val rect = mapNormalizedRectToSensor(
            normLeft = 0.10f,
            normTop = 0.35f,
            normRight = 0.90f,
            normBottom = 1.00f,
            activeArray = activeArray,
            effectiveRotation = effectiveRotation
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /**
     * Applies autonomous road auto-exposure metering and EV compensation to CaptureRequest.Builder.
     */
    private fun applyAeMeteringSettings(builder: CaptureRequest.Builder) {
        if (_isAfAeLocked.value && currentMeteringRegion != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(currentMeteringRegion))
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            _roadAeState.value = RoadAeState.TAP_LOCKED
            return
        }

        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)

        if (_roadAeMode.value == RoadAeMode.FULL_MATRIX) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            return
        }

        val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
        val chars = try { cameraManager.getCameraCharacteristics(targetCameraId) } catch (e: Exception) { null }
        val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxAeRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val compRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val effectiveRotation = getEffectiveSensorRotation(chars)

        when (_roadAeState.value) {
            RoadAeState.SKY_BLOOM -> {
                if (activeArray != null && maxAeRegions > 0) {
                    val roadMetering = createRoadMeteringRectangle(activeArray, effectiveRotation)
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(roadMetering))
                }
                val evSteps = if (_contrastRatio.value >= 2.5f) 2 else 1
                val maxComp = compRange?.upper ?: 0
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps.coerceAtMost(maxComp))
            }
            RoadAeState.BALANCED -> {
                if (activeArray != null && maxAeRegions > 0) {
                    val roadMetering = createRoadMeteringRectangle(activeArray, effectiveRotation)
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(roadMetering))
                }
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            }
            RoadAeState.NIGHT_TUNNEL -> {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            }
            RoadAeState.TAP_LOCKED -> {
                // Handled above
            }
        }
    }

    private fun updateRepeatingAeSettings() {
        val session = captureSession ?: return
        val builder = repeatingRequestBuilder ?: return
        val callback = repeatingCaptureCallback ?: return

        try {
            applyAeMeteringSettings(builder)
            session.setRepeatingRequest(builder.build(), callback, backgroundHandler)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update repeating AE settings", e)
        }
    }

    /**
     * Starts the autonomous 4 Hz dual-zone luminance analysis coroutine loop.
     */
    private fun startLuminanceAnalyzer() {
        luminanceAnalysisJob?.cancel()
        luminanceAnalysisJob = engineScope.launch {
            while (isActive) {
                delay(250) // ~4 Hz sample rate
                if (!isPreviewActive || _isAfAeLocked.value || _roadAeMode.value != RoadAeMode.AUTO_ROAD) {
                    continue
                }

                val textureView = previewTextureViewRef?.get() ?: continue
                if (!textureView.isAvailable) continue

                try {
                    val bitmap = textureView.getBitmap(32, 24) ?: continue

                    var skySum = 0L
                    var skyCount = 0
                    var roadSum = 0L
                    var roadCount = 0

                    val startX = (32 * 0.1f).toInt()
                    val endX = (32 * 0.9f).toInt()
                    val splitY = (24 * 0.35f).toInt() // Top 35% is sky

                    for (y in 0 until 24) {
                        for (x in startX..endX) {
                            val pixel = bitmap.getPixel(x, y)
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            val luma = (0.299f * r + 0.587f * g + 0.114f * b).toLong()

                            if (y < splitY) {
                                skySum += luma
                                skyCount++
                            } else {
                                roadSum += luma
                                roadCount++
                            }
                        }
                    }
                    bitmap.recycle()

                    val skyLuma = if (skyCount > 0) skySum.toFloat() / skyCount else 128f
                    val roadLuma = if (roadCount > 0) roadSum.toFloat() / roadCount else 128f
                    val rawRatio = skyLuma / maxOf(1.0f, roadLuma)

                    // EMA smoothing (alpha = 0.25)
                    smoothedContrastRatio = 0.25f * rawRatio + 0.75f * smoothedContrastRatio

                    _skyLuminance.value = skyLuma
                    _roadLuminance.value = roadLuma
                    _contrastRatio.value = smoothedContrastRatio

                    // Determine photometric state
                    val newState = when {
                        smoothedContrastRatio >= 1.8f -> RoadAeState.SKY_BLOOM
                        smoothedContrastRatio >= 0.9f -> RoadAeState.BALANCED
                        else -> RoadAeState.NIGHT_TUNNEL
                    }

                    val evTarget = if (newState == RoadAeState.SKY_BLOOM && smoothedContrastRatio >= 2.5f) 2 else if (newState == RoadAeState.SKY_BLOOM) 1 else 0

                    if (newState != lastAppliedAeState || evTarget != lastAppliedEvIndex) {
                        lastAppliedAeState = newState
                        lastAppliedEvIndex = evTarget
                        _roadAeState.value = newState
                        AppLogger.d(TAG, "Road AE state shift -> $newState (contrast=${String.format(Locale.US, "%.2f", smoothedContrastRatio)}, sky=${skyLuma.toInt()}, road=${roadLuma.toInt()}, ev=$evTarget)")
                        updateRepeatingAeSettings()
                    }
                } catch (e: Exception) {
                    // Ignore transient frame buffer recycling exceptions
                }
            }
        }
    }

    private fun stopLuminanceAnalyzer() {
        luminanceAnalysisJob?.cancel()
        luminanceAnalysisJob = null
    }

    fun setRoadAeMode(mode: RoadAeMode) {
        if (_roadAeMode.value == mode) return
        _roadAeMode.value = mode
        AppLogger.i(TAG, "Road AE mode changed to: $mode")
        if (mode == RoadAeMode.AUTO_ROAD) {
            startLuminanceAnalyzer()
        }
        updateRepeatingAeSettings()
    }

    /**
     * Applies optical image stabilization (OIS) settings to a CaptureRequest.Builder.
     */
    private fun applyStabilizationSettings(builder: CaptureRequest.Builder) {
        val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
        val chars = try { cameraManager.getCameraCharacteristics(targetCameraId) } catch (e: Exception) { null }
        val oisModes = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val supportsOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        if (supportsOis) {
            val mode = if (_isOisEnabled.value) {
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            AppLogger.d(TAG, "Applied LENS_OPTICAL_STABILIZATION_MODE = $mode (enabled=${_isOisEnabled.value})")
        }
    }

    /**
     * Enables or disables Optical Image Stabilization (OIS).
     */
    fun setOisEnabled(enabled: Boolean) {
        if (_isOisEnabled.value == enabled) return
        _isOisEnabled.value = enabled
        AppLogger.i(TAG, "OIS enabled state set to: $enabled")

        val session = captureSession
        val builder = repeatingRequestBuilder
        val callback = repeatingCaptureCallback
        if (session != null && builder != null) {
            try {
                applyStabilizationSettings(builder)
                session.setRepeatingRequest(builder.build(), callback, backgroundHandler)
                AppLogger.i(TAG, "Updated repeating request with OIS = $enabled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply OIS setting to repeating request", e)
            }
        }
    }

    /**
     * Toggles or sets optical infinity focus lock.
     * When locked, focus distance is clamped to 0.0f (infinity diopters), preventing
     * windshield glare, rain, or dust from pulling focus away from distant road targets.
     */
    fun setInfinityFocus(locked: Boolean) {
        if (_isInfinityFocusLocked.value == locked) return
        _isInfinityFocusLocked.value = locked
        if (locked) {
            _isAfAeLocked.value = false
            _tapFocusPoint.value = null
            currentMeteringRegion = null
        }
        AppLogger.i(TAG, "Infinity focus lock set to: $locked")

        val session = captureSession
        val builder = repeatingRequestBuilder
        val callback = repeatingCaptureCallback
        if (session != null && builder != null) {
            try {
                applyFocusSettings(builder)
                applyAeMeteringSettings(builder)
                session.setRepeatingRequest(builder.build(), callback, backgroundHandler)
                AppLogger.i(TAG, "Updated repeating request with infinity lock = $locked")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply infinity focus to repeating request", e)
            }
        }
    }

    /**
     * Triggers an explicit Auto-Focus cycle.
     * If normX and normY are provided in [0..1] range (e.g. from screen tap), the AF/AE metering
     * rectangle is placed at the corresponding sensor coordinates and locks both AF and AE.
     * If normX and normY are null (Re-Focus button), all locks are cleared, returning to continuous AF.
     */
    fun triggerAutoFocus(normX: Float? = null, normY: Float? = null) {
        if (_isInfinityFocusLocked.value) {
            _isInfinityFocusLocked.value = false
            AppLogger.i(TAG, "Infinity focus unlocked due to re-focus trigger")
        }

        val session = captureSession ?: run {
            AppLogger.w(TAG, "triggerAutoFocus: CaptureSession is null")
            return
        }
        val builder = repeatingRequestBuilder ?: run {
            AppLogger.w(TAG, "triggerAutoFocus: repeatingRequestBuilder is null")
            return
        }
        val callback = repeatingCaptureCallback

        try {
            val isTapLock = normX != null && normY != null
            val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
            val chars = try { cameraManager.getCameraCharacteristics(targetCameraId) } catch (e: Exception) { null }
            val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxAfRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAeRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

            if (isTapLock && activeArray != null && (maxAfRegions > 0 || maxAeRegions > 0)) {
                _isAfAeLocked.value = true
                _tapFocusPoint.value = Pair(normX!!, normY!!)

                val focusX = normX.coerceIn(0f, 1f)
                val focusY = normY.coerceIn(0f, 1f)

                val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val rotationDegrees = when (currentDisplayRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val isFacingFront = chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

                // Effective rotation from screen space to sensor space
                val effectiveRotation = if (isFacingFront) {
                    (sensorOrientation + rotationDegrees) % 360
                } else {
                    (sensorOrientation - rotationDegrees + 360) % 360
                }

                // Map normalized view (x, y) to sensor normalized (sensorX, sensorY)
                val (sensorNormX, sensorNormY) = when (effectiveRotation) {
                    90 -> Pair(focusY, 1f - focusX)
                    180 -> Pair(1f - focusX, 1f - focusY)
                    270 -> Pair(1f - focusY, focusX)
                    else -> Pair(focusX, focusY)
                }

                val centerX = (activeArray.left + sensorNormX * activeArray.width()).toInt()
                val centerY = (activeArray.top + sensorNormY * activeArray.height()).toInt()
                val regionWidth = (activeArray.width() * 0.15f).toInt()
                val regionHeight = (activeArray.height() * 0.15f).toInt()

                val afRect = android.graphics.Rect(
                    (centerX - regionWidth / 2).coerceIn(activeArray.left, activeArray.right - regionWidth),
                    (centerY - regionHeight / 2).coerceIn(activeArray.top, activeArray.bottom - regionHeight),
                    (centerX + regionWidth / 2).coerceIn(activeArray.left + regionWidth, activeArray.right),
                    (centerY + regionHeight / 2).coerceIn(activeArray.top + regionHeight, activeArray.bottom)
                )

                val meteringRegion = android.hardware.camera2.params.MeteringRectangle(
                    afRect,
                    android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
                )
                currentMeteringRegion = meteringRegion

                val regions = arrayOf(meteringRegion)
                if (maxAfRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                }
                if (maxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                }
                // Temporarily unlock AE so it can meter for the tapped point
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                AppLogger.i(TAG, "Setting AF/AE metering region for tap lock: $afRect (norm: $focusX, $focusY)")

                // Cancel previous AF state machine
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), null, backgroundHandler)

                // Trigger active AF scan and AE precapture for the tapped area
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        AppLogger.i(TAG, "Tap AF Trigger completed: afState=$afState, focusDist=$focusDist")
                    }
                }, backgroundHandler)

                // Once AF/AE converges, lock both AF and AE in repeating request
                backgroundHandler?.postDelayed({
                    try {
                        val currentSession = captureSession
                        val currentBuilder = repeatingRequestBuilder
                        if (currentSession != null && currentBuilder != null && _isAfAeLocked.value) {
                            currentBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                            currentBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                            currentBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                            if (maxAfRegions > 0) currentBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                            if (maxAeRegions > 0) currentBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                            currentBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true) // Lock AE!
                            currentSession.setRepeatingRequest(currentBuilder.build(), callback, backgroundHandler)
                            AppLogger.i(TAG, "Locked AF and AE at tap point ($focusX, $focusY)")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error locking repeating request after tap AF/AE", e)
                    }
                }, 700)
            } else {
                // User pressed Re-Focus: Reset locks, unlock AE, and return to CONTINUOUS_VIDEO
                _isAfAeLocked.value = false
                _tapFocusPoint.value = null
                currentMeteringRegion = null

                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)

                // Momentarily kick lens motor to visibly break out of previous locked state
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 5.0f) // ~0.2 meters (near focus)
                session.capture(builder.build(), null, backgroundHandler)

                // Reset AF state machine
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), null, backgroundHandler)

                // Trigger active full-frame AF sweep
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        AppLogger.i(TAG, "Re-Focus sweep completed: afState=$afState, focusDist=$focusDist")
                    }
                }, backgroundHandler)

                // Settle repeating request back into CONTINUOUS_VIDEO with unlocked AE
                backgroundHandler?.postDelayed({
                    try {
                        val currentSession = captureSession
                        val currentBuilder = repeatingRequestBuilder
                        if (currentSession != null && currentBuilder != null && !_isInfinityFocusLocked.value && !_isAfAeLocked.value) {
                            currentBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            currentBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                            currentBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                            currentBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                            applyAeMeteringSettings(currentBuilder)
                            currentSession.setRepeatingRequest(currentBuilder.build(), callback, backgroundHandler)
                            AppLogger.i(TAG, "Resumed CONTINUOUS_VIDEO repeating request after Re-Focus sweep")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error resuming repeating request after AF sweep", e)
                    }
                }, 800)
            }
            AppLogger.i(TAG, "Auto-focus cycle successfully triggered (isTapLock=$isTapLock)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger auto-focus", e)
        }
    }

    fun closeCamera() {
        try {
            stopLuminanceAnalyzer()
            previewTextureViewRef = null
            captureSession?.close()
            captureSession = null
            repeatingRequestBuilder = null
            repeatingCaptureCallback = null
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
            recorderSurface = null
            _engineState.value = CameraEngineState.Closed
            AppLogger.i(TAG, "Closed CameraDevice")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error closing camera", e)
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

            // Smart Focus: Automatically lock to optical infinity on recording start
            // to prevent windshield reflections, dust, or rain from hunting focus during drives.
            if (!_isInfinityFocusLocked.value) {
                _isInfinityFocusLocked.value = true
                AppLogger.i(TAG, "Auto-engaged infinity focus lock for video recording session")
            }

            if (cameraDevice == null) {
                openCamera()
            } else {
                restartSession()
            }

            // Start hardware media recorder after session configuration
            backgroundHandler?.postDelayed({
                try {
                    mediaRecorder?.start()
                    AppLogger.i(TAG, "Started MediaRecorder H.264 video recording to ${outputFile.absolutePath}")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to start MediaRecorder", e)
                }
            }, 300)

            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start video recording", e)
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

            AppLogger.i(TAG, "Stopped video recording. Total frames captured: $total")
            if (isPreviewActive) {
                restartSession()
            } else {
                closeCamera()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping MediaRecorder", e)
        }

        return total
    }

    fun release() {
        if (isRecordingVideo) {
            stopVideoRecording()
        }
        closeCamera()
        stopBackgroundThread()
    }
}
