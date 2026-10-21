# Walkthrough: Radar-Camera Calibration, High Azimuth Intrinsics & Optical Transform Alignment

## 1. Executive Summary

We resolved two critical calibration defects introduced in commit `ef4ca6ace75ce31cad286486b40c4465e40475c4`:
1. **High Azimuth Lateral Detachment (Intrinsics Compression)**:
   - Fixed the portrait letterbox transposition bug in `CameraIntrinsicsProvider.getStreamIntrinsics()` that compressed $f_x$ from $1486.5\text{ px}$ to **$836.16\text{ px}$** and shifted $c_x$ to $1196.25\text{ px}$, causing high-azimuth radar tracks to appear pulled inward towards the center.
   - Decoupled stream intrinsics from UI viewports, ensuring full-raster computation matching the selected recording resolution (720p vs 1080p).
2. **Inverted Optical Pitch & Broken `/tf` Transform**:
   - Restored the verified pre-`ef4ca6a` $R_x$ pitch rotation matrix with positive sine in row 1, fixing the inverted camera gaze that pitched the camera up into the sky and displaced radar tracks far below the vehicle in Foxglove Studio.
   - Re-linked calibrated `yawDeg` in `/tf` and eliminated redundant track double-rotation in `base_link`.
   - Implemented dynamic video container resolution detection and legacy profile auto-sanitization on launch.

---

## 2. Changes Made

### 2.1 Android Sensor & Intrinsics Pipeline

- **[CameraIntrinsicsProvider.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/CameraIntrinsicsProvider.kt)**:
  - Replaced the call to `getIntrinsics(..., displayRotation = Surface.ROTATION_0)` with direct, full-raster stream intrinsics calculation.
  - Queries `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` or physical sensor dimensions directly for `streamWidth` and `streamHeight`, guaranteeing square pixels and exact principal centering ($c_x = W/2, c_y = H/2$) without UI scale factors.
- **[CalibrationParameters.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/model/CalibrationParameters.kt)**:
  - In `fromJson()`, added an auto-sanitization guard: if a loaded file exhibits the corrupted signature ($f_x < 1100$ for 1080p, or off-center $c_x$), it preserves all user-tuned physical parameters (`pitchDeg`, `yawDeg`, `setbackM`, etc.) and automatically heals $f_x, f_y, c_x, c_y, HFOV, VFOV$ to true stream values.
- **[CalibrationStorageManager.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/storage/CalibrationStorageManager.kt)**:
  - Persists the healed calibration profile back to disk upon loading, ensuring seamless self-repair of existing phone profiles.
- **[SpatialProjectionEngineTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/fusion/SpatialProjectionEngineTest.kt)**:
  - Added unit tests `testLegacyCorruptedIntrinsicsAutoHealOnLoad` and `test720pStreamIntrinsicsAutoHeal`.

### 2.2 Python Vision Tools & MCAP Conversion Pipeline

- **[convert_session_to_mcap.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)**:
  - Reverted $R_x$ in `compute_camera_optical_tf` to the pre-`ef4ca6a` verified matrix:
    ```python
    Rx = np.array([
        [ 1, 0,                0],
        [ 0, math.cos(pitch),  math.sin(pitch)],
        [ 0, -math.sin(pitch), math.cos(pitch)]
    ], dtype=float)
    ```
  - Aligned `tf_radar` with vehicle frame `base_link` (`rotation.w = 1.0`).
  - Linked calibrated `yawDeg` to `compute_camera_optical_tf`.
  - Removed double rotation on radar tracks: emitted directly in `base_link` coordinates ($t["x_fwd"], t["y_left"]$).
  - Auto-detected MP4 video container resolution and dynamically scaled `/camera/calib` intrinsics if stream resolution differed from calibration.
  - Auto-healed corrupted legacy session JSONs during session conversion.
- **[ground_plane_projector.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/ground_plane_projector.py)**:
  - Aligned $R_x$ pitch matrix with the restored formulation.
  - Added auto-heal for legacy intrinsics in `from_calib_json`.

---

## 3. Verification & Validation

### 3.1 Android Unit Tests & Build
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
- **Result:** `BUILD SUCCESSFUL` across all 36 tasks. All projection, solver, and auto-heal unit tests passed.

### 3.2 Python Vision Projector Self-Test
```powershell
python tools/vision/geometry/ground_plane_projector.py
```
- **Result:** `Pixel (960, 800) -> Forward X: 28.134m, Left Y: 0.0m, Range: 28.134m - Status: PASSED`.

### 3.3 MCAP Conversion & TF Validation
```powershell
C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe tools/convert_session_to_mcap.py logs/session_20261021_091957 --no-video
```
- **Output:**
  - `[!] Healed corrupted legacy intrinsics in radar_camera_calib.json: fx was 836.2 -> restored to 1486.8 (stream 1920x1080)`
  - `TF base_link -> radar_link: trans: (0.000, 0.000, 0.950), rot: (0.0, 0.0, 0.0, 1.0)`
  - `TF base_link -> camera_optical: trans: (-0.300, 0.000, 1.450)`
  - `Calib: 1920x1080, fx=1486.8, fy=1486.8, cx=960.0, cy=540.0`
- **Projection check:** A target at 15 m ahead on the road projects to $u = 933.7\text{ px}, v = 787.8\text{ px}$ (in the lower road area on the car bumper, exactly where expected).

### 3.4 Device Installation
```powershell
adb -s 32001991d42c86d9 install -r app/build/outputs/apk/debug/app-debug.apk
```
- **Result:** `Performing Streamed Install -> Success`.
