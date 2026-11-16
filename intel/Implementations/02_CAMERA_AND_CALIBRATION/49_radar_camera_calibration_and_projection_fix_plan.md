# Implementation Plan: Radar-Camera Calibration, Intrinsics & Optical Transform Alignment

## Goal Description
Resolve calibration and projection defects introduced in commit `ef4ca6ace75ce31cad286486b40c4465e40475c4`:
1. **Camera Intrinsics Distortion at High Azimuth Angles**: Fix the corrupted $f_x$, $f_y$, $c_x$, $c_y$, and swapped FOVs generated when `CameraIntrinsicsProvider.getStreamIntrinsics()` passed `Surface.ROTATION_0`, which caused letterbox transposition down to $f_x = 836.16\text{ px}$.
2. **Resolution Decoupling & Dynamic Scaling**: Decouple camera stream intrinsics from UI viewports and bind intrinsics dynamically to the active user recording resolution (720p vs 1080p).
3. **Inverted Pitch & TF Optical Transform Disconnect in MCAP/Foxglove**: Restore the verified pre-`ef4ca6a` optical pitch rotation matrix ($R_x$), re-link active calibrated `yawDeg` to camera optical TF, and remove double rotation on radar tracks, restoring alignment with `calib_tuned`.
4. **Automated Legacy Sanitization**: Auto-heal corrupted $836.16\text{ px}$ calibration profiles both on Android app launch and in Python MCAP conversion, preserving tuned pitch and yaw angles.

---

## User Review Required

> [!IMPORTANT]
> **Why $f_x$ became 836.16 px (The Root Cause Explained Simply):**  
> It was NOT a confusion between 720p and 1080p.  
> In Android, phones are naturally **Portrait** (`ROTATION_0`). The camera video stream is **Landscape** ($1920\times 1080$).  
> When `CameraIntrinsicsProvider.getStreamIntrinsics()` passed `displayRotation = Surface.ROTATION_0`, the viewport calculator assumed the stream was being rendered inside a portrait display. It rotated the stream bounds to $1080\times 1920$ and applied a `FIT` letterbox scale factor of:
> $$\text{scale} = \frac{1080}{1920} = 0.5625$$
> It then multiplied the true 1080p focal length ($1486.5\text{ px}$) by $0.5625$, shrinking $f_x$ down to **$836.16\text{ px}$** and shifting $c_x$ to $1196.25\text{ px}$!  
> Because $u = c_x + f_x \frac{X}{Z}$, this ~42% compression severely shrunk horizontal projection angles, pulling high-azimuth radar tracks inwards toward the vehicle center.

> [!IMPORTANT]
> **Resolution Decoupling (720p vs 1080p):**  
> We will ensure:
> 1. `SessionManager` inspects the active `CameraEngine.selectedResolution` (whether 720p or 1080p) rather than defaulting to hardcoded dimensions.
> 2. `getStreamIntrinsics` calculates pure unscaled pinhole math directly:
>    - For 1080p ($1920\times 1080$): $f_x = f_y \approx 1486.5\text{ px}$, $c_x = 960.0$, $c_y = 540.0$, $HFOV = 65.7^\circ$.
>    - For 720p ($1280\times 720$): $f_x = f_y \approx 991.0\text{ px}$ (or $948.8\text{ px}$ at $68^\circ$), $c_x = 640.0$, $c_y = 360.0$.
> 3. `tools/convert_session_to_mcap.py` inspects the actual MP4 video container dimensions. If the video is 720p and the calibration JSON is 1080p (or vice-versa), it automatically scales $f_x, f_y, c_x, c_y$ by the resolution ratio $\frac{\text{video\_width}}{\text{calib\_width}}$ so `/camera/calib` always matches `/camera/video` 1:1.

