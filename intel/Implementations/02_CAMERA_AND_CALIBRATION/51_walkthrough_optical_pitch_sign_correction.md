# Walkthrough: Optical Pitch Sign Correction & MCAP Regeneration

We have corrected the pitch rotation matrix sign in the Python conversion and projection tools and successfully regenerated the Foxglove MCAP for session `20261021_112414`.

---

## 1. Summary of Changes

### Python Tooling
1. **[convert_session_to_mcap.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py#L204-L208)**:
   - Corrected $R_x$ in `compute_camera_optical_tf` so positive pitch tilts the camera optical boresight **downward toward the asphalt** ($Z_{\text{base}} = -\sin(P)$), matching Android's [SpatialProjectionEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt#L69-L74):
   ```python
   Rx = np.array([
       [ 1, 0,                0],
       [ 0, math.cos(pitch), -math.sin(pitch)],
       [ 0, math.sin(pitch),  math.cos(pitch)]
   ], dtype=float)
   ```
2. **[ground_plane_projector.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/ground_plane_projector.py#L116-L120)**:
   - Applied the identical $R_x$ matrix fix to ensure offline ground plane and footprint projections match on-device and Foxglove projections.

---

## 2. Validation & Inspection Results

### MCAP `/tf` FrameTransform Verification
We inspected the regenerated `session_20261021_112414.mcap` file directly:
- **Transform**: `base_link` $\rightarrow$ `camera_optical`
- **Translation**: $x = -0.300\text{ m},\ y = 0.000\text{ m},\ z = 1.350\text{ m}$
- **Quaternion**: $(q_x = 0.5266,\ q_y = 0.5195,\ q_z = 0.4707,\ q_w = 0.4809)$
- **Optical Axis in `base_link`**:
  $$\vec{V} = [0.9954,\ -0.0174,\ \mathbf{-0.0944}]$$
- **Optical Elevation**: $\mathbf{-5.418^\circ}$ (**tilt DOWN toward the asphalt**, matching the $5.417^\circ$ IMU sync measurement).
- **Camera Intrinsics**: $1280 \times 720$, $f_x = f_y = 991.00\text{ px}$, $c_x = 640.0\text{ px}$, $c_y = 360.0\text{ px}$.

### Target Projection Coordinates (Without Any Manual Offset):

| Distance Ahead | Camera Frame $P_c$ | Projected Image $(u, v)$ | Status |
| :---: | :---: | :---: | :---: |
| **5.0 m** | $[0.18, 0.10, 5.29]$ | $(657.2, 379.1)$ | Asphalt / Near-field |
| **10.0 m** | $[0.18, 0.57, 10.29]$ | $(657.1, 415.3)$ | Vehicles / Mid-range |
| **20.0 m** | $[0.18, 1.52, 20.24]$ | $(657.1, 434.3)$ | Lead vehicle targets |
| **50.0 m** | $[0.18, 4.35, 50.10]$ | $(657.1, 446.1)$ | Far horizon |

---

## 3. How to View in Foxglove Studio

1. Open `logs/session_20261021_112414/session_20261021_112414.mcap` in Foxglove Studio.
2. In the Image Panel settings:
   - Change the Calibration topic to `/camera/calib` (or if using your user script `/camera/calib_tuned`, set `PITCH_OFFSET_DEG = 0`).
3. Notice that the 3D radar bounding boxes now project directly onto the vehicles on the road without needing any $+10.5^\circ$ compensation.
