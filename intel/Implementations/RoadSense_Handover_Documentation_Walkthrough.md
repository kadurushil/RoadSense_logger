# Walkthrough: RoadSense Human-Focused Architecture & Handover Suite

We have created an exhaustive, human-focused engineering handover documentation suite and zero-dependency interactive visual tools in [`docs/handover/`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover).

---

## 1. Documentation Suite Inventory

The following authoritative documents and interactive HTML visualizers are now available in the repository:

| Document / Tool | Description |
| :--- | :--- |
| **[`README.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/README.md)** | Executive welcome, project mission, 3-day engineer onboarding path, and quick-start commands. |
| **[`01_SYSTEM_ARCHITECTURE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/01_SYSTEM_ARCHITECTURE.md)** | Hardware bus topology (USB-UART, MIPI CSI-2, I2C/SPI, Wi-Fi), MVVM Unidirectional Data Flow, storage hierarchy, and full JSON metadata schemas. |
| **[`02_MULTITHREADING_AND_CONCURRENCY.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/02_MULTITHREADING_AND_CONCURRENCY.md)** | 100% technical breakdown of all 8 threads, Linux priorities (`URGENT_DISPLAY -8`, `NORM+1`), 32KB/64KB buffer mechanics, conflation vs lossless dispatch, and starvation prevention. |
| **[`03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md)** | File-by-file encyclopedia covering every source file in the repository across all 13 packages, detailing responsibilities, classes, methods, and thread contexts. |
| **[`04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md)** | End-to-end data pipelines for mmWave Radar TLVs, Camera2 shutter extraction, 100 Hz IMU orientation & quaternions, GNSS tracking, and 6-DOF spatial fusion math. |
| **[`interactive_architecture_map.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_architecture_map.html)** | Self-contained interactive web tool featuring an architectural node graph, clickable component inspector, and live data-flow animator. |
| **[`interactive_multithreading_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_multithreading_explorer.html)** | Self-contained interactive web tool featuring a real-time multi-track thread simulator, buffer watermarks, and Linux thread priority comparator. |

---

## 2. Key Architecture & Multithreading Highlights

### 2.1 The Multithreading Execution Matrix
```
[Main / UI Thread]                Priority: 0 (DEFAULT)         Compose VSYNC & 60/120 Hz Drawing
[SerialInputOutputManager]        Priority: -1 (NORM+1)         3.125 Mbps UART Polling (32 KB Buffer)
[RadarSessionRecorder-Worker]     Priority: -1 (NORM+1)         Unbuffered Binary Disk Write (64 KB Buffer)
[CameraEngine-Worker]             Priority: 0 (DEFAULT)         Camera2 HAL Callbacks & Shutter Sync
[RoadSense-ImuThread]             Priority: -8 (URGENT_DISPLAY) 100 Hz Motion Sensor Interrupts & Ring Buffer
[engineScope (Road AE)]           Dispatchers.Default           400 ms 32x24 Micro Bitmap Luminance Analysis
[CanedgeIngestionScope]           Dispatchers.IO                OkHttp REST Client (Preempted during drives)
[viewModelScope (Ticker)]         Dispatchers.Default           1 Hz Telemetry & 4 Hz Hex Preview Throttling
```

### 2.2 Memory Safety & Zero-Drop Streaming Invariants
* **Direct Unbuffered Dispatch:** High-throughput serial and sensor data bypass intermediate Channel/Flow buffers, executing directly on worker threads to guarantee zero dropped bytes at 3.125 Mbps.
* **UI Conflation:** Jetpack Compose UI observes conflated `StateFlow<T>`, ensuring that high-frequency sensor interrupts (100 Hz IMU, 20 Hz Radar) never swamp the UI thread or cause frame stutter.
* **Preemption During Active Drives:** High-bandwidth CANedge Wi-Fi downloads are automatically paused during active recording sessions to reserve 100% CPU and flash write bandwidth for Radar and Camera.

---

## 3. Interactive Web Tools Verification

Both interactive visualizers are completely standalone with zero external dependencies and can be launched directly in any web browser:

1. **Architecture Map:** Double-click or open [`docs/handover/interactive_architecture_map.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_architecture_map.html) to interact with the system graph, filter by sensor modality, and inspect component traits.
2. **Multithreading Explorer:** Double-click or open [`docs/handover/interactive_multithreading_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_multithreading_explorer.html) to run the real-time thread simulation, toggle active recording, test burst buffers, and inspect Linux thread priorities.

---

## 4. Verification & Testing

* **JVM Unit Tests:** Executed `./gradlew testDebugUnitTest` — all 24 tasks passed successfully in 1 second.
* **Code Integrity:** All existing production source code remained 100% untouched and pristine.
* **Mermaid Syntax Audit:** Audited and corrected all 8 Mermaid diagrams across the handover suite:
  - Replaced unsupported `timeline` diagram type with standard `flowchart TD` in [`04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md).
  - Fixed subgraph IDs with spaces and ampersands by using alphanumeric identifiers with quoted labels.
  - Quoted node labels containing special characters, brackets, and comparisons (`|"<="|`).
  - Standardized sequenceDiagram participants and stateDiagram-v2 transition labels.
* **Interactive HTML Visualizers Audit & Fixes:**
  - Resolved missing data entries for all bottom recorder nodes in [`interactive_architecture_map.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_architecture_map.html) (`camera-rec`, `imu-rec`, `gnss-rec`, `can-rec`) and hardware nodes (`imu-hw`, `gnss-hw`, `can-hw`).
  - Added direct, clickable documentation links (`📖 Open Documentation Section →`) to the inspection panel for each component.
  - Fixed the "Storage / Recorders" filter to accurately target all recorder nodes without incorrectly dimming them.
  - Added a unified top navigation bar across both HTML tools to allow immediate switching between markdown documentation guides and interactive visualizers.
