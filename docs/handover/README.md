# RoadSense Automotive Telemetry Platform — Engineering Handover Guide

> **Document Classification:** Autonomous Vehicle Research & Engineering Systems  
> **Target Audience:** Executive Leadership, Chief Engineers, Systems Architects, Perception & Embedded Software Developers  
> **Subsystem Scope:** Multi-Sensor Acquisition, High-Speed Serial Drivers, Camera2 Pipeline, 100 Hz IMU Fusion, CANedge2 Ingestion, 6-DOF Spatial Calibration  
> **Workspace Path:** `AndroidStudioProjects/RoadSense`  
> **Status:** Production / Research Baseline (API 34)

---

## 1. Executive Summary & Genesis: A Need-Based Engineering Invention

**RoadSense** is a specialized, production-grade automotive multi-sensor data acquisition and edge-perception platform developed for motorcycle and passenger vehicle Advanced Driver Assistance Systems (ADAS) research. Operating on an Android smartphone hardware host, RoadSense bridges industrial vehicular sensor protocols with mobile edge compute to capture high-fidelity, synchronized multi-modal datasets for perception network training, spatial calibration, and active safety feature validation.

### The Operational Field Bottleneck
Prior to RoadSense, on-road test instrumentation relied on an ad-hoc, stopgap setup: a laptop running custom Python scripts connected to sensors via external USB hubs and breakout cables:
* **The 30-Minute Endurance Wall:** Under the load of USB polling, video capture, and coordinate math, laptop batteries depleted within **20 to 30 minutes**, forcing riders to abort test rides repeatedly.
* **Ergonomic & Severe Rider Safety Hazards:** In motorcycle testing, carrying a running, heat-generating laptop in a rider's backpack or strapped to a tank bag introduced severe physical safety risks, rider fatigue, and cable entanglement hazards.
* **Vibration Disconnects & Silent Data Loss:** High-vibration vehicular environments caused intermittent USB port disconnections that silently corrupted entire test sessions.
* **Non-Deterministic Jitter:** Python GIL and OS scheduling introduced multi-millisecond clock drift, destroying cross-sensor temporal alignment.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 THE LEGACY CRISIS                                      │
│                                                                                        │
│   Laptop + Python Script         ❌ 20-30 min battery limit (aborted test runs)         │
│   in Backpack / Tank Bag         ❌ Severe safety hazard & physical rider fatigue      │
│                                  ❌ High-vibration USB dropouts & corrupted dumps       │
│                                  ❌ Thermal throttling & non-deterministic OS jitter   │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │
                                  NEED-BASED INVENTION
                                            │
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              ROADSENSE EDGE PLATFORM                                   │
│                                                                                        │
│   Palm-Sized Automotive          ✅ Multi-hour continuous endurance on internal battery │
│   Perception Edge Host           ✅ Zero cables to rider; rigid cockpit bar mount       │
│                                  ✅ 3.125 Mbps zero-drop hardware-buffered ingestion    │
│                                  ✅ Nanosecond-accurate deterministic monotonic sync    │
│                                  ✅ Autonomous Wi-Fi vehicle CAN bus staging            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### Strategic Business Value & Industry Benchmarking
* **The Industry Standard:** Commercial automotive loggers from **Vector Informatik** (VN1630 / GL loggers) and **Intrepid Control Systems** (neoVI FIRE 2) cost upwards of **₹15–20 Lakhs ($18k–$25k+) per unit**, require expensive recurring software licenses, and lack integrated camera optics or spatial calibration engines.
* **High Execution Velocity (< 1 Month):** Designed, built, and field-validated in less than 30 days by the internal engineering team.
* **The Fleet Multiplier:** Achieving a **>97% direct Capex reduction** enables instrumenting an entire fleet of 30–50 production test vehicles for the cost of a single traditional industrial DAQ box.

---

## 2. Handover Documentation Architecture: Dual-Track Structure

To serve both executive leadership / systems architects during project reviews and incoming software engineers maintaining the codebase, the handover suite is organized into two distinct tracks:

```
docs/handover/
├── README.md                                          <-- Master Navigation Hub (This file)
│
├── [TRACK 1: EXECUTIVE & SYSTEMS ENGINEERING REVIEW]
│   ├── 00_EXECUTIVE_BUSINESS_AND_PRODUCT_OVERVIEW.md  <-- Business Rationale, Capex ROI & ADAS Roadmap
│   ├── 01_EMBEDDED_SYSTEMS_AND_CONTROL_SOLUTIONS.md   <-- What We Solved: Embedded & Control Breakdown
│   └── executive_business_and_systems_showcase.html   <-- Interactive Presentation Dashboard & ROI Tool
│
└── [TRACK 2: DEEP TECHNICAL & CODEBASE IMPLEMENTATION]
    ├── 02_SYSTEM_ARCHITECTURE.md                      <-- Physical Bus Topology, MVVM & Storage Layouts
    ├── 03_MULTITHREADING_AND_CONCURRENCY.md           <-- Linux Thread Priorities, Nice Values & Ring Buffers
    ├── 04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md         <-- TLV Decoding, Camera2 HAL Hooks & 6-DOF Math
    ├── 05_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md       <-- Class-by-Class Technical Encyclopedia (12 Packages)
    ├── interactive_architecture_map.html              <-- Visual Component Architecture Explorer
    └── interactive_multithreading_explorer.html       <-- Live Multithreading & Buffer Watermark Simulator
```

