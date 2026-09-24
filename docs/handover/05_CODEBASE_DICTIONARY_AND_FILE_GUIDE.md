# Complete Codebase Dictionary & File-by-File Technical Guide

> **Document Version:** 1.0.0  
> **Target Audience:** Core Software Developers, Peer Reviewers, Systems Integrators  
> **Status:** Authoritative Technical Handover Reference

---

## 1. Codebase Package Map & Organization

RoadSense is organized into 13 cohesive, modular packages under `com.bajajauto.roadsense`:

```
com.bajajauto.roadsense
 ├── acquisition/      <-- USB Serial hardware communications (CP2105 dual port)
 ├── decoding/         <-- mmWave radar magic-word frame assembly & TLV decoding
 ├── camera/           <-- Camera2 HAL integration, Auto-Road AE & shutter logging
 ├── imu/              <-- 100 Hz sensor acquisition, orientation math & jitter benchmarking
 ├── gnss/             <-- LocationManager & Fused Location provider tracking
 ├── canedge/          <-- CSS CANedge2 discovery, REST client, staging pool & ingestion
 ├── fusion/           <-- 6-DOF spatial calibration, intrinsics & perspective projection
 ├── models/           <-- Immutable domain models (RadarFrame, RadarPoint, RadarHeader)
 ├── recording/        <-- Multi-sensor session recorders, disk writers & timeline index
 ├── logging/          <-- Dual-stream app flight recorder & session diagnostics
 ├── storage/          <-- SharedPreferences wrapper for persistent user settings
 └── ui/               <-- Jetpack Compose UI (MainActivity, ViewModel, Screens, Components)
```

---

## 2. Package-by-Package Technical Encyclopedia

---

### Package 2.1: `com.bajajauto.roadsense.acquisition`

#### [`RadarConnectionManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt)
* **Role & Responsibility:** Discovers, requests USB permissions, opens, and drives the Texas Instruments mmWave radar (AWR1843BOOST) via the onboard Silicon Labs CP2105 dual-port USB-to-UART bridge.
* **Key Classes & Interfaces:**
  * `RadarConnectionManager(context: Context)`: Primary driver class.
  * `sealed class RadarConnectionState`: `Disconnected`, `Connecting`, `Connected(portInfo)`, `Error(message)`.
  * `fun interface RawDataListener`: Direct callback interface for receiving unbuffered byte chunks.
* **Hardware Port Mapping:**
  * **Port 0 (Enhanced COM / Config Port):** Configured at `115,200 baud`, 8N1. Transmits radar CLI chirp configurations (`sensorStart`, `profileCfg`, etc.).
  * **Port 1 (Standard COM / Data Port):** Configured at `3,125,000 baud` (3.125 Mbps), 8N1. Enables DTR/RTS hardware lines. Continuously ingests binary TLV packets.
* **Threading Architecture:** Instantiates `SerialInputOutputManager` with a `32,768-byte` buffer on a dedicated background worker thread. When data arrives, it iterates over `dataListeners: CopyOnWriteArrayList<RawDataListener>` and invokes `onRawData()` synchronously. Concurrently updates `_dataBytes: MutableStateFlow<ByteArray>` for throttled UI preview.
* **Error Handling:** Emits `RadarConnectionState.Error` upon USB permission denial, missing drivers, or unexpected serial runner disconnects.

---

### Package 2.2: `com.bajajauto.roadsense.decoding`

#### [`RadarPacketAssembler.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt)
* **Role & Responsibility:** Stateful byte-stream parser that converts arbitrary UART byte chunks into framed radar packets.
* **Key Classes:** `RadarPacketAssembler`.
* **Framing Protocol:**
  * Searches stream for the 8-byte TI Magic Word: `0x0102, 0x0304, 0x0506, 0x0708` (`0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07` in little-endian order).
  * Parses the fixed 40-byte header: Version, Total Packet Length, Platform ID, Frame Number, CPU Cycles, Num Detected Objects, Num TLVs, Subframe Number.
  * Handles boundary splits: If a packet spans across multiple UART chunks, it preserves remaining bytes in an internal `ByteArrayOutputStream` buffer until the entire packet arrives.
