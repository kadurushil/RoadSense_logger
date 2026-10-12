# RoadSense IMU Subsystem & 3D Level Ground Orientation Engine

> **Directory:** `intel/Implementations/Sensors/IMU/`  
> **Component Package:** `com.bajajauto.roadsense.imu`, `com.bajajauto.roadsense.ui.components`  
> **Status:** Production Ready / Deployed  
> **Last Updated:** 2026-10-12  

---

## 1. Executive Summary

The RoadSense IMU subsystem provides high-rate (100 Hz), low-jitter motion tracking, gravity-referenced level ground estimation, and real-time 3D attitude visualization directly inside the RoadSense Android application.

Key capabilities introduced:
1. **Interactive 60 FPS 3D Orientation Visualizer:** A pure Jetpack Compose Canvas 3D rendering pipeline that models the physical smartphone in real-time space hovering over a reference level ground plane ($Z = 0$).
2. **6-DOF Gravitationally Leveled Tracking:** Powered by Android's `Sensor.TYPE_GAME_ROTATION_VECTOR` (accelerometer gravity fusion + 100 Hz gyroscope integration without magnetometer distortion from the vehicle steel chassis/engine).
3. **Closed-Form Windshield Mount Kinematics:** Direct closed-form trigonometric formulas for camera optical pitch ($\arcsin(R_8)$) and lateral dashboard roll ($\arcsin(R_7)$) in vehicle Reverse Landscape (`Surface.ROTATION_270`).
4. **Zero-Lag Table Yaw Tracking:** Total elimination of Euler gimbal lock, restoring sub-degree, instant yaw tracking when flat on a table.
5. **Dynamic Road (+Y) Alignment:** Tapping Reset aligns the reference Road (+Y) axis to the camera boresight's horizontal heading, bringing the POV vector directly into the $YZ$ plane.
6. **CAD-Grade Orthogonal Side (Pitch) View:** Instant shortcut button to snap the viewport perpendicular to the $YZ$ plane ($\text{Azimuth} = 90^\circ, \text{Elevation} = 0^\circ$), displaying a pure 2D elevation cross-section of the phone's pitch tilt against the horizontal road.

---

## 2. Directory Index & Documentation Hierarchy

| File | Type | Purpose |
| :--- | :--- | :--- |
| [`plan_imu_game_rotation_3d_orientation_visualizer.md`](plan_imu_game_rotation_3d_orientation_visualizer.md) | **Plan** | Phase 1 design of the 3D Canvas orientation visualizer and `ImuTelemetryState` pipeline. |
| [`plan_fix_imu_yaw_delay_and_reverse_landscape_coordinates.md`](plan_fix_imu_yaw_delay_and_reverse_landscape_coordinates.md) | **Plan** | Mathematical root-cause analysis and derivation for reverse landscape mounting and gimbal lock removal. |
| [`walkthrough_imu_game_rotation_3d_orientation_visualizer.md`](walkthrough_imu_game_rotation_3d_orientation_visualizer.md) | **Walkthrough** | Step-by-step verification, implementation notes, APK compilation, and device validation runbook. |
| [`RADAR_PITCH_CALIBRATION_VIA_IMU.md`](RADAR_PITCH_CALIBRATION_VIA_IMU.md) | **Architecture** | Ground-truth gravity pitch extraction and radar spatial projection alignment formulation. |
| [`imu_sensor_implementation_plan.md`](imu_sensor_implementation_plan.md) | **Plan** | Initial foundation of dedicated background `HandlerThread` ingestion and 25 Hz UI downsampling. |

---

## 3. Mathematical Coordinate System & Reference Frames

### 3.1 The Three Operating Coordinate Frames

```
       [Vehicle Front / Road Direction]
                     ▲
                     │   Camera Optical Boresight (-Z_phone)
             ┌───────┴───────┐
             │  Rear Camera  │  (Back chassis facing road)
   Dashboard │ ┌───────────┐ │
   Left      │ │   Phone   │ │ Dashboard Right
  (-Y_phone) │ │  Screen   │ │ (+Y_phone in ROTATION_270)
             │ └───────────┘ │
             └───────┬───────┘
                     │   Screen Normal (+Z_phone)
                     ▼
         [Vehicle Cabin / Driver]
```

