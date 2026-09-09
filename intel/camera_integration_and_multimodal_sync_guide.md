# RoadSense — Camera Integration & Multimodal Synchronization Guide

## 1. Overview & Architecture

The RoadSense Camera subsystem captures continuous, standard H.264 (MPEG-4 AVC) video footage synchronized with millimetric radar point clouds (TI AWR1843BOOST) and GNSS/GPS satellite fixes onto a common monotonic nanosecond timebase (`SystemClock.elapsedRealtimeNanos()`).

```
                              ┌───────────────────────────────────┐
                              │           Camera2 API             │
                              │     (BACK SENSOR / Camera "0")    │
                              └─────────────────┬─────────────────┘
                                                │
                          ┌─────────────────────┴─────────────────────┐
                          │                                           │
                          ▼                                           ▼
             ┌─────────────────────────┐                 ┌─────────────────────────┐
             │ Surface 1: MediaRecorder│                 │ Surface 2: TextureView  │
             │ H.264 Hardware Encoder  │                 │ UI Preview Viewfinder   │
             │ (Video-only, no audio)  │                 │ (Detachable on tab swipe)
             └────────────┬────────────┘                 └─────────────────────────┘
                          │
                          │ CameraCaptureSession.CaptureCallback
                          │ .onCaptureStarted(session, request, timestamp, frameNumber)
                          │ (Nanosecond-accurate exposure start)
                          ▼
             ┌────────────────────────────────────────────────────────┐
             │                 CameraSessionRecorder                  │
             │ Writes:                                                │
             │ 1. camera/camera_video.mp4 (H.264 stream)             │
             │ 2. camera/camera_frames.csv (shutter metadata)        │
             │ 3. session_timeline.csv (master multimodal timeline)   │
             └────────────────────────────────────────────────────────┘
```

---

## 2. Key Components & Implementation

| File | Role | Key Features |
|---|---|---|
| `CameraConfig.kt` | Resolution & FPS configuration | Predefined profiles: 480p (SD), 720p (HD, default), 1080p (FHD); target FPS: 15, 30, 60. Dynamic bitrate calculation. |
| `CameraFrameMetadata.kt` | Per-frame telemetry record | `frameNumber`, `shutterMonotonicNs`, `exposureTimeNs`, `iso`, `utcTimestampMs`. |
| `CameraEngine.kt` | Low-level Camera2 lifecycle manager | Manages `CameraDevice`, `MediaRecorder`, dynamic session surface reconfiguration, background `HandlerThread`, and shutter callback extraction. |
| `CameraSessionRecorder.kt` | Disk persistence & CSV serialization | Streams shutter timing to `camera_frames.csv` and dispatches to `SessionTimelineWriter`. |
| `CameraDashboardCard.kt` | Jetpack Compose UI card | Live `TextureView` viewfinder, resolution & FPS selector chips, "Mute Preview" toggle, and auto-detachment when swiped away. |

---

## 3. Shutter Timestamping & Cross-Sensor Synchronization

### The Synchronization Problem
Standard video containers (such as MP4) only store nominal presentation timestamps (PTS) or relative frame indices. In automotive and multimodal robotics applications, video frames must be aligned with high-frequency radar chirps (~10–20 Hz) and GNSS locations (~1–5 Hz) down to the millisecond/microsecond level.

### The Solution
RoadSense utilizes `CameraCaptureSession.CaptureCallback.onCaptureStarted`:
```kotlin
override fun onCaptureStarted(
    session: CameraCaptureSession,
    request: CaptureRequest,
    timestamp: Long,
    frameNumber: Long
) {
    val utcTimeMs = System.currentTimeMillis()
    frameListener?.onFrameCaptured(
        CameraFrameMetadata(
            frameNumber = frameNumber,
            shutterMonotonicNs = timestamp, // Exact exposure start in nanoseconds!
            exposureTimeNs = 0L,
            iso = 0,
            utcTimestampMs = utcTimeMs
        )
    )
}
```

In Android's Camera2 API:
- `timestamp`: Represents the **start of sensor exposure** in **nanoseconds**.
- The timebase is monotonic (`CLOCK_BOOTTIME` / `SystemClock.elapsedRealtimeNanos()`).
- Because RoadSense radar packets (`RadarPacket.elapsedRealtimeNanos`) and GNSS locations (`GnssFix.elapsedRealtimeNanos`) use this exact same monotonic timebase, all three sensor modalities can be matched without clock drift or NTP leap second discrepancies.

---

## 4. Hardware Optimization & Power Management

1. **Audio Disabled by Default**:
   Audio track capture is omitted to eliminate microphone permission dialogs, prevent ALSA/AudioRecord buffer under-runs, and reduce container overhead.
2. **Viewfinder Surface Auto-Detachment**:
   When the driver/operator swipes to the Radar or GNSS tabs, the Compose `DisposableEffect` detaches the UI `Surface` from `CameraEngine`. 
   - While recording: Video recording continues uninterrupted onto the `MediaRecorder` surface, but the GPU/display rendering pipeline stops, reducing thermal throttling and battery draw.
   - While idle: The camera device closes until the user navigates back to the Camera tab.
3. **Hardware Codec Surface Input**:
   `MediaRecorder.VideoSource.SURFACE` connects directly to the Camera2 HAL, avoiding costly CPU buffer copies.

---

## 5. Output Session Directory Layout

Each session recorded by RoadSense contains the following structure:

```text
session_YYYYMMDD_HHMMSS/
├── session_metadata.json          # Overall session info, devices, active streams, duration
├── session_timeline.csv           # Master interleaved cross-sensor chronological event stream
├── camera/
│   ├── camera_video.mp4           # Playable standard H.264 MP4 video
│   └── camera_frames.csv          # Shutter exposure start timestamps per frame
├── gnss/
│   └── gnss_fixes.csv             # Lat, Lon, Alt, Speed, Bearing, Accuracy, Monotonic ns
└── radar/
    ├── radar_frames.bin           # Framed binary packets with 16-byte magic headers
    └── radar_raw_stream.bin       # Exact raw byte-for-byte UART stream
```

### Format of `camera_frames.csv`:
```csv
frame_number,shutter_monotonic_ns,exposure_time_ns,iso,utc_time_ms,utc_iso
1068,99382786159106,0,0,1788945564115,2026-09-09T14:49:24.115+05:30
1069,99382819448452,0,0,1788945564146,2026-09-09T14:49:24.146+05:30
1070,99382852748952,0,0,1788945564181,2026-09-09T14:49:24.181+05:30
```
*(Notice the exact ~33.29 ms intervals between frames, corresponding to rock-solid 30.0 FPS).*

### Format of `session_timeline.csv`:
```csv
elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary
99382772623798,SESSION,START,0,session_metadata.json,Session initialized: session_20260909_144924
99382786159106,CAMERA,FRAME,1068,camera/camera_video.mp4,res=720p (HD);exp=0ns;iso=0
99382819448452,CAMERA,FRAME,1069,camera/camera_video.mp4,res=720p (HD);exp=0ns;iso=0
...
```

---

## 6. Verification & Analysis Tools

Run `tools/inspect_session.py` to inspect any recorded session:
```bash
python tools/inspect_session.py logs/session_YYYYMMDD_HHMMSS
```

Output highlights:
- Validates the MP4 container structure (`ftyp mp42` box).
- Verifies camera frame counts and calculated average FPS.
- Checks monotonic timeline coverage and cross-sensor event counts.