* **Garbage Rejection:** Discards preamble noise bytes prior to the magic word. Validates `totalPacketLen <= 65536`. If an illegal length is encountered, it skips the current byte and resumes magic-word searching.
* **Output:** Returns a `List<RawRadarPacket>` for every completed assembly.

#### [`RadarTlvDecoder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt)
* **Role & Responsibility:** Decodes raw binary TLV (Type-Length-Value) payloads from a `RawRadarPacket` into structured domain objects (`RadarFrame`, `RadarPoint`).
* **Supported TLV Types:**
  * **Type 1 (`MMWDEMO_OUTPUT_MSG_DETECTED_POINTS`):** Detected 3D point cloud. Each point contains: Range $r$ (m), Azimuth angle $\theta$ (rad), Doppler velocity $v$ (m/s), Elevation angle $\phi$ (rad). Calculates Cartesian coordinates:
    $$x = r \cdot \cos(\phi) \cdot \sin(\theta), \quad y = r \cdot \cos(\phi) \cdot \cos(\theta), \quad z = r \cdot \sin(\phi)$$
  * **Type 2 (`MMWDEMO_OUTPUT_MSG_RANGE_PROFILE`):** 1D FFT range bin amplitudes.
  * **Type 3 (`MMWDEMO_OUTPUT_MSG_NOISE_PROFILE`):** Range noise floor profile.
  * **Type 4 (`MMWDEMO_OUTPUT_MSG_AZIMUT_STATIC_HEAT_MAP`):** 2D azimuth heatmap.
  * **Type 5 (`MMWDEMO_OUTPUT_MSG_DOPPLER_RANGE_HEAT_MAP`):** Range-Doppler 2D matrix.
  * **Type 6 (`MMWDEMO_OUTPUT_MSG_STATS`):** Inter-frame processing time and margin stats.
  * **Type 7 (`MMWDEMO_OUTPUT_MSG_DETECTED_POINTS_SIDE_INFO`):** SNR and noise value per detected point.
* **Performance:** Uses `ByteBuffer.wrap()` with `ByteOrder.LITTLE_ENDIAN` for zero-copy binary reading.

---

### Package 2.3: `com.bajajauto.roadsense.camera`

#### [`CameraEngine.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraEngine.kt)
* **Role & Responsibility:** Complete Camera2 subsystem manager. Handles device enumeration, surface allocation, video recording via `MediaRecorder`, infinity focus locking, and autonomous dual-zone Road Auto-Exposure.
* **Key Features:**
  * **Multi-Camera Enumeration:** Probes standard camera IDs as well as vendor physical IDs (Samsung physical wide `0`, ultra-wide `2`/`50`, front `1`).
  * **Deterministic Shutter Logging:** Captures `SystemClock.elapsedRealtimeNanos()` via `CameraCaptureSession.CaptureCallback.onCaptureStarted()`.
  * **Autonomous Road AE:** Employs a dual-zone photometric contrast algorithm. Analyzes downsampled $32 \times 24$ bitmaps every 400ms:
    $$\text{Contrast Ratio} = \frac{\bar{L}_{\text{sky}}}{\max(\bar{L}_{\text{road}}, 1.0)}$$
    Dynamically adjusts `CONTROL_AE_EXPOSURE_COMPENSATION` and `CONTROL_AE_REGIONS` to prevent sky overexposure and road underexposure.
  * **Infinity Lock:** Overrides AF with `CONTROL_AF_MODE_OFF` and `LENS_FOCUS_DISTANCE = 0.0f` to prevent hunting against vehicle windshield reflections.
  * **Threading:** Dedicated `CameraEngine-Worker` `HandlerThread` for HAL callbacks, plus `engineScope` on `Dispatchers.Default` for Road AE math.

#### [`CameraDeviceInfo.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraDeviceInfo.kt)
* Data class encapsulating camera hardware traits: Camera ID, facing direction, display name, focal lengths, aperture, sensor pixel array size, and physical sensor dimensions.

#### [`CameraConfig.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraConfig.kt)
* Enums defining video encoding parameters: `CameraResolution` (`RES_480P`, `RES_720P`, `RES_1080P`) and `CameraFrameRate` (`FPS_15`, `FPS_30`, `FPS_60`).

