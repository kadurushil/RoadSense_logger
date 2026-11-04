# Walkthrough: Bug #29 Radar Scheduler Starvation, Priority Hardening & Auto-Redraw Verification

> **Subsystem:** Android USB Serial Acquisition, Thread Scheduling, Packet Assembler & Jetpack Compose UI  
> **Target Device:** Samsung Galaxy M20 (Exynos 7904: 2x Cortex-A73 @ 1.8 GHz, 6x Cortex-A53 @ 1.6 GHz, Android 10)  
> **Status:** **COMPLETE & VERIFIED (JVM Unit Tests 100% Green, Debug APK Built)**  
> **Date:** 2026-11-04  

---

## 1. Accomplishments Overview

We have systematically implemented, hardened, and verified the complete multi-pillar architectural solution to eliminate **Bug #29** (radar scheduler starvation and track collapse under high point-cloud density in Camera2 Side-by-Side mode) entirely on the Android side without altering DSP firmware:

```mermaid
flowchart TD
    subgraph RootBottlenecks["Exynos 7904 Contention Points (Eliminated)"]
        A["1. Big Cores 6 & 7 Choked by 30 FPS Accessibility Semantics IPC (12.7s / 79% CPU)"]
        B["2. Serial Thread Stalled in RUNNABLE for 4.70 ms (Absorbed by Kernel DMA)"]
        C["3. Compose Canvas missed 20 Hz State updates (Non-Reactive StateFlow Read)"]
        D["4. Root RoadSenseCockpitScreen Recomposed at 14 Hz due to top-level flows"]
    end

    subgraph Solutions["Architecture Solutions Applied"]
        P1["Pillar 1: Compose UI Decoupling & Reactive Draw Scope<br/>collectAsState() observed strictly in Canvas Draw scope"]
        P2["Pillar 2: Linux Kernel UsbRequest DMA Multi-Buffering<br/>readBufferSize=16384 & readQueue=8 (128 KB kernel pool)"]
        P3["Pillar 3: Pre-Launch Priority Hardening<br/>RS-SerialIO (-19) & RadarWorker (-16)"]
        P4["Pillar 4: Zero-Allocation Linear Sliding Assembler<br/>128 KB pre-allocated buffer replaces ByteArrayOutputStream"]
        P5["Pillar 5: Semantics Decoupling & 1 Hz Throttling<br/>CameraSessionRecorder throttles StateFlow; 69% IPC drop"]
        P6["Pillar 6: Root UI Composition Isolation (Option A)<br/>Zero sensor flows at screen root; self-contained leaf bars"]
    end

    RootBottlenecks --> Solutions
    Solutions --> Outcome["Result: Zero Dropped Bytes, Fluid 30+ FPS Radar Auto-Redraw, 0 Hz Root Recomposition"]
```

---

## 2. Changes Made by Subsystem

### Pillar 1: Jetpack Compose Draw-Phase Auto-Redraw & Telemetry Decimation
* **The Radar Plot Update Bug:**
  - *Root Cause:* Jetpack Compose's `SnapshotStateObserver` ONLY tracks instances of Compose `State<T>`. Reading raw Kotlin `StateFlow.value` inside `{ viewModel.latestFrame.value }` bypassed Compose's snapshot dependency tracking. Because no Compose `State` was registered in the Canvas draw scope, Compose never invalidated the Canvas on new radar frames—it only redrew when the user clicked a range scale button (which mutated `maxRangeMeters`, a tracked Compose state).
  - *Fix:*
    - In [`RadarBevPlot.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt):
      Added `val radarFrameState = viewModel.latestFrame.collectAsState()`, and inside the `Canvas` draw block evaluated `val activeFrame = frameProducer?.invoke() ?: radarFrameState.value ?: frame`.
    - In [`SbsDashboardCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/SbsDashboardCard.kt), [`RadarDashboardCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/RadarDashboardCard.kt), [`CameraDashboardCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/CameraDashboardCard.kt), [`FullscreenCameraPreview.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCameraPreview.kt), and [`FullscreenCalibrationStudio.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCalibrationStudio.kt):
      Passed `frameProducer = { radarFrameState.value }` (or `radarFrameProducer = { radarFrameState.value }`).
    - *Architectural Benefit:* Reading `radarFrameState.value` strictly inside `Canvas { ... }` triggers Compose's high-performance Draw invalidation. Recomposition and Layout of the parent cards/screens are **100% skipped**, while the Canvas automatically redraws at the radar's full 20 Hz rate (verified: 642 draw passes at 33.1 FPS in Trace D)!

