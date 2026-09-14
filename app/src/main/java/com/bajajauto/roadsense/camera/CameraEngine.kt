package com.bajajauto.roadsense.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.bajajauto.roadsense.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

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

                    val info = CameraDeviceInfo(
                        id = id,
                        displayName = name,
                        facing = facing,
                        focalLengthMm = focal,
                        isUltraWide = isUltra
                    )
                    list.add(info)
                    AppLogger.i(TAG, "Discovered Camera: id=$id, name=$name, facing=$facing, focal=${focal}mm, ultraWide=$isUltra")
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
                AppLogger.i(TAG, "Selected default camera: ${defaultCam?.displayName} (id=${defaultCam?.id})")
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

        if (cameraDevice != null) {
            closeCamera()
            if (isPreviewActive) {
                openCamera()
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
        previewSurfaceTexture = surfaceTexture
        currentViewWidth = width
        currentViewHeight = height
        val res = _selectedResolution.value
        surfaceTexture.setDefaultBufferSize(res.width, res.height)
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
     * Updates display rotation and recalculates matrix transformation on the TextureView.
     */
    fun updateDisplayRotation(rotation: Int, textureView: TextureView? = null, width: Int = 0, height: Int = 0) {
        currentDisplayRotation = rotation
        if (width > 0) currentViewWidth = width
        if (height > 0) currentViewHeight = height

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
                AppLogger.d(TAG, "Applied AF_MODE_OFF with lens focus distance = 0.0f (Infinity)")
            }
        } else {
            // Default Continuous Auto-Focus for video/preview
            val preferredAfMode = when {
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> CameraMetadata.CONTROL_AF_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, preferredAfMode)
            AppLogger.d(TAG, "Applied AF mode: $preferredAfMode")
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
        AppLogger.i(TAG, "Infinity focus lock set to: $locked")

        val session = captureSession
        val builder = repeatingRequestBuilder
        val callback = repeatingCaptureCallback
        if (session != null && builder != null) {
            try {
                applyFocusSettings(builder)
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
     * rectangle is placed at the corresponding sensor coordinates. Otherwise, centers on frame.
     * If infinity focus was locked, this automatically unlocks it to allow refocusing.
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
            val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
            val chars = try { cameraManager.getCameraCharacteristics(targetCameraId) } catch (e: Exception) { null }
            val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxAfRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAeRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

            // Set continuous video or auto mode
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

            if (activeArray != null && (maxAfRegions > 0 || maxAeRegions > 0)) {
                val focusX = (normX ?: 0.5f).coerceIn(0f, 1f)
                val focusY = (normY ?: 0.5f).coerceIn(0f, 1f)

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

                if (maxAfRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRegion))
                }
                if (maxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRegion))
                }
                AppLogger.i(TAG, "Setting AF/AE metering region: $afRect (norm: $focusX, $focusY)")
            }

            // To ensure the lens motor visibly breaks out of a locked or settled state,
            // we first momentarily set manual focus to kick the motor away from infinity,
            // then cancel and trigger a fresh AF sweep.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 5.0f) // ~0.2 meters (near focus)
            session.capture(builder.build(), null, backgroundHandler)

            // Switch to AF AUTO mode with AF_TRIGGER_CANCEL to reset the AF state machine
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            // Trigger active AF scan
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    AppLogger.i(TAG, "AF Trigger completed: afState=$afState, focusDist=$focusDist")
                }
            }, backgroundHandler)

            // Settle repeating request back into CONTINUOUS_VIDEO with IDLE trigger
            backgroundHandler?.postDelayed({
                try {
                    val currentSession = captureSession
                    val currentBuilder = repeatingRequestBuilder
                    if (currentSession != null && currentBuilder != null && !_isInfinityFocusLocked.value) {
                        currentBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        currentBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                        currentSession.setRepeatingRequest(currentBuilder.build(), callback, backgroundHandler)
                        AppLogger.i(TAG, "Resumed CONTINUOUS_VIDEO repeating request after refocus sweep")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error resuming repeating request after AF sweep", e)
                }
            }, 800)
            AppLogger.i(TAG, "Auto-focus cycle successfully triggered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger auto-focus", e)
        }
    }

    fun closeCamera() {
        try {
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
