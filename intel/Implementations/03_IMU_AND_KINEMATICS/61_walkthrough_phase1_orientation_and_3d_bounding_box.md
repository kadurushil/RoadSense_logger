# Walkthrough: Phase 1 — Vehicle Orientation Estimation & 3D Bounding Box Projection

## 1. Executive Summary

Phase 1 has been successfully implemented and validated. The monocular vision pipeline now **dynamically estimates vehicle heading (yaw $\psi$) and orientation quaternions** purely from camera data—without utilizing any radar signals, strictly maintaining the independence of vision tracks as a ground truth to validate radar algorithms.

### Key Problems Solved
1. **Parked Sideways Vehicles (Motorcycle #3513)**:
   - **Before**: 2D bounding box width (~2.0m) was directly mapped to transverse width ($Y$), and prior length (~1.9m) was added forward along $X$ at fixed $0^\circ$ yaw. This produced an oversized $2.0\text{m} \times 2.0\text{m}$ cubic block protruding into empty space, rotated $90^\circ$ away from the vehicle.
   - **After**: Broadside aspect ratio disambiguation recognizes that optical span $S > 1.25\text{m}$ captures the motorcycle's **length** ($L = 2.0\text{m}$). The width is constrained to handlebar width ($W = 0.8\text{m}$), and orientation is rotated $90^\circ$ along the curb ($q = [0, 0, 0.7071, 0.7071]$). The 3D box neatly bounds the slender motorcycle parked along the curb.
2. **Compact Vehicle Misclassifications (Lead Vehicle #3045 / Bajaj Qute)**:
   - **Before**: Standard COCO YOLO misclassified the boxy upright rear of the Bajaj Qute as a `truck`, causing the pipeline to assign an $8.5\text{m}$ commercial truck prior length.
   - **After**: Compact vehicle sanity guard detects that a vehicle at $2.7\text{m}$ with optical width $< 1.9\text{m}$ and height $< 2.2\text{m}$ is physically a quadricycle/compact vehicle, clamping length to $2.75\text{m}$ ($W = 1.31\text{m}, H = 1.65\text{m}$).
3. **Turning & Moving Vehicles (Curved Roads & Intersections)**:
   - **Before**: Fixed $0^\circ$ yaw orientation in Foxglove `SceneUpdate`.
   - **After**: The new `KinematicHeadingTracker` calculates ground velocity vectors $\vec{v} = [\dot{X}, \dot{Y}]$ from multi-object track histories, calculates heading yaw $\psi = \arctan2(\dot{Y}, \dot{X})$, and emits real-time orientation quaternions $[0, 0, \sin(\psi/2), \cos(\psi/2)]$ into Foxglove Studio.

---

## 2. Mathematical Formulation & Architecture

```
                  2D YOLO Bounding Box [x1, y1, x2, y2]
                                   │
                                   ▼
             Optical Span Across Image: S = (w_px / fx) * X
                                   │
               ┌───────────────────┴───────────────────┐
               ▼                                       ▼
      [ Kinematic Tracker ]                   [ Aspect Ratio Guard ]
   Is track moving (v >= 0.6 m/s)?       Is S > class width prior?
   Yes: ψ = arctan2(dY, dX)               Broadside: ψ = ±90°, L = S, W = Prior_W
   No: Fallback to Aspect Guard           Head-on:   ψ = 0°,   W = S, L = Prior_L
               └───────────────────┬───────────────────┘
                                   │
                                   ▼
            Depth Offset: d = (L/2)*|cos(ψ)| + (W/2)*|sin(ψ)|
            Center:       X_center = X_contact + d
            Orientation:  q = [0, 0, sin(ψ/2), cos(ψ/2)]
                                   │
                                   ▼
                      Foxglove 3D SceneUpdate
```

### Depth Offset from Contact Patch to Volumetric Center
For any vehicle of length $L$ and width $W$ oriented at yaw angle $\psi$:
$$d_{\text{depth}} = \frac{L}{2} |\cos\psi| + \frac{W}{2} |\sin\psi|$$
- **Head-on ($\psi = 0^\circ$)**: $d_{\text{depth}} = L / 2$. Near face touches contact patch.
- **Side-on ($\psi = 90^\circ$)**: $d_{\text{depth}} = W / 2$. Near side panel touches contact patch.
- **Continuous across all angles**: Smoothly interpolates during turns.

---

## 3. Changes Made

| Component | File | Description |
|---|---|---|
| **Kinematic Tracker** | [kinematic_tracker.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/kinematic_tracker.py) | Created `KinematicHeadingTracker` maintaining sliding temporal ground coordinate history per track ID, computing velocity $(\dot{X}, \dot{Y})$ and yaw $\psi$ with circular exponential smoothing. |
| **Geometry & Sizing** | [box_dimensions.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/box_dimensions.py) | Added Indian/compact priors (`quadricycle`, `qute`, `autorickshaw`). Implemented compact vehicle sanity guard (rejecting 8.5m truck priors for small vehicles), broadside aspect ratio disambiguation, and orientation quaternion computation. |
| **Perception Runner** | [perception_runner.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/perception_runner.py) | Integrated `KinematicHeadingTracker` into both `run()` and `run_from_2d_detections()`, outputting `orientation`, `yaw_rad`, `yaw_deg`, and `speed_mps`. |
| **MCAP Scene Writer** | [mcap_scene_writer.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/mcap/mcap_scene_writer.py) | Serializes orientation quaternions $(q_x, q_y, q_z, q_w)$ to Foxglove's `cube.pose.orientation`. Formats speed into labels when vehicles are in motion. |
| **Unit Test Suite** | [test_vision_box_alignment.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/tests/test_vision_box_alignment.py) | Added unit tests covering parked broadside motorcycles, Qute compact guards, kinematic tracking, and 45° turning cuboid geometry. |

---

## 4. Verification Results

### Automated Unit Tests
Command:
```powershell
C:\ProgramData\miniconda3\envs\cv313\python.exe -m unittest scripts/tests/test_vision_box_alignment.py
```
Output:
```
........
----------------------------------------------------------------------
Ran 8 tests in 1.973s

OK
```

### Full Regression Discovery (`scripts/tests`)
Command:
```powershell
C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe -m unittest discover -s scripts/tests
```
Output:
```
Ran 27 tests in 0.020s

OK (skipped=2)
```

---

## 5. Instructions to Regenerate MCAP via Central Dashboard GUI

Please follow these steps to regenerate the enriched MCAP and visually inspect the results in Foxglove Studio:

1. **Launch the Web Dashboard**:
   - Double-click `tools/sync_and_process_logs.bat` or run:
     ```powershell
     python tools/roadsense_web_server.py
     ```
   - Open [http://localhost:8080/](http://localhost:8080/) in your browser.

2. **Navigate to the 👁️ 3D Vision Tab**:
   - Click the **👁️ 3D Vision** tab in the left-hand navigation bar.

3. **Configure the 3D Vision Runner (Card 1)**:
   - **Target Session**: Select `session_20261023_111120`.
   - **Execution Mode**:
     - Select **Instant 3D Re-projection** (`--from_2d auto`). The runner will automatically detect your 2D tracks, apply the kinematic heading tracker + broadside aspect disambiguation, and produce the enriched MCAP in **~2 to 3 seconds**!
   - **Options**: Ensure `[x] Auto-enrich Foxglove MCAP (--mcap)` is checked.

4. **Click `▶ Run 3D Vision Pipeline`**:
   - Watch the live progress bar.
   - When complete, the enriched MCAP is ready at:
     `tools/vision/output/mcap/session_20261023_111120_with_vision.mcap`

5. **Visual Inspection in Foxglove Studio**:
   - Open `session_20261023_111120_with_vision.mcap` in Foxglove Studio.
   - **Inspect Motorcycle #3513**: Observe that the box is oriented sideways along the curb ($90^\circ$ yaw) with slender $0.8\text{m}$ width and $2.0\text{m}$ length, rather than an oversized $2.0\text{m}$ square block!
   - **Inspect Lead Vehicle #3045 (Bajaj Qute)**: Observe that the 3D box neatly fits the compact vehicle body ($L \approx 2.75\text{m}$) instead of swallowing it with an $8.5\text{m}$ truck box!
   - **Inspect Turning Vehicles**: Observe that as vehicles round curves or turn, their 3D bounding boxes rotate smoothly with their direction of travel.
