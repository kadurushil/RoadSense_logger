# RoadSense: AI Agent Executive Handover & Master Context Guide

> **Document Name:** `00_MASTER_EXECUTIVE_HANDOVER.md`  
> **Location:** `context/00_MASTER_EXECUTIVE_HANDOVER.md`  
> **Audience:** Autonomous AI Coding Agents & Incoming Core Engineers  
> **Workspace Path:** `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense`  
> **Active Branch:** `feature/3125000-baud`  
> **Last Handover Date:** September 11, 2026  

---

## 1. Executive Summary & Project Mission

**RoadSense** is a high-performance Android automotive data acquisition, multimodal recording, and autonomous telemetry cockpit developed for vehicle dynamics research, radar point-cloud perception, and advanced driver assistance systems (ADAS).

The application operates as an autonomous multi-sensor logger synchronizing four distinct streams:
1. **Automotive Radar:** TI AWR1843BOOST 77 GHz mmWave radar streaming raw point clouds, clusters, and hardware EKF target tracks over high-speed USB serial at **3,125,000 baud** (3.125 Mbps).
2. **Camera Video:** Synchronized video capture (1080p/720p H.264 MP4) via CameraX with nanosecond frame capture timestamps (`camera_frames.csv`).
3. **GNSS / GPS:** Real-time vehicular trajectory, heading, ground speed, and NMEA fixes via Android Location Services (`gnss_track.csv`).
4. **Automotive CAN Bus:** CSS Electronics **CANedge2** dual-channel CAN/CAN-FD logger logging cyclic 1-minute (60s) split MF4 (MDF4) files, synchronized via local Wi-Fi AP using an autonomous 2-folder staging pool and post-recording finalizer.

---

## 2. Recent Major Milestones Achieved

As of September 11, 2026, the following major architectural milestones have been completed and verified with passing JVM unit tests and clean APK builds:

| Feature / Subsystem | Implementation Status | Key Components Involved |
|---|---|---|
| **High-Speed Radar UART** | **Production Ready** (3.125 Mbps) | `RadarSerialService`, `RadarPacketParser`, `RadarViewModel` |
| **Monotonic Sync Index** | **Production Ready** (Nanosecond accurate) | `SessionTimelineWriter`, `session_timeline.csv` |
| **Unified 3-Col File Explorer**| **Production Ready** (Nested tree + 3 indicators) | `CanedgeDashboardCard`, `CanedgeFile.kt` |
| **Autonomous CAN Staging Pool**| **Production Ready** (2-folder prioritized sync)| `CanedgeIngestionManager`, `canedge_pool/` |
| **Robust Name-by-Name Sync** | **Production Ready** (Overflow fixed) | Lookup table against `_remoteFiles` in `CanedgeIngestionManager` |
| **Smart Auto-Pruning Engine** | **Production Ready** (Auto-rescue & duplicate clearing)| Background FIFO + session rescue in `CanedgeIngestionManager` |
| **Continuous App Diagnostics** | **Production Ready** (Continuous idle logging) | `AppLogger`, `app_logs/app_run_*/app_system.log` |
| **PC ADB Processing Pipeline** | **Production Ready** (Automated sync & visualizer JSON) | `tools/sync_and_process_sessions.py`, `sync_and_process_logs.bat` |
| **Synchronized SBS Dashboard** | **Production Ready** (Camera & Radar side-by-side) | `SbsDashboardCard.kt`, `CockpitTab.SBS` in `MainActivity.kt` |
| **Offline Validation Scripts** | **Production Ready** (7 diagnostic & health tools) | `scripts/*.py`, `scripts/README.md` |
| **Robust Viewfinder Continuity**| **Production Ready** (Touch/swipe glitch eliminated) | `CameraDashboardCard.kt`, `SbsDashboardCard.kt`, `CameraEngine.kt` |
| **Hyperfocal Infinity & Tap-to-Lock**| **Production Ready** (Auto infinity on REC, tap AF/AE lock)| `CameraEngine.kt`, `CameraDashboardCard.kt` |
| **Autonomous Road AE Engine**| **Production Ready** (Dual-zone photometric sky bloom elimination)| `CameraEngine.kt`, `RoadAeMode`, `RoadAeState`, `RadarViewModel.kt` |

---

## 3. Specialized Context Navigation Map

To enable another AI agent or engineer to immediately locate deep architectural insights, the `context/` directory has been modularized into 7 specialized intelligence files:

```text
context/
 ├── 00_MASTER_EXECUTIVE_HANDOVER.md             <-- [YOU ARE HERE] Master executive overview & map
 ├── 01_ARCHITECTURE_AND_STORAGE.md             <-- Overall system design, MVVM state, storage layout
 ├── 02_RADAR_AND_HIGH_SPEED_SERIAL.md          <-- 3.125 MBaud serial driver, TLV parser & EKF tracker
 ├── 03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md    <-- CameraX, GNSS track & nanosecond timeline sync
 ├── 04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md <-- CANedge2 REST API, staging pool & smart auto-pruner
 ├── 05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md      <-- AppLogger, continuous app_logs/ & flight recorder
 ├── 06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md   <-- Python ADB extraction & web visualizer schema
 └── 07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md     <-- Java Home quirks, workspace guardrails & commands
```

### Quick Lookup Guide:
* **Working on Radar packet drops or serial framing?** Read [`02_RADAR_AND_HIGH_SPEED_SERIAL.md`](context/02_RADAR_AND_HIGH_SPEED_SERIAL.md).
* **Working on CANedge2 sync, Wi-Fi downloads, or pruning?** Read [`04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md`](context/04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md).
* **Working on Camera AF, tap-to-focus, or video frames?** Read [`03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md`](context/03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md) and [`Future_stuff.md`](Future_stuff.md).
* **Debugging boot issues, silent crashes, or sync issues?** Read [`05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md`](context/05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md).
* **Setting up the build environment, executing gradle, or using ADB?** Read [`07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md`](context/07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md).

---

## 4. Current Repository State & Build Commands

* **Branch:** `feature/3125000-baud`
* **Clean Build APK Target:** `app\build\outputs\apk\debug\app-debug.apk`

### ⚠️ Crucial Local Environment Quirk:
The Windows system environment variable `JAVA_HOME` points to an invalid/uninstalled Corretto path. **Always** set `$env:JAVA_HOME` to Android Studio's bundled JetBrains Runtime before running gradle:

```powershell
# 1. Run JVM Unit Tests
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest

# 2. Assemble Debug APK
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug
```

---

## 5. Strict Operational Guardrails

Any AI agent operating in this repository **must observe the following three strict rules**:
1. **Never perform `git commit` or `git push`** without explicit, direct instruction and authorization from the user.
2. **Never install APK onto the device via ADB automatically** unless explicitly told to do so. (Build the APK and present the path to the user).
3. **External Reference Repository is STRICTLY READ-ONLY:**
   Path: `D:\Work\Repo\CANenbl_unifiedRadarTracker`  
   *Rule:* Never modify, delete, edit, or commit files in this external directory. It is purely an algorithmic reference.
