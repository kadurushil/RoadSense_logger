# RoadSense Week 2 Executive & Technical Presentation: Slide-by-Slide Handover & Speaker Notes

> **Document Name:** `RoadSense_Week2_Slide_by_Slide_Handover_Notes.md`  
> **Workspace Location:** `intel/presentations/decks/RoadSense_Week2_Slide_by_Slide_Handover_Notes.md`  
> **Target Deck:** [`RoadSense_Week2_Executive_Progress.pptx`](./RoadSense_Week2_Executive_Progress.pptx)  
> **Target Audience:** Engineering Executives, ADAS Program Leads, Perception & Validation Engineers  
> **Author:** RoadSense Core Engineering Team  
> **Last Updated:** September 30, 2026  

---

## Master Table of Contents

1. [Executive Briefing: How to Use This Document](#executive-briefing-how-to-use-this-document)
2. [Global Acronym & Terminology Quick Reference](#global-acronym--terminology-quick-reference)
3. [Slide 01: Title Slide (Executive Sprint Briefing)](#slide-01-title-slide-executive-sprint-briefing)
4. [Slide 02: Sprint Executive Summary (From Logger to Perception Platform)](#slide-02-sprint-executive-summary-from-logger-to-perception-platform)
5. [Slide 03: 6-DOF Spatial Calibration & Reverse Touch Solver](#slide-03-6-dof-spatial-calibration--reverse-touch-solver)
6. [Slide 04: Real-Time Viewfinder Radar Overlays (Hybrid Lollipops & Range Rings)](#slide-04-real-time-viewfinder-radar-overlays-hybrid-lollipops--range-rings)
7. [Slide 05: High-Rate 100 Hz IMU Subsystem & Kinematics](#slide-05-high-rate-100-hz-imu-subsystem--kinematics)
8. [Slide 06: Desktop Toolchain (Zero-Dependency Web Dashboard & ADB Automation)](#slide-06-desktop-toolchain-zero-dependency-web-dashboard--adb-automation)
9. [Slide 07: Data Architecture (High-Throughput Foxglove MCAP Pipeline >11,500 FPS)](#slide-07-data-architecture-high-throughput-foxglove-mcap-pipeline-11500-fps)
10. [Slide 08: System Hardening (Root Cause Analysis: Bugs #11 Through #15 Resolved)](#slide-08-system-hardening-root-cause-analysis-bugs-11-through-15-resolved)
11. [Slide 09: Quality Engineering (Quantitative Benchmarks & Automated Test Coverage)](#slide-09-quality-engineering-quantitative-benchmarks--automated-test-coverage)
12. [Slide 10: Strategic Direction • Phase 3 (Automated ML Validation & Multi-Modal Sensor Fusion)](#slide-10-strategic-direction--phase-3-automated-ml-validation--multi-modal-sensor-fusion)
13. [Slide 11: Project Handover & Roadmap (Milestone Handover: Phases 1 & 2 Complete, Phase 3 Launch)](#slide-11-project-handover--roadmap-milestone-handover-phases-1--2-complete-phase-3-launch)
14. [Emergency Cheat Sheet for Tough Q&A](#emergency-cheat-sheet-for-tough-qa)

---

## Executive Briefing: How to Use This Document

This guide is your **complete personal cheat sheet** for presenting `RoadSense_Week2_Executive_Progress.pptx`. 

If you are presenting in a boardroom, a laboratory, or on a test track without your desktop PC, keep this document open on a phone or tablet. For every single slide, you will find:
1. **The Executive Pitch:** The 30-second core message you should deliver upfront.
2. **De-Mystified Concepts & Deep Dive:** A clear, intuitive breakdown of all the technical concepts, physics, and mathematics mentioned on that slide.
3. **Glossary of Terms & Abbreviations:** Every single acronym and formula explained in plain English.
4. **Talking Points & Script:** Conversational phrases you can say word-for-word.
5. **Anticipated Questions & Bulletproof Answers:** The toughest questions leaders might ask and how to answer them with confidence.

---

## Global Acronym & Terminology Quick Reference

| Acronym / Term | Full Name | Plain English Explanation |
| :--- | :--- | :--- |
| **ADAS** | Advanced Driver Assistance Systems | Electronic safety systems (like collision warnings, emergency braking, and blind-spot detection) assisting vehicle drivers. |
| **6-DOF** | 6 Degrees of Freedom | Complete 3D spatial position ($X, Y, Z$) and rotational orientation (Pitch, Yaw, Roll). |
| **$[\mathbf{R} \mid \mathbf{T}]$** | Extrinsic Matrix | The 3D mathematical transformation mapping points from the Radar's coordinate frame into the Camera's coordinate frame. $\mathbf{R}$ is a $3 \times 3$ rotation matrix, and $\mathbf{T}$ is a $3 \times 1$ translation vector ($[\Delta X, \Delta Y, \Delta Z]^T$). |
| **$\mathbf{K}$ Matrix** | Camera Intrinsic Matrix | A $3 \times 3$ matrix containing the camera's internal optical properties: focal lengths ($f_x, f_y$) and principal optical center ($c_x, c_y$). |
| **Doppler / Range-Rate** | Doppler Effect / Velocity | The physical change in radar wave frequency caused by relative motion, directly measuring target closing or receding speed in m/s. |
| **RCS** | Radar Cross Section | A measure of how detectable an object is by radar (in $\text{m}^2$ or $\text{dBsm}$). Metal trucks have huge RCS; fiberglass scooters or pedestrians have tiny RCS. |
| **IMU** | Inertial Measurement Unit | An electronic sensor combining accelerometers (measuring linear forces) and gyroscopes (measuring angular rotation rates). |
| **ASIC** | Application-Specific Integrated Circuit | A specialized microchip dedicated to a single function (e.g. ST LSM6DSL sensor hub). |
| **Quaternions** | 4D Rotation Coordinates $(x,y,z,w)$ | A mathematical way to represent 3D orientation without suffering from "gimbal lock" (where two rotational axes align and lock up). |
| **MCAP** | Modular Common Architecture Parser | An open-source, high-performance binary container format designed for robotics and autonomous vehicle telemetry (Foxglove standard). |
| **Protobuf** | Protocol Buffers | Google's compact, fast, language-neutral binary serialization standard. |
| **`/tf`** | Transform Frame (ROS/Foxglove standard) | The coordinate frame tree in robotics software that defines how the vehicle, camera, radar, and world relate to one another in 3D. |
| **GOP** | Group of Pictures | The sequence of frames in H.264 video. A "GOP 30" with IDR keyframes means a brand-new complete standalone image appears every 30 frames (exactly 1.0 second at 30 FPS). |
| **IDR Keyframe** | Instantaneous Decoder Refresh | A special video keyframe that allows a video player to seek/scrub cleanly without needing to decode any prior frames. |
| **SSE** | Server-Sent Events | A lightweight HTTP standard where a server pushes continuous real-time updates to a browser over a single persistent connection. |
| **RTK-DGPS** | Real-Time Kinematic Differential GPS | Expensive satellite positioning setup ($20\text{L}–35\text{L}$ per vehicle) using base stations to achieve sub-centimeter GPS accuracy. |
| **CLEAR MOT** | Classification of Events, Activities & Relationships Multi-Object Tracking | The global academic/industrial benchmark standard for scoring tracking algorithms: **MOTA** (Tracking Accuracy) and **MOTP** (Precision). |
| **CFAR** | Constant False Alarm Rate | Radar algorithm that dynamically sets detection threshold power above background noise to avoid false ghost detections. |
| **EKF / UKF** | Extended / Unscented Kalman Filter | Mathematical estimation algorithms that fuse noisy sensor measurements over time to track an obstacle's true position and velocity. |

---

## Slide 01: Title Slide (Executive Sprint Briefing)

```
[ ROADSENSE: WEEK 2 PROGRESS ]
Spatial Fusion, 100 Hz Kinematics & High-Throughput MCAP Perception Cockpit
Confidential • Bajaj Auto Ltd. • Advanced Perception & Telemetry
```

### 1. The Executive Pitch
> "Good morning, everyone. In Week 1, we established RoadSense as a reliable, four-sensor mobile data logger. Over the past two weeks, we made a massive leap: we transformed RoadSense from a simple logging utility into a unified, on-device spatial perception and automated validation platform. Today, we are excited to walk you through our operational 6-DOF spatial calibration, our 100 Hz kinematics subsystem, our zero-cost desktop visualizer running at over 11,500 FPS, and our upcoming Phase 3 machine-learning validation pipeline."

### 2. De-Mystified Concepts & Deep Dive
* **What is "Sprint 2 Technical Handover"?**  
  Sprint 1 (Week 1) focused on device stability: 3.125 Mbps UART serial driver, Camera2 1080p60 HAL, CANedge2 Wi-Fi ingestion, and nanosecond monotonic clock sync (`elapsedRealtimeNanos`). Sprint 2 (Week 2) focused on *sensor fusion, spatial projection, vehicle dynamics, desktop tooling, and system hardening*.
* **Why "Android 14 (API 34)"?**  
  API 34 introduces strict background execution limits and high-frequency sensor restrictions. RoadSense is engineered to run at maximum priority (`Process.THREAD_PRIORITY_URGENT_AUDIO`) with zero OS throttling.
* **Why highlight "100% JVM Tests Passing (24/24)"?**  
  Every mathematical matrix, coordinate transform, and binary parsing rule is backed by automated unit tests that run during every Gradle build. There are zero code regressions in master.

### 3. Glossary & Terms on This Slide
* **API 34:** Android 14 operating system level.
* **JVM Tests:** Java Virtual Machine unit tests that execute locally in seconds without needing a physical phone connected.
* **Kinematics:** The study of vehicle motion—specifically acceleration, braking forces, and chassis lean/yaw.

### 4. Speaker Script
> "Before we dive into the details, note that every algorithm we demonstrate today is verified by 24 automated unit tests, and all five major hardware quirks discovered during testing have been permanently resolved. Let's look at the executive summary of our deliverables."

---

## Slide 02: Sprint Executive Summary (From Logger to Perception Platform)

```
[ EXECUTIVE OVERVIEW ]
Week 2 Sprint Deliverables: From Logger to Perception Platform
Consolidating on-device calibration, high-rate kinematics, and desktop analytics
```

### 1. The Executive Pitch
> "This slide outlines our three foundational breakthroughs for Week 2. First, on-device spatial fusion: we can now calibrate the radar and camera in 60 seconds without laser rigs, and render RViz-style radar targets live on the phone screen. Second, high-rate dynamics: we integrated the smartphone's internal 100 Hz IMU to measure gravity-free braking and cornering forces. Third, desktop toolchain: we created a local web dashboard and a zero-cost MCAP archiving pipeline that processes data at over 11,500 frames per second with 0% GPU load."

### 2. De-Mystified Concepts & Deep Dive
* **Breakthrough 1: Spatial Fusion:**  
  Previously, radar targets were displayed on a separate 2D radar scope, leaving the driver to guess which radar dot matched which physical car on the road. Now, using 3D mathematics, radar detections are projected directly onto the live camera image.
* **Breakthrough 2: 100 Hz Dynamics:**  
  ADAS systems cannot rely solely on radar; they need to know what the host vehicle itself is doing. If you hit the brakes or turn the handlebars, the radar targets move relative to you. Our 100 Hz IMU measures vehicle pitch, roll, and yaw so the system knows whether motion comes from the lead car or our own vehicle.
* **Breakthrough 3: Desktop MCAP Pipeline:**  
  In the past, viewing multi-sensor data required complicated Python scripts and separate video players. We unified everything into Foxglove MCAP—a single container format used by modern autonomous vehicle companies—allowing instant playback of 3D radar clouds and video.

### 3. Glossary & Terms on This Slide
* **Reverse Touch Solver:** A closed-form mathematical formula that calculates camera mounting pitch and yaw angles instantly from a single screen tap.
* **RViz:** The standard 3D perception visualizer used in the Robot Operating System (ROS). Our "hybrid lollipop" design is directly inspired by RViz.
* **Range Rings:** Arcs drawn on the road at fixed distances (10m, 30m, 60m, 120m) that warp with camera perspective.
* **Game Rotation Vector:** An Android sensor stream that provides a 3D orientation quaternion using only the accelerometer and gyroscope—ignoring the magnetic compass so vehicle engine wiring and battery EMF don't cause heading jumps.

### 4. Anticipated Q&A
* **Q:** *Why did you say "From Logger to Perception Platform"?*  
  * **A:** "A logger merely dumps sensor bytes to disk for someone else to inspect later. A perception platform actively interprets the physical geometry between sensors in real time, understands where objects exist in 3D space, and correlates radar points with visual targets live on the vehicle."

---

## Slide 03: 6-DOF Spatial Calibration & Reverse Touch Solver

```
[ SPATIAL FUSION ]
60-Second In-Situ Extrinsic Calibration Engine
Solving mounting pitch and yaw in closed form (O(1)) without calibration rigs
```

### 1. The Executive Pitch
> "To fuse radar and camera data, you must know their exact relative physical positions in 6 degrees of freedom. Traditionally, calibrating cameras requires surveyed checkerboard calibration targets, alignment lasers, and expensive garage rigs. In RoadSense, we derived a closed-form Reverse Touch Solver. A technician simply mounts the phone on the vehicle, points it at a car at a known radar distance, taps the car on the screen, and our closed-form math solves the exact pitch and yaw angles in $O(1)$ time—in under 60 seconds."

### 2. De-Mystified Concepts & Deep Dive: How the Coordinate Systems Are Aligned

#### A. The Coordinate Frame Discrepancy Problem
A mmWave radar and a smartphone camera "see" the world in completely different coordinate systems:

```
    RADAR COORDINATE FRAME {R}                     CAMERA OPTICAL FRAME {C}
      (ISO 8855 / SAE Standard)                     (OpenCV / ROS / Camera2)

               +Z (Up)                                     +Z (Forward / Depth into scene)
                ▲                                           ▲
                │   +Y (Forward / Travel)                  /
                │   ▲                                     /
                │  /                                     /
                │ /                                     └─────────► +X (Right across image)
                └─────────► +X (Right)                  │
                                                        │
                                                        ▼ +Y (Down toward road)
```

1. **Radar Frame $\{R\}$ (Front Bumper Center):**
   * Origin: Electrical phase center of the TI AWR1843 antenna array.
   * $+X_R$: Lateral right across the vehicle width.
   * $+Y_R$: **Forward** along the vehicle's longitudinal direction of travel.
   * $+Z_R$: **Upward** perpendicular to the road.
   * *Output:* Spherical coordinates $(r, \theta, \phi)$ converted on-chip to Cartesian:  
     $X_R = r \sin\theta \cos\phi, \quad Y_R = r \cos\theta \cos\phi, \quad Z_R = r \sin\phi$.

2. **Camera Optical Frame $\{C\}$ (Windshield / Handlebar Mount):**
   * Origin: Focal point of the smartphone camera lens.
   * $+X_C$: Lateral right across the image sensor.
   * $+Y_C$: **Downward** toward the ground (matching image vertical pixel row index $v$).
   * $+Z_C$: **Forward** along the optical bore-sight axis (metric depth into the scene).

3. **Image Pixel Space $\{I\}$ (2D Viewfinder):**
   * Coordinate pair $(u, v)$ in pixels, with $(0,0)$ at the top-left corner of the preview.

---

#### B. The Complete 3-Step Mathematical Alignment Pipeline

To project any 3D radar reflection $\mathbf{P}_R = [X_R, Y_R, Z_R]^T$ onto its exact screen pixel $(u, v)$, the engine executes a deterministic 3-step pipeline:

```
[ Radar Point P_R ] ──► (Step 1: Translate T) ──► (Step 2: Axis Remap M & Rotate R) ──► [ Camera Point P_C ] ──► (Step 3: Intrinsic K) ──► [ Screen Pixel (u, v) ]
```

##### Step 1: Metric Translation ($\mathbf{T}$)
The physical displacement between the radar and the phone is measured once during vehicle mounting:
$$\mathbf{T} = \begin{bmatrix} \Delta X \\ \Delta Y \\ \Delta Z \end{bmatrix} = \begin{bmatrix} \text{Lateral offset (e.g. +0.04 m right of centerline)} \\ \text{Longitudinal setback (e.g. +0.18 m behind radar)} \\ \text{Vertical height difference (e.g. +0.45 m above radar)} \end{bmatrix}$$
We subtract the translation baseline from the radar point:
$$\mathbf{P}' = \mathbf{P}_R - \mathbf{T} = \begin{bmatrix} X_R - \Delta X \\ Y_R - \Delta Y \\ Z_R - \Delta Z \end{bmatrix}$$

##### Step 2: Axis Re-mapping ($\mathbf{M}_{\text{axis}}$) & 3D Rotation Matrix ($\mathbf{R}$)
Because Radar $+Y$ is forward while Camera $+Z$ is forward, and Radar $+Z$ is up while Camera $+Y$ is down, we apply the axis reconciliation matrix:
$$\mathbf{M}_{\text{axis}} = \begin{bmatrix} 1 & 0 & 0 \\ 0 & 0 & -1 \\ 0 & 1 & 0 \end{bmatrix}$$
This aligns the coordinate axes:
$$X_{\text{aligned}} = X_R - \Delta X, \quad Y_{\text{aligned}} = -(Z_R - \Delta Z), \quad Z_{\text{aligned}} = Y_R - \Delta Y$$

Next, we rotate by the mounting orientation angles: Pitch ($\theta$), Yaw ($\psi$), and Roll ($\phi$):
$$\mathbf{R} = \mathbf{R}_x(\theta) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{R}_z(\phi)$$
Where $\mathbf{R}_x(\theta)$ compensates for windshield tilt/rake, $\mathbf{R}_y(\psi)$ for horizontal panning, and $\mathbf{R}_z(\phi)$ for sideways cant. The resulting 3D coordinate in the camera frame is:
$$\mathbf{P}_C = \begin{bmatrix} X_C \\ Y_C \\ Z_C \end{bmatrix} = \mathbf{R} \cdot \mathbf{M}_{\text{axis}} \cdot (\mathbf{P}_R - \mathbf{T})$$

##### Step 3: Pinhole Perspective Projection ($\mathbf{K}$)
The Camera Intrinsic Matrix $\mathbf{K}$ is queried automatically from the Android Camera2 HAL (`CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`):
$$\mathbf{K} = \begin{bmatrix} f_x & 0 & c_x \\ 0 & f_y & c_y \\ 0 & 0 & 1 \end{bmatrix}$$
Where $f_x, f_y$ are focal lengths in pixels, and $c_x, c_y$ is the optical principal center.

The 3D point $\mathbf{P}_C = [X_C, Y_C, Z_C]^T$ projects onto the 2D image plane via perspective division:
$$\begin{bmatrix} u \cdot Z_C \\ v \cdot Z_C \\ Z_C \end{bmatrix} = \mathbf{K} \cdot \mathbf{P}_C \implies u = f_x \frac{X_C}{Z_C} + c_x, \quad v = f_y \frac{Y_C}{Z_C} + c_y$$

* **Culling Invariants:**
  * If $Z_C \le 0$, the target is behind the camera lens $\implies$ drop projection.
  * If $u < 0$ or $u > \text{Width}$ or $v < 0$ or $v > \text{Height}$, target is outside the camera FOV $\implies$ clip projection.

---

#### C. The Reverse Touch Solver: Solving Angles in Under 60 Seconds ($O(1)$)
Traditional camera calibration requires multi-hour checkerboard runs. How do we solve Pitch ($\theta$) and Yaw ($\psi$) on the vehicle in under 60 seconds?

1. **Known Anchor:** A lead vehicle or trihedral reflector is detected by the radar at distance $R$ (e.g. $15.0\text{ m}$).
2. **Single Viewfinder Tap:** The technician taps the visual center of that vehicle on the smartphone screen, capturing pixel coordinates $(u_{\text{tap}}, v_{\text{tap}})$.
3. **Inverting Pinhole Projection:** We compute the normalized optical ray vector:
   $$u_{\text{norm}} = \frac{u_{\text{tap}} - c_x}{f_x}, \quad v_{\text{norm}} = \frac{v_{\text{tap}} - c_y}{f_y}$$
4. **Closed-Form Angular Extraction:**  
   Because the target lies along this ray at range $R$, the mounting angles are recovered analytically without iterative optimization:
   $$\text{pitch}_{\text{solved}} = -\arctan(v_{\text{norm}}), \quad \text{yaw}_{\text{solved}} = \arctan(u_{\text{norm}})$$
5. **Real-Time Storage:** These angles are saved to `/sdcard/.../calibration/radar_camera_calib.json`. The entire computation executes in $<1\text{ millisecond}$ ($O(1)$ constant time).

---

### 3. How to Answer "How Calibration Works & How We Aligned the Sensors" Live in the Meeting

#### 🎯 The 30-Second Executive Answer:
> "We align the radar and smartphone camera using a classic 6-DOF rigid extrinsic transform $[\mathbf{R} \mid \mathbf{T}]$ and the camera's intrinsic pinhole matrix $\mathbf{K}$.  
> The physical baseline offsets—how far back, how high, and how far off-center the phone is mounted relative to the radar—are measured once with a tape measure.  
> The phone's mounting tilt angles (pitch and yaw) are solved in under 60 seconds using our closed-form Reverse Touch Solver: a technician points the car at a parked vehicle 10 meters away, taps the car on the phone screen, and our inverse trigonometry calculates the exact mounting pitch and yaw angles in $O(1)$ constant time (under 10 microseconds).  
> The resulting calibration profile is permanently saved as a JSON file and baked into every recorded session timeline."

---

#### 💡 The Intuitive Analogy: "The Laser Pointer & The Sniper Scope"
To explain this to anyone without a math or robotics background:
* **The Radar is a Laser Rangefinder on the Bumper/Bonnet:**  
  It shoots invisible radio waves straight ahead. It doesn't see colors or shapes, but it knows with centimeter precision: *"There is a solid metal object exactly 10.0 meters ahead along the centerline of our vehicle."*
* **The Smartphone is a Camera on the Windshield:**  
  It sits 30 cm behind the radar and 50 cm higher up on the windshield. It captures a rich 2D color image, but by itself, a camera doesn't know how far away things are.
* **The Calibration Challenge:**  
  If the radar detects an obstacle at 10 meters, where should we draw the red detection box on the phone's camera screen so it lands *directly* on top of the physical car?
* **Why Traditional Methods Fail Here:**  
  Traditional automotive camera calibration requires parking the car inside a specialized bay, setting up giant black-and-white checkerboard boards or optical calibration rigs on tripods, and running slow non-linear optimization algorithms (bundle adjustment). This takes hours and cannot be done on a test track or roadside.
* **The RoadSense Solution (Reverse Touch Solver):**  
  Because the radar *already* knows the exact distance to the lead vehicle (e.g. 10 meters), all the phone needs to know is: *"Which way is my lens pointing?"* When the technician taps the lead car on the screen, that touch defines a single straight line of sight through the lens. Using high-school trigonometry ($\arctan$), the phone solves its own tilt angle in a single step—instantly, with zero trial-and-error.

---

#### 📊 The Exact Constants in Our Vehicle Setup (`IRVM Bonnet Mount`)

In our real test vehicle, the calibration parameters are stored in `CalibrationParameters.kt` and serialized to `/sdcard/.../calibration/radar_camera_calib.json`. Here is every single constant, its exact value, and its physical significance:

| Constant Name (Code / Math) | Exact Value in RoadSense | Units | Physical Meaning & Why It Exists | What Happens If It's Wrong? |
| :--- | :---: | :---: | :--- | :--- |
| **`lateralOffsetM` ($\Delta X$)** | `0.00` | meters | **Lateral offset** between radar and camera along the vehicle width axis. Both sensors are mounted dead-center along the vehicle's longitudinal midline. | If offset is set wrong, radar boxes will be shifted left or right of the physical cars. |
| **`setbackM` ($\Delta Y$)** | `0.30` (30 cm) | meters | **Longitudinal setback** from the front radar antenna array to the phone camera lens behind the windshield. | If ignored, an object 10 m from the radar would be assumed 10 m from the camera, causing an optical parallax depth error of 30 cm. |
| **`heightOffsetM` ($\Delta Z$)** | `0.50` (50 cm) | meters | **Vertical elevation difference** ($\text{Phone Height} - \text{Radar Height}$). The phone lens is 50 cm higher than the bumper/bonnet radar. | If ignored, projected radar boxes float 50 cm above or below the real car. |
| **`radarHeightM` ($Z_{\text{radar}}$)** | `0.95` (95 cm) | meters | **Radar mounting height** above the road surface. Total phone height above ground is $0.95 + 0.50 = 1.45\text{ m}$. | Used to project ground features, perspective range rings, and ground footprints. |
| **`targetDistanceM` ($D$)** | `10.0` | meters | **Known distance to calibration anchor.** Distance to the lead target car parked straight ahead during calibration. | Provides the depth anchor that locks the inverse trigonometric equation. |
| **`targetWidthM` ($W$)** | `1.30` | meters | **Physical width of target vehicle.** Width of the calibration car (e.g. compact hatchback). | Scales the synthetic HUD target caliper box to match the car's visual boundaries. |
| **`targetHeightM` ($H$)** | `0.00` | meters | **Target feature elevation relative to radar plane.** $0.0\text{ m}$ corresponds to bumper/bonnet level. | Ensures radar reflections align with the vehicle's structural radar cross-section (RCS). |
| **`pitchDeg` ($\theta$)** | `+7.0` | degrees | **Mounting pitch angle (tilt).** The phone camera is tilted downward by $7.0^\circ$ toward the road surface. | Determined by the rake angle of the windshield glass and phone mount arm. |
| **`yawDeg` ($\psi$)** | `-1.0` | degrees | **Mounting yaw angle (pan).** The phone camera is panned $1.0^\circ$ left to align with the road boresight. | Corrects for slight left/right misalignment in the suction cup or clamp mount. |
| **`rollDeg` ($\phi$)** | `0.0` | degrees | **Mounting roll angle (cant).** Phone is clamped horizontally level with the vehicle's transverse axis. | Windshield clamp is leveled; small residual rolls are absorbed by camera HFOV. |
| **`imageWidth` ($W_{\text{px}}$)** | `1920` | pixels | **Camera viewfinder width.** Horizontal resolution of the live preview buffer. | Establishes the pixel coordinate space $[0 .. 1920]$. |
| **`imageHeight` ($H_{\text{px}}$)** | `1080` | pixels | **Camera viewfinder height.** Vertical resolution of the live preview buffer. | Establishes the pixel coordinate space $[0 .. 1080]$. |
| **`cx`, `cy`** | `960.0`, `540.0` | pixels | **Optical principal center.** The point on the image sensor where the camera's optical axis pierces the screen. | Exactly at $(W/2, H/2)$ for a centered pinhole camera model. |
| **`fx`, `fy`** | `~1423.2` | pixels | **Camera focal lengths in pixels.** Queried directly from Android `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`. | Dictates magnification: relates metric angles to screen pixels based on $\approx 68^\circ$ horizontal FOV ($f_x = \frac{1920}{2 \tan(34^\circ)}$). |

---

#### 🔬 The Deep Mathematical Breakdown: Every Equation De-Mystified

##### Step 1: Metric Lever-Arm Translation ($\mathbf{T}$)
$$\mathbf{P}' = \mathbf{P}_R - \mathbf{T} = \begin{bmatrix} X_R - \Delta X \\ Y_R - \Delta Y \\ Z_R - \Delta Z \end{bmatrix}$$
* **What it means:** Takes a 3D radar point $\mathbf{P}_R = [X_R, Y_R, Z_R]^T$ measured from the radar's antenna, and translates it so the coordinate origin is shifted to the phone's camera lens.
* **In our setup:**
  $$\begin{bmatrix} X' \\ Y' \\ Z' \end{bmatrix} = \begin{bmatrix} X_R - 0.00\text{ m} \\ Y_R - 0.30\text{ m} \\ 0.00\text{ m} - 0.50\text{ m} \end{bmatrix} = \begin{bmatrix} X_R \\ Y_R - 0.30 \\ -0.50 \end{bmatrix}$$
* **Why it matters:** An object 10.0 meters ahead of the bumper is $10.0 - 0.30 = 9.70\text{ m}$ ahead of the phone lens, and sits $0.50\text{ m}$ below the phone lens.

##### Step 2: Axis Permutation Matrix ($\mathbf{M}_{\text{axis}}$)
$$\mathbf{M}_{\text{axis}} = \begin{bmatrix} 1 & 0 & 0 \\ 0 & 0 & -1 \\ 0 & 1 & 0 \end{bmatrix} \implies \begin{bmatrix} X_C \\ Y_C \\ Z_C \end{bmatrix} = \begin{bmatrix} X' \\ -Z' \\ Y' \end{bmatrix}$$
* **Why this matrix is required:** Automotive radars and smartphone cameras adhere to completely different international standards:
  * **Automotive ISO Standard (Radar):** $+X$ is Right, $+Y$ is Forward along travel, $+Z$ is Upward.
  * **Computer Vision OpenCV/ROS Standard (Camera):** $+X$ is Right across image, $+Y$ is Downward toward the ground, $+Z$ is Depth into the scene.
* **The mapping consequence:**
  1. $X_C = X'$: Horizontal lateral right stays horizontal lateral right.
  2. $Y_C = -Z'$: In automotive coordinates, higher objects have positive $+Z$. But on computer screens, pixel row $v$ counts **downward** from the top. Thus, height must be inverted: $-(-0.50\text{m}) = +0.50\text{m}$ (below optical center).
  3. $Z_C = Y'$: Forward distance on the road ($+Y$ in radar) becomes optical depth into the lens ($+Z$ in camera).

##### Step 3: Extrinsic 3D Rotation ($\mathbf{R}(\theta, \psi, \phi)$)
$$\mathbf{P}_C = \mathbf{R}_z(\phi) \cdot \mathbf{R}_x(\theta) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{M}_{\text{axis}} \cdot (\mathbf{P}_R - \mathbf{T})$$
* **What it means:** Accounts for the fact that the phone is not mounted perfectly vertical and straight.
* **$\mathbf{R}_x(\theta)$ (Pitch):** Windshields slope backward, so the phone camera tilts downward by $\theta = +7.0^\circ$ to look at the road.
* **$\mathbf{R}_y(\psi)$ (Yaw):** Corrects any slight horizontal angle if the phone is angled toward the driver by $\psi = -1.0^\circ$.
* **$\mathbf{R}_z(\phi)$ (Roll):** Corrects any sideways tilt (held at $0.0^\circ$).

##### Step 4: Pinhole Perspective Projection ($\mathbf{K}$)
$$\begin{bmatrix} u \cdot Z_C \\ v \cdot Z_C \\ Z_C \end{bmatrix} = \begin{bmatrix} f_x & 0 & c_x \\ 0 & f_y & c_y \\ 0 & 0 & 1 \end{bmatrix} \begin{bmatrix} X_C \\ Y_C \\ Z_C \end{bmatrix} \implies u = f_x \frac{X_C}{Z_C} + c_x, \quad v = f_y \frac{Y_C}{Z_C} + c_y$$
* **What it means:** Converts 3D metric coordinates $(X_C, Y_C, Z_C)$ in meters into 2D viewfinder pixels $(u, v)$.
* **Why perspective division by $Z_C$ is essential:** This is the core physics of human vision and camera optics. An object that is 5 meters away looks twice as big as an object 10 meters away. Dividing by forward depth $Z_C$ scales distant objects down and pulls them toward the optical center $(c_x, c_y)$.

---

#### ⚡ Why the Reverse Touch Solver Solves Angles in $O(1)$ Time (<10 Microseconds)

Traditional calibration tools use **iterative non-linear optimizers** (e.g. Levenberg-Marquardt) because they don't know the 3D position of what the camera is seeing—they must guess, compare pixel reprojection errors across 30 photos, compute Jacobians, and iterate until convergence.

**How RoadSense eliminates iterations completely:**
1. **Radar Provides the Ground Truth Anchor:**  
   The radar detects the calibration car parked straight ahead at known distance $D = 10.0\text{ m}$ ($Z_C = 10.0 - 0.30 = 9.70\text{ m}$).
2. **Technician Taps the Viewfinder:**  
   The technician taps the center of the car on the screen at pixel coordinates $(u_{\text{tap}}, v_{\text{tap}})$.
3. **Inverting the Pinhole Ray:**  
   We instantly compute the normalized optical ray angles:
   $$u_{\text{norm}} = \frac{u_{\text{tap}} - c_x}{f_x}, \quad v_{\text{norm}} = \frac{v_{\text{tap}} - c_y}{f_y}$$
   * $u_{\text{norm}}$ is the horizontal angle tangent (how far left/right of center the car appears).
   * $v_{\text{norm}}$ is the vertical angle tangent (how far above/below center the car appears).
4. **Direct Analytical Angle Extraction:**  
   Because the vehicle is parked on the vehicle centerline ($X=0$), any horizontal shift in the image is caused *entirely* by the phone's yaw angle $\psi$:
   $$\text{yaw } \psi = \arctan(u_{\text{norm}})$$
   And because the car is at ground/bonnet level ($Z=0$), any vertical shift in the image is caused *entirely* by the phone's pitch angle $\theta$ (plus the known height offset $\Delta Z$):
   $$\text{pitch } \theta = -\arctan(v_{\text{norm}}) - \arctan\left(\frac{-\Delta Z}{D - \Delta Y}\right)$$
5. **Computational Complexity $O(1)$:**  
   There are no `for` loops, no matrices being inverted, and no numerical approximations. It executes 2 divisions and 2 arctangent calls:
   $$\text{Execution Time} \approx 6.4 \text{ microseconds}$$
   This allows an in-situ calibration routine that takes under 60 seconds on the test track with zero garage equipment.

---

### 4. Glossary & Terms on This Slide
* **6-DOF Rigid Transform:** Mathematical description of an object's position (3 translations: $X, Y, Z$) and orientation (3 rotations: pitch, yaw, roll) assuming the physical structure does not bend.
* **Intrinsics ($\mathbf{K}$):** Internal lens characteristics (focal length and sensor optical center) that convert 3D optical rays into 2D screen pixels.
* **Extrinsics ($[\mathbf{R} \mid \mathbf{T}]$):** External physical spatial relationship between two separate sensors (mounting displacement and tilt angles).
* **Reverse Touch Solver:** Closed-form $O(1)$ algorithm developed in RoadSense that solves camera mounting pitch and yaw from a single screen tap.
* **$O(1)$ Complexity:** Takes constant time (microseconds) regardless of input size, requiring no loops or numerical approximations.

---

## Slide 04: Real-Time Viewfinder Radar Overlays (Hybrid Lollipops & Range Rings)

```
[ PERCEPTION VISUALIZATION ]
RViz-Inspired Hybrid Radar Lollipops & Perspective Range Rings
Overcoming 3D-to-2D depth ambiguity with ground footprints and Painter's sorting
```

### 1. The Executive Pitch
> "If you project raw 3D radar points onto a 2D camera preview, you just see floating dots. Human drivers and test engineers cannot tell if a floating dot is a vehicle 10 meters away or an overhead bridge sign 80 meters away. We solved this 3D-to-2D depth ambiguity by introducing RViz-inspired Hybrid Radar Lollipops. Each detection features a 12-segment road contact footprint, a vertical projection stem, and Painter's depth-sorting, supplemented by perspective range rings."

### 2. De-Mystified Concepts & Deep Dive
* **Why Floating Dots Fail (Depth Ambiguity):**  
  In 2D perspective projection, an object high in the air close to you projects to the exact same screen pixel as a tall object far away. By anchoring every radar point to the ground plane ($Z = 0$), the human eye immediately perceives distance.
* **Anatomy of a Hybrid Radar Lollipop:**
  1. **Road Contact Footprint:** A 12-segment perspective ellipse drawn flat on the road surface directly beneath the vehicle.
  2. **Vertical Perspective Stem:** A semi-transparent vertical line rising from the ground ellipse up to the target's physical reflection height.
  3. **Centroid Sphere:** A colored dot at the top of the stem indicating Doppler velocity (Red = approaching vehicle, Green = moving away).
* **Painter's Algorithm Depth-Sorting:**  
  If a car at 10 meters is in front of a truck at 80 meters, you must draw the truck *first* and the close car *on top*. If you draw them in random order, distant objects will overlap and occlude foreground objects. In Kotlin, we enforce:
  ```kotlin
  lollipops.sortByDescending { it.depthM }
  ```
  This guarantees that 120-meter targets are drawn first, and 10-meter targets are painted cleanly on top!
* **Perspective Range Rings:**  
  Concentric arcs drawn on the road plane at 10m, 30m, 60m, and 120m. They warp according to camera pitch and perspective, and are clamped to the camera's Horizontal Field of View (HFOV) so they don't draw outside the screen.

### 3. Glossary & Terms on This Slide
* **HFOV:** Horizontal Field of View—the angular width seen by the camera lens (typically $\sim 70^\circ$).
* **Painter's Algorithm:** A computer graphics technique where scenes are rendered from furthest object to nearest object, mimicking how an artist paints a background before painting the foreground.
* **Radial Falloff:** Dynamic scaling where distant target markers automatically shrink in size and become slightly more transparent, preventing distant clusters from blocking the view.

---

## Slide 05: High-Rate 100 Hz IMU Subsystem & Kinematics

```
[ VEHICLE DYNAMICS ]
100 Hz LSM6DSL IMU Integration & Real-Time Jitter Profiler
Capturing vehicle chassis dynamics, pitch/roll, and magnetic-immune orientation
```

### 1. The Executive Pitch
> "Vehicle perception cannot exist in a vacuum; the vehicle is constantly accelerating, braking, pitching over speed bumps, and leaning into turns. In Week 2, we integrated the internal ST LSM6DSL 6-axis motion sensor hub running at 100 Hz on a dedicated thread. We capture gravity-subtracted linear acceleration, magnetic-immune 6-DOF orientation quaternions, and run a real-time jitter profiler guaranteeing sub-2 millisecond latency across full drive cycles."

### 2. De-Mystified Concepts & Deep Dive
* **Why 100 Hz?**  
  Radar updates at 20 Hz (every 50 ms) and Camera updates at 30–60 FPS (every 16–33 ms). Vehicle chassis vibration and sharp potholes occur on sub-15 ms timescales. Sampling the IMU at 100 Hz (~10 ms interval) guarantees we never miss a dynamic braking onset or lean-angle spike.
* **Stream 1: Calibrated Accel & Gyro (Raw Dynamics):**  
  Measures total acceleration ($m/s^2$) and angular rotation rate ($rad/s$) across $X, Y, Z$. Captures raw chassis rumble and motorcycle lean rate.
* **Stream 2: Linear Acceleration (Gravity Removed):**  
  Earth's gravity constantly exerts $9.81\text{ m/s}^2$ downward. If a smartphone is tilted forward on the windshield, gravity leaks into the forward acceleration axis, creating a false reading. Android's internal sensor fusion uses an onboard Kalman filter to mathematically remove the gravity vector, isolating *pure* vehicle acceleration and braking force.
* **Stream 3: 6-DOF Game Rotation Vector (No Compass):**  
  Standard phone orientation relies on a magnetometer (compass) to find Magnetic North. On an electric or hybrid vehicle, huge current surges through the inverter and motor cables create massive magnetic fields that make compass headings spin wildly. The **Game Rotation Vector** uses only the gyroscope and accelerometer, making it 100% immune to electromagnetic interference (EMI).
* **The Jitter Profiler:**  
  Measures the exact arrival time between consecutive IMU frames ($\Delta t$). Nominal is $10.0\text{ ms}$. If an operating system stutter delays a frame beyond $12.0\text{ ms}$ ($>2\text{ ms}$ jitter), it is flagged in `app_system.log`. In testing, our standard deviation is $\sigma = 0.4\text{ ms}$—well within automotive safety tolerances.

### 3. Glossary & Terms on This Slide
* **LSM6DSL:** High-performance, ultra-low-power 3D accelerometer and 3D gyroscope microchip manufactured by STMicroelectronics.
* **SENSOR_DELAY_FASTEST:** The highest possible acquisition rate supported by the Android Linux kernel HAL.
* **HandlerThread:** A dedicated background thread in Android with its own event loop, ensuring high-rate sensor readings never hitch the UI.

---

## Slide 06: Desktop Toolchain (Zero-Dependency Web Dashboard & ADB Automation)

```
[ DESKTOP TOOLCHAIN ]
Zero-Dependency Local Web Dashboard & ADB Automation
Complete test-session management, live telemetry gauges, and 1-click execution
```

### 1. The Executive Pitch
> "To operate RoadSense effectively in a workshop or test facility, engineers need a zero-friction way to offload data and run conversion tools without dealing with complex command-line arguments. In Week 2, we developed a local Web Dashboard running on `http://localhost:8088`. It requires zero external pip packages—running purely on Python 3 standard library—features real-time Server-Sent Events progress streaming, monitors device battery and thermals, and launches Foxglove in one click."

### 2. De-Mystified Concepts & Deep Dive
* **Why "Zero External Python Packages"?**  
  Many engineering laptops have strict IT permissions or broken virtual environments. By building `roadsense_web_server.py` using Python's built-in `http.server.ThreadingHTTPServer`, it runs instantly out-of-the-box on any computer with standard Python 3.10+ installed—no `pip install flask` or dependency conflicts required.
* **Server-Sent Events (SSE) via `/api/stream`:**  
  Traditionally, web interfaces use aggressive polling (requesting status 10 times a second), which wastes CPU and floods console logs. SSE opens a single persistent HTTP connection where the server pushes real-time text streams (bytes transferred, percentage complete, conversion FPS) as they happen.
* **ADB Device Health Monitor:**  
  While the phone is connected via USB, a background thread continuously queries Android Debug Bridge (ADB) to display live status pills:
  * Phone connection state (e.g. `Connected (M21)`)
  * Battery percentage and battery temperature (alerts if $>40^\circ\text{C}$)
  * Available phone storage space (GB free)
* **Visual Showcase Layout:**  
  The right side of the slide provides an authentic vector mockup of the dashboard header and a designated visual slot for test engineers to paste a live screen capture of `http://localhost:8088` during presentations.

### 3. Glossary & Terms on This Slide
* **ADB:** Android Debug Bridge—the standard command-line tool that lets a PC communicate with an Android smartphone over USB.
* **Port 8088:** The local network port on the PC where the web server listens (`http://127.0.0.1:8088`).

---

## Slide 07: Data Architecture (High-Throughput Foxglove MCAP Pipeline >11,500 FPS)

```
[ DATA ARCHITECTURE ]
High-Throughput Foxglove MCAP Pipeline (>11,500 FPS)
Mathematical coordinate alignment eliminating GPU transcoding overhead
```

### 1. The Executive Pitch
> "Foxglove Studio is our primary 3D visualization and playback environment. However, smartphones mounted in landscape record video with an internal 180-degree rotation tag. Initially, fixing this required full video transcoding, which took 30 seconds per minute of video on expensive GPUs. We solved this with a mathematical breakthrough: we read the MP4 orientation matrix in $O(1)$ time and fold the 180 degrees directly into the `/tf` camera frame roll. This unlocked Zero-Cost Video Demuxing at over 11,500 FPS—converting a 1-minute drive log in 3.4 seconds with 0% GPU load."

### 2. De-Mystified Concepts & Deep Dive
* **The Orientation Problem:**  
  When an Android smartphone records in landscape, the hardware image sensor is physically mounted upside-down relative to standard portrait orientation. Android writes an orientation matrix tag (`tkhd` atom) into the MP4 file saying "rotate $180^\circ$". 2D video players respect this tag, but 3D robotics visualizers (like Foxglove or RViz) ignore video metadata and project camera frustums based purely on 3D coordinate frames (`/tf`). This caused camera feeds and 3D radar clouds to render upside-down.
* **The Zero-Cost Mathematical Solution:**  
  Instead of decompressing and re-compressing millions of video pixels with FFmpeg, `session_to_mcap.py` leaves the H.264 video bitstream completely untouched (pass-through copy). Instead, it inspects the MP4 header in $O(1)$ time and mathematically rotates the 3D coordinate frame:
  $$\text{roll}_{\text{tf}} = 180.0^\circ, \quad \text{pitch}_{\text{tf}} = -\text{pitch}_{\text{mounting}}, \quad \text{yaw}_{\text{tf}} = -\text{yaw}_{\text{mounting}}$$
  In Foxglove 3D space, the camera frame is flipped upright instantly.
* **The Benchmark Matrix Explained:**
  * **Zero-Cost Demux (Default):** **$>11,500\text{ FPS}$** (converts a 1-minute 60 FPS video in **$\sim 3.4\text{ seconds}$**). Consumes **0% GPU** and minimal CPU. 100% bit-lossless copy.
  * **Hardware NVENC Transcode (`--flip-video`):** $\sim 650\text{ FPS}$ ($\sim 32\text{ seconds}$ per minute). Uses NVIDIA GPU hardware blocks. Enforces closed GOP 30 and IDR keyframes.
  * **CPU Software Transcode:** $\sim 85\text{ FPS}$ ($\sim 240\text{ seconds}$ per minute). 100% CPU saturation.
* **Embedded Cockpit Layout:**  
  Every `.mcap` container automatically attaches `RoadSense_Cockpit_Layout.json`. When an engineer double-clicks the file in Foxglove, the 3D scene, radar point cloud, upright camera feed, and timeline are pre-arranged with zero setup.

### 3. Glossary & Terms on This Slide
* **Demuxing:** De-multiplexing—extracting raw video frames from an MP4 file without decoding or re-encoding them.
* **NVENC:** NVIDIA's dedicated hardware video encoding SIP (Silicon Intellectual Property) chip.
* **Bit-Lossless:** The exact compressed video bytes from the phone are preserved with zero compression artifacts or generation loss.

---

## Slide 08: System Hardening (Root Cause Analysis: Bugs #11 Through #15 Resolved)

```
[ SYSTEM HARDENING ]
Root Cause Analysis: Bugs #11 Through #15 Resolved
Overcoming hardware quirks, codec bottlenecks, and scene-graph memory leaks
```

### 1. The Executive Pitch
> "Real-world automotive engineering requires confronting edge-case bugs and hardware silicon quirks. During Week 2, we conducted five deep root-cause post-mortems for Bugs #11 through #15. We eliminated UART radar stride aliasing, Exynos ISP camera deadlocks, Foxglove scene-graph ghost tracks, video timeline scrub freezes, and optical orientation mismatch. All five fixes are verified in production."

### 2. De-Mystified Concepts & Deep Dive
* **Bug #11: 28-Byte Stride Aliasing in Radar Parser**
  * *Symptom:* In dense traffic with 50+ radar reflections, the serial parser would desynchronize and report corrupted, chaotic coordinates.
  * *Root Cause:* TI mmWave firmware docs stated that point cloud structs are 32-byte aligned. However, the side-info descriptor (SNR and noise) was packed into 28-byte strides.
  * *Fix:* Implemented dynamic TLV header validation and explicit 28-byte stride offsets. Tested across $>100,000$ continuous frames without a single desync.
* **Bug #12: Exynos Dual-Stream HAL Stalling on AUX Camera**
  * *Symptom:* The app froze or suffered Application Not Responding (ANR) crashes when initializing preview and photometric analysis streams on Samsung Exynos chipsets.
  * *Root Cause:* Exynos hardware Image Signal Processors (ISPs) have strict concurrent hardware stream limits; allocating an extra `ImageReader` starved preview buffers.
  * *Fix:* Autonomous Road AE now grabs downsampled $32 \times 24$ bitmaps directly from the active `TextureView` on `Dispatchers.Default`—allocating zero additional hardware HAL streams.
* **Bug #13: Foxglove Scene-Graph Ghost Tracks**
  * *Symptom:* In Foxglove Studio, old radar bounding boxes never disappeared. Over a 5-minute drive, hundreds of red ghost boxes accumulated on the screen.
  * *Root Cause:* Each frame generated a dynamic UUID for targets. Foxglove treats unique IDs as new entities that persist forever unless explicitly deleted.
  * *Fix:* Standardized on static entity ID `"radar_tracks"` in `foxglove.SceneUpdate`, atomically replacing all tracks every frame.
* **Bug #14: Video Seeking Freeze (Closed GOP 30 & IDR)**
  * *Symptom:* Scrubbing the timeline backward in Foxglove caused video playback to freeze indefinitely.
  * *Root Cause:* Android hardware encoders emit variable keyframe intervals with open GOPs. Scrubbing backward meant the decoder had no valid IDR keyframe to reference.
  * *Fix:* Enforced strict 1-second closed GOPs (`-g 30`, `-forced-idr 1`) and in-band SPS/PPS headers in the transcode pipeline.
* **Bug #15: Optical Frame Inversion**
  * *Fix:* Automatic MP4 orientation detection and 180-degree `/tf` camera frame roll fold (detailed in Slide 07).

### 3. Glossary & Terms on This Slide
* **TLV:** Type-Length-Value—the binary packet structure used by TI mmWave radars to stream point clouds and target lists over serial.
* **ANR:** Application Not Responding—Android's fatal freeze dialog when the main UI thread is blocked for $>5$ seconds.
* **SPS/PPS:** Sequence Parameter Set / Picture Parameter Set—essential H.264 video configuration headers required for a decoder to initialize playback mid-stream.

---

## Slide 09: Quality Engineering (Quantitative Benchmarks & Automated Test Coverage)

```
[ QUALITY ENGINEERING ]
Quantitative Benchmarks & Automated Test Coverage
Subsystem throughput, latency, memory consumption, and unit test invariants
```

### 1. The Executive Pitch
> "Slide 9 presents our quantitative verification scorecard. Every subsystem operates well within our real-time latency and throughput targets: Radar runs at 20 Hz over 3.125 Mbps UART with zero dropped frames; Camera2 records 1080p at 30 FPS; IMU kinematics stream at 100 Hz with sub-2 millisecond jitter; and our MCAP pipeline converts data at 11,500 FPS. In addition, our automated test suite passes 24 out of 24 unit tests cleanly."

### 2. De-Mystified Concepts & Deep Dive
* **The 5-Column Scorecard Explained:**
  1. **TI mmWave Radar:** Operating at **`3,125,000 baud` (3.125 Mbps)**. Latency is $<1.2\text{ ms}$ thanks to our dedicated high-priority thread (`Process.THREAD_PRIORITY_URGENT_AUDIO`) and 64 KB direct ring buffer. Dropped frames: **0% (100% capture)**.
  2. **Camera2 Video Engine:** 30.0 FPS 1080p H.264 at $\sim 20\text{ Mbps}$. Hardware MediaCodec encoder uses $<8\%$ CPU load. Dual-zone Road AE dynamically adjusts exposure in 250 ms.
  3. **LSM6DSL Kinematics:** 100.0 Hz nominal rate. Standard deviation of inter-frame jitter is $\sigma = 0.4\text{ ms}$, with maximum jitter $<1.8\text{ ms}$. CPU load is $<1\%$.
  4. **CANedge2 Engine:** 1-minute split MF4 handling with 1.5s backoff cooldown to protect single-connection ESP32 MCUs (Bug #10 fix). 2-folder FIFO pool staging.
  5. **MCAP Toolchain:** $>11,500\text{ FPS}$ zero-cost pass-through conversion. Full Foxglove Studio schema compliance.
  6. **Automated Unit Tests:** 24 test fixtures covering TLV parsing, coordinate transforms, matrix multiplications, and Reverse Touch Solver angle recovery. Zero failures.

### 3. Glossary & Terms on This Slide
* **Ring Buffer:** A circular memory buffer where new data overwrites oldest data only if full; used to prevent memory allocations from triggering garbage collection stutters.
* **MediaCodec:** Android's low-level hardware abstraction layer for dedicated video encoding chips (Snapdragon / Exynos / MediaTek).
* **FIFO:** First-In, First-Out—cache management strategy where oldest session files are pruned first to keep internal phone storage clean.

---

## Slide 10: Strategic Direction • Phase 3 (Automated ML Validation & Multi-Modal Sensor Fusion)

```
[ STRATEGIC DIRECTION • PHASE 3 ]
Automated ML Validation & Multi-Modal Sensor Fusion Engine
Correlating camera ground truth with radar point clouds to train superior perception models
```

### 1. The Executive Pitch
> "In Week 1's roadmap, we outlined three phases: Phase 1 Calibration, Phase 2 Live Overlay, and Phase 3 ML-Based Validation. Having delivered Phases 1 and 2, Phase 3 is our grand strategic frontier. Traditional automotive validation requires ₹20L to ₹35L RTK-DGPS beacons on a single target car. We are building an automated reference laboratory: we use the calibrated camera to generate optical 3D ground truth for all surrounding vehicles, correlate radar point clouds to filter noise and clutter, mine cross-modal discrepancies into 5-second triage clips for human active learning, and train a superior multimodal fusion model that surpasses camera-only limits."

### 2. De-Mystified Concepts & Deep Dive

#### Pillar 1: Optical Ground Truth & Road Boundary Segmentation
* **How Camera Ground Truth Works:**  
  We run state-of-the-art vision models (YOLOv8x / YOLOv11) on recorded 1080p drive video. The model detects:
  1. **Dynamic Obstacles:** Cars, motorcycles, auto-rickshaws, buses, and pedestrians, extracting tight 2D bounding boxes and their bottom road-contact patches.
  2. **Static Road Boundaries:** Curbs, lane lines, guardrails, trees, signboards, and drivable corridor vs. roadside clutter.
* **Inverse Pinhole Raycasting ($Z_{\text{road}} = 0$):**  
  Because we know the camera height $Z_C$ and mounting pitch/yaw from calibration, we can cast a mathematical ray from the camera optical center through the bottom of the bounding box down to the ground plane ($Z = 0$). This calculates the vehicle's exact metric 3D position $(X_C, Y_C, Z_C)$ in meters!
* **Economic Advantage:**  
  An RTK-DGPS beacon costs **₹35,00,000 ($40,000)** and only tracks *one* instrumented car on a private track. Our optical ground-truth engine tracks **every car, auto-rickshaw, and motorcycle on public Indian roads** at zero incremental hardware cost!
* **Output:** `vision_gt.json`, latched with nanosecond timestamps to `session_timeline.csv`.

#### Pillar 2: Spatial Point-Cloud Correlation & Anomaly Mining
* **Noise vs. Vehicle Discrimination:**  
  Radars produce hundreds of reflections per chirp. Which reflections belong to the car in front, and which are noise? Using our 6-DOF transform $[\mathbf{R} \mid \mathbf{T}]$ and $\mathbf{K}$, we project radar points into camera pixel space:
  * Points landing inside vehicle bounding boxes are classified as **Vehicle Reflections**.
  * Points landing on guardrails and curbs are classified as **Static Clutter**.
  * Points landing in empty sky or empty road are classified as **Multipath Noise or False Alarms**.
* **Self-Supervised Disagreement Rules (Mining the 2% Edge Cases):**  
  98% of driving is uneventful. We don't want engineers watching 100 hours of video. The engine flags moments where sensors disagree:
  * **Rule 1: Phantom Radar Ghost:** Radar reports a high-speed approaching obstacle at 20m, but camera sees an empty road (multipath ground bounce under metal bridge).
  * **Rule 2: Low-RCS Radar Blindness:** Camera clearly sees a plastic scooter or pedestrian at 15m, but radar produces zero points (radar blind spot).
* **Human-in-the-Loop Active Learning:**  
  Whenever Rule 1 or 2 triggers, the system automatically slices a **5-second video clip** (`.mp4`) and generates an entry in `anomalies.json`. A human engineer reviews these isolated clips in 1 click on the Web Dashboard to confirm the truth.

#### Pillar 3: Superior Fused Multimodal Model (Camera + Radar)
* **Why Camera-Only Fails:**
  * **Environmental Limits:** Blinding sun glare, heavy monsoon rain, thick fog, unlit night highways.
  * **Pitch Sensitivity:** At $>60\text{m}$, a tiny $0.5^\circ$ vehicle pitch error causes an optical distance error of **$\pm 18\text{ meters}$**!
* **Why Radar-Only Fails:**  
  Lacks semantic classification (cannot tell a cardboard box from a child), sparse point clouds, and suffers from multipath ghost targets.
* **The Fused Multimodal Model:**  
  By training a neural model on both camera features and radar Doppler/range point clouds, the model combines the strengths of both:
  * Radar provides instantaneous, infallible metric distance and Doppler velocity.
  * Camera provides dense semantic classification and lateral boundary precision.
  * The resulting model operates reliably in rain and night, eliminates pitch sensitivity, and generates automated CLEAR MOT (MOTA, MOTP) validation scorecards.

### 3. Glossary & Terms on This Slide
* **Flat-Earth Assumption ($Z_{\text{road}} = 0$):** Inverted raycasting formulation assuming the vehicle travels on a locally planar road surface.
* **Active Learning:** A machine learning strategy where the algorithm automatically identifies ambiguous or difficult data points (discrepancies) and asks a human to label only those points, saving thousands of manual labeling hours.
* **CLEAR MOT (MOTA & MOTP):** Multiple Object Tracking Accuracy (penalizes misses, false positives, and ID switches) and Multiple Object Tracking Precision (measures metric distance error).

---

## Slide 11: Project Handover & Roadmap (Milestone Handover: Phases 1 & 2 Complete, Phase 3 Launch)

```
[ PROJECT HANDOVER & ROADMAP ]
Milestone Handover: Phases 1 & 2 Complete, Phase 3 Launch
Consolidating on-device perception breakthroughs and launching the validation pipeline
```

### 1. The Executive Pitch
> "To close our handover, we have delivered 100% of our Sprint 2 deliverables: Phase 1 (6-DOF Calibration) and Phase 2 (Live Viewfinder Overlays) are production-ready alongside 100 Hz kinematics and high-throughput MCAP conversion. For Sprint 3, our immediate action plan launches Phase 3: deploying our offline 3D raycaster, building the radar point-cloud correlator, and activating 1-click active learning triage on our web dashboard."

### 2. De-Mystified Concepts & Deep Dive
* **Left Card: Sprint 2 Deliverables (100% Delivered):**
  * **Phase 1 Complete:** Reverse Touch Solver solves pitch/yaw in $<60$s without calibration rigs.
  * **Phase 2 Complete:** RViz hybrid lollipops, vertical stems, Painter's sorting, and range rings render live at 30 FPS.
  * **100 Hz Dynamics:** LSM6DSL linear acceleration and 6-DOF quaternions with sub-2ms jitter.
  * **Desktop Toolchain:** Port 8088 web dashboard with live SSE streaming and $>11,500\text{ FPS}$ MCAP demuxing.
  * **Hardening:** Bugs #11 to #15 resolved and 24/24 JVM tests clean.
* **Right Card: Phase 3 Immediate Sprint Action Plan:**
  1. **Deploy Inverted Raycaster:** Stand up Python script `extract_vision_gt.py` running YOLOv8x to generate `vision_gt.json`.
  2. **Radar Point-Cloud Correlator:** Build spatial association script matching radar points to optical bounding boxes and separating vehicle returns from clutter.
  3. **Discrepancy Mining Engine:** Automate Rules 1 and 2 to slice 5-second triage clips of ghost tracks and radar blindness.
  4. **Web Dashboard Triage UI:** Add an active learning tab to `http://localhost:8088` so engineers can approve mined edge cases with one click.
  5. **Train Multimodal Model:** Train the fused radar-camera perception network to establish our automated validation baseline.

### 3. Closing Talking Points
> "In summary, RoadSense is no longer just capturing data—it is transforming into an autonomous reference laboratory that eliminates manual validation bottlenecks and equips Bajaj Auto with world-class ADAS perception capabilities. Thank you, and we look forward to your questions and feedback."

---

## Emergency Cheat Sheet for Tough Q&A

### Q1: *"Can a smartphone camera really provide accurate ground truth without an RTK-DGPS beacon?"*
* **Answer:** "Yes, within its calibrated operational envelope (0 to 50 meters). Because the pinhole camera geometry $\mathbf{K}$ and mounting height $Z_C$ are rigidly known, inverse raycasting to the road plane ($Z=0$) yields metric position accuracy within $\pm 0.5\text{ meters}$ at $30\text{m}$. Furthermore, we do not treat the camera as infallible; Pillar 2 uses CAN wheel speed and Doppler clutter invariance to verify vehicle velocity independently, and Pillar 5 flags discrepancies whenever radar and camera disagree."

### Q2: *"Why did you choose Foxglove MCAP instead of ROS bags or custom JSON files?"*
* **Answer:** "MCAP is the modern robotics industry standard. Unlike ROS bags, MCAP requires zero ROS installation, supports self-contained Protobuf schemas, allows arbitrary file attachments (we embed our cockpit layout directly in the file), and enables instant zero-cost H.264 video demuxing at 11,500 FPS without transcoding overhead."

### Q3: *"How does the Reverse Touch Solver handle vehicle suspension bounce or tyre pressure changes?"*
* **Answer:** "Suspension settling changes mounting height by a few millimeters, which introduces negligible angular error ($<0.05^\circ$). If vehicle loading significantly changes pitch, our calibration takes under 60 seconds to re-snap on the test track. In Phase 3, the 100 Hz IMU dynamically tracks pitch changes during driving to compensate for dynamic brake-dive and acceleration squat."

### Q4: *"What is the main bottleneck for Phase 3?"*
* **Answer:** "The primary task is deploying YOLOv8x/v11 inference across multi-hour drive runs on the host workstation and fine-tuning the Mahalanobis association gate between radar clusters and optical bounding boxes. Our desktop toolchain and 100x SIL digital-twin replay harness are already architected to handle this pipeline."

### Q5: *"How exactly did you align the camera and radar coordinate systems?"*
* **30-Second Executive Pitch:**
  > "Radar and camera speak completely different spatial languages: Radar uses the vehicle SAE standard where $+X$ is Right, $+Y$ is Forward, and $+Z$ is Up. Camera optical coordinates use the OpenCV/computer vision convention where $+X$ is Right, $+Y$ is Down, and $+Z$ is Optical Depth into the scene.
  > We align them through a 3-step geometric pipeline:
  > 1. We translate radar coordinates by physical sensor offset $\mathbf{T} = [\Delta X, \Delta Y, \Delta Z]^T$.
  > 2. We apply a canonical coordinate permutation matrix $\mathbf{M}_{\text{axis}}$ and a 3D Euler rotation matrix $\mathbf{R}(\theta, \psi, \phi)$ to reconcile axes and mounting tilt.
  > 3. We project metric 3D camera coordinates onto 2D image pixels using the camera's intrinsic matrix $\mathbf{K}$ queried directly from Android's Camera2 HAL.
  > To find the exact orientation angles without expensive optical calibration rigs, our **Reverse Touch Solver** lets an engineer tap a physical vehicle on screen whose radar distance is known, recovering pitch and yaw in closed form in under 60 seconds."

* **Deep Technical & Mathematical Breakdown (for Perception Engineers):**
  1. **Coordinate Frame Conventions:**
     * **Radar Frame $\{R\}$:** $+X_R = \text{Right}, +Y_R = \text{Forward (boresight)}, +Z_R = \text{Up}$.
     * **Camera Optical Frame $\{C\}$:** $+X_C = \text{Right}, +Y_C = \text{Down}, +Z_C = \text{Optical Depth (Forward)}$.
  2. **Translation & Axis Transformation:**
     A point $\mathbf{P}_R = [X_R, Y_R, Z_R]^T$ is translated by physical lever-arm offset $\mathbf{T}$ and rotated by Euler angles (pitch $\theta$, yaw $\psi$, roll $\phi$):
     $$\mathbf{P}_{\text{vehicle}} = \mathbf{R}_{\text{extrinsic}} (\mathbf{P}_R - \mathbf{T})$$
     To align the vehicle/radar axes into camera optical frame $\{C\}$, we apply the permutation matrix $\mathbf{M}_{\text{axis}}$:
     $$\mathbf{M}_{\text{axis}} = \begin{bmatrix} 1 & 0 & 0 \\ 0 & 0 & -1 \\ 0 & 1 & 0 \end{bmatrix}$$
     which maps: $X_C = X_{\text{veh}}$, $Y_C = -Z_{\text{veh}}$ (up becomes down), $Z_C = Y_{\text{veh}}$ (forward becomes depth).
  3. **Pinhole Perspective Projection:**
     $$\begin{bmatrix} u \cdot w \\ v \cdot w \\ w \end{bmatrix} = \mathbf{K} \cdot \mathbf{P}_C = \begin{bmatrix} f_x & 0 & c_x \\ 0 & f_y & c_y \\ 0 & 0 & 1 \end{bmatrix} \begin{bmatrix} X_C \\ Y_C \\ Z_C \end{bmatrix}$$
     $$u = \frac{f_x \cdot X_C}{Z_C} + c_x, \quad v = \frac{f_y \cdot Y_C}{Z_C} + c_y$$
     where $[f_x, f_y, c_x, c_y]$ are retrieved programmatically from Android's `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`.
  4. **Closed-Form Reverse Touch Solver ($O(1)$ Calibration):**
     Instead of iterative non-linear optimization or checkerboards, the user taps a vehicle bounding centroid $(u_{\text{tap}}, v_{\text{tap}})$ at radar distance $R$:
     $$u_{\text{norm}} = \frac{u_{\text{tap}} - c_x}{f_x}, \quad v_{\text{norm}} = \frac{v_{\text{tap}} - c_y}{f_y}$$
     $$\text{pitch } \theta = -\arctan(v_{\text{norm}}), \quad \text{yaw } \psi = \arctan(u_{\text{norm}})$$
     This guarantees instant, repeatable calibration convergence right on the test track.

