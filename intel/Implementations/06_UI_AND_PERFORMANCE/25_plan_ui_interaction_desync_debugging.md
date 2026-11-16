# Implementation Plan: Debugging & Resolving UI-Induced Desync and Frame Drops

## Executive Summary: What Exactly Are We Fixing?

To understand why touching or swiping the screen caused the camera to freeze for half a second (~500 ms) and create synchronization spikes, here is an intuitive breakdown:

---

### The Plain-English Explanation (The Movie Camera & TV Analogy)

Imagine the phone's camera sensor is a **movie camera** that outputs its video feed to two separate places simultaneously:
1. **The Recording Tape (`MediaRecorder`):** Which saves the video file and timestamp logs to disk.
2. **The Living Room TV (`TextureView` preview):** Which shows the live viewfinder on your phone screen so you can see what it's pointing at.

#### The Problem:
* In the current code, whenever you **swipe to another tab** (like the Radar tab) or **toggle Fullscreen**, the phone thinks: *"The user isn't looking at the small TV screen anymore, let me destroy the TV connection to save battery."*
* But instead of simply leaving the recording tape running in the background, the current code **completely reboots the entire movie camera hardware** (`restartSession()`)!
* It takes the camera hardware **300 to 600 milliseconds** to shut down, re-initialize its lenses and chips, and start streaming again. 
* **During that half-second reboot, zero video frames are sent to the recording tape.** That is the exact 500 ms hole you saw in the audit logs!

#### The CPU Priority Problem (The VIP Pass Analogy):
* In Android, touching or swiping the screen gets an automatic **VIP Pass** on the phone's CPU so that touch gestures feel buttery smooth.
* The **IMU thread** was given a VIP Pass (`THREAD_PRIORITY_URGENT_DISPLAY`), which is why the IMU recorded **96,411 frames with 0 drops**!
* However, the **Camera and Timeline recording threads** were running on "standard general admission" tickets. When you touched the screen, Android's CPU gave all its attention to the screen touch animation and briefly starved the camera logging threads of CPU time.

---

### The 3 Things We Are Fixing:

| # | What Is Happening Today | What We Are Changing It To |
| :--- | :--- | :--- |
| **Fix 1: Stop Camera Hardware Reboots** | Whenever you swipe tabs or tap fullscreen, the code closes and restarts the camera hardware (`CameraCaptureSession`). | **Lock the camera hardware during recording:** Once recording starts, the camera sensor **never** reboots or stops streaming. Swiping tabs will merely stop displaying the screen pixels without touching the active recording tape. |
| **Fix 2: Single Persistent Preview Surface** | The app creates 3 separate preview screens (one for Camera tab, one for SBS tab, one for Fullscreen). Switching between them destroys one and creates another. | **One permanent background surface:** The camera streams to a single permanent offscreen texture. All screens simply look at this single texture, with zero destruction or recreation. |
| **Fix 3: VIP CPU Priority for All Recorders** | Camera and Timeline recording worker threads ran at default priority (0), getting preempted by screen touches. | **Promote all recording threads to Real-Time priority (-8):** Camera, Radar, and Timeline writers get the same VIP priority as the IMU, ensuring screen touches cannot delay or stutter data logging. |

---

## Technical Deep-Dive & Root Cause Analysis

Tracing the execution flow across the Android app reveals three concurrent technical root causes:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        THE 3-TIER ROOT CAUSE ARCHITECTURE                              │
│                                                                                        │
│  [1. Compose View Churn]                                                               │
│      User Taps Fullscreen / Swipes Tabs                                                │
│         │                                                                              │
│         ▼                                                                              │
│      `shouldRenderPreview` toggles false in CameraDashboardCard                        │
│      Compose destroys old TextureView -> creates new TextureView                       │
│         │                                                                              │
│         ▼                                                                              │
│  [2. Hardware HAL Session Restart (The Primary Culprit)]                               │
│      TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed()                    │
│      `CameraEngine.detachPreviewSurface()` -> invokes `restartSession()`               │
│         │                                                                              │
│         ▼                                                                              │
│      CameraCaptureSession.stopRepeating() + close() + device.createCaptureSession()   │
│      ==> Camera HAL completely reboots ISP pipeline (300ms - 600ms hardware freeze)   │
│      ==> ZERO frames delivered to MediaRecorder surface during this window             │
│         │                                                                              │
│         ▼                                                                              │
│  [3. Linux CFS Thread Priority Imbalance]                                              │
│      Main UI Thread (-4) & RenderThread (-10) preempt CameraEngine-Worker (0)         │
│      and SessionTimelineWriter (0), delaying event ingestion.                          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1. Camera2 Hardware CaptureSession Reconfiguration
* In `CameraEngine.kt` (lines 457–462 & 691–702):
  ```kotlin
  fun detachPreviewSurface(surfaceTexture: SurfaceTexture? = null) {
      ...
      previewSurface?.release()
      previewSurface = null
      if (cameraDevice != null && isRecordingVideo) {
          restartSession() // <-- HAL TEARDOWN!
      }
  }
  ```
* When `restartSession()` is invoked, it closes the active `CameraCaptureSession` and calls `cameraDevice.createCaptureSession()`.
* In Android Camera2, tearing down and recreating a capture session reconfigures the hardware Image Signal Processor (ISP), re-negotiates sensor timings, and drains in-flight buffers. On modern hardware (Exynos/Snapdragon), this takes **300 to 600 ms**.
* During this reconfiguration period, the sensor stops delivering frames to **ALL** surfaces, including the `recorderSurface` (`MediaRecorder`). This produces the exact ~500 ms drop observed in `camera_frames.csv` and `camera_video.mp4`.

