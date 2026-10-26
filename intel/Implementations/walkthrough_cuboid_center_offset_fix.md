# Walkthrough: Phase 1 — 3D Cuboid Center Offset Fix

## 1. What Was Implemented

### The Core Fix
In [tools/vision/geometry/box_dimensions.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/box_dimensions.py):
- **Problem**: Previously, `compute_3d_box()` set `center.x_fwd_m = x_contact_m`. In Foxglove Studio, 3D cuboid primitives are positioned at their **geometric volumetric center**. Because the cuboid extends $\pm L/2$ along $X$, setting the center to the contact patch caused the nearest face of the 3D box to overhang forward towards the camera by $L/2 \approx 1.5\text{ to }2.0\text{ meters}$, producing an artificially oversized and misaligned bounding box on the camera view.
- **Solution**: Updated `BoxDimensions.compute_3d_box()`:
  $$\text{center.x} = x_{\text{contact}} + \frac{L}{2}$$
  $$\text{center.y} = y_{\text{contact}}$$
  $$\text{center.z} = \frac{H}{2}$$
  - The near face of the 3D box now lands **precisely on the tire contact patch** ($X_{\text{near}} = \text{center.x} - L/2 = x_{\text{contact}}$).
  - The cuboid length ($L$) extends forward into the vehicle body.
  - Added an explicit `"contact_patch"` dictionary to the output payload alongside `"center"` so downstream consumers retain access to both the volumetric center and the ground-contact point.

### Perception Runner Updates
In [tools/vision/pipeline/perception_runner.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/perception_runner.py):
- Updated both `run()` and `run_from_2d_detections()` to output both `position_3d` (volumetric center) and `contact_patch` (ground contact).
- Retained `range_m` as the range to the nearest visible surface of the vehicle.

---

## 2. Verification Results

### Automated Unit Tests
Created [scripts/tests/test_vision_box_alignment.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/tests/test_vision_box_alignment.py):
- Verified that for optical sizing and class priors, the volumetric center is offset forward by exactly $+L/2$.
- Verified that $\text{center.x} - L/2 == x_{\text{contact}}$.
- Verified that $\text{center.z} - H/2 == 0.0$ (box bottom rests on asphalt).
```
..
----------------------------------------------------------------------
Ran 2 tests in 0.000s

OK
```

### Full Test Suite Discovery (`scripts/tests`)
```
Ran 21 tests in 0.020s

OK
```

### Android Gradle Unit Tests (`gradlew testDebugUnitTest`)
```
BUILD SUCCESSFUL in 8s
24 actionable tasks: 24 up-to-date
```

---

## 3. Instructions to Regenerate MCAP via Central Dashboard GUI

Please follow these steps in your browser to regenerate the enriched MCAP and visually inspect Track #5915:

1. **Launch the Web Dashboard**:
   - If not already running, double-click `tools/sync_and_process_logs.bat` or run:
     ```powershell
     python tools/roadsense_web_server.py
     ```
   - Open [http://localhost:8080/](http://localhost:8080/) in your web browser.

2. **Navigate to the 👁️ 3D Vision Tab**:
   - Click the **👁️ 3D Vision** tab in the left-hand navigation bar.

3. **Configure the 3D Vision Runner (Card 1)**:
   - **Target Session**: Select `session_20261023_111120`.
   - **Execution Mode**:
     - *Recommended (Fastest)*: Select **Instant 3D Re-projection** (`--from_2d auto-detects existing 2D tracks, 80k+ FPS`). The runner will automatically detect your existing 2D tracks (`session_20261023_111120_vision_tracks.json`), recalculate the 3D ground projection with the $+L/2$ offset, and enrich the MCAP in **~2 seconds** without re-running YOLO!
     - *Alternative*: Select **Full Session** to re-run YOLOv26 inference on `camera_video.mp4` across all 35,332 frames.
   - **Options**: Leave `[x] Auto-enrich Foxglove MCAP (--mcap)` and `[x] Camera Optical 3D Sizing` checked.

4. **Click `▶ Run 3D Vision Pipeline`**:
   - Watch the live progress bar in the dashboard.
   - When complete, you will see the generated file path:
     `tools/vision/output/mcap/session_20261023_111120_with_vision.mcap`

5. **Visual Inspection in Foxglove Studio**:
   - Open the newly generated `session_20261023_111120_with_vision.mcap` in Foxglove Studio.
   - Observe Track **#5915** (the white Bajaj Qute around 6.5m - 10m).
   - Verify that the magenta 3D box no longer overhangs forward into the foreground, but now rests its near face directly on the vehicle's tires and neatly encloses the car body.

---

## 4. Lateral Position Shift Experiment & Revert (Documented in Bug #26)

### The Experiment
An experimental closed-form solver was evaluated to compensate for perspective convergence of the solid 3D cuboid across depth for vehicles traveling parallel to the host vehicle ($0^\circ$ yaw). In straight-line tracking (e.g. Frame 24828 Track #5915), equating the 2D YOLO $[x_1, x_2]$ edges to near and far corners bounded the rendered cuboid within 1 to 2 pixels.

### Why It Was Reverted
In turning scenarios (vehicles turning at intersections, curves, or cross traffic):
- The zero-yaw ($0^\circ$) assumption breaks down. The 2D bounding box width no longer captures vehicle width ($W$), but vehicle length ($L$) or an aspect mixture ($L |\sin\psi| + W |\cos\psi|$).
- Extreme 2D bounding pixels no longer map to the assumed near/far corner depths.
- This resulted in severe box squeezing (collapsing to $0.4\text{ m}$ width clamp limits) and violent lateral jumps during vehicle turns.

### Current Architecture
- The experimental perspective lateral centering was **reverted**.
- Lateral positioning remains anchored to the contact patch ground ray ($Y_{\text{center}} = Y_{\text{contact}}$).
- The full post-mortem analysis and future guidance (requiring a true 3D heading / yaw estimator $\psi$) is preserved in [intel/debugging_and_troubleshooting_knowledge_base.md](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/debugging_and_troubleshooting_knowledge_base.md#bug-26-perspective-compensated-lateral-centering-failure-in-turning-scenarios--the-zero-yaw-assumption-breakdown).
