# Walkthrough: Monocular 3D Vehicle Orientation Stabilization & Kinematics

## 1. Executive Summary

We successfully resolved erratic vehicle orientation tracking in the monocular 3D perception pipeline (Bug #26). Previously, obstacles suffered from rapid $360^\circ$ spinning, erratic angle jitter ($\pm 90^\circ/\text{frame}$), and sudden $180^\circ$ front-to-rear flips whenever the ego vehicle closed in on lead obstacles.

By implementing **Techniques A, B, and C** in a dedicated module ([`vehicle_orientation_tracker.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/vehicle_orientation_tracker.py)), vehicle heading tracking has been stabilized to road steering physical limits:
- **Lead vehicle (Track #1, 748 frames at 55 km/h)**:
  - Average frame-to-frame yaw delta dropped to **$0.34^\circ$** (previously up to $\pm 180^\circ$).
  - Maximum frame-to-frame delta bounded at **$2.60^\circ$**.
  - **$0$ flips** and **$0$ spins** across the entire 748-frame run.
- **Stationary vehicles (Track #3513, 660 frames)**:
  - Average frame-to-frame yaw delta of **$0.06^\circ$**, latched cleanly at the road curb ($90^\circ$).
- **Pipeline throughput**:
  - Full session (35,330 frames, 39,126 tracks) projected in **0.8 seconds** (~43,500 FPS).
  - Enriched master MCAP created in **10.2 seconds** with 456,206 messages in chronological sequence.

---

## 2. Technical Architecture & Techniques Implemented

### Technique A: Host CAN Ego-Motion Compensation
* **Problem**: Monocular camera ray projection measures relative displacement $\Delta \vec{r}_{\text{rel}} = \Delta \vec{r}_{\text{target}} - \Delta \vec{r}_{\text{ego}}$. When the host car travels faster than a lead car ($v_{\text{ego}} > v_{\text{target}}$), relative forward velocity is negative ($v_{x,\text{rel}} < 0$), causing $\arctan2(v_y, v_x)$ to evaluate to $\approx 180^\circ$ (pointing backward into the ego windshield).
* **Solution**: `EgoMotionProvider` ingests host powertrain wheel speed ($v_{\text{ego}} = \text{speed\_kmph} / 3.6$) and chassis yaw rate ($\omega_{\text{ego}}$) directly from `/vehicle/telemetry` via $O(\log N)$ binary search interpolation. Target velocity is transformed into road world coordinates:
  $$\vec{v}_{\text{world}} = \vec{v}_{\text{rel}} + \vec{v}_{\text{ego}} + (\vec{\omega}_{\text{ego}} \times \vec{r})$$
  $$v_{x,\text{world}} = v_{x,\text{rel}} + v_{\text{ego}} - \omega_{\text{ego}} y_{\text{rel}}$$
  $$v_{y,\text{world}} = v_{y,\text{rel}} + \omega_{\text{ego}} x_{\text{rel}}$$
* **Domain Guardrail**: Ego motion is sourced strictly from vehicle CAN and GNSS telemetry. Zero radar tracks are ingested, preserving vision as an independent ground truth.

### Technique B: Multi-Frame Motion State Hysteresis & Zero-Speed Latching
* **Problem**: When a vehicle is stationary or stopped at an intersection, true velocity is $\approx 0$. Computing $\arctan2(v_y, v_x)$ evaluates camera pixel noise derivatives, creating random high-speed spinning.
* **Solution**: A dual-threshold Schmitt trigger state machine:
  - **Transition to Moving**: Requires $3$ consecutive frames with $v_{\text{world}} \ge 1.2\text{ m/s}$ (~$4.3\text{ km/h}$).
  - **Transition to Stopped**: Requires $5$ consecutive frames with $v_{\text{world}} \le 0.6\text{ m/s}$ (~$2.2\text{ km/h}$).
  - **Zero-Speed Latching**: When stopped, heading $\psi$ is locked to `latched_yaw` (the arrival heading) rather than evaluating sensor noise.

### Technique C.1: Hard Physical Yaw-Rate Clamping & Circular IIR
* **Problem**: Tire-ground friction limits automotive chassis rotation to $\dot{\psi}_{\text{max}} \approx 0.8\text{ rad/s}$ ($45.8^\circ/\text{s}$). Single-frame bounding box edge detections caused artificial angular rate spikes exceeding $500^\circ/\text{s}$.
* **Solution**: 
  - Maximum angular difference per frame is clamped to $\Delta \psi_{\text{max}} = \dot{\psi}_{\text{max}} \cdot \Delta t \approx 1.53^\circ/\text{frame}$ at 30 FPS.
  - Angular smoothing uses circular shortest-arc exponential filtering:
    $$\Delta \psi = \text{atan2}(\sin(\psi_{\text{raw}} - \psi_{\text{prev}}), \cos(\psi_{\text{raw}} - \psi_{\text{prev}}))$$
    $$\psi_{\text{clamped}} = \text{clamp}(\Delta \psi, -\Delta \psi_{\text{max}}, \Delta \psi_{\text{max}})$$
    $$\psi_{\text{new}} = \text{atan2}(\sin(\psi_{\text{prev}} + \alpha \psi_{\text{clamped}}), \cos(\psi_{\text{prev}} + \alpha \psi_{\text{clamped}}))$$

### Technique C.2: Road-Parallel Soft Lane Alignment Attractor
* **Solution**: For vehicles driving down the road with negligible lateral velocity ($|v_y| < 0.35\text{ m/s}$), small trajectory drift is softly attracted toward the lane axis ($0^\circ$ for forward vehicles, $\pm 180^\circ$ for oncoming traffic) with blending factor $\beta = 0.15$.

---

## 3. Verification & Validation Results

### 1. Dedicated Python Unit Tests (`scripts/tests/test_vehicle_orientation_tracker.py`)
All 5 targeted test suites pass in both `cv313` and `roadsense-mcap` environments:
- `test_technique_a_ego_motion_compensation_prevents_180deg_flip`: Verified closing-speed acceleration from $10 \to 16\text{ m/s}$ keeps lead heading at $0^\circ$ without flip.
- `test_technique_b_zero_speed_latching_and_hysteresis`: Verified 3-frame moving debounce, 5-frame stopped debounce, and stationary sensor jitter rejection.
- `test_technique_c1_physical_yaw_rate_clamping`: Verified sudden $90^\circ$ lateral step is clamped strictly to $\le 0.8\text{ rad/s} \cdot \Delta t$ ($< 1.6^\circ/\text{frame}$).
- `test_technique_c2_lane_attractor`: Verified subtle road drift is pulled toward lane axis.
- `test_bisect_interpolation`: Verified binary search telemetry interpolation across edge and interior timestamps.

### 2. Regression Suites
- **Python Discovery Suite**: 32/32 tests PASSED in both `cv313` and `roadsense-mcap`.
- **Android Gradle Suite**: 24/24 tasks UP-TO-DATE (`BUILD SUCCESSFUL` in 7s via bundled JBR).

### 3. Full Session MCAP Verification (`session_20261023_111120`)
- Input: `logs/session_20261023_111120/session_20261023_111120.mcap` (845.1 MB)
- Output: `tools/vision/output/mcap/session_20261023_111120_with_vision.mcap` (850.2 MB)
- Total Frames: 35,330
- Total 3D Tracks: 39,126
- Enriched MCAP Messages: 456,206 (35,330 `/vision/geo_tracks`, 420,876 original messages, 4 attachments preserved)