#### [`CameraFrameMetadata.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraFrameMetadata.kt)
* Data model for recorded video frames: Frame number, shutter monotonic nanoseconds (`elapsedRealtimeNs`), host wall clock milliseconds (`wallClockMs`), exposure time nanoseconds, ISO sensitivity, and aperture.

---

### Package 2.4: `com.bajajauto.roadsense.imu`

#### [`ImuManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)
* **Role & Responsibility:** High-rate acquisition engine for hardware motion sensors.
* **Threading & Priority:** Runs on `RoadSense-ImuThread` with Linux priority `Process.THREAD_PRIORITY_URGENT_DISPLAY` (-8).
* **Sensors Acquired:** Accelerometer (`TYPE_ACCELEROMETER`), Linear Acceleration (`TYPE_LINEAR_ACCELERATION`), Gyroscope (`TYPE_GYROSCOPE`), Magnetometer (`TYPE_MAGNETIC_FIELD`), Rotation Vector (`TYPE_ROTATION_VECTOR`).
* **Mathematics:**
  * Remaps sensor coordinate frame to match vehicle landscape display orientation using `SensorManager.remapCoordinateSystem()`.
  * Computes orientation Euler angles (Pitch, Roll, Azimuth/Yaw) and full 4-element normalized quaternions ($q_x, q_y, q_z, q_w$).
  * Maintains a 128-entry circular buffer for real-time empirical sampling jitter benchmarking.
* **Dispatch:** Directly invokes `@Volatile onFrameListener` at 100 Hz during active recording; conflates live telemetry at 25 Hz for Compose UI.

#### [`ImuFrame.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuFrame.kt)
* Data model representing a synchronized multi-sensor IMU snapshot: Monotonic timestamp (ns), Raw Accel $(x,y,z)$, Linear Accel $(x,y,z)$, Gyro rates $(x,y,z)$ in deg/s and rad/s, Euler angles (deg), and Quaternion $(q_x, q_y, q_z, q_w)$.

#### [`ImuSample.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSample.kt)
* Raw individual sensor sample with sensor type, raw values array, hardware sensor timestamp, and monotonic host timestamp.

#### [`ImuSamplingBenchmark.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSamplingBenchmark.kt)
* Benchmark telemetry metrics: Target rate preset, actual measured rate (Hz), mean interval (ms), standard deviation jitter (ms), and total samples recorded.

#### [`ImuSensorCapability.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSensorCapability.kt)
* Sensor hardware audit record: Sensor name, vendor, version, maximum range, resolution, minimum delay (microseconds), and maximum rate capability.

#### [`ImuTelemetryState.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuTelemetryState.kt)
* UI-facing telemetry snapshot for high-density HUD gauges.

---

### Package 2.5: `com.bajajauto.roadsense.gnss`

#### [`GnssLocationManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/gnss/GnssLocationManager.kt)
* **Role & Responsibility:** Wraps Android `LocationManager` and `LocationListener` to acquire high-accuracy GNSS navigation fixes (GPS, GLONASS, Galileo, BeiDou).
* **Clock Sync:** Correlates satellite fix time with `SystemClock.elapsedRealtimeNanos()` and host wall-clock epoch.
* **Metrics Tracked:** Latitude, Longitude, Altitude, Speed (m/s and km/h), Bearing, Horizontal Accuracy (HDOP equivalent), Satellite count, and provider type.

#### [`GnssFix.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/gnss/GnssFix.kt)
* Immutable data record representing an individual navigation fix.

#### [`GnssState.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/gnss/GnssState.kt)
* Sealed hierarchy representing GNSS connection and satellite lock state: `Searching`, `Locked`, `Disabled`, `PermissionRequired`.

---

### Package 2.6: `com.bajajauto.roadsense.canedge`

#### [`CanedgeDiscovery.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/canedge/discovery/CanedgeDiscovery.kt)
* Discovers and validates the presence of a CSS Electronics CANedge2 logger on the Wi-Fi network. Probes device metadata (`http://<ip>/device.json`) and validates hardware Device ID (`7AC5E17F`).

