# RoadSense Automated Vision Ground-Truth & Multi-Pillar ADAS Validation Suite

> **Document Classification:** Autonomous Vehicle Research & Engineering Systems  
> **Subsystem Scope:** Automated Optical 3D Ground Truth, Kinematic Consistency, SIL Replay Simulation, Cross-Modal Anomaly Mining, CLEAR MOT Benchmarking, ISO 15623 Pass/Fail Criteria  
> **Workspace Path:** `intel/Validation`  
> **Status:** Production Engineering Specification  

---

## 1. Executive Summary

The **RoadSense Validation Framework** transforms the synchronized multi-sensor datasets captured by the RoadSense application into an **automated reference laboratory**. 

To overcome the physical boundaries of single-sensor testing (such as optical rain/night limits or expensive ₹20L–₹35L RTK-DGPS beacons), RoadSense deploys a **6-Pillar Validation Ecosystem**:
1. **Optical 3D Ground Truth:** Inverted pinhole raycasting ($Z_{\text{road}} = 0$) and metric monocular depth.
2. **Ego-Motion Kinematic Consistency:** Doppler zero-velocity invariance of stationary roadside clutter ($V_r + V_{\text{ego}} \cos \theta \equiv 0$) and dead-reckoning landmark stability via CAN wheel speed and 100 Hz IMU.
3. **Physical Target Protocols:** Sub-centimeter range and azimuth benchmarking using surveyed trihedral corner reflectors under Euro NCAP / ISO 15623 active safety maneuvers.
4. **SIL Digital-Twin Replay:** 100x accelerated replay of raw binary telemetry (`radar_frames.bin`) with synthetic fault injections (frame drops, clutter bursts, multipath ghosts) to benchmark candidate trackers (EKF, UKF, IMM).
5. **Cross-Modal Anomaly Mining:** Autonomous edge-case mining flagging sensor discrepancies (phantom radar targets, radar blindness, kinematic paradoxes) with automated 5-second video clip extraction.
6. **Fleet Depot CI/CD Engine:** Automated workshop Wi-Fi log offloading and continuous regression gates.

---

## 2. Validation Documentation & Tooling Sitemap

This directory contains the authoritative mathematical formulations, benchmarking specifications, and developer runbooks:

| Document / Tool | Focus & Scope | Key Takeaways |
| :--- | :--- | :--- |
| **[`00_VALIDATION_ROADMAP_AND_ARCHITECTURE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/00_VALIDATION_ROADMAP_AND_ARCHITECTURE.md)** | Master Roadmap & System Architecture | 4-stage progressive roadmap, economic comparison against ₹20L RTK-DGPS beacons, end-to-end dataflow, and fleet milestones |
| **[`01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md)** | Mathematical Derivation | Inverse pinhole raycasting, closed-form flat-earth road plane intersection, pitch sensitivity analysis ($\partial Y/\partial\theta$), and extrinsics inversion |
| **[`02_RADAR_TRACKING_VALIDATION_METRICS_SPEC.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/02_RADAR_TRACKING_VALIDATION_METRICS_SPEC.md)** | Benchmarking Metrics & Standards | Formal CLEAR MOT standard (MOTA, MOTP, IDF1, MT/ML), 4-zone distance binning, kinematics RMSE, and ISO 15623 / Euro NCAP pass/fail thresholds |
| **[`03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md)** | Toolchain Architecture & CLI Runbook | Python pipeline architecture, standardized JSON data schemas (`vision_gt.json`, `matched_tracks.json`), CLI runbook, and batch fleet execution |
| **[`04_MULTI_PILLAR_VALIDATION_STRATEGY_AND_BRAINSTORMING.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/04_MULTI_PILLAR_VALIDATION_STRATEGY_AND_BRAINSTORMING.md)** | Multi-Pillar Strategy & Synergy | Comparative matrix across all 6 validation pillars, cross-sensor triangulation, and progressive implementation waves |
| **[`05_KINEMATIC_CONSISTENCY_AND_CAN_IMU_VALIDATION.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/05_KINEMATIC_CONSISTENCY_AND_CAN_IMU_VALIDATION.md)** | Kinematic Consistency Specification | Doppler zero-velocity invariant formulation, dead-reckoning landmark stability, mounting yaw drift detection, and Python audit script |
| **[`06_SIL_REPLAY_SIMULATION_AND_STRESS_TESTING.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/06_SIL_REPLAY_SIMULATION_AND_STRESS_TESTING.md)** | SIL Replay & Stress Testing | 100x accelerated digital-twin replay, candidate tracker benchmark harness (TI DSP vs EKF vs UKF vs IMM), and synthetic fault injection mechanics |
| **[`07_CROSS_MODAL_ANOMALY_DETECTION_AND_EDGE_CASE_MINING.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/07_CROSS_MODAL_ANOMALY_DETECTION_AND_EDGE_CASE_MINING.md)** | Cross-Modal Anomaly Mining | Autonomous discrepancy rules (ghost tracks, radar blindness, kinematic paradoxes), automated video highlight reel, and triage manifest |
| **[`08_PROFESSIONAL_ADAS_VALIDATION_PLAYBOOK.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/08_PROFESSIONAL_ADAS_VALIDATION_PLAYBOOK.md)** | Operational ADAS Playbook | 3-layer decoupled testing pyramid, CIPV curve compensation, TTC formulas, Euro NCAP/ISO test matrices, and day-by-day lead checklist |
| **[`interactive_validation_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/interactive_validation_explorer.html)** | Interactive Web Simulator | Live ground-truth raycasting canvas, synthetic tracking noise/ghost injection, Hungarian matching, and real-time MOTA/RMSE calculator (offline functional) |

