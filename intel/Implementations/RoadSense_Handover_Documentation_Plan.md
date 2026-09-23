# Implementation Plan: RoadSense Human-Focused Handover & Multithreading Documentation Suite

RoadSense is a high-performance multi-modal automotive data acquisition platform capturing TI mmWave Radar (3.125 Mbps UART), Camera2 (480p–1080p, up to 60 FPS with Road AE), 100 Hz IMU (accelerometer/gyro/mag/quaternions), GNSS location, and CSS Electronics CANedge2 CAN-bus telemetry. This plan establishes an exhaustive, human-centric technical handover suite inside `docs/handover/`, combining in-depth Markdown documentation and interactive standalone HTML visualization tools.

---

## User Review Required

> [!IMPORTANT]
> **Human-Centric vs. AI-Agent Context:**  
> The existing `context/` folder contains concise, machine-optimized intelligence files for autonomous agents. This new suite inside `docs/handover/` is specifically engineered for **human automotive engineers, software developers, and research scientists**. It focuses on technical depth, mental models, hardware interactions, execution timelines, and interactive diagrams with zero cognitive friction.

> [!NOTE]
> **No Codebase Code Modifications:**  
> This task creates comprehensive technical documentation and interactive HTML visualizers in `docs/handover/`. It does not modify existing production Android app source code, guaranteeing 100% build stability.

---

## Proposed Handover Documentation Suite Structure

We will structure the new human-focused handover documentation within `docs/handover/` with modular Markdown guides and interactive HTML visualizers:

```text
docs/handover/
 ├── README.md                                    <-- Handover Master Guide & Navigation Map
 ├── 01_SYSTEM_ARCHITECTURE.md                    <-- System Topology, MVVM Unidirectional Flow & Storage Layout
 ├── 02_MULTITHREADING_AND_CONCURRENCY.md         <-- Exhaustive Thread Map, Priorities, Ring Buffers & Race Prevention
 ├── 03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md     <-- Complete File-by-File Encyclopedia of Every Kotlin Source File
 ├── 04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md       <-- Detailed Sensor Pipelines, Clock Sync & Spatial Fusion
 ├── interactive_architecture_map.html            <-- Self-contained Interactive System Topology & Component Inspector
 └── interactive_multithreading_explorer.html     <-- Self-contained Interactive Thread Timeline & Concurrency Simulator
```

---

## Detailed Contents of Handover Documents

### 1. `docs/handover/README.md` (Executive Welcome & Guide)
* Executive project purpose: High-fidelity multi-sensor vehicle telemetry capture for Level-2+ ADAS research.
* Quick-start roadmap for new engineers joining the project.
* Key design principles: Zero-drop streaming, unbuffered monotonic timestamping, memory predictability, and battery/thermal safety.
* How to use the documentation suite (reading order, markdown guides, and interactive HTML tools).

### 2. `docs/handover/01_SYSTEM_ARCHITECTURE.md` (Overall Architecture)
* **High-Level Topology:** Physical sensor bus topology (USB-UART CP2105, MIPI CSI-2 Camera HAL, I2C/SPI Phone Sensors, Wi-Fi 802.11 b/g/n CANedge2 ESP32).
* **Software Architecture:** Unidirectional Data Flow (UDF) pattern with MVVM, Kotlin Coroutines, and Jetpack Compose.
* **Storage Directory Hierarchy:**
  * App run flight logs (`/files/app_logs/app_run_YYYYMMDD_HHMMSS/app_system.log`).
  * Permanent recording sessions (`/files/sessions/session_YYYYMMDD_HHMMSS/`).
  * Sub-directories: `radar/`, `camera/`, `gnss/`, `imu/`, `can/`.
  * Sync index: `session_timeline.csv` and metadata `session_metadata.json`.
  * Staging cache: `canedge_pool/`.
* **State Lifecycle & Transitions:** Idle -> Arming -> Multi-Sensor Recording -> Finalizing -> Storage.

