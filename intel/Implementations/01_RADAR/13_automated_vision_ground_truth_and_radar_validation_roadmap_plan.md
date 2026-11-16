# Plan: Automated Vision Ground-Truth & Radar Tracking Validation Framework Roadmap

## Goal Description
With the successful deployment of RoadSense's synchronized multi-sensor logging engine, pinhole camera intrinsics ($K$), and 6-DOF extrinsic calibration ($[\mathbf{R} \mid \mathbf{T}]$), the foundation is in place to construct an **Automated Vision-Based Ground-Truth Generation & Radar Tracking Validation Framework**.

In traditional automotive radar validation, verifying tracking filters (e.g., TI DSP on-chip EKF, host-side Kalman filters, or custom multi-target trackers) requires expensive differential GPS (RTK-DGPS) carrier beacons mounted on target vehicles ($>\text{₹}20\text{ Lakhs}$ per beacon vehicle). 

Because RoadSense maintains deterministic monotonic nanosecond clock synchronization (`elapsedRealtimeNanos`) between the 1080p60 camera and 77 GHz mmWave radar, along with a validated spatial calibration matrix, **we can invert the optical projection pipeline**:
1. Run high-accuracy computer vision detection models on `camera_video.mp4`.
2. Map 2D optical bounding boxes into 3D metric coordinates $(X_C, Y_C, Z_C)$ via flat-earth road-plane raycasting ($Z_{\text{road}} = 0$) or monocular 3D bounding box estimation.
3. Transform optical 3D positions into the radar coordinate frame $\{R\}$ using the inverse extrinsic transform $[\mathbf{R} \mid \mathbf{T}]^{-1}$ to generate frame-accurate **Optical Ground Truth (OGT)**.
4. Automatically evaluate and benchmark radar target tracking algorithms across spatial accuracy (range, lateral, velocity RMSE), tracking continuity (MOTA, MOTP, IDF1, ID switches), and latency without requiring physical beacon hardware.

This plan details the phased roadmap, mathematical specifications, metric definitions, and automated toolchain architecture to be documented in [`intel/Validation`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation).

---

## User Review Required

> [!IMPORTANT]
> **Key Architecture Decisions for User Alignment:**
> 1. **Primary Ground-Truth Generation Approach:**
>    - **Stage 1 Baseline: 2D Bounding Box + Flat-Earth Road-Plane Raycasting ($Z_{\text{road}} = 0$):**
>      Uses state-of-the-art 2D detectors (e.g. YOLOv8x / YOLOv11 / Grounding DINO) to detect lead vehicle road contact patches $(u_{\text{bottom}}, v_{\text{bottom}})$. Known camera height $h_{\text{cam}}$ and pitch $\theta_{\text{mount}}$ yield a closed-form, deterministic metric range $(X, Y)$ without model depth hallucinations.
>    - **Stage 2 Enhancement: Monocular 3D Bounding Box / Metric Depth Integration:**
>      Incorporates 3D bounding box estimators (e.g., Metric3D / Depth Anything v2 / MonoFlex) to infer physical vehicle dimensions (length, width, height) and handle non-flat road slopes.
> 2. **Evaluation Metrics Standard:**
>    - Adopt standard automotive perception & tracking benchmarks: **CLEAR MOT** (MOTA, MOTP, MT/ML tracks, ID switches), spatial kinematic error distributions (range RMSE, lateral RMSE, velocity RMSE partitioned into distance bins: 0–15m, 15–40m, 40–80m), and track initiation/coasting latency.
> 3. **Validation Suite Structure in `intel/Validation`:**
>    - The generated documents in [`intel/Validation`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation) will serve as the authoritative engineering specifications and runbooks for implementing the PC-side Python validation pipeline.

---

## Open Questions

