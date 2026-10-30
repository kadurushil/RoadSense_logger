# Implementation Plan: IMU Reverse Landscape Vehicle Frame Transformation

## 1. Goal Description
Fix the roll, pitch, and yaw values in `/imu/data` and the orientation quaternion in `/imu/pose` within the RoadSense MCAP conversion pipeline. 

Currently, `tools/convert_session_to_mcap.py` computes naive Tait-Bryan Euler angles directly from the raw Android phone hardware quaternion without accounting for vehicle mounting. Because the smartphone is clamped horizontally in **Reverse Landscape mode (`Surface.ROTATION_270`)** at the rear-view mirror position:
- `pitch_deg` is erroneously reported as **`~85.0°`** (elevation of phone's portrait $Y$-axis).
- 1G gravity ($~9.8\text{ m/s}^2$) is reported on longitudinal acceleration (`acceleration.x` / `ax = -9.38\text{ m/s}^2`), causing Foxglove to indicate constant emergency braking while stationary.
- Vehicle yaw rate is reported on `gx` rather than `gz`.
- `/imu/pose` publishes the unmapped phone quaternion under `frame_id = "base_link"`.

This plan implements a mathematically closed-form $SO(3)$ vehicle mount transform from Phone Frame ($\mathcal{F}_{\text{phone}}$) to Vehicle Body Frame (`base_link` $\mathcal{F}_{\text{vehicle}}$ ISO 8855 FLU: Forward, Left, Up), synchronizing the PC conversion engine with Android's [`ImuSample.toEulerAnglesDeg()`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSample.kt#L45-L77) and the RoadSense coordinate transform specification.

---

## 2. Mathematical Formulation & Coordinate Frame Derivation

```
       [Vehicle Front / Road Direction (+X_base Forward)]
                             ▲
                             │   Camera Optical Boresight (-Z_phone)
                     ┌───────┴───────┐
                     │  Rear Camera  │  (Back chassis facing road)
   Dashboard Left    │ ┌───────────┐ │  Dashboard Right
   (-Y_phone)        │ │   Phone   │ │  (+Y_phone in ROTATION_270)
   (+Y_base Left)    │ │  Screen   │ │  (-Y_base Right)
                     │ └───────────┘ │
                     └───────┬───────┘
                             │   Screen Normal (+Z_phone)
                             ▼   (-X_base Rearward)
                 [Vehicle Cabin / Driver]
```

### 2.1 The Two Coordinate Frames
1. **Android Phone Hardware Frame ($\mathcal{F}_{\text{phone}}$):**
   - $+X_{\text{phone}}$: Screen right edge (portrait).
   - $+Y_{\text{phone}}$: Screen top edge (earpiece).
   - $+Z_{\text{phone}}$: Screen normal (facing out of front glass toward driver).
   - $-Z_{\text{phone}}$: Rear camera optical boresight (pointing out back chassis toward road).

2. **Vehicle Frame (`base_link` $\mathcal{F}_{\text{vehicle}}$ — ISO 8855 FLU):**
   - $+X_{\text{base}}$: **Forward** along vehicle centerline.
   - $+Y_{\text{base}}$: **Left** across driver side.
   - $+Z_{\text{base}}$: **Up** toward vehicle roof perpendicular to road.

### 2.2 Physical Mount Mapping in Reverse Landscape (`Surface.ROTATION_270`)
- **Forward ($+X_{\text{base}}$):** Rear camera points forward through windshield $\implies X_{\text{base}} = -Z_{\text{phone}}$.
- **Left ($+Y_{\text{base}}$):** Driver side is USB-C bottom of phone $\implies Y_{\text{base}} = -Y_{\text{phone}}$.
- **Up ($+Z_{\text{base}}$):** Roof is top/left edge of phone $\implies Z_{\text{base}} = -X_{\text{phone}}$.

Expressing as a linear 3D transform $R_{\text{mount}}$ ($\mathbf{v}_{\text{base}} = R_{\text{mount}} \cdot \mathbf{v}_{\text{phone}}$):
$$R_{\text{mount}} = \begin{bmatrix} 0 & 0 & -1 \\ 0 & -1 & 0 \\ -1 & 0 & 0 \end{bmatrix}$$

Properties of $R_{\text{mount}}$:
- $R_{\text{mount}}^T R_{\text{mount}} = I$ (Orthogonal)
- $\det(R_{\text{mount}}) = +1$ (Proper right-handed $SO(3)$ rotation: 180° rotation around $\frac{1}{\sqrt{2}}[-1, 0, 1]^T$)
- $R_{\text{mount}}^{-1} = R_{\text{mount}}^T = R_{\text{mount}}$ (Symmetric involution)

### 2.3 Sensor Kinematics in Vehicle Frame
- **Accelerations ($a_{\text{base}} = R_{\text{mount}} \cdot a_{\text{phone}}$):**
  $$a_x = -a_{z,\text{phone}} \quad (\text{Longitudinal acceleration: positive = accelerating forward})$$
  $$a_y = -a_{y,\text{phone}} \quad (\text{Lateral acceleration: positive = cornering right / accelerating left})$$
  $$a_z = -a_{x,\text{phone}} \quad (\text{Vertical acceleration: } \approx +9.81\text{ m/s}^2 \text{ normal reaction to gravity})$$

- **Linear Dynamic Accelerations ($lin\_a_{\text{base}} = R_{\text{mount}} \cdot lin\_a_{\text{phone}}$):**
  $$lin\_a_x = -lin\_a_{z,\text{phone}}$$
  $$lin\_a_y = -lin\_a_{y,\text{phone}}$$
  $$lin\_a_z = -lin\_a_{x,\text{phone}}$$

- **Angular Velocities ($\omega_{\text{base}} = R_{\text{mount}} \cdot \omega_{\text{phone}}$):**
  $$\omega_x = -g_{z,\text{phone}} \quad (\text{Roll rate, rad/s})$$
  $$\omega_y = -g_{y,\text{phone}} \quad (\text{Pitch rate, rad/s})$$
  $$\omega_z = -g_{x,\text{phone}} \quad (\text{Yaw rate around vertical } Z_{\text{base}} \text{ axis, rad/s})$$

### 2.4 Vehicle Attitude Euler Angles (Closed-Form)
Given phone quaternion $(q_x, q_y, q_z, q_w)$ from `imu_frames.csv`, construct $R_{\text{phone}}$:
$$R_{\text{phone}} = \begin{bmatrix}
1 - 2(q_y^2 + q_z^2) & 2(q_x q_y - q_z q_w) & 2(q_x q_z + q_y q_w) \\
2(q_x q_y + q_z q_w) & 1 - 2(q_x^2 + q_z^2) & 2(q_y q_z - q_x q_w) \\
2(q_x q_z - q_y q_w) & 2(q_y q_z + q_x q_w) & 1 - 2(q_x^2 + q_y^2)
\end{bmatrix} = \begin{bmatrix} R_0 & R_1 & R_2 \\ R_3 & R_4 & R_5 \\ R_6 & R_7 & R_8 \end{bmatrix}$$

1. **Optical Pitch ($\theta_{\text{pitch}}$):**
   Forward vector in world: $\hat{\mathbf{x}}_{\text{veh}} = [-R_2, -R_5, -R_8]^T$.
   Vertical component is $-R_8$. Positive pitch indicates tilt downward toward road surface:
   $$\theta_{\text{pitch}} = \arcsin(R_8) = \arcsin(1 - 2(q_x^2 + q_y^2))$$
   *Validation on `session_20261023_111120`:* Median pitch = **`+4.78°`** (perfectly matches calibration `5.42°`).

2. **Lateral Mount Roll ($\phi_{\text{roll}}$):**
   Passenger-side transverse axis in world: $[R_1, R_4, R_7]^T$.
   ISO 8855 positive roll leans right (passenger side down, $R_7 < 0$):
   $$\phi_{\text{roll}} = -\arcsin(R_7) = -\arcsin(2(q_y q_z + q_x q_w))$$
   *Validation on `session_20261023_111120`:* Median roll = **`-1.68°`** (virtually level with car dashboard).

3. **Azimuth Yaw Heading ($\psi_{\text{yaw}}$):**
   Horizontal components of forward boresight: East is $-R_2$, North is $-R_5$.
   Navigation heading clockwise from North:
   $$\psi_{\text{yaw}} = \text{atan2}(-R_2, -R_5) \pmod{360^\circ}$$
   where $-R_2 = -2(q_x q_z + q_y q_w)$ and $-R_5 = -2(q_y q_z - q_x q_w)$.

### 2.5 Vehicle Attitude Quaternion for `/imu/pose`
$$R_{\text{veh}} = R_{\text{phone}} \cdot R_{\text{mount}}$$
In quaternion algebra:
$$q_{\text{veh}} = q_{\text{phone}} \otimes q_{\text{mount}}$$
where $q_{\text{mount}} = [-\frac{\sqrt{2}}{2}, 0, \frac{\sqrt{2}}{2}, 0]^T$.

---

## 3. Approved Topic Architecture & Schema Separation

To guarantee 100% backward compatibility with any extensions expecting raw phone HAL values while providing physically accurate vehicle kinematics:

| Topic | Frame (`frame_id`) | Description & Content |
|---|---|---|
| **`/imu/data`** *(Existing, Unmodified)* | `imu_device` / Phone HAL | **100% Raw Phone Sensors** (as logged directly from Android HAL):<br>• Raw phone axes (`ax`, `ay`, `az`, `gx`, `gy`, `gz`, `lin_ax`, `lin_ay`, `lin_az`, `qx`, `qy`, `qz`, `qw`)<br>• Raw phone Tait-Bryan angles |
| **`/imu/vehicle`** *(New)* | `base_link` (ISO 8855 FLU) | **Transformed Vehicle Kinematics** (`roadsense.ImuVehicle`):<br>• `roll_deg`: Leveled mount roll (median $\approx -1.7^\circ$)<br>• `pitch_deg`: True windshield camera pitch (median $\approx +4.8^\circ$)<br>• `yaw_deg`: Compass azimuth navigation heading ($0^\circ$ to $360^\circ$)<br>• `acceleration`: Forward ($a_x$), Left ($a_y$), Vertical ($a_z \approx +9.81\text{ m/s}^2$)<br>• `linear_acceleration`: Forward ($lin\_a_x$), Left ($lin\_a_y$), Vertical ($lin\_a_z$)<br>• `angular_velocity`: Roll rate ($\omega_x$), Pitch rate ($\omega_y$), Yaw rate ($\omega_z$)<br>• `orientation`: Vehicle attitude quaternion ($q_{\text{veh}} = q_{\text{phone}} \otimes q_{\text{mount}}$) |
| **`/imu/pose`** *(Updated)* | `base_link` | **Vehicle Orientation Pose** (`foxglove.PoseInFrame`):<br>• Uses the transformed vehicle quaternion $q_{\text{veh}}$ so Foxglove's 3D panel displays the vehicle attitude upright in `base_link`. |

---

## 4. Proposed Changes

### Component 1: MCAP Conversion Engine (`tools/convert_session_to_mcap.py`)

#### [MODIFY] [`tools/convert_session_to_mcap.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)
- Update `parse_imu_csv(imu_csv_path)`:
  - Apply $R_{\text{mount}}$ to transform linear acceleration, gravity acceleration, and angular velocity into vehicle frame (`base_link`).
  - Compute closed-form optical pitch, lateral roll, and azimuth yaw using the reverse landscape mount kinematics.
  - Compute vehicle orientation quaternion $q_{\text{veh}} = q_{\text{phone}} \otimes q_{\text{mount}}$.
  - Yield both vehicle-frame properties and raw phone sensor properties.
- Update `roadsense.Imu` JSON schema:
  - Add optional raw properties (`raw_ax`, `raw_ay`, etc.) for forensic tracing.
- Update `/imu/pose` publisher:
  - Writes vehicle quaternion $q_{\text{veh}}$ into `pose.orientation` under `frame_id = "base_link"`.

```python
# Mounting transformation matrix for Reverse Landscape (Surface.ROTATION_270):
# X_base = -Z_phone, Y_base = -Y_phone, Z_base = -X_phone
# Quaternion for R_mount: q_mount = [-sqrt(0.5), 0, sqrt(0.5), 0]
```

---

### Component 2: Multi-Sensor Parity Validator (`tools/verify_conversion_parity.py`)

#### [MODIFY] [`tools/verify_conversion_parity.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/verify_conversion_parity.py)
- Add Category 9 (or extend Category 8) to verify IMU physical invariants:
  - Asserts median `pitch_deg` is within plausible windshield tilt ($+2.0^\circ$ to $+10.0^\circ$), NOT $+85^\circ$.
  - Asserts median `roll_deg` is within level dashboard tolerance ($-5.0^\circ$ to $+5.0^\circ$).
  - Asserts stationary vertical acceleration `acceleration.z` is positive normal reaction ($+8.0$ to $+12.0\text{ m/s}^2$), while longitudinal `acceleration.x` is near zero ($< 3.0\text{ m/s}^2$).
  - Asserts `/imu/pose` quaternion matches vehicle orientation matrix.

---

### Component 3: Unit Tests (`scripts/tests/test_verify_conversion_parity.py`)

#### [MODIFY] [`scripts/tests/test_verify_conversion_parity.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/tests/test_verify_conversion_parity.py)
- Add unit tests verifying:
  - $R_{\text{mount}}$ matrix properties ($\det = 1$, orthogonal).
  - Quaternion composition $q_{\text{veh}} = q_{\text{phone}} \otimes q_{\text{mount}}$ aligns with $R_{\text{phone}} R_{\text{mount}}$.
  - Reverse landscape Euler angle recovery matches test fixtures.

---

## 5. Verification Plan

### Automated Tests
1. **Python Unit Tests:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" -m unittest discover -s scripts/tests -p "test_*.py"
   ```
2. **Re-Export & Multi-Sensor Parity Validator:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" tools/sync_and_process_sessions.py --process-only --session session_20261023_111120 --force --mcap
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" tools/verify_conversion_parity.py --session logs/session_20261023_111120
   ```
3. **Android JVM Unit Tests:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
   ```

### Manual Verification in Foxglove Studio
- Open `logs/session_20261023_111120/session_20261023_111120.mcap`.
- In the State/Plot panel, inspect `/imu/data`:
  - `pitch_deg` should hover around **$+4.8°$** (windshield tilt), not $85°$.
  - `roll_deg` should hover around **$-1.7°$** (nearly level).
  - `acceleration.z` should show **$+9.8\text{ m/s}^2$** (reaction to gravity), and `acceleration.x` should show longitudinal accel (near 0 when stationary).
  - `angular_velocity.z` should show vehicle yaw rate.
- In the 3D panel, inspect `/imu/pose`: the coordinate axes in `base_link` should remain upright with slight forward downward pitch.