### 3. `docs/handover/02_MULTITHREADING_AND_CONCURRENCY.md` (In-Depth Multithreading)
* **The Multithreading Challenge:** Ingesting 3.125 Mbps UART data, 60 FPS video frames, 100 Hz IMU samples, and multi-megabyte CAN files simultaneously without dropping frames, stuttering UI (60/120 Hz), or triggering Android ANRs.
* **Complete Thread & Priority Matrix:**
  * **Main / UI Thread:** Jetpack Compose layout, draw, composition; receives conflated `StateFlow` updates; zero heavy computations.
  * **Serial IO Worker Thread (`SerialInputOutputManager`):** Continuous USB endpoint polling, 32 KB native buffer, synchronous direct listener dispatch.
  * **Radar Disk Writer Thread (`RadarSessionRecorder-Worker`):** Dedicated single-thread executor with priority `Thread.NORM_PRIORITY + 1`, 64 KB `BufferedOutputStream` writing binary frames to disk.
  * **Camera Worker Looper (`CameraEngine-Worker` `HandlerThread`):** Handles Camera2 HAL capture callbacks, frame lifecycle, and shutter timestamp extraction.
  * **Road AE Analyzer (`engineScope` on `Dispatchers.Default`):** Non-blocking 32x24 downsampled photometric luminance sampling, sky-bloom rejection, adaptive EV compensation.
  * **IMU Worker Looper (`RoadSense-ImuThread` `HandlerThread`):** Priority `Process.THREAD_PRIORITY_URGENT_DISPLAY`, lockless continuous 100 Hz sampling, circular jitter window.
  * **CANedge Network Engine (`Dispatchers.IO`):** Asynchronous OkHttp client, deferred during active drives to prevent CPU/Wi-Fi radio thermal throttling.
  * **ViewModel Telemetry Ticker (`viewModelScope` on `Dispatchers.Default`):** 1 Hz periodic CPU/battery/Hz calculations, 4 Hz UI hex preview sampler.
* **Concurrency Primitives & Thread Safety:**
  * `CopyOnWriteArrayList` for lock-free listener registries.
  * `@Volatile` state flags for cross-thread visibility.
  * Ring buffers & chunked array transfers.
  * Conflation vs. Unbuffered queues (UI indicators use `MutableStateFlow` conflation; recording pipelines use direct callbacks).
  * Synchronization and atomic boundaries.

