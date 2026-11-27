# RoadSense: System Architecture & Storage Specification

> **Document Name:** `01_ARCHITECTURE_AND_STORAGE.md`  
> **Location:** `context/01_ARCHITECTURE_AND_STORAGE.md`  
> **Audience:** Autonomous AI Coding Agents & System Architects  

---

## 1. High-Level Software Architecture

RoadSense uses modern Android MVVM (Model-View-ViewModel) architecture with unidirectional data flow (UDF) powered by Jetpack Compose, Kotlin Coroutines, and reactive `StateFlow` primitives.

```mermaid
graph TD
    UI[Jetpack Compose Cockpit<br>MainActivity / TabRow] --> VM[RadarViewModel<br>Central State Coordinator]
    
    VM --> SM[SessionManager<br>Master Lifecycle & Timeline]
    VM --> RSS[RadarSerialService<br>3.125 MBaud USB UART]
    VM --> CM[CameraEngine<br>Camera2 + MediaRecorder]
    VM --> GM[GnssLocationManager<br>GPS + Pre-Fix SVs & NMEA]
    VM --> CIM[CanedgeIngestionManager<br>Staging Pool & Smart Pruner]
    VM --> SPE[SpatialProjectionEngine<br>6-DOF Extrinsics & Pinhole Projections]
    VM --> CSM[CalibrationStorageManager<br>Profiles & Persistence]
    VM --> AL[AppLogger<br>Flight Recorder Dual-Stream]

    SM --> DiskSessions[Storage: sessions/session_*/]
    CIM --> DiskPool[Storage: sessions/canedge_pool/]
    CSM --> DiskCalib[Storage: calibration/radar_camera_calib.json]
    AL --> DiskAppLogs[Storage: app_logs/app_run_*/]
    AL --> DiskSessionDebug[Storage: session_*/session_debug.log]
```

---

## 2. Core Package & Component Mapping

| Subsystem / Layer | Key Kotlin Classes | Primary Responsibility |
|---|---|---|
| **UI Cockpit & Readiness HUD** | `ui/MainActivity.kt`<br>`ui/RadarViewModel.kt`<br>`ui/SensorReadinessEvaluator.kt`<br>`ui/components/LiveMetricsBar.kt`<br>`ui/components/gl/*.kt`<br>`ui/screens/*.kt` | Multi-tab `KeepAliveTabSlot` Compose UI with 7 cockpit tabs: Radar, GNSS, IMU, Camera, CANedge, SBS, and Storage. Features Tier C **OpenGL ES 3.0 2D/3D BEV Engine** (`RadarGlBevRenderer`, `BevCameraController`, `BevPanelSettings`, mutual-exclusion `GL`/`Skia` toggle, Foxglove colormaps, Vertical Stixels, Decay trails & TLV 4 lanes), `LiveMetricsCanvasHud` traffic-light pills, Fullscreen Camera/SBS, Calibration Studio, and `Choreographer` VSync `RadarDisplayPacer`. |
| **Session Control** | `recording/SessionManager.kt`<br>`recording/SessionInfo.kt`<br>`recording/SessionTimelineWriter.kt` | Manages active recording sessions, folder initialization, metadata generation, and nanosecond monotonic timestamp events. |
| **Radar Ingestion** | `radar/service/RadarSerialService.kt`<br>`radar/parser/RadarPacketParser.kt`<br>`radar/model/RadarFrame.kt` | Custom USB FTDI/CDC serial driver operating at 3.125 Mbps. Rings buffers and decodes TLV binary frames into point clouds and clusters. |
| **IMU & Vehicle Dynamics** | `imu/ImuManager.kt`<br>`imu/ImuSample.kt`<br>`imu/ImuSamplingBenchmark.kt`<br>`ui/screens/ImuDashboardCard.kt` | High-rate **200 Hz** STMicroelectronics LSM6DSL 6-DOF Accel/Gyro, 100 Hz Linear Acceleration without $g$, and 100 Hz Game Rotation Vector. Acquired via dedicated background `HandlerThread` with primary-driver Hz reporting and empirical jitter profiling. |
| **Camera Capture** | `camera/CameraEngine.kt`<br>`camera/AutoFitSurfaceView.kt`<br>`camera/CameraSessionRecorder.kt`<br>`ui/components/PersistentCameraViewportHost.kt` | Camera2 + `MediaRecorder` video capture, smart infinity focus lock, tap-to-focus/AE lock, Yellow `STBY` warm-pause when off-tab, timeline-indexed frame logging (`session_timeline.csv`), and single persistent `SurfaceView` lifecycle across tabs/fullscreen. |
| **Sensor Fusion & Spatial Calibration** | `fusion/engine/SpatialProjectionEngine.kt`<br>`fusion/engine/CameraIntrinsicsProvider.kt`<br>`fusion/model/CalibrationParameters.kt`<br>`fusion/storage/CalibrationStorageManager.kt`<br>`ui/components/ViewfinderRadarOverlay.kt` | 6-DOF extrinsic rigid transform ($[\mathbf{R} \mid \mathbf{T}]$), Camera2 intrinsic matrix ($K$ with 16:9 ISP crop), real-time closed-form touch reverse solver, ground plane contact footprint projection, hybrid RViz-style radar lollipops, concentric range arcs, and Painter's algorithm depth-sorting. |
| **GNSS Tracking** | `gnss/GnssLocationManager.kt`<br>`ui/screens/GnssDashboardCard.kt` | Android `LocationManager` (`GPS_PROVIDER`) + `FusedLocationProviderClient` + NMEA sentence parsing, live pre-fix satellite status (`used/inView`), A-GPS XTRA/time injection, and `hasTrueSatelliteLock` gate. Generates synchronized `gnss_fixes.csv`. |
| **CANedge2 Sync** | `canedge/ingestion/CanedgeIngestionManager.kt`<br>`canedge/ingestion/CanedgeSyncPlanner.kt`<br>`canedge/repository/CanedgeRepository.kt`<br>`canedge/network/CanedgeHttpClient.kt` | ESP32 REST client, persistent staging pool (`canedge_pool/`), deferred drive sync, `CALIBRATING` RTC offset gate, live rollover clock measurement, smart auto-pruning engine, and unified file explorer. |
| **Diagnostics** | `logging/AppLogger.kt` | Continuous app-wide flight recorder (`app_system.log`) and active session flight recorder (`session_debug.log`). |

