# Implementation Plan: Fix IMU Yaw Delay/Overshoot & Reverse Landscape Coordinates

## Goal Description
Resolve two critical issues observed during physical device testing of the IMU Live Telemetry visualizer:
1. **Yaw Lag & Overshoot on Flat Surface:** When rotating the phone flat on a table, yaw exhibited delay, overshoot, and drift correction.
2. **Inverted/Swapped Angles in Vehicle Mount (+75° Pitch, +90.7° Roll):** When mounted in reverse landscape (`Surface.ROTATION_270`), pitch and roll showed non-physical values ($+75^\circ$ and $+90.7^\circ$) due to an axis mismatch in `remapCoordinateSystem`.

This plan establishes a mathematically rigorous, closed-form orientation pipeline based on [`intel/Sensor fusion basics.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Sensor%20fusion%20basics.md) and [`intel/Implementations/Sensors/RADAR_PITCH_CALIBRATION_VIA_IMU.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Implementations/Sensors/RADAR_PITCH_CALIBRATION_VIA_IMU.md).

---

## 1. Root Cause Analysis

### 1.1 Why Yaw Overshot and Delayed on a Flat Table
In `ImuManager.kt`, lines 356–360 previously executed:
```kotlin
val axisY = SensorManager.AXIS_MINUS_Z
val success = SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
SensorManager.getOrientation(targetMatrix, orientationAngles)
```
- When the phone lies flat on a table, phone screen normal $+Z_{\text{phone}}$ points straight UP, and $-Z_{\text{phone}}$ points straight **DOWN into the table**.
- Setting `axisY = AXIS_MINUS_Z` mapped the remapped frame's $Y$-axis straight down into the floor (parallel to gravity).
- In Euler angle mathematics, when the reference $Y$-axis is vertical, **Pitch is $\pm 90^\circ$ (Exact Gimbal Lock)**!
- At gimbal lock, the denominator in $\text{atan2}$ for azimuth and roll approaches zero. Small gyro integration variations caused the angle to swing, oscillate, and overshoot before settling.
- **Before this change**, `SensorManager.getOrientation(rotationMatrix, orientationAngles)` was called directly on `rotationMatrix`. Because $Z_{\text{phone}}$ was vertical, Pitch $= 0^\circ$, Roll $= 0^\circ$, and Azimuth was pure rotation around $Z$ with **zero gimbal lock, zero delay, and instant response**.

### 1.2 Why Pitch Was +75° and Roll Was +90.7° in Reverse Landscape
In reverse landscape (`Surface.ROTATION_270`):
- Top of the phone is on the passenger's **Right** ($+Y_{\text{phone}}$ is horizontal across the dashboard).
- Right side of screen is pointing **Down** toward the floor ($+X_{\text{phone}}$ is vertical).
- Back camera lens points **Forward** down the road ($-Z_{\text{phone}}$).
- Screen faces **Rearward** into the cabin ($+Z_{\text{phone}}$).

The previous `remapCoordinateSystem` swapped $+X$ and $-Z$, which treated the vertical earth axis as Roll (giving $\approx 90.7^\circ$) and the longitudinal boresight as Pitch (giving $\approx 75^\circ$).

---

## 2. Mathematical Solution & Coordinate Alignments

### 2.1 The Three Reference Frames

