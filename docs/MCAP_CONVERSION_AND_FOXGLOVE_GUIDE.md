# RoadSense MCAP Conversion & Foxglove Studio Operational Guide

> **Document Name:** `MCAP_CONVERSION_AND_FOXGLOVE_GUIDE.md`  
> **Status:** Active Reference & User Guide  
> **Target Audience:** ADAS Engineers, Data Scientists, Autonomous Vehicle Researchers  
> **Updated:** September 22, 2026  

---

## 1. Overview

RoadSense provides native, end-to-end support for compiling multimodal recording sessions into **Foxglove MCAP (`.mcap`)** container files. 

MCAP is the modern, high-performance container standard for robotics and automotive data logging. Instead of managing separate directories with binary UART streams, MP4 videos, CSV timetables, and CAN files, a single `.mcap` container bundles every sensor modality with nanosecond synchronization, Zstandard chunk compression, and random-access seeking.

```mermaid
flowchart LR
    A["RoadSense Session Folder<br/>(radar, camera, gnss, logs, calib)"] --> B["convert_session_to_mcap.py<br/>(roadsense-mcap Python 3.12)"]
    B --> C["session_YYYYMMDD_HHMMSS.mcap<br/>(Zstd Compressed + Indexed)"]
    C --> D["Foxglove Studio<br/>(Desktop or Web)"]
    D --> E1["3D Perspective View<br/>(Radar Points + 3D Cuboids)"]
    D --> E2["Camera Viewfinder<br/>(Synchronized H.264 Video)"]
    D --> E3["Satellite Map<br/>(GPS Trajectory)"]
    D --> E4["Telemetry Plots<br/>(Speed, Accel, Diagnostics)"]
```

---

## 2. Quick-Start: 1-Click Conversion

### Method A: Interactive Windows Batch Script (Recommended)
Double-click `sync_and_process_logs.bat` in the project root:
```text
===============================================================================
         RoadSense - Log Sync, Visualizer & Foxglove MCAP Pipeline
===============================================================================
Python Interpreter: C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe

Select an operation:

  [1] Full Pipeline: Sync from Device (ADB) and Process New Sessions
  [2] Sync Only: Pull sessions from Device without processing
  [3] Process Only: Process un-processed local sessions (skips up-to-date)
  [4] Force Re-process All: Re-generate all local session visualizer files
  [5] Process Specific Session: Choose a specific session to process
  [6] Export to Foxglove MCAP: Convert sessions into .mcap container
  [7] Exit
```
* Select **`[6]`** to export a specific session or press **ENTER** to batch-convert all sessions.
* The script automatically detects and activates the dedicated `roadsense-mcap` (Python 3.12) environment.

### Method B: Standalone Python CLI
Activate the conda environment and run the converter directly:
```powershell
conda activate roadsense-mcap

# Fast conversion with default layout rotation (1.9s, recommended)
python tools/convert_session_to_mcap.py logs/session_20260922_120145

# Physical 180° video flip (re-encodes video bitstream upright for external players)
python tools/convert_session_to_mcap.py logs/session_20260922_120145 --flip-video

# Ultra-compact conversion (radar, GPS, and telemetry only, excludes video)
python tools/convert_session_to_mcap.py logs/session_20260922_120145 --no-video
```

---

## 3. Opening and Visualizing in Foxglove Studio