### 2. ViewPager & Compose Layout Churn
* In `CameraDashboardCard.kt` (line 115) and `SbsDashboardCard.kt` (line 149):
  ```kotlin
  val shouldRenderPreview = isCurrentTab && !isCameraFullScreen && !isCalibrationFullScreen && !isPreviewMutedByUser && hasCameraPermission
  ```
* Whenever the user swipes tabs or toggles fullscreen, Compose destroys the existing `TextureView` and disposes of its `SurfaceTexture`.
* When returning or switching to fullscreen, a brand-new `TextureView` is mounted, calling `attachPreviewSurface()` which triggers **a second** `restartSession()`.

### 3. Thread Priority Imbalances
* In `ImuManager.kt` (line 43), the sensor thread uses `Process.THREAD_PRIORITY_URGENT_DISPLAY` (-8) and achieved **zero drops** (98.6 Hz continuous).
* In contrast, `CameraEngine-Worker` defaults to standard priority (`THREAD_PRIORITY_DEFAULT` / 0), and `CameraSessionRecorder` and `SessionTimelineWriter` run at `Thread.NORM_PRIORITY` (Java 5 = Linux 0).
* When a user touches or gestures on the screen, the Android UI thread (`THREAD_PRIORITY_DISPLAY` / -4) and RenderThread (-10) dominate the CPU cores, causing write queue latency spikes.

---

## User Review Required

> [!IMPORTANT]
> **Summary of Proposed Changes for Approval:**
> 1. **Do Not Recreate CaptureSession During Active Recording:**
>    When `isRecordingVideo == true`, `CameraEngine` will **never** close or restart the hardware `CameraCaptureSession` when preview surfaces detach/attach. The recording session stays locked and continuously streaming to `recorderSurface`.
> 2. **Dynamic Surface Re-Targeting Without HAL Reboots:**
>    Camera2 allows updating a repeating request dynamically (`session.setRepeatingRequest()`) without closing the session. If the user swipes away, the camera simply stops targeting the screen without interrupting the video file.
> 3. **Elevate Worker Thread Priorities:**
>    Promote `CameraEngine-Worker`, `CameraSessionRecorder-Worker`, `TimelineWriter-Worker`, and `RadarSessionRecorder-Worker` to high-priority Linux scheduler classes (`Process.THREAD_PRIORITY_URGENT_DISPLAY` / -8).

---

## Proposed Changes

---

### Component 1: Camera Hardware Lifecycle (`com.bajajauto.roadsense.camera`)

#### [MODIFY] `CameraEngine.kt`
- Modify `detachPreviewSurface()`: When `isRecordingVideo == true`, do **NOT** invoke `restartSession()`. Release the preview surface reference and update the repeating request to target only `recorderSurface` via `session.setRepeatingRequest(recordOnlyBuilder.build())`.
- Modify `attachPreviewSurface()`: If `isRecordingVideo == true` and the capture session is already running with `recorderSurface`, attach the new preview surface dynamically without stopping the hardware session.
- Modify `backgroundThread` instantiation:
  ```kotlin
  backgroundThread = HandlerThread("CameraEngine-Worker", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
  ```

---

### Component 2: UI Viewfinder Continuity (`com.bajajauto.roadsense.ui`)

#### [MODIFY] `CameraDashboardCard.kt` & `FullscreenCameraPreview.kt`
- Decouple the UI preview lifecycle so that swiping away from the Camera tab or entering Fullscreen does not trigger destructive surface teardowns while recording is active.

---

### Component 3: Thread Priorities (`com.bajajauto.roadsense.recording`)

#### [MODIFY] `CameraSessionRecorder.kt`
- Elevate executor thread priority to real-time background processing (`Thread.MAX_PRIORITY - 1`).

#### [MODIFY] `SessionTimelineWriter.kt`
- Elevate executor thread priority to `Thread.MAX_PRIORITY - 1`.

#### [MODIFY] `RadarSessionRecorder.kt`
- Elevate executor thread priority to `Thread.MAX_PRIORITY - 1`.

---

## Verification Plan

### Desktop ADB Validation Protocol (Controlled Bench Test)
Since this issue is 100% reproducible on the desk using ADB mode, execute this automated test procedure:

1. **Connect Device via ADB:**
   ```powershell
   adb devices
   ```
2. **Launch RoadSense & Start Controlled Recording:**
   Launch the app and start a 40-second recording session.
3. **Execute Automated Simulated Touch Stress Test via ADB:**
   While the session is recording, execute rapid UI interactions via shell input:
   ```powershell
   # 1. Tap fullscreen toggle (t = 10s)
   adb shell input tap 950 400
   Start-Sleep -Seconds 4

   # 2. Tap to exit fullscreen (t = 14s)
   adb shell input tap 950 400
   Start-Sleep -Seconds 4

   # 3. Swipe to Radar tab (t = 18s)
   adb shell input swipe 900 600 100 600 200
   Start-Sleep -Seconds 4

   # 4. Swipe back to Camera tab (t = 22s)
   adb shell input swipe 100 600 900 600 200
   Start-Sleep -Seconds 4

   # 5. Stop recording (t = 30s)
   adb shell input tap 500 1800
   ```
4. **Pull and Audit Session Logs:**
   ```powershell
   python scripts/session_health_check.py --latest
   python scripts/audit_cross_sensor_sync.py --latest
   ```
5. **Success Criteria:**
   - **Camera Frame Drops (>50 ms):** `0 occurrences (0.00%)` (Down from 20 occurrences).
   - **Radar-Camera Sync Spikes (>33.3 ms):** `0 occurrences` during active recording.
   - **UI Trigger Correlation:** Zero drops provoked by tab swipes or fullscreen toggles in `sync_audit_report.png`.
   - **JVM Unit Tests:** `./gradlew testDebugUnitTest` passes cleanly.
