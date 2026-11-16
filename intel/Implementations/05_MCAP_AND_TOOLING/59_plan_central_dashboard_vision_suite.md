# Implementation Plan: Central Web Dashboard 3D Vision Suite Integration

## Goal Description
The RoadSense project currently features a Central Web Automation Dashboard (`tools/roadsense_web_server.py` + `tools/web_dashboard/index.html`) that allows users to trigger ADB synchronization, session processing, Foxglove MCAP generation, and sensor health diagnostics from a browser.

However, the **Computer Vision Perception Suite** (`tools/vision/`):
- `perception_runner.py` (3D ground-plane vehicle & pedestrian tracking)
- `inject_vision_tracks.py` (non-destructive MCAP `/vision/geo_tracks` topic injection)
- `evaluate_vision_vs_radar.py` (spatio-temporal Hungarian bipartite accuracy benchmarking)
- `generate_baseline_perception.py` (YOLO tracking and dense Video Depth Anything neural depth)

must currently be run manually via the CLI with complex parameters. Furthermore, the vision pipeline requires a dedicated Python environment (**`cv313`** with PyTorch 2.1.1 + CUDA 12.1 + Ultralytics + OpenCV), which differs from the base or `roadsense-mcap` Python environment running the web server.

This plan details the implementation of a **native, user-friendly 3D Vision Suite control tab** within the Central Dashboard, featuring:
1. **Intelligent Multi-Environment Routing**: `roadsense_web_server.py` auto-detects `cv313` (`C:\ProgramData\miniconda3\envs\cv313\python.exe`) and routes vision commands to it seamlessly.
2. **1-Click 3D Vision Pipeline**: Run YOLO detection, closed-form 3D ground projection, and auto-generate enriched Foxglove MCAPs with `/vision/geo_tracks` directly from the UI.
3. **Automated Radar-vs-Vision Benchmarking**: 1-click execution of bipartite track matching and diagnostic accuracy evaluation with live reporting.
4. **Real-Time Progress Streaming**: Emits structured `[PROGRESS]` events from vision scripts so the dashboard's live progress bar widgets display FPS, frame indices, percentage, and countdown ETA.
5. **Direct Artifact Opening & Inspection**: Clickable folder links and report shortcuts for `tools/vision/output/mcap/` and `tools/vision/output/reports/`.

---

## User Review Required

> [!IMPORTANT]
> **Environment Isolation (`cv313`)**:
> The vision suite requires PyTorch with CUDA, Ultralytics, and OpenCV, which reside in `C:\ProgramData\miniconda3\envs\cv313`. The web server supervisor will automatically detect and execute vision scripts using this interpreter while continuing to run radar/MCAP scripts using the active server Python environment.

> [!NOTE]
> All vision outputs will continue to adhere to the non-destructive safety contract: original `logs/` recordings are **100% read-only**. Enriched MCAPs with `/vision/geo_tracks` are generated inside `tools/vision/output/mcap/`.

---

## Technical Architecture & Design

### Process & Data Flow Diagram
```mermaid
graph TD
    UI[Web Dashboard Browser UI] -->|POST /api/run script: tools/vision/...| SVR[roadsense_web_server.py]
    SVR -->|Auto-detect Environment| ENV{Script Type}
    ENV -->|Vision Script| V_PY[Miniconda cv313 Python - CUDA Active]
    ENV -->|Standard Script| S_PY[sys.executable]
    
    V_PY -->|Execute| PR[perception_runner.py --mcap]
    V_PY -->|Execute| EV[evaluate_vision_vs_radar.py]
    V_PY -->|Execute| INJ[inject_vision_tracks.py]
    V_PY -->|Execute| BL[generate_baseline_perception.py]
    
    PR -->|Stream [PROGRESS]| SSE[SSE Stream /api/stream]
    SSE -->|Update Progress Bar & Terminal| UI
    PR -->|Export| MCAP[tools/vision/output/mcap/*_with_vision.mcap]
    EV -->|Export| REP[tools/vision/output/reports/*_benchmark_report.json]
```

---

## Proposed Changes

### Component 1: Web Automation Server (`tools/roadsense_web_server.py`)

#### [MODIFY] `tools/roadsense_web_server.py`
1. **Vision Python Resolver (`get_python_for_script`)**:
   - Checks if the script resides inside `tools/vision/`.
   - Locates `cv313` Python at `C:\ProgramData\miniconda3\envs\cv313\python.exe` (with fallbacks to `conda run -n cv313` or user home miniconda paths).
   - If not a vision script, uses `sys.executable`.
2. **Vision Environment Detection in `/api/status`**:
   - Adds `"vision"` status object to `/api/status`:
     ```json
     {
       "available": true,
       "env_name": "cv313",
       "python_path": "C:\\ProgramData\\miniconda3\\envs\\cv313\\python.exe",
       "cuda_available": true
     }
     ```
3. **Artifact Directory Serving & Opening**:
   - Enhance `/api/open_folder` to support opening vision output directories (`tools/vision/output/mcap`, `tools/vision/output/reports`, `tools/vision/output/json`).
   - Allow static file serving for `tools/vision/output/` so generated reports and annotated MP4 previews can be accessed directly.