---

## 3. Four-Stage Progressive Roadmap

```
Stage 1: Optical 3D Ground Truth
   ├── Run YOLOv8x/v11 on camera_video.mp4
   ├── Closed-form flat-earth raycasting (Z=0)
   └── Output: vision_gt.json
   │
Stage 2: Spatial-Temporal Association & Kinematic Validation
   ├── Time-interpolation via session_timeline.csv
   ├── Coordinate transform: P_R = R^T * (P_C - T)
   ├── Hungarian data association (Mahalanobis gate)
   └── Stationary clutter Doppler check (V_r + V_ego * cos(θ) == 0)
   │
Stage 3: Automated Validation Metrics & SIL Replay
   ├── Kinematics RMSE across 4 distance zones (0-15m, 15-40m, 40-80m, >80m)
   ├── CLEAR MOT evaluation (MOTA, MOTP, IDF1, ID switches)
   ├── Candidate tracker benchmarking (EKF vs UKF vs IMM)
   └── Automated executive HTML/PDF report generation
   │
Stage 4: Fleet Regression & Autonomous Anomaly Mining
   ├── Autonomous discrepancy mining (Ghost tracks, radar blindness)
   ├── Automated 5-second video highlight reel extraction
   └── Continuous depot CI/CD regression gates
```

---

## 4. Quick-Start Guide for Engineers

1. **Explore the Concepts Interactively:**
   Open [`interactive_validation_explorer.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/interactive_validation_explorer.html) in any web browser to simulate target distance changes, inspect pitch sensitivity, inject synthetic multipath ghost targets, and observe live CLEAR MOT scorecard calculations.
2. **Review Mathematical Formulations:**
   Consult [`01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/01_CAMERA_TO_3D_GROUND_TRUTH_MATHEMATICS.md) for coordinate frame definitions and [`05_KINEMATIC_CONSISTENCY_AND_CAN_IMU_VALIDATION.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/05_KINEMATIC_CONSISTENCY_AND_CAN_IMU_VALIDATION.md) for kinematic Doppler consistency formulas.
3. **Inspect the Multi-Pillar Strategy:**
   Read [`04_MULTI_PILLAR_VALIDATION_STRATEGY_AND_BRAINSTORMING.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/04_MULTI_PILLAR_VALIDATION_STRATEGY_AND_BRAINSTORMING.md) to understand how the 6 pillars cross-validate one another.
4. **Execute the Python Validation Pipeline:**
   Follow the runbook in [`03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Validation/03_AUTOMATED_TOOLCHAIN_AND_EXECUTION_RUNBOOK.md) to run single-session or fleet validation.