---

## 3. Storage Directory Hierarchy (Physical On-Device Layout)

All persistent logs, calibration profiles, and active sessions are stored inside the app's external files directory:
`/sdcard/Android/data/com.bajajauto.roadsense/files/`

```text
/sdcard/Android/data/com.bajajauto.roadsense/files/
 │
 ├── calibration/                                  <-- SENSOR FUSION & EXTRINSIC CALIBRATION PROFILES
 │    └── radar_camera_calib.json                  <-- Active 6-DOF extrinsics, pitch/yaw/roll, setback & heights
 │
 ├── app_logs/                                     <-- CONTINUOUS APP-WIDE FLIGHT RECORDER LOGS
 │    ├── app_run_20260911_090015/
 │    │    └── app_system.log                      <-- Full idle diagnostics (Wi-Fi, sync, UI, crashes)
 │    └── app_run_20260911_121542/
 │         └── app_system.log
 │
 └── sessions/
      │
      ├── canedge_pool/                            <-- PERSISTENT STAGING POOL FOR CANedge MF4 CHUNKS
      │    ├── 00000041_00000050.MF4               <-- Format: {FOLDER_ID}_{FILE_ID}.MF4
      │    ├── 00000041_00000051.MF4
      │    └── 00000041_00000052.MF4
      │
      └── session_20260911_111401/                 <-- PERMANENT MULTI-SENSOR RECORDING SESSION
           ├── session_metadata.json               <-- Session-wide descriptor & hardware properties
           ├── session_debug.log                   <-- Active recording flight recorder stream
           ├── session_timeline.csv                <-- Nanosecond cross-sensor monotonic event index
           │
           ├── radar/
           │    ├── radar_raw_stream.bin           <-- Raw binary UART byte stream from TI sensor
           │    └── radar_frames.bin               <-- Decoded binary framed packets (ROAD magic)
           │
           ├── camera/
           │    └── camera_video.mp4               <-- Recorded H.264 video file (frame timestamps indexed in session_timeline.csv)
           │
           ├── gnss/
           │    └── gnss_fixes.csv                 <-- Lat, lon, alt, speed, bearing, accuracy
           │
           ├── imu/
           │    └── imu_frames.csv                 <-- 200 Hz LSM6DSL Accel/Gyro, 100 Hz Linear Accel & Orientation
           │
           ├── sync/
           │    └── sync_keyframes.csv             <-- Keyframe monotonic alignment markers
           │
           └── can/
                ├── 00000041_00000051.MF4          <-- CANedge chunks assigned to this drive session
                └── 00000041_00000052.MF4
```

