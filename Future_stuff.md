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

### 2.1 Unified CANedge File Explorer with OneDrive-Style Status Column
* **Problem:** Currently, having three separate modal dialogs for "ON DEVICE", "SYNCED", and "SESSION" fragments the user's mental model and forces them to jump between multiple popups to determine file status. Moreover, as the logger accumulates dozens of power-cycle folders over time, querying everything causes MCU connection latency and risks bloating Android storage.
* **Feature Requirement:**
  * **Unified Single-Window Explorer:** Replace the three separate dialogs with a single, unified file explorer dialog modeled after Windows File Explorer / OneDrive.
  * **Interactive Explorer Mockup:**
    ```text
    ┌─────────────────────────────────────────────────────────────────────────────┐
    │ 📁 CANedge File Explorer                                       [Sync] [✕]   │
    ├─────────────────────────────────────────────────────────────────────────────┤
    │ ☁️ SD Card: 18 folders (70 files)  |  💾 Pool: 3 files (7 MB) | 🎬 Session: 1│
    ├─────────────────────────────────────────────────────────────────────────────┤
    │ ▼ 📁 00000034/ [LATEST SESSION] (2 files • 4.8 MB)                          │
    │   ├── 00000001.MF4    2.4 MB   10:24:10   🎬 IN SESSION   (Green Check)     │
    │   └── 00000002.MF4    2.4 MB   10:24:20   💾 IN POOL      (Blue Check)      │
    │                                                                             │
    │ ▼ 📁 00000033/ [PRE-RESTART DRIVE] (1 file • 2.4 MB)                        │
    │   └── 00000001.MF4    2.4 MB   10:23:50   🎬 IN SESSION   (Green Check)     │
    │                                                                             │
    │ ▶ 📁 00000032/ [ROLLING SCOPE] (1 file • 2.4 MB)                            │
    │   └── 00000001.MF4    2.4 MB   10:15:00   ☁️ REMOTE ONLY  (Cloud Icon)      │
    └─────────────────────────────────────────────────────────────────────────────┘
    ```

  * **OneDrive-Style Status Column & State Machine:**
    | Status State | Visual Badge / Icon | Description & Location |
    | :--- | :--- | :--- |
    | **`REMOTE_ONLY`** | ☁️ Gray Cloud Icon | File exists on CANedge SD card; not yet downloaded to local phone storage. |
    | **`SYNCING`** | ⏳ Blue Spinner / Progress | File is actively being downloaded over Wi-Fi HTTP from logger to staging pool. |
    | **`IN_POOL`** | 💾 Blue / Secondary Checkmark | Downloaded and safely cached in local staging pool (`sessions/canedge_pool/`). |
    | **`IN_SESSION`** | 🎬 Green Check / Filmstrip | Assigned to active/recorded session (`sessions/<session>/can/`) & indexed in `session_timeline.csv`. |

  * **Top-5 Rolling Session Window & Power-Cycle Continuity:**
    * **Folder Numbering:** CANedge creates an 8-digit decimal folder on each power cycle (`00000033/`, `00000034/`).
    * **Vehicle Restart Handling:** If the vehicle stalls or restarts mid-drive, CANedge increments to a new folder. Because the app scans the top 5 directories descending:
      $$\mathcal{W}_{\text{sync}} = \{ D_{(0)}, D_{(1)}, D_{(2)}, D_{(3)}, D_{(4)} \}$$
      chunks generated before and after the restart are both ingested seamlessly without losing pre-restart data.
    * **Collision Prevention:** Local files prepend folder numbers (`00000033_00000001.MF4`, `00000034_00000001.MF4`).

  * **Multi-Tier Storage Management Policy:**
    | Storage Tier | Location | Cleanup Mechanism | Rules & Safeguards |
    | :--- | :--- | :--- | :--- |
    | **CANedge SD Card** | `/LOG/<DEVICE_ID>/` | Firmware Cyclic Logging (`"cyclic": 1`) | Automatic FIFO directory deletion when SD fills up. No risk of Wi-Fi filesystem corruption. |
    | **Android Staging Pool** | `sessions/canedge_pool/` | Rolling LRU Pruning | 1. Files from folders outside top 5 are automatically pruned.<br>2. Pool size capped at 300 MB.<br>3. Manual "Prune Pool" button in Explorer. |
    | **Permanent Sessions** | `sessions/<session>/can/` | User / Script Managed | Preserved permanently until user deletes session or exports via `sync_and_process_logs.bat`. |

  * **Implementation Steps & Scope:**
    1. **Data Model (`CanedgeUnifiedFileItem.kt`):** Define `CanedgeFileStatus` enum and UI wrapper combining remote metadata, local file references, and download progress.
    2. **Repository Top-5 Window (`CanedgeRepository.kt`):** Implement `listRecentSessionMf4Files(device, maxFolders = 5)` to query only the 5 latest directories descending.
    3. **Ingestion Manager Integration (`CanedgeIngestionManager.kt`):**
       - Route downloads directly to persistent staging cache `sessions/canedge_pool/`.
       - Expose reactive `unifiedFiles: StateFlow<List<CanedgeUnifiedFileItem>>`.
       - Implement `pruneStagingPool()` to enforce 300 MB cap and drop files outside top 5 folders.
       - Implement session window linker to copy matching chunks into `sessions/<session>/can/`.
    4. **Unified UI Dialog (`CanedgeDashboardCard.kt` / `CanedgeFileExplorerDialog.kt`):**
       - Replace the three separate dialogs with a single accordion tree view.
       - Render folder headers with summary badges (`[2 files • 4.8 MB]`, `LATEST`).
       - Render file rows with size, timestamp, and OneDrive-style status badges.
       - Include action buttons for "Sync Now", "Prune Pool", and "Close".

