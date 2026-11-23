# Walkthrough: Vision-vs-Radar Benchmarking Methodology & Pitch Back-Calculation Fix

## Summary of Changes

Implemented the approved benchmarking methodology overhaul across `tools/vision/eval/`, `tools/vision/geometry/`, `tools/web_dashboard/index.html`, and `tools/vision/tests/`, fixing systematic evaluation biases and integrating session-wide camera pitch back-calculation with automatic ideal-pitch re-benchmarking.

---

## What Was Implemented

### 1. Spatio-Temporal Associator ([spatial_associator.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/spatial_associator.py))
* Added `anchor_mode` (`"contact_patch"` default, `"position_3d"` / `"center"` selectable) via `get_vision_anchor_xy()` so radar bumper/cluster tracks compare against vision's front ground-contact patch instead of volumetric box interiors (`+length/2`).
* Added `association_mode` (`"nearest"` default for radius existence matching, `"bipartite"` selectable for 1-to-1 `lap.lapjv` assignment).
* Classified every unmatched vision track (`no_radar_tracks_in_frame`, `lost_in_bipartite_1to1`, `near_miss_gate_to_6m`, `miss_6_to_10m`, `far_gt_10m`) and emitted per-frame `radar_matched_indices` for reverse `Radar → Vision` precision tracking.

### 2. Metrics & Backward-Compatible Reporting ([error_metrics.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/error_metrics.py))
* Preserved all legacy top-level keys (`matched_count`, `total_vision_tracks`, `total_radar_tracks`, `match_rate_pct`, `overall`, `range_bins`) while scoping top-level metrics to `(0, max_range_m]` (`50.0 m` default, zero lateral filtering, all classes included).
* Computed true per-bin match rates across both legacy `range_bins` and `extended_range_bins` (`0_15m`, `15_30m`, `30_50m`, `50_75m`, `75m_plus`), plus `all_ranges`, `by_class`, `unmatched_breakdown`, `mean_signed_fwd_bias_m`, and `radar_to_vision` stats.

### 3. Session-Wide Pitch Back-Calculation Solver ([dynamic_pitch_solver.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/geometry/dynamic_pitch_solver.py))
* Implemented `DynamicPitchSolver.solve_session_pitch()` using vectorized NumPy ground-plane projection on `bbox_2d` pixels stored in `*_vision_tracks.json`.
* Uses mutual nearest-neighbor pairs (`r_fwd >= 4.0 m`) with a Huber-loss spatial residual + longitudinal bias objective and Golden-section refinement to recover the ideal pitch angle $\theta^*$ across all classes without collapsing into near-range radar clutter.
* Implemented `DynamicPitchSolver.reproject_vision_frames()` for fast full-session re-projection at $\theta^*$.

### 4. Benchmark Runner, Ideal-Pitch Pass, SSE Progress & ANSI Color Highlighting ([evaluate_vision_vs_radar.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/eval/evaluate_vision_vs_radar.py))
* Fixed `load_radar_tracks_from_mcap()` via `parse_radar_label()` to parse hardware track IDs (`ID #<tid>`) and forward velocities (`vx_fwd_mps`) from `/radar/tracks` labels instead of using `idx + 1`.
* Deduplicated `Radar → Vision` hit counts across unique synchronized radar frames so 30 FPS camera vs 22 FPS radar frame pairing does not double-count radar tracks.
* Added tunable CLI flags (`-g/--gate_m`, `--max-range`, `--anchor`, `--assoc-mode`, `--exclude-classes`, `--min-conf`, `--solve-pitch`/`--no-solve-pitch`) and automated Pass 2 ideal-pitch re-benchmarking (`summary.pitch_calibration_analysis`, `summary.ideal_pitch_benchmark`).
* Added continuous `[PROGRESS]` SSE streaming across all phases (`load_mcap` `2%–10%`, `load_vision` `12%`, Pass 1 `15%–40%`, `pitch_solve` coarse/fine iterations `40%–78%`, re-projection `79%`, Pass 2 `82%–97%`, report write `98%`, `done` `100%`) with live `current/total`, `fps`, and `eta_s`.
* Added semantic ANSI color highlighting (`_color_rate`, `_color_mae`, `_color_bias`, `_color_pitch_err`) matching `session_health_check.py`, `verify_conversion_parity.py`, and the Web Dashboard `ansiToHtml()` palette.

