# Implementation Plan: RoadSense MCAP (Foxglove) Conversion Toolchain (Phase 1)

> **Document Name:** `mcap_phase1_implementation_plan.md`  
> **Status:** Planning / Awaiting User Approval  
> **Target Subsystem:** PC Tooling, Multimodal Session Pipeline & Foxglove Studio Visualization  
> **Reference Document:** [`intel/mcap_architecture_and_feasibility_plan.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/mcap_architecture_and_feasibility_plan.md)  
> **Python Target:** Python 3.12 (`roadsense-mcap` Conda Environment)  
> **Author:** Antigravity AI Engineering Agent  
> **Date:** September 22, 2026  

---

## 1. Goal Description

Implement **Phase 1** of the RoadSense Foxglove MCAP migration:
Create an automated, end-to-end Python conversion pipeline that ingests heterogeneous RoadSense recording sessions (`radar_frames.bin`, `camera_video.mp4`, `camera_frames.csv`, `gnss_fixes.csv`, `session_timeline.csv`, `radar_camera_calib.json`, `can/*.MF4`) and compiles them into a single, self-contained, Zstandard-compressed, and indexed **`.mcap` container file**.

This enables engineers and researchers to instantly drag and drop any RoadSense drive recording into **Foxglove Studio** (desktop or web) to view:
* **3D Perspective View:** 3D radar point clouds with Doppler velocity color-coding, oriented 3D obstacle cuboids, velocity arrows, and vehicle coordinate frame (`base_link`).
* **Camera Viewfinder:** Synchronized H.264 camera video stream with 3D radar bounding boxes automatically projected into the camera plane using the 6-DOF calibration extrinsics.
* **GPS Map:** Real-world GPS trajectory tracked over satellite maps.
* **CAN & ADAS Telemetry:** Time-series plots for vehicle speed, motor torque, steering, road grade, and FCW/BSD/ACC alert stages.
* **Flight Recorder Logs:** Searchable, color-coded system and debug logs.

---

## 2. User Review Required

> [!IMPORTANT]
> **Conda Virtual Environment (`roadsense-mcap` - Python 3.12):**
> A dedicated Conda environment named **`roadsense-mcap`** using **Python 3.12** will be created to isolate all Foxglove MCAP, Protobuf, OpenCV, and compression libraries, aligning with your existing automotive environments (such as `carla312`):
> ```powershell
> conda create -y -n roadsense-mcap python=3.12
> conda activate roadsense-mcap
> pip install -r tools/requirements-mcap.txt
> ```

> [!NOTE]
> **Zero Risk to Target Hardware / Android App:**
> Phase 1 is executed strictly on the PC side. The Android app's high-speed UART ring buffer (3.125 Mbps), MediaCodec video recording, and binary logging remain 100% untouched and protected from any thermal or scheduling disruption.

---

## 3. Proposed Changes

```
RoadSense Project Root
 ├── tools/
 │    ├── requirements-mcap.txt                      [NEW] Python dependencies
 │    ├── convert_session_to_mcap.py                 [NEW] Core MCAP converter engine
 │    ├── foxglove_layouts/
 │    │    └── RoadSense_Cockpit_Layout.json         [NEW] Pre-configured Foxglove layout preset
 │    └── sync_and_process_sessions.py               [MODIFY] Add --mcap flag and automatic hook
 ├── sync_and_process_logs.bat                       [MODIFY] Add interactive menu option [6] & auto-conda activation
 ├── context/
 │    └── 06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md   [MODIFY] Document MCAP toolchain & schemas
 └── docs/
      └── MCAP_CONVERSION_AND_FOXGLOVE_GUIDE.md      [NEW] Comprehensive user operational guide
```

---

### Component 1: Conda Environment Setup & Python Dependencies (`tools/requirements-mcap.txt`)

To ensure clean isolation and rock-solid binary compatibility across Windows C++ extensions (Protobuf, Zstandard, OpenCV), we will establish a dedicated Conda virtual environment targeting Python 3.12:

#### 1. Conda Environment Creation & Activation
```powershell
# Create dedicated Conda environment with Python 3.12
conda create -y -n roadsense-mcap python=3.12

# Activate environment
conda activate roadsense-mcap
```

#### 2. Dependency Specification (`tools/requirements-mcap.txt`)
#### [NEW] `tools/requirements-mcap.txt`
```txt
mcap>=1.4.0
mcap-protobuf-support>=0.5.4
foxglove-schemas-protobuf>=0.4.0
protobuf>=4.25.0
zstandard>=0.22.0
```

#### 3. Installation Step
```powershell
# Within activated roadsense-mcap environment:
pip install -r tools/requirements-mcap.txt
```

---

### Component 2: Core MCAP Conversion Engine (`tools/convert_session_to_mcap.py`)

#### [NEW] `tools/convert_session_to_mcap.py`

This script provides both a standalone CLI and an importable module:
```bash
python tools/convert_session_to_mcap.py <session_dir> [--output <output.mcap>] [--compress zstd] [--no-video]
```

#### Key Architecture & Modules:
1. **Schema Initialization (`McapProtobufWriter`):**
   Registers official `foxglove.*` protobuf descriptors:
   - `foxglove.PointCloud` (Channel `/radar/points`, frame `radar_link`)
   - `foxglove.SceneUpdate` (Channel `/radar/tracks`, 3D Cubes + Velocity Arrows + Text Labels)
   - `foxglove.LocationFix` (Channel `/gnss/fix`)
   - `foxglove.CompressedVideo` (Channel `/camera/video`, H.264 bitstream chunks)
   - `foxglove.CameraCalibration` (Channel `/camera/calib`, $K$ matrix from Camera2)
   - `foxglove.FrameTransforms` (Channel `/tf`, 6-DOF rigid extrinsics $[\mathbf{R} \mid \mathbf{T}]$)
   - `foxglove.Log` (Channel `/diagnostics/logs`)
2. **Binary TLV Parsing Integration:**
   Reuses the proven, adaptive Multi-TLV v2.2 decoder from `tools/sync_and_process_sessions.py` (fixed in Bug #11) to unpack:
   - 12B/10B point clouds with Doppler velocities and SNR.
   - 28B/20B/14B tracks with orientation heading, TTI, risk, and bounding dimensions.
   - TLVs 4, 5, 6 for road boundaries, powertrain CAN signals, and ADAS warnings.
3. **Microsecond Monotonic Time Alignment:**
   Aligns every sensor packet using the host's monotonic nanosecond timestamp (`ROAD` header timestamp, `camera_frames.csv` shutter timestamp, `gnss_fixes.csv` realtime timestamp).
4. **H.264 Video Embedding:**
   Reads MP4 sample chunks directly from `camera_video.mp4` using pure Python / standard decapsulation and maps each sample to its corresponding shutter monotonic timestamp from `camera_frames.csv`.
5. **Metadata Attachments:**
   Embeds `session_metadata.json` and `radar_camera_calib.json` as native MCAP attachments for 100% self-contained archives.
6. **Chunk Compression & B-Tree Summary Indexing:**
   Emits Zstandard-compressed chunks (60%–75% compression ratio) and generates summary indexes (Message Index, Chunk Index, Channel Index) for sub-millisecond random-access seeking in Foxglove Studio.

---

### Component 3: Integration with Master Pipeline (`tools/sync_and_process_sessions.py`)

#### [MODIFY] `tools/sync_and_process_sessions.py`
* Add `--mcap` command-line argument:
  ```python
  parser.add_argument("--mcap", action="store_true", help="Automatically generate Foxglove .mcap file alongside visualizer JSON")
  ```
* In `process_session(session_dir, force=False)`:
  When `--mcap` is passed, automatically invoke `convert_session_to_mcap.process_session_to_mcap(...)`, emitting `session_YYYYMMDD_HHMMSS.mcap` directly in the session folder.

---

### Component 4: Master Batch Script Menu (`sync_and_process_logs.bat`)

#### [MODIFY] `sync_and_process_logs.bat`
* Auto-detect and activate `roadsense-mcap` Conda environment if available.
* Add menu option `[6] Export Session to Foxglove MCAP`:
  ```cmd
  echo   [6] Export to Foxglove MCAP: Convert sessions to .mcap container
  ```
* Prompts user for session ID or converts all sessions with 1 click.

---

### Component 5: Pre-Configured Foxglove Studio Layout Preset

#### [NEW] `tools/foxglove_layouts/RoadSense_Cockpit_Layout.json`
A packaged, importable JSON layout preset for Foxglove Studio:
1. **Top-Left (3D View):**
   - Topic `/radar/points`: Colored by `velocity` (Doppler colormap: Cyan to Red). Point size: 3px.
   - Topic `/radar/tracks`: Renders 3D colored bounding boxes, forward velocity vectors, and text labels (`ID #857 | -1.6 m/s`).
   - Coordinate frames: `base_link`, `radar_link`, `camera_link`.
   - Grid & Range Rings enabled (10m, 30m, 60m).
2. **Top-Right (Camera Overlay):**
   - Topic `/camera/video`: H.264 camera stream.
   - Calibration topic `/camera/calib`: Automatically projects 3D radar cuboids onto 2D video!
3. **Bottom-Left (Satellite Map):**
   - Topic `/gnss/fix`: Real-time vehicle marker on satellite map with breadcrumb trail.
4. **Bottom-Right (Telemetry Plots):**
   - Time-series curves: Vehicle speed [km/h], motor torque [Nm], ACC obstacle distance [m], FCW stage.
5. **Bottom Tray (Logs & State):**
   - Topic `/diagnostics/logs`: Color-coded log messages.

---

### Component 6: Documentation & Operational Guides

#### [NEW] `docs/MCAP_CONVERSION_AND_FOXGLOVE_GUIDE.md`
Step-by-step user guide detailing:
* How to activate the `roadsense-mcap` Conda environment (Python 3.12).
* How to convert a session with 1 command or batch script.
* How to open the resulting `.mcap` in Foxglove Studio (desktop app or web at `https://app.foxglove.dev/`).
* How to import the `RoadSense_Cockpit_Layout.json` preset.
* Complete channel dictionary and schema specifications.

#### [MODIFY] `context/06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md`
Update system context with MCAP toolchain and architecture diagrams.

---

## 4. Verification Plan

### Automated Verification:
```powershell
# 1. Activate conda environment and install dependencies
conda activate roadsense-mcap
pip install -r tools/requirements-mcap.txt

# 2. Run standalone converter on recent session (session_20260922_120145)
python tools/convert_session_to_mcap.py logs/session_20260922_120145

# 3. Verify MCAP structure, channels, and message counts using mcap reader
python -c "
from mcap.reader import make_reader
with open('logs/session_20260922_120145/session_20260922_120145.mcap', 'rb') as f:
    reader = make_reader(f)
    print('MCAP Profile:', reader.get_header().profile)
    summary = reader.get_summary()
    for ch_id, ch in summary.channels.items():
        print(f'Channel [{ch.topic}]: schema={ch.schema_id}, msgs={summary.statistics.channel_message_counts[ch_id]}')
"

# 4. Run standalone converter on older session (session_20260914_172150) to verify backward compatibility
python tools/convert_session_to_mcap.py logs/session_20260914_172150

# 5. Run JVM unit tests to ensure zero regressions in Kotlin codebase
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
```

### Manual Verification:
1. Open Foxglove Studio (Desktop or `https://app.foxglove.dev/`).
2. Drag and drop `session_20260922_120145.mcap`.
3. Import `tools/foxglove_layouts/RoadSense_Cockpit_Layout.json`.
4. Play back drive session:
   - Verify 3D radar point cloud renders with Doppler velocity colors.
   - Verify 3D bounding boxes track moving vehicles with velocity vectors.
   - Verify camera video plays synchronously with radar points.
   - Verify GPS map accurately displays vehicle driving along test route.
