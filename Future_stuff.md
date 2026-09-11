# RoadSense: Future Enhancements & Pending Tasks Backlog

> **Purpose:** Centralized roadmap and feature backlog for pending architectural upgrades, hardware integrations, and user experience enhancements across RoadSense.  
> **Document Location:** `Future_stuff.md`  
> **Maintainers:** RoadSense Engineering & Autonomous AI Coding Agents  

---

## 1. Camera Subsystem Upgrades

### 1.1 Start Camera Preview Closed by Default (Lag Elimination)
* **Problem:** Currently, when switching between dashboard cards (e.g. from Radar to CANedge or Settings), the camera preview surface is continuously rendering frames, causing noticeable GPU/UI thread contention and slight frame stutter during card switching.
* **Feature Requirement:**
  * The camera preview should start **closed/paused by default** when opening the Camera tab.
  * Provide an explicit **"Enable Preview"** / **"Preview Muted"** toggle button.
  * During active session recording, recording can continue in the background with the preview surface detached/muted to save thermal budget and prevent any UI lag.

### 1.2 Interactive Tap-to-Focus with Metering Regions
* **Problem:** Windshield dust, wiper marks, and reflections are high-contrast obstacles near the lens. Android's default `CONTROL_AF_MODE_CONTINUOUS_VIDEO` often locks focus onto the windshield glass instead of the road ahead, blurring vehicular targets.
* **Feature Requirement:**
  * Implement an interactive Compose gesture listener (`pointerInput`) on the camera preview surface.
  * When the user taps the preview (e.g. pointing towards the horizon/road), calculate the sensor-relative coordinates via `CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE`.
  * Construct a `MeteringRectangle` centered on the tap point and apply `CaptureRequest.CONTROL_AF_REGIONS` + `CaptureRequest.CONTROL_AE_REGIONS`.
  * Trigger an explicit AF cycle (`CONTROL_AF_TRIGGER_START`) and render a brief, animated focus ring / reticle at the touch point.

### 1.3 Optical Hyperfocal / Infinity Focus Mode
* **Feature Requirement:**
  * Add an **"Infinity / Road Mode"** toggle in camera settings.
  * Set `CaptureRequest.CONTROL_AF_MODE = CONTROL_AF_MODE_OFF` with `CaptureRequest.LENS_FOCUS_DISTANCE = 0.0f` (diopters).
  * This physically locks lens focus to optical infinity (>= 3 meters), making it physically impossible for the lens to focus on the windshield glass.

---

## 2. CANedge2 Hardware & Ingestion Architecture

### 2.1 Visual Remote & Local File Hierarchy Inspector (Card Metric Drill-Downs)
* **Problem:** Right now, the cockpit card only displays aggregate numbers (`ON DEVICE: 70`, `SYNCED: 1`, `SESSION: 0`). Users have no visual feedback on what specific folders or files exist on the SD card, what has already been cached, or which files are assigned to the current drive. This makes debugging in the field opaque and confusing.
* **Feature Requirement:**
  * **Interactive Metric Cards:** Turn the three cockpit metric boxes into clickable buttons/dialog triggers:
    1. **"ON DEVICE" Click:** Opens a bottom-sheet or modal showing the full remote directory tree on the CANedge logger (`LOG/7AC5E17F/00000042/*.MF4`), highlighting which session folder has the highest serial number.
    2. **"SYNCED" Click:** Shows the list of all files cached in the local storage pool with file sizes and download status.
    3. **"SESSION" Click:** Displays the list of files specifically staged or copied into the active RoadSense session folder (`sessions/<session>/can/`), showing live transfer progress.

### 2.2 Always-On Autonomous Ingestion with Staging Pool
* **Problem:** Currently, syncing is tied to manual triggers or active recording. If a drive starts, the first split chunk takes 10 seconds to finish on the logger, meaning files aren't immediately ready when recording begins.
* **Feature Requirement:**
  * **Phase 1: Persistent Staging Pool (`sessions/canedge_pool/`)**
    * Maintain a persistent local staging pool and `canedge_catalog.json` index.
    * Prune files older than 48 hours or when pool size exceeds 500 MB.
  * **Phase 2: Always-On Background Ingestion Worker**
    * Continuously poll the latest session folder on the CANedge (every 10s) as long as Wi-Fi connection is detected.
    * Download newly closed 10-second chunks into `canedge_pool/` before the user even taps Record.
  * **Phase 3: Session Window Staging Linker**
    * When user taps "START Recording", determine the drive window [T_start, T_stop].
    * Instantly hard-link or copy matching chunks from `canedge_pool/` to `sessions/<session>/can/` with zero waiting time at drive conclusion.
  * **Phase 4: Dashboard Telemetry & State Indicators**
    * Display pool size and live states (`IDLE`, `DOWNLOADING CHUNK #N`, `STAGING TO ACTIVE SESSION`) on the dashboard card.

---

## 3. General UI & Performance Optimizations

### 3.1 Background Processing & Log Extraction Automation
* Integrate visual status indicators for ADB automated log synchronization and visualizer pipelines directly into the developer tools card.

### 3.2 GPS / Radar Dynamic Track Overlay on Video
* Optional visual preview overlay projecting decoded radar bounding boxes and tracks directly onto the camera preview surface with microsecond alignment using shutter timestamps.

---

## 4. Post-Processing Pipeline & Python Visualizer Tasks

### 4.1 Automated MF4 DBC Signal Decoding in Visualizer
* **Current State:** MF4 files are downloaded and indexed into `sessions/<session>/can/`. The Python tool syncs them to the PC.
* **Feature Requirement:**
  * Integrate an MF4 decoder (`asammdf` / `cantools`) into `sync_and_process_sessions.py`.
  * Decode CAN signals using vehicle DBC files (e.g. wheel speeds, steering angle, brake pressure, yaw rate).
  * Align decoded CAN channels against radar frame timestamps using the shared monotonic clock in `session_timeline.csv`.

### 4.2 Cross-Sensor Drift & Clock Jitter Diagnostic Script
* **Feature Requirement:**
  * Add a verification script in `tools/` that parses `session_timeline.csv` across all sensors (Radar, GNSS, Camera, CAN) and plots timestamp delta distributions.
  * Detects missing packets, frame drops, or monotonic drift automatically after each drive test.
