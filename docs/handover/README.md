# RoadSense Automotive Telemetry Platform — Engineering Handover Guide

> **Document Classification:** Autonomous Vehicle Research & Engineering Systems  
> **Target Audience:** Automotive Software Engineers, Perception Engineers, Embedded Systems Architects  
> **Subsystem Scope:** Multi-Sensor Acquisition, High-Speed Serial Drivers, Camera2 Pipeline, 100 Hz IMU Fusion, CANedge2 Ingestion, 6-DOF Spatial Calibration  
> **Workspace Path:** `AndroidStudioProjects/RoadSense`  
> **Status:** Production / Research Baseline (API 34)

---

## 1. Executive Summary & Mission

**RoadSense** is a specialized, production-grade automotive multi-sensor data logger developed for motorcycle and passenger vehicle advanced driver assistance systems (ADAS) research. Operating on an Android smartphone hardware host, RoadSense bridges industrial vehicular sensor protocols with mobile edge compute to capture high-fidelity, synchronized multi-modal datasets for perception network training, spatial calibration, and sensor fusion algorithms.

Traditional automotive data acquisition setups require bulky industrial x86 box PCs, heavy automotive inverters, and complex cabling harnesses. RoadSense consolidates this pipeline into an ultra-portable, thermally robust smartphone application capable of continuous, frame-accurate data logging across five concurrent sensor modalities:

```
                                  ┌────────────────────────┐
                                  │   RoadSense Android    │
                                  │   Perception Host      │
                                  └───────────┬────────────┘
                                              │
         ┌───────────────────┬────────────────┼────────────────┬───────────────────┐
         │ (3.125 Mbps UART) │ (MIPI CSI-2)   │ (I2C/SPI)      │ (GNSS Chipset)    │ (Wi-Fi 802.11)
         ▼                   ▼                ▼                ▼                   ▼
┌──────────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│  TI AWR1843BOOST │ │ 1080p/60 FPS │ │  100 Hz IMU  │ │ 1-5 Hz GNSS  │ │  CSS CANedge2    │
│  77 GHz mmWave   │ │ Camera2 Lens │ │ Accel / Gyro │ │ Lat/Lon/Alt  │ │  Dual CAN Bus    │
│  Radar Sensor    │ │ Auto-Road AE │ │ Quaternions  │ │ Speed/Course │ │  Vehicle OBD/J1939│
└──────────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘
```

---

## 2. Key Engineering Pillars

1. **Deterministic Monotonic Synchronization:**  
   Every sensor sample, radar chirp packet, camera exposure shutter, and IMU frame is stamped at the hardware acquisition edge with `SystemClock.elapsedRealtimeNanos()`. This nanosecond-level common clock decouples real-time physical events from wall-clock drift, NTP jumps, or timezone re-synchronizations.

2. **Zero-Drop High-Throughput Ingestion:**  
   The Texas Instruments mmWave radar streams binary telemetry at **3,125,000 baud** (3.125 Mbps), producing ~312.5 KB/s of continuous binary stream. The ingestion engine implements direct-to-disk unbuffered recording with high-priority background worker threads and a 64 KB native ring buffer to guarantee zero dropped bytes even under heavy CPU or UI load.

3. **Predictable Memory Footprint & Thermal Safety:**  
   By bypassing unnecessary byte-array duplications, recycling buffers, decoupling UI rendering from storage pipelines, and throttling preview transformations, RoadSense operates at sustained ambient temperatures inside vehicular cockpits without triggering OEM thermal throttling or Android out-of-memory (OOM) kills.

4. **Closed-Loop Spatial Calibration & Fusion:**  
   RoadSense incorporates an on-device 6-DOF extrinsics engine ($[\mathbf{R} \mid \mathbf{T}]$) and pinhole camera intrinsics ($K$) provider. mmWave point clouds are projected in real time onto the active camera viewfinder with Painter's Algorithm depth sorting, ground range arcs, and an interactive Reverse Touch Solver for single-tap extrinsic calibration.

---

## 3. Handover Documentation Sitemap

This documentation suite is organized logically into deep-dive technical modules accompanied by zero-dependency interactive visualizers:

| Document | Primary Focus | Key Takeaways |
| :--- | :--- | :--- |
| **[`01_SYSTEM_ARCHITECTURE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/01_SYSTEM_ARCHITECTURE.md)** | System topology, MVVM, storage layouts | Hardware bus architecture, Unidirectional Data Flow, session folder schemas, metadata schemas |
| **[`02_MULTITHREADING_AND_CONCURRENCY.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/02_MULTITHREADING_AND_CONCURRENCY.md)** | Thread maps, priorities, ring buffers | Complete priority matrix, looper threads, coroutine scopes, race condition prevention, GC churn elimination |
| **[`03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md)** | Source file dictionary | Class-by-class, file-by-file technical encyclopedia across all 12 packages in the application |
| **[`04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md)** | Sensor pipelines & spatial math | TLV frame decoding, Camera2 shutter extraction, 100 Hz IMU orientation math, CANedge staging, 6-DOF projection |
| **[`interactive_architecture_map.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_architecture_map.html)** | Interactive visual architecture | Interactive visual node graph, component inspector, live data flow animator (open in any browser) |
| **[`interactive_multithreading_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_multithreading_explorer.html)** | Interactive thread explorer | Real-time multi-track thread simulator, buffer watermarks, thread priority matrix inspector |

---

## 4. Suggested Engineer Reading Path

To rapidly master the codebase and assume operational responsibility:

```
[Day 1: System Level]
   │
   ├─► Read: README.md (Mission & Overview)
   ├─► Open: interactive_architecture_map.html (Explore System Topology)
   └─► Read: 01_SYSTEM_ARCHITECTURE.md (Data Flow & Storage Schemas)
   │
[Day 2: Concurrency & Hardware Mechanics]
   │
   ├─► Read: 02_MULTITHREADING_AND_CONCURRENCY.md (Thread Priorities & Rings)
   ├─► Open: interactive_multithreading_explorer.html (Simulate Buffer Loads)
   └─► Read: 04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md (Radar TLVs & Camera Shutter Sync)
   │
[Day 3: Codebase Deep Dive & Verification]
   │
   ├─► Reference: 03_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md (Package Encyclopedia)
   ├─► Run JVM Unit Tests: ./gradlew testDebugUnitTest
   └─► Inspect Live Hardware Telemetry via ADB Logcat
```

---

## 5. Build, Test & Hardware Quick-Start

### Setting Build Environment
RoadSense requires Android Studio Giraffe / Iguana / Koala or later with JDK 17. In PowerShell on the local workstation:

```powershell
# Set JBR Java Home
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"

# Run JVM Unit Tests
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug
```

### Live ADB Telemetry Monitoring
To monitor real-time sensor events and flight logs across all subsystems:

```powershell
& "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s RoadSense:D CanedgeIngestion:D RadarSerialService:D AppLogger:D CameraEngine:D SpatialProjection:D
```
