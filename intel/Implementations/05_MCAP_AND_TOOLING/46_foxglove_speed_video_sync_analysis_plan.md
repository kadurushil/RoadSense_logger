# Implementation Plan: Master Sensor Synchronization via `session_timeline.csv` (Foxglove & Visualizer Engine)

## Goal Description
Eliminate cross-sensor synchronization discrepancies (such as vehicle speed appearing to rise in the plot panel before the vehicle moves in the camera frame) across Foxglove Studio (`.mcap`) and the web visualizer (`frame_mapping.json`).
Establish `session_timeline.csv` as the **authoritative master ground truth backbone** so that all channels—**Camera, Radar (Points, Tracks, Diagnostics, ADAS), Vehicle Telemetry (Powertrain CAN / TLV5), GNSS (Fixes, Speed, Course), and IMU (100 Hz Accel, Gyro, Orientation Pose)**—are synchronized with microsecond precision and zero startup phase drift.

---

## Architectural Diagnosis & Discovered Flaws

### 1. The Pre-Roll Shutter Desynchronization Bug (Empirical Proof from `session_20261012_164153`)
- **Android Logging Sequence:**
  - `16:41:54.004`: `CameraSessionRecorder` begins logging rows into `camera_frames.csv` and `session_timeline.csv` (rows 0..12, frames 2924..2936). At this instant, the camera is still in its initial **preview mode**.
  - `16:41:54.642`: `CameraEngine` reconfigures the capture session for hardware video recording (`restartSession()`).
  - `16:41:54.915`: `CameraEngine` starts `MediaRecorder` (`backgroundHandler?.postDelayed({ mediaRecorder?.start() }, 300)`).
  - `16:41:54.902`: The first video frame encoded into `camera_video.mp4` arrives at **frame 2937** (row 13 in `camera_frames.csv`).
  - **The Defect:** Rows 0 to 12 in `camera_frames.csv` have **no corresponding video packets** in `camera_video.mp4`.
- **The Cascade in `convert_session_to_mcap.py` & `sync_and_process_sessions.py`:**
  - Both conversion scripts naively assumed:
    $$\text{MP4 Packet } i \iff \text{CSV Row } i$$
  - Packet 0 in the video container (recorded at $t \approx 16:41:54.902$) was assigned `shutter_timestamps[0]` ($16:41:54.004$).
  - **Every single video frame was timestamped ~911 ms too early.**
  - When the vehicle engaged motor torque ($13.2\text{ Nm}$) and registered its first wheel speed tick ($0.8\text{ km/h}$) at $t = 3.96\text{s}$, the playback cursor in Foxglove reached $3.96\text{s}$, plotting the speed rise while displaying video frame 104—which was physically captured at $t = 3.05\text{s}$ while the car was motionless with the brake pressed!
  - The video did not show forward motion until around frame 180 ($t \approx 6.0\text{s}$), creating an apparent **~1.5 to 2-second lead of speed over video**.

### 2. Complete Bypass of `session_timeline.csv`
- While Android's `SessionTimelineWriter` logs every sensor event into `session_timeline.csv` with nanosecond monotonic timestamps (`elapsed_realtime_ns`) and hardware sequence IDs, **neither `convert_session_to_mcap.py` nor `sync_and_process_sessions.py` ever ingested `session_timeline.csv`**!
- Each converter parsed isolated files (`radar_frames.bin`, `gnss_fixes.csv`, `imu_frames.csv`, `camera_frames.csv`) independently without validating against the master timeline.

---

## User Review Required

> [!IMPORTANT]
> **Pre-Roll Boundary Detection**:
> `camera_video.mp4` does not contain frames from the pre-recording preview phase.
> The proposed master timeline engine automatically identifies the true video start sequence ID ($S_{\text{record}}$) by locating the session reconfiguration gap ($\Delta t > 250\text{ ms}$) in `session_timeline.csv` and cross-verifying with `session_debug.log` `Started MediaRecorder`.
> All MP4 video packets are mapped starting from $S_{\text{record}}$, eliminating the 911 ms pre-roll offset permanently.

