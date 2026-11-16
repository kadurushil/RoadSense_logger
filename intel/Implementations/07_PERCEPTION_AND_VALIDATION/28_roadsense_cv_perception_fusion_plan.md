# RoadSense × CV 3D Perception & Ground-Truth Fusion Architecture Plan

## Goal Description
Execute a phased, step-by-step program to master, evaluate, and fuse the computer vision tracking & metric depth pipeline from `D:\Work\CV` (Ultralytics YOLO26 + ByteTrack, ByteDance Video Depth Anything) with RoadSense's calibrated camera-radar spatial alignment engine, Android Camera2 hardware intrinsics, and TI mmWave radar ground truth.

This phased roadmap allows us to:
1. Establish the conceptual and mathematical foundation in `intel/Vision/` (**Step 1 - Completed**).
2. Generate and evaluate unaligned baseline data using the raw CV repo to observe real-world failure modes (uncalibrated focal length, pitch tilt bias, depth compression) (**Step 2**).
3. Conduct a deep architectural dive into model management, GPU allocation, ByteTrack Kalman filtering, and VDA streaming hidden states (**Step 3**).
4. Build the unified multi-modal fusion engine, using RoadSense extrinsics, intrinsics, and mmWave radar ground truth to calibrate metric depth and produce automated 3D annotations and Foxglove MCAP telemetry (**Step 4**).

---

## User Review Required

> [!IMPORTANT]
> **Step-by-Step Phased Execution Order**:
> * **Step 1 (Done):** Created [`intel/Vision/RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Vision/RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md) as the central technical reference.
> * **Step 2 (Next Action):** Inspect and run the raw CV pipeline on `D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4` to establish our unaligned baseline dataset, observing why pedestrians 68m away produce negative $Y$ coordinates (levitating) and uncalibrated lateral $X$ spans.
> * **Step 3:** Deep dive into how `yolo_script.py` and `estimate_bbox_depths.py` manage the model, stream frames through GPU VRAM, and maintain temporal attention caches.
> * **Step 4:** Implement the RoadSense fusion bridge script (`fuse_radar_cv_ground_truth.py`), coupling radar tracks with YOLO bboxes for closed-loop metric depth auto-calibration ($\alpha = Z_{\text{radar}} / Z_{\text{VDA}}$).

---

## The Phased Roadmap

```mermaid
flowchart TD
    subgraph Step 1 ["Step 1: Strategic Planning & Architecture (DONE)"]
        A[Create intel/Vision/ Directory] --> B[RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md]
    end

    subgraph Step 2 ["Step 2: Baseline Unaligned Generation & Audit (CURRENT)"]
        C[Run Raw CV Pipeline on WIN_20250902 Video] --> D[Inspect try1_with_depth.json]
        D --> E[Audit Negative Y Pitch Drift & Arbitrary Focal Length Scaling]
    end

    subgraph Step 3 ["Step 3: Mechanics, Model Lifecycle & GPU Profiling"]
        F[YOLO26 + ByteTrack Tracking Pipeline Analysis]
        G[ByteDance VDA ViT-L Streaming State infer_video_depth_one]
        H[VRAM Allocation, Precision FP16 vs FP32, Inference Latency]
    end

    subgraph Step 4 ["Step 4: Fusion & Ground Truth Generation Engine"]
        I[Inject RoadSense K Matrix from Camera2]
        J[Inject 6-DOF [R|T] Keystone Extrinsics]
        K[Hungarian Bipartite 2D-3D Radar-Vision Matching]
        L[Dynamic Scale Calibrator: alpha = Z_radar / Z_VDA]
        M[Automated nuScenes/KITTI JSON & Foxglove MCAP Export]
    end

    Step 1 --> Step 2 --> Step 3 --> Step 4
```

---

## Detailed Step Breakdown

### Step 1: Architectural Foundation (Completed)
* Created `c:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\intel\Vision\`.
* Authored [`RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Vision/RoadSense_CV_Perception_and_Ground_Truth_Fusion_Guide.md) covering:
  * Full mathematical formulation of coordinate transformations (Optical RDF vs ISO 8855 vs ROS FLU).
  * Extrinsic matrix derivation $[R|T]$ incorporating camera pitch $\theta$, yaw $\psi$, roll $\phi$, and elevation $H_{\text{cam}}$.
  * Pinhole back-projection equations and ground plane ray intersections.
  * The physical ground truth role of TI mmWave radar (TLV Type 1 point clouds and TLV Type 7 tracks).

### Step 2: Baseline Unaligned Generation & Audit (Next Action)
* Execute the raw CV scripts from `D:\Work\CV` without any alignment or external calibration:
  1. `yolo_script.py` $\rightarrow$ Generates 2D tracking bounding boxes and track IDs.
  2. `estimate_bbox_depths(with updates for metric x, and y calculation).py` $\rightarrow$ Samples VDA depth at bounding box centers using uncalibrated `--focal_length_x 500.0`.
* Analyze real outputs from `D:\Work\CV\Logs\try1_with_depth.json`:
  * Measure the vertical $Y$ error caused by uncalibrated downward camera pitch.
  * Compare the sampled depths against known automotive distances.
  * Document why sampling at the geometric center $\frac{y1+y2}{2}$ introduces noise on vehicle roofs/windshields.

### Step 3: Model Management, GPU Acceleration & Temporal Mechanics
* Inspect model loading and PyTorch memory allocations:
  * ViT-L weights (`metric_video_depth_anything_vitl.pth`) parameter count and CUDA cache behavior.
  * How `infer_video_depth_one()` caches hidden feature representations $\mathbf{H}_t$ to avoid inter-frame flickering.
  * How ByteTrack maintains track state across frame dropouts using two-stage confidence matching.
* Document performance metrics: VRAM footprint, per-frame latency, and batch processing constraints.

### Step 4: Multi-Modal Perception Fusion & Ground Truth Engine
* Develop the master fusion script:
  * Ingests RoadSense session files (`radar_camera_calib.json`, `radar_frames.bin`, `video.mp4`, `timestamps.csv`).
  * Replaces estimated focal lengths with real Camera2 intrinsics $K$.
  * Rotates optical coordinates into true vehicle coordinates $(X_v, Y_v, Z_v)$ using calibrated $[R|T]$.
  * Projects mmWave radar tracks into camera pixel coordinates.
  * Solves 2D-3D association with Hungarian bipartite matching.
  * Computes dynamic scale correction $\alpha = Z_{\text{radar}} / Z_{\text{VDA}}$.
  * Exports standardized 3D ground truth annotations and Foxglove MCAP telemetry.

---

## Verification Plan

### Automated Tests
1. Verify syntax and execution parameters of `D:\Work\CV` Python scripts using Python 3 environment.
2. Confirm integrity of output JSON structures (`try1.json`, `try1_with_depth.json`).

### Manual Verification
1. Inspect baseline tracking and depth videos generated from `D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4`.
2. Review quantitative error metrics comparing unaligned output vs. calibrated RoadSense geometry.
