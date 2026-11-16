# Walkthrough: Ego Turning Model & Oncoming Vehicle Orientation Fix

## Overview
This document summarizes the investigation, mathematical modeling, implementation, and empirical validation of the **Host Vehicle Ego Turning Model** and **Oncoming Vehicle Orientation Stabilization** in RoadSense's offline 3D geometric vision pipeline.

---

## 1. Problem Statement & Failure Analysis

### 1.1 The Track #4991 "Diagonal Crawl Trap"
In `session_20261023_111120`, Track #4991 (an oncoming Bajaj auto-rickshaw traveling at $-5.5\text{ km/h}$ / $-1.53\text{ m/s}$) approached from range $35.6\text{ m} \to 3.5\text{ m}$. While 3D bounding box trajectory and depth estimation were accurate, its 3D heading remained pinned diagonally between $-17^\circ$ and $-37^\circ$ throughout its 140 frames (5.0s) instead of facing oncoming traffic ($-165^\circ \to -180^\circ$).

### 1.2 Identified Root Causes
1. **The Double-Dampening Crawl Trap**:
   The rate limiter clamped angular change per frame:
   $$\text{delta\_clamped} = \text{clamp}(\text{diff}, -\Delta \psi_{\text{max}}, \Delta \psi_{\text{max}})$$
   where $\Delta \psi_{\text{max}} = \omega_{\text{max}} \cdot \Delta t = 0.8\text{ rad/s} \times 0.0333\text{ s} \approx 1.51^\circ/\text{frame}$.
   However, the filtered angle was updated as:
   $$\psi_{k} = \psi_{k-1} + \alpha \cdot \text{delta\_clamped}$$
   With $\alpha = 0.25$, the effective maximum slew rate was throttled down to **$0.38^\circ/\text{frame}$ ($11.5^\circ/\text{s}$)**. To rotate $180^\circ$ at $11.5^\circ/\text{s}$ required **473 frames (15.7 seconds)**! Because Track 4991 was only visible for 140 frames, it could only advance by $53^\circ$, leaving it stuck in a diagonal pose indefinitely.

2. **Near-Zero Velocity Singularity**:
   At low oncoming ground speed ($v_{x,\text{world}} \approx -1.5\text{ m/s}$), sub-pixel pitch jitter at range 25–35m induced instantaneous velocity swings of $\pm 2\text{ m/s}$. Whenever $v_x$ crossed zero, $\text{atan2}(v_y, v_x)$ flipped between $0^\circ$ and $180^\circ$. The low-pass filter averaged these diametric swings into the diagonal range ($-20^\circ \to -40^\circ$).

3. **Absence of Host Vehicle Ego Turning Model**:
   When the host vehicle steered or navigated a curve, stationary roadside objects (parked cars, utility poles) swept across the camera field of view, causing their relative heading in `base_link` to appear to change or drift.

---

## 2. Implemented Architecture & Mathematical Solutions

