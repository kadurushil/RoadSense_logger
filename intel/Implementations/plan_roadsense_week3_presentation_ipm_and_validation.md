# Implementation Plan: RoadSense Technical Progress & Vision Presentation Deck (16-Slide Master Deck)

## Goal Description
Expand and update the executive & technical progress presentation for RoadSense. This incorporates the major architectural, algorithmic, and diagnostic breakthroughs developed on the `validation` branch since October 5, 2026. 

Specifically, this plan:
1. Expands on the Geometric Vision pipeline as **Inverse Perspective Mapping (IPM)**, detailing its mathematical raycasting derivations, ADAS legacy, and operational advantages vs. physical pitfalls (2–3 slides).
2. Documents the **IMU-Assisted Camera Calibration & Live Horizon Engine**, detailing 1-tap gravitational pitch extraction, digital clamp spirit leveling, ADAS pitch quality metrics, and pillarbox studio UI (1 slide).
3. Establishes the **Master Sensor Decoupling & Coordinate System Architecture**, decoupling Camera Boresight (`cameraYawDeg`, `cameraPitchDeg`) from Radar Mounting Squint (`radarSquintDeg`) relative to `base_link` (1 slide).
4. Explains the **Radar & Vision Track Association** and benchmarking framework concisely (1 slide).
5. Documents the **Master Timeline Synchronizer & Nanosecond Alignment**, resolving the 2.16s video phase lead via hardware sequence number locking (1 slide).
6. Explains the **100 Hz Zero-Jitter Kinematics Engine**, eliminating 18 ms IMU jitter spikes via SPSC lock-free ring buffers and native 104 Hz rate locking (1 slide).
7. Highlights our **Deep-Tech Diagnostic Innovations**:
   - **On-Device Perfetto Kernel Tracing via ADB Shell** for nanosecond thread scheduling & hardware IRQ analysis without rooting (1 slide).
   - **Zero-Dependency Standalone Interactive HTML Audit Reports** with coordinated 6-track zooming, side-by-side dual scorecards, and Foxglove-style mouse-locked crosshair HUDs (1 slide).
