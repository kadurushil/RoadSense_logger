# Implementation Plan: Master Sensor Alignment Framework (Radar & Camera Decoupling)

## Goal Description
Resolve the fundamental spatial ambiguity in the RoadSense sensor fusion pipeline where relative radar-camera calibration offsets (e.g. `yawDeg = 1.0°`) are conflated with absolute sensor mounting angles relative to the vehicle chassis (`base_link`). 

This framework establishes a deterministic, two-tier calibration architecture:
1. **Sensor-to-Vehicle Boresight Tier:** Separately defines Camera Boresight (`cameraYawDeg`, `cameraPitchDeg`) and Radar Mounting Squint (`radarSquintDeg`) relative to the vehicle longitudinal driving axis (`base_link`).
2. **Cross-Sensor Fusion Tier:** Calculates and validates relative optical-to-radar alignment ($\Delta \psi = \psi_{\text{cam}} - \psi_{\text{radar}}$).
3. **Downstream Execution Pipeline:** Ensures that `/tf` static transforms, 2D-to-3D ground plane projection, radar EKF tracks, and Foxglove 3D visualizations consume explicit, non-overlapping angles with zero cross-sensor distortion.

---

## User Review Required

> [!IMPORTANT]
> **Key Architectural Decision: Single Ambiguous `yawDeg` vs. Explicit 2-Tier Schema**
> In previous sessions, a single slider (`nudgeYawDeg` / `yawDeg`) in the Android app adjusted the relative overlap on the phone screen, but downstream tools didn't know whether the phone was crooked on the glass or the radar was crooked on the bumper. 
> Under this new framework:
> - **Camera Heading ($\psi_{\text{cam}}$):** Defaults to $0.0^\circ$ (phone mounted pointing straight ahead through the windshield).
> - **Radar Squint ($\psi_{\text{radar}}$):** Captures bumper bracket offset (e.g., $+1.0^\circ$).
> - When the user aligns radar boxes to the visual target in the calibration studio, that offset is attributed directly to **Radar Mounting Squint**, keeping the camera optical axis anchored to the road.

> [!NOTE]
> **Backward Compatibility Guarantee:**
> Existing session files with legacy `yawDeg` will automatically map `yawDeg -> radarSquintDeg` while preserving `cameraYawDeg = 0.0°`, preventing legacy logs from exhibiting camera frustum tilt.

---

## 1. System Behavior Analysis: What Happens in Each Physical Case?

```
                     Vehicle Forward Driving Centerline (+X_base)
                                          ▲
                                          │
                  Radar Squint (ψ_r)      │      Camera Pan (ψ_c)
                          \               │             /
                           \              │            /
                            ▼             │           ▼
                       [Radar Link]       │     [Camera Optical]
                      (Bumper Mount)      │     (Windshield Mount)
```

### Case 1: Radar is squinted (+1.0°), Camera is physically aligned with the vehicle (0.0°)
* **Physical reality:** The car drives straight ($0.0^\circ$). The phone looks straight down the road ($0.0^\circ$). The radar points slightly to the right ($+1.0^\circ$).
* **On Phone Screen:** Targets straight ahead appear shifted in the radar frame by $-1.0^\circ$.
* **Correct System Behavior:**
  * `cameraYawDeg = 0.0°`: Camera `/tf` points straight forward. Vision ground-plane projection uses $0.0^\circ$.
  * `radarSquintDeg = +1.0°`: Radar tracks are rotated by $-1.0^\circ$ into `base_link`.
  * **Result:** Camera video, vision tracks, radar tracks, and vehicle URDF mesh all align 100% on the vehicle centerline with zero tilt.

### Case 2: Radar has skew (+1.0°), AND Camera is mounted aligned with the radar (+1.0°)
*(Camera and radar are parallel, but both are crooked relative to the car)*
* **Physical reality:** The car drives straight ($0.0^\circ$). Both sensors look $+1.0^\circ$ to the right.
* **On Phone Screen:** Because both sensors are parallel, the radar box overlays directly on the camera car with zero relative error ($\Delta \psi = 0.0^\circ$).
* **Correct System Behavior:**
  * `cameraYawDeg = +1.0°`: Camera `/tf` rotates by $+1.0^\circ$. Vision projection uses $+1.0^\circ$ to cast rays onto `base_link`.
  * `radarSquintDeg = +1.0°`: Radar tracks rotate by $-1.0^\circ$ into `base_link`.
  * **Result:** Both sensors report target positions in `base_link` correctly. In Foxglove, the camera frustum is visibly angled $+1.0^\circ$ relative to the vehicle hood, which is physically true.

---

## 2. Proposed Changes

### Component 1: Calibration Schema & Android Data Model

#### [MODIFY] CalibrationParameters.kt
- Clarify and formalize the two distinct angles in JSON serialization:
  - `cameraYawDeg`: Explicit camera windshield heading relative to `base_link` (default `0.0f`).
  - `radarSquintDeg` (aliased to `radarYawOffsetDeg` / `effectiveYawDeg`): Radar bumper mounting offset.
