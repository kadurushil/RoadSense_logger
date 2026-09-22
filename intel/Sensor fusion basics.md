# RoadSense — Sensor Fusion Basics & Radar-Camera Spatial Calibration Guide

> **Document Name:** `Sensor fusion basics.md`  
> **Location:** `intel/Sensor fusion basics.md`  
> **Purpose:** Technical foundation, mathematical formulation, and interactive calibration UX proposals for fusing mmWave radar point clouds and hardware tracks with live smartphone camera video in RoadSense.  
> **Target Audience:** Automotive Engineers, Computer Vision Developers, and System Architects  
> **Date:** September 2026  

---

## 1. Executive Summary & Problem Formulation

### 1.1 The Multimodal Sensor Fusion Advantage
Automotive perception relies on complementary physical sensing modalities:
* **TI AWR1843 mmWave Radar (77 GHz FMCW):**
  * *Strengths:* Direct, microsecond-accurate metric range ($Y$), radial Doppler velocity ($V_{\text{doppler}}$), operational immunity to fog, dust, rain, direct sunlight glare, and night pitch-black darkness.
  * *Weaknesses:* Sparse point clouds, coarse angular/lateral resolution, multipath reflections, lack of semantic classification (cannot distinguish a cardboard box from a fallen pedestrian).
* **Smartphone Camera (Camera2 / VideoCapture):**
  * *Strengths:* Dense 2D spatial context, rich color/texture, clear lane lines, semantic object recognition (cars, two-wheelers, pedestrians, traffic signs).
  * *Weaknesses:* Vulnerable to environmental lighting/glare, no direct depth measurement, monocular depth estimation is computationally heavy and noisy.

**The Goal:** Project the radar's metric 3D point cloud and tracked obstacles directly onto the camera's 2D preview viewfinder in real time.

```
                   ┌───────────────────────────────────────┐
                   │  Camera Viewfinder (2D Pixel Pixels)  │
                   │  (u, v) in screen coordinates         │
                   └──────────────────▲────────────────────┘
                                      │
                                      │ Perspective Projection
                                      │ K · [ R | T ]
                                      │
                   ┌──────────────────┴────────────────────┐
                   │    TI AWR1843 mmWave Radar (3D Space) │
                   │    (X_radar, Y_radar, Z_radar)        │
                   └───────────────────────────────────────┘
```

### 1.2 The Calibration Dilemma in Field Testing
Traditional automotive camera-radar calibration requires:
1. A specialized indoor calibration hall.
2. High-precision laser rangefinders and optical checkerboards / AprilTags.
3. Rigid trihedral radar corner reflectors placed at surveyed 3D coordinates.

**The Reality in RoadSense:**
* The radar sensor is **rigidly fixed to the vehicle** (front bumper or grille bracket, aligned with vehicle heading).
* The camera is a **smartphone mounted in a windshield or dashboard clamp**.
* Every time a tester mounts the phone, the mount position varies slightly in tilt, height, and rotation.
* **Objective:** Create a fast, intuitive, manual calibration method that allows a tester to align the radar overlay to real-world objects in under 60 seconds directly on the phone screen without external lab equipment.

---

## 2. Coordinate Systems & Mathematical Projection Model

### 2.1 Radar Coordinate Frame ($\mathcal{F}_{\text{radar}}$)
Following the standard vehicle ISO reference frame used in `RadarFrame.kt`:
* $+X_r$: Lateral axis (meters, positive pointing to the right of the vehicle)
* $+Y_r$: Longitudinal axis (meters, positive pointing forward along vehicle heading)
* $+Z_r$: Elevation axis (meters, positive pointing upward)
* Origin ($O_r$): Center of the radar antenna array on the front bumper

### 2.2 Camera Coordinate Frame ($\mathcal{F}_{\text{camera}}$)
Standard optical pinhole camera frame:
* $+X_c$: Points toward the right of the camera sensor
* $+Y_c$: Points downward toward the bottom of the phone screen
* $+Z_c$: Optical axis (pointing forward through the lens into the scene)
* Origin ($O_c$): Camera optical center

