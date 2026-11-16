# Implementation Plan: Harmonizing Cross-Sensor Synchronization Audit Output Formatting

## Goal Description
Enhance the visual clarity, human readability, and data presentation of [`scripts/audit_cross_sensor_sync.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py) so that its output is structured, elegant, and matches the proven design aesthetic of [`scripts/session_health_check.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/session_health_check.py).

Currently, `session_health_check.py` is praised for its clean 2-column key-value alignment, bracketed section numbers (`[X/N]`), standardized `Status:` lines, and closing executive summary scorecard. In contrast, `audit_cross_sensor_sync.py` suffers from:
1. Irregular section markers (`1.`, `2.`, `3.`) without total section indexing.
2. Inconsistent indentation and erratic bullet dashes (`- `) mixed with bracketed subsection headers.
3. Jagged label widths causing column misalignments.
4. Calculation artifacts (e.g., displaying `1000.0 Hz` for single-shot session lifecycle events like `IMU:START`).
5. Absence of a closing **Executive Cross-Sensor Synchronization Summary Scorecard** and overall verdict banner.

---

## User Review Required
> [!NOTE]
> All underlying timing math, bisect latency calculations, flight recorder UI event parsing, and Matplotlib multi-panel plot generation logic will remain **100% intact**. Only the terminal text formatting, alignment tables, section headings, and status badge presentations are being redesigned.

---

## Proposed Output Redesign Comparison

### Before (Current Cluttered Output)
```text
================================================================================
CROSS-SENSOR MONOTONIC SYNCHRONIZATION AUDIT
Session:  session_20261006_144848
Timeline: C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\logs\session_20261006_144848\session_timeline.csv
================================================================================

1. TIMELINE OVERVIEW:
  Total Synchronized Events: 1,045
  Total Timeline Duration:   35.39 seconds (0.59 minutes)
  Concurrent Thread Interleaving: 6 events (Average queue jitter: 15.29 ms, Max: 30.63 ms)
  Monotonic Integrity:       PASS (Multithreaded queue within normal buffer limits)

2. STREAM EVENT DISTRIBUTION & SENSOR HEALTH:
  CAMERA:FRAME            :  1036 events across  34.96s ( 29.6 Hz)
  IMU:START               :     1 events across   0.00s (1000.0 Hz)
  IMU:STOP                :     1 events across   0.00s (1000.0 Hz)
  IMU:SYNC_ANCHOR         :     6 events across  25.34s (~4.22s period / 500 frames)
  SESSION:START           :     1 events across   0.00s (1000.0 Hz)

  [IMU High-Rate Data Stream in imu/imu_frames.csv]:
    - Total Consolidated Samples: 3,402 frames
    - Effective Sampling Rate:    98.72 Hz (Nominal 100 Hz across 34.46s)
    - Mean Inter-Sample Interval: 10.13 ms
    - Sampling Jitter Gaps (>20ms): 0 (0.000%)
    - Timeline Representation:    6 periodic anchors (1 every 5s / 500 frames)
    - IMU Logging Status:         PASS (Healthy 100 Hz continuous stream; downsampled 5s sync anchors in timeline to prevent file bloat)

3. CROSS-SENSOR LATENCY ALIGNMENT:
  Insufficient overlapping Radar & Camera events in timeline.
  IMU Sync Anchors -> Nearest Camera Frame:
    - Average Offset:         7.34 ms
    - Maximum Offset:         16.22 ms

4. DETECTED DESYNC & TIMING ANOMALIES BREAKDOWN:
  WARN: Camera Video Frame Drops (>50ms): 1 occurrences
    - Drop at t=  1.03s: dt =  538.0 ms
  PASS: 100% of radar frames synchronized within 1-frame horizon (<33.3ms).

  [UI & User Screen Interaction Correlation from Flight Recorder Log]:
    - Total UI Interactions Logged: 11 events during active recording
    - Camera Drops Triggered by UI: 0 / 1 (0.0%)
    - Root Cause Analysis:          IDENTIFIED
      Android Camera2 reconfigures preview surface when toggling fullscreen or swiping tabs,
      causing ~500ms temporary frame pauses and brief cross-sensor latency spikes.

================================================================================
TIMELINE AUDIT COMPLETE
================================================================================
```

---