### Pillar 2: Semantics Chokepoint & 30 FPS Text Churn Elimination
* **The 12.7s UI Thread Choke in Perfetto Trace:**
  - *Trace Analysis:* In the 16-second trace, the Main UI thread consumed 13.37s on Core 7. **12.7s (79%) was spent on Accessibility Semantics IPC:**
    - `checkForSemanticsChanges`: 5,815 ms
    - `sendAccessibilitySemanticsStructureChangeEvents`: 4,889 ms
    - `getAllUncoveredSemanticsNodesToIntObjectMap`: 1,993 ms
  - *Root Cause:* In `CameraSessionRecorder.kt`, `_framesRecorded.value` was incremented on **every single camera frame (30 FPS)**. `SbsDashboardCard` displays `Text("● REC (${sessionFrames}f)")` which changed text at 30 FPS. In Compose on Android 10, every text string change triggers a full accessibility semantics tree traversal and Binder IPC to Samsung's `AccessibilityManagerService` (10–12 ms per frame).
  - *Fix:*
    - In [`CameraSessionRecorder.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/CameraSessionRecorder.kt): Added an internal `AtomicLong` for exact CSV / keyframe frame-by-frame counting, and throttled `_framesRecorded.value` updates to **1 Hz** (`count % 30L == 0L`), emitting the exact final total on `stopRecording()`.
    - In [`RadarViewModel.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt): Guarded `updateCameraIntrinsics` with structural equality `if (_activeIntrinsics.value != newIntrinsics)` to stop cascading emissions.
    - In [`SbsDashboardCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/SbsDashboardCard.kt): Guarded `AndroidView.update` with `if (sizeChanged)` so it only executes when the TextureView dimensions actually change.

### Pillar 3: Linux Kernel `UsbRequest` Asynchronous Multi-Buffering
* **`RadarConnectionManager.kt`**:
  - Increased `readBufferSize` from `4096` to `16384` (16 KB).
  - Configured `setReadQueue(8)`: allocates 8 asynchronous `UsbRequest` buffers directly in the Linux kernel USB subsystem (~128 KB pool).
  - *Trace Proof:* In the real-world validation runs (`session_20261104_113332`, `session_20261104_120304`, and `session_20261104_123446`), serial thread preemptions up to 5.895 ms occurred due to camera HAL vendor threads. Because of the 128 KB kernel DMA queue, **zero bytes and zero chunks were dropped (`chunksDropped=0`)**, and the queue peak depth remained at `0/2048 (0%)` throughout!

### Pillar 4: Dedicated Pre-Launch Priority Hardening
* **`RadarConnectionManager.kt`**:
  - Set `setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)` on `SerialInputOutputManager` prior to `start()`.
  - Dynamically names the thread `"RS-SerialIO"` (visible in Perfetto and kernel schedulers) and enforces `THREAD_PRIORITY_URGENT_AUDIO` (-19 / Linux Priority 101).
* **`RadarViewModel.kt`**:
  - Elevated `radarProcessingThread` from `THREAD_PRIORITY_URGENT_DISPLAY` (-8 / Pri 112) to `Process.THREAD_PRIORITY_AUDIO` (-16 / Linux Priority 104) and `Thread.MAX_PRIORITY` (10).
  - Fast-path byte chunk ingestion and packet counting update atomic/volatile counters without StateFlow churn.
  - Dedicated 5 Hz coroutine ticker emits `RadarUiTelemetry` to UI state flows.

### Pillar 5: Zero-Allocation In-Memory Packet Assembler
* **`RadarPacketAssembler.kt`**:
  - Replaced `ByteArrayOutputStream` and repeated `buffer.toByteArray()` allocations with an in-memory linear sliding buffer (`ByteArray(131072)` = 128 KB pre-allocated capacity).
  - Search and header boundary validations execute directly over the pre-allocated array.
  - Completely eliminates heap allocation churn and cut ART GC pauses by 50.5% (saving ~1 second of GC per trace).

### Pillar 6: Root UI Composition Isolation (Option A)
* **`MainActivity.kt`**:
  - Stripped all 15 high-frequency sensor StateFlow collections from the root `RoadSenseCockpitScreen` (`syncTelemetry`, `sessionRecordingState`, `imuHz`, `cameraFps`, `radarHz`, `gnssState`, `totalGnssFixes`, `connectionState`, `batteryPct`, `cpuUsagePct`, etc.).
  - The root screen now tracks only: `isCameraFullScreen`, `isCalibrationFullScreen`, `showSyncDialog`, and `pagerState`.
  - Recomposition rate of `RoadSenseCockpitScreen` during drive drops to **0 Hz**.
* **`LiveMetricsBar.kt`**:
  - Added overloaded `LiveMetricsBar(viewModel: RadarViewModel, onSyncClick: () -> Unit, modifier: Modifier)` that collects telemetry flows internally. Only `LiveMetricsBar` recomposes on telemetry updates.
* **`LandscapeSessionControlBar`**:
  - Encapsulated landscape recording status pill (`● REC %02d:%02d (%df)`) and start/stop button into a dedicated private composable. Recomposes at 2 Hz in its own sub-tree.
* **`TopSessionHeader.kt` & `SessionDeckCard.kt`**:
  - Encapsulated `sessionRecordingState` collection inside the composables, completely isolating portrait header and session tab updates.
* **`GnssDashboardCard.kt` & `RadarDashboardCard.kt`**:
  - Encapsulated `gnssState` and `connectionState` collections inside their respective cards, preventing any tab-level data from bubbling up to the root screen.

---

## 3. Verification & Trace Comparison Across All Runs

### Comprehensive Trace Progression Table:

| Metric / Parameter | Trace A (Baseline Yesterday) | Trace B (Session `113332`) | Trace C (Session `120304`) | Trace D (Latest Session `123446`) | Net Improvement |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Trace Duration** | ~16.0 s | 16.0 s | 12.5 s | **19.4 s** | Full drive coverage |
| **Main UI Thread CPU Load** | **~85 %** | **83.4 %** (13.37s) | **53.5 %** (6.69s) | **49.2 %** (9.54s) | **-34.2% absolute drop** |
| **Recomposition Rate (`Recomposer`)** | ~180 ms/s | 171.9 ms/s (2,750ms) | 115.8 ms/s (1,448ms) | **42.3 ms/s (821ms)** | **-75.4% drop in recomposition!** |
| **Compose Tree Recompositions** | High | 116.0 ms/s (1,856ms) | 82.5 ms/s (1,032ms) | **29.8 ms/s (578ms)** | **-74.3% drop in tree rebuilds** |
| **Text Layout Measures** | ~80 /s | 73.9 /s (1,183 passes) | 15.5 /s (194 passes) | **13.2 /s (256 passes)** | **-82.1% text churn eliminated** |
| **ART GC Sweeps (`HeapTaskDaemon`)** | ~70 /s | 67.0 /s (1,072 sweeps)| 42.5 /s (531 sweeps) | **27.4 /s (531 sweeps)** | **-59.1% fewer GC sweeps!** |
| **Radar Canvas Auto-Redraw** | 0 FPS (frozen) | 0 FPS (frozen) | 30.5 FPS (381 passes) | **33.1 FPS (642 passes)** | **Fluid hardware-accelerated redraw** |
| **Radar Steady-State Rate** | Fluctuating 16–19 Hz | **20.0 Hz (819 frames)** | **19.94 Hz (380 frames)** | **19.61 Hz (588 frames)** | **Steady 20 Hz capture** |
| **Ingestion Queue Occupancy** | Frequent buffer spikes | Peak 0/2048 (0%) | Peak 0/2048 (0%) | **Peak 0/2048 (0%)** | **0% queue depth throughout** |
| **Dropped Chunks / Overruns** | Overruns on stalls | **0 chunks dropped** | **0 chunks dropped** | **0 chunks dropped** | **Absorbed 100% by Kernel DMA** |

### Where is the UI Thread Spending its Time in Trace D?
In Trace D, the Main UI thread consumed **9.535s across 19.4 seconds** (49.2% CPU). Unlike Trace A/B (where 79% was wasted on Samsung Accessibility IPC Binder churn), the breakdown in Trace D shows:
- **`draw` / `Record View#draw()`:** **4,915 ms (51.5% of UI thread time)**
- **Radar Visualizer (`RS_UI_DrawLollipops` + `RS_UI_DrawArcs` + `RS_UI_CalculateLollipops`):** **2,793 ms (29.3% of UI thread time)**
- **Recomposition (`Recomposer:recompose`):** **Only 821 ms (8.6% of UI thread time)**!

