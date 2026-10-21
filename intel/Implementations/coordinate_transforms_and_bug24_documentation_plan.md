# Implementation Plan: Sensor Fusion Coordinate Transforms & Bug #24 Documentation

Consolidate all scattered sensor fusion coordinate frame specifications into a single, definitive, human-readable master document (`intel/Coordinate Transforms.md`), provide a high-density AI cheat sheet (`intel/COORDINATE_SYSTEMS_AI_CHEATSHEET.md`), and document Bug #24 in `intel/debugging_and_troubleshooting_knowledge_base.md`.

---

## 1. Goal Description

### Problems Addressed:
1. **Scattered Coordinate Knowledge**: Information on coordinate axes, unit conventions, and transformations is currently dispersed across multiple files (`Sensor fusion basics.md`, `RADAR_PITCH_CALIBRATION_VIA_IMU.md`, `Pixel_to_World_Coordinate_Transformations_and_Depth_Resolution_Guide.md`, `convert_session_to_mcap.py`, and Kotlin source files). This dispersion previously caused confusion regarding axis directions (e.g. TI radar RFU vs. vehicle FLU vs. camera RDF) and led to the pitch sign bug.
2. **Post-Mortem Documentation**: The recent bug where optical pitch rotation in `convert_session_to_mcap.py` was inverted ($R_x(-P)$), causing a $+10.5^\circ$ discrepancy ($2 \times 5.4^\circ = 10.8^\circ$) in Foxglove Studio, needs to be permanently archived as **Bug #24** in `intel/debugging_and_troubleshooting_knowledge_base.md`.

---

## 2. Proposed Changes

### Component 1: Troubleshooting Knowledge Base

#### [MODIFY] [intel/debugging_and_troubleshooting_knowledge_base.md](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/debugging_and_troubleshooting_knowledge_base.md)
- Add **Bug #24: Optical Pitch Sign Inversion ($R_x(-P)$) in MCAP `/tf` Transform & $2\times$ Pitch Discrepancy ($+10.5^\circ$) in Foxglove 3D Visualization**:
  - **Symptoms**: Radar tracks appearing ~180 px too high in Foxglove Studio 3D frustum / Image Panel; user needing $+10.5^\circ$ manual pitch offset in `/camera/calib_tuned` script to force alignment.
  - **Root Cause**: Mathematical sign inversion in $R_x$ row 1/row 2 causing optical axis to point UP ($+5.42^\circ$) into the sky instead of DOWN ($-5.42^\circ$) toward asphalt, resulting in an exact $2 \times \text{pitch} = 10.83^\circ$ total angular error.
  - **Solution & Fix**: Aligned $R_x$ in `convert_session_to_mcap.py` and `ground_plane_projector.py` with `SpatialProjectionEngine.kt` ($y_2 = y_1 \cos P - z_1 \sin P, z_2 = y_1 \sin P + z_1 \cos P$), and verified regenerated MCAP `/tf` elevation is $-5.418^\circ$.
  - **Core Engineering Rule #24**: *Verify Optical $R_x$ Against Upright World Boresight Vector ($Z_{\text{base}} = -\sin\theta$)*.

---

### Component 2: Master Coordinate Transforms Guide (Human-Readable)

#### [NEW] [intel/Coordinate Transforms.md](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Coordinate%20Transforms.md)
A comprehensive, authoritative, diagram-rich master document covering:
1. **Executive Architecture & Frame Hierarchy**:
   - Mermaid diagram showing the complete ROS TF tree: `base_link` $\to$ `radar_link`, `camera_optical`, `imu_link`, `velodyne_frame` (URDF root).
2. **Exhaustive Sensor-by-Sensor Coordinate Specifications**:
   - **Vehicle Frame (`base_link`)**: ISO 8855 / ROS REP-103 FLU (+X Forward, +Y Left, +Z Up). Origin at front bumper/bonnet radar mount.
   - **TI mmWave Radar Sensor Frame (`radar_sensor` / `radar_link`)**: TI Custom MRR RFU (+X Right, +Y Forward, +Z Up). Heading clockwise positive. Exact conversion formulas to `base_link`.
   - **Camera Optical Frame (`camera_optical`)**: OpenCV/ROS REP-103 RDF (+X Right, +Y Down, +Z Forward).
   - **Smartphone IMU Device Frame (`imu_device`)**: Android Sensor HAL (+X Right, +Y Top, +Z Screen Out). Reverse landscape ($270^\circ$) remapping to vehicle.
   - **2D Image / Viewfinder Pixel Frame (`image_pixels`)**: $(u, v)$ from Top-Left $(0, 0)$ to $(W, H)$.
   - **CAN Dynamics Frame (`can_dynamics`)**: Speed, yaw rate, wheel speeds.
   - **GNSS Geodetic / Local Frame (`gnss_enu`)**: WGS84 $\to$ Local ENU.
3. **Mathematical Transformation Pipeline & Closed-Form Matrices**:
   - Base Optical Transform: $R_0 = \begin{bmatrix} 0 & -1 & 0 \\ 0 & 0 & -1 \\ 1 & 0 & 0 \end{bmatrix}$
   - Yaw ($R_y$), Pitch ($R_x$), Roll ($R_z$) order and exact matrices.
   - Intrinsic Matrix $K$, unscaled 720p/1080p equations, and FOV relationships.
   - Closed-form forward projection ($P_{\text{base}} \to u, v$) and inverse ray-tracing to ground plane ($Z=0$).
4. **Phone Reverse Landscape Mount ($180^\circ$) & Zero-Copy Rules**:
   - Why the physical sensor buffer is inverted.
   - How Foxglove `/tf` folds $180^\circ$ into optical roll ($\phi_{\text{effective}} = \phi_{\text{calib}} + 180^\circ$) for zero-copy Demuxing without GPU transcoding.
   - Android TextureView / Compose rotation handling.
5. **Master Axis Cross-Reference Matrix**:
   - A single unified comparative lookup table mapping every axis across all 7 coordinate systems.

---

### Component 3: AI Quick-Reference Cheat Sheet (AI Consumption)

#### [NEW] [intel/COORDINATE_SYSTEMS_AI_CHEATSHEET.md](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/COORDINATE_SYSTEMS_AI_CHEATSHEET.md)
A dense, high-efficiency cheat sheet designed for instant recall by AI agents and quick developer lookup:
- **One-Second Lookups**: Direct tables for "+X / +Y / +Z" across all sensors.
- **Signs of Angles**: Pitch (positive = down), Yaw (positive = right or CCW), Roll (positive = CW).
- **Code Symbol Map**: Kotlin and Python file paths and line ranges for every transform.
- **Common Gotchas**: Sign pitfalls, matrix order, and radar RFU $\to$ vehicle FLU formulas.

---

## 3. Verification Plan

### Automated Consistency Checks
1. Validate that mathematical matrix equations across `Coordinate Transforms.md`, `COORDINATE_SYSTEMS_AI_CHEATSHEET.md`, `convert_session_to_mcap.py`, and `SpatialProjectionEngine.kt` are 100% identical.
2. Run a Python test script verifying:
   - Forward projection of a test target at $10\text{ m}$ matches between documentation formulas and implementation code.
   - $R_x(P)$ produces $Z_{\text{base}} = -\sin(P)$.

### Manual Review
- Inspect the generated markdown files in the IDE to ensure diagram legibility, table formatting, and clear math rendering.
