# Theoretical Formulation: Automated Radar-Camera Pitch Calibration & Dynamic Stabilization via IMU

> **Document Name:** `RADAR_PITCH_CALIBRATION_VIA_IMU.md`  
> **Workspace Location:** `intel/Implementations/Sensors/RADAR_PITCH_CALIBRATION_VIA_IMU.md`  
> **Subsystem:** Radar-Camera Sensor Fusion & Calibration Engine  
> **Related Implementations:** [`SpatialProjectionEngine.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt), [`ImuManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)  
> **Status:** Architecture & Theoretical Blueprint for Future Implementation  

---

## 1. Problem Statement: Why Pitch is the Most Critical Extrinsic Parameter

In multimodal automotive perception (mmWave Radar + Forward Camera), the 6-DOF extrinsic calibration matrix $[\mathbf{R} \mid \mathbf{T}]$ maps radar detections $\mathbf{P}_r = [X_r, Y_r, Z_r]^T$ into camera coordinates $\mathbf{P}_c = [X_c, Y_c, Z_c]^T$:

$$\mathbf{P}_c = \mathbf{R}_x(\theta) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{R}_z(\phi) \cdot \mathbf{P}_r + \mathbf{T}$$

where $\theta$ is the **pitch angle**, $\psi$ is **yaw**, and $\phi$ is **roll**.

### The Sensitivity of Pitch Error:
Forward-facing cameras exhibit extreme sensitivity to pitch tilt error $\Delta \theta$:
1. **Pixel Projection Displacement:**  
   Given camera focal length $f_y$ (typically $\approx 1600\,\text{pixels}$ on 1080p), the vertical screen displacement $\Delta v$ is:
   $$\Delta v = f_y \cdot \tan(\Delta \theta)$$
   * For just **$\Delta \theta = 1.0^\circ$**, $\Delta v \approx 1600 \cdot \tan(0.01745) \approx \mathbf{28\,\text{pixels}}$!
   * For **$\Delta \theta = 2.5^\circ$**, $\Delta v \approx \mathbf{70\,\text{pixels}}$ vertical shift.
2. **Metric Height Error at Distance:**  
   At a forward target distance $Y = 50\,\text{meters}$, a $1.0^\circ$ pitch misalignment shifts the perceived height of a vehicle by:
   $$\Delta Z = Y \cdot \sin(\Delta \theta) = 50 \cdot \sin(1.0^\circ) \approx \mathbf{0.87\,\text{meters}}$$
   This vertical error causes radar footprints to falsely float into the windshield sky or penetrate down into the asphalt road surface.

---

## 2. Leveraging the Phone IMU for Automated Pitch Calibration

Currently, pitch calibration requires manual keystoning via slider nudges or dragging a reticle onto a parked car bumper. With our new **100 Hz IMU subsystem** (`LSM6DSL` + `Samsung Game Rotation Vector`), we can eliminate manual tuning through **two distinct operational modes**:

```mermaid
graph TD
    subgraph Mode 1: Static One-Tap Auto-Level
        M1[Park Vehicle on Level Ground] --> G1[Query Gravity Vector<br>TYPE_GRAVITY / TYPE_ACCELEROMETER]
        G1 --> P1["Compute Static Tilt Angle θ_mount<br>θ_mount = arctan2(gy, sqrt(gx² + gz²))"]
        P1 --> S1[Set Baseline Pitch in radar_camera_calib.json]
    end

    subgraph Mode 2: Dynamic In-Drive Suspension Stabilization
        M2[Driving: Braking Nose-Dip & Acceleration Squat] --> G2[Query 100 Hz Gyroscope + GameRotationVector]
        G2 --> P2["Compute Real-Time Pitch Delta Δθ(t)<br>Δθ(t) = θ_attitude(t) - θ_baseline"]
        P2 --> S2[Apply Δθ(t) to SpatialProjectionEngine in Real Time]
        S2 --> HUD[Radar Overlays Remain Anchored to Road Surface]
    end
```

---

## 3. Mathematical Formulation

### 3.1 Smartphone Coordinate System vs Vehicle Road Frame
* **Android Sensor Coordinate Frame:**
  * $+X$: Points to the right edge of the screen.
  * $+Y$: Points to the top edge of the screen.
  * $+Z$: Points out of the screen toward the user's face.
* **Landscape Vehicle Mount (RoadSense Standard):**
  * When mounted in landscape in a windshield phone holder with the camera facing forward along the road:
    * The phone's $+X$ axis typically points forward (or $-X$ depending on landscape-left vs landscape-right).
    * The phone's $+Y$ axis points horizontally across the dashboard.
    * The phone's $+Z$ axis points toward the vehicle cabin.

### 3.2 Closed-Form Static Pitch Extraction (Level Ground Calibration)
When the vehicle is stationary on a level surface (e.g., garage or flat road), the true gravity vector is strictly vertical: $\mathbf{g}_{\text{world}} = [0, 0, -9.80665]^T\,\text{m/s}^2$.

The phone's onboard `Sensor.TYPE_GRAVITY` reports $\mathbf{g}_{\text{phone}} = [g_x, g_y, g_z]^T$ in phone body coordinates:
1. **Extract Pitch Inclination Angle ($\theta_{\text{mount}}$):**
   $$\theta_{\text{mount}} = \arctan2\left(g_x, \sqrt{g_y^2 + g_z^2}\right) \quad \text{(Landscape)}$$
   $$\text{or} \quad \theta_{\text{mount}} = \arctan2\left(g_y, \sqrt{g_x^2 + g_z^2}\right) \quad \text{(Portrait)}$$