### 5. Web Dashboard Controls & UTF-8 Server Subprocess Decoding ([index.html](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/web_dashboard/index.html), [roadsense_web_server.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/roadsense_web_server.py))
* Updated the Vision Benchmark card and `runVisionBenchmark()` with tunable controls for Distance Gating Cutoff (`3.5 m` default), Max Forward Range (`50 m` default, `0` = All), Vision 3D Anchor (`Contact Patch` / `3D Box Center`), Matching Mode (`Nearest Radius` / `1-to-1 Hungarian`), Exclude Pedestrians, and Back-Calculate Ideal Pitch.
* Fixed `cp1252` mojibake (`â€¢`, `Â°`) in `roadsense_web_server.py` `start_process()` by adding `encoding="utf-8", errors="replace"` and `PYTHONIOENCODING="utf-8"` to `subprocess.Popen`.

### 6. Automated Unit Tests ([test_vision_eval.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/tests/test_vision_eval.py))
* Added 6 unit tests covering contact-patch vs. center anchoring, nearest-radius vs. bipartite matching, zero lateral filtering + `50 m` scope + `>50 m` bins, backward-compatible JSON summary keys, radar label ID/speed parsing, and synthetic pitch error recovery in the presence of near-range clutter.

---

## Verification Results

### 1. Automated Tests (`cv313`)
```powershell
C:\ProgramData\miniconda3\envs\cv313\python.exe -m unittest discover -s tools/vision/tests -v
```
* **Result:** **7/7 tests PASSED** (`0.083s`), including existing [test_perception_timestamps.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/tests/test_perception_timestamps.py) and all 6 new tests in [test_vision_eval.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/vision/tests/test_vision_eval.py).
* Module self-tests (`spatial_associator.py`, `error_metrics.py`) both exited `0` (`Status: PASSED`).

### 2. Full Session Validation (`session_20261120_112453`)
```powershell
C:\ProgramData\miniconda3\envs\cv313\python.exe tools/vision/eval/evaluate_vision_vs_radar.py logs/session_20261120_112453
```
* **Configured Pitch (`5.232°`, `0–50 m`, `3.5 m` gate, `contact_patch`, `nearest`, all lateral, all classes):**
  * Scoped Match Rate: **`22.5%`** (`11,015 / 48,999`), `0–15 m`: **`37.9%`**, `15–30 m`: **`5.7%`**, `30–50 m`: **`0.5%`**, `50–75 m`: **`0.0%`**, `>75 m`: **`0.0%`**
  * Fwd MAE / RMSE / Bias: **`1.272 m` / `1.549 m` / `-0.172 m`**
  * Reverse `Radar → Vision` Precision: **`16.2%`** (`8,628 / 53,374`), with `8,994 / 40,024` empty radar frames.
* **Pitch Calibration Regression:**
  * Back-Calculated Ideal Pitch: **`4.238°`** (configured pitch was **`0.995°` too steep**; adjustment `-0.995°`)
  * Mutual-NN Median Distance: **`2.156 m` → `1.897 m`** | Median Fwd Bias: **`-0.391 m` → `-0.002 m`**
* **Ideal Pitch Re-Benchmark (`@ 4.238°`):**
  * Scoped Match Rate (`0–50 m`): **`22.6%`** (`10,082 / 44,640`), with Fwd MAE improved to **`1.136 m`** (RMSE **`1.422 m`**)
  * **`15–30 m`** match rate nearly doubled from `5.7%` → **`10.5%`** (`1,634 / 15,558`), Fwd MAE `1.665 m` → **`1.332 m`**, Bias `-0.151 m` → **`+0.046 m`**
  * **`30–50 m`** match rate tripled from `0.5%` → **`1.6%`** (`125 / 7,670`)
  * Per-class longitudinal bias converged near zero (`bus`: `-0.64 m` → **`+0.012 m`**, `truck`: `-0.351 m` → **`-0.006 m`**, `car`: **`+0.03 m`**, `motorcycle`: `-0.384 m` → **`-0.086 m`**).