1. **World Ground Frame ($\mathcal{F}_{\text{world}}$):**
   * $Z_{\text{world}}$: Perpendicular to level ground (pointing UP against gravity).
   * $X_{\text{world}}, Y_{\text{world}}$: The horizontal road plane ($Z = 0$).
   * $R = \text{SensorManager.getRotationMatrixFromVector}$ maps phone body vectors to world space: $\mathbf{v}_{\text{world}} = R \cdot \mathbf{v}_{\text{phone}}$.
2. **Phone Body Hardware Frame ($\mathcal{F}_{\text{phone}}$):**
   * $+X_{\text{phone}}$: Screen right edge (portrait).
   * $+Y_{\text{phone}}$: Screen top edge (earpiece).
   * $+Z_{\text{phone}}$: Screen normal (facing out of front glass toward driver).
   * $-Z_{\text{phone}}$: Rear camera optical boresight (pointing out back chassis).
3. **Vehicle Mount in Reverse Landscape (`Surface.ROTATION_270`):**
   * Phone is clamped horizontally with screen facing driver, rotated 90° clockwise.
   * Top of phone is to passenger side $\implies$ **Vehicle Lateral Right is $+Y_{\text{phone}}$**.
   * Bottom of phone is to driver side $\implies$ **Vehicle Lateral Left is $-Y_{\text{phone}}$**.
   * Right edge of screen is pointing down $\implies$ **Vehicle Floor is $+X_{\text{phone}}$** (Roof is $-X_{\text{phone}}$).
   * Rear camera points forward $\implies$ **Vehicle Forward is $-Z_{\text{phone}}$**.

---

### 3.2 Closed-Form Angular Formulations

Given raw 3x3 world rotation matrix $R$:
$$R = \begin{bmatrix} R_0 & R_1 & R_2 \\ R_3 & R_4 & R_5 \\ R_6 & R_7 & R_8 \end{bmatrix}$$

#### 1. Camera Optical Pitch ($\theta_{\text{pitch}}$)
The rear camera optical boresight in world coordinates is:
$$\hat{\mathbf{b}}_{\text{world}} = R \begin{bmatrix} 0 \\ 0 \\ -1 \end{bmatrix} = \begin{bmatrix} -R_2 \\ -R_5 \\ -R_8 \end{bmatrix}$$
The vertical component of this vector is $-R_8$. Positive pitch indicates tilt down toward the road surface:
$$\theta_{\text{pitch}} = \arcsin(R_8) = \text{atan2}(R_8, \sqrt{R_2^2 + R_5^2})$$
* Horizontal boresight: $R_8 = 0 \implies \theta = 0.0^\circ$.
* Tilted $5^\circ$ downward toward road: $R_8 \approx 0.087 \implies \theta = +5.0^\circ$.

#### 2. Lateral Mount Roll ($\phi_{\text{roll}}$)
The lateral dashboard vector pointing to the passenger side:
* In **Reverse Landscape (`Surface.ROTATION_270`)**: $+Y_{\text{phone}} \implies \mathbf{v}_{\text{lat}} = [R_1, R_4, R_7]^T$:
  $$\phi_{\text{roll}} = \arcsin(R_7)$$
  When clamped horizontally level: $R_7 \approx 0.0 \implies \phi_{\text{roll}} \approx 0.0^\circ$.
* In **Standard Landscape (`Surface.ROTATION_90`)**: $-Y_{\text{phone}} \implies \phi_{\text{roll}} = \arcsin(-R_7)$.

