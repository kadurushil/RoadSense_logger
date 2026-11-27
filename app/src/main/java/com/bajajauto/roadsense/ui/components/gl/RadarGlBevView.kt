package com.bajajauto.roadsense.ui.components.gl

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bajajauto.roadsense.DebugToggleSnapshot
import com.bajajauto.roadsense.diagnostics.TrackRecomposition
import com.bajajauto.roadsense.models.RadarFrame
import com.bajajauto.roadsense.ui.RadarViewModel
import java.util.concurrent.atomic.AtomicReference

/**
 * Cached runtime check for OpenGL ES 3.0 hardware support (`minSdk = 28` guarantees GLES 3.0 on
 * all supported devices, with safe fallback to Skia Canvas if an emulator or exotic GPU lacks it).
 */
@Volatile
private var cachedGles3Support: Boolean? = null

fun isOpenGlEs3Supported(context: Context): Boolean {
    cachedGles3Support?.let { return it }
    val supported = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
    } catch (_: Throwable) {
        false
    }
    cachedGles3Support = supported
    return supported
}

/**
 * Interactive OpenGL ES 3.0 `GLSurfaceView` handling 60 FPS touch orbit, 2-finger pan,
 * pinch-to-zoom, and double-tap reset directly on the native View layer with **0 Compose recompositions**.
 */
@SuppressLint("ViewConstructor")
private class InteractiveRadarGlSurfaceView(
    context: Context,
    private val cameraController: BevCameraController,
    private val onCameraModifiedChanged: (Boolean) -> Unit
) : GLSurfaceView(context) {

    @Volatile
    var maxRangeMeters: Float = 100f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastPointerCount = 0
    private var lastReportedModified = false
    private var ignoreDragUntilUp = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val updated = cameraController.onPinchZoom(detector.scaleFactor)
                notifyModifiedIfChanged(updated.isModifiedFromDefault)
                requestRender()
                return true
            }
        }
    )

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                ignoreDragUntilUp = true
                val updated = cameraController.resetView()
                notifyModifiedIfChanged(updated.isModifiedFromDefault)
                requestRender()
                return true
            }
        }
    )

    private fun notifyModifiedIfChanged(isModified: Boolean) {
        if (isModified != lastReportedModified) {
            lastReportedModified = isModified
            onCameraModifiedChanged(isModified)
        }
    }

    fun syncModifiedState() {
        notifyModifiedIfChanged(cameraController.state.isModifiedFromDefault)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        doubleTapDetector.onTouchEvent(event)

        val ptrCount = event.pointerCount
        var focusX = 0f
        var focusY = 0f
        for (i in 0 until ptrCount) {
            focusX += event.getX(i)
            focusY += event.getY(i)
        }
        if (ptrCount > 0) {
            focusX /= ptrCount
            focusY /= ptrCount
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = focusX
                lastTouchY = focusY
                lastPointerCount = ptrCount
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = focusX
                lastTouchY = focusY
                lastPointerCount = ptrCount
            }

            MotionEvent.ACTION_MOVE -> {
                if (!ignoreDragUntilUp && ptrCount == lastPointerCount && ptrCount > 0) {
                    val dx = focusX - lastTouchX
                    val dy = focusY - lastTouchY
                    if (dx != 0f || dy != 0f) {
                        val updated = if (ptrCount >= 2) {
                            cameraController.onTwoFingerPan(dx, dy, width.toFloat(), height.toFloat(), maxRangeMeters)
                        } else if (!scaleDetector.isInProgress) {
                            cameraController.onSingleFingerDrag(
                                dx,
                                dy,
                                width.toFloat(),
                                height.toFloat(),
                                maxRangeMeters
                            )
                        } else {
                            cameraController.state
                        }
                        notifyModifiedIfChanged(updated.isModifiedFromDefault)
                        requestRender()
                    }
                }
                lastTouchX = focusX
                lastTouchY = focusY
                lastPointerCount = ptrCount
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                lastPointerCount = 0
                ignoreDragUntilUp = false
            }
        }
        return true
    }
}

/**
 * Lifecycle-safe Jetpack Compose host for the native OpenGL ES 3.0 Radar BEV `GLSurfaceView`.
 *
 * Key architectural properties:
 * 1. **Zero Compose wakeups at 20–22.3 Hz:** Subscribes directly to `viewModel.displayPacer` via a
 *    lock-free listener and calls `glSurfaceView.requestRender()` under `RENDERMODE_WHEN_DIRTY`.
 * 2. **60 FPS Interactive 2D/3D Camera:** Pinch-zoom, 1-finger 3D orbit (or 2D pan), 2-finger 3D pan,
 *    and double-tap / button view reset run directly on `InteractiveRadarGlSurfaceView` + `GLThread`.
 * 3. **Mutual exclusion & clean teardown:** Switching to Skia Canvas or leaving the tab immediately
 *    unregisters the pacer listener and calls `glSurfaceView.onPause()` so `GLThread` sleeps at 0% CPU/GPU.
 * 4. **Dual-SurfaceView Z-order safety:** Uses `setZOrderMediaOverlay(true)` with 16-bit depth buffer
 *    (`8-8-8-8-16-0`) so it coexists cleanly beside Camera2's `AutoFitSurfaceView` while keeping all
 *    Compose dialogs, floating pills, and bookmark popups cleanly on top.
 */