> [!NOTE]
> **Dropped Frame Immunity**:
> In `session_20261012_164153`, `camera_frames.csv` logged 32,647 frames while `camera_video.mp4` contains 32,641 frames (6 dropped frames over 18 minutes).
> Naive 0-based array indexing accumulates phase error whenever a frame is dropped.
> Under the new master timeline engine, each video packet is matched to `session_timeline.csv` using its **Presentation Timestamp (PTS)** and binary search (`bisect`), ensuring zero cumulative drift.

---

## Proposed Changes

```mermaid
flowchart TD
    subgraph MasterBackbone [Master Ground Truth Backbone]
        STL[session_timeline.csv] --> Parser[MasterTimelineSynchronizer]
        SMeta[session_metadata.json] --> Parser
        SLog[session_debug.log] --> Parser
    end

    subgraph TimelineAlignment [Cross-Sensor Unified Event Alignment]
        Parser --> CamAlign[Camera Stream: MP4 PTS -> Timeline Sequence ID]
        Parser --> RadAlign[Radar Stream: Hardware Frame -> Timeline Event]
        Parser --> CanAlign[Telemetry / VCU: TLV 5 -> Monotonic Timestamp]
        Parser --> GnssAlign[GNSS Fixes: GPS Time -> Monotonic Timestamp]
        Parser --> ImuAlign[IMU Stream: 100 Hz Continuous -> Monotonic Timestamp]
    end

    subgraph DownstreamSinks [Downstream Producers]
        CamAlign & RadAlign & CanAlign & GnssAlign & ImuAlign --> MCAPWriter[tools/convert_session_to_mcap.py]
        CamAlign & RadAlign & CanAlign & GnssAlign --> VisWriter[tools/sync_and_process_sessions.py]
        CamAlign & RadAlign & CanAlign & GnssAlign --> AuditEngine[scripts/cross_sensor_sync_engine.py]
    end

    MCAPWriter --> FoxgloveMCAP[session.mcap (100% Synchronized)]
    VisWriter --> FrameMapping[frame_mapping.json (Accurate MP4 Index)]
    AuditEngine --> HTMLReport[sync_audit_report.html (Dynamic Speed vs Video Audit)]
```

---

### Component 1: Master Timeline Synchronizer Core
#### [NEW] `scripts/master_timeline_synchronizer.py`
A shared, high-performance Python engine responsible for loading `session_timeline.csv`, resolving clock offsets, identifying the exact video recording window, and matching any sensor event to its nearest neighbor in $O(\log N)$ time.

**Key Responsibilities:**
1. **Load and Sort Timeline:**
   ```python
   class MasterTimelineSynchronizer:
       def __init__(self, session_dir: str):
           self.session_dir = session_dir
           self.timeline_records = []      # List of (mono_ns, sensor, event, seq_id, rel_path, summary)
           self.camera_timeline = []       # [(mono_ns, seq_id), ...]
           self.radar_timeline = []        # [(mono_ns, seq_id), ...]
           self.time_offset_ns = 0         # (startTimeWallMs * 1e6) - startTimeMonotonicNs
           self.video_start_seq_id = None  # Sequence ID where MediaRecorder actually started
           self.video_start_mono_ns = None # Timestamp where MediaRecorder started
           self._ingest_master_timeline()
   ```
2. **Detect Pre-Roll Video Start Boundary:**
   - Scans `camera_timeline` for the temporal reconfiguration gap ($\Delta t \ge 250\text{ ms}$):
     In `session_20261012_164153`:
     - Frame 2936: `2006435795583098`
     - Frame 2937: `2006436295200175` ($\Delta t = 499.6\text{ ms}$)
   - Cross-checks with `session_debug.log` `Started MediaRecorder`.
   - Sets `video_start_seq_id = 2937`, `video_start_mono_ns = 2006436295200175`.
