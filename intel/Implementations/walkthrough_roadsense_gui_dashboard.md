# Walkthrough: RoadSense Web Dashboard & Script Automation Hub

> **Artifact Name:** `walkthrough.md`  
> **Workspace Mirror:** `intel/Implementations/walkthrough_roadsense_gui_dashboard.md`  
> **Date:** 2026-09-28  
> **Branch:** `feature/3125000-baud`  

---

## 1. Executive Summary

We transformed the offline script workflow and `sync_and_process_logs.bat` into a visual, web-enabled **RoadSense Telemetry & Script Automation Hub**. The dashboard serves as an intuitive visual wrapper around the 12+ existing RoadSense data pipeline and diagnostics scripts across `tools/` and `scripts/`.

The implementation uses **Approach 2 (Local Web Dashboard)** built with 100% zero external dependencies (Python standard library `http.server`, `threading`, `subprocess`, `queue`), featuring real-time log streaming via Server-Sent Events (SSE) and full preservation of the legacy CLI menu (`--cli`).

---

## 2. Key Changes Made

### 2.1 Front-End Web Dashboard
* **[`tools/web_dashboard/index.html`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/web_dashboard/index.html)**:
  * **Cockpit Dark Theme:** Automotive UI aesthetic with CSS custom variables, status pills, and split-pane layout.
  * **Categorized Script Tabs:**
    1. 🔄 **Log Sync & Processing** (`tools/sync_and_process_sessions.py`): Full Pipeline, Sync Only, Process Only, session scope dropdown, MCAP toggle, force toggle, video flip.
    2. 📦 **MCAP Container Export** (`tools/convert_session_to_mcap.py`): Target session dropdown, exclude video toggle, rotate video toggle.
    3. 🩺 **Sensor Diagnostics** (`scripts/`): Master 7-point health check, monotonic sync audit, radar stream validator, camera jitter validator, GNSS CEP accuracy validator, CANedge staging validator (including phone staging pool audit).
    4. 🔍 **Flight Recorder Search** (`scripts/search_flight_recorder.py`): Real-time keyword filter, errors-only checkbox, session dropdown, max results slider.
    5. 🔬 **Deep Inspection Utilities** (`tools/`): Session inspector, raw UART binary dump inspector, GNSS Haversine trajectory analyzer.
  * **Real-Time Terminal Console:**
    * Monospace font, auto-scrolling buffer with auto-scroll toggle.
    * Color-coded line highlighting (`[+]` green, `[-]` orange, `[!]` red, `===` cyan).
    * Toolbar with `▶ Run Script`, `⏹ Stop Script`, `📋 Copy Logs`, `🗑 Clear Terminal`.
    * Live execution timer (`00:14.2s`) and process status badge (`● IDLE`, `● RUNNING`, `● FINISHED`).

