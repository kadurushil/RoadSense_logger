# Plan: MCAP Temporal Alignment & Non-Destructive Stream Enrichment

## Goal Description
Resolve the timeline desynchronization and elapsed time distortion bug observed in Foxglove Studio by aligning vision track timestamps with master UTC Wall Clock nanoseconds (`utc_ns`), and implement a 100% non-destructive, least-invasive MCAP injection engine that copies the original recording to `output/mcap/`, preserves all 12 original schemas, 12 channels, and 4 attachments, and adds `/vision/geo_tracks` in strict chronological order.

---

## User Review Required

> [!IMPORTANT]
> **Root Cause of the Timeline Distortion Bug:**
> In `session_20261006_095609.mcap`, all messages (`/camera/video`, `/radar/tracks`, `/imu/data`) use **UTC Wall Clock nanoseconds**:
> $$\text{start\_time} = 1,791,260,769,296,000,000\text{ ns (October 6, 2026)}$$
> However, `session_timeline.csv` records raw Android monotonic clock timestamps:
> $$\text{elapsed\_realtime\_ns} = 1,463,695,749,944,206\text{ ns (16.9 days since phone boot)}$$
> Because raw monotonic nanoseconds were written into the vision tracks without adding `time_offset_ns`, Foxglove Studio saw a timeline stretching from **January 1970 to October 2026 (56.7 years)**, causing the timeline scrubber and elapsed time to display an enormous number.

> [!TIP]
> **The Exact Mathematical Solution:**
> In `session_metadata.json`:
> $$\text{time\_offset\_ns} = (\text{startTimeWallMs} \times 1,000,000) - \text{startTimeMonotonicNs} = 1,789,797,073,560,504,756\text{ ns}$$
> Adding `time_offset_ns` to the first video frame's monotonic timestamp:
> $$1,463,695,749,944,206 + 1,789,797,073,560,504,756 = 1,791,260,769,310,448,962\text{ ns}$$
> This **identically matches** the exact timestamp of `/camera/video` in the original MCAP to the nanosecond.

---

## Non-Invasive MCAP Structure Analysis

Inspection of `session_20261006_095609.mcap` reveals the exact original structure:
1. **12 Original Schemas:**
   * 4 JSON schemas: `roadsense.VehicleTelemetry`, `roadsense.RadarDiagnostics`, `roadsense.RadarAdas`, `roadsense.Imu`.
   * 8 Protobuf schemas: `foxglove.FrameTransforms`, `foxglove.CameraCalibration`, `foxglove.PointCloud`, `foxglove.SceneUpdate` (Schema ID 8), `foxglove.LocationFix`, `foxglove.PoseInFrame`, `foxglove.CompressedVideo`, `foxglove.Log`.
2. **12 Original Channels:**
   * `/vehicle/telemetry`, `/radar/diagnostics`, `/radar/adas`, `/imu/data`, `/tf`, `/camera/calib`, `/radar/points`, `/radar/tracks` (Channel ID 8), `/gnss/fix`, `/imu/pose`, `/camera/video`, `/diagnostics/logs`.
3. **4 Original Attachments:**
   * `session_metadata.json`, `radar_camera_calib.json`, `RoadSense_Cockpit_Layout.json`, `foxglove.layout`.

### Least-Invasive Injection Strategy:
* Re-use **Schema ID 8 (`foxglove.SceneUpdate`)** directly from the source MCAP instead of creating redundant schema definitions.
* Register a single new channel: `Channel 13: topic=/vision/geo_tracks`, `schema_id=8`, `message_encoding=protobuf`.
* Stream-copy all original message payloads byte-for-byte without decompressing or modifying video packets, radar point clouds, or telemetry.
* Copy all 4 attachments byte-for-byte so Foxglove automatically loads the complete Cockpit Layout.

---

## Proposed Changes

### Component 1: Timeline Loader & Perception Runner
#### [MODIFY] [`tools/vision/pipeline/timeline_loader.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/timeline_loader.py)
* Load `startTimeWallMs` and `startTimeMonotonicNs` from `session_metadata.json` (or `session_metadata.json` attachment).
* Compute `time_offset_ns`.
* Return both UTC Wall Clock nanoseconds (`utc_ns`) and monotonic nanoseconds (`mono_ns`) so all timestamps align with the original MCAP.

#### [MODIFY] [`tools/vision/pipeline/perception_runner.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/pipeline/perception_runner.py)
* Store both `"timestamp_utc_ns"` and `"timestamp_mono_ns"` in each frame of `vision_tracks.json`.

---

### Component 2: Non-Destructive MCAP Enrichment Engine
#### [MODIFY] [`tools/vision/mcap/inject_vision_tracks.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/mcap/inject_vision_tracks.py)
* **Zero Modification Guarantee:** The original file in `logs/session_20261006_095609/` is never touched.
* **Timestamp Normalizer:** If `vision_tracks.json` contains monotonic timestamps (`< 1e16`), automatically calculate `time_offset_ns` from `session_metadata.json` to guarantee all `/vision/geo_tracks` messages are in UTC nanoseconds.
* **Schema Re-use:** Map `/vision/geo_tracks` directly to the existing `foxglove.SceneUpdate` schema ID from the source MCAP.
* **Attachment Preservation:** Copy all attachments (`foxglove.layout`, metadata) into the output file.
* **Chronological Interleaving:** Two-pointer linear streaming merge maintaining strict monotonic ordering by `log_time`.
* **Output Path:** `tools/vision/output/mcap/session_20261006_095609_with_vision.mcap`.

---

## Verification Plan

### Automated Tests
1. **Timestamp Alignment Test:** Compare the first `/vision/geo_tracks` message timestamp with the first `/camera/video` message timestamp in the enriched MCAP. Expected delta: $|\Delta t| \le 33\text{ ms}$.
2. **Timeline Duration Test:** Check `message_start_time` and `message_end_time` in the enriched MCAP using `make_reader(f).get_summary().statistics`. Verify that total duration equals exactly $1919.7\text{ seconds}$ (~32 minutes) and NOT 56 years!
3. **Channel & Attachment Integrity:** Verify that all 12 original topics plus `/vision/geo_tracks` exist, message counts for original topics match the original MCAP, and all 4 attachments are present.

### Manual Verification
1. **Foxglove Studio Timeline:** Open `session_20261006_095609_with_vision.mcap` in Foxglove Studio. Verify that:
   * The timeline starts at 00:00:00 and ends at ~00:32:00 (no multi-year elapsed time).
   * Scrubbing along the timeline smoothly moves the video, radar point cloud, radar tracks (Cyan), and vision tracks (Electric Magenta) synchronously.
   * Clicking a GPS map point jumps to that exact time with both sensor tracks rendered in the 3D panel.