> [!WARNING]
> **Restoring the Verified Pre-`ef4ca6a` $R_x$ Pitch Matrix:**  
> In `tools/convert_session_to_mcap.py` and `tools/vision/geometry/ground_plane_projector.py`, $R_x$ will be restored to:
> ```python
> Rx = np.array([
>     [ 1, 0,                0],
>     [ 0, math.cos(pitch),  math.sin(pitch)],
>     [ 0, -math.sin(pitch), math.cos(pitch)]
> ], dtype=float)
> ```
> In camera optical frame ($+X$ right, $+Y$ down, $+Z$ forward), a downward camera pitch toward the asphalt means forward road points ($Z > 0$) appear lower in the image ($+Y$). The positive sine in row 1 ($Y_c = Y_0 \cos p + Z_0 \sin p$) adds forward distance to downward vertical elevation, correctly placing cars on the road surface. Commit `ef4ca6a` inverted this to $-\sin p$, which made $Y_c$ negative, pointing the camera into the sky and pushing tracks below the car.

---

## Detailed Technical Explanations

### Question 1: Why True 1080p Stream Intrinsics? Was It 720p Skew?
* **Why it failed**: It was not calculating for 720p. The phone was actually recording at 1080p ($1920\times 1080$ as confirmed in `session_debug.log` line 42: `MediaRecorder setup: 1920x1080 @ 30fps`). But `CameraIntrinsicsProvider.getStreamIntrinsics()` passed `Surface.ROTATION_0`. The UI viewport logic interpreted `ROTATION_0` as a portrait orientation, transposed the landscape stream to $1080\times 1920$, and shrunk it by $\frac{1080}{1920} = 0.5625$ to fit in a letterbox.
* **The resolution contract**:
  - If user selects **1080p**: $W=1920, H=1080 \implies f_x = 1486.5, c_x = 960.0, c_y = 540.0$.
  - If user selects **720p**: $W=1280, H=720 \implies f_x = 991.0, c_x = 640.0, c_y = 360.0$.
  The new calculation removes all viewport and rotation transformations for video recording, calculating pure sensor buffer intrinsics from the chosen resolution.

---

### Question 2: What Does "Sanitize Legacy Files on Load" Mean?
* Existing sessions recorded on the phone and the active `radar_camera_calib.json` on the device currently store the corrupted values:
  ```json
  "fx": 836.159, "fy": 836.159, "cx": 1196.25, "cy": 303.75, "hfovDeg": 39.92
  ```
  While retaining user-tuned physical mount angles:
  ```json
  "pitchDeg": 5.9118, "yawDeg": 1.0000, "setbackM": 0.30
  ```
* If we only modify new calibration routines, existing saved profiles on disk would remain broken.
* **Auto-Sanitization on Load**:
  When `CalibrationParameters.fromJson()` runs (both in Android and Python):
  1. It checks: Is $f_x < 1100$ for a 1080p profile, or is $c_x \ne \text{width} / 2$?
  2. If detected, it **preserves** all user-tuned physical values (`pitchDeg`, `yawDeg`, `rollDeg`, `setbackM`, `heightOffsetM`, `radarHeightM`), but **recomputes** $f_x, f_y, c_x, c_y, HFOV, VFOV$ based on the stream resolution.
  3. It writes the healed JSON back to disk. Existing user calibrations and past sessions are repaired instantly without requiring manual recalibration.

---

### Question 3: What Other Logic Changes in Point 3?
Aside from reverting $R_x$ to the verified pre-`ef4ca6a` formulation:
1. **Re-link Calibrated `yawDeg` in `/tf`**:
   In `ef4ca6a`, `convert_session_to_mcap.py` hardcoded `camera_yaw_deg = float(calib_params.get("cameraYawDeg", 0.0))` where `cameraYawDeg` was not set, ignoring the calibrated `yawDeg = 1.00°`. We update `/tf` to use `calib_params.get("yawDeg", 0.0)`.
