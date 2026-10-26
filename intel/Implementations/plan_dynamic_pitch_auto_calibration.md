# Plan: 3D Cuboid Center Offset Alignment & Dynamic Pitch Auto-Calibration

## 1. Goal & Executive Summary

### The Problem Identified in Track #5915
When reviewing vision tracks in Foxglove Studio (e.g., Track **#5915** in `session_20261023_111120`), the 3D bounding box (magenta cuboid) appears misaligned and significantly oversized compared to the vehicle in the camera image:
1. **The 3D Cuboid Center Offset (Immediate Root Cause)**:
   In Foxglove (`foxglove.SceneUpdate`), `cube.pose.position` specifies the **volumetric geometric center** of the 3D box. However, our pipeline passes the forward distance of the **tire contact patch** ($X_{\text{contact}}$) directly as `position.x`. 
   Consequently, the 3D box extends symmetrically along $X$ as $[X_{\text{contact}} - L/2, X_{\text{contact}} + L/2]$. This causes the near face of the box to overhang **$L/2 \approx 1.5\text{ to }2.0\text{ meters}$ closer to the ego-vehicle than the vehicle's actual contact patch**, making the projected 3D box project much lower and wider on screen than the vehicle itself!
2. **Camera Pitch Drift & Sensitivity**:
   Inverse Perspective Mapping (IPM) estimates distance via the ground plane intersection. A pitch offset of even $0.5^\circ$ introduces longitudinal errors that scale quadratically with distance ($dX/d\theta \approx -X^2/H$).

### Phased Execution Strategy
Per user direction:
- **Phase 1 (Immediate Focus)**: Fix the 3D cuboid center offset in `BoxDimensions` and `McapSceneWriter`. The user will visually verify this fix in Foxglove by regenerating the MCAP through the Central Web Dashboard GUI.
- **Phase 2 (Subsequent Diagnostics)**: Develop `tools/vision/eval/analyze_track_pitch_dynamics.py` (strictly within `tools/vision/eval/` to maintain modular separation) to inspect individual track timelines.
- **Phase 3 (Dynamic Pitch Solver)**: Implement `tools/vision/geometry/dynamic_pitch_solver.py` for automated pitch regression and radar fusion.

---

## 2. Phase 1: 3D Cuboid Center Offset Fix (Immediate Task)

### Mathematical Derivation
Let:
- $(u_{\text{center}}, v_{\text{bottom}})$: 2D bounding box bottom-center (tire contact patch).
- $(X_{\text{contact}}, Y_{\text{contact}}, 0)$: 3D road surface intersection point in `base_link` coordinates (+X forward, +Y left, +Z up).
- $(L, W, H)$: Physical vehicle extent (Length, Width, Height) in meters.

Currently:
$$\text{center.x} = X_{\text{contact}} \implies \text{Near Face } X = X_{\text{contact}} - \frac{L}{2} \quad (\text{Error: extends } 1.6\text{m too close!})$$

Corrected formulation:
$$\text{center.x} = X_{\text{contact}} + \frac{L}{2}$$
$$\text{center.y} = Y_{\text{contact}}$$
$$\text{center.z} = \frac{H}{2}$$

**Why this fixes the alignment**:
- The nearest face of the cuboid is now at:
  $$X_{\text{near}} = \text{center.x} - \frac{L}{2} = \left(X_{\text{contact}} + \frac{L}{2}\right) - \frac{L}{2} = X_{\text{contact}}$$
- The near face of the 3D box rests **precisely on the tire contact patch**.
- The length of the cuboid ($L$) extends **forward away from the camera** from $X_{\text{contact}}$ to $X_{\text{contact}} + L$, naturally enclosing the car cabin, hood, and rear.
- The bottom face rests on the asphalt ($Z = 0$), and the center sits at $Z = H/2$.

```mermaid
graph LR
    subgraph Current Implementation (Defective)
        C1[Camera / Ego] -->|X_contact - L/2| Near1[Near Face: Overhangs ~1.6m too close!]
        Near1 -->|Center = X_contact| Center1[Tire Contact Patch]
        Center1 -->|X_contact + L/2| Far1[Far Face]
    end
    
    subgraph Corrected Implementation (Aligned)
        C2[Camera / Ego] -->|X_contact| Near2[Near Face: Snaps exactly to Tire Patch]
        Near2 -->|Center = X_contact + L/2| Center2[Volumetric Vehicle Center]
        Center2 -->|X_contact + L| Far2[Far Face: Encloses Vehicle]
    end
```

### Affected Files
1. **[tools/vision/geometry/box_dimensions.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/box_dimensions.py)**:
   - Update `compute_3d_box()`:
     ```python
     # Offset the longitudinal center by +L/2 so the near face aligns with the contact patch
     x_center_m = round(x_contact_m + (length_m / 2.0), 3)
     z_center_m = round(height_m / 2.0, 2)

     return {
         "center": {
             "x_fwd_m": x_center_m,
             "y_left_m": round(y_left_m, 3),
             "z_up_m": z_center_m,
         },
         "contact_patch": {
             "x_fwd_m": round(x_contact_m, 3),
             "y_left_m": round(y_left_m, 3),
             "z_up_m": 0.0,
         },
         "size": {
             "length_m": length_m,
             "width_m": width_m,
             "height_m": height_m,
         },
         ...
     }
     ```
2. **[tools/vision/mcap/mcap_scene_writer.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/mcap/mcap_scene_writer.py)**:
   - Ensure the cube position uses the corrected volumetric center, while the floating text label position sits cleanly over the vehicle roof at `z_up + (height_m / 2.0) + 0.35`.
3. **[tools/vision/pipeline/perception_runner.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/perception_runner.py)**:
   - Pass `x_contact_m` into `BoxDimensions.compute_3d_box()`, preserving `range_m` as the range to the vehicle's near surface.

---

## 3. How the User Will Regenerate the MCAP via Dashboard GUI

After Phase 1 is applied, the user will regenerate the MCAP using the Central Web Dashboard GUI:

1. **Open the RoadSense Central Automation Hub**:
   - URL: `http://localhost:8080/` (or launch via `tools/sync_and_process_logs.bat`).
2. **Navigate to the 👁️ 3D Vision Tab**:
   - Click the `👁️ 3D Vision` button in the top navigation tab bar.
3. **Configure Card 1 (🚀 1-Click 3D Vision Pipeline + MCAP Export)**:
   - **Target Session**: Select `session_20261023_111120` from the dropdown.
   - **Execution Mode**: Choose **Full Session** (or select **Instant 3D Re-projection** if you have `baseline_yolo_tracking.json` to project at 80k+ FPS).
   - **Pipeline Options (Pre-checked defaults)**:
     - `[x]` Auto-enrich Foxglove MCAP (`--mcap`)
     - `[x]` Camera Optical 3D Sizing
   - **YOLO Conf**: `0.25`
4. **Click `▶ Run 3D Vision Pipeline`**:
   - Monitor the live progress bar and FPS speed.
   - Upon completion, the terminal displays a clickable link to `tools/vision/output/mcap/session_20261023_111120_with_vision.mcap`.
5. **Inspect in Foxglove Studio**:
   - Open `session_20261023_111120_with_vision.mcap` in Foxglove Studio.
   - Navigate to Track `#5915` (around 6–8 meters) and visually verify that the magenta 3D box now snaps tightly around the vehicle body instead of overhanging forward towards the camera.

---

## 4. Phase 2 & 3: Subsequent Work (After User Verification)

### Phase 2: Diagnostic Track Pitch Analyzer (`tools/vision/eval/analyze_track_pitch_dynamics.py`)
*Note: Located strictly inside `tools/vision/eval/` to maintain clean vision component isolation.*
- Diagnostic tool to extract any single track across its full timeline.
- Generates interactive Plotly analysis charts comparing:
  - $X_{\text{geom}}(\theta_{\text{calib}})$ vs. $X_{\text{optical}}(w_{\text{px}}, h_{\text{px}})$ vs. $X_{\text{radar}}$.
  - Instantaneous solved pitch $\theta(t)$ over time.
- Identifies road slope transitions and suspension pitch dynamics.

### Phase 3: Dynamic Pitch Solver (`tools/vision/geometry/dynamic_pitch_solver.py`)
- Modular Huber-loss regression solving for optimal session-level static pitch offset or real-time rolling Kalman dynamic pitch.
- Closed-form radar-to-vision pitch alignment when mmWave radar hits are available.

---

## 5. Verification Plan for Phase 1

### Automated Tests
```bash
python -m unittest scripts/tests/test_vision_dashboard_integration.py
python -m unittest discover -s scripts/tests
```
- Verify that `BoxDimensions.compute_3d_box()` correctly places `x_fwd_m = x_contact + length_m / 2.0`.
- Verify that near-face distance equals `x_contact_m`.

### Manual User Verification
- User runs the 3D Vision pipeline via the Web Dashboard GUI for `session_20261023_111120`.
- User inspects Track `#5915` in Foxglove Studio to visually confirm the box alignment.
