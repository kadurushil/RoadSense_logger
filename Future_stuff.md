# RoadSense: Future Enhancements & Pending Tasks Backlog

> **Purpose:** Centralized roadmap and feature backlog for pending architectural upgrades, hardware integrations, and user experience enhancements across RoadSense.  
> **Document Location:** `Future_stuff.md`  
> **Maintainers:** RoadSense Engineering & Autonomous AI Coding Agents  

---

## 1. Camera Subsystem Upgrades

### 1.1 Start Camera Preview Closed by Default (Lag Elimination) [COMPLETED]
* **Status:** Implemented. Camera preview starts closed/muted by default on every app launch. Viewfinder is activated on-demand via the "Enable Viewfinder" button or eye icon toggle. In-memory state is preserved across tab navigation during an active session and resets to off upon app restart.
* **Feature Requirement:**
  * The camera preview should start **closed/paused by default** when opening the Camera tab.
  * Provide an explicit **"Enable Preview"** / **"Preview Muted"** toggle button.
  * During active session recording, recording can continue in the background with the preview surface detached/muted to save thermal budget and prevent any UI lag.

### 1.2 Interactive Tap-to-Focus with Metering Regions & AE Lock [COMPLETED]
* **Status:** Implemented. Viewfinder touch gestures capture normalized coordinates, map to sensor active array with orientation compensation, trigger single-shot AF sweep & AE precapture, and lock both focus distance (`CONTROL_AF_MODE_AUTO` + `IDLE`) and auto-exposure (`CONTROL_AE_LOCK = true`). Visual feedback includes an interactive target reticle (`AF/AE LOCK`) and a one-tap `[Reset Lock]` button.

### 1.3 Optical Hyperfocal / Infinity Focus Mode [COMPLETED]
* **Status:** Implemented. Setting `CaptureRequest.CONTROL_AF_MODE = CONTROL_AF_MODE_OFF` with `CaptureRequest.LENS_FOCUS_DISTANCE = 0.0f` (diopters) physically clamps the lens motor to optical infinity (>= 3m), preventing windshield dust, glare, and wiper blades from stealing focus. Seamlessly integrated with session recording.

### 1.4 Autonomous Dual-Zone Luminance Analysis (Dynamic Sky-Bloom Elimination)
* **Problem:** In forward-facing automotive cameras, the upper 30–40% of the frame captures bright ambient sky, direct sunlight, and cloud glare. Default matrix/evaluative 3A metering averages over the entire scene, causing the camera's ISP to aggressively lower exposure. As a result, the bottom 60–70% of the frame (road asphalt, oncoming vehicles, shadows, license plates, and lane markers) becomes severely underexposed and plunged into dark shadows.
* **Algorithmic Architecture (Approach 3: Real-Time Dual-Zone Photometric Contrast):**
  1. **Dual-Zone Luminance Partitioning:**
     - The scene is partitioned along real-world orientation into two photometric zones:
       - **Zone 1 (Sky / Upper 35%):** $\mathcal{Z}_{\text{sky}} = \{ (x, y) \mid y \in [0.0, 0.35], x \in [0.1, 0.9] \}$
       - **Zone 2 (Road / Lower 65%):** $\mathcal{Z}_{\text{road}} = \{ (x, y) \mid y \in [0.35, 1.0], x \in [0.1, 0.9] \}$
  2. **Photometric Contrast Ratio Evaluation:**
     - A lightweight background analyzer samples frame luminance at 5–10 Hz (e.g. from downsampled Y-luminance buffers or Camera2 metadata):
       $$R_{\text{contrast}} = \frac{\bar{L}_{\text{sky}}}{\bar{L}_{\text{road}}}$$
     - **Condition A (Sky Bloom / Harsh Daytime):** $R_{\text{contrast}} \ge 2.0$  
       $\rightarrow$ Sky is blowing out the scene. Restrict `CaptureRequest.CONTROL_AE_REGIONS` strictly to $\mathcal{Z}_{\text{road}}$ and apply adaptive EV boost ($+0.5$ to $+1.0$ EV) to maintain road visibility.
     - **Condition B (Balanced / Overcast):** $1.0 \le R_{\text{contrast}} < 2.0$  
       $\rightarrow$ Soft cloud cover or uniform light. Meter with center-weighted road focus with neutral EV bias ($0.0$ EV).
     - **Condition C (Night / Tunnel / Inverted):** $R_{\text{contrast}} < 1.0$  
       $\rightarrow$ Headlights on dark road or entering a tunnel. Automatically relax back to full-frame matrix metering to prevent over-amplifying noise on the dark road.
  3. **Hysteresis & Temporal Smoothing:**
     - Filter $R_{\text{contrast}}$ with an Exponential Moving Average (EMA, $\alpha = 0.2$) across consecutive frames to prevent rapid exposure oscillation under overhead power lines, traffic lights, or road bridges.
  4. **Driver Controls & Telemetry:**
     - **Mode Deck:** `AUTO ROAD AE` (Smart Dual-Zone, Default) vs `FULL MATRIX` (Legacy standard metering).
     - **Status Badge:** Live viewfinder pill displaying `ROAD AE` when sky rejection is active.
     - **Tap Lock Precedence:** Any explicit tap-to-focus lock immediately overrides the autonomous metering zone until `[Reset Lock]` is pressed.

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

