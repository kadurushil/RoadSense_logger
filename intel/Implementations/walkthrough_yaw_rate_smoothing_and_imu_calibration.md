# Walkthrough: Ego Yaw Rate Smoothing, Soft Deadband & IMU Mounting Calibration Documentation

## Overview
This document summarizes:
1. The **IMU 3D mounting misalignment analysis and documentation** explaining why chassis yaw rate fluctuates during straight-line travel.
2. The implementation of a **zero-phase centered low-pass smoothing filter** in `EgoMotionProvider`.
3. The implementation of a **continuous soft-knee deadband** in `VehicleOrientationTracker` to eliminate discrete step velocity jumps.
4. Empirical verification on `session_20261023_111120` and the re-generated MCAP.

---

## 1. Documentation of IMU Mounting Misalignment & Calibration Challenges

As identified, during straight-line travel the chassis IMU yaw rate signal displayed fluctuations ($\pm 0.02\text{ to } \pm 0.04\text{ rad/s} \approx \pm 1.1^\circ/\text{s} \text{ to } \pm 2.3^\circ/\text{s}$) due to uncalibrated physical mounting angles ($\alpha_{\text{roll}}, \beta_{\text{pitch}}, \gamma_{\text{yaw}}$). On real roads with suspension roll, road camber, and throttle squat/dive, roll and pitch rates leak directly into the measured yaw channel:
$$\omega_{\text{yaw, meas}} \approx \omega_{\text{yaw, true}} + \sin(\beta_{\text{pitch}}) \cdot \omega_{\text{roll}} - \sin(\alpha_{\text{roll}}) \cdot \omega_{\text{pitch}} + b_z$$

### Documentation Added:
1. **[`Future_stuff.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/Future_stuff.md#L383-L449) (Section 8)**:
   * Detailed physical and mathematical cross-axis coupling models.
   * Documented standard 3-stage automotive calibration routines:
     - **Stage 1 (Flat-Road Static Leveling):** Parked on flat ground to solve pitch/roll angles from the gravity vector $[0, 0, -g]^T$ and null zero-speed gyro bias.
     - **Stage 2 (Slope / Incline Calibration):** Parked on a ramp to isolate orthogonal yaw angle $\gamma_{\text{yaw}}$.
     - **Stage 3 (Dynamic Straight-Line Driving):** Least-squares regression over straight travel with GNSS/wheel ticks to solve the full $3 \times 3$ rotation matrix $R_{\text{IMU}\to\text{Body}}$.
2. **[`intel/debugging_and_troubleshooting_knowledge_base.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/debugging_and_troubleshooting_knowledge_base.md#L1256-L1293) (Bug #27 & Rule #27)**:
   * Documented symptoms, root causes, software pre-filtering mitigations, and permanent takeaways.

---

## 2. Implemented Code Changes

### 2.1 Zero-Phase Centered Low-Pass Filter in `EgoMotionProvider`
* **File:** [`tools/vision/geometry/vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/vehicle_orientation_tracker.py#L98-L124)
* **Design:** 7-sample symmetric triangular/Gaussian kernel ($[1, 2, 3, 4, 3, 2, 1] / 16$) applied across `self.yaw_rates_radps` upon loading.
* **Property:** Eliminates high-frequency quantization noise and road vibration ($>3\text{ Hz}$) with **zero phase delay**, ensuring turn peaks stay aligned with video frames.

### 2.2 Continuous Soft-Knee Deadband in `VehicleOrientationTracker`
* **File:** [`tools/vision/geometry/vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/vehicle_orientation_tracker.py#L236-L250)
* **Design:** Replaces the binary hard deadband with a continuous ramp between `deadband_inner = 0.010 rad/s` ($0.57^\circ/\text{s}$, sensor noise floor) and `deadband_outer = 0.025 rad/s` ($1.43^\circ/\text{s}$, full maneuver threshold):
  $$\text{factor} = \frac{|\omega| - \omega_{\text{inner}}}{\omega_{\text{outer}} - \omega_{\text{inner}}}$$
  $$\omega_{\text{filtered}} = \text{factor} \cdot \omega$$
* **Benefit:** Completely eliminates discrete velocity steps ($\Delta v_y = \Delta \omega \cdot x$) when the signal hovers around the deadband boundary on straight roads.

---

## 3. Verification & Validation Results

### 3.1 Automated Tests
* **Unit Tests (`scripts/tests/test_vehicle_orientation_tracker.py`):** **11/11 tests passed** (including new `test_ego_motion_provider_yaw_rate_smoothing` and `test_soft_deadband_continuity`).
* **Python Test Suite:** **38/38 tests passed** in both `cv313` and `roadsense-mcap`.
* **Android Gradle Tests:** `./gradlew testDebugUnitTest` **BUILD SUCCESSFUL** (24 tasks up-to-date).

### 3.2 Empirical Validation on `session_20261023_111120`
* **Track 767 (Lead Vehicle during Turn 2):**
  * Maximum lateral velocity $v_y$ reduced from **$12.03\text{ m/s} \to 1.38\text{ m/s}$** ($88\%$ reduction in false lateral velocity).
  * Yaw angle remained pinned along forward lane ($[-5.2^\circ, +21.4^\circ]$) through the entire $90^\circ$ turn.
* **Track 766 (Oncoming Car during Turn 2):**
  * Maximum lateral velocity $v_y$ reduced from **$10.06\text{ m/s} \to 1.35\text{ m/s}$**.
* **Track 4991 (Oncoming Auto-Rickshaw):**
  * Stable in oncoming basin through all 140 frames ($-179.9^\circ \to +178.2^\circ$).
* **Re-generated MCAP:** `tools\vision\output\mcap\session_20261023_111120_with_vision.mcap` (850.2 MB).