### 2.2 Zero-Dependency Backend Server & Process Supervisor
* **[`tools/roadsense_web_server.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/roadsense_web_server.py)**:
  * **`ThreadingHTTPServer`**: Multi-threaded HTTP server so active SSE connections do not block concurrent REST requests.
  * **Dynamic Port Auto-Hunting:** Automatically probes ports `8088..8108` and falls back to ephemeral port allocation if busy.
  * **Process Supervisor:**
    * Spawns worker scripts using `subprocess.Popen` with Windows process grouping (`CREATE_NEW_PROCESS_GROUP`).
    * Background reader thread pipes stdout/stderr into an in-memory broadcast queue.
    * Broadcasts lines to connected web clients via `/api/stream` (`text/event-stream`).
    * Dedicated `/api/stop` endpoint cleanly reaps process trees via `taskkill /F /T /PID`.
  * **REST Endpoints:**
    * `GET /api/status`: Returns ADB device connection state, local session list from `logs/`, and active process status.
    * `GET /api/sessions`: Returns sorted list of `session_*` folders.
    * `POST /api/run`: Spawns selected script with configured arguments.
    * `GET /api/stream`: Persistent Server-Sent Events stream.
    * `POST /api/stop`: Stops the active run.
    * `POST /api/shutdown`: Cleanly terminates server and exits.

### 2.3 Master Entry Batch Script
* **[`sync_and_process_logs.bat`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/sync_and_process_logs.bat)**:
  * Default behavior: Auto-detects `roadsense-mcap` virtual environment and launches the Web Dashboard, opening the default browser to `http://127.0.0.1:8088/`.
  * CLI Fallback: Running `sync_and_process_logs.bat --cli` drops directly into the classic interactive command-line choice menu.
  * Escaped Windows batch special characters (`^&`) to prevent subshell execution errors.

### 2.4 Foxglove MCAP IMU Telemetry & Orientation Ingestion
* **[`tools/convert_session_to_mcap.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)**:
  * **Channel `/imu/data` (`roadsense.Imu` JSON schema):** Streams 100 Hz high-resolution IMU readings including calibrated accelerations (`ax`, `ay`, `az`), gyroscope angular rates (`gx`, `gy`, `gz`), linear accelerations (`lin_ax`, `lin_ay`, `lin_az`), rotation vector quaternions (`qx`, `qy`, `qz`, `qw`), and closed-form Euler angles (`roll_deg`, `pitch_deg`, `yaw_deg`).
  * **Channel `/imu/pose` (`foxglove.PoseInFrame` Protobuf):** Publishes 3D device orientation in `base_link` frame for direct 3D orientation visualization in Foxglove Studio.
  * **[`tools/foxglove_layouts/RoadSense_Cockpit_Layout.json`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/foxglove_layouts/RoadSense_Cockpit_Layout.json):** Pre-configured plot curves for IMU pitch, linear acceleration, and yaw rate.

### 2.5 Video Rotation Re-encoding Bitrate Optimization
* Fixed video size ballooning when `--flip-video` is enabled:
  * **Root Cause:** Prior re-encoding configuration used `preset: ultrafast` with `tune: zerolatency` without CRF or target bitrate. In `libx264`, `ultrafast` disables CABAC entropy coding, motion estimation, and sub-pixel partitioning, causing uncompressed I/P frame sizes to balloon from 257 MB to ~560 MB.
  * **Solution:** Configured `out_stream.options = {"preset": "veryfast", "crf": "24"}`. Enables CABAC and macroblock motion search while encoding at ~160 FPS, reducing full session size to **251.33 MB** (even smaller than the original MP4).

---

## 3. Verification & Validation Results

### 3.1 Automated Backend & SSE Verification
We executed an end-to-end Python test verifying:
1. `GET /` -> HTTP 200, served dashboard HTML successfully (32,825 bytes).
2. `GET /api/status` -> Valid JSON response detecting 26 local sessions and ADB status.
3. `POST /api/run` -> Spawned `scripts/session_health_check.py --latest`.
4. `GET /api/stream` -> Streamed 8 live log lines and process state transitions over SSE in real time.
5. Exit state -> Script completed with exit code `0` and server state transitioned back to `IDLE`.
6. `POST /api/shutdown` -> Graceful server termination.

```text
=== Starting Verification Test ===
[*] Testing GET /...
[+] GET / OK! Length: 32825
[*] Testing GET /api/status...
[+] Status data: {'running': False, 'script': None, 'exit_code': None}
[+] Local sessions count: 26
[*] Testing POST /api/run with session_health_check.py --latest...
[+] Run response: {'success': True, 'message': 'Process started successfully.'}
[*] Testing GET /api/stream...
    [SSE] event: process_status
    [SSE] data: {"running": true, "script": "session_health_check.py", "exit_code": null}
    [SSE] data: ================================================================================
    [SSE] data: ROADSENSE SESSION HEALTH AUDIT: session_20260922_120145
    [SSE] data: Path: C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\logs\session_20260922_120145
    [SSE] data: ================================================================================
    [SSE] data: [1/7] SESSION LIFECYCLE & METADATA:
[+] SSE successfully streamed 8 lines!
[+] Final process status: {'running': False, 'script': 'session_health_check.py', 'exit_code': 0}
[*] Testing POST /api/shutdown...
[+] Shutdown response: {'success': True, 'message': 'Server shutting down...'}
=== Verification Passed 100%! ===
```

### 3.2 CLI Menu Fallback Verification
Tested running `sync_and_process_logs.bat --cli`:
```text
===============================================================================
         RoadSense - Log Sync, Visualizer & Foxglove MCAP Pipeline (CLI)
===============================================================================
Python Interpreter: C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe

Select an operation:

  [1] Full Pipeline: Sync from Device (ADB) and Process New Sessions
  [2] Sync Only: Pull sessions from Device without processing
  [3] Process Only: Process un-processed local sessions (skips up-to-date)
  [4] Force Re-process All: Re-generate all local session visualizer files
  [5] Process Specific Session: Choose a specific session to process
  [6] Export to Foxglove MCAP: Convert sessions into .mcap container
  [7] Exit

Enter your choice [1-7]: 7
Exiting.
```

---

## 4. How to Use the New Dashboard

1. **Launch the Dashboard:**
   Double-click [`sync_and_process_logs.bat`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/sync_and_process_logs.bat) or run from PowerShell:
   ```powershell
   .\sync_and_process_logs.bat
   ```
2. **Access in Browser:**
   Your default browser will automatically open `http://127.0.0.1:8088/`.
3. **Execute Any Script:**
   * Select a category tab on the left.
   * Adjust checkboxes or select a target session from the dropdown.
   * Click the primary green action button (`▶ Run Pipeline`, `▶ Run Diagnostic`, etc.).
   * Watch real-time streaming output in the embedded terminal.
4. **Stop or Cancel:**
   Click `⏹ Stop Script` at any point to terminate the running process tree.
5. **Headless / Terminal Fallback:**
   ```powershell
   .\sync_and_process_logs.bat --cli
   ```
