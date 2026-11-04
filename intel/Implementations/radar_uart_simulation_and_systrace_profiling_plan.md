# Radar In-App Replay & Perfetto Systrace Profiling Implementation Plan

## Goal Description
Isolate and prove with 100% certainty why mmWave radar frames $> 120$ points suffer dropouts and visualizer freezes on Android. Specifically, test the user's critical question:
> **"Is it because our app is unable to draw so many 3D RViz-inspired lollipops and gets stuck processing that information (affecting our background logging worker), or is it purely an upstream hardware UART/FIFO data loss issue?"**

Because no external USB-UART hardware (CP2102/FTDI) or second OTG adapter is available on the desk, we will use the **single standard USB cable currently connected for ADB** to run:
1. **In-App Radar Replay Engine:** Replays recorded raw sessions (`radar_raw_stream.bin` from `session_20261103_162349` and `session_20261102_165601`) directly inside RoadSense at realistic 20 Hz pacing.
2. **PC-to-Phone ADB Virtual Streamer:** Allows streaming test sessions from PC over `adb forward tcp:9090` directly into the RoadSense acquisition pipeline.
3. **Automated Perfetto / Systrace Profiling:** Captures microsecond-level CPU scheduling, ART Garbage Collection (GC) pauses, Compose UI measure/draw times, and worker latency over ADB while dense frames (140+ lollipops) are rendering.

---

## User Review Required

> [!NOTE]
> **Zero Hardware Changes Needed:**
> Everything runs on your existing setup:
> - **Phone:** Samsung Galaxy M30 (SM-M305F, Android 10).
> - **Connection:** The single USB cable currently plugged into your PC running ADB.
> - **Data Source:** Existing drive logs stored on your device/PC (`session_20261103_162349` and `session_20261102_165601`).

---

## Isolation Strategy: Pinpointing the Freeze Cause

```
                     ┌─────────────────────────────────────────────────────────┐
                     │               EXPERIMENTAL TEST MATRIX                  │
                     └─────────────────────────────────────────────────────────┘

  Test Scenario               Radar Input             3D Lollipops    What It Isolates
 ─────────────────────────────────────────────────────────────────────────────────────────────────
  Scenario 1: Baseline        Replay session_162349   ENABLED         Tests if full pipeline (140+
  (Full Load)                 raw stream @ 20 Hz      (All 3D Arcs    lollipops + Camera + Session
                                                      + Footprints)   Writer) causes UI/Worker freeze.

  Scenario 2: UI A/B          Replay session_162349   DISABLED        If Scenario 1 freezes but
  (UI Overhead Isolation)     raw stream @ 20 Hz      (Overlay OFF)   Scenario 2 runs at 60 FPS,
                                                                      the bottleneck is in Canvas draw!

  Scenario 3: Synthetic       Inject intact 2,500 B   ENABLED         Proves whether 180 intact
  Dense Frame Injection       frames (180 points      (Stress Test)   points can be decoded and drawn
                              + 10 tracks) @ 20 Hz                    without buffer corruption.
```

---

## Proposed Changes

### Component 1: In-App Radar Replay Engine (`RadarReplayManager.kt`)
[NEW] `app/src/main/java/com/bajajauto/roadsense/acquisition/RadarReplayManager.kt`
- Implements `RadarByteSource` interface so it can feed bytes into `RadarViewModel` identically to `RadarConnectionManager`.
- Reads `radar_raw_stream.bin` from local session storage (`/sdcard/Android/data/com.bajajauto.roadsense/files/sessions/`).
- Dispatches byte chunks at authentic 20 Hz intervals (50 ms pacing) with configurable burst chunk sizes.
- Exposes telemetry: `currentReplayFrame`, `totalReplayFrames`, `replayFps`, `isReplaying`.

### Component 2: ADB Virtual Serial Streamer (`tools/stream_radar_over_adb.py` & `AdbStreamReceiver.kt`)
[NEW] `app/src/main/java/com/bajajauto/roadsense/acquisition/AdbStreamReceiver.kt`
- A lightweight TCP socket server listening on `127.0.0.1:9090`.
- Connects directly to `RadarViewModel.onRawData()`.
- Whenever PC runs `adb forward tcp:9090 tcp:9090` and sends binary radar frames, RoadSense consumes them as if from a physical USB UART port.

[NEW] `tools/stream_radar_over_adb.py`
- Python PC script that reads any `.bin` session and streams it over TCP port 9090.
- Features interactive controls:
  - `--fps 20` (default) or `--step` (advance frame-by-frame on Enter key).
  - `--loop` (continually loops dense frames to test thermal/sustained UI stability).
  - `--inject-synthetic-dense` (generates intact 2,500-byte frames with 180 points and 10 tracks).

### Component 3: Microsecond Perfetto Trace Instrumentation
[MODIFY] `RadarViewModel.kt`, `ViewfinderRadarOverlay.kt`, `SpatialProjectionEngine.kt`
- Add `android.os.Trace.beginSection()` and `Trace.endSection()`:
  - `Trace.beginSection("RS_Lollipop_Projection")` (measures 3D matrix math)
  - `Trace.beginSection("RS_Lollipop_DrawCanvas")` (measures Compose Canvas drawing)
  - `Trace.beginSection("RS_RadarWorker_Process")` (measures assembler + decoder)
  - `Trace.beginSection("RS_Session_WriteFrame")` (measures binary disk write)

### Component 4: Perfetto Automated Runner & Analyzer (`scripts/profile_radar_scheduling.py`)
[NEW] `scripts/profile_radar_scheduling.py`
- PC Python script executing:
  1. `adb shell perfetto` with `sched`, `freq`, `art`, `am`, `app`, `camera`, `gfx`, `view` for 15 seconds.
  2. Runs replay of the dense frame segment (Frames #400–#600 of `session_162349`).
  3. Pulls `profile_run.pftrace` to PC.
  4. Parses the trace and reports:
     - Peak Compose UI frame time during 140+ lollipops (did it exceed 16.6 ms?).
     - ART Garbage Collection pause count and total freeze duration.
     - `RadarProcessingWorker` scheduling latency (did it get preempted by Camera or UI?).

---

## Verification Plan

### Automated Tests
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
```

### Manual Verification Workflow
1. **Replay Validation:**
   - Launch RoadSense on Samsung Galaxy M30.
   - Tap "Debug Replay" $\to$ select `session_20261103_162349`.
   - Observe live viewfinder: 3D lollipops appear, tracks animate, telemetry updates at 20 Hz.
2. **UI Load Test:**
   - Toggle Overlay ON and OFF during dense frames (Frames #400–#600).
   - Check if the worker sequence counter stays monotonically continuous.
3. **Perfetto Trace Analysis:**
   - Run `python scripts/profile_radar_scheduling.py`.
   - Open trace in `https://ui.perfetto.dev` to verify whether GC or RenderThread caused any worker preemption.