8. Showcases the **Central Web Dashboard & Unified Platform** (`localhost:8088`), integrating vision pipelines, 1-click batch health/sync audits, and SSE streaming (1 slide).
9. Documents the **System Hardening & Bug Post-Mortems** (Bugs #16 through #25) at the conclusion of the deck in the established 4-card format (2 slides).
10. Defines the **Updated Milestone & Hardening Roadmap**, setting the primary target as **System Perfection**: eliminating mechanical mounting play between camera and radar to breach **$>66\%$ track association** before training automated validation models (1 slide).

---

## User Review Required

> [!IMPORTANT]
> **Slide Deck Scope & Structure Review (16 Slides Total)**  
> This updated outline now features dedicated slides for our two standout diagnostic innovations: **Perfetto ADB Kernel Tracing** and **Interactive Standalone HTML Audit Reports**, cementing an authoritative engineering presence for executive review.

> [!NOTE]
> **Native Equation Workflow Preservation**  
> In accordance with our established presentation standard, all mathematical equations will have clean, dedicated layout space reserved in the PPTX cards. Exact copy-pasteable LaTeX strings will be provided in the accompanying Slide-by-Slide Handover Notes document for native PowerPoint insertion (`Alt + =` / OMML).

---

## Master 16-Slide Presentation Deck Architecture

| Slide # | Category & Section | Slide Title & Core Focus | Origin / Rationale |
| :---: | :--- | :--- | :--- |
| **01** | **Title Slide** | **RoadSense: Perception, IPM & Systems Hardening**<br>Multimodal perception, IPM telemetry, and automotive systems hardening. | Master Deck Foundation |
| **02** | **Executive Summary** | **Sprint Deliverables: From Optical IPM to Nanosecond Hardware Sync**<br>High-level overview: IPM perception, IMU auto-leveling, 10,400 FPS pipeline, Perfetto kernel audits, and central diagnostics hub. | Executive Pitch |
| **03** | **Perception (Slide A1)** | **Inverse Perspective Mapping (IPM): Mathematical Derivation**<br>Raycasting model: pinhole ray inversion ($\vec{d}_C = \mathbf{K}^{-1}[u, v, 1]^T$), flat-earth plane intersection ($Z_{\text{road}} = 0$), closed-form ground coordinates ($Y_v, X_v$). | **User Request A** |
| **04** | **Perception (Slide A2)** | **IPM in Automotive ADAS: Legacy, Advantages & Physical Pitfalls**<br>Decades of automotive ADAS heritage (MobilEye, Dickmanns). **Advantages:** $O(1)$ real-time, no GPU/depth model, 50+ FPS. **Pitfalls:** Road slope/crown, pitch dynamics from braking/accel ($\pm 1.5^\circ$), pitch sensitivity derivative ($\frac{\partial Y}{\partial\theta} \approx -\frac{Y^2}{H}$), horizon singularity. | **User Request A** |
| **05** | **Perception (Slide A3)** | **Modular Geometric Vision Pipeline & MCAP Integration**<br>YOLO26 + ByteTrack tracking, dual bounding box sizing (nuScenes class priors vs. camera optical spans), 180° rotation auto-detection, `/vision/geo_tracks` injected into Foxglove MCAP in Electric Magenta. | **User Request A** |
| **06** | **Spatial Setup** | **IMU-Assisted Camera Calibration & Live Horizon Engine**<br>1-tap gravitational pitch extraction (`GAME_ROTATION_VECTOR`), digital clamp spirit level ($\pm 0.8^\circ$), ADAS pitch quality badge ($3^\circ-12^\circ$), 25 Hz Live Horizon follow mode, and pillarbox studio HUD. | **User Request (IMU Calibration)** |
| **07** | **Spatial Calibration** | **Master Sensor Decoupling & Coordinate System Architecture**<br>Decoupling Camera Boresight (`cameraYawDeg`, `cameraPitchDeg`) from Radar Mounting Squint (`radarSquintDeg`). Eliminating camera frustum tilt. Master ISO 8855 FLU vs. OpenCV RDF coordinate frame standards. | **Git Audit (Oct 21, Bug #25)** |
| **08** | **Benchmarking (Slide B)**| **Radar-to-Vision Track Association & Spatial Evaluation**<br>Hungarian matching (`lapjv`) within $\pm 50\text{ ms}$ window and $3.5\text{m}$ Euclidean gating. Error metrics: Longitudinal ($\Delta Y$) and Lateral ($\Delta X$) MAE/RMSE across Near ($0\text{--}15\text{m}$), Mid ($15\text{--}30\text{m}$), and Far ($>30\text{m}$) zones. | **User Request B** |
| **09** | **Core Infrastructure** | **Master Timeline Synchronizer & Nanosecond Clock Alignment**<br>Resolving the 2.16s video phase lead (Bug #22) by locking MP4 packets to hardware sequence numbers ($S_{\text{record}} + i$) with crystal interpolation. Integrated `/sync/frame_info` stream. | **Git Audit (Oct 19, Bug #22)** |
| **10** | **Vehicle Dynamics** | **100 Hz Zero-Jitter Kinematics & Android OS Hardening**<br>Eliminating 18 ms IMU jitter spikes (Bug #19) via `SENSOR_DELAY_FASTEST` (0 µs) and SPSC lock-free ring buffer, locking to ST LSM6DSL native 104 Hz rate. Gravity-free linear acceleration & magnetic-immune 6-DOF game rotation. | **Git Audit (Oct 14, Bug #19)** |
| **11** | **Diagnostic Toolchain** | **Deep-Tech Diagnostics: On-Device Perfetto Kernel Tracing via ADB**<br>Zero-overhead nanosecond Linux kernel scheduler tracing on connected hardware without rooting. Tracing `sched_switch`, `irq`, and in-app `atrace` slices. Proving $<0.05\text{ ms}$ app slice execution and diagnosing HAL thread preemption vs. epoll wait. | **User Request (Perfetto Toolchain)** |
| **12** | **Diagnostic Toolchain** | **Data Delivery: Standalone Interactive HTML Audit Reports & HUD**<br>Zero-dependency, single-file offline HTML reports (~1.2 MB, `file:///`) powered by bundled Plotly.js. Coordinated 6-track synchronized zooming, Foxglove-style mouse-locked crosshair HUD (eliminating jitter), sticky navigation, and dual certification scorecards. | **User Request (HTML Audit Reports)** |
| **13** | **Operations Platform (Slide D)** | **Central Web Dashboard: Unified Script Execution Platform**<br>Zero-dependency local web dashboard (`localhost:8088`) with SSE live progress streaming. Centralized 1-click runner executing sync audits, MCAP conversion (10,400 FPS), health checks, and geometric vision pipeline. Native `/api/open_folder` OS integration. | **User Request D** |
| **14** | **System Hardening (Slide C1)** | **Root Cause Analysis: Bugs #16 Through #19 Resolved**<br>4-card format: Bug #16 (MP4 moov Window Overflow), Bug #17 (TLV 6 FCW Decoding Offset), Bug #18 (Foxglove Anonymous Schema Hash), Bug #19 (IMU 18 ms Jitter Spikes & HAL Decimation). | **User Request C** |
| **15** | **System Hardening (Slide C2)** | **Root Cause Analysis: Bugs #21 Through #25 Resolved**<br>4-card format: Bug #21 (Pre-Roll Video Desync), Bug #22 (Dropped Callbacks Phase Lead), Bug #23 (10,400 FPS Converter Boost), Bugs #24 & #25 (Optical Pitch Sign Correction & Camera/Radar Yaw Decoupling). | **User Request C** |
| **16** | **Milestone Roadmap (Slide E)** | **Milestone Handover & System Perfection Roadmap**<br>**Focus on System Perfection:** Eliminating mechanical mounting play and vibration on vehicle brackets. **Target gate:** Achieve $>66\%$ radar-vision track association before advancing to automated model training. Conservative, measured milestone commitments. | **User Request E** |

---

## Detailed Highlights for the New Diagnostic Slides

### Slide 11: On-Device Perfetto Kernel Tracing via ADB Shell
* **The Problem:** Microsecond timing anomalies (e.g. 18 ms IMU jitter spikes) cannot be diagnosed with standard userspace logging because logging itself introduces CPU overhead.
* **The Solution (`scripts/record_perfetto_trace.py` & `analyze_perfetto_trace.py`):**
  * Android's official zero-overhead tracing daemon (`perfetto`) writes directly to an in-memory 32–64 MB circular ring buffer ($< 1\%$ CPU impact).
  * Records Linux CFS scheduler events (`sched_switch`, `sched_wakeup`), hardware IRQs (`irq_handler_entry/exit`), and CPU frequency scaling across big.LITTLE cores.
  * Embeds custom application trace slices: `Trace.beginSection("RoadSense:onSensorChanged")`.
* **Key Finding:** Proved that `RoadSense-ImuThread` spends $<0.05\text{ ms}$ processing each sample, and that the 18 ms spikes were caused by Android HAL software decimation rather than CPU thread starvation.

### Slide 12: Standalone Interactive HTML Audit Reports & Foxglove-Style HUD
* **The Problem:** Static Matplotlib PNGs squished nominal $0\text{--}25\text{ms}$ steady-state data into unreadable flat lines due to 500 ms initialization spikes, and standard Plotly tooltips violently jittered when hovering over noisy 100 Hz signals.
* **The Solution (`scripts/cross_sensor_sync_engine.py`):**
  * **Zero Dependencies:** Pure standard library serialization (`json`, `os`, `sys`) with bundled `plotly-basic.min.js` (~1.0 MB). Creates a completely self-contained `.html` file (~1.2 MB) that opens offline on any PC via `file:///`.
  * **Foxglove-Style Mouse-Locked HUD:** Decoupled hover tooltips from discrete noisy $(x, y)$ coordinates; locks a vertical crosshair line to cursor `clientX` with $O(\log N)$ binary search, completely eliminating vertical tooltip bounce.
  * **Coordinated 6-Track Zooming:** Subplots share an anchored x-axis, zooming all sensors in lockstep.
  * **Sticky Viewport Toolbar:** 1-click presets (`🎯 Steady-State Focus`, `🔍 Show All Spikes`, `⚡ Startup Phase`, `🛑 Teardown Phase`).
  * **Dual Certification Scorecards:** 8-Point Subsystem Health + 10-Point Cross-Sensor Sync Checklist with `CERTIFIED GOLD` badges.

---

## Verification Plan

### Automated PPTX Generation & Syntax Check
```powershell
# Verify Node.js script execution and PPTX generation
node intel/presentations/generate_roadsense_week3_deck.js
```
* **Success Criteria:** Exit code 0, generated `.pptx` file in `intel/presentations/decks/`.

### Slide-by-Slide Handover Notes Document
* Create `intel/presentations/decks/RoadSense_Week3_Slide_by_Slide_Handover_Notes.md` containing:
  - The Executive Pitch for each slide.
  - Detailed background and physics explanations.
  - Copy-pasteable LaTeX equation strings for PowerPoint `Alt + =`.
  - Glossary of terms and bulletproof anticipated Q&A.

### Workspace Document Mirroring
* In accordance with Rule 6, mirror this plan directly into `intel/Implementations/plan_roadsense_week3_presentation_ipm_and_validation.md`.