```
        Radar Frame (ISO)                          Camera Optical Frame
             +Y (Forward)                               +Z_c (Forward)
                 ▲                                          ▲
                 │                                         /
                 │                                        /
       -X ◄──────┼──────► +X (Right)                     ┌───────► +X_c (Right)
                 │                                       │
                 │                                       │
                 ▼ -Y                                    ▼ +Y_c (Down)
             (+Z = Up)
```

### 2.3 Intrinsic Parameters Matrix ($K$)
The transformation from 3D camera coordinates to 2D normalized screen pixels:
$$K = \begin{bmatrix} f_x & 0 & c_x \\ 0 & f_y & c_y \\ 0 & 0 & 1 \end{bmatrix}$$
* $f_x, f_y$: Focal lengths expressed in pixel units.
* $c_x, c_y$: Principal point (optical center), typically $(W/2, H/2)$.

> **Key RoadSense Advantage:** Intrinsics do not need manual guessing! The Android Camera2 API exposes them directly via `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`, or they can be calculated directly from the sensor active array size and focal length:
> $$f_{\text{pixels}} = \frac{W_{\text{pixels}}}{2 \cdot \tan(\text{HFOV} / 2)}$$

### 2.4 Extrinsic Transformation (Rigid 6-DOF)
To transform a radar point $\mathbf{P}_r = [X_r, Y_r, Z_r]^T$ into camera coordinates $\mathbf{P}_c$:
$$\mathbf{P}_c = \mathbf{R} \cdot \mathbf{P}_r + \mathbf{T}$$

Where:
* **Translation Vector $\mathbf{T} = [\Delta X, \Delta Y, \Delta Z]^T$ (Fixed Physical Hardware Constants):**
  * **Lateral Offset ($\Delta X$):** $\mathbf{0.0\text{ m}}$ (both the bonnet radar and the phone behind the IRVM are centered along the vehicle longitudinal centerline).
  * **Longitudinal Setback ($\Delta Y$):** $\mathbf{\le 0.30\text{ m}}$ (radar is on the rear portion of the bonnet, directly ahead of the windshield/IRVM mount).
  * **Elevation Offset ($\Delta Z$):** $\mathbf{\approx +0.50\text{ m}}$ (phone under IRVM is approximately 50 cm elevated above the bonnet radar plane).
* **Rotation Matrix $\mathbf{R} = \mathbf{R}_x(\theta) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{R}_z(\phi)$ (The Real Variables to Calibrate):**
  * $\theta$ (Pitch): Tilt angle up/down toward the road.
  * $\psi$ (Yaw): Heading angle left/right relative to vehicle boresight.
  * $\phi$ (Roll): Clamp leveling slant in the phone mount.

### 2.5 Screen Projection Equation
For any 3D radar point $\mathbf{P}_r$:
$$s \begin{bmatrix} u \\ v \\ 1 \end{bmatrix} = K \cdot \begin{bmatrix} \mathbf{R} & \mathbf{T} \end{bmatrix} \begin{bmatrix} X_r \\ Y_r \\ Z_r \\ 1 \end{bmatrix}$$

Dividing by the projective depth scale factor $s = Z_c$:
$$u = f_x \frac{X_c}{Z_c} + c_x, \quad v = f_y \frac{Y_c}{Z_c} + c_y$$

---

## 3. The Projector Keystone Analogy

### 3.1 Why the Analogy Fits Perfectly
In digital video projectors, keystone distortion occurs when the projector lens is not perfectly perpendicular to the projection surface. Modern projectors solve this without user math through:
1. **Vertical Keystone:** Adjusts for projector pitch (ceiling vs coffee table tilt).
2. **Horizontal Keystone:** Adjusts for off-axis yaw (projector placed on side table).
3. **Roll / Leveling:** Corrects rotational slant.
4. **4-Corner Warp:** Directly pins the four virtual corners to match the physical boundary.

In RoadSense, the camera screen acts like the projection surface, and the radar detections act like the light rays. By giving the user **keystone-style visual adjustments**, we eliminate the need for manual matrix entry.

### 3.2 Parameter Mapping Table