### 2.2 Autonomous Ingestion with Deferred Session Staging & Prioritization
* **Philosophy:** Real-time CAN bus logging is physically handled by the CANedge logger hardware onto its internal SD card with zero risk of frame drops. The Android phone's real-time priority during drive recording is **Radar (3.125 Mbps UART)** and **Camera (H.264 Video Encoding)**.
* **Feature Requirement:**
  * **Deferred Sync During Active Recording:**
    * During active recording, high-volume Wi-Fi copying is **deferred or paused** to guarantee 100% CPU and network bandwidth for Radar and Video.
    * CANedge sync operates actively when idle, steadily building the local staging pool (`sessions/canedge_pool/`).
  * **Post-Recording Staging Finalizer:**
    * When the user taps **STOP Recording**, RoadSense finalizes CAN logs:
      1. Pulls any remaining closed chunks from the drive into `canedge_pool/`.
      2. Selects all chunks matching the drive time window $[T_{\text{start}}, T_{\text{stop}}]$ and copies them into `sessions/<session>/can/`.
      3. Back-calculates the physical recording monotonic timestamps:
         $$\text{chunkMonotonicNs} = T_{\text{session\_start\_mono}} + (T_{\text{chunk\_written}} - 10\text{s} - T_{\text{session\_start\_wall}}) \times 10^6$$
      4. Logs each chunk into `session_timeline.csv` anchored at $T \approx 0\text{s}$ (synchronous with the very first radar frame and video packet) instead of the delayed download arrival time!
  * **Intuitive Planned vs Synced UI Status Bar:**
    * Replace confusing aggregate totals (`ON DEVICE: 88` vs `SYNCED: 24`) with an intuitive numerical ratio:
      $$\mathbf{24 / 26\text{ Synced}}$$
    * Accompanied by a clean horizontal fill bar showing percentage progress.
    * Remove search emoji icons (`🔍`) from small cockpit cards for clean, professional automotive telemetry aesthetics.

---

## 3. General UI & Performance Optimizations

### 3.1 Background Processing & Log Extraction Automation
* Integrate visual status indicators for ADB automated log synchronization and visualizer pipelines directly into the developer tools card.

### 3.2 GPS / Radar Dynamic Track Overlay on Video
* Optional visual preview overlay projecting decoded radar bounding boxes and tracks directly onto the camera preview surface with microsecond alignment using shutter timestamps.

---

## 4. CAN Post-Processing, Viewing Tools & In-App Signal Parsing

### 4.1 Ecosystem Tools to Open & View MF4 Files Directly
Automotive engineers have several powerful tools to inspect, graph, and analyze `.MF4` files without manual programming:
1. **Vector vSignalyzer 17.0 (Installed on PC):**
   * Path: `C:\Program Files\Vector vSignalyzer 17.0`
   * Natively opens `.MF4` files directly. Supports associating vehicle `.DBC` databases, plotting multi-channel time-series graphs, calculating FFTs, and exporting to CSV/MATLAB/ASC.