3. **Map MP4 Packets to Shutter Timestamps:**
   - For an MP4 packet with presentation timestamp $\text{pts\_sec}$, computes:
     $$T_{\text{target}} = \text{video\_start\_mono\_ns} + (\text{pts\_sec} \times 10^9)$$
   - Uses `bisect_left` on `camera_timeline` (restricted to frames $\ge \text{video\_start\_seq\_id}$) to find the exact nearest physical shutter exposure event.
   - Guaranteed immunity against dropped frames or pre-roll preview rows.
4. **Nearest Telemetry Matcher:**
   - Provides `find_nearest_telemetry(mono_ns)` and `find_nearest_camera_frame(mono_ns)` returning the exact matching frame number, time delta $\Delta t$, and associated data payload.

---

### Component 2: Foxglove MCAP Multimodal Converter
#### [MODIFY] `tools/convert_session_to_mcap.py`

**Explicit Code Changes:**
1. **Ingest Master Timeline on Startup:**
   ```python
   from scripts.master_timeline_synchronizer import MasterTimelineSynchronizer
   
   sync_master = MasterTimelineSynchronizer(session_dir)
   time_offset_ns = sync_master.time_offset_ns
   ```
2. **Replace Naive `parse_camera_video_frames` Indexing:**
   ```python
   # INSTEAD OF:
   # if frame_idx < len(shutter_timestamps):
   #     mono_ns = shutter_timestamps[frame_idx]
   
   # REPLACED WITH:
   pts_sec = float(packet.pts * stream.time_base) if packet.pts is not None else (frame_idx / 30.0)
   mono_ns = sync_master.get_camera_shutter_mono_ns(pts_sec, frame_idx)
   utc_ns = mono_ns + time_offset_ns
   ```
3. **Harmonize Radar, CAN Telemetry, and GNSS Channels:**
   - Ensure all channel message timestamps (`publish_time` and `log_time`) are computed using:
     $$\text{utc\_ns} = \text{mono\_ns} + \text{time\_offset\_ns}$$
   - Validate that `/vehicle/telemetry` messages (from TLV 5) align with the exact monotonic timestamps logged in `session_timeline.csv`.
4. **Publish Dedicated Synchronized Frame Info Channel (`/sync/frame_info`):**
   - Register a lightweight JSON schema `roadsense.SyncFrameInfo` on topic `/sync/frame_info`.
   - Published with each video frame to allow direct inspection inside Foxglove Studio:
     ```json
     {
       "video_frame_index": 79,
       "camera_hw_frame_number": 3028,
       "radar_frame_id_rel": 79,
       "radar_frame_id_abs": 6575,
       "sync_delta_ms": 17.91,
       "speed_kmph": 0.8
     }
     ```
   - Also inject `radar_frame_id_rel` and `radar_frame_id_abs` directly into `/vehicle/telemetry` and `/radar/diagnostics` JSON payloads.

---

### Component 3: Web Visualizer Pipeline (`frame_mapping.json`)
#### [MODIFY] `tools/sync_and_process_sessions.py`

**Explicit Code Changes:**
1. **Incorporate Pre-Roll Offset in `find_nearest_camera_frame`:**
   - Use `MasterTimelineSynchronizer` to load `camera_frames.csv` excluding pre-recording preview frames (rows 0..12).
   - Ensure `video_frame_index` represents the **exact 0-based index into the MP4 video container**:
     $$\text{v\_frame\_idx} = \text{matched\_frame\_number} - S_{\text{record}}$$
     clamped to $[0, \text{max\_video\_frames} - 1]$.
   - This ensures that when the web visualizer seeks to `video_frame_index`, the displayed frame is physically and mathematically synchronized with the radar bounding box and speed graph.

---

### Component 4: Cross-Sensor Sync Engine & Diagnostic Validation Suite
#### [MODIFY] `scripts/cross_sensor_sync_engine.py`