| Projector Adjustment | RoadSense Physical Parameter | Visual Effect on Camera Screen |
|---|---|---|
| **Vertical Keystone / Tilt** | **Camera Pitch ($\theta$)** | Moves the radar horizon line up or down. Controls elevation alignment of distant objects. |
| **Horizontal Keystone / Skew** | **Camera Yaw ($\psi$)** | Shifts the radar centerline left or right. Aligns boresight with vehicle heading. |
| **Roll / Level** | **Mount Roll ($\phi$)** | Rotates the overlay clockwise or counterclockwise to level with the physical horizon. |
| **Throw Ratio / Zoom** | **Sensor Height ($\Delta Z$) & Setback ($\Delta Y$)** | Expands or compresses the perspective depth ladder ($5\text{m} \to 10\text{m} \to 20\text{m}$ spacing). |
| **Lens Shift (Horizontal)** | **Lateral Offset ($\Delta X$)** | Shifts overlay left/right without rotating perspective. |

---

## 4. Interactive Calibration UX Proposals

### Proposal 1: "Target Pin & Snap" (Recommended Primary Workflow)
*Fastest and most natural for automotive testing.*

```
 ┌─────────────────────────────────────────────────────────────┐
 │ 🔴 LIVE CALIBRATION                          [LOCK & SAVE]  │
 │                                                             │
 │                          Horizon                            │
 │ ───────────────────────────┬─────────────────────────────── │
 │                            │                                │
 │                            │                                │
 │                      ┌─────▼─────┐                          │
 │                      │  Target   │                          │
 │                      │   (Car)   │                          │
 │                      └─────▲─────┘                          │
 │                            │                                │
 │               [DRAG]  ◯────┘ (Radar Reticle: Y=10.2m)       │
 │                                                             │
 │  ◄  ▲  ▼  ►   Pitch: +2.1° | Yaw: -0.8° | Setback: 1.8m     │
 └─────────────────────────────────────────────────────────────┘
```

#### Workflow:
1. **Park or Stage:** Position the vehicle 8–15 meters behind a stationary target (a parked car, traffic cone, or test pedestrian).
2. **Detection:** The TI radar immediately detects the target and streams a cluster/track at e.g. $(X = 0.2\text{m}, Y = 10.2\text{m})$.
3. **Display Floating Reticle:** The camera preview shows a glowing radar reticle with its measured distance tag (`10.2 m`).
4. **Drag to Align:** Because initial camera pitch/yaw may be slightly off, the reticle might appear floating above or beside the actual car. The user **drags the reticle** directly onto the bumper/base of the physical car.
5. **Auto-Solve:** The engine computes the exact $\Delta \theta$ (pitch) and $\Delta \psi$ (yaw) to snap the coordinate ray to that pixel.
6. **Optional 2nd Distance Refinement:** If a second target is available (e.g. at 25m or 5m), pinning both simultaneously locks Pitch, Yaw, and Height scale with high precision.

---

### Proposal 2: "Projector 4-Corner / Ground Trapezoid Grid"
*Best for calibrating in an open parking lot or standard lane.*

```
 ┌─────────────────────────────────────────────────────────────┐
 │ 📐 GROUND LADDER CALIBRATION                 [SAVE CALIB]   │
 │                                                             │
 │               Top-Left (30m)       Top-Right (30m)          │
 │                     ◆─────────────────◆                     │
 │                    /      20m Line     \                    │
 │                   /   ───────────────── \                   │
 │                  /        10m Line       \                  │
 │                 /     ─────────────────   \                 │
 │                /           5m Line         \                │
 │               ◆                             ◆               │
 │          Bottom-Left (2m)              Bottom-Right (2m)    │
 │                                                             │
 │  [Reset Default]  [Invert Skew]   Trapezoid Width: 3.5m     │
 └─────────────────────────────────────────────────────────────┘
```

#### Workflow:
1. When activated, a virtual 3D ground ladder representing a standard 3.5-meter highway lane is drawn on the preview.
2. It features four circular corner draggable handles.
3. The user simply drags the handles until the virtual lane edges align with the visible road lane markings or parking bay lines.
4. **Direct Homography / Extrinsic Computation:** The four corner points define the projective homography matrix $\mathbf{H}_{\text{ground}}$ from road space $(X_r, Y_r, 0)$ to camera pixels $(u, v)$.

---

### Proposal 3: "Parametric Keystone HUD & Nudge Deck"
*Designed for on-the-fly micro-adjustments during live drives.*