```
       [Vehicle Front / Road Direction]
                     ▲
                     │   Camera Optical Boresight (-Z_phone)
             ┌───────┴───────┐
             │  Rear Camera  │  (Back of phone facing road)
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
   - $Z_{\text{world}}$ points **UP** (perpendicular to level ground).
   - $X_{\text{world}}, Y_{\text{world}}$ form the horizontal road plane ($Z=0$).
   - Android's `R = SensorManager.getRotationMatrixFromVector` maps vectors from phone body to world: $\mathbf{v}_{\text{world}} = R \cdot \mathbf{v}_{\text{phone}}$.
2. **Phone Body Hardware Frame ($\mathcal{F}_{\text{phone}}$):**
   - $+X_{\text{phone}}$: Screen right edge (in portrait).
   - $+Y_{\text{phone}}$: Screen top edge (earpiece).
   - $+Z_{\text{phone}}$: Screen normal (out of front glass).
   - $-Z_{\text{phone}}$: Rear camera optical boresight!
3. **Vehicle Mount in Reverse Landscape (`Surface.ROTATION_270`):**
   - Top of phone is to the right $\implies$ **Lateral Right across dashboard is $+Y_{\text{phone}}$**.
   - Bottom of phone is to the left $\implies$ **Lateral Left is $-Y_{\text{phone}}$**.
   - Vertical Down toward floor is $+X_{\text{phone}}$ $\implies$ **Vertical Up is $-X_{\text{phone}}$**.
   - Forward through windshield is **$-Z_{\text{phone}}$**.

---

### 2.2 Closed-Form Angular Formulas (Invariant to Gimbal Lock)

Instead of passing mangled matrices through `remapCoordinateSystem`, we extract the physical angles directly from the raw world rotation matrix $R$:

$$R = \begin{bmatrix} R_0 & R_1 & R_2 \\ R_3 & R_4 & R_5 \\ R_6 & R_7 & R_8 \end{bmatrix}$$

#### 1. Camera Optical Pitch ($\theta_{\text{pitch}}$)
The rear camera optical boresight in world coordinates is:
$$\hat{\mathbf{b}}_{\text{world}} = R \begin{bmatrix} 0 \\ 0 \\ -1 \end{bmatrix} = \begin{bmatrix} -R_2 \\ -R_5 \\ -R_8 \end{bmatrix}$$
The vertical component of this vector is $-R_8$.
Following RoadSense standard (`CalibrationParameters.pitchDeg`, positive pitch tilts DOWN toward road):
$$\theta_{\text{pitch}} = \arcsin(R_8) = \text{atan2}(R_8, \sqrt{R_2^2 + R_5^2})$$
- When horizontal: $R_8 = 0 \implies \theta_{\text{pitch}} = 0^\circ$.
- When tilted downward toward the road by $5^\circ$: $R_8 = \sin(5^\circ) \approx 0.087 \implies \theta_{\text{pitch}} = \mathbf{+5.0^\circ}$!
- **Universal across all mounts** because the camera lens is physically fixed to $-Z_{\text{phone}}$.

#### 2. Lateral Mount Roll ($\phi_{\text{roll}}$)
The lateral dashboard vector pointing to the passenger side (vehicle right) is:
- In **Reverse Landscape (`Surface.ROTATION_270`)**: $+Y_{\text{phone}} \implies \mathbf{v}_{\text{lat}} = [R_1, R_4, R_7]^T$.
  $$\phi_{\text{roll}} = \arcsin(R_7)$$
  When clamped level across the dashboard: $R_7 \approx 0.0 \implies \phi_{\text{roll}} \approx \mathbf{0.0^\circ}$!
- In **Standard Landscape (`Surface.ROTATION_90`)**: $-Y_{\text{phone}} \implies \phi_{\text{roll}} = \arcsin(-R_7)$.
- In **Portrait (`Surface.ROTATION_0`)**: $+X_{\text{phone}} \implies \phi_{\text{roll}} = \arcsin(R_6)$.

#### 3. Yaw Heading ($\psi_{\text{yaw}}$)
- **Flat on Table ($|R_8| > 0.7$):** Pure azimuth from `SensorManager.getOrientation(rotationMatrix, orientationAngles)[0]`.
  - Zero gimbal lock, zero lag, instant sub-degree response.
- **Windshield Mount ($|R_8| \le 0.7$):** Camera optical boresight heading in the horizontal ground plane:
  $$\psi_{\text{yaw}} = \text{atan2}(-R_2, -R_5)$$

---

### 2.3 Synchronizing 3D Model Geometry in `PhoneOrientation3DVisualizer.kt`
Currently, `localCorners` in `PhoneOrientation3DVisualizer.kt` defined $X$ as width across the dash, $Y$ as thickness, and $Z$ as vertical height.
However, $R$ is in the phone's native body frame!
We align the 3D model vertices to the phone body frame:
- Local $X \in [-pw, +pw]$: Portrait screen width ($0.72\text{ units}$)
- Local $Y \in [-ph, +ph]$: Portrait screen length ($1.45\text{ units}$)
- Local $Z \in [-pt, +pt]$: Phone thickness ($0.10\text{ units}$)
- Front screen is at $+pt$ (facing user).
- Rear camera is at $-pt$ (facing away from screen).
- Camera optical boresight ray points along $[0, 0, -1]^T$.

When rotated into Reverse Landscape (`ROTATION_270`), $R$ rotates $+Y$ to the right, $+X$ down, $-Z$ forward, and $+Z$ rearward. The 3D phone model will perfectly match the physical phone in space!

---

## 3. Proposed Changes

### Component 1: `com.bajajauto.roadsense.imu`

#### [MODIFY] [`ImuManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)
- Remove `remapCoordinateSystem` from `Sensor.TYPE_GAME_ROTATION_VECTOR`.
- Compute `pitchDeg = asin(R[8])` (optical downward tilt).
- Compute `rollDeg = asin(R[7])` for `ROTATION_270` (and `asin(-R[7])` for `ROTATION_90`).
- Compute `yawDeg` using native azimuth when flat and boresight heading when upright.
- Store raw `rotationMatrix` in `currentWorldMatrix` for 1:1 3D visualization.

---

### Component 2: `com.bajajauto.roadsense.ui.components`

#### [MODIFY] [`PhoneOrientation3DVisualizer.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/PhoneOrientation3DVisualizer.kt)
- Update `localCorners` to match Android phone body frame:
  - $X$: Width ($0.72$)
  - $Y$: Height ($1.45$)
  - $Z$: Thickness ($0.10$)
- Set Front Screen face to $+Z$ corners (indices 4, 5, 6, 7).
- Set Rear Chassis face to $-Z$ corners (indices 0, 1, 2, 3).
- Place camera aperture on back casing at $(x=-0.20, y=0.52, z=-pt)$ and shoot optical boresight ray along $[0, 0, -1]$.

---

## 4. Verification Plan

### Automated Tests
1. **JVM Unit Tests:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
   ```
2. **Android Debug APK Compilation:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug
   ```

### Manual Verification
1. **Flat on Table:**
   - Place phone flat on table.
   - Rotate clockwise and counter-clockwise: verify yaw updates instantly without delay or overshoot.
   - Verify roll reads $\approx 0.0^\circ$.
2. **Reverse Landscape Mount in Vehicle:**
   - Mount phone in reverse landscape (`Surface.ROTATION_270`, top of phone on passenger's right).
   - Verify Camera Pitch reads nominal windshield angle (e.g. $+4.0^\circ$ to $+7.0^\circ$, NOT $+75^\circ$).
   - Verify Lateral Roll reads near zero (e.g. $\pm 0.5^\circ$, NOT $+90.7^\circ$).
   - Verify the 3D phone model in the visualizer sits upright in reverse landscape with the green boresight ray shooting forward onto the road grid.