**Explicit Code Changes:**
1. **Add Subsystem Audit 6: Vehicle Speed & Powertrain Dynamics vs Camera Frame Timeline:**
   - Parses Radar TLV 5 `speed_kmph`, `motor_torque_nm`, `gear`, `brake_status`, and GNSS `speed_kmh`.
   - Identifies the first motion onset ($t_{\text{motion}}$):
     - Brake release
     - Motor torque ramp ($> 10\text{ Nm}$)
     - Wheel speed tick ($> 0.5\text{ km/h}$)
   - Correlates $t_{\text{motion}}$ with the corresponding camera frame shutter timestamp and MP4 frame number.
   - Evaluates synchronization delta:
     $$\Delta t_{\text{sync}} = |t_{\text{wheel\_speed}} - t_{\text{camera\_shutter}}|$$
   - Emits a PASS/FAIL scorecard entry:
     - **PASS:** $|\Delta t_{\text{sync}}| \le 33.3\text{ ms}$ (within 1 camera frame)
     - **WARN:** $33.3\text{ ms} < |\Delta t_{\text{sync}}| \le 100\text{ ms}$
     - **FAIL:** $|\Delta t_{\text{sync}}| > 100\text{ ms}$
2. **Embed Speed Profile & Motion Correlation in `sync_audit_report.html`:**
   - Embeds a synchronized speed curve in the HTML report allowing interactive inspection of vehicle speed vs camera shutter timestamps.

---

## Verification Plan

### Automated Tests
1. **Master Timeline Synchronizer Unit & Integration Test:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" -m unittest scripts/tests/test_master_timeline_synchronizer.py
   ```
   Validates pre-roll gap detection, PTS-to-shutter mapping, and nearest telemetry retrieval.

2. **Run Cross-Sensor Sync Engine on Target Session:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" scripts/cross_sensor_sync_engine.py --session "logs/session_20261012_164153"
   ```
   Verifies that Subsystem Audit 6 reports `PASS` for Vehicle Speed vs Camera Timeline.

3. **Re-generate MCAP and Validate Channel Alignment:**
   ```powershell
   & "C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe" tools/convert_session_to_mcap.py --session "logs/session_20261012_164153" --overwrite
   ```
   Verify with an automated check script that at $t = 3.96\text{s}$ (speed rise), the video message timestamp matches the physical shutter exposure of the recording start.

4. **Batch Health Suite Regression Check:**
   ```powershell
   python scripts/batch_audit_and_health_check.py --latest
   ```

5. **JVM Unit Tests:**
   ```powershell
   ./gradlew testDebugUnitTest
   ```

### Manual Verification
1. **Open the re-converted `session_20261012_164153.mcap` in Foxglove Studio.**
2. **Verify Vehicle Speed vs Motion at $t = 3.96\text{s}$:**
   - Scrub to $t \approx 3.96\text{s}$.
   - Verify that when `/vehicle/telemetry.speed_kmph` starts rising, the camera panel displays the vehicle at the exact instant wheel torque and speed engage.
   - Verify zero perceived desync between the speed plot curve and video playback.
3. **Foxglove Live Frame ID Verification against `frame_mapping.json`:**
   - In Foxglove Studio, add a **Raw Messages Panel** (or **Indicator Panel**).
   - In the panel configuration, select topic `/sync/frame_info` (or `/vehicle/telemetry`).
   - Pause playback at any point and use Foxglove frame stepping (`.` for step forward, `,` for step backward).
   - Read the values displayed in the panel:
     - `video_frame_index`
     - `camera_hw_frame_number`
     - `radar_frame_id_rel`
     - `radar_frame_id_abs`
   - Open `frame_mapping.json` in VS Code / editor, navigate to row `video_frame_index`, and confirm that:
     $$\text{MCAP } \texttt{video\_frame\_index} == \texttt{frame\_mapping.json} \rightarrow \texttt{video\_frame\_index}$$
     $$\text{MCAP } \texttt{radar\_frame\_id\_abs} == \texttt{frame\_mapping.json} \rightarrow \texttt{radar\_frame\_id\_abs}$$
   - This proves 100% deterministic, frame-for-frame synchronization between Foxglove MCAP playback and the RoadSense ground truth mapping.
4. **HTML Report Verification:**
   - Open `sync_audit_report.html` for `session_20261012_164153` in browser and confirm that the vehicle dynamics panel shows clean, zero-lag crosshair correlation.