2. **`asammdf` Desktop GUI:**
   * Open-source Python tool (`pip install asammdf[gui]`). Provides an intuitive UI to open MF4 files, inspect raw CAN frames, drag-and-drop DBC signals onto synchronized waveform plots, and export to BUSMASTER `.log`, Vector `.asc`, or CSV.
3. **BUSMASTER (v3.2.2 - Installed on PC):**
   * Path: `C:\Program Files (x86)\BUSMASTER_v3.2.1`
   * Opens ASCII `.log` files natively for CAN bus playback, signal plotting, and DBC filtering.
4. **PlotJuggler:**
   * High-speed, lightweight desktop visualizer for multi-sensor time-series. Excellent for overlaying vehicle speed curves directly against radar obstacle distance curves.

---

### 4.2 Automated PC Pipeline Converter (`sync_and_process_logs.bat`)
* **Problem:** Manually transferring and stitching dozens of 10-second MF4 split files is tedious.
* **Feature Requirement:**
  * Enhance `tools/sync_and_process_sessions.py` (invoked via `sync_and_process_logs.bat`) with an automated CAN processor:
    1. **Stitching:** Reads all split `.MF4` files from `sessions/<session>/can/` in chronological order.
    2. **BUSMASTER Export:** Generates a unified, human-readable **`session_busmaster.log`** matching the exact 7-column format:
       ```text
       ***BUSMASTER Ver 3.2.2***
       ***PROTOCOL CAN***
       ...
       ***<Time><Tx/Rx><Channel><CAN ID><Type><DLC><DataBytes>***
       12:41:13:4948 Tx 1 0x600 s 8 00 01 00 01 FF B8 00 00 
       12:41:13:4978 Rx 1 0x316 s 8 3F 41 F4 00 32 08 FA 22 
       ```
    3. **Consolidated MF4:** Optionally merges chunks into a single `session_can_merged.mf4` using `asammdf`.
    4. **Timeline Alignment:** Generates `can_signals.json` aligned microsecond-for-microsecond with `session_timeline.csv` and video frame timestamps.

---

### 4.3 Pure Java/Kotlin In-App MF4 Parser & Signal Telemetry
* **Can we parse CANedge MF4 on Android in pure Kotlin? YES!**
* **Reverse-Engineered Fixed-Width Stride (36 Bytes):**
  * CANedge writes a deterministic variant of ASAM MDF v4.10 (`UnFinMF `).
  * Data records start at offset `14632` and repeat with an exact **36-byte stride**:
    ```text
    ┌──────────────┬──────────────┬───────────────────────────────┬────────────────────────┐
    │ Rec ID (4B)  │ Timestamp    │ CAN ID / Channel Metadata     │ CAN Payload Data       │
    │ uint32 = 1   │ uint64 (ns)  │ 16 Bytes (ID, Flags, DLC)     │ 8 Bytes Raw Payload    │
    └──────────────┴──────────────┴───────────────────────────────┴────────────────────────┘
    ```
* **Pure Kotlin DBC Signal Extraction:**
  * By reading the 36-byte records directly from `can/*.MF4` using `java.nio.ByteBuffer`, a lightweight Kotlin class (`CanedgeMf4Reader.kt`) can extract raw CAN frames without any native C++ or Python dependencies!
  * With a lightweight DBC bit-unpacking routine:
    $$\text{PhysicalValue} = (\text{RawBits} \times \text{Scale}) + \text{Offset}$$
  * Key signals can be extracted on the phone:
    * **Vehicle Speed** (e.g. `0x316` / Wheel Speed) $\to \text{km/h}$
    * **Steering Wheel Angle** $\to \text{degrees}$
    * **Brake Switch / Throttle Position** $\to \%$
    * **Yaw Rate / Longitudinal Acceleration** $\to \text{deg/s}, \text{m/s}^2$

---

### 4.4 In-App Telemetry & Radar-CAN Fusion Graphing
* **Feature Requirement:**
  * Add a **"CAN Telemetry"** card / tab to RoadSense:
    * **Live/Playback Time-Series Chart:** Plots vehicle speed as a dynamic line graph beside the radar track list.
    * **Ego-Motion Compensation for Radar:** Uses the decoded vehicle speed to differentiate between truly stationary roadside objects and moving vehicles ahead.
    * **Driver Reaction Analysis:** Visualizes brake activation timing relative to radar forward collision warnings (TTC - Time To Collision).