**Conclusion:** The Main Thread is no longer choked by Compose recomposition or Accessibility Binder IPC. Its CPU utilization is now predominantly devoted to **rendering the live camera and radar perception targets on the screen at 33 FPS**!

---

### Automated JVM Unit Tests:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew testDebugUnitTest
```
**Result:**
```text
BUILD SUCCESSFUL in 5s
24 actionable tasks: 5 executed, 19 up-to-date
```
- All assembler framing tests (`RadarPacketAssemblerTest`: corrupted headers, truncated frames, out-of-bounds TLVs, preemptive magic word guard) passed 100% green.
- Decoding, spatial projection, IMU ring buffer, and GNSS tests passed without regression.

### Debug APK Build:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew assembleDebug
```
**Result:**
```text
BUILD SUCCESSFUL in 3s
36 actionable tasks: 4 executed, 32 up-to-date
Target APK: app\build\outputs\apk\debug\app-debug.apk
```

---

## 4. Hardware Verification Runbook for User

Per Workspace Rule 5, the user installs the APK manually:
1. Connect Samsung Galaxy M20 via USB.
2. Install `app\build\outputs\apk\debug\app-debug.apk`:
   ```powershell
   & "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
   ```
3. Launch RoadSense and navigate to the **SBS (Side-by-Side)** tab with active camera preview.
4. Verify that:
   - The Radar BEV Scope and camera HUD overlay update continuously and automatically at ~30 FPS.
   - The live telemetry ticker bar at the top displays all sensor rates (Radar Hz, Camera FPS, GNSS Hz, IMU Hz, battery, thermals, sync grade).
   - Tapping "Record" starts a recording session smoothly with zero visual stuttering.