#### [`CanedgeIngestionManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/canedge/ingestion/CanedgeIngestionManager.kt)
* Autonomous file ingestion engine. Manages the local `canedge_pool/` staging cache, throttles downloads during active recording drives to prioritize Radar/Camera, stages completed 1-minute split MF4 chunks, and prunes old cache folders to enforce a 300 MB storage cap.

#### [`CanedgeHttpClient.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/canedge/network/CanedgeHttpClient.kt)
* Specialized OkHttp client. Configured with a single-connection pool and 1500 ms backoff cooldowns to accommodate the ESP32 microcontroller's single-socket HTTP server limitation (Bug #10 post-mortem).

#### [`CanedgeRepository.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/canedge/repository/CanedgeRepository.kt)
* Parses directory listings from the CANedge2 SD card filesystem (`/00000001/`, etc.) and formats remote file hierarchies.

#### Models: [`CanedgeDevice.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/canedge/model/CanedgeDevice.kt) & [`CanedgeFile.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/canedge/model/CanedgeFile.kt)
* Data models for CANedge device info, firmware versions, remote file descriptors, sync progress, and unified explorer items.

---

### Package 2.7: `com.bajajauto.roadsense.fusion`

#### [`SpatialProjectionEngine.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt)
* **Role & Responsibility:** Core mathematical engine for 6-DOF spatial calibration and perspective projection.
* **Coordinate Transformation:** Transforms 3D radar coordinates $(x_r, y_r, z_r)$ into camera coordinate space using rotation matrix $\mathbf{R}(\text{roll}, \text{pitch}, \text{yaw})$ and translation vector $\mathbf{T}(\Delta X, \Delta Y, \Delta Z)$.
* **Perspective Projection:** Projects 3D camera coordinates onto the 2D image plane using camera intrinsic matrix $\mathbf{K}$:
  $$u = f_x \frac{X_c}{Z_c} + c_x, \quad v = f_y \frac{Y_c}{Z_c} + c_y$$
* **Reverse Touch Solver:** Closed-form $O(1)$ inverse solver. When a technician taps an on-screen target (e.g. a vehicle bumper at known radar depth $D$), it recalculates the required pitch and yaw angles:
  $$\Delta \text{yaw} \approx \arctan\left(\frac{u - c_x}{f_x}\right), \quad \Delta \text{pitch} \approx -\arctan\left(\frac{v - c_y}{f_y}\right)$$
* **Painter's Algorithm:** Sorts projected radar points descending by depth so foreground objects render on top of distant clusters.

#### [`CameraIntrinsicsProvider.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/CameraIntrinsicsProvider.kt)
* Computes focal lengths $(f_x, f_y)$ and optical center $(c_x, c_y)$ from Camera2 `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` or synthesizes them from physical sensor dimensions and active view dimensions.

#### [`CalibrationParameters.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/model/CalibrationParameters.kt)
* Data model for 6-DOF extrinsics: `deltaXMeters`, `deltaYMeters`, `deltaZMeters`, `pitchDeg`, `yawDeg`, `rollDeg`.

#### [`CalibrationStorageManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/storage/CalibrationStorageManager.kt)
* Manages atomic persistence of `radar_camera_calib.json` on disk with backup restoration.

#### [`SimulatedRadarDataProvider.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/sim/SimulatedRadarDataProvider.kt)
* Generates synthetic geometric targets (moving vehicle targets, stationary road signs, guard rails) for bench testing spatial calibration algorithms without physical radar hardware.

---

### Package 2.8: `com.bajajauto.roadsense.recording`

#### [`SessionManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionManager.kt)
* Central director of session recording directories. Generates timestamped directories (`session_YYYYMMDD_HHMMSS/`), initializes `session_timeline.csv`, attaches `AppLogger`, and finalizes `session_metadata.json`.

#### [`RadarSessionRecorder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/RadarSessionRecorder.kt)
* Dedicated high-throughput binary recorder. Writes framed packets with 24-byte sync header (`ROAD`, monotonic nanoseconds, wall-clock milliseconds, packet length) to `radar_frames.bin` and raw stream to `radar_raw_stream.bin`.

#### [`RawUartRecorder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/RawUartRecorder.kt)
* Standalone debug binary recorder for capturing continuous raw UART dumps outside of structured sessions.