```
 ┌─────────────────────────────────────────────────────────────┐
 │ 🎛️ RADAR CALIBRATION HUD                      [X] Close     │
 │                                                             │
 │  PITCH (Horizon)   [ ───●─────────── ]   -1.4°   [▲] [-0.1°]│
 │  YAW (Boresight)   [ ──────●──────── ]   +0.3°   [▼] [+0.1°]│
 │  ROLL (Level)      [ ─────●───────── ]    0.0°   [◄] [-0.1°]│
 │  HEIGHT (Mount)    [ ────────●────── ]   1.25m   [►] [+0.1°]│
 │  SETBACK (Bumper)  [ ──────●──────── ]   1.80m   [+] [-]    │
 │                                                             │
 │  [ PRESET: Windshield Center ]  [ PRESET: Windshield Right ]│
 └─────────────────────────────────────────────────────────────┘
```

#### Features:
* Continuous sliders for fluid adjustment.
* Discrete single-tap nudge buttons ($0.1^\circ$ / $1\text{cm}$) for precision tuning while watching traffic ahead.
* Stored hardware presets for repeatable vehicle mounts.

---

### 4.4 In-Depth Comparative Evaluation: Which Approach is Superior?

With the physical mounting parameters established:
$$\Delta X = 0.0\text{ m}, \quad \Delta Y \le 0.30\text{ m}, \quad \Delta Z \approx +0.50\text{ m}$$
the translation is **essentially deterministic**. The only variables changing each session are **Camera Pitch ($\theta$)** and **Camera Yaw ($\psi$)** (plus minor **Roll $\phi$**).

Here is how the three approaches stack up:

| Evaluation Dimension | Option A: 4-Corner Ground Grid | Option B: Target Pin & Snap | Option C: Keystone HUD & Sliders |
|---|---|---|---|
| **Underlying Math** | Planar 2D Homography ($H_{3\times 3}$, 8 DOF) | Single/Dual Point Ray Intersection | Pinhole Extrinsics ($R_{\text{pitch}}, R_{\text{yaw}}$) |
| **Environmental Requirement** | High: Needs clear, straight painted lane lines or marked grid | Medium: Needs a distinct radar reflector / car in view | **Zero:** Works anywhere (street, garage, highway) |
| **Physical Degeneracy Risk** | **High:** Dragging 4 unconstrained 2D corners can introduce non-physical shearing or perspective warping | **Low:** Directly adjusts viewing ray | **Zero:** Strictly constrained to rigid physical rotation ($\theta, \psi$) |
| **Calibration Speed** | ~45–60 seconds (fiddling with 4 corners) | ~15–20 seconds (drag reticle onto object) | **~5–10 seconds (2 slider taps or nudge arrows)** |
| **Target Reflection Ambiguity** | N/A (uses visual lanes) | **Moderate:** mmWave radar centroid may scatter between bumper, exhaust, and axle | Minimal: User visually observes overlay alignment |
| **Adjustable on the Move?** | No (must be stationary on marked lane) | Difficult while driving | **Yes: Driver/passenger can tap [▲] or [▼] at a traffic stop** |

#### The Architectural Recommendation: Hybrid (Option C with Live Verification)
Instead of forcing a rigid either/or choice, the optimal software design is:
1. **Primary Interface: Option C (Keystone HUD Deck):**
   * Dedicated Pitch ($\theta$) and Yaw ($\psi$) sliders with fine-step nudge buttons (`[▲] [▼] [◄] [►]`).
   * A subtle, toggleable **Horizon Line** and **Boresight Centerline** projected onto the viewfinder.
2. **Visual Verification: Option B in the Background:**
   * Live radar detections (cars ahead, roadside barriers) are rendered in real time.
   * As the user moves the Pitch/Yaw slider, the radar bounding boxes slide interactively. The moment a green radar box snaps over the license plate of the car in front, calibration is verified and locked!
3. **Preset Storage:**
   * Because the phone mount is usually installed in the same position beneath the IRVM, RoadSense saves the calibrated values to `radar_camera_calib.json`. On future app launches, calibration is pre-loaded automatically!

---

## 5. Visual Representation of Live Overlay (After Calibration)

Once calibrated, the live viewfinder can render high-fidelity multimodal overlays:

### 5.1 Radar Point Cloud (TLV Type 1)
* **Visual Form:** Small translucent circular dots projected onto the scene.
* **Doppler Velocity Color Coding:**
  * **Cyan / Blue:** Stationary road furniture (guardrails, parked vehicles, curbs).
  * **Green:** Receding targets (moving away faster than ego vehicle).
  * **Amber / Red:** Approaching targets (closing distance, potential hazards).
* **SNR Transparency:** Higher radar reflection intensity = higher alpha opacity; low-confidence multipath noise = faded.

### 5.2 Hardware Tracked Obstacles (TLV Type 7)
* **Visual Form:** 3D perspective bounding boxes or ground footprints projected around tracked objects.
* **Telemetry Tag:** Floating pill displaying:
  * Unique Track ID (`TID #3`)
  * Metric Distance (`14.2 m`)
  * Relative Velocity (`-32 km/h`)
  * Time-to-Collision (TTC) (`1.6 s` ⚠️)
* **Dynamic Warning:** Flashing yellow/red highlight when TTC $< 2.0\text{ seconds}$.

```
 ┌─────────────────────────────────────────────────────────────┐
 │ Live Viewfinder Preview                      REC 00:04:12   │
 │                                                             │
 │                         ┌──────────┐                        │
 │                         │  CAR     │                        │
 │                         │  18.4 m  │                        │
 │                         │  -12 km/h│                        │
 │                         └────┬─────┘                        │
 │                       ┌──────┴──────┐                       │
 │                       │             │                       │
 │                       │ [3D BOX]    │                       │
 │                       └─────────────┘                       │
 │                                                             │
 │   • (Radar Point)                                           │
 │         • (Point)                                           │
 │                                                             │
 │ Radar: 20 Hz (42 pts, 3 trk) | Camera: 1080p @ 30fps       │
 └─────────────────────────────────────────────────────────────┘
```

---

## 6. RoadSense Software Architecture & Implementation Plan

```
app/src/main/java/com/bajajauto/roadsense/
 ├── fusion/
 │    ├── RadarCameraExtrinsics.kt         <-- Data class: pitch, yaw, roll, dx, dy, dz
 │    ├── RadarCameraProjectionEngine.kt   <-- 3D-to-2D matrix projection & pinhole math
 │    └── CalibrationStorageManager.kt     <-- SharedPreferences / JSON persistence
 ├── ui/
 │    ├── components/
 │    │    ├── RadarCameraOverlayCanvas.kt <-- Jetpack Compose Canvas rendering over TextureView
 │    │    ├── CalibrationReticleLayer.kt  <-- Draggable target handles & road ladder
 │    │    └── KeystoneCalibrationDialog.kt<-- Sliders, D-pad & preset controls
 │    └── screens/
 │         └── CameraDashboardCard.kt      <-- "Calibrate Overlay" toggle on Camera deck
```

### 6.1 Calibration Persistence
Saved into a dedicated JSON file:
`app_logs/radar_camera_calib.json` (and mirrored to `SharedPreferences`):
```json
{
  "camera_id": "0",
  "lens_facing": "BACK",
  "orientation": "LANDSCAPE",
  "intrinsics": {
    "fx": 1420.5,
    "fy": 1420.5,
    "cx": 960.0,
    "cy": 540.0
  },
  "extrinsics": {
    "pitch_deg": 2.15,
    "yaw_deg": -0.80,
    "roll_deg": 0.20,
    "lateral_offset_m": 0.15,
    "longitudinal_setback_m": 1.85,
    "camera_height_m": 1.25
  },
  "calibrated_at": "2026-09-15T10:30:00Z"
}
```

---

## 7. Discussion Agenda for Tomorrow

1. **Target Selection:** Which physical target setup is easiest for regular testing? (e.g. parked test vehicle, traffic cone with corner reflector, or road lane lines?)
2. **Interaction Model:** Do we prioritize the **Target Pin & Snap (Proposal 1)** or the **Keystone Sliders HUD (Proposal 3)** for initial implementation?
3. **UI Placement:** Should the calibration toggle live on the main `CameraDashboardCard` or inside a dedicated `Calibration & Alignment` modal dialog?
4. **Elevation Handling:** Since TI AWR1843BOOST has lower elevation resolution than azimuth, should tracked ground obstacles be anchored to the road plane ($Z_r = 0$) by default?