### Step 1: Open Foxglove Studio
* **Desktop App:** Launch **Foxglove Studio** on Windows/Linux/macOS.
* **Web App:** Open [https://app.foxglove.dev/](https://app.foxglove.dev/) in Google Chrome or Microsoft Edge.

### Step 2: Load MCAP File
* Click **"Open local file"** or simply **drag and drop** the generated `session_YYYYMMDD_HHMMSS.mcap` directly into the window.

### Step 3: Import the Pre-Configured Cockpit Layout
RoadSense includes an optimized dashboard layout preset:
1. In Foxglove Studio, click the **Layout** menu (top menu bar) $\rightarrow$ **Import from file...**
2. Select:
   ```text
   tools/foxglove_layouts/RoadSense_Cockpit_Layout.json
   ```
3. Your screen will instantly configure into the **RoadSense Cockpit**:
   * **Top-Left (3D View):** 3D radar point clouds colored by Doppler velocity, 3D tracking cuboids with forward velocity vectors, and vehicle coordinate axes.
   * **Top-Right (Camera View):** Synchronized 720p HD video.
   * **Bottom-Left (Satellite Map):** Vehicle location tracked on real-world satellite imagery.
   * **Bottom-Right (Plots & Logs):** Vehicle speed, radar diagnostics, and flight recorder logs.

---

## 4. Channel Dictionary & Protobuf Schemas

| Topic | Schema Name | Encoding | Description |
|---|---|---|---|
| `/radar/points` | `foxglove.PointCloud` | `protobuf` | 3D point cloud (`x`, `y`, `z`, `velocity`, `snr`) in `radar_link`. |
| `/radar/tracks` | `foxglove.SceneUpdate` | `protobuf` | 3D oriented obstacle bounding boxes, velocity arrows, and labels. |
| `/camera/video` | `foxglove.CompressedVideo` | `protobuf` | H.264 Annex B bitstream packets synchronized to shutter nanoseconds. |
| `/camera/calib` | `foxglove.CameraCalibration` | `protobuf` | 720p pinhole intrinsics ($K$), distortion ($D$), and projection ($P$). |
| `/gnss/fix` | `foxglove.LocationFix` | `protobuf` | Latitude, longitude, altitude, GPS speed, and heading. |
| `/imu/data` | `sensor_msgs.Imu` | `protobuf` | 100 Hz triaxial acceleration ($a_x, a_y, a_z$), angular velocity ($\omega_x, \omega_y, \omega_z$), and 6-DOF quaternion ($q_x, q_y, q_z, q_w$). |
| `/tf` | `foxglove.FrameTransforms` | `protobuf` | 6-DOF extrinsics (`base_link` $\rightarrow$ `radar_link`, `camera_optical`, and `imu_link`). |
| `/diagnostics/logs` | `foxglove.Log` | `protobuf` | Color-coded session logs from `session_debug.log`. |
| `/vehicle/telemetry` | `roadsense.VehicleTelemetry` | `jsonschema` | Speed, yaw rate, pitch rate, and longitudinal/lateral accelerations. |
| `/radar/diagnostics` | `roadsense.RadarDiagnostics` | `jsonschema` | Inliers count, RANSAC status, ego velocity, and road boundaries. |

---

## 5. Embedded Attachments
The MCAP file also embeds critical configuration and metadata files as native attachments:
* **`session_metadata.json`**: Device hardware info, sensor baud rates, session duration, and frame counts.
* **`radar_camera_calib.json`**: Physical vehicle mounting parameters (pitch tilt, yaw heading, radar height, setback).

---

## 6. Physical Phone Mount & Coordinate Frame Alignment

### 6.1 Physical Vehicle Mounting Geometry
The smartphone is mounted to the vehicle windshield in landscape orientation with the phone body axes defined as:
* **$+Y_{\text{phone}}$**: **Lateral Right** (pointing toward the passenger side).
* **$+X_{\text{phone}}$**: **Vertical Down** (pointing downward toward the road/chassis).
* **$+Z_{\text{phone}}$**: **Longitudinal Backward** (normal to the screen, facing into the cabin/driver).
* **$-Z_{\text{phone}}$**: **Longitudinal Forward** (out the back of the phone, camera lens facing the road).

```text
               Vehicle Forward (Road Ahead)
                         ▲
                         │  -Z (Rear Camera points here)
                         │
                 ┌───────────────┐  ▲
                 │               │  │ -X (Left edge of phone = UP / Sky)
    Vehicle Left │   [ SCREEN ]  │  │
    (-Y phone    │               │  ▼
     = Bottom)   │               │  ▲
                 └───────────────┘  │ +X (Right edge of phone = DOWN / Ground)
                         │          ▼
                         │  +Z (Screen faces cabin / driver)
                         ▼
               Vehicle Rear (Cabin)
                         
             ◄───────────────────────►
      -Y (Bottom / USB)     +Y (Top of Phone = Lateral Right / Passenger)
```

### 6.2 Axis Mapping Matrix (Vehicle base_link <-> Phone Body)
Following the right-hand rule ($\mathbf{X} \times \mathbf{Y} = \mathbf{Z}$), the mapping between the standard ISO vehicle frame (`base_link`: $X$=Forward, $Y$=Left, $Z$=Up) and the physical phone body frame is:

$$\begin{pmatrix} X_{\text{phone}} \\ Y_{\text{phone}} \\ Z_{\text{phone}} \end{pmatrix} = \begin{bmatrix} 0 & 0 & -1 \\ 0 & -1 & 0 \\ -1 & 0 & 0 \end{bmatrix} \begin{pmatrix} X_{\text{vehicle}} \\ Y_{\text{vehicle}} \\ Z_{\text{vehicle}} \end{pmatrix}$$

$$\begin{pmatrix} X_{\text{vehicle}} \\ Y_{\text{vehicle}} \\ Z_{\text{vehicle}} \end{pmatrix} = \begin{bmatrix} 0 & 0 & -1 \\ 0 & -1 & 0 \\ -1 & 0 & 0 \end{bmatrix} \begin{pmatrix} X_{\text{phone}} \\ Y_{\text{phone}} \\ Z_{\text{phone}} \end{pmatrix}$$

### 6.3 Implications for Perception & Tooling
1. **Camera Video 180° Inversion:**  
   Because the phone is mounted with the top of the device ($+Y$) pointing to the right (`ROTATION_270`), Android's Camera2 raw sensor scanlines are inverted 180° relative to standard horizon display. This is normalized in post-processing using `--flip-video` or via Foxglove layout rotation.
2. **IMU Accelerometer Readings:**  
   Earth's gravity vector points downward, aligning with $+X_{\text{phone}}$ ($a_x \approx +9.81\,\text{m/s}^2$). Forward vehicle acceleration produces an inertial reaction along $+Z_{\text{phone}}$ ($+a_z$).
3. **Camera Optical Frame (`camera_optical`):**  
   Standard ROS optical frame ($X$=Right, $Y$=Down, $Z$=Forward) translates directly to:
   * $X_{\text{optical}} = +Y_{\text{phone}} = -Y_{\text{vehicle}}$
   * $Y_{\text{optical}} = +X_{\text{phone}} = -Z_{\text{vehicle}}$
   * $Z_{\text{optical}} = -Z_{\text{phone}} = +X_{\text{vehicle}}$

