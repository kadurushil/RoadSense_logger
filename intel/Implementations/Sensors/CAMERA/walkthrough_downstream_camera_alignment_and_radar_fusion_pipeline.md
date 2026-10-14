# Walkthrough: Downstream Camera Alignment & Radar Fusion Pipeline Audit

## 1. Executive Summary

This audit verified the complete end-to-end flow of the RoadSense calibration pipeline from Android phone recording to downstream processing:
1. `radar_camera_calib.json` snapshot generation on Android.
2. Foxglove MCAP multimodal translation (`tools/convert_session_to_mcap.py`).
3. Offline 3D geometric vision tracking (`tools/vision/geometry/` & `tools/vision/pipeline/perception_runner.py`).
4. Spatio-temporal association and evaluation against radar ground truth (`tools/vision/eval/spatial_associator.py`).

During the audit, **4 major mathematical errors and systemic shortcomings** were uncovered and resolved. These bugs previously caused radar tracks and vision tracks to diverge or project into invalid space.

---

## 2. Key Findings & Resolved Shortcomings

### Bug 1: Inverted Pitch Rotation Matrix ($R_x$) in Downstream Tools
- **Issue:** In both `convert_session_to_mcap.py` and `ground_plane_projector.py`, the pitch rotation matrix $R_x$ had its row 1 and row 2 off-diagonal sine signs reversed:
  ```python
  # PREVIOUS (INVERTED):
  Rx = np.array([
      [ 1, 0,                0],
      [ 0, math.cos(pitch),  math.sin(pitch)],
      [ 0, -math.sin(pitch), math.cos(pitch)]
  ])
  ```
- **Consequence:** Positive pitch ($+7.0^\circ$, tilting the camera down towards the asphalt) was rotated as negative pitch ($-7.0^\circ$, tilting up into the sky). In `GroundPlaneProjector`, optical axis rays pointed into the sky ($Z_{\text{base}} = +0.122$), triggering horizon rejection. In Foxglove MCAP, the 3D camera frustum was tilted upward away from the road surface.
- **Fix:** Corrected $R_x$ to match `SpatialProjectionEngine.kt`:
  ```python
  # FIXED (MATCHES SPATIAL PROJECTION ENGINE):
  Rx = np.array([
      [ 1, 0,                0],
      [ 0, math.cos(pitch), -math.sin(pitch)],
      [ 0, math.sin(pitch),  math.cos(pitch)]
  ])
  ```
  Now, an optical ray from a downward-tilted camera correctly intersects the ground plane with positive forward distance and $Z=0$.

---

### Bug 2: Hardcoded 1280x720 Coordinates in GroundPlaneProjector
- **Issue:** In `ground_plane_projector.py` lines 108–113:
  ```python
  if self.video_rotation_deg == 180:
      u_raw = 1280.0 - u_center
      v_raw = 720.0 - v_bottom
  ```
- **Consequence:** RoadSense records 1080p (1920x1080) video on the Galaxy M30. For a centered detection $(u=960, v=800)$, this produced $u_{\text{raw}} = 1280 - 960 = 320$ px and $v_{\text{raw}} = 720 - 800 = -80$ px, resulting in negative coordinates and severe lateral position distortion (shifting objects by over 2 meters laterally).
- **Fix:** Dynamic resolution coordinates using `self.intrinsics.width` and `self.intrinsics.height`, defaulting `video_rotation_deg = 0` when frames are already rotated upright by OpenCV.

---

### Bug 3: Missing Intrinsics in `radar_camera_calib.json`
- **Issue:** `CalibrationParameters.kt` previously only serialized extrinsics (`pitchDeg`, `yawDeg`, `setbackM`, etc.). Downstream tools fell back to hardcoded `hfov_deg = 68.0` and 720p dimensions ($f_x = 948.8$).
- **Consequence:** On Galaxy M30 (true 1080p focal length $f_x = 1486.5$ px, $c_x = 960$, $c_y = 540$, $\text{HFOV} = 65.7^\circ$), this caused a $4.4\%$ anamorphic focal scale error and $5-10\%$ metric distance estimation error.
- **Fix:**
  - Added `streamWidth`, `streamHeight`, `fx`, `fy`, `cx`, `cy`, `hfovDeg`, `vfovDeg` to `CalibrationParameters.kt`.
  - Added `CameraIntrinsicsProvider.getStreamIntrinsics` to capture pure unscaled 1080p buffer parameters.
  - Updated `RadarViewModel.startSessionRecording` and `saveBaselineCalibration` to snapshot live stream intrinsics into `radar_camera_calib.json`.
  - Added `PinholeIntrinsics.from_calib_json` in `ground_plane_projector.py` to dynamically load and scale intrinsics.
  - Dynamically populate Foxglove `/camera/calib` from `calib_params`.

---

### Bug 4: Yaw Semantics Decoupling (Camera Yaw vs. Radar Squint)
- **Issue:** In the physical setup, the windshield camera points forward ($\text{yaw}_{\text{cam}} \approx 0.0^\circ$), while `yawDeg` in `radar_camera_calib.json` represents the radar mounting yaw squint on the bumper (e.g. $-1.0^\circ$). Previously:
  - `convert_session_to_mcap.py` rotated `tf_cam` by $-1.0^\circ$ and left `tf_radar` with zero rotation.
  - Radar tracks in `base_link` were not rotated by the $-1.0^\circ$ squint.
  - Vision tracks were skewed laterally by $-1.0^\circ$.
- **Consequence:** Radar tracks and vision tracks were artificially separated by $0.35$ m laterally at 10 m range.
- **Fix:**
  - Added explicit `cameraYawDeg` (default $0.0^\circ$) and `radarYawOffsetDeg` (from `effectiveYawDeg`) to `CalibrationParameters.kt`.
  - Applied `radarYawOffsetDeg` to `tf_radar` (`base_link -> radar_link`) and rotated all `/radar/tracks` bounding boxes and velocity vectors into `base_link`.
  - Used `cameraYawDeg` for `tf_cam` and `GroundPlaneProjector`.
  - Result: Both radar cuboids and vision cuboids now converge onto the identical metric ground position in `base_link`.

---

## 3. Verification & Test Results

### Python Module Self-Tests
```bash
python tools/vision/geometry/camera_extrinsics.py  -> PASSED
python tools/vision/geometry/ground_plane_projector.py -> PASSED (Pixel 960,800 -> X=4.467m, Y=0.0m)
python tools/vision/geometry/box_dimensions.py -> PASSED
python tools/vision/mcap/mcap_scene_writer.py -> PASSED
tools/vision/eval/spatial_associator.py -> PASSED
```

### JVM Unit Tests
```bash
.\gradlew.bat testDebugUnitTest -> BUILD SUCCESSFUL (all 53 tests passed)
.\gradlew.bat assembleDebug -> BUILD SUCCESSFUL (Debug APK generated)
```
