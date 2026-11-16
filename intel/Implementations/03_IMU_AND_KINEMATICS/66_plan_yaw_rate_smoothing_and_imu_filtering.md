# Implementation Plan: Ego Yaw Rate Smoothing & IMU Pre-Processing

## Goal Description
During cornering and straight-line driving, external vehicle 3D bounding boxes can exhibit rotational jitter or exaggerated angular slewing if the host vehicle's incoming ego yaw rate signal ($\omega_{\text{ego}}$) contains high-frequency noise, discretization steps, or chassis vibration. 

This plan clarifies the exact yaw rate signal source currently consumed by the vision pipeline, analyzes its noise profile against alternative sensors, and details the implementation of a dedicated **Low-Pass Yaw Rate Smoothing Filter** and **Soft Deadband** to ensure clean, jitter-free ego motion compensation.

---

## 1. Active Yaw Rate Source Audit

Currently, the offline perception pipeline ingests yaw rate from **`/vehicle/telemetry`**:

```
+-------------------------------------------------------------+
|  Host Vehicle Powertrain CAN (TLV 5: c[1])                  |
|  Topic: /vehicle/telemetry                                   |
|  Signal: yaw_rate_radps (Chassis ESC / VCU Gyroscope)       |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|  EgoMotionProvider (vehicle_orientation_tracker.py)         |
|  - Ingests: speed_kmph / 3.6, yr_base_link = -yr_can        |
|  - Rate: ~20 - 50 Hz                                        |
|  - Units: Radians per second (rad/s)                        |
|  - Frame: ROS / ISO-8855 base_link (+Left / +Z Up)          |
+-------------------------------------------------------------+
```

### Why not Phone IMU (`/imu/data`) or Radar Diagnostics (`/radar/diagnostics`)?
| Source | Topic | Signal | Sensor / Origin | Noise Characteristics | Current Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **CAN Telemetry** | `/vehicle/telemetry` | `yaw_rate_radps` | Chassis ESC / VCU | Low drift, resolution $0.01\text{ rad/s}$, moderate quantization | **Active Source** |
| **Phone IMU** | `/imu/data` | `angular_velocity.z` | Samsung SM-M305F Gyro | Very noisy ($\sigma \approx 0.094\text{ rad/s} \approx 5.4^\circ/\text{s}$), windshield mount pitch offset ($85^\circ$) | Deferred to future IMU fusion |
| **Radar Doppler** | `/radar/diagnostics` | `ego_yaw_rate` | Radar point Doppler RANSAC | Noisy when few stationary targets exist ($\sigma \approx 0.15\text{ rad/s}$) | Diagnostic only |

---

## 2. Root Cause of Yaw Rate Jitter

1. **CAN Quantization Noise ($0.01\text{ rad/s}$ Step)**:
   The CAN bus quantizes yaw rate to $0.01\text{ rad/s}$ ($0.57^\circ/\text{s}$). On straight roads, slight road pitch or engine idle causes the raw signal to bounce between $0.00$, $\pm 0.01$, and $\pm 0.02\text{ rad/s}$.
2. **Hard-Step Deadband Discontinuity**:
   `VehicleOrientationTracker` applied a hard deadband threshold:
   ```python
   if abs(ego_yaw_rate_radps) < 0.015:
       filtered_ego_yaw_rate = 0.0
   else:
       filtered_ego_yaw_rate = ego_yaw_rate_radps
   ```
   When raw yaw rate crosses from $0.014 \to 0.016\text{ rad/s}$, the filtered value jumps discontinuously from $0.0 \to 0.016\text{ rad/s}$. At range $x = 25\text{ m}$, this instantly injects a lateral velocity step:
   $$\Delta v_y = \Delta \omega \cdot x = 0.016 \times 25 = 0.40\text{ m/s}$$
   This lateral step perturbs $\text{atan2}(v_y, v_x)$ by several degrees frame-to-frame.
3. **Chassis & Suspension Roll/Pitch Cross-Coupling**:
   When entering a turn, vehicle body roll and pitch transients couple into the yaw gyro before steady-state cornering is reached.

---

## User Review Required

> [!IMPORTANT]
> **Filter Type Selection for Offline Pipeline**:
> 1. **Option A (Recommended) - Zero-Phase Centered Window Smoothing**:
>    Because `EgoMotionProvider` loads the session telemetry upfront, applying a centered, symmetric filter (e.g. 7-sample triangular/Gaussian kernel, window $\approx 0.2\text{s}$) eliminates high-frequency noise with **zero time delay (zero phase lag)**. This guarantees that the peak of the yaw rate curve lines up with camera frames with zero lag.
> 2. **Option B - Causal Exponential Moving Average (IIR)**:
>    $$\omega_k = (1 - \alpha) \omega_{k-1} + \alpha \omega_{\text{raw}, k}$$
>    Matches real-time streaming constraints, but introduces a 50–100 ms phase delay during sharp turn entries.
> 
> *Our recommendation*: Implement Option A for `EgoMotionProvider` session loading, with a soft-knee deadband transition in `VehicleOrientationTracker`.

