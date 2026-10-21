# Implementation Plan: Optical Pitch Sign Correction & MCAP Regeneration

Fix the optical pitch rotation matrix in `convert_session_to_mcap.py` and `ground_plane_projector.py` to correctly tilt the camera boresight downward toward the asphalt, eliminating the need for the $+10.5^\circ$ manual offset in Foxglove Studio, and regenerate `session_20261021_112414.mcap`.

---

## 1. Problem Summary & Root Cause

In session `session_20261021_112414`, the user noted that manually applying **$+10.5^\circ$** pitch in Foxglove Studio via `/camera/calib_tuned` aligned the radar tracks with physical vehicles.

### Mathematical Root Cause
1. **$10.5^\circ \approx 2 \times 5.417^\circ = 10.83^\circ$**:
   - The user used "Sync Pitch from IMU", storing `pitchDeg = 5.4174°` into `radar_camera_calib.json`.
   - In `tools/convert_session_to_mcap.py`, `compute_camera_optical_tf` had:
     ```python
     Rx = np.array([
         [ 1, 0,                0],
         [ 0, math.cos(pitch),  math.sin(pitch)],
         [ 0, -math.sin(pitch), math.cos(pitch)]
     ], dtype=float)
     ```
   - When multiplied by $R_0 = \begin{bmatrix} 0 & -1 & 0 \\ 0 & 0 & -1 \\ 1 & 0 & 0 \end{bmatrix}$ and transformed to `base_link`, the camera optical axis ray $Z_c = [0, 0, 1]^T$ evaluated to:
     $$Z_{\text{base}} = \mathbf{+\sin(pitch)} = +0.0944 \implies \text{Elevation} = \mathbf{+5.418^\circ}\ (\text{UP into the sky})$$
   - Because the camera was pointed **$+5.42^\circ$ UP** instead of **$-5.42^\circ$ DOWN**, the `/tf` transform had an angular error of:
     $$\Delta \theta = (+5.417^\circ) - (-5.417^\circ) = \mathbf{+10.83^\circ}$$
2. **Screen Effect**:
   - 3D radar targets on the road ($Z = 0.95\text{ m}$) projected **180–190 pixels too high** ($v \approx 227\text{ px}$ instead of $v \approx 415\text{ px}$).
   - The user's Foxglove script added $\Delta c_y = f_y \cdot \tan(+10.5^\circ) \approx 176\text{ px}$, artificially shifting the boxes down to $v \approx 403\text{ px}$.
3. **Android App vs. Python Inconsistency**:
   - The Android app ([SpatialProjectionEngine.kt:L73-74](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt#L73-L74)) already uses the correct matrix:
     ```kotlin
     val y2 = y1 * cosP - z1 * sinP
     val z2 = y1 * sinP + z1 * cosP
     ```
     which represents $R_x(P) = \begin{bmatrix} 1 & 0 & 0 \\ 0 & \cos P & -\sin P \\ 0 & \sin P & \cos P \end{bmatrix}$ (downward tilt).
   - Only the Python tooling (`convert_session_to_mcap.py` and `ground_plane_projector.py`) had the inverted matrix.

---

## 2. Proposed Changes

### Component: Python Conversion & Projection Tools

#### [MODIFY] [convert_session_to_mcap.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)
- Correct $R_x$ in `compute_camera_optical_tf` (lines 204–208):
  ```diff
      # Pitch (tilt around camera X axis, pointing right: positive pitch tilts downward toward asphalt)
      Rx = np.array([
          [ 1, 0,                0],
  -       [ 0, math.cos(pitch),  math.sin(pitch)],
  -       [ 0, -math.sin(pitch), math.cos(pitch)]
  +       [ 0, math.cos(pitch), -math.sin(pitch)],
  +       [ 0, math.sin(pitch),  math.cos(pitch)]
      ], dtype=float)
  ```

#### [MODIFY] [ground_plane_projector.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/ground_plane_projector.py)
- Correct $R_x$ in `GroundPlaneProjector.__init__` (lines 116–120):
  ```diff
          # Pitch tilt around camera X-axis (right: positive tilts downward toward asphalt)
          Rx = np.array([
              [ 1, 0,                0],
  -           [ 0, math.cos(pitch),  math.sin(pitch)],
  -           [ 0, -math.sin(pitch), math.cos(pitch)]
  +           [ 0, math.cos(pitch), -math.sin(pitch)],
  +           [ 0, math.sin(pitch),  math.cos(pitch)]
          ], dtype=float)
  ```

---

## 3. MCAP Regeneration & Verification Plan

### Step 1: Regeneration
1. Backup existing MCAP:
   ```powershell
   Copy-Item logs\session_20261021_112414\session_20261021_112414.mcap logs\session_20261021_112414\session_20261021_112414.mcap.bak
   ```
2. Re-run `convert_session_to_mcap.py` using the dedicated conda environment:
   ```powershell
   C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe tools\convert_session_to_mcap.py logs\session_20261021_112414
   ```

### Step 2: Automated Verification
1. Inspect the regenerated MCAP `/tf` transform:
   - Verify `base_link -> camera_optical` quaternion points downward:
     $$Z_{\text{base}} = -\sin(5.417^\circ) = -0.0944 \implies \text{Elevation} = \mathbf{-5.418^\circ}$$
2. Re-run projection simulator:
   - Confirm target at $10.0\text{ m}$ projects to $v = 415.4\text{ px}$ natively with default $c_y = 360\text{ px}$.

### Step 3: Manual Verification in Foxglove Studio
1. Open the regenerated `session_20261021_112414.mcap` in Foxglove Studio.
2. In the Image Panel, set the Calibration topic back to `/camera/calib` (or set `PITCH_OFFSET_DEG = 0` in the script).
3. Confirm that 3D radar bounding boxes now align cleanly with physical vehicles on the road without any manual offset.
