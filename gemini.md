# RoadSense Local Environment & AI Assistant Operational Guide

> **Document Name:** `gemini.md`  
> **Purpose:** Authoritative reference for autonomous AI coding agents and developers working in this local environment. Contains all machine-specific configurations, known local environment quirks, build workarounds, and hardware guidelines.  
> **Workspace Path:** `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense`  
> **Last Updated:** 2026-09-22  

---

## 1. Project Documentation & System Context Map

> ⚠️ **MANDATORY FOR AGENTS:** Always review relevant context files before making architectural decisions or modifying core subsystems.

* **`context/`**: Authoritative, modularized context folder containing 8 specialized intelligence files:
  * [`00_MASTER_EXECUTIVE_HANDOVER.md`](context/00_MASTER_EXECUTIVE_HANDOVER.md) — Master executive overview, active milestones & navigation map.
  * [`01_ARCHITECTURE_AND_STORAGE.md`](context/01_ARCHITECTURE_AND_STORAGE.md) — MVVM unidirectional data flow, component package mapping & storage layout.
  * [`02_RADAR_AND_HIGH_SPEED_SERIAL.md`](context/02_RADAR_AND_HIGH_SPEED_SERIAL.md) — 3.125 Mbps UART driver, 64KB direct ring buffer & TLV framing.
  * [`03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md`](context/03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md) — Camera2, autonomous Road AE, infinity lock, fullscreen viewfinder & monotonic clock sync.
  * [`04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md`](context/04_CANEDGE2_AUTONOMOUS_INGESTION_ENGINE.md) — CANedge2 REST API, 2-folder staging pool & smart auto-pruner.
  * [`05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md`](context/05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md) — AppLogger dual-stream flight recorder (`app_system.log` & `session_debug.log`).
  * [`06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md`](context/06_PC_TOOLING_AND_VISUALIZER_PIPELINE.md) — Python ADB extraction, replay pipeline & web visualizer schema.
  * [`07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md`](context/07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md) — Machine quirks, HAL limitations & command cheat sheet.
  * [`08_RADAR_CAMERA_SPATIAL_CALIBRATION_AND_FUSION.md`](context/08_RADAR_CAMERA_SPATIAL_CALIBRATION_AND_FUSION.md) — 6-DOF extrinsics, reverse touch solver, hybrid lollipops & range rings.
* **`intel/`**: In-depth hardware integration guides, post-mortems, and theoretical formulations:
  * `intel/Sensor fusion basics.md` — Complete spatial calibration and perspective projection theory.
  * `intel/debugging_and_troubleshooting_knowledge_base.md` — Post-mortem analysis of Bugs #1 through #10.
  * `intel/CANedge2_Android_Integration_Guide.md` — Full hardware spec for CSS Electronics CANedge2.
  * `intel/presentations/` — Presentation deck generator and executive slides.
  * `intel/Implementations/` — Step-by-step implementation plans and walkthroughs.
* **`scripts/`**: Standalone PC diagnostic & sensor validation tools (`session_health_check.py`, `audit_cross_sensor_sync.py`, etc.).
* **`Future_stuff.md`**: Centralized backlog of future ADAS enhancements and roadmap.
* **`README.md`**: Top-level project landing page and quick-start guide.

---

## 2. Local Java Home & Gradle Build Instructions

### ⚠️ Known Issue: Invalid System `JAVA_HOME`
* **Problem:** The Windows system environment variable `JAVA_HOME` is set to an invalid/uninstalled path:  
  `C:\Program Files\Amazon Corretto\jdk17.0.12_7`  
  Attempting to run `./gradlew` directly results in:  
  `ERROR: JAVA_HOME is set to an invalid directory: C:\Program Files\Amazon Corretto\jdk17.0.12_7`

### ✅ Solution / Workaround:
Always set `$env:JAVA_HOME` to Android Studio's bundled JetBrains Runtime (JBR) before invoking `gradlew` in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew assembleDebug
```

### Quick Build & Test Commands:
```powershell
# Assemble Debug APK
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug

# Run JVM Unit Tests
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest

# Full Clean & Build
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew clean assembleDebug
```

---

## 3. Android SDK, ADB & Device Deployment

### Local Paths:
* **Android SDK Directory:** `C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk`
* **ADB Executable:** `C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe`
* **Built APK Target:** `app\build\outputs\apk\debug\app-debug.apk`

### Quick Device Diagnostics via ADB:
> ⚠️ **MANDATORY POLICY:** Build and APK installation are performed manually by the USER (see Rule 5 below). Agents should NOT attempt `adb install` or `pm install`.

```powershell
# Check connected devices
& "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices

# Monitor live RoadSense, Camera, Radar, Fusion & CANedge logcat
& "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s RoadSense:D CanedgeIngestion:D RadarSerialService:D AppLogger:D CameraEngine:D SpatialProjection:D
```

---

## 4. Workspace Boundaries & Security Guardrails

### 🛡️ Strict Workspace Rules:
1. **Primary Working Repository:**  
   `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense` (All project work must take place here).
2. **External Reference Repository (STRICTLY READ-ONLY):**  
   `D:\Work\Repo\CANenbl_unifiedRadarTracker`  
   * **Rule:** NEVER modify, delete, edit, or commit to any files in this directory. It is strictly a read-only historical and algorithmic reference.
3. **Commit Authorization:**  
   * NEVER perform `git commit` or `git push` without explicit confirmation and instruction from the user.
4. **Git Reset Safety:**  
   * NEVER run destructive commands (like `git reset --hard` or unprompted resets) that could cause fear of data loss. Always use targeted `git add <file>` and standard commits.
5. **Build and App Installation Delegation:**  
   * The user will perform all APK builds and installations directly. Agents must NOT attempt to execute `adb install` or `pm install` on target hardware. Agents focus on code edits, specifications, architectures, and JVM unit testing.
6. **Implementation Plans & Architecture Artifacts Mirroring:**  
   * Whenever an implementation plan, architectural feasibility report, or walkthrough artifact is created or updated in the internal brain artifact directory, agents MUST mirror a copy directly into `intel/Implementations/<plan_name>.md` (or `intel/` for general architecture reports). This guarantees the USER has direct workspace access to all design documents without needing to check hidden `.gemini` folders.

---

## 5. Multi-Sensor Hardware & Serial Parameters

### 5.1 TI mmWave Radar (AWR1843BOOST)
* **Baud Rate:** **`3,125,000 baud` (3.125 Mbps)** on the data streaming port; `115,200 baud` on the CLI command port.
* **Architecture:** Dedicated high-priority worker thread (`Process.THREAD_PRIORITY_URGENT_AUDIO`) and 64 KB direct byte ring buffer to guarantee zero dropped frames at 20 Hz.
* **USB Permission:** Android requires explicit runtime USB permission dialog consent handled via `UsbManager`.

### 5.2 Camera2 Subsystem & Viewfinder Quirks
* **HAL Surface Allocation Limits:** Many midrange Android chipsets limit concurrent Camera2 HAL output streams. Autonomous Road AE samples downsampled $32 \times 24$ bitmaps directly from the active `TextureView` on `Dispatchers.Default` rather than allocating an extra hardware stream.
* **Surface Re-attachment:** Always use `CameraEngine.isSurfaceAttached(st)` before re-instantiating or detaching preview surfaces to prevent false "PAUSED" states on fullscreen or card transitions.
* **Resolution Changes:** Surface buffer resizing must be sequenced with a 150 ms delayed HAL reopen to prevent buffer starvation crashes.
* **Interaction Stability:** Cockpit `HorizontalPager` has `userScrollEnabled = false` to prevent accidental tab navigation during touch dragging or calibration reticle interaction.

### 5.3 Radar-Camera Spatial Calibration & Fusion
* **6-DOF Rigid Transform ($[\mathbf{R} \mid \mathbf{T}]$):** Lateral offset $\Delta X$, setback $\Delta Y$, height $\Delta Z$, pitch $\theta$, yaw $\psi$, roll $\phi$.
* **Camera Intrinsics ($K$):** Automatically queried at runtime via Camera2 `CameraCharacteristics`.
* **Reverse Touch Solver:** Closed-form $O(1)$ angle recovery from single-tap or trackpad delta drag over physical vehicle targets at known radar distance.
* **Painter's Algorithm Depth-Sorting:** All radar projections must be sorted descending by forward depth (`lollipops.sortByDescending { it.depthM }`) so distant detections ($100\text{m}$) render first and close targets ($10\text{m}$) render on top.
* **Range Rings:** Concentric ground arcs at $10\text{m}, 30\text{m}, 60\text{m}, 120\text{m}$ clamped to camera HFOV with staggered distance labels.

### 5.4 CANedge2 Hardware & Networking Parameters
* **Device Model:** CSS Electronics CANedge2 | **Device ID:** `7AC5E17F` | **Firmware:** `01.09.03`
* **Wi-Fi AP Credentials:** SSID: `M21` | Pass: `987654321` (2.4 GHz WPA2)
* **Default Fallback IP:** `http://192.168.x.x` or `http://7AC5E17F/`
* **Logging Mode:** 1-minute (60s) MF4 file splitting, cyclic logging enabled (`"cyclic": 1`).
* **Single-Connection MCU Server (Bug #10):** ESP32 cannot handle concurrent HTTP connections. All downloads must be sequential with 1.5s error backoff cooldowns. Never send HTTP DELETE.

---

## 6. Storage Directory Structure

```text
/sdcard/Android/data/com.bajajauto.roadsense/files/
 ├── calibration/
 │    └── radar_camera_calib.json      <-- Persistent 6-DOF extrinsics profile
 ├── app_logs/                         <-- Continuous app-wide flight recorder logs
 │    └── app_run_YYYYMMDD_HHMMSS/
 │         └── app_system.log          <-- Idle boot, discovery, sync & UI events
 └── sessions/
      ├── canedge_pool/                <-- Staging cache for 2 recent session folders
      │    ├── 00000033_00000001.MF4
      │    └── 00000034_00000001.MF4
      └── session_YYYYMMDD_HHMMSS/     <-- Permanent recording session
           ├── session_metadata.json
           ├── session_debug.log        <-- Active recording flight recorder log
           ├── session_timeline.csv    <-- Shared monotonic sync index
           ├── radar/
           │    ├── radar_raw_stream.bin
           │    └── radar_frames.bin
           ├── camera/
           │    ├── camera_video.mp4
           │    └── camera_frames.csv
           ├── gnss/
           │    └── gnss_track.csv
           └── can/
                ├── 00000033_00000001.MF4
                └── 00000034_00000001.MF4
```
