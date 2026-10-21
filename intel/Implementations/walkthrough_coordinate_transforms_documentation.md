# Walkthrough: Master Coordinate Transforms Documentation & Bug #24 Post-Mortem

We have completed the comprehensive documentation of RoadSense sensor fusion coordinate frames, transformation mathematics, and the Bug #24 post-mortem.

---

## 1. Summary of Changes

### 1. Updated Troubleshooting Knowledge Base
- **File:** [`intel/debugging_and_troubleshooting_knowledge_base.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/debugging_and_troubleshooting_knowledge_base.md)
- **Added Bug #24**: Documented the optical pitch sign inversion ($R_x(-P)$), the $2\times\theta$ mathematical identity ($2 \times 5.4^\circ = 10.83^\circ \approx 10.5^\circ$), why the Foxglove tuning script was previously needed, and the verified fix.
- **Added Core Engineering Rule #24**: *Verify Optical $R_x$ Against Upright World Boresight Vector ($Z_{\text{base}} = -\sin\theta$)*.

### 2. Created Authoritative Master Reference Guide
- **File:** [`intel/Coordinate Transforms.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Coordinate%20Transforms.md)
- **Content:**
  - **ROS TF Architecture Tree**: Complete hierarchy from `base_link` down to `radar_link`, `camera_optical`, `imu_link`, `velodyne_frame`, and `screen_pixels`.
  - **7 Sensor Frame Specifications**:
    1. Vehicle Base Frame (`base_link`, ISO 8855 FLU: Forward, Left, Up)
    2. TI mmWave Radar Frame (`radar_link`, TI Native RFU: Right, Forward, Up)
    3. Camera Optical Frame (`camera_optical`, OpenCV/ROS RDF: Right, Down, Forward)
    4. Smartphone IMU Frame (`imu_device`, Android HAL Portrait & Reverse Landscape $270^\circ$)
    5. Screen Pixel Frame (`screen_pixels`, Raster Column/Row 2D)
    6. Vehicle CAN Dynamics Frame (Speed, Yaw Rate, Wheel Speeds)
    7. Global GNSS Frame (WGS84 $\to$ Local ENU)
  - **Full Matrix Mathematics**: Explicit $3\times3$ equations for $R_0, R_y, R_x, R_z$, forward pinhole projection, and inverse road plane intersection.
  - **Reverse Landscape Zero-Copy Demuxing Architecture**: Mathematical folding of $180^\circ$ into `/tf` optical roll.
  - **Master Axis Cross-Reference Matrix**: Comprehensive lookup table comparing every axis and angle convention across all sensors.

### 3. Created AI Agent Quick Reference Cheat Sheet
- **File:** [`intel/COORDINATE_SYSTEMS_AI_CHEATSHEET.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/COORDINATE_SYSTEMS_AI_CHEATSHEET.md)
- **Content:**
  - High-density lookup tables for instant recall of axes, angle signs, and matrix equations.
  - One-line invariant self-test for AI models to verify optical pitch matrices before making code edits.
  - Canonical source file index linking code symbols directly to Kotlin and Python files.

---

## 2. Verification Results
- Ran an automated matrix test validating that the documented formulas yield an optical boresight elevation of **$-5.418^\circ$ (downward tilt)** in `base_link`, matching both Android's `SpatialProjectionEngine.kt` and `tools/convert_session_to_mcap.py`.
