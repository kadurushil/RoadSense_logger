# Ego-Motion Kinematic Consistency & CAN/IMU Cross-Validation

> **Document Classification:** Autonomous Vehicle Research & Engineering Systems  
> **Target Audience:** Control Systems Engineers, State Estimation Specialists, Embedded Sensor Architects  
> **Location:** `intel/Validation/05_KINEMATIC_CONSISTENCY_AND_CAN_IMU_VALIDATION.md`  
> **Status:** Authoritative Mathematical Specification  

---

## 1. Overview & The "Zero-External-Sensor" Principle

A major challenge in automotive validation is that external reference systems (such as differential GPS or optical motion capture) require complex logistics, good weather, and expensive beacons.

**Kinematic Consistency Validation (Pillar 2)** eliminates this dependency entirely. It exploits the **fundamental physical invariants of rigid-body vehicle kinematics**:
* The vehicle's own CAN bus broadcasts calibrated wheel speeds ($V_{\text{ego}}$) and steering dynamics at high frequency.
* The host edge computer's internal motion ASIC samples 3-axis angular rates ($\omega_x, \omega_y, \omega_z$) and linear accelerations at 100 Hz.
* Physical stationary objects (road asphalt, guardrails, traffic signs, bridge pillars, parked vehicles) have **exactly zero velocity in the inertial world frame** ($V_{\text{world}} \equiv 0$).

By cross-referencing radar Doppler measurements and target trajectories against these onboard kinematic invariants, we can validate radar tracking, detect mounting bracket misalignments, and isolate sensor drift **without requiring any external optical models or target beacons**.

---

## 2. Methodology 1: Stationary Clutter Doppler Invariance

### 2.1 The Physical Doppler Invariant
Let the host vehicle travel forward along a road at instantaneous longitudinal velocity $V_{\text{ego}}$ (measured via CAN bus wheel speed sensors or transmission output shaft encoders).

A stationary roadside landmark (e.g. a metal guardrail or overhead gantry) is situated at azimuth angle $\theta$ and elevation angle $\phi$ relative to the radar sensor's boresight:

```
TOP-DOWN VELOCITY VECTOR PROJECTION:

                Landmark (Stationary: V_world = 0)
                       ●
                      /
                     /  Line-of-Sight Vector
                    /   
                   /    Angle θ (Azimuth)
                  /
                 ▼  
         ┌───────────────┐
         │  Radar Sensor │
         └───────┬───────┘
                 │
                 │  Forward Vehicle Speed: V_ego
                 ▼
```

Because the landmark is fixed to the earth, the relative velocity vector between the radar and the landmark is purely the negative of the vehicle's ego-velocity:

$$\mathbf{V}_{\text{rel}} = - \mathbf{V}_{\text{ego}} = \begin{bmatrix} 0 \\ - V_{\text{ego}} \\ 0 \end{bmatrix}$$

The radar's FMCW Doppler processing measures the **radial projection** of this relative velocity along the line-of-sight unit vector $\hat{\mathbf{r}}$:

$$\hat{\mathbf{r}} = \begin{bmatrix} \cos(\phi) \sin(\theta) \\ \cos(\phi) \cos(\theta) \\ \sin(\phi) \end{bmatrix}$$

Taking the dot product:

$$V_{\text{radial, theoretical}} = \mathbf{V}_{\text{rel}} \cdot \hat{\mathbf{r}} = - V_{\text{ego}} \cdot \cos(\phi) \cdot \cos(\theta)$$

For typical automotive road sensors mounted near horizontal elevation ($\phi \approx 0^\circ \implies \cos(\phi) \approx 1.0$), this simplifies to the **Fundamental Doppler Invariant**:

$$V_{\text{radial}} + V_{\text{ego}} \cdot \cos(\theta) \equiv 0.00 \text{ m/s}$$

### 2.2 The Doppler Residual Metric ($\epsilon_{\text{Doppler}}$)
For every radar point or cluster classified as stationary clutter by the radar DSP, the validation engine calculates the Doppler residual:

$$\epsilon_{\text{Doppler}} = \left| V_{\text{radial, measured}} + V_{\text{ego, CAN}} \cdot \cos(\theta_{\text{azimuth}}) \right|$$

In a perfectly calibrated system, $\epsilon_{\text{Doppler}} < 0.15\text{ m/s}$ (bounded by Doppler FFT bin resolution $\Delta v = \frac{\lambda}{2 T_{\text{chirp}}}$).

### 2.3 Diagnostic Fault Isolation Matrix
Systematic deviations in $\epsilon_{\text{Doppler}}$ isolate specific hardware and calibration faults:

| Observed Residual Pattern | Underlying Root Cause | Corrective Action |
| :--- | :--- | :--- |
| **Constant non-zero offset ($\epsilon \approx \text{const}$ across all angles $\theta$)** | Vehicle CAN wheel speed scaling error (e.g. tire wear, incorrect rolling radius in DBC). | Recalibrate wheel diameter factor in CAN decoding configuration. |
| **Residual scales proportionally with velocity ($\epsilon \propto V_{\text{ego}}$)** | mmWave FMCW chirp slope frequency error or radar sampling clock drift. | Recalibrate radar PLL frequency reference. |
| **Residual varies as a function of azimuth ($\epsilon \propto \sin \theta$)** | Physical mounting yaw misalignment ($\Delta \psi$) between radar and vehicle centerline. | Adjust mounting yaw offset in `radar_camera_calib.json`. |
| **High variance ($\sigma_\epsilon > 0.8\text{ m/s}$) on straight roads** | Severe mechanical vibration or loose mounting bracket on motorcycle chassis. | Stiffen sensor mounting bracket; inspect vibration damping grommets. |

