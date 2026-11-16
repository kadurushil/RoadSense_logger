# Implementation Plan: IMU Mounting Extrinsics Calibration Compensation

## 1. Goal Description
Compensate for the physical windshield mounting angles ($\theta_{\text{mount}} = \text{pitchDeg}$, $\phi_{\text{mount}} = \text{rollDeg}$, $\psi_{\text{mount}} = \text{yawDeg}$) when transforming IMU data from the smartphone frame into the vehicle chassis body frame (`base_link` ISO 8855 FLU).

### Problem Statement
Currently:
- In `/imu/data`, the raw phone orientation relative to gravity/ground correctly reflects the camera optical boresight pointing downward by $\approx 5.0^\circ$ toward the asphalt.
- In `/imu/vehicle`, our initial implementation applied a nominal $90^\circ$ reverse-landscape axis swap ($R_{\text{nominal}}$). Consequently, `/imu/vehicle` still reported the physical windshield mounting angle ($\approx +5.0^\circ$ pitch) as if the *vehicle chassis itself* were permanently tilted downward into the ground.
- Furthermore, because of this $5.0^\circ$ tilt, the $1\text{G}$ gravity vector leaked into longitudinal acceleration ($A_x = 9.81 \times \sin(5.0^\circ) \approx 0.85\text{ m/s}^2$).

### Solution
In the vehicle chassis frame (`base_link`), the vehicle body is level with the road on a flat surface ($0.0^\circ$ pitch, $0.0^\circ$ roll). By applying the session's active 6-DOF calibration extrinsics from `radar_camera_calib.json`:
1. `/imu/data` retains the ground-referenced camera optical orientation ($\approx 5.0^\circ$ pitch toward the road surface).
2. `/imu/vehicle` is transformed into the true vehicle body frame (`base_link`), where static vehicle pitch and roll are centered at **`0.0°`** on flat ground. Dynamic pitch (accelerating squat, braking dive, hill climb/descent) is measured purely relative to the level vehicle chassis.
3. Accelerations in `/imu/vehicle` are aligned with the vehicle body: static gravity rests entirely on $A_z \approx +9.81\text{ m/s}^2$, leaving $A_x \approx 0.0\text{ m/s}^2$ at standstill.

---

## 2. Mathematical Kinematics & Coordinate Derivation

```
       [Vehicle Front / Road Direction (+X_base Forward)]
                             ▲
                             │   Camera Optical Boresight (-Z_phone)
                             │   (Tilted downward by pitchDeg ~5.0°)
                     ┌───────┴───────┐
                     │  Rear Camera  │
   Dashboard Left    │ ┌───────────┐ │  Dashboard Right
   (-Y_phone)        │ │   Phone   │ │  (+Y_phone in ROTATION_270)
   (+Y_base Left)    │ │  Screen   │ │  (-Y_base Right)
                     │ └───────────┘ │
                     └───────┬───────┘
                             │   Screen Normal (+Z_phone)
                             ▼   (-X_base Rearward)
                 [Vehicle Cabin / Driver]
```

### 2.1 The Coordinate Transform Chain
RoadSense defines the camera optical frame $\mathcal{F}_{\text{opt}}$ (OpenCV RDF: Right, Down, Forward) relative to `base_link` $\mathcal{F}_{\text{base}}$ (ISO 8855 FLU: Forward, Left, Up) via:
$$R_{\text{opt\_from\_base}} = R_z(\phi_{\text{calib}}) \cdot R_x(\theta_{\text{calib}}) \cdot R_y(\psi_{\text{calib}}) \cdot R_0$$
where:
$$R_0 = \begin{bmatrix} 0 & -1 & 0 \\ 0 & 0 & -1 \\ 1 & 0 & 0 \end{bmatrix}$$
and $R_{\text{cam\_to\_base}} = R_{\text{opt\_from\_base}}^T$.

