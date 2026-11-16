# Implementation Plan: Professional Real-Time Cross-Sensor Synchronization, Keyframe Association Table & On-Device Quality Gate

> **Goal:** Build an automotive-grade **Cross-Sensor Synchronization Engine** in `com.bajajauto.roadsense.sync` modeled after professional ADAS perception pipelines (nuScenes, Waymo, ROS 2, Apollo). The engine delivers sensor-presence-aware real-time association, creates an immutable **Synchronized Keyframe Table** under `sync/sync_keyframes.csv`, stamps an automated **Session Quality Gate** into `session_metadata.json`, displays live health on `LiveMetricsBar`, provides a rock-solid opaque inspection modal, and renders an interactive **Session Health Card** in `SessionDeckCard` when recording finishes.

---

## 1. Architectural Updates & User Feedback Incorporation

Based on your review and feedback, the following critical design decisions are codified into this plan:

| User Review Feedback | Architectural Decision & Implementation |
| :--- | :--- |
| **1. Dedicated `sync/` Folder for Keyframes** | The relational keyframe table will be stored in a dedicated subfolder: `<sessionDir>/sync/sync_keyframes.csv`. `SessionInfo.kt` is updated with `val syncDir: File = File(sessionDir, "sync")`. |
| **2. Sensor-Aware State Logic (App-State Principle)** | Calculations will never throw false errors when a sensor is disconnected. If Radar is unplugged or Camera is paused, the sync engine adapts gracefully, reporting `SYNC: 2.8 ms (Cam↔IMU) [RADAR: DISCONNECTED]` without failing. (Global `AppStateManager` concept documented for future standalone component). |
| **3. High-Contrast Opaque UI Overlay** | The inspection modal will use a 100% opaque Material 3 `ModalBottomSheet` / elevated `Surface` (`#161B22` container, `#0D1117` base, elevation `12.dp`, `zIndex = 99f`) to guarantee zero bleed-through or transparency artifacts over the camera preview or charts. |
| **4. Post-Session Health Card in `SessionDeckCard`** | When recording stops, a new interactive **"Session Sync & Stream Health"** card appears in [`SessionDeckCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/SessionDeckCard.kt), showing the certification grade (`GOLD`, `SILVER`, `WARN`), stream drop counters, and latency percentiles. |

---

## 2. Downstream Utilization in Professional ADAS Pipelines

Professional autonomous vehicle perception stacks (e.g. Apollo, Autoware, Waymo, nuScenes) use this sync engine output downstream for:

```
                               ┌──────────────────────────────────────────────┐
                               │     Cross-Sensor Sync Engine (RoadSense)     │
                               └──────────────────────┬───────────────────────┘
                                                      │
                         Emits Keyframe Association:  │ (Camera Frame #k, t_cam)
                         Nearest IMU (#m, Δt_imu)     │
                         Nearest Radar (#n, Δt_rad)   │
                                                      ▼
     ┌───────────────────────┬────────────────────────┴─┬────────────────────────┬───────────────────────┐
     │                       │                          │                        │                       │
     ▼                       ▼                          ▼                        ▼                       ▼
[1. 10x-50x Faster      [2. Point Cloud &          [3. EKF Dynamic          [4. Time-Aligned        [5. Automated Dataset
 ML Training Loaders]    Motion De-Skewing]         Covariance Inflation]    Auto-Annotation]        Curation & Quarantine]
 PyTorch Dataset         Unwarps radar points       Inflates R_k when        Eliminates bounding-    Filters out frames
 slices multimodal       into camera frame via:     measurement latency      box projection jitter   with Δt > 16.7ms
 frames in O(1) time     T_ego = [R(ω·Δt) | v·Δt]   exceeds nominal period   for 3D ground truth     from training sets
```

1. **10x–50x Faster Multi-Modal AI Training (PyTorch / TensorRT):** Pre-computed index in `sync/sync_keyframes.csv` allows PyTorch loaders to slice camera, radar, and IMU data in **$O(1)$** time without scanning or bisecting raw logs.
2. **Ego-Motion De-Skewing:** Uses high-rate IMU angular velocity $\boldsymbol{\omega}$ and linear speed $\mathbf{v}$ to unwarp radar points into the camera optical frame:
   $$\mathbf{T}_{\text{ego}}(t_{\text{rad}} \to t_{\text{cam}}) = \begin{bmatrix} \mathbf{R}(\boldsymbol{\omega} \cdot \Delta t) & \mathbf{v}_{\text{ego}} \cdot \Delta t \\ \mathbf{0} & 1 \end{bmatrix}$$
3. **EKF Dynamic Covariance Inflation:** When packet jitter occurs ($\Delta t > 16.7\text{ ms}$), downstream trackers dynamically inflate measurement noise covariance $\mathbf{R}_k(\Delta t) = \mathbf{R}_0 + \sigma_v^2 (\Delta t)^2$ to avoid false track jumps.
4. **Automated Fleet Dataset Curation:** Hard-mining queries filter out frames with operating system thread stalls before neural network training.

---

## 3. Storage Architecture: The Keyframe Table (`sync/sync_keyframes.csv`)

### 3.1 Folder Structure in Each Session
```text
sessions/session_YYYYMMDD_HHMMSS/
├── session_metadata.json          <-- Augmented with "crossSensorSync" scorecard
├── session_timeline.csv            <-- Tier 1: Master chronological diagnostic log
├── sync/                           <-- Dedicated Sync Folder
│   └── sync_keyframes.csv          <-- Tier 2: Relational multi-sensor binding table
├── camera/
│   ├── camera_video.mp4
│   └── camera_frames.csv
├── imu/
│   └── imu_frames.csv
└── radar/
    ├── radar_frames.bin
    └── radar_raw_stream.bin
```

### 3.2 Keyframe Table Format (`sync/sync_keyframes.csv`)
Each row captures one synchronized time-slice centered on the camera shutter exposure:
```csv
keyframe_id,camera_mono_ns,wall_time_ms,nearest_imu_mono_ns,delta_imu_ms,imu_seq,nearest_rad_mono_ns,delta_rad_ms,rad_seq,instantaneous_pitch_deg,sync_grade
1,1000000000,1729061234000,1000002100,+2.10,1402,998400000,-1.60,281,7.12,GOLD
2,1033333333,1729061234033,1033330100,-3.23,1405,1048400000,+15.07,282,7.08,GOLD
```

---

## 4. Proposed Component Architecture

```mermaid
graph TD
    subgraph Sensor_Aware_Capture [com.bajajauto.roadsense.sync]
        IMU[100 Hz IMU Stream] -->|pushTimestamp O(1)| CSSA[CrossSensorSyncAssociator]
        RAD[20 Hz Radar Stream] -->|pushTimestamp O(1)| CSSA
        CAM[30 FPS Camera Shutter] -->|queryAssociation O(log N)| CSSA
        STATE[Sensor Presence Tracker] -->|active mask| CSSA
    end

    subgraph Storage_Pipeline [com.bajajauto.roadsense.recording]
        CSSA -->|Tier 1: Event Summary| TLW[SessionTimelineWriter -> session_timeline.csv]
        CSSA -->|Tier 2: Keyframe Row| SKW[SyncKeyframeWriter -> sync/sync_keyframes.csv]
        SM[SessionManager.closeSession] -->|trigger| SSA[SessionSyncAuditor]
        SSA -->|stamp scorecard| META[session_metadata.json]
    end

    subgraph User_Interface [com.bajajauto.roadsense.ui]
        CSSA -->|LiveSyncTelemetry StateFlow| LMB[LiveMetricsBar SYNC Pill]
        LMB -->|tap| SBS[Opaque Sync Inspection Sheet]
        SM -->|audit result| SDC[SessionDeckCard Post-Session Health Card]
    end
```

---

### Component 1: Synchronization Engine (`com.bajajauto.roadsense.sync`)

#### `[DELETE]` [`SyncPlaceholder.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/SyncPlaceholder.kt)
Remove the empty placeholder file.

#### `[NEW]` `com/bajajauto/roadsense/sync/CrossSensorSyncAssociator.kt`
* **Purpose:** High-performance, lock-free, zero-allocation temporal associator.
* **Sensor-Aware State Handling:**
  - Maintains active presence flags: `isRadarActive`, `isImuActive`, `isCameraActive`.
  - Auto-detects stream timeouts: if radar frames cease for $> 2.0\text{ s}$, `isRadarActive` automatically transitions to `false`.
  - When querying association:
    - If Radar is inactive: `delta_rad_ms` is flagged as `N/A`, and radar temporal penalty is bypassed.
    - If IMU is active: associates with nearest IMU sample in $O(\log N)$ without allocating memory.
* **Ring Buffers:**
  - Pre-allocated `LongArray(128)` for IMU and `LongArray(32)` for Radar.
* **Output:**
  - Returns `KeyframeAssociation(camMonoNs, imuMonoNs, imuDeltaMs, imuSeq, radMonoNs, radDeltaMs, radSeq, pitchDeg, grade)`.
  - Exposes `val liveSyncTelemetry: StateFlow<LiveSyncTelemetry>` smoothed at 5 Hz for UI.

#### `[NEW]` `com/bajajauto/roadsense/sync/SyncKeyframeWriter.kt`
* **Purpose:** Dedicated non-blocking writer for `sync/sync_keyframes.csv` within the active session.
* Uses buffered I/O on a background single-thread executor to ensure zero impact on camera capture frame rates.

---

### Component 2: Session Recording & Metadata Pipeline (`com.bajajauto.roadsense.recording`)

#### `[MODIFY]` [`SessionInfo.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionInfo.kt) & [`SessionManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionManager.kt)
* Add `val syncDir: File = File(sessionDir, "sync")`.
* In `SessionManager.createSession()`, create `sync/` directory alongside `camera/`, `imu/`, `radar/`.
* Register `sync/sync_keyframes.csv` in `session_metadata.json` stream index.
* In `SessionManager.closeSession()`:
  - Run `SessionSyncAuditor.auditSession(sessionInfo)`.
  - Populate `"crossSensorSync"` in `session_metadata.json`.

#### `[NEW]` `com/bajajauto/roadsense/recording/SessionSyncAuditor.kt`
* **Parity with Python Script:** Directly ports the statistical routines from [`audit_cross_sensor_sync.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py).
* **Sensor-Aware Auditing:**
  - If radar file is empty or absent, marks Radar as `SKIP (Disconnected)` and evaluates the session based on Camera-IMU synchronization.
  - Computes steady-state mean proximity $\Delta t$, median ($p_{50}$), $p_{95}$, $p_{99}$, peak max offset, drop counts ($> 50\text{ ms}$), and sampling jitter ($> 20\text{ ms}$).
  - Assigns overall certification grade (`GOLD`, `SILVER`, `WARN`, `DEGRADED`).
* **Returns:** `SessionSyncAuditResult` containing both structured JSON data and formatted diagnostic strings for the UI.

#### `[MODIFY]` [`CameraSessionRecorder.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/CameraSessionRecorder.kt)
* When each camera frame is recorded:
  1. Query `CrossSensorSyncAssociator`.
  2. Write row to `sync/sync_keyframes.csv` via `SyncKeyframeWriter`.
  3. Augment `SessionTimelineWriter` `CAMERA:FRAME` summary string (`imu_dt=X.Xms;rad_dt=Y.Yms`).

---

### Component 3: Live UI Telemetry, Opaque Modal & Post-Session Health Card (`com.bajajauto.roadsense.ui`)

#### `[MODIFY]` [`LiveMetricsBar.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/LiveMetricsBar.kt)
* Add `SYNC` metric pill:
  - **Radar Connected:** `SYNC 2.8 ms` (Green: Cam-IMU $< 3.5\text{ ms}$, Rad-Cam $< 16.7\text{ ms}$).
  - **Radar Disconnected:** `SYNC 2.8 ms (Cam-IMU)` (Green: evaluations reflect active sensors only).
  - **Jitter Spike:** `SYNC 14.2 ms ⚠️` (Amber).
  - **Stall:** `SYNC STALL` (Red).

#### `[NEW]` `com/bajajauto/roadsense/ui/components/SyncInspectionBottomSheet.kt`
* **Design & Aesthetics:**
  - High-density, professional automotive dark theme (`#161B22` container, `#0D1117` base).
  - **100% Opaque:** Uses Material 3 `ModalBottomSheet` or elevated `Surface` with `zIndex(99f)` and elevation `12.dp` — strictly zero transparency bleed-through.
* **Content:**
  - **Camera ↔ IMU Alignment:** Live $\Delta t$, rolling average, Nyquist limit ($5.0\text{ ms}$).
  - **Radar ↔ Camera Alignment:** Live $\Delta t$, rolling average, half-frame limit ($16.7\text{ ms}$), or "Disconnected (Bypassed)".
  - **Stream Continuity:** Drop counter for each sensor.
  - **Live Quality Grade Preview:** `GOLD (99.4% compliant)`.

#### `[MODIFY]` [`SessionDeckCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/SessionDeckCard.kt)
* In `SessionRecordingState.Finished`:
  - Render a dedicated **"Session Sync & Sensor Health Scorecard"**:
    - **Grade Badge:** Prominent `GOLD`, `SILVER`, or `WARN` colored badge.
    - **Sensor Status Matrix:** Clear rows showing each attached sensor's effective rate, drop count, and alignment delta.
    - **Anomaly Highlights:** Any dropped frames or radar disconnections are clearly flagged with red/amber badges so the tester instantly knows if the test drive had mishaps.

---

## 5. Verification Plan

### 5.1 Automated Unit Tests
* **`CrossSensorSyncAssociatorTest.kt`:**
  - Verify sensor disconnection handling (radar unplugged -> no crash, clean fallback).
  - Verify ring buffer modulo wraparound and sub-microsecond binary search.
* **`SessionSyncAuditorTest.kt`:**
  - Run on existing session logs (`session_20261015_144546` and `session_20261012_164153`).
  - Validate numerical agreement with `audit_cross_sensor_sync.py` ($\pm 0.05\text{ ms}$).

### 5.2 Device Verification on Samsung Galaxy M30
1. Deploy `app-debug.apk` to the connected test device (`32001991d42c86d9`).
2. Test Scenario A (Camera + IMU only, Radar disconnected):
   - Observe `LiveMetricsBar`: `SYNC` pill reflects Camera-IMU sync without errors.
   - Tap `SYNC` pill: Verify opaque inspection modal displays without transparency bleed.
   - Stop session: Verify `SessionDeckCard` shows `GOLD (Camera + IMU)` and notes `Radar: Disconnected`.
3. Test Scenario B (Full Sensor Suite: Radar + Camera + IMU):
   - Observe `LiveMetricsBar`: `SYNC` pill tracks full multi-sensor sync.
   - Stop session: Verify `sync/sync_keyframes.csv` contains all columns and `session_metadata.json` has complete `"crossSensorSync"` scorecard.
4. Run `python scripts/audit_cross_sensor_sync.py` to confirm 100% agreement.