@Composable
fun RadarGlBevView(
    viewModel: RadarViewModel,
    maxRangeMeters: Float,
    dynamicOnly: Boolean,
    minSnrFilter: Boolean,
    debugState: DebugToggleSnapshot,
    onGlInitError: () -> Unit,
    onCameraModifiedChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    TrackRecomposition("RadarBevGL")

    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraResetNonce by viewModel.bevCameraResetNonce.collectAsState()
    var lastHandledResetNonce by remember { mutableLongStateOf(cameraResetNonce) }
    val cameraPresetRequest by viewModel.bevCameraPresetRequest.collectAsState()
    var lastHandledPresetSeq by remember { mutableLongStateOf(cameraPresetRequest?.second ?: 0L) }
    val glViewRef = remember { AtomicReference<InteractiveRadarGlSurfaceView?>(null) }
    val cameraController = remember {
        BevCameraController(BevCameraState(is3D = debugState.bev3dMode))
    }
    val renderer = remember(cameraController) {
        RadarGlBevRenderer(
            cameraController = cameraController,
            onGlInitError = onGlInitError
        )
    }

    val currentConfig = BevRenderConfig(
        maxRangeMeters = maxRangeMeters,
        dynamicOnly = dynamicOnly,
        minSnrFilter = minSnrFilter,
        showGrid = debugState.bevShowGrid,
        showClusters = debugState.bevShowClusters,
        showRawPoints = debugState.bevShowRawPoints,
        showTracks = debugState.bevShowTracks,
        is3DMode = debugState.bev3dMode,
        trailEnabled = debugState.bevTrailEnabled,
        pointColorMode = debugState.bevPointColorMode,
        colorMap = debugState.bevColorMap,
        pointShape = debugState.bevPointShape,
        pointSizePreset = debugState.bevPointSizePreset,
        decayPreset = debugState.bevDecayPreset,
        stixelsEnabled = debugState.bevStixelsEnabled,
        showRoadLanes = debugState.bevShowRoadLanes,
        hideOutliers = debugState.bevHideOutliers,
        flipZ = debugState.bevFlipZ,
        enableTimingLog = debugState.masterDebugEnabled && debugState.canvasTimingLogs,
        enablePerfWindowLog = debugState.uiPerfProfilingLogs || debugState.masterDebugEnabled
    )

    // Handle Reset View button presses from RadarBevPlot or FullscreenSbsPreview
    LaunchedEffect(cameraResetNonce) {
        if (cameraResetNonce > lastHandledResetNonce) {
            lastHandledResetNonce = cameraResetNonce
            cameraController.resetView()
            glViewRef.get()?.let { view ->
                view.syncModifiedState()
                view.requestRender()
            }
        }
    }

    // Handle Foxglove 3D quick camera presets (Chase / Iso / Top / Low)
    LaunchedEffect(cameraPresetRequest) {
        val req = cameraPresetRequest ?: return@LaunchedEffect
        if (req.second > lastHandledPresetSeq) {
            lastHandledPresetSeq = req.second
            cameraController.applyPreset(req.first)
            glViewRef.get()?.let { view ->
                view.syncModifiedState()
                view.requestRender()
            }
        }
    }

    // Push config updates lock-free and trigger a single dirty render when controls change
    SideEffect {
        renderer.updateConfig(currentConfig)
        glViewRef.get()?.let { view ->
            view.maxRangeMeters = maxRangeMeters
            view.syncModifiedState()
            view.requestRender()
        }
    }

    // Bind directly to RadarDisplayPacer + Activity lifecycle; clean teardown on unmount/toggle
    DisposableEffect(lifecycleOwner, viewModel.displayPacer, renderer) {
        val frameListener: (RadarFrame?) -> Unit = { frame ->
            renderer.updateFrame(frame)
            glViewRef.get()?.requestRender()
        }
        viewModel.displayPacer.addFrameListener(frameListener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            val view = glViewRef.get() ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view.onResume()
                    view.requestRender()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    view.onPause()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            viewModel.displayPacer.removeFrameListener(frameListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            val view = glViewRef.getAndSet(null)
            try {
                view?.onPause()
            } catch (_: Throwable) {
                // Ignore teardown race if surface already detached
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            InteractiveRadarGlSurfaceView(
                context = ctx,
                cameraController = cameraController,
                onCameraModifiedChanged = onCameraModifiedChanged
            ).apply {
                this.maxRangeMeters = currentConfig.maxRangeMeters
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                preserveEGLContextOnPause = true
                setZOrderMediaOverlay(true)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                glViewRef.set(this)
                renderer.updateFrame(viewModel.displayPacer.latestFrame.value)
                requestRender()
            }
        },
        update = { view ->
            glViewRef.set(view)
            view.maxRangeMeters = maxRangeMeters
            view.requestRender()
        },
        onRelease = { view ->
            glViewRef.compareAndSet(view, null)
            try {
                view.onPause()
            } catch (_: Throwable) {
                // Ignore teardown race
            }
        },
        modifier = modifier
    )
}