In Reverse Landscape (`Surface.ROTATION_270`), the phone body frame $\mathcal{F}_{\text{phone}}$ relates to the camera optical frame $\mathcal{F}_{\text{opt}}$ via:
- $X_{\text{opt}}$ (Right) $= +Y_{\text{phone}}$ (passenger side)
- $Y_{\text{opt}}$ (Down) $= +X_{\text{phone}}$ (floor)
- $Z_{\text{opt}}$ (Forward) $= -Z_{\text{phone}}$ (rear camera optical boresight)

$$R_{\text{opt\_from\_phone}} = \begin{bmatrix} 0 & 1 & 0 \\ 1 & 0 & 0 \\ 0 & 0 & -1 \end{bmatrix}$$

### 2.2 Complete Calibrated Mounting Matrix ($R_{\text{base\_from\_phone}}$)
The exact transformation mapping vectors from Phone Frame to Vehicle `base_link` is:
$$R_{\text{base\_from\_phone}} = R_{\text{cam\_to\_base}} \cdot R_{\text{opt\_from\_phone}} = R_{\text{opt\_from\_base}}^T \cdot R_{\text{opt\_from\_phone}}$$

> [!NOTE]
> When $\theta_{\text{calib}} = 0, \phi_{\text{calib}} = 0, \psi_{\text{calib}} = 0$, $R_{\text{base\_from\_phone}}$ degenerates identically to the nominal swap matrix:
> $$R_{\text{base\_from\_phone}}(0, 0, 0) = \begin{bmatrix} 0 & 0 & -1 \\ 0 & -1 & 0 \\ -1 & 0 & 0 \end{bmatrix} = R_{\text{nominal}}$$
> When $\theta_{\text{calib}} = 5.03^\circ$, the $5.03^\circ$ mounting pitch tilt is mathematically compensated.

### 2.3 Vehicle Body Orientation in World
From Android's `Sensor.TYPE_GAME_ROTATION_VECTOR` ($R_{\text{world\_from\_phone}}$):
$$\mathbf{v}_{\text{world}} = R_{\text{world\_from\_phone}} \cdot \mathbf{v}_{\text{phone}} = R_{\text{world\_from\_phone}} \cdot R_{\text{base\_from\_phone}}^T \cdot \mathbf{v}_{\text{base}}$$
Therefore:
$$R_{\text{world\_from\_base}} = R_{\text{world\_from\_phone}} \cdot R_{\text{base\_from\_phone}}^T$$

From $R_{\text{world\_from\_base}}$, the true vehicle body angles are:
- **Vehicle Pitch ($\theta_{\text{veh}}$):**
  $$\theta_{\text{veh}} = \arcsin(R_{\text{world\_from\_base}}[2, 0])$$
  *(Centered at $0.0^\circ$ on flat road; positive indicates nose-up incline/acceleration squat, negative indicates nose-down hill/braking dive)*.
- **Vehicle Roll ($\phi_{\text{veh}}$):**
  $$\phi_{\text{veh}} = \text{atan2}(R_{\text{world\_from\_base}}[2, 1], R_{\text{world\_from\_base}}[2, 2])$$
  *(Centered at $0.0^\circ$ on flat road; positive indicates vehicle leaning right)*.
- **Vehicle Yaw ($\psi_{\text{veh}}$):**
  $$\psi_{\text{veh}} = \text{atan2}(R_{\text{world\_from\_base}}[1, 0], R_{\text{world\_from\_base}}[0, 0]) \pmod{360^\circ}$$
  *(Horizontal vehicle heading in ground plane)*.

### 2.4 Empirical Validation on Session `session_20261023_111120` (116,096 samples)
Using `pitchDeg = 5.034°` and `rollDeg = -0.356°` from `radar_camera_calib.json`:
- **Uncompensated Pitch:** Median = **`+4.78°`** (offset by mounting angle).
- **Compensated Vehicle Pitch:** Median = **`+0.26°`** (Mean: **`+0.50°`**).
- **Compensated Vehicle Roll:** Median = **`-1.33°`** (Mean: **`-1.40°`**).