### 4. `docs/handover/03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md` (File-by-File Encyclopedia)
* Exhaustive, package-by-package technical inventory of every file in `app/src/main/java/com/bajajauto/roadsense/`:
  * **`acquisition/`**: `RadarConnectionManager.kt` (dual-port CP2105 USB driver, permission handling, serial IO thread).
  * **`decoding/`**: `RadarPacketAssembler.kt` (magic word frame sync, 40-byte header parser), `RadarTlvDecoder.kt` (TLV type 1–7 parser).
  * **`camera/`**: `CameraEngine.kt` (Camera2 session, dual-zone Road AE, infinity lock, preview lifecycle), `CameraDeviceInfo.kt`, `CameraConfig.kt`, `CameraFrameMetadata.kt`.
  * **`imu/`**: `ImuManager.kt` (100 Hz acquisition, quaternion conversion, jitter benchmarking), `ImuFrame.kt`, `ImuSample.kt`, `ImuSamplingBenchmark.kt`, `ImuSensorCapability.kt`, `ImuTelemetryState.kt`.
  * **`gnss/`**: `GnssLocationManager.kt` (FusedLocation / Android LocationManager, monotonic clock sync), `GnssFix.kt`, `GnssState.kt`.
  * **`canedge/`**: `CanedgeDiscovery.kt`, `CanedgeIngestionManager.kt`, `CanedgeHttpClient.kt`, `CanedgeRepository.kt`, `CanedgeDevice.kt`, `CanedgeFile.kt`.
  * **`fusion/`**: `SpatialProjectionEngine.kt` (6-DOF extrinsics, reverse touch solver, painter's algorithm), `CameraIntrinsicsProvider.kt`, `CalibrationParameters.kt`, `CalibrationStorageManager.kt`, `SimulatedRadarDataProvider.kt`.
  * **`recording/`**: `SessionManager.kt`, `RadarSessionRecorder.kt`, `RawUartRecorder.kt`, `CameraSessionRecorder.kt`, `GnssSessionRecorder.kt`, `ImuSessionRecorder.kt`, `SessionTimelineWriter.kt`, `SessionInfo.kt`, `SessionRecordingState.kt`.
  * **`logging/`**: `AppLogger.kt` (dual-stream flight recorder).
  * **`storage/`**: `AppPreferences.kt`.
  * **`ui/`**: `MainActivity.kt`, `RadarViewModel.kt`, plus all dashboard cards and components.
* For each file: Purpose, Primary Classes, Key Methods, Threading Model, Dependencies, and Output Artifacts.

### 5. `docs/handover/04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md` (Pipelines & Sync)
* **Monotonic Nanosecond Clock Synchronization:** Why `SystemClock.elapsedRealtimeNanos()` is the single source of truth across all sensors; cross-sensor correlation in `session_timeline.csv`.
* **Radar Processing Pipeline:** Hardware chirp -> UART -> `SerialInputOutputManager` -> `RadarPacketAssembler` -> `RadarTlvDecoder` -> `RadarFrame` -> BEV & Projection.
* **Camera Processing Pipeline:** MIPI CSI-2 -> HAL Surface -> `MediaRecorder` H.264 stream & `onCaptureStarted` shutter logging.
* **IMU Pipeline:** Hardware FIFO -> SensorEventListener -> Matrix math & Quaternions -> CSV + Live UI.
* **CANedge Pipeline:** Automotive CAN bus -> ESP32 SD card -> Wi-Fi HTTP Server -> Android Staging Pool -> Session Ingestion.
* **Spatial Calibration & Sensor Fusion:** Homogeneous 3D coordinate transform, intrinsic pinhole projection, reverse touch calibration math.

### 6. `docs/handover/interactive_architecture_map.html` (Interactive Web Architecture)
* Standalone, zero-dependency HTML5/CSS3/JavaScript interactive tool.
* Interactive visual node graph showing hardware devices, background threads, repositories, and UI layers.
* Clickable nodes opening a side panel with full technical specs, thread priority, data rate, and associated Kotlin source files.
* Subsystem filter toggles (Radar, Camera, CAN, IMU, GNSS, Storage, UI).
* Live data animation demonstrating packet flow from physical sensors through ring buffers to disk and UI.

### 7. `docs/handover/interactive_multithreading_explorer.html` (Interactive Thread Timeline)
* Standalone, zero-dependency HTML5 tool.
* Interactive multi-track timeline showing concurrent execution across all threads (Main UI, Serial IO, Disk Writer, Camera Worker, IMU Looper, CANedge Coroutine, Ticker).
* Visual depiction of buffer states (32KB serial buffer, 64KB disk buffer, IMU ring buffer).
* Interactive controls to simulate "Start Recording", "High Radar Burst", "Camera Frame Drop Scenario", or "CANedge Idle Sync".
* Thread priority comparison chart explaining why specific thread priorities were chosen to eliminate audio/display stutter.

---

## Verification Plan

### Automated Tests
* Validate all Kotlin code and JVM unit tests remain pristine and functional:
  ```powershell
  $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
  ```

### Manual Verification
1. Inspect markdown files in `docs/handover/` for technical accuracy, formatting, and markdown link integrity.
2. Open `docs/handover/interactive_architecture_map.html` in Microsoft Edge / Chrome to verify styling, responsiveness, interactivity, and node inspection.
3. Open `docs/handover/interactive_multithreading_explorer.html` in Microsoft Edge / Chrome to test thread simulation, timeline rendering, and priority visualization.
4. Verify workspace mirroring into `intel/Implementations/RoadSense_Handover_Documentation_Plan.md` as required by Rule 6.
