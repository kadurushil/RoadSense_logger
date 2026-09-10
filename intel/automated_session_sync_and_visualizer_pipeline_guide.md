# RoadSense Automated Session Synchronization & Visualizer Conversion Pipeline

> **Target Audience:** Test Engineers, Autonomous Vehicle Researchers, and AI Agents processing multi-modal vehicle road-test logs captured by the RoadSense Android Application.
> **Related Specifications:** [`VISUALIZER_JSON_FORMAT_AND_CONVERSION_GUIDE.md`](file:///D:/Work/Repo/CANenbl_unifiedRadarTracker/TLV_info/VISUALIZER_JSON_FORMAT_AND_CONVERSION_GUIDE.md), [`intel/radar_tlv_structure_and_decoding_guide.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/radar_tlv_structure_and_decoding_guide.md).

---

## 1. Executive Overview

The RoadSense mobile application captures synchronized, high-bandwidth automotive sensor streams directly onto Android flash storage:
1. **Radar Sensor:** 3.125 Mbaud binary framed UART packets (`radar_frames.bin` with 24-byte `ROAD` headers).
2. **Camera Sensor:** H.264 video (`camera_video.mp4`) accompanied by shutter timestamps (`camera_frames.csv`).
3. **GNSS / Vehicle Telemetry:** High-rate position, ground speed, and bearing fixes (`gnss_fixes.csv`).
4. **Master Timeline:** Nanosecond cross-sensor monotonic event timeline (`session_timeline.csv`).

To consume these logs in downstream desktop visualizers (e.g., Python PyQtGraph/OpenGL visualizers, MATLAB reconstruction toolchains, and interactive track inspectors), the raw binary data must be pulled from the phone, time-aligned, and converted into standardized JSON structures:
- **`track_history.json`**: Top-level visualizer dataset containing the `radarFrames` array and accumulated `tracks` trajectory histories.
- **`frame_mapping.json`**: Temporal lookup table mapping hardware radar chirps to corresponding camera video frame indices.
- **`camera_video.mp4`**: H.264 camera stream for side-by-side visual verification.

The automated pipeline implemented in [`tools/sync_and_process_sessions.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/sync_and_process_sessions.py) and launched via [`sync_and_process_logs.bat`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/sync_and_process_logs.bat) provides a 1-click, end-to-end bridge between test-vehicle phones and host analysis PCs.

---

## 2. Architecture & Data Flow

```
[ Android Device (SM-M305F) ]
  /sdcard/Android/data/com.bajajauto.roadsense/files/sessions/session_YYYYMMDD_HHMMSS/
  ├── radar/radar_frames.bin (24B ROAD header + TI TLVs)
  ├── camera/camera_video.mp4 & camera_frames.csv
  ├── gnss/gnss_fixes.csv
  └── session_metadata.json
              │
              │  [1] ADB Pull (Smart Differential Skip)
              ▼
[ Local Host: logs/session_YYYYMMDD_HHMMSS/ ]
              │
              │  [2] Binary TLV Parser (Adaptive 20B/14B/12B Stride)
              │  [3] Nanosecond Cross-Sensor Monotonic Time-Alignment
              │  [4] Hardware EKF Track Trajectory Accumulation
              ▼
[ Visualizer Output Artifacts ]
  ├── track_history.json   <-- Strict visualizer schema (Point Cloud, Clusters, Tracks)
  ├── frame_mapping.json   <-- Frame-accurate radar-to-camera sync index
  └── camera_video.mp4     <-- Direct video asset
```

---

## 3. Toolchain Files & Quick Launch

### 3.1 The 1-Click Batch Launcher: `sync_and_process_logs.bat`
Located in the project root: [`sync_and_process_logs.bat`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/sync_and_process_logs.bat).

Run by double-clicking or from the terminal:
```cmd
.\sync_and_process_logs.bat
```

#### Interactive Menu:
```text
===============================================================================
               RoadSense - Log Sync and Visualizer Pipeline
===============================================================================

Select an operation:

  [1] Full Pipeline: Sync from Device (ADB) and Process New Sessions
  [2] Sync Only: Pull sessions from Device without processing
  [3] Process Only: Process un-processed local sessions (skips up-to-date)
  [4] Force Re-process All: Re-generate all local session visualizer files
  [5] Process Specific Session: Choose a specific session to process
  [6] Exit

Enter your choice [1-6]:
```

### 3.2 The Core Python Engine: `tools/sync_and_process_sessions.py`
Located at: [`tools/sync_and_process_sessions.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/sync_and_process_sessions.py).

#### Command-Line Arguments:
```bash
python tools/sync_and_process_sessions.py [OPTIONS]
```
| Argument | Description |
| :--- | :--- |
| *(None)* | Default: Auto-syncs via ADB and processes all new/modified sessions. |
| `--sync-only` | Only pulls sessions from the device; skips visualizer JSON generation. |
| `--process-only` | Skips ADB detection entirely; operates only on existing local folders in `logs/`. |
| `--force` | Disables smart cache and re-generates JSON files for up-to-date sessions. |
| `--force-all` | Forces re-processing and re-generating visualizer files across all sessions. |
| `--session <id>` | Targets a single specific session folder (e.g. `--session session_20260910_093816`). |
| `--logs-dir <path>` | Custom local directory path where sessions are located (defaults to project `logs/`). |

---

## 4. Smart Caching & Avoidance of Redundant Work

To handle large automotive datasets (where video recordings are several gigabytes), the pipeline implements two layers of caching:

### Layer 1: ADB Pull Avoidance (Device $\rightarrow$ PC)
Before issuing any ADB pull command, `sync_sessions_from_device()` inspects the local directory:
```python
if os.path.isdir(local_path):
    meta_file = os.path.join(local_path, "session_metadata.json")
    if os.path.isfile(meta_file):
        print(f"    - [{s_name}] Already synced.")
        continue
```
* **Benefit:** Completed sessions are never pulled twice across USB. The sync step completes in $< 1\text{ second}$ if no new recordings exist on the phone.

### Layer 2: Visualizer Processing Skip (Raw $\rightarrow$ JSON)
Before parsing binary radar packets, `process_session()` checks file modification timestamps (`mtime`):
$$\text{Skip Processing} \iff (\text{mtime}(\text{track\_history.json}) \ge \text{mtime}(\text{radar\_frames.bin})) \land (\text{mtime}(\text{frame\_mapping.json}) \ge \text{mtime}(\text{radar\_frames.bin}))$$

```python
if not force and os.path.isfile(track_history_path) and os.path.isfile(mapping_path):
    radar_mtime = os.path.getmtime(radar_bin)
    if os.path.getmtime(track_history_path) >= radar_mtime and os.path.getmtime(mapping_path) >= radar_mtime:
        print(f"[=] Skipping {session_name}: Already processed and up-to-date.")
        return True
```
* **Benefit:** Parsing thousands of frames and serializing 200MB JSON trees is executed **only once**. Subsequent runs skip up-to-date sessions instantly ($< 0.01\text{ ms}$).
* **Override:** To re-run after altering parsing or tracking algorithms, use Option `[4]` or supply the `--force` flag.

---

## 5. Output Data Schemas & Downstream Visualizer Compatibility

The generated files adhere strictly to the format documented in `VISUALIZER_JSON_FORMAT_AND_CONVERSION_GUIDE.md`:

### 5.1 `track_history.json`
Top-level object with two root arrays:
```json
{
  "radarFrames": [ /* Array of Frame Objects */ ],
  "tracks":      [ /* Array of Track Objects */ ]
}
```

#### A. `radarFrames` Array
Every frame corresponds to a physical radar chirp epoch:
```json
{
  "frameIdx": 105,
  "timestamp": 5250.0,
  "numPoints": 17,
  "motionState": 0,
  "egoVelocity": [0.0, 11.25],
  "canVehSpeed_kmph": 40.5,
  "AccelPedal_Act_perc": 0.0,
  "shaftTorque_Nm": 0.0,
  "engagedGear": 0,
  "roadGrade_Deg": 0.0,
  "acceleration_avg": 0.0,
  "sensorStats": [
    { "frame_number": 6126, "cpu_cycles": 3435738662, "subframe": 0 }
  ],
  "video_frame_index": 158,
  "video_time_delta": -0.014,
  "filtered_barrier_x": [-10.0, 10.0],
  "adas": [
    {
      "poi_id": 0, "acc_dist": 0.0, "acc_rel_v": 0.0, "acc_rel_a": 0.0,
      "acc_status": 0, "tti": 10.23, "aeb_risk": 0, "fcw_stage": 0,
      "brake_prefill_req": false, "target_confidence": 0.0,
      "bsd_left_active": false, "bsd_right_active": false,
      "lca_warning_level": 0, "approach_ttc": 10.23,
      "corridor_width": 3.75, "sensor_blindness": 0
    }
  ],
  "performance_stats": [
    { "t_pre_ms": 0.0, "t_track_ms": 0.0, "t_total_ms": 0.0 }
  ],
  "pointCloud": [
    {
      "x": -3.55, "y": 23.51, "z": 0.0,
      "velocity": 0.0, "snr": 65.2, "noise": 0.0,
      "pointId": 0, "clusterNumber": 0, "isOutlier": false
    }
  ],
  "clusters": [
    {
      "id": 1, "x": -3.55, "y": 23.51,
      "vx": 0.0, "vy": 0.0, "radialSpeed": 0.0,
      "azimuth": -8.58, "isOutlier": false, "isStationaryInBox": false
    }
  ]
}
```

> [!IMPORTANT]
> **Single-Element Array Rule:**
> In accordance with MATLAB and visualizer deserialization constraints, `sensorStats`, `adas`, and `performance_stats` MUST be wrapped as single-element lists: `[{ ... }]`.

#### B. `tracks` Array (Hardware EKF Kalman Trajectories)
Hardware targets parsed from TLV Type 3 are accumulated into longitudinal track trajectories indexed by their native hardware `tid`:
```json
{
  "id": 3166,
  "isConfirmed": true,
  "historyLog": [
    {
      "frameIdx": 51,
      "state": 3,
      "correctedPosition": [-3.9062, 43.3516],
      "predictedPosition": [-3.9062, 43.3516],
      "correctedVelocity": [0.0, -1.1719],
      "predictedVelocity": [0.0, -1.1719],
      "accel": [0.0, -0.3984],
      "omega": 0.0,
      "modelProbabilities": [1.0, 0.0, 0.0],
      "ttc": 100.0,
      "risk": 0,
      "tti": 100.0,
      "isStationary": false,
      "covarianceP": [
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
      ],
      "ellipseRadii": [0.0, 0.0],
      "ellipseAngle": 0.0,
      "objectExtentRadii": [0.8398, 0.2226],
      "objectExtentAngle": 0.0
    }
  ]
}
```

* **Extent Radii Ordering:** Formatted as `[radial, cross]` = `[length/2, width/2]` (derived from `ySize / 2.0` and `xSize / 2.0`).
* **Sanitization:** All coordinates and floating-point computations are validated via `safe_float()` ensuring no `NaN` or `Infinity` tokens exist.

---

### 5.2 `frame_mapping.json`
Saved as a standard JSON Array `[ ... ]` ending with a session summary object:
```json
[
  {
    "radar_frame_id_rel": 1,
    "radar_frame_id_abs": 6126,
    "radar_timestamp": 167115.7471,
    "video_frame_index": 0,
    "video_frame_ts": 167116.2025,
    "video_time_delta": 0.4554,
    "camera_frame_hw": 2244
  },
  {
    "radar_frame_id_rel": 2,
    "radar_frame_id_abs": 6127,
    "radar_timestamp": 167115.8064,
    "video_frame_index": 0,
    "video_frame_ts": 167116.2025,
    "video_time_delta": 0.3961,
    "camera_frame_hw": 2244
  },
  ...
  {
    "metadata": {
      "average_fps": 30.012,
      "duration_sec": 111.05,
      "total_frames": 3333,
      "mp4_frames": 3331
    }
  }
]
```

#### Fields:
* `radar_frame_id_rel`: 1-based relative frame counter.
* `radar_frame_id_abs`: Hardware 32-bit frame counter from TI packet header.
* `radar_timestamp`: Host monotonic timestamp in seconds (`mono_ns / 1e9`).
* `video_frame_index`: **Strictly 0-based index** to seek within `camera_video.mp4` ($0 \dots N-1$).
* `video_frame_ts`: Monotonic camera frame shutter timestamp in seconds.
* `video_time_delta`: Temporal error $\Delta t = \text{video\_ts} - \text{radar\_ts}$ in seconds.
* `camera_frame_hw`: Raw Android Camera2 hardware capture sequence counter (for hardware diagnosis).

---

## 6. Verification and Test Results

The pipeline was executed against all test sessions recorded on the Samsung SM-M305F test vehicle:

| Session | Radar Frames | Video Frames | GNSS Fixes | Result |
| :--- | :--- | :--- | :--- | :--- |
| `session_20260909_111841` | 925 | 0 (Radar only) | 0 | **Processed** (`track_history.json`, `frame_mapping.json`) |
| `session_20260909_141237` | 212 | 0 (Radar only) | 0 | **Processed** (`track_history.json`, `frame_mapping.json`) |
| `session_20260909_163202` | 3,340 | 4,834 | 175 | **Processed** (`track_history.json`, `frame_mapping.json`, `camera_video.mp4`) |
| `session_20260909_163627` | 2,927 | 4,323 | 153 | **Processed** (`track_history.json`, `frame_mapping.json`, `camera_video.mp4`) |
| `session_20260910_093125` | 7,529 | 11,844 | 321 | **Processed** (`track_history.json`, `frame_mapping.json`, `camera_video.mp4`) |
| `session_20260910_093816` | 2,273 | 3,333 | 118 | **Processed** (`track_history.json`, `frame_mapping.json`, `camera_video.mp4`) |

When running the pipeline a second time:
```text
[=] Skipping session_20260909_111841: Already processed and up-to-date.
[=] Skipping session_20260909_141237: Already processed and up-to-date.
[=] Skipping session_20260909_163202: Already processed and up-to-date.
[=] Skipping session_20260909_163627: Already processed and up-to-date.
[=] Skipping session_20260910_093125: Already processed and up-to-date.
[=] Skipping session_20260910_093816: Already processed and up-to-date.
Pipeline Finished: Successfully processed 6/10 sessions in 0.08 seconds.
```

---

## 7. Troubleshooting & Maintenance Guide

| Issue / Symptom | Possible Cause | Solution |
| :--- | :--- | :--- |
| `No authorized Android device detected` | USB debugging disabled or unauthorized RSA key. | Enable USB Debugging in Developer Options; accept the RSA authorization prompt on the phone screen. |
| `adb is not recognized` | ADB is not in system `PATH`. | The script automatically scans `~\AppData\Local\Android\Sdk\platform-tools\adb.exe`. If Android Studio is installed in a non-standard location, add `platform-tools` to system `PATH`. |
| Session is skipped but files need re-generating | Visualizer format updated or bug fixed. | Run with `--force` flag or select **Option `[4]`** in `sync_and_process_logs.bat`. |
| Missing `camera_video.mp4` | Recording was radar-only or video encoding crashed before session stop. | `track_history.json` and `frame_mapping.json` are still generated with `video_frame_index: null`. |
| JSON validation error in visualizer | Bare `NaN` or `Inf` values emitted. | Ensure all numeric conversions use `safe_float(val)`. |