---

## 3. Proposed Changes

### Component 1: MCAP Conversion Engine (`tools/convert_session_to_mcap.py`)

#### [MODIFY] [`tools/convert_session_to_mcap.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)
- Update `parse_imu_csv(imu_csv_path, calib_params=None)`:
  - If `calib_params` is provided, compute $R_{\text{base\_from\_phone}}$ using `calib_params["pitchDeg"]`, `calib_params["rollDeg"]`, and `calib_params["cameraYawDeg"]`.
  - In `raw_sample` (for `/imu/data`): Keep camera optical angles with respect to ground (`pitch_deg ~ 5.0°`).
  - In `vehicle_sample` (for `/imu/vehicle`):
    - Compute $R_{\text{world\_from\_base}} = R_{\text{world\_from\_phone}} \cdot R_{\text{base\_from\_phone}}^T$.
    - Compute vehicle body pitch ($\arcsin(R_{\text{world\_from\_base}}[2, 0])$), roll, and yaw.
    - Transform accelerations: $\mathbf{a}_{\text{base}} = R_{\text{base\_from\_phone}} \cdot \mathbf{a}_{\text{phone}}$.
    - Transform angular velocity: $\boldsymbol{\omega}_{\text{base}} = R_{\text{base\_from\_phone}} \cdot \boldsymbol{\omega}_{\text{phone}}$.
    - Compute vehicle orientation quaternion $q_{\text{veh}}$ directly from $R_{\text{world\_from\_base}}$.
- Update caller at line 2090+:
  - Pass `calib_params` into `parse_imu_csv(imu_csv, calib_params=calib_params)`.

---

### Component 2: Multi-Sensor Parity Validator (`tools/verify_conversion_parity.py`)

#### [MODIFY] [`tools/verify_conversion_parity.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/verify_conversion_parity.py)
- Update `/imu/vehicle` physical validation threshold:
  - Asserts median `pitch_deg` in `/imu/vehicle` is centered near level ground (e.g. $|\text{pitch}| \le 5.0^\circ$, median $\approx 0.0^\circ$), confirming mounting pitch is compensated.
  - Asserts `/imu/data` preserves raw camera optical pitch ($\approx 5.0^\circ$).

---

### Component 3: Unit Tests (`scripts/tests/test_verify_conversion_parity.py`)

#### [MODIFY] [`scripts/tests/test_verify_conversion_parity.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/tests/test_verify_conversion_parity.py)
- Update `test_imu_vehicle_frame_transformation()` to pass calibration parameters (`pitchDeg=5.034, rollDeg=-0.356`) and assert:
  - `raw_sample["pitch_deg"]` $\approx 5.01^\circ$ (optical boresight relative to ground).
  - `veh_sample["pitch_deg"]` $\approx 0.02^\circ$ (vehicle chassis relative to flat road).
  - `veh_sample["roll_deg"]` $\approx 0.01^\circ$.

---

## 4. Verification Plan

### Automated Tests
1. **Python Unit Tests:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" -m unittest discover -s scripts/tests -p "test_*.py"
   ```
2. **Re-Export Session `session_20261023_111120`:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" tools/sync_and_process_sessions.py --process-only --session session_20261023_111120 --force --mcap
   ```
3. **Parity Validator:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" tools/verify_conversion_parity.py --session logs/session_20261023_111120
   ```
4. **Android JVM Unit Tests:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
   ```

### Manual Verification
- In Foxglove, compare `/imu/data.pitch_deg` vs. `/imu/vehicle.pitch_deg`:
  - `/imu/data.pitch_deg` hovers around $+5.0^\circ$ (camera optical pitch).
  - `/imu/vehicle.pitch_deg` hovers around $0.0^\circ$ (vehicle chassis on flat asphalt).