> [!NOTE]
> 1. **Target Vehicle Classes:** Should the validation framework focus primarily on standard 4-wheel passenger vehicles, or explicitly prioritize **two-wheelers (motorcycles, scooters) and auto-rickshaws** (which are critical for Indian traffic ADAS)? *(Recommendation: Include specialized bounding box anchoring for two-wheelers and auto-rickshaws).*
> 2. **Radar Tracking Baseline:** Will we be validating the **TI DSP internal on-chip EKF tracks** (TLV Type 7), a **host-side Python/Kotlin tracking filter** (e.g., Hungarian association + Extended Kalman Filter), or both comparatively? *(Recommendation: Support both by decoupling the tracker input schema).*

---

## Proposed Roadmap & Documentation Suite

We will structure the validation framework documentation inside [`intel/Validation`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation) into four authoritative modular documents and an interactive browser-based validation explorer:

```
intel/Validation/
├── 00_VALIDATION_ROADMAP_AND_ARCHITECTURE.md          [NEW: Master Roadmap, 4-Stage Plan & System Topology]
├── 01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md        [NEW: Inverse Pinhole Raycasting, Road-Plane Math & Intrinsics]
├── 02_RADAR_TRACKING_VALIDATION_METRICS_SPEC.md       [NEW: CLEAR MOT, Kinematic RMSE, Latency & Pass/Fail Criteria]
├── 03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md    [NEW: Python Pipeline Architecture, Schemas & CLI Runbook]
└── interactive_validation_explorer.html               [NEW: Interactive Web Simulator for GT Raycasting & MOT Metrics]
```

---

### Component 1: Master Roadmap & Framework Architecture

#### [NEW] `intel/Validation/00_VALIDATION_ROADMAP_AND_ARCHITECTURE.md`
- **1. Executive Mission & Strategic Context:**
  - The economics of ADAS perception validation: replacing ₹20L RTK-DGPS beacons with optical camera ground truth.
  - Leveraging RoadSense's core assets: nanosecond hardware clock synchronization, calibrated 6-DOF extrinsics, and unified session storage.
- **2. The 4-Stage Progressive Roadmap:**
  - **Stage 1 (Near-Term): Optical 3D Ground-Truth Engine:**
    - Offline Python toolchain parsing `camera_video.mp4` using high-capacity vision models (YOLOv8x/v11).
    - Raycasting road contact patches onto the ground plane $Z=0$ using camera intrinsics $K$ and mounting height/pitch.
    - Exporting standardized `vision_ground_truth.json` indexed by monotonic nanoseconds.
  - **Stage 2 (Mid-Term): Temporal Association & Gating Engine:**
    - Time-interpolating vision ground truth to exact radar chirp timestamps via `session_timeline.csv`.
    - 3D spatial transformation of ground truth into radar coordinate frame $\{R\}$.
    - Global Nearest Neighbor (GNN) and Hungarian association with Mahalanobis distance gating on $(X, Y, \dot{Y})$.
  - **Stage 3 (Validation Suite): Automated Benchmarking & Report Generator:**
    - Implementation of CLEAR MOT (MOTA, MOTP, IDF1, False Positives, Misses, ID Switches).
    - Kinematic error quantification: Range RMSE, lateral error distributions, velocity accuracy.
    - Automated generation of executive HTML/PDF validation scorecards per session.
  - **Stage 4 (Continuous CI/CD): Fleet-Scale Regression Harness:**
    - Batch regression across hundreds of test drives.
    - Tracking algorithm parameter optimization (tuning EKF process noise $Q$, measurement noise $R$, and gating thresholds $\chi^2$ against optical ground truth).

---

### Component 2: Mathematical Formulation of Optical 3D Ground Truth

#### [NEW] `intel/Validation/01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md`
- **1. Inverse Pinhole Projection Geometry:**
  - Camera projection recap: $(u, v) = \mathbf{K} \cdot \mathbf{P}_C / Z_C$.
  - Normalized camera ray definition:
    $$\mathbf{d}_C = \mathbf{K}^{-1} \begin{bmatrix} u \\ v \\ 1 \end{bmatrix} = \begin{bmatrix} (u - c_x) / f_x \\ (v - c_y) / f_y \\ 1 \end{bmatrix}$$