---

## 5. FUSION_STEPS — Radar-Camera Spatial Calibration & Multimodal ADAS Fusion

> **Reference Spec:** See full theoretical, mathematical, and keystone formulation in [`intel/Sensor fusion basics.md`](intel/Sensor%20fusion%20basics.md).

### 5.1 Strategic Architecture & The 3 Critical Safeguards
1. **Radar Ground Truth:** TI AWR1843 mmWave radar provides absolute metric range ($Y_r$) and radial Doppler velocity ($V_{\text{doppler}}$) immune to glare, shadows, and darkness. The camera provides dense semantic classification and 2D bounding boxes.
2. **Field-Deployable Calibration (Under 60 Seconds):** Traditional lab checkerboards and laser alignment cannot be required every time the phone mount is adjusted. RoadSense employs a **Keystone HUD** and **Target Pin & Snap** interaction.
3. **Flat-Earth Road-Plane Anchor ($Z_{\text{road}} \equiv 0$):** Due to the coarse elevation resolution of the standard AWR1843 antenna layout ($\approx 58^\circ$), road obstacles and vehicle bounding boxes are anchored to the ground plane to prevent false vertical floating.

---

### 5.2 Phase 1: Spatial Projection Engine & Keystone Calibration (Immediate Priority) [COMPLETED]
* **Status:** Implemented. 2D planar radar forward projection engine (`SpatialProjectionEngine.kt`), Camera2 intrinsics extraction (`CameraIntrinsicsProvider.kt`), closed-form reverse solver for real-time drag alignment over parked target vehicle, 100% GUI-adjustable 6-DOF vehicle extrinsics (`CalibrationParameters.kt`), dedicated `CameraCalibrationCard.kt`, `QuickNudgeBar.kt`, and edge-to-edge `FullscreenCalibrationStudio.kt`. Persists to `radar_camera_calib.json` via `CalibrationStorageManager.kt`. Unit tests in `SpatialProjectionEngineTest.kt`.
* **Pinhole Camera Intrinsics ($K$):**
  * Automatically extracted via Android Camera2 `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` or calculated from sensor active array and HFOV:
    $$f_{\text{pixels}} = \frac{W_{\text{pixels}}}{2 \cdot \tan(\text{HFOV} / 2)}, \quad c_x = \frac{W}{2}, \quad c_y = \frac{H}{2}$$
* **6-DOF Extrinsic Rigid Transformation ($[\mathbf{R} \mid \mathbf{T}]$):**
  $$\mathbf{P}_c = \mathbf{R} \cdot \mathbf{P}_r + \mathbf{T}$$
  * $\mathbf{T} = [\Delta X, \Delta Y, \Delta Z]^T$ (lateral clamp offset $\approx 0.0\text{m}$, longitudinal setback $\approx 1.8\text{m}$, bumper-to-windshield height $\approx 1.0\text{m}$).
  * $\mathbf{R} = \mathbf{R}_x(\theta) \cdot \mathbf{R}_y(\psi) \cdot \mathbf{R}_z(\phi)$ (pitch tilt $\theta$, heading yaw $\psi$, mount roll $\phi$).
* **Screen Projection Equation:**
  $$u = f_x \frac{X_c}{Z_c} + c_x, \quad v = f_y \frac{Y_c}{Z_c} + c_y$$