---

## Proposed Changes

### Vision Geometry & Tracking Pipeline

#### [MODIFY] [`tools/vision/geometry/vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/vehicle_orientation_tracker.py)

1. **Add Low-Pass Yaw Rate Smoothing in `EgoMotionProvider`**:
   Apply centered smoothing over `self.yaw_rates_radps` upon loading:
   ```python
   def _smooth_yaw_rates(self, window_size: int = 7) -> None:
       """
       Applies a zero-phase centered low-pass filter over raw CAN yaw rates.
       Attenuates quantization steps and high-frequency chassis vibration (>3 Hz)
       without introducing phase delay.
       """
       if len(self.yaw_rates_radps) < window_size:
           return
       
       # Normalized triangular/Gaussian kernel
       weights = [1, 2, 3, 4, 3, 2, 1]
       weight_sum = sum(weights)
       half = len(weights) // 2
       
       smoothed = []
       n = len(self.yaw_rates_radps)
       for i in range(n):
           val = 0.0
           w_total = 0.0
           for j, w in enumerate(weights):
               idx = max(0, min(n - 1, i - half + j))
               val += w * self.yaw_rates_radps[idx]
               w_total += w
           smoothed.append(val / w_total)
       
       self.yaw_rates_radps = smoothed
   ```

2. **Implement Continuous Soft-Knee Deadband in `VehicleOrientationTracker`**:
   Replace the hard step discontinuity with a continuous soft-knee transition:
   ```python
   # Soft deadband: zero below inner threshold, smooth linear ramp to outer threshold
   deadband_inner = 0.010  # rad/s (~0.57°/s) - noise floor
   deadband_outer = 0.025  # rad/s (~1.43°/s) - full transition
   abs_yr = abs(ego_yaw_rate_radps)
   
   if abs_yr <= deadband_inner:
       filtered_ego_yaw_rate = 0.0
   elif abs_yr >= deadband_outer:
       filtered_ego_yaw_rate = ego_yaw_rate_radps
   else:
       # Smooth Hermite/linear transition factor between 0.0 and 1.0
       factor = (abs_yr - deadband_inner) / (deadband_outer - deadband_inner)
       filtered_ego_yaw_rate = math.copysign(factor * abs_yr, ego_yaw_rate_radps)
   ```

---

### Unit & Regression Tests

#### [MODIFY] [`scripts/tests/test_vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/tests/test_vehicle_orientation_tracker.py)

1. Add unit test `test_ego_motion_provider_yaw_rate_smoothing()`:
   * Generate a synthetic noisy step/pulse signal with $0.02\text{ rad/s}$ quantization jitter.
   * Verify high-frequency noise variance is reduced by $>70\%$.
   * Verify peak timing has zero phase shift.
2. Add unit test `test_soft_deadband_continuity()`:
   * Sweep `ego_yaw_rate_radps` from $0.0 \to 0.05\text{ rad/s}$ in $0.001$ increments.
   * Verify output is continuous with no discontinuous jumps ($|\Delta y| < 0.002$).

---

## Verification Plan

### Automated Tests
1. **Python Unit Tests**:
   ```powershell
   C:\ProgramData\miniconda3\envs\cv313\python.exe -m unittest scripts/tests/test_vehicle_orientation_tracker.py
   C:\ProgramData\miniconda3\envs\cv313\python.exe -m unittest discover -s scripts/tests
   ```
2. **Android Gradle Unit Tests**:
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
   ```

### Empirical Verification on Session Data
1. Run perception runner on `logs/session_20261023_111120`:
   ```powershell
   C:\ProgramData\miniconda3\envs\cv313\python.exe tools/vision/pipeline/perception_runner.py logs/session_20261023_111120 --from_2d auto --mcap
   ```
2. Inspect vision track heading smoothness:
   * Verify yaw standard deviation during straight-line driving ($20\text{--}35\text{ km/h}$).
   * Inspect turn maneuvers (Turn 2, Turn 7, Track 766, Track 767) to confirm stable orientations without sudden jerks.
3. User visual review in Foxglove Studio using `RoadSense_Cockpit_Layout.json`.
