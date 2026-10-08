# RoadSense × CV 3D Perception & Ground-Truth Fusion Architecture Guide

> **Document Name:** `RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md`  
> **Location:** `intel/Vision/RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md`  
> **Status:** Architectural Foundation & Phased Implementation Specification  
> **Target Audience:** ADAS Engineers, Computer Vision Developers, Sensor Fusion Architects  
> **Date:** October 2026  

---

## Table of Contents
1. [Executive Summary & The "Eureka" Breakthrough](#1-executive-summary--the-eureka-breakthrough)
2. [Step-by-Step Learning & Implementation Roadmap](#2-step-by-step-learning--implementation-roadmap)
3. [Deep Comparison: RoadSense vs. D:\Work\CV](#3-deep-comparison-roadsense-vs-dworkcv)
4. [Coordinate Frame Harmonization & Mathematical Mechanics](#4-coordinate-frame-harmonization--mathematical-mechanics)
5. [The Baseline: What the Raw CV Pipeline Does (And Where It Breaks)](#5-the-baseline-what-the-raw-cv-pipeline-does-and-where-it-breaks)
6. [Deep Dive: Model Mechanics, GPU Acceleration & Temporal Streaming](#6-deep-dive-model-mechanics-gpu-acceleration--temporal-streaming)
7. [The Fused Pipeline Architecture (Vision + Radar + Calibration)](#7-the-fused-pipeline-architecture-vision--radar--calibration)
8. [Ground Truth Dataset Generation Specification](#8-ground-truth-dataset-generation-specification)
9. [Reference Implementation Blueprint](#9-reference-implementation-blueprint)
10. [Verification & Evaluation Strategy](#10-verification--evaluation-strategy)

---

## 1. Executive Summary & The "Eureka" Breakthrough

Perception in autonomous driving and Advanced Rider Assistance Systems (ARAS / ADAS) relies heavily on two parallel technologies:
1. **Geometric Physical Sensors (mmWave Radar / LiDAR / CAN):** Directly measure physical metric distance ($Y$), radial Doppler velocity ($V_{\text{doppler}}$), and vehicle dynamics with microsecond precision, invariant to ambient lighting, weather, or optical glare. However, they lack semantic classification (cannot distinguish a cardboard box from a child) and have coarse lateral/angular resolution.
2. **Dense Monocular Computer Vision (YOLO + Deep Depth Networks):** Provides rich semantic classification (pedestrians, cars, two-wheelers, road signs) and dense bounding boxes. However, monocular depth models (such as ByteDance's Video Depth Anything) suffer from scale ambiguity, metric compression at long range, and lack real-world spatial anchoring. Furthermore, standard CV pipelines compute 3D positions strictly relative to an uncalibrated camera lens axis, ignoring vehicle roll, pitch tilt, and height above the asphalt.

### The Breakthrough Synergy

```
┌───────────────────────────────────────────────┐     ┌───────────────────────────────────────────────┐
│              D:\Work\CV PIPELINE              │     │             ROADSENSE PLATFORM                │
│                                               │     │                                               │
│ • Ultralytics YOLO26 + ByteTrack (2D semantics)│     │ • Android Camera2 Hardware Intrinsics (K)    │
│ • ByteDance Video Depth Anything (dense depth)│  +  │ • 6-DOF Keystone Extrinsic Calibration [R|T]  │
│                                               │     │ • TI AWR1843 mmWave Radar Ground Truth (Y, V) │
│ ⚠️ Limitation: Uncalibrated focal length,     │     │ • Microsecond Shutter Sync & MCAP Exporter    │
│    Zero extrinsics, No metric ground anchor   │     │                                               │
└───────────────────────┬───────────────────────┘     └───────────────────────┬───────────────────────┘
                        │                                                     │
                        └──────────────────────────┬──────────────────────────┘
                                                   │
                                                   ▼
                        ┌─────────────────────────────────────────────────────┐
                        │      UNIFIED 3D PERCEPTION & GROUND TRUTH ENGINE    │
                        │                                                     │
                        │ 1. Metric Scale Calibration: Z_radar / Z_model      │
                        │ 2. World-to-Vehicle Projection: ISO Ground Plane    │
                        │ 3. Automated 3D Bounding Box Dataset Generation     │
                        │ 4. Single-Pane Foxglove MCAP Telemetry Export       │
                        └─────────────────────────────────────────────────────┘
```

By fusing the two codebases:
* **RoadSense's Hardware Intrinsics ($K$)** replace guessed or arbitrary focal lengths (`--focal_length_x 500.0`).
* **RoadSense's 6-DOF Extrinsics ($[R|T]$)** rotate uncalibrated camera optical rays into true ISO vehicle coordinates ($+X$ right, $+Y$ forward along heading, $+Z$ up from asphalt), instantly eliminating downward pitch skew.
* **RoadSense's TI mmWave Radar Tracks** serve as the **physical ground truth anchor** to calculate the real-time scale factor $\alpha = Z_{\text{radar}} / Z_{\text{VDA}}$, turning noisy monocular depth maps into precision metric measurements.
* The system simultaneously logs driving sessions and auto-generates annotated 3D ground-truth datasets for training and benchmarking ADAS vision models.

---

## 2. Step-by-Step Learning & Implementation Roadmap

To ensure a deep conceptual understanding of the models, data structures, and math, we follow a strict four-step pedagogical workflow:

```mermaid
journey
    title Four-Step Perception Fusion Journey
    section Step 1: Planning
      Architectural Guide & Mathematical Blueprint: 5: Antigravity, User
      Establish intel/Vision/ Knowledge Base: 5: Antigravity, User
    section Step 2: Baseline
      Run Raw CV Pipeline on WIN_20250902 Video: 4: Antigravity, User
      Audit try1_with_depth.json Pitch & Scale Errors: 4: Antigravity, User
    section Step 3: Mechanics
      Deep Dive into YOLO26 + ByteTrack Tracking: 5: Antigravity, User
      VDA Streaming Architecture & GPU Latency Audit: 5: Antigravity, User
    section Step 4: Fusion
      Inject RoadSense [R|T] and K Matrix: 5: Antigravity, User
      Hungarian 2D-3D Radar-Vision Matching Engine: 5: Antigravity, User
      Automated Ground Truth Dataset & MCAP Export: 5: Antigravity, User
```

1. **Step 1: Strategic Planning & Architecture (Current Step):**  
   Document the end-to-end mathematical models, coordinate conversions, and pipeline flow in this document to establish our technical baseline.
2. **Step 2: Baseline Generation with Raw CV (Zero Alignment):**  
   Execute the CV scripts as-is on real test video footage. Inspect the unaligned output (`try1_with_depth.json`). Concretely document why a pedestrian 68m away produces a vertical $Y$ position of $-5.8\text{m}$ (floating in the sky) due to uncalibrated camera pitch.
3. **Step 3: Model Mechanics & GPU Acceleration Deep-Dive:**  
   Examine how the code loads neural weights, allocates GPU memory, executes ByteTrack Kalman filters, and processes streaming video depth with hidden temporal states.
4. **Step 4: Full Multi-Modal Fusion Engine:**  
   Implement the bridge script that combines RoadSense calibration files (`radar_camera_calib.json`), radar binary frames (`radar_frames.bin`), and video into a unified 3D ground-truth perception pipeline.

---

## 3. Deep Comparison: RoadSense vs. D:\Work\CV

| Feature / Dimension | Current `D:\Work\CV` Pipeline | RoadSense Platform | Fused Perception Pipeline |
|---|---|---|---|
| **Primary Domain** | Offline 3D Vision (Windows CUDA) | Edge Multi-Modal Logger (Android / RPi) | Edge Logging + Workstation 3D Reconstruction |
| **Object Semantics** | YOLO26n + ByteTrack (2D Bounding Boxes) | None (Radar clustering / tracks only) | YOLO26 Semantics + Radar Kinematics |
| **Depth Estimation** | Video Depth Anything (vitl, VKITTI) | Physical FMCW mmWave Radar (77 GHz) | Dual: Dense Neural Depth + Sparse Radar Ground Truth |
| **Camera Intrinsics ($K$)** | Hardcoded/Guessed (`fx=500.0`, `cx=W/2`) | Android Camera2 `LENS_INTRINSIC_CALIBRATION` | True Hardware Intrinsics calibrated to native sensor |
| **Camera Extrinsics ($[R|T]$)** | **None** (assumes camera = world) | 6-DOF Keystone HUD (`pitchDeg`, `yawDeg`, `heightM`) | Full Ground-Plane $[R\|T]$ (ISO 8855 vehicle frame) |
| **Time Synchronization** | Monotonic epoch via FFmpeg (`mono2abs`) | Nanosecond hardware shutter timestamps | Sub-millisecond shutter-to-radar sync |
| **Ground Contact Modeling** | Midpoint $\frac{y1+y2}{2}$ (hits windshield/cabin) | Road Plane Geometry ($Z_{\text{road}} = -H_{\text{radar}}$) | Bounding box bottom-center $(cx, y2)$ ray intersection |
| **Output Data Format** | `tracking_data_with_depth.json` | Binary `.bin`, SQLite, Foxglove `.mcap` | MCAP + 3D Ground Truth Annotations (nuScenes/KITTI) |

---

## 4. Coordinate Frame Harmonization & Mathematical Mechanics

A fundamental challenge in multi-modal fusion is that computer vision, robotics (ROS), and automotive vehicle dynamics each use different coordinate conventions:

```
    Camera Optical (RDF)               Vehicle Body / Radar (ISO)              ROS FLU (base_link)
       +Z_c (Forward)                        +Y_v (Forward)                       +X_b (Forward)
             ▲                                     ▲                                    ▲
            /                                      │                                    │
           /                                       │                                    │
          ┌───────► +X_c (Right)         -X_v ◄────┼────► +X_v (Right)        +Y_b ◄────┼────► -Y_b (Right)
          │                                        │                                    │
          │                                        │                                    │
          ▼ +Y_c (Down)                            ▼ -Y_v                               ▼ -X_b
      (+Z_c = Optical Depth)                   (+Z_v = Up)                          (+Z_b = Up)
```

### 4.1 Frame Definitions
1. **Camera Optical Frame ($\mathcal{F}_c$ - RDF: Right-Down-Forward):**
   * $+X_c$: Points rightward across the image sensor.
   * $+Y_c$: Points downward toward the bottom of the screen.
   * $+Z_c$: Optical axis pointing forward into the scene (depth $Z$).
2. **Vehicle Ground Frame ($\mathcal{F}_v$ - ISO 8855):**
   * $+X_v$: Lateral axis pointing toward vehicle passenger side (right).
   * $+Y_v$: Longitudinal axis pointing forward along vehicle heading.
   * $+Z_v$: Vertical elevation pointing upward perpendicular to road asphalt ($Z_v = 0$ at road contact).
3. **Pinhole Camera Intrinsics ($K$):**
   $$K = \begin{bmatrix} f_x & 0 & c_x \\ 0 & f_y & c_y \\ 0 & 0 & 1 \end{bmatrix}$$
   Where:
   * $f_x, f_y$ are focal lengths in pixel units: $f_x = \frac{W}{2 \tan(\text{HFOV}/2)}$.
   * $c_x, c_y$ are the optical center coordinates, extracted directly from [`CameraIntrinsicsProvider.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/CameraIntrinsicsProvider.kt).

### 4.2 Extrinsic Rigid Transformation Matrix ($[R|T]$)

To transform any 3D point $\mathbf{P}_v = [X_v, Y_v, Z_v]^T$ in vehicle space into camera coordinates $\mathbf{P}_c = [X_c, Y_c, Z_c]^T$:
$$\mathbf{P}_c = \mathbf{R} \cdot (\mathbf{P}_v - \mathbf{T})$$

Where:
* **Translation Vector $\mathbf{T}$:**
  $$\mathbf{T} = \begin{bmatrix} \Delta X_{\text{lat}} \\ \Delta Y_{\text{setback}} \\ H_{\text{cam}} \end{bmatrix} = \begin{bmatrix} \text{lateralOffsetM} \\ \text{setbackM} \\ \text{radarHeightM} + \text{heightOffsetM} \end{bmatrix}$$
  * Typical windshield mount values: $\Delta X = 0.0\text{ m}$, $\Delta Y = 0.30\text{ m}$, $H_{\text{cam}} = 0.95 + 0.50 = 1.45\text{ m}$.
* **Rotation Matrix $\mathbf{R}$ (Euler Sequence):**
  $$\mathbf{R} = \mathbf{R}_z(\phi) \cdot \mathbf{R}_x(\theta) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{R}_0$$
  * $\mathbf{R}_0$: Permutation matrix aligning ISO axes $(X_v, Y_v, Z_v)$ to base optical axes $(X_c, Y_c, Z_c)$:
    $$\mathbf{R}_0 = \begin{bmatrix} 1 & 0 & 0 \\ 0 & 0 & -1 \\ 0 & 1 & 0 \end{bmatrix}$$
  * $\theta$ (Pitch): Tilt downward toward the asphalt (typically $+5^\circ$ to $+10^\circ$).
  * $\psi$ (Yaw): Left/right pan relative to vehicle centerline.
  * $\phi$ (Roll): Leveling clamp slant.

### 4.3 Inverse Back-Projection: From Optical Pixels to True Vehicle 3D Coordinates

When the CV model detects an object at pixel centroid $(u, v)$ with measured depth $Z_c$, the raw camera-frame coordinates are:
$$X_c = \frac{u - c_x}{f_x} \cdot Z_c, \quad Y_c = \frac{v - c_y}{f_y} \cdot Z_c, \quad Z_c = Z_c$$

Applying RoadSense's calibrated inverse rotation $\mathbf{R}^T$ and translation $\mathbf{T}$ transforms this point directly into real-world vehicle space:
$$\mathbf{P}_v = \mathbf{R}^T \cdot \mathbf{P}_c + \mathbf{T}$$

$$\begin{bmatrix} X_v \\ Y_v \\ Z_v \end{bmatrix} = \mathbf{R}^T \begin{bmatrix} \frac{u - c_x}{f_x} Z_c \\ \frac{v - c_y}{f_y} Z_c \\ Z_c \end{bmatrix} + \begin{bmatrix} \Delta X \\ \Delta Y \\ H_{\text{cam}} \end{bmatrix}$$

This single transformation **solves the pitch tilt problem**: an object 30m away no longer has an erroneous vertical drop, because $\mathbf{R}^T$ rotates the tilted viewing ray back into the horizontal vehicle reference plane!

---

## 5. The Baseline: What the Raw CV Pipeline Does (And Where It Breaks)

In `D:\Work\CV\Logs\try1_with_depth.json`, inspection of real output reveals:

```json
{
  "frame_number": 1,
  "detections": [
    {
      "track_id": 1,
      "class_name": "person",
      "bbox_pixels": { "x1": 1176, "y1": 459, "x2": 1202, "y2": 497 },
      "center_pixel": { "x": 1189.0, "y": 478.0 },
      "position_m": {
        "x": 21.558,
        "y": -5.837,
        "z": 67.934
      }
    }
  ]
}
```

### The Three Structural Flaws of the Unaligned Output:

1. **The Negative $Y$ "Sky Person" Paradox:**
   * In camera optical coordinates, negative $Y$ is **upward** above the camera center.
   * Here $Y = -5.837\text{ m}$. To an engineer reading this file, it appears the pedestrian is levitating nearly 6 meters in the air!
   * *Root Cause:* The camera is mounted on a windshield tilted downwards by $\approx 7^\circ$. At $Z = 67.9\text{ m}$, a flat road appears visually higher on screen than the optical horizon ($v < c_y$). Without extrinsics to account for pitch, the math naively treats this as positive elevation.
2. **Arbitrary Focal Length Scaling:**
   * The script used `--focal_length_x 500.0`.
   * For a Full HD $1920 \times 1080$ camera with a standard $68^\circ$ FOV:
     $$f_x = \frac{1920}{2 \cdot \tan(34^\circ)} \approx 1423.3\text{ pixels}$$
   * Because $f_x$ was set to $500$ instead of $1423$, the computed lateral position $X$ is inflated by nearly $300\%$! ($X = 21.55\text{ m}$ instead of $\approx 7.5\text{ m}$).
3. **Windshield Sampling Noise:**
   * Sampling depth at the bounding box centroid $\frac{y1+y2}{2}$ frequently samples vehicle roofs, windshields, or background foliage behind pedestrians.
   * Sampling at the bottom-center $(c_x, y_2)$ targets the ground contact point, which can be cross-checked with the road plane equation.

---

## 6. Deep Dive: Model Mechanics, GPU Acceleration & Temporal Streaming

To understand how the workstation CV pipeline operates, we break down its two neural components:

### 6.1 YOLO26 + ByteTrack (`yolo_script.py`)
* **Detection Backbone:** Ultralytics YOLO26 (`yolo26n.pt`), optimized with TensorRT or PyTorch CUDA kernels.
* **Association Algorithm (ByteTrack):**
  * Rather than discarding low-confidence bounding boxes, ByteTrack uses a two-stage association strategy:
    1. First associates high-confidence detections ($S > 0.6$) with existing tracks using Kalman filter motion predictions and bounding box IoU.
    2. Second associates remaining unmatched tracks with low-confidence detections ($0.1 < S < 0.6$), successfully recovering temporarily occluded or blurred vehicles.
* **GPU Memory Footprint:** Light ($\approx 1.2\text{ GB}$ VRAM for batch size 1 at $1920 \times 1080$).

### 6.2 ByteDance Video Depth Anything (VDA) (`run_metric_grid.py`)
* **Architecture:** Vision Transformer (ViT-L) backbone trained with spatio-temporal attention layers.
* **The Streaming Engine (`infer_video_depth_one`):**
  * Standard Depth Anything operates on single static frames, causing severe high-frequency temporal flicker.
  * VDA resolves this via streaming recurrent attention: it processes frames sequentially, retaining an internal cached hidden state $\mathbf{H}_t$:
    $$\mathbf{D}_t, \mathbf{H}_t = \text{Model}(\mathbf{I}_t, \mathbf{H}_{t-1})$$
  * This guarantees temporal consistency across video frames without requiring the entire video to be loaded into GPU memory at once.
* **VRAM Allocation & Precision:**
  * In FP16 mode, ViT-L requires $\approx 4.5\text{ GB}$ VRAM at resolution $518 \times 518$.
  * Inference latency on an NVIDIA RTX GPU is $\approx 25\text{–}35\text{ ms}$ per frame ($30\text{ fps}$ near real-time).

---

## 7. The Fused Pipeline Architecture (Vision + Radar + Calibration)

```mermaid
flowchart TD
    subgraph Data Inputs ["Synchronized In-Vehicle Data"]
        V[Video File .mp4 / .mkv]
        T[Shutter Timestamps CSV]
        R[TI Radar Binary Stream radar_frames.bin]
        C[Active Calibration radar_camera_calib.json]
    end

    subgraph Perception Layer ["Workstation Vision Models"]
        V --> Y[YOLO26 + ByteTrack]
        Y -->|2D Bboxes & Tracks| BBOX[2D Detection Table]
        V --> VDA[Video Depth Anything ViT-L]
        VDA -->|Temporal Depth Map| DDEPTH[Dense Metric Depth Map]
    end

    subgraph RoadSense Fusion Engine ["RoadSense Spatial Projection Engine"]
        C -->|Pitch, Yaw, Height, Intrinsics K| SPE[SpatialProjectionEngine]
        R -->|3D Point Cloud & Tracks| SPE
        SPE -->|2D Screen Projections (u, v, Range, V_doppler)| RADAR_PROJ[Projected Radar Detections]
    end

    subgraph Association & Scale Correction ["Multi-Modal Fusion Core"]
        BBOX --> ASSOC[Hungarian Bipartite Matcher]
        RADAR_PROJ --> ASSOC
        DDEPTH --> ASSOC
        ASSOC -->|Matched Pairs| SCALE[Dynamic Scale Calibrator: alpha = Z_radar / Z_vda]
        SCALE -->|Scale Corrected Z| CORRECTED[Calibrated 3D Obstacles]
        SPE -->|Inverse Extrinsics R_inv, T| CORRECTED
    end

    subgraph Outputs ["Automated Ground Truth & Telemetry"]
        CORRECTED --> MCAP[Foxglove MCAP: Unified 3D Frustum + Radar + Video]
        CORRECTED --> GT_JSON[3D Ground Truth Annotations JSON: nuScenes/KITTI]
        CORRECTED --> BENCH[Monocular Depth Benchmark Report]
    end
```

### 7.1 Hungarian 2D-3D Bipartite Association Algorithm
For each frame $t$:
1. We project all active TI radar tracks $\mathbf{T}_i = (x_r, y_r, \text{range}, v_r)$ into screen pixel coordinates $(u_r, v_r)$ using [`SpatialProjectionEngine.project2DRadarToScreen()`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt#L34-L107).
2. For each 2D vision bounding box $\mathbf{B}_j = (x_1, y_1, x_2, y_2)$, we define a cost function $C_{i,j}$:
   $$C_{i,j} = w_1 \cdot \text{NormalizedDistance}((u_r, v_r), \text{BboxCenter}_j) + w_2 \cdot (1 - \text{PointInsideBbox}(u_r, v_r))$$
3. Solve the assignment problem using the Hungarian algorithm (linear sum assignment).
4. For every matched pair $(i, j)$:
   * Radar range $Y_r$ becomes the **ground-truth distance** for vision track $j$.
   * Doppler velocity $V_{\text{doppler}}$ is attached to vision track $j$.
   * Semantic class (e.g. `car`, `truck`, `motorcycle`, `pedestrian`) is attached to radar track $i$.

### 7.2 Dynamic Depth Auto-Calibration ($\alpha$-Correction)
For all matched objects in frame $t$:
$$\alpha_t = \text{median}\left(\left\{ \frac{Z_{r, k}}{Z_{\text{VDA}, k}} \right\}_{k=1}^K\right)$$
If $\alpha_t$ systematically deviates from $1.0$ (e.g. $\alpha = 1.15$), the model is underestimating depth by $15\%$. The entire dense depth field is multiplied by $\alpha_t$:
$$\mathbf{D}_{\text{calibrated}} = \alpha_t \cdot \mathbf{D}_{\text{VDA}}$$
This delivers self-calibrating dense metric depth that adapts dynamically to camera changes!

---

## 8. Ground Truth Dataset Generation Specification

The fused pipeline generates rich, standardized 3D bounding box datasets ready for training and benchmarking ADAS perception models.

### Output JSON Schema (`session_ground_truth_3d.json`)
```json
{
  "metadata": {
    "session_id": "session_20261007_143000",
    "vehicle_id": "Bajaj_Test_Mule_01",
    "calibration_profile": "IRVM Bonnet Mount",
    "intrinsics": { "fx": 1420.5, "fy": 1420.5, "cx": 960.0, "cy": 540.0 },
    "extrinsics": { "pitch_deg": 7.0, "yaw_deg": -1.0, "roll_deg": 0.0, "camera_height_m": 1.45 }
  },
  "frames": [
    {
      "frame_index": 124,
      "timestamp_utc_ns": 1791369000123456789,
      "shutter_mono_ns": 45123984712,
      "scale_factor_alpha": 1.024,
      "objects_3d": [
        {
          "track_id": 4,
          "class_name": "car",
          "confidence_2d": 0.92,
          "bbox_2d": { "x1": 840, "y1": 410, "x2": 1120, "y2": 620 },
          "ground_contact_pixel": { "u": 980.0, "v": 620.0 },
          "position_vehicle_iso_m": {
            "x_lat": 0.35,
            "y_long": 18.42,
            "z_elev": 0.85
          },
          "position_camera_optical_m": {
            "x": 0.35,
            "y": 0.12,
            "z": 18.15
          },
          "radar_ground_truth": {
            "matched": true,
            "radar_track_id": 7,
            "range_m": 18.42,
            "doppler_mps": -4.25,
            "snr_db": 38.5,
            "azimuth_deg": 1.1
          },
          "depth_model_estimate_m": 17.98,
          "depth_residual_error_m": -0.44
        }
      ]
    }
  ]
}
```

---

## 9. Reference Implementation Blueprint

To bridge RoadSense and `D:\Work\CV`, we define a dedicated Python CLI tool located in the RoadSense tools suite:

```
RoadSense/
 ├── intel/
 │    └── Vision/
 │         └── RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md (This File)
 └── tools/
      └── vision_fusion/
           ├── run_fused_ground_truth_pipeline.py  <-- Master Workstation Fusion CLI
           ├── spatial_calibrator.py               <-- Python port of SpatialProjectionEngine
           └── radar_bbox_matcher.py               <-- Hungarian bipartite association
```

### Master CLI Usage
```powershell
python "tools/vision_fusion/run_fused_ground_truth_pipeline.py" `
    --session_dir "d:\Work\CV\Logs" `
    --video_path "d:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4" `
    --calib_file "c:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\intel\Implementations\radar_camera_calib.json" `
    --tracking_json "d:\Work\CV\Logs\try1.json" `
    --encoder vitl `
    --export_mcap `
    --output_dir "d:\Work\CV\Logs\fused_output"
```

---

## 10. Verification & Evaluation Strategy

1. **Step 2 Verification (Baseline Audit):**
   * Inspect existing detections in `try1_with_depth.json`.
   * Plot $Y$ vs. $Z$ distribution to visualize the negative-$Y$ pitch bias.
2. **Step 3 Verification (Model & GPU Profiling):**
   * Benchmark memory usage and inference FPS of ByteTrack and VDA ViT-L.
3. **Step 4 Verification (Fused Pipeline Evaluation):**
   * Compute Mean Absolute Error (MAE) and Root Mean Squared Error (RMSE) between uncalibrated VDA depth and radar ground-truth range across all frames:
     $$\text{MAE}_{\text{raw}} = \frac{1}{N} \sum_{i=1}^N |Z_{\text{VDA}, i} - Z_{\text{radar}, i}|$$
     $$\text{MAE}_{\text{calibrated}} = \frac{1}{N} \sum_{i=1}^N |\alpha \cdot Z_{\text{VDA}, i} - Z_{\text{radar}, i}|$$
   * Target: Calibrated fusion reduces MAE by $\ge 40\%$ and aligns all vehicle coordinates with the ground plane ($Z_{\text{elev}} \approx \text{target center-of-mass} \pm 0.3\text{ m}$).
