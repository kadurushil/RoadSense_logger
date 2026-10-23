# RoadSense Week 3 Executive & Technical Presentation: Slide-by-Slide Handover & Speaker Notes

> **Document Name:** `RoadSense_Week3_Slide_by_Slide_Handover_Notes.md`  
> **Workspace Location:** `intel/presentations/decks/RoadSense_Week3_Slide_by_Slide_Handover_Notes.md`  
> **Target Deck:** [`RoadSense_Week3_Executive_Progress.pptx`](./RoadSense_Week3_Executive_Progress.pptx)  
> **Target Audience:** Engineering Executives, ADAS Program Leads, Perception & Validation Engineers  
> **Author:** RoadSense Core Engineering Team  
> **Last Updated:** October 22, 2026  

---

## Master Table of Contents

1. [Executive Briefing: How to Use This Document](#executive-briefing-how-to-use-this-document)
2. [Global Acronym & Terminology Quick Reference](#global-acronym--terminology-quick-reference)
3. [Slide 01: Title Slide (Executive Sprint Briefing)](#slide-01-title-slide-executive-sprint-briefing)
4. [Slide 02: Sprint Executive Summary (From Optical IPM to Nanosecond Hardware Sync)](#slide-02-sprint-executive-summary-from-optical-ipm-to-nanosecond-hardware-sync)
5. [Slide 03: Inverse Perspective Mapping (IPM) — Mathematical Derivation](#slide-03-inverse-perspective-mapping-ipm--mathematical-derivation)
6. [Slide 04: IPM in Automotive ADAS: Heritage, Advantages & Pitfalls](#slide-04-ipm-in-automotive-adas-heritage-advantages--pitfalls)
7. [Slide 05: Modular Geometric Vision Pipeline & MCAP Integration](#slide-05-modular-geometric-vision-pipeline--mcap-integration)
8. [Slide 06: IMU-Assisted Camera Calibration & Live Horizon Engine](#slide-06-imu-assisted-camera-calibration--live-horizon-engine)
9. [Slide 07: Master Sensor Decoupling & Coordinate System Architecture](#slide-07-master-sensor-decoupling--coordinate-system-architecture)
10. [Slide 08: Radar-to-Vision Track Association & Spatial Evaluation](#slide-08-radar-to-vision-track-association--spatial-evaluation)
11. [Slide 09: Master Timeline Synchronizer & Nanosecond Clock Alignment](#slide-09-master-timeline-synchronizer--nanosecond-clock-alignment)
12. [Slide 10: 100 Hz Zero-Jitter Kinematics Subsystem & Android OS Hardening](#slide-10-100-hz-zero-jitter-kinematics-subsystem--android-os-hardening)
13. [Slide 11: Deep-Tech Diagnostics: On-Device Perfetto Kernel Tracing via ADB](#slide-11-deep-tech-diagnostics-on-device-perfetto-kernel-tracing-via-adb)
14. [Slide 12: Data Delivery: Standalone Interactive HTML Audit Reports & HUD](#slide-12-data-delivery-standalone-interactive-html-audit-reports--hud)
15. [Slide 13: Central Web Dashboard: Single Unified Script Execution Platform](#slide-13-central-web-dashboard-single-unified-script-execution-platform)
16. [Slide 14: System Hardening — Root Cause Analysis: Bugs #16 Through #21 (Compact 6-Bug Grid)](#slide-14-system-hardening--root-cause-analysis-bugs-16-through-21-compact-6-bug-grid)
17. [Slide 15: System Hardening — Root Cause Analysis: Bugs #22 Through #25, #12, #13/#14 (Compact 6-Bug Grid)](#slide-15-system-hardening--root-cause-analysis-bugs-22-through-25-12-1314-compact-6-bug-grid)
18. [Slide 16: Milestone Handover & System Perfection Roadmap](#slide-16-milestone-handover--system-perfection-roadmap)
19. [Emergency Cheat Sheet for Tough Boardroom Q&A](#emergency-cheat-sheet-for-tough-boardroom-qa)

---

## Executive Briefing: How to Use This Document

This document is your **personal cheat sheet** and speaking script for presenting `RoadSense_Week3_Executive_Progress.pptx`. 

If you are presenting in an executive boardroom, engineering review, or test track environment:
1. **The Executive Pitch:** Deliver this 30-second conversational opening for each slide immediately upon advancing.
2. **De-Mystified Concepts & Deep Dive:** Clear explanations of the underlying physics, coordinate geometry, and kernel scheduling.
3. **Copy-Pasteable LaTeX Formulas:** For slides with mathematical formulas, copy the provided LaTeX string, press `Alt + =` inside PowerPoint to open an equation box, paste the string, and press `Enter` to produce native vector OMML.
4. **Speaker Script:** Natural, conversational talking points you can read or adapt word-for-word.
5. **Anticipated Questions & Bulletproof Answers:** Detailed explanations for tough technical or management inquiries.

---

## Global Acronym & Terminology Quick Reference

| Acronym / Term | Full Name | Plain English Explanation |
| :--- | :--- | :--- |
| **IPM** | Inverse Perspective Mapping | A mathematical transformation that projects 2D camera pixels onto a flat 3D ground plane ($Z_{\text{road}} = 0$) using camera intrinsics and mounting pitch. |
| **Boresight** | Optical / Antenna Centerline | The optical or radio line-of-sight axis pointing directly forward along the sensor's physical travel vector. |
| **Squint ($\psi_{\text{radar}}$)** | Radar Mounting Angular Offset | The physical angle between the radar bumper bracket and the true vehicle driving centerline. |
| **`base_link`** | Vehicle Body Root Frame | The ISO 8855 standard coordinate frame anchored to the host vehicle chassis ($+X$ Forward, $+Y$ Left, $+Z$ Up). |
| **`camera_optical`** | Pinhole Camera Frame | The OpenCV/ROS optical convention ($+X$ Right across sensor, $+Y$ Down toward ground, $+Z$ Optical Depth). |
| **PTS** | Presentation Timestamp | The microsecond clock timestamp assigned to a video frame by the camera hardware encoder indicating when it must be displayed. |
| **Atrace / Ftrace** | Android / Linux Kernel Tracing | Linux kernel infrastructure that records scheduler context switches (`sched_switch`) and hardware interrupts without debugger overhead. |
| **SPSC** | Single-Producer Single-Consumer | A high-performance lock-free ring buffer where one thread writes and another reads without mutex lock contention. |
| **HUD** | Heads-Up Display | A visual overlay displaying critical telemetry directly over the primary visualization window. |
| **CLEAR MOT** | Classification of Events, Activities & Relationships Multi-Object Tracking | The global academic and industrial benchmark standard for scoring tracking algorithms (MOTA, MOTP, IDF1). |
| **Lapjv** | Jonker-Volgenant Algorithm | A fast, optimal algorithm for solving the Linear Sum Assignment Problem (Hungarian matching) in polynomial time. |

---

## Slide 01: Title Slide (Executive Sprint Briefing)

```
[ ROADSENSE: WEEK 3 PROGRESS ]
Inverse Perspective Mapping, Coordinate Decoupling & Multi-Sensor Hardening
Confidential • Bajaj Auto Ltd. • Advanced Perception & Telemetry
```

### 1. The Executive Pitch
> "Good morning, everyone. In Sprint 3, we transitioned RoadSense from an experimental multi-sensor logger into an operational on-device perception and automated diagnostic suite. Today, we will show you our closed-form Inverse Perspective Mapping pipeline running at over 50 FPS on mobile CPUs, our IMU-assisted auto-leveling calibration studio, our nanosecond Master Timeline Synchronizer that permanently eliminated a 2-second video phase lead, our ADB-based Perfetto kernel tracing diagnostics, and our roadmap toward reaching a certified 66% track association milestone."

### 2. De-Mystified Concepts & Deep Dive
* **What changed between Sprint 2 and Sprint 3?**  
  Sprint 2 proved 6-DOF spatial math and baseline Foxglove MCAP generation. Sprint 3 focused on *offline geometric computer vision (IPM), separating vehicle chassis coordinates from individual sensor mounting squints, eliminating nanosecond clock desyncs under heavy OS load, and conducting deep-tech kernel diagnostics*.
* **Why "53 JVM Unit Tests Passing Clean"?**  
  Every coordinate transform matrix, axis permutation, TLV parser stride, and timestamp associator is backed by automated JVM tests executed on every build. MASTER has zero code regressions.

### 3. Speaker Script
> "Before we unpack the algorithms, note that all 10 system bugs discovered during field testing—from kernel HAL decimation spikes to optical matrix inversions—have been completely resolved and verified by 53 unit tests. Let's look at the executive summary."

---

## Slide 02: Sprint Executive Summary (From Optical IPM to Nanosecond Hardware Sync)

```
[ SPRINT EXECUTIVE OVERVIEW ]
Sprint 3 Deliverables: From Optical IPM to Nanosecond Hardware Sync
Consolidating closed-form vision geometry, spatial decoupling, kernel diagnostics, and bug resolutions
```

### 1. The Executive Pitch
> "This slide outlines our three foundational pillars for Sprint 3. First, computer vision: we deployed Inverse Perspective Mapping (IPM) with YOLO26, enabling real-time 3D vehicle tracking at over 50 FPS with zero neural depth inference overhead. Second, spatial alignment and timing: we created 1-tap IMU auto-leveling, decoupled camera boresight from radar squint, and fixed a 2-second video phase lead. Third, deep-tech toolchains: we built on-device Perfetto kernel tracing and offline interactive HTML audit reports that senior management can inspect on any PC without installing software."

### 2. De-Mystified Concepts
* **Pillar 1 (IPM):** Instead of running heavy 500 MB deep-learning depth networks (like Depth Anything) that require high-power GPUs and run at 10 FPS, IPM uses high-school trigonometry ($Z_{\text{road}} = 0$) to compute 3D metric obstacle distances in 20 microseconds.
* **Pillar 2 (Spatial Decoupling):** Previously, tweaking the camera alignment slider unintentionally tilted the radar coordinate frame. We decoupled them into an explicit two-tier vehicle chassis architecture.
* **Pillar 3 (Kernel Diagnostics):** Using Android's low-level Perfetto subsystem, we proved that our Android logging threads consume $<0.05\text{ ms}$ of CPU time, verifying that timing hiccups stemmed from vendor HAL decimation rather than app delays.

---

## Slide 03: Inverse Perspective Mapping (IPM) — Mathematical Derivation

```
[ PERCEPTION & VISION ENGINEERING ]
Inverse Perspective Mapping (IPM): Mathematical Derivation
Closed-form flat-earth raycasting (Z_road = 0) recovering 3D metric coordinates from 2D pixel contact patches
```

### 1. The Executive Pitch
> "How does a single 2D camera pixel become a 3D distance in meters? By using Inverse Perspective Mapping. Because vehicles drive on asphalt, their tire contact patches touch the ground plane where $Z = 0$. By inverting the camera's pinhole intrinsic matrix and rotating by mounting pitch, our closed-form raycasting equations solve exact longitudinal distance $Y_v$ and lateral displacement $X_v$ in constant time ($O(1)$) in under 20 microseconds."

### 2. Mathematical Derivation & Step-by-Step Breakdown

```
       Windshield Mount (H_cam = 1.35m)
             Camera [C]
                │ \  Optical Boresight Ray (d_C)
                │  \
                │   \  Depression Angle: β = θ_mount + arctan((v - c_y) / f_y)
                │    \
                ▼     ▼
  ═════════════════════●═════════════════════  Road Plane (Z_road = 0)
   0m                   Lead Vehicle Tire Contact
   ◄────── Y_v ────────►
```

#### Step 1: Inverse Optical Ray Vector ($\mathbf{d}_C$)
Given pixel coordinate $[u, v, 1]^T$ and camera intrinsic matrix $\mathbf{K}$:
$$\mathbf{d}_C = \mathbf{K}^{-1} \begin{bmatrix} u \\ v \\ 1 \end{bmatrix} = \begin{bmatrix} \frac{u - c_x}{f_x} \\ \frac{v - c_y}{f_y} \\ 1 \end{bmatrix}$$

#### Step 2: Total Depression Angle ($\beta$)
The bottom-most edge of the 2D bounding box $(u_{\text{center}}, v_{\text{bottom}})$ corresponds to the tire contact patch. The vertical depression angle $\beta$ relative to the road plane is:
$$\beta = \theta_{\text{mount}} + \arctan\left(\frac{v_{\text{bottom}} - c_y}{f_y}\right)$$

#### Step 3: Closed-Form Ground Coordinate Solution ($Z_{\text{road}} = 0$)
Intersecting this ray with the asphalt at camera height $H_{\text{cam}}$ yields:
$$Y_v = \frac{H_{\text{cam}}}{\tan(\beta)}$$
$$X_v = Y_v \cdot \left(\frac{u_{\text{center}} - c_x}{f_x}\right) \cdot \cos(\theta_{\text{mount}}) + \Delta X$$

### 3. Native PowerPoint LaTeX Formulas (Alt + =)
* **Equation 1 (Depression Angle):**
  `\beta = \theta_{\text{mount}} + \arctan\left(\frac{v_{\text{bottom}} - c_y}{f_y}\right)`
* **Equation 2 (Longitudinal Depth):**
  `Y_v = \frac{H_{\text{cam}}}{\tan(\beta)}`
* **Equation 3 (Lateral Position):**
  `X_v = Y_v \cdot \left(\frac{u_{\text{center}} - c_x}{f_x}\right) \cdot \cos(\theta_{\text{mount}}) + \Delta X`

### 4. Anticipated Tough Q&A
* **Q:** *Why take the bottom of the bounding box instead of the center?*
  * **A:** "A vehicle's center floats 0.75m in the air, violating $Z_{\text{road}} = 0$. The tire contact patch is the only optical feature guaranteed to physically touch the asphalt plane."

---

## Slide 04: IPM in Automotive ADAS: Heritage, Advantages & Pitfalls

```
[ PERCEPTION & VISION ENGINEERING ]
IPM in Automotive ADAS: Decades of Heritage, Advantages & Pitfalls
Why ground-plane projection powers automotive ADAS, and where its physical assumptions break down
```

### 1. The Executive Pitch
> "Inverse Perspective Mapping is not a novel heuristic—it is a 30-year cornerstone of automotive ADAS, first introduced in the 1990s Prometheus project and popularized by early MobilEye lane departure systems. While it provides blazing $O(1)$ real-time speed with zero GPU load, it has strict physical vulnerabilities: road slope changes, dynamic vehicle pitching during braking, and quadratic error divergence at long range."

### 2. De-Mystified Deep Dive: The Pitch Sensitivity Derivative

Why is precise calibration mandatory for IPM? Differentiating longitudinal depth $Y$ with respect to mounting pitch $\theta$:
$$Y = \frac{H}{\tan(\theta + \alpha)} \implies \frac{\partial Y}{\partial \theta} = -\frac{H}{\sin^2(\theta + \alpha)} \approx -\frac{Y^2}{H_{\text{cam}}}$$

#### The Quadratic Sensitivity Table (for $H_{\text{cam}} = 1.35\text{ m}$):
* At $Y = 15\text{ m}$: Error gradient is $166\text{ m/rad} \implies 0.5^\circ$ pitch error yields **$1.45\text{ m}$ error**.
* At $Y = 30\text{ m}$: Error gradient is $666\text{ m/rad} \implies 0.5^\circ$ pitch error yields **$5.81\text{ m}$ error**.
* At $Y = 50\text{ m}$: Error gradient is $1,851\text{ m/rad} \implies 0.5^\circ$ pitch error yields **$16.15\text{ m}$ error**!

### 3. Native PowerPoint LaTeX Formulas (Alt + =)
`\frac{\partial Y}{\partial \theta} \approx -\frac{Y^2}{H_{\text{cam}}}`

### 4. Speaker Script
> "Look at this sensitivity equation: error grows with the square of distance ($Y^2$). At 15 meters, half a degree of pitch error is negligible (1.4m). But at 50 meters, that exact same half-degree causes a 16-meter error! That is why we engineer IMU-assisted auto-leveling and radar cross-sensor association."

---

## Slide 05: Modular Geometric Vision Pipeline & MCAP Integration

```
[ PERCEPTION & VISION ENGINEERING ]
Modular Geometric Vision Pipeline & MCAP Integration
High-rate YOLO26 tracking, dual 3D bounding geometry, and non-destructive Foxglove topic injection
```

### 1. The Executive Pitch
> "To execute IPM on driving sessions, we built a modular offline pipeline. We run YOLO26 with ByteTrack to track vehicles across 57,000 frames at over 50 FPS. We automatically correct 180° reverse-landscape video mounting, calculate metric 3D box sizes from optical pixel spans, elevate box centers to road height, and inject these tracks into Foxglove MCAP files under `/vision/geo_tracks` in Electric Magenta without altering the original recordings."

### 2. De-Mystified Concepts
* **Optical Span Sizing vs Class Priors:**  
  If we only use class priors, every car is assumed to be $1.8\text{m} \times 4.2\text{m}$. With Option B, we use the camera's high resolution to measure the physical pixel width:
  $$W_{\text{metric}} = \frac{w_{\text{px}}}{f_x} \cdot Z_c, \quad H_{\text{metric}} = \frac{h_{\text{px}}}{f_y} \cdot Z_c$$
  This allows distinguishing a compact hatchback from a wide commercial bus.
* **Non-Destructive MCAP Enrichment:**  
  Raw drive logs in `logs/` (1.5 GB+) are marked strictly read-only. The injector creates an enriched copy preserving all 12 original hardware channels and layout attachments byte-for-byte.

---

## Slide 06: IMU-Assisted Camera Calibration & Live Horizon Engine

```
[ SPATIAL SETUP & CALIBRATION ]
IMU-Assisted Camera Calibration & Live Horizon Engine
Eliminating pitch guesswork via gravity vector extraction, digital spirit level, and 25 Hz horizon follow
```

### 1. The Executive Pitch
> "Because a 0.5° pitch error causes massive distance errors at 50 meters, technicians cannot rely on manual guesswork. We leveraged the smartphone's internal 6-DOF gravitational orientation vector. A technician mounts the phone, taps 'Sync Pitch', and the system extracts exact mounting pitch and roll in under 10 milliseconds. Our digital clamp spirit level confirms the phone is within ±0.8° of road level, and a live 25 Hz artificial horizon line projects directly on the viewfinder."

### 2. De-Mystified Concepts & Physics
* **Why `Sensor.TYPE_GAME_ROTATION_VECTOR`?**  
  Electric vehicles (EVs) produce intense electromagnetic fields from battery packs and motor inverters that cause magnetic compasses to glitch by 30°–90°. The Game Rotation Vector fuses only the accelerometer (measuring gravity) and gyroscope (measuring angular rates), making it 100% immune to EV electromagnetic interference.
* **Extraction Formulas:**
  $$\theta_{\text{pitch}} = \arcsin(R_8), \quad \phi_{\text{roll}} = \arcsin(R_7)$$
* **The Pillarbox Studio Layout:**  
  The Galaxy M30 screen has a wide 19.5:9 aspect ratio. When displaying standard 16:9 1080p video, 210-pixel blank pillarbox wings exist on the left and right. We placed pitch controls on the left wing and radar yaw on the right wing, keeping the center road preview 100% unobstructed.

### 3. Native PowerPoint LaTeX Formulas (Alt + =)
`\theta_{\text{pitch}} = \arcsin(R_8), \qquad \phi_{\text{roll}} = \arcsin(R_7)`

---

## Slide 07: Master Sensor Decoupling & Coordinate System Architecture

```
[ PERCEPTION & SPATIAL CALIBRATION ]
Master Sensor Alignment: Decoupling Camera Boresight from Radar Squint
Eliminating cross-sensor distortion by establishing an explicit two-tier vehicle coordinate architecture
```

### 1. The Executive Pitch
> "During testing, we discovered Bug #25: adjusting the calibration slider to compensate for a 1.0° radar bumper bracket squint accidentally rotated the camera optical frame, creating a 17-pixel lateral skew in 3D vision tracks. We resolved this by establishing a two-tier coordinate architecture: Camera Boresight is explicitly locked straight forward along the vehicle chassis ($0.0^\circ$), while radar bracket imperfections are isolated to Radar Mounting Squint ($+1.0^\circ$). Both sensors now project into vehicle `base_link` with zero cross-distortion."

### 2. De-Mystified Concepts: Frame Standards Comparison

```
     VEHICLE CHASSIS FRAME {base_link}             CAMERA OPTICAL FRAME {camera_optical}
            (ISO 8855 Standard)                           (OpenCV / ROS Standard)

                 +Z (Up)                                     +Z (Optical Depth Forward)
                  ▲                                           ▲
                  │   +X (Forward / Travel)                  /
                  │   ▲                                     /
                  │  /                                     /
                  │ /                                     └─────────► +X (Right across image)
                  └─────────► -Y (Right across chassis)   │
                                                          │
                                                          ▼ +Y (Down toward road)
```

* **Permutation Matrix $\mathbf{R}_0$ (FLU $\to$ RDF):**
  $$\mathbf{R}_0 = \begin{bmatrix} 0 & -1 & 0 \\ 0 & 0 & -1 \\ 1 & 0 & 0 \end{bmatrix}$$

### 3. Native PowerPoint LaTeX Formulas (Alt + =)
`\mathbf{R}_0 = \begin{bmatrix} 0 & -1 & 0 \\ 0 & 0 & -1 \\ 1 & 0 & 0 \end{bmatrix}`

---

## Slide 08: Radar-to-Vision Track Association & Spatial Evaluation

```
[ MULTI-SENSOR VALIDATION & BENCHMARKING ]
Radar-to-Vision Track Association & Spatio-Temporal Evaluation
Automated Hungarian bipartite matching and distance-binned error metrics against mmWave radar ground truth
```

### 1. The Executive Pitch
> "To quantitatively benchmark our optical IPM pipeline, we cross-evaluate it against our TI mmWave radar ground truth. Our automated evaluation engine matches vision and radar tracks within a ±50 ms window, solves optimal 2D bipartite assignment using the Hungarian algorithm (`lapjv`) with a 3.5m spatial gate, and computes longitudinal and lateral MAE and RMSE across near, mid, and far distance zones."

### 2. De-Mystified Concepts
* **What is Hungarian Matching (`lapjv`)?**  
  If camera sees 3 cars and radar sees 4 tracks, which radar track belongs to which visual car? The Jonker-Volgenant algorithm (`lapjv`) solves the global cost matrix in polynomial time, guaranteeing the mathematically optimal one-to-one assignment that minimizes total spatial distance error.
* **Why 3.5-Meter Gating?**  
  Standard automotive lane widths are 3.5 to 3.7 meters. Setting a 3.5m gating threshold prevents a radar track in the adjacent overtaking lane from falsely matching a car directly ahead in our lane.

---

## Slide 09: Master Timeline Synchronizer & Nanosecond Clock Alignment

```
[ CORE INFRASTRUCTURE & TIMING ]
Master Timeline Synchronizer: Nanosecond Multi-Sensor Alignment
Resolving cumulative video phase lead (Bug #22) via hardware-sequence locking and linear interpolation
```

### 1. The Executive Pitch
> "During a 40-minute drive, we uncovered a critical timing bug: camera video was moving 2.16 seconds before vehicle wheel speed or radar tracks climbed. The cause was subtle: the Android hardware encoder captured all 72,336 video frames with zero drops, but userspace dropped 91 metadata callbacks under heavy load. By locking video frames directly to camera hardware exposure sequence numbers with crystal interpolation, our Master Timeline Synchronizer permanently eliminated this phase lead, guaranteeing $<15\text{ ms}$ synchronization across the entire session."

### 2. De-Mystified Concepts: The Dropped Callback Trap

```
Camera Sensor Hardware (Zero Drops: 72,336 frames) ──► MediaRecorder (Video MP4: 72,336 frames)
          │
          ▼ (Under heavy CPU load, Android drops 91 userspace callbacks)
CameraCaptureSession Callback ──► camera_frames.csv (Only 72,245 rows!)
```

* If you map MP4 Packet $i \to$ CSV Row $i$, every dropped callback shifts subsequent video frames one row into the past! By frame 21,655, video was stamped 2.16 seconds too early.
* **The Hardware Sequence Invariant:**
  $$\text{expected\_hw\_seq} = \text{video\_start\_seq\_id} + \text{packet\_idx}$$
* This counter strictly increments by 1 for every physical sensor exposure. If a callback is dropped, we interpolate its timestamp using the camera crystal clock ($33.293\text{ ms/frame}$).

### 3. Native PowerPoint LaTeX Formulas (Alt + =)
`\text{expected\_hw\_seq} = \text{video\_start\_seq\_id} + \text{packet\_idx}`

---

## Slide 10: 100 Hz Zero-Jitter Kinematics Subsystem & Android OS Hardening

```
[ VEHICLE DYNAMICS & OS ENGINEERING ]
100 Hz Zero-Jitter Kinematics Subsystem & Android OS Hardening
Bypassing Android HAL decimation filters to achieve sub-millisecond IMU jitter at native 104 Hz
```

### 1. The Executive Pitch
> "In high-rate vehicle dynamics, timestamp jitter ruins Kalman filter velocity estimates. We discovered that requesting standard 10,000 microsecond delays caused the Android HAL to apply software rate decimation, producing periodic 18 ms jitter spikes. By switching to `SENSOR_DELAY_FASTEST` (0 µs) with an SPSC lock-free ring buffer, we bypass the software filter entirely and lock directly to the ST LSM6DSL's native 104 Hz hardware FIFO with sub-millisecond jitter ($\sigma < 1.0\text{ ms}$)."

### 2. De-Mystified Concepts
* **What is SPSC?** Single-Producer Single-Consumer lock-free ring buffer. The sensor hardware thread deposits raw bytes without acquiring mutex locks, preventing Java Garbage Collection (GC) pauses from causing dropped kinematic ticks.
* **Gravity-Removed Linear Acceleration:** Measures pure dynamic vehicle braking and cornering forces by subtracting Earth's $9.81\text{ m/s}^2$ gravitational vector in real time.

---

## Slide 11: Deep-Tech Diagnostics: On-Device Perfetto Kernel Tracing via ADB

```
[ DEEP-TECH TOOLCHAINS & DIAGNOSTICS ]
Deep-Tech Diagnostics: On-Device Perfetto Kernel Tracing via ADB
Zero-overhead nanosecond Linux kernel scheduler and hardware IRQ profiling on target hardware
```

### 1. The Executive Pitch
> "When diagnosing microsecond timing anomalies, standard software loggers alter system behavior by adding CPU overhead—the classic Heisenberg effect. We solved this by developing an on-device Perfetto diagnostic toolchain. Using ADB shell commands, we configure Android's low-overhead kernel ftrace engine to record Linux scheduler context switches, hardware interrupts, and in-app execution slices directly into an in-memory ring buffer with $<1\%$ CPU impact—proving conclusively that our sensor threads execute in under 50 microseconds."

### 2. De-Mystified Concepts
* **Why Perfetto?** It is Google's official next-generation Linux kernel tracing daemon. It reads directly from kernel tracefs buffers, providing nanosecond-precision visibility into:
  1. `sched/sched_switch`: When our thread is switched on or off a CPU core.
  2. `irq/irq_handler_entry`: The exact microsecond the ST LSM6DSL hardware interrupt pin fires.
  3. `atrace`: Custom in-app execution slices (`RoadSense:onSensorChanged`).
* **The Breakthrough Finding:** Traces proved that our IMU thread never suffered from CPU starvation; it was actively sleeping in `epoll_wait` because the vendor HAL filter simply didn't deliver the sample!

---

## Slide 12: Data Delivery: Standalone Interactive HTML Audit Reports & HUD

```
[ DEEP-TECH TOOLCHAINS & DIAGNOSTICS ]
Data Delivery: Standalone Interactive HTML Audit Reports & HUD
Zero-dependency offline inspection reports with synchronized 6-track zooming and jitter-free crosshair HUD
```

### 1. The Executive Pitch
> "Static Matplotlib graphs were failing us: 500 ms camera startup spikes squished nominal 20 ms steady-state data into unreadable flat lines. We built a zero-dependency HTML audit engine that bundles Plotly.js into a single 1.2 MB file. It opens instantly on any computer via `file:///` without Python or internet, provides coordinated 6-track lockstep zooming, eliminates hover tooltip jitter with a Foxglove-style mouse-locked crosshair HUD, and delivers dual Subsystem Health and Sync Certification scorecards."

### 2. De-Mystified Concepts: The Mouse-Locked HUD Innovation
* **The Problem (Bug #20):** Plotly's native hover architecture anchors tooltips to discrete data points $(x, y)$. In noisy 100 Hz signals, horizontal mouse movement caused the tooltip box to jump 80 pixels vertically, making inspection nauseating.
* **The Solution:** We decoupled tooltips from discrete $(x, y)$ coordinates. A GPU-accelerated vertical crosshair follows cursor `clientX`, while an $O(\log N)$ binary search samples values across all 6 panels simultaneously, rendering a rock-solid, jitter-free HUD.

---

## Slide 13: Central Web Dashboard: Single Unified Script Execution Platform

```
[ OPERATIONS & AUTOMATION TOOLCHAIN ]
Central Web Dashboard: Single Unified Script Execution Platform
Zero-dependency local cockpit on :8088 running synchronization, conversion, health audits, and vision
```

### 1. The Executive Pitch
> "To empower validation engineers and eliminate error-prone terminal commands, we created the RoadSense Web Cockpit on `localhost:8088`. Built with zero external dependencies using Python's standard library, it streams real-time Server-Sent Events (SSE) progress and features a 1-click master runner: ingest raw drive $\to$ synchronize master timeline $\to$ convert to MCAP at 10,400 FPS $\to$ execute 8-point health audit $\to$ run geometric vision tracking, complete with direct Windows Explorer folder opening."

### 2. De-Mystified Concepts
* **Server-Sent Events (SSE):** A lightweight HTTP standard where the Python server pushes continuous live log updates to the browser over a single persistent connection, eliminating heavy WebSocket dependencies.
* **10,400 FPS MCAP Converter:** Converted from $O(N^2)$ list comprehensions to $O(\log N)$ binary search (Bug #23), boosting conversion throughput by 80x and processing a 40-minute drive in 33 seconds.

---

## Slide 14: System Hardening — Root Cause Analysis: Bugs #16 Through #21 (Compact 6-Bug Grid)

```
Root Cause Analysis: Bugs #16 Through #21 (Part 1)
Overcoming large container overflows, CAN decoding shifts, schema errors, IMU decimation, hover jitter, and video pre-roll
```

### 1. The Executive Pitch
> "Reliability in safety-critical ADAS is forged in rigorous root-cause analysis. Slides 14 and 15 detail the 12 most critical hardware and software quirks we permanently eliminated, presented in an executive 6-card compact layout. On Slide 14, we cover Bugs #16 through #21: overcoming gigabyte MP4 container indexing crashes, correcting a 1-byte CAN offset in forward collision warnings, fixing Foxglove schema hash mismatches, bypassing HAL decimation filters for sub-millisecond IMU jitter, eliminating Plotly tooltip jumping with our crosshair HUD, and wiping out camera pre-roll timing offsets."

### 2. De-Mystified Concepts per Card (6-Card Grid)
* **Bug #16 (MP4 moov Atom Buffer Overflow):** MP4 files record a variable-length metadata index (`moov`) at their tail that grows with recording length. A fixed 16MB tail buffer threw out-of-memory errors on files $>500\text{ MB}$. Solved by parsing ISO box length headers directly in $O(1)$ zero-copy time.
* **Bug #17 (TLV 6 FCW 1-Byte Alignment):** The TI radar firmware injected an undocumented message counter byte immediately preceding the CAN payload, shifting payload bits by 8 and swapping TargetX with TargetY. Corrected offsets and unified color-coded warning alerts.
* **Bug #18 (Foxglove Schema Hash Rejection `0bb4508b`):** Returning generic JavaScript arrays from custom transformers failed Protobuf schema validation in Foxglove Studio. Solved by importing official `@foxglove/schemas` and typed `Float64Array`.
* **Bug #19 (IMU 18ms Jitter Spikes):** Supplying explicit sampling periods (e.g., $10,000\text{ µs}$) engaged Android HAL decimation filters, dropping every 4th sample. Solved via `SENSOR_DELAY_FASTEST` (0 µs), achieving $<1\text{ ms}$ jitter.
* **Bug #20 (Plotly Tooltip Hover Jitter):** Standard Plotly tooltips anchor to discrete $(x, y)$ points, causing 80px vertical erratic jumping over noisy 100 Hz IMU traces. Replaced with an $O(\log N)$ mouse-locked crosshair HUD.
* **Bug #21 (Pre-Roll Video Temporal Lead):** Hardware camera pipelines stream viewfinder preview frames before the hardware MP4 encoder starts recording, creating a variable lead. Solved by anchoring timestamps to `session_timeline.csv`.

---

## Slide 15: System Hardening — Root Cause Analysis: Bugs #22 Through #25, #12, #13/#14 (Compact 6-Bug Grid)

```
Root Cause Analysis: Bugs #22 Through #25, #12, #13/#14 (Part 2)
Eliminating frame drop phase drift, 80x MCAP boost, inverted optical pitch, yaw conflation, mutex locks, and ghost tracks
```

### 1. The Executive Pitch
> "Continuing our hardening post-mortems, Slide 15 addresses the remaining 6 critical system-level breakthroughs: eliminating cumulative 2.16-second video phase drift via hardware sequence mapping, achieving an 80x speedup in Foxglove MCAP conversion, fixing an inverted optical pitch matrix that projected targets 188 pixels into the sky, establishing two-tier sensor-to-vehicle coordinate decoupling, eliminating Android logging thread contention, and wiping out Foxglove ghost track artifacts."

### 2. De-Mystified Concepts per Card (6-Card Grid)
* **Bug #22 (Cumulative Frame Drop Phase Drift):** Missing camera preview callbacks caused monotonic timestamp queues to drift out of sync by up to 2.16 seconds. Solved by mapping hardware sequence IDs ($S_{\text{start}} + \text{idx}$) to lock step with the hardware crystal oscillator.
* **Bug #23 (MCAP Converter Quadratic Bottleneck):** Uncached list comprehensions for nearest-neighbor lookups scaled quadratically ($O(N^2)$), causing 12 billion heap allocations. Replaced with $O(\log N)$ binary search, boosting conversion 80x to 10,411 FPS.
* **Bug #24 (Optical Pitch Inversion +5.4°):** Python coordinate projection tooling had inverted signs in $R_x$ (evaluating to $R_x(-P)$), elevating the optical boresight $+5.4^\circ$ and placing bounding boxes 188 pixels above lead vehicles. Corrected sign conventions.
* **Bug #25 (Radar Yaw Conflation):** The radar mounting bracket squint angle was incorrectly conflated with vehicle chassis yaw. Established an explicit two-tier transform chain (`radar` $\to$ `base_link` $\to$ `camera_optical`).
* **Bug #12 (Dual-Stream Logger Lock Contention):** App-wide logging (`app_system.log`) contended with active session flight recording (`session_debug.log`) under heavy USB load. Decoupled into isolated non-blocking buffered IO streams.
* **Bugs #13 & #14 (Foxglove Ghost Tracks & In-Band H.264 GOPs):** Ephemeral scene entity IDs left zombie bounding boxes lingering in 3D scene updates, and missing in-band SPS/PPS headers caused video seek freezes. Resolved with atomic entity IDs and strict 1-second closed GOPs (`repeat-headers=1`).

---

## Slide 16: Milestone Handover & System Perfection Roadmap

```
[ STRATEGIC DIRECTION & ROADMAP ]
Milestone Handover & System Perfection Roadmap
Consolidating Sprint 3 perception foundations and establishing disciplined hardware alignment targets
```

### 1. The Executive Pitch
> "To close our Sprint 3 briefing: all foundational deliverables—IPM perception, IMU auto-leveling, sensor decoupling, nanosecond timeline synchronization, and deep-tech toolchains—are 100% complete and verified by 53 unit tests. Our next focus is disciplined: System Perfection. We will rigidize the vehicle's physical mounting brackets to eliminate vibration and play, with a strict milestone gate: achieve greater than 66% sustained radar-vision track association before advancing to automated model training."

### 2. De-Mystified Concepts: The Disciplined Path Forward
* **Why Focus on Hardware Rigidity?**  
  Algorithms cannot compensate for mechanical wobble. If the bumper radar bracket flexes by $1.0^\circ$ over bumps, our pitch sensitivity derivative ($\partial Y/\partial\theta$) proves that targets at 40m will oscillate by 10 meters! Hardening the metal brackets is prerequisite to reliable perception.
* **The Strict $>66\%$ Association Gate:**  
  We avoid over-promising autonomous AI capabilities prematurely. Only when real-world track association consistently surpasses 66% under rough driving will we initiate supervised multi-modal ML training.

---

## Emergency Cheat Sheet for Tough Boardroom Q&A

### Q1: "Why use Inverse Perspective Mapping instead of AI Depth Estimation (like Depth Anything)?"
* **Answer:** "Monocular AI depth models are computationally heavy (40–80 ms per frame on desktop GPUs), suffer from scale ambiguity (they know relative depth, not exact meters), and hallucinate under varying lighting. IPM runs in under 20 microseconds ($O(1)$) on low-power mobile CPUs, requires zero GPU silicon, and uses true metric calibration ($H_{\text{cam}}$ and $\theta_{\text{mount}}$). For highway and urban asphalt driving, IPM is the industry standard."

### Q2: "What happens to IPM when the road goes uphill or over a speed bump?"
* **Answer:** "That is the primary physical pitfall of IPM: it assumes a planar surface ($Z_{\text{road}} = 0$). An uphill slope causes the ray to hit the road prematurely, making targets appear closer than they are. We handle this through multi-modal sensor fusion: the mmWave radar measures true radial Doppler distance regardless of road slope. By cross-associating radar and vision, we validate the planar assumption."

### Q3: "How does the Master Timeline Synchronizer handle dropped camera frames?"
* **Answer:** "Android Camera2 increments its hardware frame counter (`CaptureResult.getFrameNumber()`) strictly by 1 for every exposure at the physical sensor level, even if userspace drops the software callback. We lock every video packet to its hardware sequence ID: $\text{expected\_hw\_seq} = S_{\text{start}} + \text{packet\_idx}$. When a callback is missing, we linearly interpolate its timestamp using the camera crystal clock ($33.293\text{ ms}$), completely preventing cumulative phase drift."

### Q4: "Why is the target gate set specifically to 66% track association?"
* **Answer:** "In automotive radar-camera tracking, radar detections suffer from specular reflections, ground clutter, and field-of-view limits (horizontal azimuth vs. optical frustum), while cameras suffer from occlusions. A $66\%$ ($2/3$) sustained association rate across continuous dynamic drive cycles proves that the physical sensor baselines, time synchronization, and extrinsic transforms are rock solid. Only once this baseline is met is it statistically valid to train supervised neural validation networks."
