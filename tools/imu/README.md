# RoadSense IMU Processing & Fusion Engine (`tools/imu/`)

This directory houses all algorithms, 6-DOF coordinate transformations, and sensor fusion engines for inertial measurement unit (IMU) data across the RoadSense platform.

---

## Architecture & Responsibilities

```text
tools/imu/
├── __init__.py          # Public API exports
├── transforms.py        # 6-DOF mounting extrinsics & spatial coordinate transformations
├── heading_filter.py    # 2-state GNSS-IMU Extended Kalman Filter (ZUPT + Doppler course fusion)
├── processor.py         # Stream generator producing /imu/data and /imu/vehicle samples
└── README.md            # Technical specification & mathematical documentation
```

---

## 1. Coordinate Frames & Mounting Extrinsics (`transforms.py`)

In RoadSense vehicle recordings, the smartphone is clamped to the windshield at the rear-view mirror in **Reverse Landscape (`Surface.ROTATION_270`)** orientation.

### Frame Definitions
1. **Phone Hardware Frame (Android Native Portrait)**:
   - $+X$: Right along short edge
   - $+Y$: Up along long edge
   - $+Z$: Out of glass toward user
2. **Camera Optical Frame (RDF)**:
   - $+X$: Right (along vehicle $+Y$)
   - $+Y$: Down (along vehicle $-Z$)
   - $+Z$: Forward along optical axis (along vehicle $+X$)
3. **Vehicle Chassis Body Frame (ISO 8855 FLU)**:
   - $+X$: Forward along vehicle longitudinal axis
   - $+Y$: Left along vehicle lateral axis
   - $+Z$: Up along vehicle vertical axis

### Rotation Matrix Formulation
$$R_{\text{base\_from\_phone}} = R_{\text{cam\_to\_base}} \cdot R_{\text{opt\_from\_phone}}$$
where:
$$R_{\text{cam\_to\_base}} = (R_z(\phi_{\text{calib}}) \, R_x(\theta_{\text{calib}}) \, R_y(\psi_{\text{calib}}) \, R_0)^T$$
$$\mathbf{a}_{\text{base}} = R_{\text{base\_from\_phone}} \cdot \mathbf{a}_{\text{phone}}$$
$$\boldsymbol{\omega}_{\text{base}} = R_{\text{base\_from\_phone}} \cdot \boldsymbol{\omega}_{\text{phone}}$$

---

## 2. GNSS-IMU Heading Filter (`heading_filter.py`)

MEMS gyroscopes feature a persistent zero-rate DC bias ($b_z \approx -1.45^\circ/\text{s}$). Under open-loop 6-DOF sensor fusion (`Sensor.TYPE_GAME_ROTATION_VECTOR`), this causes yaw to drift by $> 85^\circ$ per minute ($> 1,700^\circ$ over 20 minutes).

### 2-State Extended Kalman Filter
- **State Vector**: $\mathbf{x} = [\psi, b_z]^T$ (geographic bearing $\psi \in [-\pi, \pi)$ where $0 = \text{North}, \frac{\pi}{2} = \text{East}$, and gyro bias $b_z$).
- **Prediction (100 Hz)**:
  $$\omega_{\text{bearing}} = -v\_gz_k$$
  $$\psi_k = \text{wrap\_pi}(\psi_{k-1} + (\omega_{\text{bearing}} - b_z) \Delta t)$$
  $$P_{k|k-1} = F P_{k-1} F^T + Q, \quad F = \begin{bmatrix} 1 & -\Delta t \\ 0 & 1 \end{bmatrix}$$
- **Zero Velocity Update (ZUPT)**:
  When vehicle is stationary (speed $< 0.3\text{ m/s}$), true $\omega = 0$. The measured rate directly measures bias ($H = [0, 1]$), locking $b_z$ to the true physical offset and reducing stationary drift to $< 1.0^\circ$.
- **GPS Doppler Course Update**:
  When driving (speed $> 1.5\text{ m/s}$), GPS course over ground anchors the vehicle heading to true geographic North/East ($H = [1, 0]$).

---

## 3. High-Level Processor (`processor.py`)

`ImuSessionProcessor` parses `imu_frames.csv` and synchronized `gnss_fixes.csv`, producing two synchronized sample dictionaries per timestamp:
- **`raw_sample`** (published to `/imu/data`): Raw phone accelerations and optical attitude.
- **`vehicle_sample`** (published to `/imu/vehicle` & `/imu/pose`): Extrinsics-compensated, gravity-decoupled kinematics with zero-drift geographic heading and normalized vehicle orientation quaternion.