### After (Proposed Clean & Readable Design)
```text
================================================================================
ROADSENSE CROSS-SENSOR SYNCHRONIZATION AUDIT: session_20261006_144848
Timeline: C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\logs\session_20261006_144848\session_timeline.csv
================================================================================

[1/5] TIMELINE OVERVIEW & MONOTONIC INTEGRITY:
  Total Events:         1,045 synchronized timeline events
  Timeline Duration:    35.39 seconds (0.59 minutes)
  Write Queue Jitter:   6 interleavings (mean: 15.29 ms, max: 30.63 ms)
  Monotonic Ordering:   Strictly monotonic across multithreaded workers
  Status:               PASS (Intact & Monotonic)

[2/5] STREAM EVENT DISTRIBUTION & SENSOR RATES:
  CAMERA:FRAME          1,036 events   34.96s   29.63 FPS (Nominal 30 FPS)
  IMU:SYNC_ANCHOR           6 events   25.34s    0.24 Hz  (1 anchor / 5.0s)
  IMU:START                 1 events     --       --      (Lifecycle marker)
  IMU:STOP                  1 events     --       --      (Lifecycle marker)
  SESSION:START             1 events     --       --      (Lifecycle marker)
  Status:               PASS (5 Stream Types Indexed)

  IMU High-Rate File Stream (imu/imu_frames.csv):
  Consolidated Frames:  3,402 frames logged
  Effective Rate:       98.72 Hz (Nominal 100 Hz across 34.46s)
  Inter-Sample Period:  mean=10.13 ms, jitter >20ms: 0 (0.00%)
  Timeline Anchors:     6 downsampled sync anchors (every 500 frames)
  Status:               PASS (Healthy 100 Hz Continuous Stream)

[3/5] CROSS-SENSOR LATENCY & TIME ALIGNMENT:
  Radar -> Camera Alignment:
  Overlapping Frames:   0 radar frames present in session
  Latency Delta:        N/A (Radar stream inactive)
  Status:               SKIP (Radar stream absent)

  IMU Anchors -> Camera Alignment:
  Evaluated Anchors:    6 sync anchor frames
  Mean Latency Offset:  7.34 ms
  Peak Latency Offset:  16.22 ms (Nyquist limit: <16.67 ms @ 30 FPS)
  Status:               PASS (Synchronized within half-frame)

  GNSS -> Camera Alignment:
  Evaluated Fixes:      0 GNSS points
  Latency Delta:        N/A (GNSS fixes absent)
  Status:               SKIP (GNSS stream absent)

[4/5] TIMING ANOMALIES & UI INTERACTION CORRELATION:
  Camera Frame Drops:   1 occurrence (>50 ms)
    ! Drop @ t= 1.03s:  dt = 538.0 ms (Camera2 preview initialization)
  Radar Latency Spikes: 0 occurrences (>33.3 ms / 1-frame horizon)
  UI Interactions:      11 events logged in flight recorder
  UI-Triggered Drops:   0 / 1 (0.0% correlated with UI touch / gestures)
  Root Cause Analysis:  IDENTIFIED (Camera2 preview surface startup)
  Status:               WARN (1 startup frame drop; steady state intact)

[5/5] MULTI-PANEL SYNCHRONIZATION DIAGNOSTIC PLOT:
  Diagnostic Chart:     sync_audit_report.png
  Time-Series Panels:   5 synchronized subplots (offsets, drops, UI, IMU, jitter)
  Report Resolution:    120 DPI (428,102 bytes)
  Status:               PASS (Plot Generated)

================================================================================
CROSS-SENSOR SYNCHRONIZATION SUMMARY SCORECARD:
================================================================================
  Timeline Monotonic Ordering : PASS (Intact & Monotonic)
  High-Rate IMU (100 Hz)      : PASS (Healthy 100 Hz Continuous Stream)
  Radar-Camera Alignment      : SKIP (Radar stream absent)
  IMU-Camera Synchronization  : PASS (Synchronized within half-frame)
  Camera Frame Stability      : WARN (1 startup frame drop; steady state intact)
  UI Desync Correlation       : PASS (0 UI-Induced Drops)
  Diagnostic Plot Generation  : PASS (Plot Generated)
--------------------------------------------------------------------------------
OVERALL AUDIT VERDICT: WARN (Minor startup drop; synchronization steady)
================================================================================
```

---

## Detailed Formatting Principles Applied

