# Walkthrough: Dynamic Plot Scaling (Approach A) & Standalone Interactive HTML Audit Report

## 1. Overview
We solved the graph scaling defect in the cross-sensor synchronization auditor and introduced a **zero-dependency, single-file interactive HTML audit report** powered by Plotly.js.

### Key Problems Addressed:
1. **Matplotlib Y-Axis Compression Defect**: Camera2 initialization ($t < 1.0\text{s}$, $\Delta t \approx 480\text{--}550\text{ms}$) and teardown ($t > T - 1.5\text{s}$, $\Delta t \approx 380\text{ms}$) boundary spikes forced the y-axis to $400\text{--}800\text{ms}$, squishing the nominal $0\text{--}25\text{ms}$ steady-state data into an unreadable flat line.
2. **Interactive Inspection Need**: Engineers analyzing session recordings needed the ability to zoom into individual sensor frames, inspect exact timestamps on hover, and pan across all 6 synchronized streams simultaneously.
3. **Single-File Zero-Dependency Requirement**: The generated report had to be a single `.html` file capable of running on **any PC without internet, local web servers, or Python dependencies (`file:///` protocol)**.

---

## 2. Changes Made

### 2.1 Approach A Dynamic Y-Scaling in Static PNG ([scripts/audit_cross_sensor_sync.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py))
- **Steady-State Core Windowing**: Segments the timeline into transient boundaries ($t < 2.0\text{s}$, $t > T_{\text{end}} - 1.5\text{s}$) vs steady-state core.
- **Panel 1 (Radar $\to$ Camera Offset)**: Limits y-axis to `[-2.0, max(45.0, core_max * 1.25)]`. For clean sessions, y-axis spans $0\text{--}45\text{ms}$, making $16.67\text{ms}$ Nyquist and $33.33\text{ms}$ 1-frame horizon guidelines clearly visible.
- **Panel 2 (Frame Intervals)**: Limits y-axis to `[-5.0, max(75.0, core_max * 1.25)]`. Separates the Camera $33.3\text{ms}$ line and Radar $50\text{ms}$ line.
- **Boundary Callout Badges**: Boundary spikes exceeding the y-limits are tagged with top-pinned markers (e.g. `▲ Startup Init: 221ms` and `▲ Startup Pause: 484ms (Camera2 Init)`).
- **In-Flight Anomaly Resilience**: True mid-session drops automatically expand the axes.

### 2.2 Offline Standalone Interactive HTML Report ([scripts/audit_cross_sensor_sync.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py))
- **Pre-Bundled Plotly Runtime**: Bundled `scripts/assets/plotly-basic.min.js` (~1.0 MB) in the repository.
- **Zero Python Dependencies**: Standard Python libraries (`json`, `os`, `sys`) serialize the 6 sensor streams into JSON. No `pip install plotly` required!
- **Zero Network Calls**: Plotly runtime is inlined directly into `<script>` inside `sync_audit_report.html`. The file is $\approx 1.2\text{ MB}$, completely self-contained, and opens via `file:///` on any browser.
- **Coordinated Multi-Track Zooming**: All 6 subplots share the same x-axis (`xaxis: { anchor: 'y6' }`), so zooming on any subplot zooms all 6 subplots in lockstep.
- **Interactive Views Toolbar**:
  - `🎯 Steady-State Focus (Approach A)`: Default zoom revealing steady-state jitter.
  - `🔍 Show All Spikes (Full Horizon)`: Auto-ranges to show full boundary spikes.
  - `⚡ Startup Phase (0-5s)`: Zooms into pipeline initialization.
  - `🛑 Teardown Phase (Last 5s)`: Zooms into session close behavior.
- **Executive Scorecard & KPI Cards**: Top summary cards with certification badge (`CERTIFIED GOLD`, `CERTIFIED SILVER`), and the complete 10-point audit checklist.

---

## 3. Verification & Validation

### 3.1 Verification on `session_20261016_144844`
Ran:
```powershell
python scripts/audit_cross_sensor_sync.py logs/session_20261016_144844
```
**Results**:
- **Diagnostic Plot**: `logs/session_20261016_144844/sync_audit_report.png` (585 KB)
- **Interactive HTML**: `logs/session_20261016_144844/sync_audit_report.html` (1.22 MB, 100% standalone)

### 3.2 Visual Render Verification via Headless Browser
Tested and captured screenshots using the browser MCP tools:
1. **Interactive HTML Report Overview**:
   - Header, KPI grid, toolbar, and 6-panel synchronized charts rendered cleanly with dark-theme styling.
2. **Interactive View Toggle Verification**:
   - Clicked `Show All Spikes (Full Horizon)`: axes smoothly expanded to $0\text{--}400\text{ms}$ showing boundary peaks.
   - Clicked `Steady-State Focus`: axes snapped back to $[0, 45\text{ms}]$ and $[0, 75\text{ms}]$.
3. **Scorecard Checklist Table**:
   - All 10 checklist rows rendered with green `PASS` badges.

### 3.3 Verification on `session_20261016_143148`
Ran:
```powershell
python scripts/audit_cross_sensor_sync.py logs/session_20261016_143148
```
Both `sync_audit_report.png` and `sync_audit_report.html` generated cleanly with full audit verification.

---

## 4. Architecture Refactoring & Decoupling

### 4.1 Separation of Concerns
To maintain modularity and allow other scripts / pipelines to reuse the audit engine programmatically, the monolithic 1,468-line script was split into two clean components:

1. **Backend Engine ([scripts/cross_sensor_sync_engine.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/cross_sensor_sync_engine.py))**:
   - Contains all domain calculations, monotonic integrity verifications, UI desync event parsing, Matplotlib PNG rendering (with dynamic scaling), and offline Plotly HTML dashboard generation.
   - Core API: `audit_timeline(sess_dir, make_plot=True, make_html=True, show_gui=False)` returning `(scorecard, overall_grade, plot_path, html_path)`.

2. **CLI Runner ([scripts/audit_cross_sensor_sync.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py))**:
   - Lean ~50-line entry point focusing strictly on CLI arguments (`--latest`, `--no-plot`, `--no-html`, `--show`, `target`), session directory resolution, and invoking `audit_timeline(...)`.
   - Preserves 100% backward compatibility for command-line users and the Diagnostic Hub web server.

### 4.2 Verification of Refactored Pipeline
Ran test executions:
```powershell
# Default session run
python scripts/audit_cross_sensor_sync.py logs/session_20261016_144844

# CLI flag test
python scripts/audit_cross_sensor_sync.py --latest --no-plot
```
Both commands executed with code `0`, generated expected artifacts, and logged identical scorecards without errors.