---

### Component 2: Central Dashboard UI (`tools/web_dashboard/index.html`)

#### [MODIFY] `tools/web_dashboard/index.html`
1. **Header Status Pill**:
   - Add `#visionStatusPill` next to GPU and ADB status pills:
     `👁️ Vision: cv313 (CUDA)` (Green when ready, Amber/Gray if environment missing).
2. **New Navigation Tab**:
   - Add `<button class="tab-btn" onclick="switchTab('visionTab')">👁️ 3D Vision Suite</button>`.
3. **Tab Panel (`#visionTab`) Design**:
   - **Card 1: 🚀 1-Click 3D Geometric Vision & MCAP Pipeline**:
     - Target Session selection dropdown (`#visionSessionSelect`).
     - Execution Mode:
       - `Full Session` (Process all frames).
       - `Quick Test Slice` (Default: 300 frames input box).
       - `Instant 3D Re-projection` (Use existing 2D detections JSON `--from_2d` without re-running YOLO).
     - MCAP Integration: Checkbox `Auto-enrich Foxglove MCAP with /vision/geo_tracks (--mcap)` (Checked by default).
     - Optical Sizing vs. Fixed Class Priors toggle.
     - Confidence threshold slider (default: 0.25).
     - Action button: `▶ Run 3D Geometric Vision Pipeline`.
   - **Card 2: 📊 Radar vs. Vision Ground-Truth Benchmark**:
     - Target Session dropdown (`#visionEvalSessionSelect`).
     - Max distance gating input (default: 3.5m).
     - Action button: `⚡ Benchmark Accuracy (Radar vs Vision)`.
   - **Card 3: 📦 Standalone MCAP Topic Injection**:
     - Fast merging of pre-existing `_vision_tracks.json` into source MCAP or standalone tracks export.
     - Action button: `Inject Tracks into MCAP`.
   - **Card 4: 🎬 Baseline Video Annotation & Neural Depth**:
     - Standalone YOLO tracking video (`-y`) or Full 3D with Video Depth Anything (`vits` / `vitl`).
     - Action button: `Generate Annotated Video`.
4. **JavaScript Handlers**:
   - Add `runVisionPipeline()`, `runVisionBenchmark()`, `runVisionMcapInjection()`, `runBaselinePerception()`.
   - Populate vision session dropdowns on status refresh.
   - Update `appendLogLine()` to format vision output paths (`_with_vision.mcap`, `_benchmark_report.json`) as clickable buttons to open folders or inspect reports.

---

### Component 3: Vision Scripts Progress Emitting

#### [MODIFY] `tools/vision/pipeline/perception_runner.py`
- Add structured `[PROGRESS]` emission during frame tracking:
  ```json
  [PROGRESS] {"step": "vision_tracking", "desc": "[session_...] 3D Vision Tracking (1,200/5,000)", "current": 1200, "total": 5000, "pct": 24.0, "fps": 52.4, "eta_s": 72.5}
  ```
- When `--mcap` is active, emit `mcap_start` and `done` progress events.

#### [MODIFY] `tools/vision/mcap/inject_vision_tracks.py`
- Add structured `[PROGRESS]` emission during MCAP message merging:
  ```json
  [PROGRESS] {"step": "vision_mcap", "desc": "[session_...] Merging /vision/geo_tracks into MCAP...", "current": 50000, "total": 650000, "pct": 7.7}
  ```

---

### Component 4: Integration Test Suite

#### [NEW] `scripts/tests/test_vision_dashboard_integration.py`
Unit test suite verifying:
1. `get_python_for_script()` correctly resolves `cv313` for any script in `tools/vision/`.
2. `/api/status` accurately reports vision environment and CUDA readiness.
3. Parameter builder functions generate valid command lines for `perception_runner.py`, `inject_vision_tracks.py`, and `evaluate_vision_vs_radar.py`.

---

## Verification Plan

### Automated Tests
1. **Dashboard Vision Integration Test Suite**:
   ```powershell
   python -m unittest scripts/tests/test_vision_dashboard_integration.py
   ```
2. **Full Repository Python Test Suite**:
   ```powershell
   C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe -m unittest discover -s scripts/tests
   ```
3. **Android Gradle Unit Tests**:
   ```powershell
   powershell -Command "& { `$env:JAVA_HOME = 'C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr'; ./gradlew testDebugUnitTest }"
   ```

### Manual Verification
1. **Launch Web Server**:
   ```powershell
   python tools/roadsense_web_server.py
   ```
2. **Dashboard UI Verification**:
   - Open browser to `http://localhost:8088`.
   - Verify the top bar displays `👁️ Vision: cv313 (CUDA)`.
   - Click the `👁️ 3D Vision Suite` tab.
   - Select a session (e.g. `session_20261021_112414` or `session_20261023_111120`).
   - Run a quick test slice (e.g. 150 frames) with `--mcap`.
   - Verify live progress bar updates smoothly with frame count, FPS, and percentage.
   - Verify output links appear in the console upon completion.
   - Trigger the `Radar vs. Vision Benchmark` and verify the summary table and accuracy metrics display in the console.