---

## 3. Handover Documentation Sitemap & Key Takeaways

| Track | Document / Visualizer | Primary Audience | Key Takeaways |
| :--- | :--- | :--- | :--- |
| **Track 1** | **[`00_EXECUTIVE_BUSINESS_AND_PRODUCT_OVERVIEW.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/00_EXECUTIVE_BUSINESS_AND_PRODUCT_OVERVIEW.md)** | Leadership, VPs of R&D, Chief Engineers | Need-based invention, laptop battery crisis, Vector/Intrepid ₹15-20L cost benchmark, >97% Capex ROI, fleet scalability, 3-phase ADAS product roadmap |
| **Track 1** | **[`01_EMBEDDED_SYSTEMS_AND_CONTROL_SOLUTIONS.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/01_EMBEDDED_SYSTEMS_AND_CONTROL_SOLUTIONS.md)** | Chief Engineers, Systems Architects | Embedded & control breakthroughs: Monotonic clock discipline (no PPS), 3.125 Mbps UART buffers, autonomous Road AE, 6-DOF Reverse Touch Solver, air-gapped CAN staging |
| **Track 1** | **[`executive_business_and_systems_showcase.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/executive_business_and_systems_showcase.html)** | Management Reviews, Live Briefings | Zero-dependency interactive web dashboard: Dynamic fleet ROI slider, 5-problem simulator, live 6-DOF reverse touch calibration canvas, presentation & print mode |
| **Track 2** | **[`02_SYSTEM_ARCHITECTURE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/02_SYSTEM_ARCHITECTURE.md)** | Software Architects, Pipeline Engineers | Physical hardware bus architecture, Unidirectional Data Flow (UDF), session folder hierarchies, metadata and timeline CSV schemas |
| **Track 2** | **[`03_MULTITHREADING_AND_CONCURRENCY.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/03_MULTITHREADING_AND_CONCURRENCY.md)** | Concurrency & Real-Time Developers | Complete priority matrix (Linux niceness), looper worker threads, coroutine dispatchers, GC churn elimination, race condition prevention |
| **Track 2** | **[`04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md)** | Perception & Sensor Fusion Specialists | TI mmWave TLV decoding math, Camera2 shutter extraction, 100 Hz IMU quaternions, CANedge staging, 6-DOF projection & Painter's depth sorting |
| **Track 2** | **[`05_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/05_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md)** | Incoming Core Developers | Complete class-by-class, file-by-file technical encyclopedia across all 12 packages in the application codebase |
| **Track 2** | **[`interactive_architecture_map.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_architecture_map.html)** | Software Developers | Interactive visual node graph, component inspector, live data flow animator (open in any browser) |
| **Track 2** | **[`interactive_multithreading_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/docs/handover/interactive_multithreading_explorer.html)** | Real-Time Platform Engineers | Interactive multi-track thread simulator, buffer watermarks, thread priority matrix inspector |

---

## 4. Suggested Review Reading Paths

### Path A: For Executive Leadership & Upper Management Review
```
[Executive Review Path]
   │
   ├─► 1. Open: executive_business_and_systems_showcase.html (Interactive ROI & System Simulator)
   ├─► 2. Read: 00_EXECUTIVE_BUSINESS_AND_PRODUCT_OVERVIEW.md (Genesis, Capex ROI & Roadmap)
   └─► 3. Read: 01_EMBEDDED_SYSTEMS_AND_CONTROL_SOLUTIONS.md (The 5 Embedded Problems Solved)
```

### Path B: For Incoming Software Engineers & Maintainers
```
[Developer Implementation Path]
   │
   ├─► 1. Read: 02_SYSTEM_ARCHITECTURE.md (Bus Topology & Storage Layout)
   ├─► 2. Read: 03_MULTITHREADING_AND_CONCURRENCY.md (Thread Priorities & Memory Isolation)
   ├─► 3. Read: 04_SENSOR_PIPELINES_AND_SYNC_ENGINE.md (Sensor TLVs & Shutter Hooks)
   ├─► 4. Reference: 05_CODEBASE_DICTIONARY_AND_FILE_GUIDE.md (Package Encyclopedia)
   └─► 5. Verify Build: ./gradlew testDebugUnitTest
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