All algorithms were integrated into [`tools/vision/geometry/vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/vehicle_orientation_tracker.py):

### 2.1 Kinematic Ego Turning Model
In the ISO-8855 `base_link` frame ($+X$ Forward, $+Y$ Left, $+Z$ Up), counter-clockwise rotation is positive. When the host vehicle turns at yaw rate $\omega_{\text{ego}}$, an external stationary object's relative coordinate frame rotates in the opposite direction:
$$\Delta \psi_{\text{apparent}} = -\omega_{\text{ego}} \cdot \Delta t$$

The tracker propagates prior heading states for all visible tracks at each frame:
```python
ego_rot = filtered_ego_yaw_rate * frame_dt
latched_yaw = atan2(sin(latched_yaw - ego_rot), cos(latched_yaw - ego_rot))
smoothed_yaw = atan2(sin(smoothed_yaw - ego_rot), cos(smoothed_yaw - ego_rot))
```

### 2.2 IMU Gyro Deadband
To prevent chassis and engine idle vibration from polluting stationary object orientations:
$$\omega_{\text{filtered}} = \begin{cases} 0.0 & \text{if } |\omega_{\text{ego}}| < 0.015\text{ rad/s} \ (\approx 0.85^\circ/\text{s}) \\ \text{clamp}(\omega_{\text{ego}}, -1.0, 1.0) & \text{otherwise} \end{cases}$$

### 2.3 Window-Averaged Secant Ego-Motion Compensation
Secant velocity is evaluated over a robust 12-frame window ($\Delta t \approx 0.4\text{ s}$). To prevent transition artifacts when ego yaw rate changes, ego speed and yaw rate are averaged across the exact history window:
$$\vec{v}_{\text{rel}} = \frac{\vec{r}_{\text{new}} - \vec{r}_{\text{old}}}{\Delta t}$$
$$v_{x,\text{world}} = v_{x,\text{rel}} + \bar{v}_{\text{ego}} - (\bar{\omega}_{\text{ego}} \cdot \bar{y})$$
$$v_{y,\text{world}} = v_{y,\text{rel}} + (\bar{\omega}_{\text{ego}} \cdot \bar{x})$$

### 2.4 Heading Basin Hysteresis & Instant $180^\circ$ Disambiguation
Tracks are classified into `forward` or `oncoming` basins based on $v_{x,\text{world}} < -0.5\text{ m/s}$ with a 4-frame confirmation debounce. When an oncoming track is detected with its heading trapped in the forward hemisphere ($|\psi| < 70^\circ$, $|\Delta \psi| > 110^\circ$), the filter snaps directly across the circle to the true oncoming basin rather than crawling through diagonal space.

### 2.5 Decoupled Rate Limiter
The rate limiter decouples micro-smoothing from physical vehicle turning:
$$\text{step} = \begin{cases} \alpha \cdot \Delta \psi & \text{if } |\Delta \psi| \le \Delta \psi_{\text{max}} \quad (\text{micro-jitter smoothing}) \\ \operatorname{sgn}(\Delta \psi) \cdot \Delta \psi_{\text{max}} & \text{if } |\Delta \psi| > \Delta \psi_{\text{max}} \quad (\text{full physical turn slew at } 57.3^\circ/\text{s}) \end{cases}$$

---

## 3. Verification & Validation

### 3.1 Unit Test Results
All 9 unit tests in [`scripts/tests/test_vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/tests/test_vehicle_orientation_tracker.py) pass:
- `test_technique_a_ego_motion_compensation_prevents_180deg_flip`: Pass
- `test_technique_a_high_speed_overtaking`: Pass
- `test_technique_b_motion_hysteresis_and_latching`: Pass
- `test_technique_c1_physical_yaw_rate_clamping`: Pass
- `test_technique_c2_lane_attractor`: Pass
- `test_technique_turning_model_stationary_track`: Pass ($+0.1000\text{ rad}$ rotation verified)
- `test_technique_turning_model_imu_deadband`: Pass (zero drift under chassis vibration)
- `test_oncoming_vehicle_basin_disambiguation`: Pass (snaps to $>150^\circ$)
- `test_decoupled_rate_clamping_allows_real_turns`: Pass (allows full physical turn slew)

Full Python test suite across both environments:
- `cv313`: 36/36 tests passed.
- `roadsense-mcap`: 36/36 tests passed.

Gradle JVM unit tests:
- `gradlew.bat testDebugUnitTest`: BUILD SUCCESSFUL (24/24 tasks up-to-date).

### 3.2 Empirical Verification on Track #4991
Re-running the geometric perception runner on `session_20261023_111120`:
- **Frame 0–12**: Vehicle initialized at far range ($35\text{ m}$).
- **Frame 13**: Instant basin disambiguation triggered. Heading snapped cleanly from $-5.4^\circ \to -176.6^\circ$ in a single frame.
- **Frames 14–140**: Heading remained locked into the oncoming orientation ($-160^\circ \to -180^\circ / +178^\circ$).
- **Zero Diagonal Freezing**: Diagonal crawl is completely eliminated.
- **Enriched MCAP Created**: `tools/vision/output/mcap/session_20261023_111120_with_vision.mcap` (850.2 MB).