- **2. Flat-Earth Road-Plane Intersection ($Z_{\text{road}} = 0$):**
  - Rigid body transformation between camera frame $\{C\}$ and vehicle/ground frame $\{V\}$.
  - Derivation of metric distance along optical ray intersecting the plane $Z = 0$:
    $$Y_{\text{ground}} = \frac{h_{\text{cam}}}{\tan\left(\theta_{\text{mount}} + \arctan\left(\frac{v_{\text{bottom}} - c_y}{f_y}\right)\right)}$$
    $$X_{\text{ground}} = Y_{\text{ground}} \cdot \frac{u_{\text{bottom}} - c_x}{f_x} \cdot \cos(\theta_{\text{mount}})$$
  - Pitch sensitivity analysis: Error propagation $\frac{\partial Y}{\partial \theta}$ and why in-situ pitch calibration is essential.
- **3. Coordinate Transformation: Optical Ground Truth to Radar Frame:**
  - Applying inverse extrinsics:
    $$\mathbf{P}_R = \mathbf{R}^T \cdot (\mathbf{P}_C - \mathbf{T})$$
  - Transforming 3D bounding box corners and vehicle centroid.
- **4. Monocular 3D Deep Learning Enhancements (Stage 2):**
  - Utilizing Metric3D / Depth Anything v2 for non-planar road elevation (crests, dips, slopes).
  - Fusion of 2D bottom-edge raycasting with dense monocular depth for robust multi-hypothesis 3D localization.

---

### Component 3: Radar Tracking Validation Metrics Specification

#### [NEW] `intel/Validation/02_RADAR_TRACKING_VALIDATION_METRICS_SPEC.md`
- **1. Kinematic Accuracy Metrics (Spatial & Velocity):**
  - **Longitudinal Range Error ($e_Y$):** Absolute error, relative percentage error ($|e_Y| / Y$), and Root Mean Square Error (RMSE).
  - **Lateral Position Error ($e_X$):** Lateral offset accuracy (critical for lane assignment and cut-in false alarm rejection).
  - **Euclidean Spatial Error ($e_{2D}$):** $\sqrt{\Delta X^2 + \Delta Y^2}$.
  - **Doppler vs. Derived Velocity Error ($e_V$):** Comparing radar radial velocity against optical ground-truth finite differences and vehicle CAN speed.
  - **Distance-Binned Performance Breakdown:** Evaluation partitioned into 4 standard ADAS operational zones:
    * Zone 1: Near-Field ($0\text{m} - 15\text{m}$)
    * Zone 2: Mid-Range ($15\text{m} - 40\text{m}$)
    * Zone 3: Long-Range ($40\text{m} - 80\text{m}$)
    * Zone 4: Far-Field ($> 80\text{m}$)
- **2. Tracking Continuity & Temporal Metrics (CLEAR MOT):**
  - **MOTA (Multiple Object Tracking Accuracy):**
    $$\text{MOTA} = 1 - \frac{\sum_t (m_t + fp_t + mme_t)}{\sum_t g_t}$$
  - **MOTP (Multiple Object Tracking Precision):** Average spatial distance between matched tracks and ground truth.
  - **IDF1 Score:** Harmonic mean of identification precision and recall.
  - **Mostly Tracked (MT) vs. Mostly Lost (ML):** Percentage of targets tracked for $>80\%$ vs $<20\%$ of their trajectory.
  - **ID Switches ($mme$):** Frequency of track ID swapping during close-target crossing.
- **3. ADAS System-Level Latency & Reliability Metrics:**
  - **Track Confirmation Latency ($T_{\text{confirm}}$):** Time in milliseconds/frames from optical target entry to confirmed radar track state.
  - **Track Coasting Duration ($T_{\text{coast}}$):** Persistence of track predictions during brief sensor occlusion.
  - **Ghost Target / Clutter Rate:** Quantification of false alarms from roadside guardrails, overhead bridges, and multipath reflections.