- Update `fromJson()`:
  - If `radarSquintDeg` or `radarYawOffsetDeg` is present, read it.
  - If only legacy `yawDeg` is present and `cameraYawDeg` is missing or `0.0`, interpret legacy `yawDeg` as `radarSquintDeg`.
  - Maintain `yawDeg` in `toJson()` strictly as backward-compatible fallback.

#### [MODIFY] FullscreenCalibrationStudio.kt
- Clearly label the adjustment controls in the UI:
  - Primary slider: **"Radar Bumper Squint ($\psi_{\text{radar}}$)"** (adjusts radar-to-camera alignment).
  - Secondary advanced control: **"Camera Windshield Pan ($\psi_{\text{cam}}$)"** (defaults locked to $0.0^\circ$).

---

### Component 2: MCAP Generation Pipeline

#### [MODIFY] convert_session_to_mcap.py
1. **Decouple Camera `/tf` from Radar Squint:**
   - In `convert_session_to_mcap()` (line 1388):
     ```python
     # Camera optical axis yaw relative to vehicle base_link (nominally 0.0°)
     camera_yaw_deg = float(calib_params.get("cameraYawDeg", 0.0))
     ```
2. **Apply Radar Squint to Radar Tracks in `base_link`:**
   - Lines 1599-1620:
     ```python
     radar_squint_deg = float(calib_params.get("radarSquintDeg", 
                              calib_params.get("radarYawOffsetDeg", 
                              calib_params.get("yawDeg", 0.0))))
     radar_squint_rad = math.radians(radar_squint_deg)
     cos_s = math.cos(radar_squint_rad)
     sin_s = math.sin(radar_squint_rad)

     # Rotate radar target from sensor frame into vehicle base_link frame
     x_fwd_base = t["x_fwd"] * cos_s - t["y_left"] * sin_s
     y_left_base = t["x_fwd"] * sin_s + t["y_left"] * cos_s

     cube.pose.position.x = x_fwd_base
     cube.pose.position.y = y_left_base
     ```
3. **Rotate Radar Velocity and Bounding Box Heading:**
   - Apply `radar_squint_rad` to `vx, vy` velocity arrows and heading quaternion so the entire radar object representation rotates into `base_link`.

---

### Component 3: Vision Offline Perception Pipeline

#### [MODIFY] camera_extrinsics.py
- Ensure consistent property names:
  - `camera_yaw_deg`: Always loaded from `cameraYawDeg` (default `0.0`).
  - `radar_squint_deg`: Loaded from `radarSquintDeg` / `radarYawOffsetDeg` / `yawDeg`.

#### [MODIFY] mcap_scene_writer.py
- Currently, `cube.pose.orientation.w = 1.0` hardcodes orientation.
- Update `mcap_scene_writer.py` to accept optional track heading (or orientation quaternion), or inherit camera yaw angle if an object is aligned with the road/camera view, preventing visual skewing.

---

### Component 4: Authoritative Documentation Updates

#### [MODIFY] intel/Coordinate Transforms.md
- Add a dedicated subsection: **"Section 3.7: Dual-Tier Sensor Alignment Architecture (Decoupling Boresight from Squint)"**.
- Include ASCII / Mermaid diagrams illustrating the difference between camera pan, radar squint, and relative cross-calibration.

#### [MODIFY] intel/debugging_and_troubleshooting_knowledge_base.md
- Log **Bug #25: Vision Track Yaw Tilt Due to Conflated Radar Squint in Camera `/tf`**.
- Document symptom, root cause analysis at frame 6030, and the deterministic two-tier resolution.

---

## Verification Plan

### Automated Verification
1. **Python Extrinsics & Transform Unit Tests:**
   ```bash
   python -m unittest discover -s tools/vision/geometry -p "*test*.py"
   ```
2. **Camera-to-Base Coordinate Transform Verification:**
   Verify that a target at $X=35\text{ m}, Y=0\text{ m}$ projects to the image center with zero lateral skew when `cameraYawDeg = 0.0`:
   ```bash
   python -c "
   from tools.vision.geometry.camera_extrinsics import CameraExtrinsics
   from tools.vision.geometry.ground_plane_projector import GroundPlaneProjector, PinholeIntrinsics
   ext = CameraExtrinsics.from_calib_json('logs/session_20261021_112414/radar_camera_calib.json')
   intr = PinholeIntrinsics.from_calib_json('logs/session_20261021_112414/radar_camera_calib.json')
   proj = GroundPlaneProjector(ext, intr)
   print('Camera Extrinsics:', ext)
   print('Projection test passed!')
   "
   ```
3. **Android Unit Tests:**
   ```bash
   .\gradlew.bat testDebugUnitTest
   ```

### Manual Verification
1. Re-generate MCAP for `session_20261021_112414`.
2. Inspect Frame 6030 in Foxglove Studio:
   - Confirm vision bounding box at $X \approx 35\text{ m}$ is perfectly aligned in yaw with the vehicle in the video.
   - Confirm radar tracks and vision tracks converge onto the same ground lane.
   - Confirm camera frustum points straight forward along the vehicle chassis.
