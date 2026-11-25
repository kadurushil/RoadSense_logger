package com.bajajauto.roadsense.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
    private var captureSyncThread: HandlerThread? = null
    private var captureSyncHandler: Handler? = null
    @Volatile
    private var isMediaRecorderActive: Boolean = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null

    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    private var repeatingRequestBuilder: CaptureRequest.Builder? = null
    private var repeatingCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    @Volatile
    private var isRepeatingActive: Boolean = false

    @Volatile
    private var captureSessionGeneration: Long = 0L

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

    var onRecorderErrorListener: ((String) -> Unit)? = null

    // Hardware Static Road Auto-Exposure State (Option A: 0% CPU/GPU)
    private val _roadAeMode = MutableStateFlow(RoadAeMode.FULL_MATRIX)
    val roadAeMode: StateFlow<RoadAeMode> = _roadAeMode.asStateFlow()

    private val _roadAeState = MutableStateFlow(RoadAeState.BALANCED)
    val roadAeState: StateFlow<RoadAeState> = _roadAeState.asStateFlow()

    private val _skyLuminance = MutableStateFlow(128f)
    val skyLuminance: StateFlow<Float> = _skyLuminance.asStateFlow()

    private val _roadLuminance = MutableStateFlow(128f)
    val roadLuminance: StateFlow<Float> = _roadLuminance.asStateFlow()

    private val _contrastRatio = MutableStateFlow(1.0f)
    val contrastRatio: StateFlow<Float> = _contrastRatio.asStateFlow()

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
    private var isPendingVideoRecording = false

    @Volatile
    private var isPreviewActive = false

    @Volatile
    private var isWarmingUpForRecording = false
    @Volatile
    private var warmupFramesRemaining = 0
    private var warmupImageReader: ImageReader? = null
    private var warmupCallback: ((Boolean) -> Unit)? = null
    private var warmupTimeoutRunnable: Runnable? = null
    private val warmupLock = Any()

    /**
     * Returns true when the Camera2 pipeline is already open, repeating, and delivering
     * steady shutter callbacks so recording can start immediately without a warmup wait.
     */
    fun isWarmForRecording(): Boolean {
        return cameraDevice != null && isRepeatingActive && _totalFramesLogged.value >= 3L
    }

    /**
     * Pre-warms the Camera2 HAL / ISP before calling [startVideoRecording] so exposure,
     * white balance, and the 30 FPS sensor clock settle before frame #0 of the MP4 begins.
     * Uses the live preview surface if active, or a lightweight 32x32 draining [ImageReader]
     * when the viewfinder is muted. Bounded by [timeoutMs] (default 2000 ms).
     */
    fun warmUpForRecording(
        targetFrames: Int = 5,
        timeoutMs: Long = 2000L,
        onWarm: (Boolean) -> Unit
    ) {
        if (!hasCameraPermission()) {
            onWarm(false)
            return
        }
        if (isWarmForRecording() || isRecordingVideo || isPendingVideoRecording) {
            onWarm(true)
            return
        }

        val timeoutTask = Runnable {
            completeWarmup(success = false, reason = "timeout(${timeoutMs}ms)")
        }

        synchronized(warmupLock) {
            warmupTimeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
            warmupTimeoutRunnable = timeoutTask
            warmupCallback = onWarm
            warmupFramesRemaining = targetFrames.coerceAtLeast(1)
            isWarmingUpForRecording = true

            if (previewSurface == null || previewSurface?.isValid != true) {
                releaseWarmupReaderLocked()
                warmupImageReader = ImageReader.newInstance(32, 32, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        try {
                            reader.acquireLatestImage()?.close()
                        } catch (_: Exception) {}
                    }, backgroundHandler)
                }
            }
        }

        backgroundHandler?.postDelayed(timeoutTask, timeoutMs)
        AppLogger.i(TAG, "Starting camera pre-record warmup (targetFrames=$targetFrames, timeoutMs=$timeoutMs, hasPreview=${previewSurface?.isValid == true})")

        backgroundHandler?.post {
            if (!isWarmingUpForRecording) return@post
            if (cameraDevice == null) {
                openCamera()
            } else if (!isRepeatingActive) {
                if (previewSurface?.isValid == true && captureSession != null && repeatingRequestBuilder != null) {
                    resumeRepeatingPreviewIfNeeded()
                } else {
                    restartSession()
                }
            }
        }
    }

    fun cancelWarmup() {
        completeWarmup(success = false, reason = "cancelled")
        backgroundHandler?.post {
            synchronized(warmupLock) {
                releaseWarmupReaderLocked()
            }
            if (!isPreviewActive && !isRecordingVideo && !isPendingVideoRecording) {
                closeCamera()
            }
        }
    }

    private fun completeWarmup(success: Boolean, reason: String) {
        val cb: ((Boolean) -> Unit)?
        synchronized(warmupLock) {
            if (!isWarmingUpForRecording && warmupCallback == null) return
            isWarmingUpForRecording = false
            warmupFramesRemaining = 0
            warmupTimeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
            warmupTimeoutRunnable = null
            cb = warmupCallback
            warmupCallback = null
        }
        AppLogger.i(TAG, "Camera pre-record warmup finished (success=$success, reason=$reason)")
        cb?.invoke(success)
    }

    private fun releaseWarmupReaderLocked() {
        try {
            warmupImageReader?.close()
        } catch (_: Exception) {}
        warmupImageReader = null
    }

    /**
     * Kept as a no-op for API compatibility.
     * Pre-allocating an unconsumed `SurfaceTexture(false)` caused the BufferQueue to stall after
     * 3 buffers while keeping the Camera HAL / ISP active in the background when preview was muted.
     */
    fun prewarmCamera() {
        AppLogger.d(TAG, "prewarmCamera() skipped: camera opens on-demand when preview or recording is active")
    }

    @Volatile
    var currentDisplayRotation: Int = Surface.ROTATION_0
        private set

    private var currentViewWidth: Int = 0
    private var currentViewHeight: Int = 0

    private var videoOutputFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    init {
        startBackgroundThread()
        discoverCameras()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraEngine-Worker", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            backgroundHandler = Handler(looper)
        }
        captureSyncThread = HandlerThread("CameraEngine-CaptureSync", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            captureSyncHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        captureSyncThread?.quitSafely()
        backgroundThread?.quitSafely()
        try {
            captureSyncThread?.join()
            captureSyncThread = null
            captureSyncHandler = null
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            AppLogger.e(TAG, "Error stopping camera background threads", e)
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

            // 2. Discover physical multi-camera IDs if supported by vendor HAL (Android 9+ / API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (id in standardIds) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val physicalIds = chars.physicalCameraIds
                        if (physicalIds.isNotEmpty()) {
                            AppLogger.i(
                                TAG,
                                "Logical camera $id exposes physical multi-cameras: ${physicalIds.joinToString()}"
                            )
                            visitedIds.addAll(physicalIds)
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // 3. Inspect characteristics in a single pass without redundant Binder roundtrips
            for (id in visitedIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val focal = focalLengths?.firstOrNull() ?: 0f

                    val isUltra = (facing == CameraCharacteristics.LENS_FACING_BACK && focal > 0f && focal <= 2.6f)

                    val name = when {
                        isUltra -> "Ultra-Wide (${String.format(Locale.US, "%.1f", focal)}mm)"
                        facing == CameraCharacteristics.LENS_FACING_BACK -> "Main Wide (${
                            String.format(
                                Locale.US,
                                "%.1f",
                                focal
                            )
                        }mm)"

                        facing == CameraCharacteristics.LENS_FACING_FRONT -> "Front (${
                            String.format(
                                Locale.US,
                                "%.1f",
                                focal
                            )
                        }mm)"

                        else -> "Camera $id"
                    }

                    val oisModes =
                        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
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
                    AppLogger.i(
                        TAG,
                        "Discovered Camera: id=$id, name=$name, facing=$facing, focal=${focal}mm, ultraWide=$isUltra, supportsOis=$hasOis"
                    )
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Skipping camera candidate $id: ${e.message}")
                }
            }

            _availableCameras.value = list

            if (_selectedCamera.value == null) {
                val defaultCam = list.firstOrNull { it.isBackFacing && !it.isUltraWide }
                    ?: list.firstOrNull { it.isBackFacing }
                    ?: list.firstOrNull()
                _selectedCamera.value = defaultCam
                _isOisSupported.value = defaultCam?.supportsOis == true
                AppLogger.i(
                    TAG,
                    "Selected default camera: ${defaultCam?.displayName} (id=${defaultCam?.id}), OIS supported: ${defaultCam?.supportsOis}"
                )
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

        AppLogger.i(
            TAG,
            "Switching camera from ${_selectedCamera.value?.displayName} to ${deviceInfo.displayName} (id=${deviceInfo.id})"
        )
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
            if (cameraDevice != null && !isRecordingVideo && !isPendingVideoRecording) {
                restartSession()
            }
        }
    }

    fun setFrameRate(fps: CameraFrameRate) {
        if (_selectedFps.value != fps) {
            _selectedFps.value = fps
            AppLogger.i(TAG, "Frame rate changed to: ${fps.label}")
            if (cameraDevice != null && !isRecordingVideo && !isPendingVideoRecording) {
                restartSession()
            }
        }
    }

    val persistentSurfaceTexture: SurfaceTexture?
        get() = previewSurfaceTexture

    /**
     * Resumes the repeating preview capture request on `backgroundHandler` when re-attaching
     * a persistent `SurfaceTexture` that was paused when switching away from the Camera/SBS tab.
     */
    private fun resumeRepeatingPreviewIfNeeded() {
        if (isRepeatingActive) return
        val session = captureSession ?: return
        val builder = repeatingRequestBuilder ?: return
        val callback = repeatingCaptureCallback ?: return
        val postStartNs = System.nanoTime()
        backgroundHandler?.post {
            if (!isPreviewActive && !isRecordingVideo && !isPendingVideoRecording && !isWarmingUpForRecording) return@post
            try {
                session.setRepeatingRequest(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
                isRepeatingActive = true
                if (!isRecordingVideo) {
                    _engineState.value = CameraEngineState.Previewing(
                        resolution = _selectedResolution.value,
                        fps = _selectedFps.value.fps
                    )
                }
                val resumeMs = (System.nanoTime() - postStartNs) / 1_000_000.0
                AppLogger.i(
                    TAG,
                    "Resumed repeating capture request on persistent SurfaceView in ${
                        String.format(
                            Locale.US,
                            "%.2f",
                            resumeMs
                        )
                    } ms (restarts=0)"
                )
            } catch (e: Exception) {
                AppLogger.w(
                    TAG,
                    "Could not resume repeating request on persistent surface; restarting session: ${e.message}"
                )
                restartSession()
            }
        }
    }

    /**
     * Attaches the persistent [AutoFitSurfaceView] Surface from [MainActivity].
     * Because the single SurfaceView never leaves the window, tab and fullscreen switches reuse
     * the exact same Surface with 0 CameraCaptureSession restarts and 0 dropped video frames.
     */
    fun attachPreviewSurface(surface: Surface, width: Int = 0, height: Int = 0) {
        if (!surface.isValid) return

        if (previewSurface === surface && captureSession != null && cameraDevice != null && _engineState.value !is CameraEngineState.Error) {
            currentViewWidth = width
            currentViewHeight = height
            if (!isPreviewActive || !isRepeatingActive ||
                _engineState.value is CameraEngineState.Closed ||
                _engineState.value is CameraEngineState.Standby
            ) {
                isPreviewActive = true
                resumeRepeatingPreviewIfNeeded()
            }
            return
        }

        previewSurface = surface
        currentViewWidth = width
        currentViewHeight = height
        isPreviewActive = true
        val res = _selectedResolution.value

        AppLogger.i(
            TAG,
            "Attached persistent SurfaceView surface: ${res.width}x${res.height}, view size: ${width}x${height}"
        )

        if (cameraDevice != null) {
            restartSession()
        } else {
            openCamera()
        }
    }

    /**
     * Pauses or resumes preview repeating requests when switching to/from non-camera tabs
     * without destroying the persistent SurfaceView Surface.
     */
    fun setPreviewPaused(paused: Boolean) {
        if (paused) {
            if (!isPreviewActive) return
            isPreviewActive = false
            if (!isRecordingVideo && !isPendingVideoRecording && !isWarmingUpForRecording) {
                backgroundHandler?.post {
                    if (!isPreviewActive && !isRecordingVideo && !isPendingVideoRecording && !isWarmingUpForRecording && isRepeatingActive) {
                        try {
                            captureSession?.stopRepeating()
                            isRepeatingActive = false
                            _engineState.value =
                                if (cameraDevice != null) CameraEngineState.Standby else CameraEngineState.Closed
                            AppLogger.i(
                                TAG,
                                "Paused CameraCaptureSession repeating request (ISP idle while off-tab/muted)"
                            )
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Could not pause repeating request on park: ${e.message}")
                        }
                    }
                }
            } else {
                AppLogger.i(
                    TAG,
                    "Preserving active CameraCaptureSession repeating request for 0-drop recording while parked"
                )
            }
        } else {
            if (isPreviewActive) return
            isPreviewActive = true
            resumeRepeatingPreviewIfNeeded()
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
        val matrix = CameraViewportTransform.configureTransformMatrix(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            streamWidth = res.width,
            streamHeight = res.height,
            displayRotation = displayRotation
        )

        textureView.setTransform(matrix)
        AppLogger.d(
            TAG,
            "configureTransform (Standard FIT): view=${viewWidth}x${viewHeight}, buf=${res.width}x${res.height}, rot=$displayRotation"
        )
    }

    /**
     * Detaches preview surface when the user swipes away to another tab or mutes preview.
     * Preserves persistent SurfaceTexture, CameraDevice, and CameraCaptureSession for instant (<16ms)
     * re-attachment, while pausing the 30 FPS repeating request when not recording so the Camera ISP
     * consumes 0% CPU when off-tab or muted!
     */
    fun detachPreviewSurface(surface: Surface? = null) {
        if (surface != null && previewSurface != null && previewSurface !== surface) {
            AppLogger.d(TAG, "Ignoring detachPreviewSurface for stale Surface")
            return
        }
        val hadPreviewSurface = previewSurface != null
        AppLogger.i(TAG, "Detaching preview surface (SurfaceHolder destroyed)")
        isPreviewActive = false
        previewSurface = null

        if (isRecordingVideo || isPendingVideoRecording) {
            // Drop destroyed SurfaceView target immediately so BufferQueue stalls don't freeze MediaRecorder in background/rotation
            if (hadPreviewSurface && cameraDevice != null) {
                AppLogger.i(TAG, "Preview surface destroyed mid-recording; reconfiguring session to recorderSurface only")
                restartSession()
            }
        } else {
            backgroundHandler?.post {
                if (!isPreviewActive && !isRecordingVideo && !isPendingVideoRecording && isRepeatingActive) {
                    try {
                        captureSession?.stopRepeating()
                        isRepeatingActive = false
                        _engineState.value =
                            if (cameraDevice != null) CameraEngineState.Standby else CameraEngineState.Closed
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Could not pause repeating request on detach: ${e.message}")
                    }
                }
            }
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

        if (cameraDevice != null || _engineState.value is CameraEngineState.Opening) {
            return
        }

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
                    if (!isPreviewActive && !isRecordingVideo && !isPendingVideoRecording && !isWarmingUpForRecording) {
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
                    isRepeatingActive = false
                    camera.close()
                    cameraDevice = null
                    _engineState.value = CameraEngineState.Closed
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    AppLogger.e(TAG, "CameraDevice onError: id=${camera.id}, code=$error")
                    isRepeatingActive = false
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

        val targetId = _selectedCamera.value?.id ?: "0"
        val chars = try {
            cameraManager.getCameraCharacteristics(targetId)
        } catch (e: Exception) {
            null
        }
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

        AppLogger.i(
            TAG,
            "MediaRecorder setup: ${res.width}x${res.height} @ ${fps}fps, bitrate=${bitrate}, orientationHint=${rotationHint}deg"
        )

        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(res.width, res.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOrientationHint(rotationHint)
            setOnErrorListener { _, what, extra ->
                val msg = "MediaRecorder error what=$what extra=$extra"
                AppLogger.e(TAG, msg)
                isRecordingVideo = false
                isPendingVideoRecording = false
                isMediaRecorderActive = false
                _engineState.value = CameraEngineState.Error(msg)
                onRecorderErrorListener?.invoke(msg)
            }
            setOnInfoListener { _, what, extra ->
                AppLogger.w(TAG, "MediaRecorder info what=$what extra=$extra")
            }
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
        val activeWarmupSurface = if (isWarmingUpForRecording && !isRecordingVideo && !isPendingVideoRecording && (previewSurface == null || previewSurface?.isValid != true)) {
            warmupImageReader?.surface
        } else {
            null
        }

        previewSurface?.takeIf { it.isValid }?.let { surfaces.add(it) }
        activeWarmupSurface?.let { surfaces.add(it) }
        recorderSurface?.let { surfaces.add(it) }

        if (surfaces.isEmpty()) {
            AppLogger.i(TAG, "No surfaces attached yet; waiting for preview or recording")
            return
        }

        try {
            val sessionGen = ++captureSessionGeneration
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null || sessionGen != captureSessionGeneration) {
                        AppLogger.d(
                            TAG,
                            "Ignoring stale CameraCaptureSession onConfigured (gen=$sessionGen, current=$captureSessionGeneration)"
                        )
                        try {
                            session.close()
                        } catch (_: Exception) {}
                        return
                    }
                    captureSession = session

                    try {
                        val isRecSession = isRecordingVideo || isPendingVideoRecording
                        if (isPendingVideoRecording && mediaRecorder != null) {
                            try {
                                mediaRecorder?.start()
                                isMediaRecorderActive = true
                                isRecordingVideo = true
                                isPendingVideoRecording = false
                                AppLogger.i(TAG, "MediaRecorder started synchronously inside onConfigured")
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed to start MediaRecorder in onConfigured", e)
                            }
                        }

                        val requestBuilder = device.createCaptureRequest(
                            if (isRecSession) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        )

                        previewSurface?.takeIf { it.isValid }?.let { requestBuilder.addTarget(it) }
                        activeWarmupSurface?.let { requestBuilder.addTarget(it) }
                        recorderSurface?.let { requestBuilder.addTarget(it) }

                        val targetFps = _selectedFps.value.fps
                        requestBuilder.set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(targetFps, targetFps)
                        )
                        requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                        applyFocusSettings(requestBuilder)
                        applyStabilizationSettings(requestBuilder)
                        applyAeMeteringSettings(requestBuilder)

                        repeatingRequestBuilder = requestBuilder

                        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureStarted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                timestamp: Long,
                                frameNumber: Long
                            ) {
                                if (session !== captureSession) return
                                val isRec = isRecordingVideo && isMediaRecorderActive
                                val meta = CameraFrameMetadata(
                                    frameNumber = frameNumber,
                                    shutterTimestampNs = timestamp,
                                    utcTimestampMs = System.currentTimeMillis(),
                                    isRecording = isRec
                                )
                                _totalFramesLogged.value++
                                if (isWarmingUpForRecording) {
                                    val done = synchronized(warmupLock) {
                                        if (isWarmingUpForRecording) {
                                            warmupFramesRemaining--
                                            warmupFramesRemaining <= 0
                                        } else {
                                            false
                                        }
                                    }
                                    if (done) {
                                        completeWarmup(success = true, reason = "shutter_frames_settled")
                                    }
                                }
                                frameListeners.forEach { it.invoke(meta) }
                            }
                        }

                        repeatingCaptureCallback = captureCallback
                        session.setRepeatingRequest(
                            requestBuilder.build(),
                            captureCallback,
                            captureSyncHandler ?: backgroundHandler
                        )
                        isRepeatingActive = true

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
                    if (sessionGen != captureSessionGeneration) {
                        try {
                            session.close()
                        } catch (_: Exception) {}
                        return
                    }
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
            isRepeatingActive = false
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
        val chars = try {
            cameraManager.getCameraCharacteristics(targetCameraId)
        } catch (e: Exception) {
            null
        }
        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        if (_isInfinityFocusLocked.value) {
            if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                AppLogger.d(TAG, "Applied AF_MODE_OFF with lens focus distance = 0.0f (Infinity)")
            }
        } else if (_isAfAeLocked.value && currentMeteringRegion != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(currentMeteringRegion))
            AppLogger.d(TAG, "Applied AF Lock on region: $currentMeteringRegion")
        } else {
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

        val left =
            (activeArray.left + sMinX * activeArray.width()).toInt().coerceIn(activeArray.left, activeArray.right)
        val right =
            (activeArray.left + sMaxX * activeArray.width()).toInt().coerceIn(activeArray.left, activeArray.right)
        val top = (activeArray.top + sMinY * activeArray.height()).toInt().coerceIn(activeArray.top, activeArray.bottom)
        val bottom =
            (activeArray.top + sMaxY * activeArray.height()).toInt().coerceIn(activeArray.top, activeArray.bottom)

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
     * Option A Static Hardware Road AE:
     * Applies static Camera2 CONTROL_AE_REGIONS restricted to the bottom 65% road zone
     * once at session configure or manual mode toggle (0% CPU, 0% GPU, 0 mid-drive HAL calls).
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
            _roadAeState.value = RoadAeState.BALANCED
            AppLogger.d(TAG, "Applied FULL_MATRIX AE (regions=null, ev=0)")
            return
        }

        val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
        val chars = try {
            cameraManager.getCameraCharacteristics(targetCameraId)
        } catch (e: Exception) {
            null
        }
        val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxAeRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val effectiveRotation = getEffectiveSensorRotation(chars)

        if (activeArray != null && maxAeRegions > 0) {
            val roadMetering = createRoadMeteringRectangle(activeArray, effectiveRotation)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(roadMetering))
            AppLogger.i(
                TAG,
                "Applied AUTO_ROAD AE region=${roadMetering.rect} (active=$activeArray, rot=$effectiveRotation, maxAeRegions=$maxAeRegions)"
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            AppLogger.w(
                TAG,
                "AUTO_ROAD fallback to FULL_MATRIX (activeArray=${activeArray != null}, maxAeRegions=$maxAeRegions)"
            )
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        _roadAeState.value = RoadAeState.BALANCED
    }

    private fun updateRepeatingAeSettings() {
        if (!isRepeatingActive) return
        val session = captureSession ?: return
        val builder = repeatingRequestBuilder ?: return
        val callback = repeatingCaptureCallback ?: return

        try {
            applyAeMeteringSettings(builder)
            session.setRepeatingRequest(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update repeating AE settings", e)
        }
    }

    fun setRoadAeMode(mode: RoadAeMode) {
        if (_roadAeMode.value == mode) return
        _roadAeMode.value = mode
        AppLogger.i(TAG, "Road AE mode changed to: $mode")
        updateRepeatingAeSettings()
    }

    /**
     * Applies optical image stabilization (OIS) settings to a CaptureRequest.Builder.
     */
    private fun applyStabilizationSettings(builder: CaptureRequest.Builder) {
        val targetCameraId = _selectedCamera.value?.id ?: getBackCameraId() ?: "0"
        val chars = try {
            cameraManager.getCameraCharacteristics(targetCameraId)
        } catch (e: Exception) {
            null
        }
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

        if (!isRepeatingActive) return
        val session = captureSession
        val builder = repeatingRequestBuilder
        val callback = repeatingCaptureCallback
        if (session != null && builder != null) {
            try {
                applyStabilizationSettings(builder)
                session.setRepeatingRequest(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
                AppLogger.i(TAG, "Updated repeating request with OIS = $enabled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply OIS setting to repeating request", e)
            }
        }
    }

    /**
     * Toggles or sets optical infinity focus lock.
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

        if (!isRepeatingActive) return
        val session = captureSession
        val builder = repeatingRequestBuilder
        val callback = repeatingCaptureCallback
        if (session != null && builder != null) {
            try {
                applyFocusSettings(builder)
                applyAeMeteringSettings(builder)
                session.setRepeatingRequest(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
                AppLogger.i(TAG, "Updated repeating request with infinity lock = $locked")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to apply infinity focus to repeating request", e)
            }
        }
    }

    /**
     * Triggers an explicit Auto-Focus cycle.
     */
    fun triggerAutoFocus(normX: Float? = null, normY: Float? = null) {
        if (_isInfinityFocusLocked.value) {
            _isInfinityFocusLocked.value = false
            AppLogger.i(TAG, "Infinity focus unlocked due to re-focus trigger")
        }

        if (!isRepeatingActive) return
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
            val chars = try {
                cameraManager.getCameraCharacteristics(targetCameraId)
            } catch (e: Exception) {
                null
            }
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
                val isFacingFront =
                    chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

                val effectiveRotation = if (isFacingFront) {
                    (sensorOrientation + rotationDegrees) % 360
                } else {
                    (sensorOrientation - rotationDegrees + 360) % 360
                }

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
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                AppLogger.i(TAG, "Setting AF/AE metering region for tap lock: $afRect (norm: $focusX, $focusY)")

                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
                session.setRepeatingRequest(builder.build(), callback, captureSyncHandler ?: backgroundHandler)

                if (callback != null) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    builder.set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                    )
                    session.capture(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    builder.set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                    )
                }
                AppLogger.i(TAG, "Triggered in-stream tap AF/AE scan at ($focusX, $focusY)")

                backgroundHandler?.postDelayed({
                    try {
                        val currentSession = captureSession
                        val currentBuilder = repeatingRequestBuilder
                        if (currentSession != null && currentBuilder != null && _isAfAeLocked.value && isRepeatingActive) {
                            currentBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                            currentBuilder.set(
                                CaptureRequest.CONTROL_AF_TRIGGER,
                                CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                            )
                            currentBuilder.set(
                                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                            )
                            if (maxAfRegions > 0) currentBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                            if (maxAeRegions > 0) currentBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                            currentBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                            currentSession.setRepeatingRequest(
                                currentBuilder.build(),
                                callback,
                                captureSyncHandler ?: backgroundHandler
                            )
                            AppLogger.i(TAG, "Locked AF and AE at tap point ($focusX, $focusY)")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error locking repeating request after tap AF/AE", e)
                    }
                }, 700)
            } else {
                _isAfAeLocked.value = false
                _tapFocusPoint.value = null
                currentMeteringRegion = null

                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                applyAeMeteringSettings(builder)

                if (callback != null) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                    session.capture(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
                }

                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), callback, captureSyncHandler ?: backgroundHandler)
                AppLogger.i(TAG, "Resumed CONTINUOUS_VIDEO repeating request on Re-Focus")
            }
            AppLogger.i(TAG, "Auto-focus cycle successfully triggered (isTapLock=$isTapLock)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger auto-focus", e)
        }
    }

    fun closeCamera() {
        try {
            captureSessionGeneration++
            isRepeatingActive = false
            completeWarmup(success = false, reason = "camera_closed")
            captureSession?.close()
            captureSession = null
            repeatingRequestBuilder = null
            repeatingCaptureCallback = null
            cameraDevice?.close()
            cameraDevice = null
            synchronized(warmupLock) {
                releaseWarmupReaderLocked()
            }
            mediaRecorder?.release()
            mediaRecorder = null
            recorderSurface = null
            if (previewSurfaceTexture != null) {
                previewSurface?.release()
                previewSurfaceTexture?.release()
                previewSurfaceTexture = null
            }
            previewSurface = null
            _engineState.value = CameraEngineState.Closed
            AppLogger.i(TAG, "Closed CameraDevice and released preview surfaces")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error closing camera", e)
        }
    }

    /**
     * Starts video recording directly to the specified MP4 file.
     */
    fun startVideoRecording(outputFile: File): Boolean {
        if (isRecordingVideo || isPendingVideoRecording) return true

        try {
            videoOutputFile = outputFile
            setupMediaRecorder(outputFile)
            isPendingVideoRecording = true
            isRecordingVideo = false
            isMediaRecorderActive = false
            recordingStartTimeMs = SystemClock.elapsedRealtime()
            _totalFramesLogged.value = 0L

            if (!_isInfinityFocusLocked.value) {
                _isInfinityFocusLocked.value = true
                AppLogger.i(TAG, "Auto-engaged infinity focus lock for video recording session")
            }

            // Finish any pending warmup state before reconfiguring to MediaRecorder surface
            completeWarmup(success = true, reason = "recording_starting")

            if (cameraDevice == null) {
                synchronized(warmupLock) { releaseWarmupReaderLocked() }
                openCamera()
            } else {
                restartSession()
                synchronized(warmupLock) { releaseWarmupReaderLocked() }
            }

            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start video recording", e)
            isRecordingVideo = false
            isPendingVideoRecording = false
            isMediaRecorderActive = false
            return false
        }
    }

    /**
     * Stops video recording and always releases MediaRecorder & reconfigures/closes CameraDevice,
     * even if `MediaRecorder.stop()` throws (e.g. accidental < 1s stop before frame #1).
     */
    fun stopVideoRecording(): Long {
        if (!isRecordingVideo && !isPendingVideoRecording) return 0L

        isRecordingVideo = false
        isPendingVideoRecording = false
        isMediaRecorderActive = false
        val total = _totalFramesLogged.value

        try {
            mediaRecorder?.stop()
            AppLogger.i(TAG, "Stopped video recording. Total frames captured: $total")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping MediaRecorder (frames=$total) — cleaning up resources safely", e)
        } finally {
            try {
                mediaRecorder?.reset()
            } catch (_: Exception) {}
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            recorderSurface = null

            try {
                if (isPreviewActive && previewSurface != null && previewSurface?.isValid == true) {
                    restartSession()
                } else {
                    closeCamera()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error restoring camera after MediaRecorder stop", e)
            }
        }

        return total
    }

    fun release() {
        if (isRecordingVideo || isPendingVideoRecording) {
            stopVideoRecording()
        }
        closeCamera()
        stopBackgroundThread()
    }
}