#### [`CameraSessionRecorder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/CameraSessionRecorder.kt)
* Appends frame shutter timestamps, exposure durations, and sensor metadata into `camera_frames.csv`.

#### [`ImuSessionRecorder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/ImuSessionRecorder.kt)
* Writes 100 Hz synchronized IMU data (Accel, Gyro, Mag, Quaternions) into `imu_samples.csv`.

#### [`GnssSessionRecorder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/GnssSessionRecorder.kt)
* Appends location coordinates, speed, and accuracy into `gnss_track.csv`.

#### [`SessionTimelineWriter.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionTimelineWriter.kt)
* Writes chronological multi-sensor lifecycle events into `session_timeline.csv`.

---

### Package 2.9: `com.bajajauto.roadsense.logging`

#### [`AppLogger.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/logging/AppLogger.kt)
* Production dual-stream flight recorder. 
* Stream 1 writes continuous application diagnostics to `/files/app_logs/app_run_YYYYMMDD_HHMMSS/app_system.log`.
* Stream 2 attaches to active sessions and writes session-specific events into `session_debug.log`.
* Thread-safe asynchronous logging backed by an internal worker queue and auto-flush logic.

---

### Package 2.10: `com.bajajauto.roadsense.storage`

#### [`AppPreferences.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/storage/AppPreferences.kt)
* Encapsulates Android `SharedPreferences`. Stores user configurations such as radar max range, dynamic filter toggles, camera resolution, frame rate, lens ID, and CANedge IP address.

---

### Package 2.11: `com.bajajauto.roadsense.ui`

#### [`MainActivity.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt)
* Application entry point. Sets up full-screen edge-to-edge window insets, initializes `AppLogger`, and hosts `RoadSenseCockpitScreen`. Configures `HorizontalPager` with 7 tabs (`Radar`, `GNSS`, `IMU`, `Camera`, `CANedge`, `SBS`, `Storage`). Disables user horizontal drag gesture during calibration to prevent accidental tab navigation.

#### [`RadarViewModel.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)
* Central orchestration engine. Holds state flows for all hardware subsystems, hosts the 1-second telemetry ticker, coordinates session start/stop workflows, and manages spatial projection parameters.

#### UI Screens (`ui/screens/`):
* **`RadarDashboardCard.kt`**: Bird's Eye View (BEV) radar plot, cluster telemetry, and connect/stream actions.
* **`CameraDashboardCard.kt`**: Camera preview viewfinder, resolution/FPS selectors, Road AE controls, and calibration trigger.
* **`GnssDashboardCard.kt`**: Live location telemetry, speed gauge, and satellite constellation status.
* **`ImuDashboardCard.kt`**: 100 Hz orientation gauges, pitch/roll indicators, and jitter benchmark results.
* **`CanedgeDashboardCard.kt`**: CANedge2 discovery, connection status, storage pool metrics, and unified file explorer.
* **`SbsDashboardCard.kt`**: Side-by-side synchronized view (Camera viewfinder on left, Radar BEV plot on right).
* **`SessionDeckCard.kt`**: Recorded sessions browser, storage usage analytics, and file export options.

#### UI Components (`ui/components/`):
* **`LiveMetricsBar.kt`**: Persistent high-density status bar showing live Hz rates for all sensors, battery %, CPU %, and CPU temperature.
* **`TopSessionHeader.kt`**: Session recording status pill, duration counter, and Start/Stop record action buttons.
* **`FullscreenCameraPreview.kt`**: Zero-overhead full-screen camera preview with attached radar overlays.
* **`FullscreenCalibrationStudio.kt`**: Dedicated studio for interactive 6-DOF extrinsic calibration, featuring touch drag and target reticles.
* **`ViewfinderRadarOverlay.kt`**: Draws radar lollipops, depth-colored target circles, velocity vectors, and ground range rings.
* **`RadarBevPlot.kt`**: High-performance 2D Canvas polar plot rendering mmWave point clouds in Cartesian space.
* **`QuickNudgeBar.kt`**: Precision +/- step buttons for pitch, yaw, roll, and distance offsets.
* **`CameraCalibrationCard.kt`**: Extrinsic sliders, baseline reset, and calibration save buttons.