#### 3. Instantaneous Yaw Heading ($\psi_{\text{yaw}}$)
* **Table Flat ($|R_8| > 0.7$):** Direct azimuth from `SensorManager.getOrientation(R, orientationAngles)[0]`. Zero gimbal lock, zero overshoot.
* **Windshield Mount ($|R_8| \le 0.7$):** Camera optical boresight heading in the horizontal ground plane:
  $$\psi_{\text{yaw}} = \text{atan2}(-R_2, -R_5)$$

---

## 4. Architectural Innovations & Bug Fixes

### 4.1 Root Cause & Elimination of Yaw Lag on Flat Surfaces
* **Bug:** When rotating the phone flat on a table, yaw exhibited delay, overshooting past the stopping point, and slowly drifting back.
* **Root Cause:** Android's `SensorManager.remapCoordinateSystem(R, axisX, AXIS_MINUS_Z)` was used to map boresight ($-Z$) to $Y$. When the phone lay flat on a table, $-Z$ pointed straight down into the floor (parallel to gravity), forcing the Euler representation into **Pitch $= -90^\circ$ (Exact Gimbal Lock)**. At gimbal lock, the Euler trigonometric denominator approaches zero, leading to non-linear numerical oscillation and lag.
* **Solution:** Removed artificial remapping entirely. When flat ($|R_8| > 0.7$), yaw is sampled directly from the un-remapped rotation matrix $R$, restoring instant sub-degree responsiveness.

### 4.2 Dynamic Road (+Y) Axis Alignment on Reset
* **Feature:** Tapping the Reset button aligns the reference Road (+Y) axis directly with the phone's current camera POV vector.
* **Math:** Captures $\psi_{\text{tare}} = \text{yawDeg}$ and applies a rotation around the vertical world $Z$-axis:
  $$\begin{bmatrix} x' \\ y' \\ z' \end{bmatrix} = \begin{bmatrix} \cos\psi_{\text{tare}} & -\sin\psi_{\text{tare}} & 0 \\ \sin\psi_{\text{tare}} & \cos\psi_{\text{tare}} & 0 \\ 0 & 0 & 1 \end{bmatrix} \begin{bmatrix} x \\ y \\ z \end{bmatrix}$$
  This rotates the horizontal boresight vector so that $b'_x = 0$ and $b'_y = \sqrt{R_2^2 + R_5^2} > 0$. The boresight beam lies **100% inside the $YZ$ plane**, perfectly aligning with the Road (+Y) line $[0, 2.2, 0]$.

### 4.3 CAD-Grade Orthogonal Side (Pitch) View Shortcut
* **Feature:** A dedicated button (`Icons.Default.ViewInAr`) snaps the 3D orbit camera to look perpendicular to the $YZ$ plane:
  $$\text{Azimuth} = 90^\circ, \quad \text{Elevation} = 0^\circ$$
* **Visual Output:**
  * Ground plane ($Z = 0$) renders as an exact horizontal line.
  * Road (+Y) axis points horizontally to the right.
  * Camera boresight beam angles downward at the true physical pitch angle $\theta$ without foreshortening.
  * On-canvas indicator displays `SIDE PITCH: %+.1f°`.

### 4.4 Camera Placement Correction
* Placed camera aperture at `lensLocalX = 0.18f, lensLocalY = 0.50f, lensLocalZ = -pt - 0.01f`.
* When viewing the phone from the back chassis (facing $-Z$), $+X_{\text{phone}}$ is on the **left side**, positioning the camera lens on the top-left corner as on physical smartphones.

---

## 5. UI Layout & Defensive Reflow Engineering

In portrait mode, card header text was previously reflowing vertically character-by-character (`T-I-L-T-E-D...`) because the title `Column` lacked `Modifier.weight(1f)`.

**Applied Fix:**
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Column(
        modifier = Modifier.weight(1f).padding(end = 8.dp)
    ) {
        Text(
            text = "3D Vehicle Attitude & Level Ground",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Game Rotation Vector (6-DOF Leveled)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        ...
    ) {
        Text(
            text = statusText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}
```
Non-weighted children are measured first in Compose, guaranteeing the status pill always retains its full horizontal width.