- **4. Automotive Pass/Fail Compliance Thresholds:**
  - Proposed benchmark criteria aligned with Euro NCAP and ISO 15623 (Forward Collision Warning):
    * Range Accuracy: $\le \max(0.5\text{m}, 2.5\% \text{ of range})$
    * Lateral Accuracy: $\le 0.4\text{m}$ at $<30\text{m}$
    * Velocity Accuracy: $\le 0.5\text{ m/s}$ ($1.8\text{ km/h}$)
    * Track Initiation Time: $\le 150\text{ ms}$ (3 radar frames at 20 Hz)
    * MOTA Score: $\ge 85\%$ in unobstructed highway scenarios.

---

### Component 4: Automated Toolchain Architecture & Developer Runbook

#### [NEW] `intel/Validation/03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md`
- **1. Pipeline Component Architecture:**
  - Data ingestion: Parsing `session_YYYYMMDD_HHMMSS/` (video, radar binary, timeline CSV, calib JSON).
  - Python toolchain modular breakdown:
    * `extract_vision_gt.py`: Runs YOLO/Metric3D on `camera_video.mp4`, performs road-plane raycasting, and outputs `vision_gt.json`.
    * `associate_tracks.py`: Loads `radar_frames.bin` and `vision_gt.json`, interpolates timestamps via `session_timeline.csv`, applies Hungarian matching, and outputs `matched_tracks.json`.
    * `compute_validation_metrics.py`: Computes MOTA, MOTP, RMSE, latency, and distance-binned stats.
    * `generate_validation_report.py`: Produces interactive standalone HTML validation report with Plotly/Canvas time-series charts.
- **2. Unified JSON Data Schemas:**
  - Full JSON schemas for `vision_gt.json`, `radar_tracks.json`, and `validation_summary.json`.
- **3. Developer CLI Runbook & Automation Scripts:**
  - Single-command execution:
    ```bash
    python tools/validation/run_session_validation.py --session logs/session_20260924_103000 --calib calibration/radar_camera_calib.json
    ```
  - Batch execution script for multi-session fleet validation.

---

### Component 5: Interactive Web Validation Explorer

#### [NEW] `intel/Validation/interactive_validation_explorer.html`
- A rich, zero-dependency, self-contained interactive web application demonstrating the validation concepts:
  - **Interactive Viewfinder & Ground-Truth Raycasting Simulator:**
    - Live perspective canvas displaying a simulated forward camera view with an adjustable target vehicle.
    - Drag vehicle to change distance ($10\text{m} - 80\text{m}$) or lateral lane position.
    - Real-time display of optical bounding box $(u, v)$, raycast ground-plane projection $(X_C, Y_C)$, and transformed radar coordinates $(X_R, Y_R)$.
  - **Radar Tracker Evaluation Simulator:**
    - Inject synthetic tracking noise, latency, and multipath ghost targets.
    - Live Hungarian data association visualizer (connecting radar tracks to optical ground truth).
    - Dynamic calculation of MOTA, MOTP, range RMSE, and lateral error in real time as the user manipulates target parameters.
  - **Distance-Binned Error Histogram:**
    - Visual breakdown of spatial accuracy across 0–15m, 15–40m, 40–80m ranges.

---

## Verification Plan

### Automated Verification
1. **Markdown & Link Validation:**
   - Verify all file cross-references between [`intel/Validation/`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation) and existing project docs are fully resolvable.
2. **HTML5 Syntax & Zero-Dependency Audit:**
   - Confirm `interactive_validation_explorer.html` is valid HTML5 and functions 100% offline without CDN dependencies.
3. **Mathematical Consistency Check:**
   - Verify coordinate frame formulas against [`intel/Sensor fusion basics.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Sensor%20fusion%20basics.md) and [`app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt).

### Manual Verification
1. **Mathematical Rigor Review:**
   - Review inverse raycasting and flat-earth equations to ensure optical pitch tilt and height offsets properly account for lens distortion and aspect ratio.
2. **Interactive Simulator Testing:**
   - Open `interactive_validation_explorer.html` in browser to test target dragging, Hungarian association matching, and MOTA score updates.