---

## 3. Methodology 2: Dead-Reckoning Landmark Stability (Ego-SLAM Constraint)

### 3.1 Kinematic Pose Integration
By fusing vehicle CAN speed $V_{\text{ego}}$ with the phone's 100 Hz calibrated gyroscope yaw rate ($\omega_z$), the vehicle's planar trajectory in the navigation frame is tracked via continuous dead-reckoning:

$$\psi(t) = \int_0^t \omega_z(\tau) \, d\tau + \psi_0$$

$$\mathbf{X}_{\text{ego}}(t) = \begin{bmatrix} X(t) \\ Y(t) \end{bmatrix} = \int_0^t \begin{bmatrix} V_{\text{ego}}(\tau) \cos(\psi(\tau)) \\ V_{\text{ego}}(\tau) \sin(\psi(\tau)) \end{bmatrix} d\tau$$

The homogeneous transformation matrix representing the vehicle pose at time $t$ relative to session start $t_0$ is:

$$\mathbf{T}_V^W(t) = \begin{bmatrix} \cos(\psi(t)) & -\sin(\psi(t)) & X(t) \\ \sin(\psi(t)) & \cos(\psi(t)) & Y(t) \\ 0 & 0 & 1 \end{bmatrix}$$

### 3.2 Landmark Spatial Invariance
When the radar observes a prominent stationary target (e.g. a metal signpost) at time $t_1$ at coordinates $\mathbf{P}_1 = [x_1, y_1, 1]^T$ in the sensor frame, its world coordinate is:

$$\mathbf{P}_{\text{world}}(t_1) = \mathbf{T}_V^W(t_1) \cdot \mathbf{T}_R^V \cdot \mathbf{P}_1$$

When the radar re-observes this same physical landmark at time $t_2$ ($100\text{ ms}$ to $2\text{ s}$ later as the vehicle drives past) at sensor coordinates $\mathbf{P}_2 = [x_2, y_2, 1]^T$:

$$\mathbf{P}_{\text{world}}(t_2) = \mathbf{T}_V^W(t_2) \cdot \mathbf{T}_R^V \cdot \mathbf{P}_2$$

Because the physical landmark is static:

$$\left\| \mathbf{P}_{\text{world}}(t_1) - \mathbf{P}_{\text{world}}(t_2) \right\| \equiv 0.00 \text{ m}$$

```
DEAD-RECKONING LANDMARK STABILITY:

[Vehicle at t1] ─── Radar detects Signpost at P1 ───► World Landmark: P_world(t1)
       │                                                         ▲
   Dead-Reckoning                                                │ Spatially Invariant:
   Trajectory Integration                                        │ ||P(t1) - P(t2)|| < 0.2m
       ▼                                                         ▼
[Vehicle at t2] ─── Radar detects Signpost at P2 ───► World Landmark: P_world(t2)
```

* **Validation Threshold:** For high-quality tracking, the drift between consecutive landmark projections must remain $< 0.20\text{ m}$ over a $30\text{ m}$ traversal window.
* **Failure Identification:** If the landmark trajectory curves or stretches in world coordinates, the tracker's Kalman filter covariance matrices ($\mathbf{Q}$ and $\mathbf{R}$) are improperly balanced.

---

## 4. Implementation Algorithm & Python Script Architecture

The validation toolchain implements this analysis in `tools/validation/audit_kinematic_consistency.py`:

```python
"""
audit_kinematic_consistency.py
Validates radar Doppler measurements and stationary clutter stability against CAN wheel speed and IMU.
"""

import numpy as np
import pandas as pd

def compute_doppler_consistency(radar_points_df, can_speed_df, timeline_df):
    """
    Computes frame-by-frame Doppler residual:
    epsilon = |V_radial + V_ego * cos(theta)|
    """
    # 1. Merge radar points with CAN speed using monotonic nanoseconds
    merged = pd.merge_asof(
        radar_points_df.sort_values("mono_ns"),
        can_speed_df.sort_values("mono_ns"),
        on="mono_ns",
        direction="nearest",
        tolerance=50_000_000 # 50 ms max match tolerance
    )

    # 2. Filter for stationary candidates: V_radial != 0 and within clutter envelope
    stationary_mask = np.abs(merged["v_radial"] + merged["v_ego_mps"] * np.cos(merged["azimuth_rad"])) < 2.5
    stationary_points = merged[stationary_mask].copy()

    # 3. Calculate Doppler residual
    stationary_points["v_expected"] = - stationary_points["v_ego_mps"] * np.cos(stationary_points["azimuth_rad"])
    stationary_points["residual_mps"] = np.abs(stationary_points["v_radial"] - stationary_points["v_expected"])

    # 4. Compute performance metrics
    mean_residual = stationary_points["residual_mps"].mean()
    rmse_residual = np.sqrt(np.mean(stationary_points["residual_mps"]**2))
    p95_residual = np.percentile(stationary_points["residual_mps"], 95)

    status = "PASS" if rmse_residual < 0.35 else "FAIL"

    return {
        "status": status,
        "sample_count": len(stationary_points),
        "mean_residual_mps": float(mean_residual),
        "rmse_residual_mps": float(rmse_residual),
        "p95_residual_mps": float(p95_residual)
    }
```

---

## 5. Summary & Key Engineering Benefits

1. **Zero Setup Cost:** Operates on normal road-test datasets without requiring physical targets or specialized test tracks.
2. **Instant Hardware Sanity Check:** Provides an immediate automated check for radar frequency drift, loose mounting brackets, and CAN DBC decoding errors.
3. **Continuous Background Execution:** Can run automatically on every session log ingested into the fleet database.
