# RoadSense

> **Autonomous Multimodal Automotive Telemetry, Radar-Camera Spatial Fusion & Synchronized ADAS Data Acquisition Cockpit**

[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B%20%28API%2031%2B%29-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09.00-blue.svg)](https://developer.android.com/jetpack/compose)
[![Baud Rate](https://img.shields.io/badge/Radar%20UART-3%2C125%2C000%20Baud-orange.svg)](context/02_RADAR_AND_HIGH_SPEED_SERIAL.md)
[![CAN Bus](https://img.shields.io/badge/CAN%20Logger-CANedge2%20MF4-red.svg)](context/04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md)

---

## 1. Executive Overview

**RoadSense** is a high-performance Android data acquisition, multimodal recording, and autonomous telemetry cockpit engineered for vehicle dynamics research, radar point-cloud perception, and advanced driver assistance systems (ADAS).

The application operates as an autonomous multi-sensor flight recorder, synchronizing four distinct real-time data streams against a shared, strictly monotonic nanosecond timebase:

1. **Automotive mmWave Radar:** Texas Instruments **AWR1843BOOST** 77–81 GHz FMCW radar streaming raw point clouds, clusters, and hardware EKF target tracks over high-speed USB serial at **3,125,000 baud** (3.125 Mbps).
2. **Camera Video & Perception:** Hardware-accelerated 1080p/720p H.264 video via Camera2 with per-frame monotonic exposure timestamps (`camera_frames.csv`), autonomous dual-zone Road AE, and hyperfocal optical infinity focus lock.
3. **High-Accuracy GNSS:** Real-time vehicular trajectory, ground speed, bearing, altitude, and raw NMEA sentence logging via Android Location Services (`gnss_track.csv`).
4. **Automotive CAN Bus:** CSS Electronics **CANedge2** dual-channel CAN/CAN-FD logger logging cyclic 1-minute split MF4 (MDF4) files, ingested wirelessly via local Wi-Fi AP using an autonomous 2-folder staging pool and post-recording finalizer.

---

## 2. Key Capabilities & Subsystems

### 2.1 6-DOF Radar-Camera Spatial Calibration Studio
* **Rigid Spatial Formulation ($[\mathbf{R} \mid \mathbf{T}]$):** Full 6 degrees of freedom (lateral mount offset $\Delta X$, bumper-to-windshield setback $\Delta Y$, height offset $\Delta Z$, pitch $\theta$, yaw $\psi$, roll $\phi$).
* **Camera2 Pinhole Intrinsics ($K$):** Automatically queries physical active sensor array and lens properties to dynamically compute focal lengths ($f_x, f_y$) and optical principal points ($c_x, c_y$).
* **Interactive Reverse Touch Solver:** Allows calibration in under 60 seconds on roadside test drives. By simply dragging a reticle onto a known physical target (e.g. parked vehicle bumper at $10\text{m}$), the solver analytically computes the exact camera Pitch and Yaw in $O(1)$ closed-form time without iterative optimization.
* **Trackpad Delta Drag Mode:** Enables incremental relative adjustment from anywhere on screen, preventing the user's finger from blocking or occluding the target vehicle.
* **Quick Nudge Bar:** Floating toolbar offering $\pm 0.1^\circ$ and $\pm 0.5^\circ$ micro-step angle nudges, instant angle revert, and JSON profile persistence to `files/calibration/radar_camera_calib.json`.

### 2.2 Hybrid Radar Lollipop & Ground Footprint Visualization
To overcome 3D-to-2D depth ambiguity, distance compression ($1/Y$), and occlusion reversal (where far targets float ambiguously on foreground cars), RoadSense incorporates an RViz-inspired hybrid projection model:
* **Ground Contact Footprints:** Projects 12-segment closed polygons resting flat on the asphalt road plane ($Z_{\text{road}} = -H_{\text{radar}}$), foreshortening authentically into 2D ellipses in camera perspective.
* **Vertical Perspective Stems:** Renders vertical perspective lines connecting the road contact base to the elevated vehicle centroid, tilting naturally with camera pitch and road slope.
* **Lollipop Heads & Glow Halos:** Distance-scaled head radius with glowing halo, solid core, and white specular center for tracked targets.
* **Track-Point Gating & Clutter Rejection:** Automatically gates raw radar reflections within $1.8\,\text{m}$ of active EKF tracks.
* **Painter's Algorithm Depth-Sorting:** Sorts all targets descending by forward depth (`sortByDescending { it.depthM }`), guaranteeing distant targets ($100\,\text{m}$) render first and closer vehicles ($10\,\text{m}$) render on top.
* **Doppler Velocity Color Palette:**
  * **Closing in / Hazard ($V_r < -0.5\,\text{m/s}$):** Vivid Red (`#FFFF5252`)
  * **Opening gap ($V_r > +0.5\,\text{m/s}$):** Bright Green (`#FF69F0AE`)
  * **Tracked Obstacle ($|V_r| \le 0.5\,\text{m/s}$):** High-Visibility Amber (`#FFFFD54F`)
  * **Stationary Point / Clutter:** Crisp Cyan (`#FF40C4FF`)
* **Distance Alpha Fall-Off:** $\alpha = \text{clamp}(1.0 - \frac{Y}{100\text{m}} \times 0.65, 0.20, 0.95)$ gracefully fades distant detections near the horizon.
* **Telemetry Badges:** Floating pill badges displaying hardware Track ID and metric distance (e.g. `ID:#1 • 14m`).

### 2.3 Perspective Radar Range Rings & Boresight Overlay
* Concentric distance arcs at $10\text{m}, 30\text{m}, 60\text{m}, 120\text{m}$ calculated on the road plane.
* Azimuth clamped to visible camera HFOV ($\approx \pm 28^\circ$) to eliminate unnatural vertical curvature.
* Staggered distance labels preventing vertical collision near the horizon.
* Longitudinal center boresight ray ($0^\circ$ azimuth) from $2\text{m}$ to $120\text{m}$.

### 2.4 Autonomous Dual-Zone Road AE & Optical Infinity Lock
* **Dynamic Road AE:** Samples a $32 \times 24$ preview thumbnail at 4 Hz to compute ITU-R BT.601 luminance across the sky (top 35%) and road (bottom 65%). When high-contrast sky bloom occurs ($R \ge 1.8\times$), it restricts metering to the lower road zone with $+0.5$ to $+1.0$ EV compensation to illuminate vehicles and road hazards.
* **Hyperfocal Infinity Lock:** Locks lens focus to optical infinity (`LENS_FOCUS_DISTANCE = 0.0f`) upon session recording, preventing continuous autofocus from hunting or locking onto windshield rain/dust.
* **Tap-to-Focus & AE Lock:** Tap anywhere on the live viewfinder to target an obstacle with an interactive 12% metering bounding box.

### 2.5 CANedge2 Autonomous Ingestion Engine
* **2-Folder Staging Pool (`canedge_pool/`):** Autonomous background sync keeps the 2 most recent CANedge session folders downloaded locally.
* **Deferred Post-Recording Finalizer:** During high-speed driving trials, CAN downloads pause to preserve CPU/battery; upon session stop, the finalizer sweeps and assigns the exact MF4 logs to the completed session folder.
* **Smart FIFO Auto-Pruning:** Background garbage collection purges downloaded files from the staging pool while guaranteeing active session files are permanently preserved.

---

## 3. Hardware Architecture & Specifications

| Component | Hardware Model | Interface & Parameters | Primary Role |
|---|---|---|---|
| **Automotive Radar** | Texas Instruments AWR1843BOOST | USB Serial @ **3,125,000 baud** (FTDI / CDC-ACM) | 77 GHz FMCW mmWave Point Cloud & EKF Tracker |
| **CAN Bus Logger** | CSS Electronics CANedge2 | Dual CAN/CAN-FD, 802.11 b/g/n Wi-Fi AP | 1-minute split MF4 vehicle telemetry logging |
| **Video Camera** | Android Device Camera Sensor | Camera2 HAL (1080p/720p H.264 MP4) | Scene context, lane dynamics & visual tracking |
| **Location** | Multi-Constellation GNSS | GPS + GLONASS + Galileo + BeiDou + NMEA | High-precision trajectory, ground speed & bearing |
| **Compute Host** | Android Smartphone (API 31+) | USB OTG Host + Wi-Fi Client | Central synchronization, logging & cockpit UI |

---

## 4. Cockpit UI Architecture

The interface is built using Jetpack Compose with unidirectional data flow (UDF) coordinated by `RadarViewModel`:

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│  Live Telemetry Ticker (Sensors Hz | GNSS Fix | Battery % & Temp | CPU % & Temp) │
├──────────────────────────────────────────────────────────────────────────────────┤
│  Top Navigation Tabs:                                                            │
│  [ RADAR ]  [ GNSS ]  [ CAMERA ]  [ CANEDGE ]  [ SBS (Side-by-Side) ]  [ SESSIONS ]│
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Active Dashboard View:                                                          │
│  • Edge-to-edge dedicated Fullscreen Viewfinder mode                             │
│  • Fullscreen Spatial Calibration Studio with horizon line & ground ladder       │
│  • Interactive Quick Nudge Bar for roadside fine-tuning                          │
│  • Synchronized Side-by-Side (SBS) dual Camera & Radar view                      │
│  • Multi-column hierarchical CANedge file explorer with cloud/local sync status  │
│                                                                                  │
├──────────────────────────────────────────────────────────────────────────────────┤
│  Master Recording Bar: [ START / STOP SESSION ] (Session Timer, Disk Space, Log) │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Storage Directory Hierarchy

All session recordings, persistent flight recorder logs, and calibration profiles are stored in:  
`/sdcard/Android/data/com.bajajauto.roadsense/files/`

```text
/sdcard/Android/data/com.bajajauto.roadsense/files/
 │
 ├── calibration/
 │    └── radar_camera_calib.json                  <-- Active 6-DOF extrinsics & mounting profile
 │
 ├── app_logs/
 │    └── app_run_YYYYMMDD_HHMMSS/
 │         └── app_system.log                      <-- Continuous flight recorder log
 │
 └── sessions/
      │
      ├── canedge_pool/                            <-- Staging cache for CANedge MF4 chunks
      │    ├── 00000041_00000050.MF4
      │    └── 00000041_00000051.MF4
      │
      └── session_YYYYMMDD_HHMMSS/                 <-- Permanent multi-sensor session folder
           ├── session_metadata.json               <-- Master descriptor, sensor counts, offsets
           ├── session_debug.log                   <-- Active recording diagnostic stream
           ├── session_timeline.csv                <-- Monotonic nanosecond cross-sensor sync index
           │
           ├── radar/
           │    ├── radar_raw_stream.bin           <-- Unmodified byte stream from TI sensor
           │    └── radar_frames.bin               <-- Decoded binary framed packets (ROAD header)
           │
           ├── camera/
           │    ├── camera_video.mp4               <-- H.264 video recording
           │    └── camera_frames.csv              <-- Nanosecond frame exposure timestamps
           │
           ├── gnss/
           │    └── gnss_track.csv                 <-- WGS84 fixes, speed, bearing, accuracy
           │
           └── can/
                ├── 00000041_00000051.MF4          <-- CANedge chunks assigned to this session
                └── 00000041_00000052.MF4
```

---

## 6. PC Diagnostic & Processing Pipeline

The `tools/` and `scripts/` directories provide a complete Python data extraction and offline validation suite:

```powershell
# Extract sessions and continuous logs from phone via ADB
python tools/sync_and_process_sessions.py --sync-only

# Run full processing pipeline (unpack radar frames, video index, and generate web visualizer JSON)
python tools/sync_and_process_sessions.py

# Standalone session health check & frame integrity audit
python scripts/session_health_check.py --session sessions/session_YYYYMMDD_HHMMSS/

# Cross-sensor monotonic alignment audit
python scripts/audit_cross_sensor_sync.py --session sessions/session_YYYYMMDD_HHMMSS/
```

---

## 7. Developer Build & Runbook

### ⚠️ Local Environment Quirk: Invalid System `JAVA_HOME`
Before running Gradle commands in PowerShell, always set `$env:JAVA_HOME` to Android Studio's bundled JetBrains Runtime (JBR):

```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
```

### Quick Build & Test Commands:
```powershell
# 1. Run JVM Unit Tests
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest

# 2. Assemble Debug APK
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug

# 3. Clean and Rebuild
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew clean assembleDebug
```

### ADB Deployment & Live Monitoring:
```powershell
$ADB = "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Deploy APK onto connected device (preserving user data)
& $ADB install -r app\build\outputs\apk\debug\app-debug.apk

# Monitor live multi-sensor logcat
& $ADB logcat -s RoadSense:D CanedgeIngestion:D RadarSerialService:D AppLogger:D CameraEngine:D SpatialProjection:D
```

---

## 8. Documentation Directory Map

For in-depth architectural specifications, refer to the specialized documentation suite:

* **[`context/`](context/)**: Authoritative modular system context:
  * [`00_MASTER_EXECUTIVE_HANDOVER.md`](context/00_MASTER_EXECUTIVE_HANDOVER.md) — Executive handover & roadmap
  * [`01_ARCHITECTURE_AND_STORAGE.md`](context/01_ARCHITECTURE_AND_STORAGE.md) — Architecture & storage specification
  * [`02_RADAR_AND_HIGH_SPEED_SERIAL.md`](context/02_RADAR_AND_HIGH_SPEED_SERIAL.md) — 3.125 Mbps UART & TLV framing
  * [`03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md`](context/03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md) — Camera2, Road AE & sync
  * [`04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md`](context/04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md) — CANedge2 REST client & pool
  * [`05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md`](context/05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md) — Dual-stream flight recorder
  * [`06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md`](context/06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md) — Python pipeline & tools
  * [`07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md`](context/07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md) — Local quirks & runbook
  * [`08_RADAR_CAMERA_SPATIAL_CALIBRATION_AND_FUSION.md`](context/08_RADAR_CAMERA_SPATIAL_CALIBRATION_AND_FUSION.md) — 6-DOF calibration, reverse solver & lollipop overlays
* **[`intel/`](intel/)**: In-depth post-mortems, hardware specifications, presentation decks, and calibration guides.
* **[`Future_stuff.md`](Future_stuff.md)**: Product backlog, ADAS fusion phases, and planned enhancements.

---

## 9. License & Operational Guardrails

* **Confidentiality:** Proprietary automotive software developed for research and development.
* **Workspace Guardrail:** External reference repository `D:\Work\Repo\CANenbl_unifiedRadarTracker` is strictly read-only.