* **Interactive Calibration UX:**
  * **Proposal 3 (Keystone HUD):** Sliders and $\pm 0.1^\circ$ nudge buttons for Pitch (horizon), Yaw (boresight), Roll, Height, and Setback.
  * **Proposal 1 (Target Pin & Snap):** Floating radar reticle at target distance (e.g. parked car at $10.2\text{m}$). Tester drags reticle onto vehicle bumper; solver auto-calculates $\Delta \theta$ and $\Delta \psi$.
  * **Persistence:** Stored in `calibration/radar_camera_calib.json` with hardware mount presets.

---

### 5.3 Phase 2: Live Viewfinder Fusion & ADAS HUD Overlay
* **Doppler-Aware Point Cloud Rendering:**
  * Real-time 20 Hz projection onto active `TextureView` via Compose `Canvas`.
  * Velocity color-coding: **Cyan** (stationary road furniture), **Green** (receding traffic), **Amber/Red** (closing/approaching hazards).
  * Transparency modulated by radar SNR.
* **3D Perspective Obstacle Bounding Boxes:**
  * For TI EKF tracks (TLV Type 7), project perspective 3D cuboids or road contact footprints.
  * Floating telemetry tag: `ID #3 | 18.4 m | -28 km/h | TTC: 1.6s ⚠️`.
* **Forward Collision Warning (FCW):**
  * Real-time Time-to-Collision ($\text{TTC} = Y / |V_r|$). Auditory beep and visual flashing border when $\text{TTC} < 2.0\text{ s}$.

---

### 5.4 Phase 3: Semantic AI Association & Deep Fusion (Advanced)
* **On-Device 2D Detection:** Lightweight YOLOv8-nano running via TFLite / NNAPI for vehicle, two-wheeler, and pedestrian bounding boxes.
* **IoU & Mahalanobis Distance Gating:** Associating 2D vision detections with 3D radar tracks.
* **Result:** High-confidence multimodal objects where the camera supplies the semantic label and the radar supplies exact metric depth and velocity.

---

### 5.5 Automated Radar-Camera Pitch Calibration & Dynamic Stabilization via IMU
* **Reference Document:** Full theoretical derivation, formulas, and blueprint in [`intel/Implementations/Sensors/RADAR_PITCH_CALIBRATION_VIA_IMU.md`](intel/Implementations/Sensors/RADAR_PITCH_CALIBRATION_VIA_IMU.md).
* **Motivation:**
  * Pitch tilt angle ($\theta$) is the most critical and sensitive extrinsic parameter. A $1.0^\circ$ pitch error creates a **$\approx 28\text{ px}$ vertical shift** on 1080p and a **$0.87\text{ m}$ height error** at $50\text{ m}$ forward range.
* **Feature Roadmap:**
  1. **One-Tap Level Ground Calibration:**
     * When parked on flat ground, query `Sensor.TYPE_GRAVITY` and 100 Hz `Sensor.TYPE_ACCELEROMETER`.
     * Closed-form inclination angle: $\theta_{\text{mount}} = \arctan2(-g_z, \sqrt{g_x^2 + g_y^2})$ (rear camera optical boresight is $-Z_{\text{phone}}$).
     * 100-sample averaging achieves **$\pm 0.03^\circ$ accuracy** ($< 1\text{ px}$ error) with a single tap, eliminating manual slider adjustment.
  2. **Dynamic In-Drive Pitch Compensation (Suspension Dynamics):**
     * High-rate 100 Hz `GameRotationVector` and Gyroscope pitch rate ($\omega_y$) compute real-time suspension dynamics:
       $$\Delta \theta(t) = \theta_{\text{attitude}}(t) - \theta_{\text{baseline}}$$
     * Dynamically adjusts the camera projection matrix during hard braking (front nose-dip $-1.5^\circ$ to $-3.0^\circ$) and acceleration squat ($+1.0^\circ$ to $+2.0^\circ$).
     * Prevents radar footprints and lollipops from jumping upward into the sky during vehicle braking.
  3. **Mount Slippage & Health Monitoring:**
     * Detects when the windshield mount shifts or slips over road bumps while stopped at red lights, offering a one-tap `[Auto Re-align]` HUD banner.

