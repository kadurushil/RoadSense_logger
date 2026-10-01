# Walkthrough: RoadSense Week 2 Presentation Update — Phase 3 ML Validation & Visual Showcases

> **Document Name:** `walkthrough_roadsense_week2_presentation_validation_pivot.md`  
> **Workspace:** `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense`  
> **Target Date:** September 30, 2026  
> **Generated PPTX:** [`RoadSense_Week2_Executive_Progress.pptx`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/presentations/decks/RoadSense_Week2_Executive_Progress.pptx)  
> **Generator Script:** [`generate_roadsense_week2_deck.js`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/presentations/generate_roadsense_week2_deck.js)  

---

## 1. Summary of Changes

In response to the Week 1 roadmap review (Slide 13 of `15_09_2026_ANDROID_LOGGER_Rushil_OG.pptx`) and the multi-pillar validation specifications in `intel/Validation/`, we updated the **Week 2 Executive & Technical Progress Presentation** (`RoadSense_Week2_Executive_Progress.pptx`):

1. **Slide 6 (Desktop Toolchain): Added Web Dashboard Visual Showcase**
   * Built an authentic dark-mode UI header mockup (`RoadSense Telemetry & Script Hub • http://127.0.0.1:8088`) with live telemetry pills: `ADB: Connected (M21)`, `BATTERY: 55% • 36.2°C`, `STORAGE: 42.8 GB Free`, and `GPU: NVENC Active`.
   * Integrated a dedicated visual screenshot placeholder slot (`addPlaceholder`) so the dashboard interface can be showcased during meetings without a live desktop connection.

2. **Slide 7 (Data Architecture): Added Foxglove 3D Perception Visual Showcase**
   * Re-architected into a split layout:
     * Left side contains the Zero-Cost `/tf` roll mathematical formula and the quantitative speed benchmark table (>11,500 FPS vs. 650 FPS NVENC vs. 85 FPS CPU).
     * Right side contains a designated visual placeholder slot for the **Foxglove Studio 3D Perception Cockpit** (`RoadSense_Cockpit_Layout.json`), highlighting the 3D scene, radar point clouds, upright video, and timeline scrubber.

3. **Slide 10 (Strategic Direction): Transformed into Phase 3 Automated ML Validation**
   * **Category:** `STRATEGIC DIRECTION • PHASE 3`
   * **Title:** `Automated ML Validation & Multi-Modal Sensor Fusion Engine`
   * **Subtitle:** `Correlating camera ground truth with radar point clouds to train superior perception models`
   * **Pillar 1 (Optical Ground Truth):** YOLOv8x/v11 detection of vehicles, road boundaries, drivable corridor, and roadside clutter; inverted pinhole raycasting ($Z_{\text{road}}=0$) replacing ₹35L RTK-DGPS beacons.
   * **Pillar 2 (Point-Cloud Correlation):** Correlating 3.125M radar returns against optical objects to discriminate true vehicles from multipath noise/ghosts; autonomous discrepancy mining (Rules 1 & 2) cutting 5-second video triage clips for human active learning.
   * **Pillar 3 (Fused Multimodal Model):** Fusing radar Doppler/depth with vision semantics to surpass camera-only limitations (rain, glare, night, long-range pitch errors) and automate fleet CLEAR MOT validation.

4. **Slide 11 (Project Handover): Formalized Milestone Handover & Phase 3 Sprint Plan**
   * **Left Card (Deliverables 100% Complete):** Formally documents that **Phase 1 (6-DOF Extrinsic Calibration)** and **Phase 2 (Live Viewfinder Overlay)** are **100% complete**, along with 100 Hz IMU kinematics and >11,500 FPS MCAP archiving.
   * **Right Card (Phase 3 Sprint Action Plan):** Details the 5 concrete action items for the upcoming sprint (Deploying inverted raycaster `vision_gt.json`, radar reflection correlator, discrepancy mining engine, active learning web UI, and training the benchmark multimodal model).

---

## 2. Quantitative Verification

The updated Node.js generation script was executed and verified:

```powershell
cd intel/presentations
node generate_roadsense_week2_deck.js
```

* **Output File:** `intel\presentations\decks\RoadSense_Week2_Executive_Progress.pptx`
* **File Size:** `377,597 bytes` (~378 KB)
* **Exit Code:** `0` (Success)
* **JVM Unit Tests:** `BUILD SUCCESSFUL` (24/24 unit tests passing cleanly via `./gradlew testDebugUnitTest`)

---

## 3. Visual Layout Reference

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ SLIDE 06: DESKTOP TOOLCHAIN (Web Dashboard Showcase & Telemetry Pills)                 │
│ ┌──────────────────────────────────────┐  ┌──────────────────────────────────────────┐ │
│ │ Zero-Dependency Local Web Server     │  │ RoadSense Telemetry Hub (:8088)          │ │
│ │ • http://localhost:8088              │  │ [ADB: OK] [BAT: 55%] [STORE: 42GB]       │ │
│ │ • /api/stream Live SSE Progress      │  ├──────────────────────────────────────────┤ │
│ │ • Autonomous ADB Device Poller       │  │ [ SCREENSHOT SLOT: Web Dashboard Cockpit]│ │
│ └──────────────────────────────────────┘  └──────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ SLIDE 07: DATA ARCHITECTURE (Foxglove 3D Perception & MCAP Benchmark)                  │
│ ┌──────────────────────────────────────┐  ┌──────────────────────────────────────────┐ │
│ │ Zero-Cost /tf Roll & Benchmark Table │  │ [ SCREENSHOT SLOT: Foxglove 3D Cockpit ] │ │
│ │ • >11,500 FPS Demux (~3.4s, 0% GPU)  │  │ • Upright 1080p Video + /tf Roll         │ │
│ │ • Embedded RoadSense Layout          │  │ • 3D Radar Point Clouds & Tracks         │ │
│ └──────────────────────────────────────┘  └──────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ SLIDE 10: STRATEGIC DIRECTION • PHASE 3 (Automated ML Validation & Fusion)             │
│ ┌───────────────────┐  ┌───────────────────────┐  ┌──────────────────────────────────┐ │
│ │ Pillar 1: Vision  │  │ Pillar 2: Correlation │  │ Pillar 3: Fused Multimodal Model │ │
│ │ 3D Ground Truth   │  │ Clutter vs. Target    │  │ Surpassing Camera-Only Limits    │ │
│ │ • Raycasting Z=0  │  │ • Noise vs Vehicle    │  │ • All-Weather Resilience         │ │
│ │ • BBoxes & Bounds │  │ • Ghost vs Blindness  │  │ • Eliminates Pitch Sensitivity   │ │
│ │ • Replaces RTK    │  │ • 5-sec Triage Clips  │  │ • Automated CLEAR MOT Scorecards │ │
│ └───────────────────┘  └───────────────────────┘  └──────────────────────────────────┘ │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ SLIDE 11: PROJECT HANDOVER (Phases 1 & 2 Complete, Phase 3 Launch)                     │
│ ┌──────────────────────────────────────┐  ┌──────────────────────────────────────────┐ │
│ │ Sprint 2 Deliverables: 100% COMPLETE │  │ Phase 3 Sprint Action Plan               │ │
│ │ ✅ Phase 1: 6-DOF Calibration (O(1)) │  │ 1. Deploy Inverted Raycaster (Z=0)       │ │
│ │ ✅ Phase 2: Live Viewfinder Overlays │  │ 2. Point-Cloud & Clutter Correlator      │ │
│ │ ✅ 100 Hz LSM6DSL IMU Kinematics     │  │ 3. Automated Discrepancy Mining Engine   │ │
│ │ ✅ Local Web Dashboard & MCAP        │  │ 4. Active Learning Web Triage UI (:8088) │ │
│ │ ✅ Bugs #11 to #15 Post-Mortems      │  │ 5. Train Fused Multimodal Benchmark Model│ │
│ └──────────────────────────────────────┘  └──────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────────────┘
```