2. **Noise Reduction via 100 Hz Temporal Averaging:**
   Averaging 100 samples over a 1-second static window yields microsecond-level noise rejection:
   $$\bar{\mathbf{g}} = \frac{1}{N} \sum_{i=1}^{N=100} \mathbf{g}_i, \quad \sigma_g < 0.005\,\text{m/s}^2$$
   This yields an angular accuracy of:
   $$\Delta \theta_{\text{error}} \approx \arcsin\left(\frac{0.005}{9.81}\right) \approx \mathbf{\pm 0.029^\circ}$$
   *An accuracy of $\pm 0.03^\circ$ translates to less than **1 pixel of vertical error** on screen!*

3. **Mechanical Bumper Offset ($\theta_{\text{bumper}}$):**
   If the radar itself is mounted with a small physical bracket tilt $\theta_{\text{bumper}}$ (typically measured once during mechanical installation, e.g. $+0.5^\circ$), the relative extrinsic pitch angle is simply:
   $$\theta_{\text{calib}} = \theta_{\text{mount}} - \theta_{\text{bumper}}$$

---

## 4. Dynamic In-Drive Pitch Compensation (Suspension Dynamics)

Vehicles are not rigid bodies; the chassis continuously pitches on its springs and dampers:
* **Hard Braking:** Weight transfers to the front axle; front suspension compresses $\to$ **nose dips down by $-1.5^\circ \text{ to } -3.5^\circ$**.
* **Hard Acceleration:** Weight transfers to the rear axle $\to$ **nose lifts up by $+1.0^\circ \text{ to } +2.0^\circ$**.
* **Road Crests & Dips:** Speed humps, bridge joints, and pavement elevation changes cause transient vertical pitching.

### The Dynamic Pitch Artifact:
Without dynamic compensation, when the driver hits the brakes, the windshield camera tilts down toward the road. The radar on the bumper also tilts down. However, because static calibration assumes a fixed horizon, **projected radar lollipops temporarily jump upward into the sky**, breaking visual alignment with the vehicle ahead.

### The Solution: Real-Time Dynamic Rotation Matrix Update
Using the 100 Hz `Sensor.TYPE_GAME_ROTATION_VECTOR` (magnetic-immune 6-DOF attitude):
1. **Compute Instantaneous Pitch Delta:**
   $$\Delta \theta(t) = \theta_{\text{attitude}}(t) - \theta_{\text{static\_baseline}}$$
2. **Dynamic Projection Matrix:**
   $$\mathbf{R}_{\text{dynamic}}(t) = \mathbf{R}_x\big(\theta_{\text{baseline}} + \alpha \cdot \Delta \theta(t)\big) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{R}_z(\phi)$$
   where $\alpha \in [0.0, 1.0]$ is a tunable vehicle suspension stiffness coefficient (accounting for the relative compliance between windshield glass and front bumper mount).
3. **Low-Pass Filter / Complementary Filter:**
   To prevent high-frequency engine vibration from jittering the HUD:
   $$\Delta \theta_{\text{filtered}}(t) = (1 - \beta) \cdot \Delta \theta_{\text{filtered}}(t-1) + \beta \cdot \Delta \theta(t), \quad \beta = 0.15$$

---

## 5. Mount Misalignment & Vibration Health Monitoring

Smartphone mounts frequently slip over time due to road potholes, thermal expansion of suction cups, or accidental bumps by the driver.

### Autonomous Misalignment Detection:
1. When the vehicle is stopped at a traffic light (`speedKmh < 1.0` from GNSS):
   - Measure the static gravity angle $\theta_{\text{current}}$.
   - Compare with saved calibration: $\Delta = |\theta_{\text{current}} - \theta_{\text{baseline}}|$.
2. If $\Delta > 1.5^\circ$ persistently across two consecutive stops:
   - Display a non-intrusive warning badge in the Cockpit:  
     `⚠️ Windshield Mount Shifted (+2.1°) — [Auto Re-align]`
   - Tapping **[Auto Re-align]** recalculates the baseline pitch in $0.1\,\text{seconds}$ without opening any menus.

---

## 6. Future Implementation Blueprint in RoadSense

### Phase 1: GUI Integration in `CameraCalibrationCard.kt`
Add an **"Auto-Level Pitch from IMU"** button to the existing Calibration Studio:
```text
┌───────────────────────────────────────────────────────────┐
│ 📐 Radar-Camera Extrinsics Calibration                    │
├───────────────────────────────────────────────────────────┤
│ Pitch (Horizon Tilt):  +4.2°   [-0.1°]  [+0.1°]  [Zero]   │
│                                                           │
│  [ ⚡ Auto-Level Pitch from IMU (Gravity: -4.21°) ]       │
│  "Park car on level ground and tap to snap pitch to zero" │
└───────────────────────────────────────────────────────────┘
```

### Phase 2: Engine Wiring in `SpatialProjectionEngine.kt`
```kotlin
fun autoLevelPitchFromGravity(gravitySample: ImuSample): Float {
    // Landscape coordinate extraction:
    val gx = gravitySample.x
    val gy = gravitySample.y
    val gz = gravitySample.z
    val pitchAngleDeg = Math.toDegrees(kotlin.math.atan2(gx.toDouble(), kotlin.math.sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
    return pitchAngleDeg
}
```

### Phase 3: Dynamic In-Drive Hook in `ViewfinderRadarOverlay.kt`
Feed `imuTelemetry.pitchDeg` into the 20 Hz projection render loop, stabilizing radar footprints during harsh vehicle braking and acceleration.