2. **Remove Radar Track Double-Rotation**:
   In commit `ef4ca6a`, `convert_session_to_mcap.py` rotated `tf_radar` by `radar_yaw_rad` in `/tf` AND also manually rotated `x_fwd` and `y_left` of each track by `radar_yaw_rad` before adding them to `entity.cubes`. This rotated tracks twice in Foxglove. Tracks are defined in `base_link` and will not be double-rotated.
3. **Dynamic Resolution Matching in Python Exporter**:
   `convert_session_to_mcap.py` will inspect the actual MP4 video resolution. If the video is 720p, `/camera/calib` is emitted with $W=1280, H=720, f_x=948.8\text{--}991, c_x=640, c_y=360$. If 1080p, it emits $W=1920, H=1080, f_x=1486.5, c_x=960, c_y=540$. Switching resolutions will never desync calibration from video.

---

## Proposed Changes

### Component 1: Android Camera Intrinsics Pipeline

#### [MODIFY] [CameraIntrinsicsProvider.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/CameraIntrinsicsProvider.kt)
- Update `getStreamIntrinsics(context, cameraId, streamWidth, streamHeight)` to compute pure pinhole intrinsics for the recording resolution without passing through `CameraViewportTransform` letterbox scaling.
- Set $c_x = \text{streamWidth} / 2.0$, $c_y = \text{streamHeight} / 2.0$, $f_x = f_y = \frac{\text{streamWidth}/2.0}{\tan(HFOV / 2.0)}$.

#### [MODIFY] [CalibrationParameters.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/model/CalibrationParameters.kt)
- In `fromJson()`, detect legacy corrupted intrinsics ($f_x < 1100$ for 1080p or $c_x \ne \text{width}/2$) and auto-repair $f_x, f_y, c_x, c_y, HFOV, VFOV$ while keeping `pitchDeg` ($5.91^\circ$) and `yawDeg` ($1.00^\circ$).

#### [MODIFY] [CalibrationStorage.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/CalibrationStorage.kt)
- Update default baseline profile values to standard 1080p unscaled stream values.

---

### Component 2: MCAP Export & Vision Geometry Tools

#### [MODIFY] [convert_session_to_mcap.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)
1. **Restore $R_x$ Optical Pitch Matrix**: Revert `Rx` in `compute_camera_optical_tf` to the pre-`ef4ca6a` verified matrix.
2. **Re-link Calibrated Yaw**: Pass `calib_params.get("yawDeg", 0.0)` into `compute_camera_optical_tf`.
3. **Remove Track Double Rotation**: Ensure track positions and `/tf` coordinate transforms operate cleanly in `base_link`.
4. **Dynamic Video Resolution Adaptation**: Read video resolution from container, dynamically scale intrinsics if video resolution differs from calibration profile.
5. **Auto-Sanitize Legacy JSON in Python**: If `radar_camera_calib.json` in session folder has $f_x = 836.16$, auto-heal it during conversion.

#### [MODIFY] [ground_plane_projector.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/ground_plane_projector.py)
- Align `GroundPlaneProjector` $R_x$ matrix and intrinsics loader with the restored formulation.

---

## Verification Plan

### Automated Tests
1. **Spatial Projection Unit Tests**:
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
   ```
2. **Python Vision Geometry Validation**:
   ```powershell
   python tools/vision/geometry/ground_plane_projector.py
   ```
3. **MCAP Export Test on Bench Session**:
   ```powershell
   python tools/convert_session_to_mcap.py logs/session_20261021_091957 --no-video
   ```

### Manual Verification
1. Inspect generated `radar_camera_calib.json` to verify:
   - `streamWidth`: 1920, `streamHeight`: 1080
   - `fx`: ~1486.5, `fy`: ~1486.5
   - `cx`: 960.0, `cy`: 540.0
   - `hfovDeg`: 65.7°, `vfovDeg`: 39.8°
2. Launch Foxglove with newly converted MCAP and verify that radar tracks snap directly onto vehicle bounding boxes without the `calib_tuned` script override.