1. **Section Brackets `[X/N]`**:
   Directly mirror `session_health_check.py` with `[1/5]`, `[2/5]`, `[3/5]`, `[4/5]`, and `[5/5]`.
2. **Standard 2-Column Key-Value Alignment**:
   Labels will be consistently left-aligned with a fixed padding of 22–24 characters:
   `print(f"  {label:22s}: {value}")`
   This eliminates jagged spacing and ensures all colons and values form a single vertical margin.
3. **Consistent Subsystem Status Line**:
   Every section and sensor pairing concludes with:
   `print(f"  Status:               {colorize_status(status_str)}")`
   utilizing the standard ANSI colors:
   - Green for `PASS` / `HEALTHY` / `INTACT` / `EXCELLENT`
   - Yellow for `WARN` / `SKIP`
   - Red for `FAIL` / `ERROR`
4. **Clean Event Distribution Table**:
   Replace the confusing `(1000.0 Hz)` calculated on zero-duration single-instance lifecycle events (`IMU:START`, `SESSION:START`) with clean categorization:
   - For streaming events (`CAMERA:FRAME`, `RADAR:FRAME`): show count, duration, and formatted FPS/Hz.
   - For periodic downsampled events (`IMU:SYNC_ANCHOR`): show count, duration, effective anchor frequency, and period.
   - For lifecycle events (`SESSION:START`, `IMU:START`, `IMU:STOP`): show `(Lifecycle marker)`.
5. **Cleaned Anomaly Breakdown**:
   Format drops and spikes cleanly without random dashes or unbounded line wraps.
6. **Executive Summary Scorecard**:
   Introduce an end-of-audit scorecard table with `28s` label padding followed by colorized badges, concluded by an `OVERALL AUDIT VERDICT:` banner.

---

## Proposed Changes

### PC Diagnostic Scripts
#### [MODIFY] [`scripts/audit_cross_sensor_sync.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py)
- Refactor `audit_timeline()`:
  - Update main banner header to match `session_health_check.py` style (`ROADSENSE CROSS-SENSOR SYNCHRONIZATION AUDIT: {sess_name}`).
  - Maintain a `scorecard = {}` dictionary to track section verdicts.
  - Implement Section `[1/5] TIMELINE OVERVIEW & MONOTONIC INTEGRITY:`.
  - Implement Section `[2/5] STREAM EVENT DISTRIBUTION & SENSOR RATES:` with columnar event breakdown and high-rate IMU sub-card.
  - Implement Section `[3/5] CROSS-SENSOR LATENCY & TIME ALIGNMENT:` with cards for Radar->Camera, IMU->Camera, GNSS->Camera.
  - Implement Section `[4/5] TIMING ANOMALIES & UI INTERACTION CORRELATION:`.
  - Implement Section `[5/5] MULTI-PANEL SYNCHRONIZATION DIAGNOSTIC PLOT:`.
  - Implement `CROSS-SENSOR SYNCHRONIZATION SUMMARY SCORECARD:` and `OVERALL AUDIT VERDICT:`.
- Add `'SKIP'` to `colorize_status()` as cyan/yellow neutral so skipped streams (e.g. radar absent) are visually distinct from failures.

---

## Verification Plan

### Automated Tests
1. **Console Run on Latest Session**:
   ```powershell
   python scripts/audit_cross_sensor_sync.py --latest --no-plot
   ```
   *Expected Output*: Formatted output matches the proposed redesign with 100% column alignment, zero calculation errors, and the complete summary scorecard.

2. **Full Run with Plot Generation**:
   ```powershell
   python scripts/audit_cross_sensor_sync.py --latest
   ```
   *Expected Output*: Verified that `sync_audit_report.png` is generated and Section 5 reports the generated file details cleanly.

3. **Comparison Test with `session_health_check.py`**:
   ```powershell
   python scripts/session_health_check.py --latest
   ```
   *Expected Output*: Both scripts share the exact same visual language, padding, and scorecard presentation.

### Manual Verification
- Open the web dashboard at `http://127.0.0.1:8088`.
- In the **Diagnostics** tab, select **Cross-Sensor Monotonic Sync Audit (`audit_cross_sensor_sync.py`)** on the latest local session and click **Run Diagnostic**.
- Verify in the browser terminal output window that colors render brightly and the text formatting is clean, aligned, and uncluttered.