### 3.2 Downstream PC Analysis Storage Layout (`logs/` & `tools/vision/output/`)
When extracted via ADB and processed via the PC Toolchain (`sync_and_process_sessions.py`, `convert_session_to_mcap.py`, `tools/CAN_Processing/`, and `tools/vision/`):
```text
logs/
 └── session_YYYYMMDD_HHMMSS/
      ├── session_YYYYMMDD_HHMMSS.mcap      <-- Unified Foxglove MCAP single-container archive
      │    ├── Channels: /radar/points, /radar/tracks, /camera/video, /camera/calib, /gnss/fix, /imu/*, /can/*, /tf
      │    └── Attachments: session_metadata.json, radar_camera_calib.json, RoadSense_Cockpit_Layout.json
      ├── track_history.json                <-- Legacy web visualizer radar perception & trajectories
      ├── frame_mapping.json                <-- Legacy radar-to-camera temporal sync index
      └── [original pulled sensor subfolders: radar/, camera/, gnss/, imu/, sync/, can/]

tools/vision/output/
 ├── json/<session>_vision_tracks.json      <-- 3D geometric vision detections & contact_patch tracks
 ├── mcap/<session>_with_vision.mcap        <-- Enriched MCAP injecting /vision/* topics
 └── reports/<session>_benchmark_report.json <-- Dual-pass Vision-vs-Radar & pitch regression report
```

---

## 4. Metadata Schemas

### 4.1 `session_metadata.json` Schema
Written by `SessionManager.writeMetadata(sessionInfo)` upon session start and finalized upon session stop:
```json
{
  "sessionId": "session_20260911_111401",
  "startTimeWallMs": 1789105441000,
  "startTimeIso": "2026-09-11T11:14:01.000+05:30",
  "startTimeMonotonicNs": 128459201400000,
  "stopTimeWallMs": 1789105655000,
  "stopTimeIso": "2026-09-11T11:17:35.000+05:30",
  "durationMs": 214000,
  "stopTimeMonotonicNs": 128673201400000,
  "device": {
    "manufacturer": "samsung",
    "model": "SM-M215F",
    "device": "m21",
    "sdkInt": 31
  },
  "radar": {
    "sensor": "TI AWR1843BOOST",
    "baudRateDataPort": 3125000,
    "baudRateCliPort": 115200,
    "totalFrames": 2140,
    "totalBytes": 14582000
  },
  "gnss": {
    "sensor": "Android GNSS Location Provider",
    "totalFixes": 214
  },
  "camera": {
    "sensor": "Android Camera Video Encoder (H.264)",
    "totalFrames": 6420
  },
  "can": {
    "sensor": "CSS Electronics CANedge2",
    "totalFiles": 3
  },
  "activeStreams": [
    "radar/radar_frames.bin",
    "radar/radar_raw_stream.bin",
    "gnss/gnss_fixes.csv",
    "camera/camera_video.mp4",
    "sync/sync_keyframes.csv",
    "can/00000041_00000051.MF4",
    "can/00000041_00000052.MF4",
    "can/00000041_00000053.MF4",
    "session_timeline.csv",
    "session_debug.log"
  ]
}
```

### 4.2 `session_timeline.csv` Format
The monotonic synchronization spine for post-processing alignment:
```csv
elapsedRealtimeNs,sensor,event,sequenceId,relativePath,summary
128459201400000,SESSION,START,0,session_metadata.json,Session initialized: session_20260911_111401
128459235000000,RADAR,FRAME,1,radar/radar_frames.bin,points=32;clusters=4;tracks=2
128459240000000,CAMERA,FRAME,1,camera/camera_video.mp4,ptsUs=1000;width=1920;height=1080
128459290000000,GNSS,FIX,1,gnss/gnss_fixes.csv,lat=18.5204;lon=73.8567;speed=11.2;bearing=88.4
128469201400000,CAN,MF4_FILE,1,can/00000041_00000051.MF4,size=2415800;remotePath=/00000041/00000051.MF4
128673201400000,SESSION,STOP,0,session_metadata.json,Session finished: totalFrames=2140;totalFixes=214
```
