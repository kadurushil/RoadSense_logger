# Walkthrough: Central Web Dashboard 3D Vision Suite Integration

We have integrated the 3D Geometric Vision & Perception Suite into the **RoadSense Central Web Automation Hub** (`tools/roadsense_web_server.py` + `tools/web_dashboard/index.html`).

---

## 1. What Was Implemented

### Multi-Environment Python Process Routing
The Web Server (`tools/roadsense_web_server.py`) now dynamically selects the Python environment based on the script target:
- **Vision Scripts** (`tools/vision/...`): Discovers and executes with the **Miniconda `cv313`** Python interpreter (`C:\ProgramData\miniconda3\envs\cv313\python.exe`) which contains PyTorch 2.1.1, CUDA 12.1 acceleration, OpenCV, and Ultralytics.
- **Data & Telemetry Scripts** (`tools/convert_session_to_mcap.py`, `tools/sync_and_process_sessions.py`, `scripts/...`): Continues executing seamlessly on the active system / `roadsense-mcap` environment.

### Header Status Pill
- Added `#visionStatusPill` displaying live status (`Vision: cv313 (CUDA)` with a luminous green dot or `Vision: Missing cv313` with a gray dot).
- Hovering shows the exact interpreter path and CUDA availability.

### Dedicated 👁️ 3D Vision Tab
A top-level tab in the dashboard provides 4 specialized cards:

1. **🚀 1-Click 3D Geometric Vision & MCAP Pipeline (`perception_runner.py`)**
   - **Target Session**: Dropdown populated from local `logs/`.
   - **Execution Modes**:
     - *Full Session* (Default) — processes all video frames.
     - *Quick Test Slice* — customizable frame count (default: 300).
     - *Instant 3D Re-projection* — runs `--from_2d baseline_yolo_tracking.json` at 80,000+ FPS without re-running YOLO.
   - **Recommended Defaults (Pre-selected)**:
     - `[x]` Auto-enrich Foxglove MCAP (`--mcap`) -> outputs `<session>_with_vision.mcap` containing Magenta cuboids.
     - `[x]` Camera Optical 3D Sizing (`use_optical_sizing=True`).
     - YOLO Confidence threshold: `0.25`.

2. **📊 Radar vs. Vision Ground-Truth Benchmark (`evaluate_vision_vs_radar.py`)**
   - Evaluates 3D vision tracking accuracy against mmWave radar ground truth using spatio-temporal Hungarian matching.
   - **Default Gate Distance**: `3.5` meters.
   - Outputs range-binned MAE/RMSE metrics and JSON benchmark reports.

3. **📦 Standalone MCAP Topic Injection (`inject_vision_tracks.py`)**
   - Injects pre-computed `_vision_tracks.json` into an MCAP container without re-running YOLO.
   - Allows creating a lightweight standalone MCAP or merged into the source recording.

4. **🎬 Baseline Video Annotation & Neural Depth (`generate_baseline_perception.py`)**
   - **Default Mode**: Standalone YOLO Tracking (`-y`, ~50 FPS without depth).
   - Optional Video Depth Anything ViT-S (Fast) and ViT-L (High-Precision).

### Live Progress Bar Streaming
All 4 scripts now emit structured `[PROGRESS]` events that update the dashboard's live progress widget in real time, displaying:
- Current frame count vs. total frames
- Active processing speed (FPS)
- Estimated time remaining (ETA in seconds/minutes)
- Completion pulse upon finish

### Rich Output Links
Terminal output automatically transforms:
- `tools/vision/output/mcap/<session>_with_vision.mcap` -> Clickable download / preview link + folder opener.
- `tools/vision/output/reports/<session>_benchmark_report.json` -> Clickable report link.
- `tools/vision/output/tracks/<session>_vision_tracks.json` -> Clickable track inspect link.

### Sticky Navigation Tab Bar & Layout Hardening
- Pinned `.tab-bar` with `flex-shrink: 0; position: sticky; top: 0; z-index: 20;` so the tab switcher never squashes or scrolls off-screen when opening tall content panels (such as the 3D Vision suite).
- Added `leftPane.scrollTop = 0` on `switchTab()` to automatically reset panel scroll position upon navigation.

---

## 2. Verification Results

### Automated Unit Test Suite (`scripts/tests/test_vision_dashboard_integration.py`)
```
....
----------------------------------------------------------------------
Ran 4 tests in 0.001s

OK
```
- Verified `find_vision_python()` resolves to `C:\ProgramData\miniconda3\envs\cv313\python.exe`.
- Verified `get_python_for_script()` routes `tools/vision/...` scripts to `cv313` and all other scripts to `sys.executable`.
- Verified `/api/status` API payload contains `"vision"` metadata.
- Verified dashboard HTML contains all required elements, tabs, and pre-selected defaults.

### Full Repository Regression Test (`scripts/tests`)
```
Ran 19 tests in 0.020s

OK
```

### Android Gradle Unit Tests (`gradlew testDebugUnitTest`)
```
BUILD SUCCESSFUL in 1s
24 actionable tasks: 24 up-to-date
```
