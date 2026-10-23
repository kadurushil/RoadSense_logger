# Walkthrough: Real-Time Dual Progress Bar for Visualizer JSON Serialization

## Overview
During multi-sensor session processing, generating large visualizer datasets (`track_history.json` and `frame_mapping.json`) previously froze the terminal and web dashboard for 15–30+ seconds with a static message. In `session_20261023_111120`, `track_history.json` exceeds **305 MB** (23,340 frames, 4,598 tracks).

We replaced the monolithic, blocking `json.dump(final_payload, tf, indent=2)` with a **high-performance chunked streaming JSON writer** that emits live dual-progress metrics (percentage, MB/s throughput, frame counts, and ETA countdown).

---

## Changes Implemented

### 1. `tools/sync_and_process_sessions.py`
- **`stream_write_frame_mapping()`**:
  - Streams `mapping_records` to disk line by line.
  - Updates progress every 1,000 records.
  - Computes transfer throughput and ETA.
  - Appends the visualizer metadata summary record.
- **`stream_write_track_history()`**:
  - Implements a streaming chunked JSON writer with standard 2-space indentation.
  - Iterates through `viz_radar_frames` and `tracks`, calculating disk write offsets using `tf.tell()`.
  - Emits real-time `[PROGRESS]` events containing:
    - Overall session progress (`overall_pct`, `overall_current`, `overall_total`)
    - Sub-progress (`file_name`, `file_pct`, `current_mb`, `total_mb`, `speed_mb_s`, `eta_s`)
  - Displays live `\r` carriage returns in the terminal when running interactively (`sys.stdout.isatty()`).
  - Completes writing a 305 MB dataset in **~8 seconds** at **~35 MB/s**, yielding a **~3x speedup** over monolithic serialization.
- **`process_session()`**:
  - Integrated both streaming writers with proper progress scaling whether `--mcap` is active (70% -> 75% -> MCAP) or absent (70% -> 99% -> 100% `done`).

### 2. Comprehensive Test Suite
- **`scripts/tests/test_stream_json_writer.py`**:
  - `test_track_history_exact_equivalence`: Validates that streamed output is **byte-for-byte and object-for-object identical** to standard `json.dump(..., indent=2)`.
  - `test_track_history_edge_cases`: Verifies all combinations (empty frames, empty tracks, single frame/track, populated datasets).
  - `test_frame_mapping_stream_writer`: Validates line-by-line JSON Lines (.jsonl) generation and metadata record appending.

---

## Verification & Results

### 1. Unit Test Suite
```powershell
C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe -m unittest scripts/tests/test_stream_json_writer.py
```
**Result**: 3/3 tests passed in 0.011s. Streamed JSON is byte-for-byte identical to standard `json.dump()`.

### 2. Full Regression Suite
```powershell
C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe -m unittest discover -s scripts/tests
```
**Result**: 15/15 tests passed with zero failures.

### 3. Real Session Verification (`session_20261023_111120`)
```powershell
python tools/sync_and_process_sessions.py --process-only --session session_20261023_111120 --force
```
**Output Highlights**:
```
[PROGRESS] {"step": "sync_save", "desc": "[session_20261023_111120] Writing track_history.json (27,040/27,938)", "overall_pct": 98.2, "current": 27040, "total": 27938, "sub": {"file_name": "track_history.json", "file_pct": 96.8, "current_mb": 290.6, "total_mb": 300.3, "speed_mb_s": 34.8, "eta_s": 0.3}}
...
[+] Generated: logs\session_20261023_111120\track_history.json (23,340 frames, 4,598 unique tracks, 305.5 MB)
[PROGRESS] {"step": "done", "desc": "[session_20261023_111120] Visualizer processing complete", "pct": 100, "overall_pct": 100}
```
Validation check confirmed:
- `track_history.json`: 23,340 frames and 4,598 tracks parsed successfully via `json.load()`.
- `frame_mapping.json`: 23,341 records parsed successfully with valid metadata.
