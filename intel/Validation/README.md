# RoadSense Automated Vision Ground-Truth & Radar Validation Suite

> **Document Classification:** Autonomous Vehicle Research & Engineering Systems  
> **Subsystem Scope:** Automated Optical 3D Ground-Truth Generation, Radar Target Tracking Benchmarking, CLEAR MOT Evaluation, ISO 15623 Pass/Fail Criteria  
> **Workspace Path:** `intel/Validation`  
> **Status:** Production Engineering Specification  

---

## 1. Executive Summary

The **RoadSense Validation Framework** transforms the synchronized multi-sensor datasets captured by the RoadSense application into an **automated reference laboratory**. 

By inverting the calibrated pinhole camera projection matrix ($\mathbf{K}$) and 6-DOF extrinsic transform ($[\mathbf{R} \mid \mathbf{T}]$) and enforcing the flat-earth road-plane constraint ($Z_{\text{road}} = 0$), high-capacity computer vision models (YOLOv8x/v11, Metric3D) extract frame-accurate metric 3D ground-truth trajectories from video without requiring multi-million-rupee differential GPS beacons (RTK-DGPS).

These optical trajectories are temporally aligned via `session_timeline.csv` and transformed into the radar coordinate frame $\{R\}$ to automatically benchmark mmWave radar target tracking algorithms across spatial kinematics, tracking continuity, latency, and clutter rejection.

---

## 2. Validation Documentation & Tooling Sitemap

This directory contains the authoritative mathematical formulations, benchmarking specifications, and developer runbooks for the automated validation framework:

| Document / Tool | Focus & Scope | Key Takeaways |
| :--- | :--- | :--- |
| **[`00_VALIDATION_ROADMAP_AND_ARCHITECTURE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/00_VALIDATION_ROADMAP_AND_ARCHITECTURE.md)** | Master Roadmap & System Architecture | 4-stage progressive roadmap, economic comparison against ₹20L RTK-DGPS beacons, end-to-end dataflow, and fleet milestones |
| **[`01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md)** | Mathematical Derivation | Inverse pinhole raycasting, closed-form flat-earth road plane intersection, pitch sensitivity analysis ($\partial Y/\partial\theta$), and extrinsics inversion |
| **[`02_RADAR_TRACKING_VALIDATION_METRICS_SPEC.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/02_RADAR_TRACKING_VALIDATION_METRICS_SPEC.md)** | Benchmarking Metrics & Standards | Formal CLEAR MOT standard (MOTA, MOTP, IDF1, MT/ML), 4-zone distance binning, kinematics RMSE, and ISO 15623 / Euro NCAP pass/fail thresholds |
| **[`03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md)** | Toolchain Architecture & CLI Runbook | Python pipeline architecture, standardized JSON data schemas (`vision_gt.json`, `matched_tracks.json`), CLI runbook, and batch fleet execution |
| **[`interactive_validation_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/interactive_validation_explorer.html)** | Interactive Web Simulator | Live ground-truth raycasting canvas, synthetic tracking noise/ghost injection, Hungarian matching, and real-time MOTA/RMSE calculator (offline functional) |

---

## 3. Four-Stage Progressive Roadmap

```
Stage 1: Optical 3D Ground Truth
   ├── Run YOLOv8x/v11 on camera_video.mp4
   ├── Closed-form flat-earth raycasting (Z=0)
   └── Output: vision_gt.json
   │
Stage 2: Spatial-Temporal Association
   ├── Time-interpolation via session_timeline.csv
   ├── Coordinate transform: P_R = R^T * (P_C - T)
   └── Hungarian data association (Mahalanobis gate)
   │
Stage 3: Automated Validation Metrics
   ├── Kinematics RMSE across 4 distance zones (0-15m, 15-40m, 40-80m, >80m)
   ├── CLEAR MOT evaluation (MOTA, MOTP, IDF1, ID switches)
   └── Automated executive HTML/PDF report generation
   │
Stage 4: Fleet Regression & Algorithm Tuning
   ├── Batch processing across test fleets
   └── Automated Bayesian tuning for Kalman/EKF parameters (Q, R, gating)
```

---

## 4. Quick-Start Guide for Engineers

1. **Explore the Concepts Interactively:**
   Open [`interactive_validation_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/interactive_validation_explorer.html) in any web browser to simulate target distance changes, inspect pitch sensitivity, inject synthetic multipath ghost targets, and observe live CLEAR MOT scorecard calculations.
2. **Review Mathematical Formulations:**
   Consult [`01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md) for coordinate frame definitions and ground-plane intersection formulas.
3. **Execute the Python Validation Pipeline:**
   Follow the runbook in [`03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md) to set up dependencies and run `run_session_validation.py`.
