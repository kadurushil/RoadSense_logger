# Implementation Plan: Tier-2 IMU Gyro-Yaw Alignment Integration (`tools/CAN_Processing/`)

## 1. Goal & Scope
Integrate the validated **Tier-2 Gyro-Yaw cross-correlation** engine (from `tools/CAN_Sync_Lab/`) into the production **`tools/CAN_Processing/`** package so every session processed through the CLI or **RoadSense Web Dashboard (`--mcap`)** automatically achieves `~25 ms` + drift-compensated CAN synchronization even when Radar (`radar_frames.bin`) is absent.

## 2. Empirical Safeguards Identified from Existing No-Radar Captures
Testing against the 3 zero-radar logs in `logs/` revealed two essential safeguards to include during integration:
1. **Dual-Anchor Coarse Search (Stale Metadata Immunity):**
   * On `session_20261119_104518` (`176.4 s`, no Radar), `session_metadata.json` had a stale `clockOffsetMs = 57,614 ms`, while the actual CAN clock was near NTP UTC (`+0.53 s` at `0.900` correlation, `11.9σ` SNR). Pre-shifting only by `meta_offset_sec` with a narrow search window pushed the true peak out of range.
   * **Design:** Search overlapping lags around **both** `0.0 s` (NTP baseline) **and** `meta_offset_sec` (when `|meta_offset_sec| > 5.0 s`), using `eff_lag = min(max_search_lag_s, 0.70 * overlap_duration_s)`, and select whichever anchor yields the stronger valid peak.
2. **Tiered Confidence Gate (Short Drive Support + Stationary Safety):**
   * On `session_20261119_105126` (`119.2 s`, no Radar), `Gyro Projected Yaw (-sign)` achieved **`0.998`** correlation (`-0.096 s` vs VCU filtered gyro) with **`4.2σ`** SNR (because short 1-turn drives have a wider autocorrelation lobe).
   * Meanwhile `session_20261119_121554` (stationary bench session) had `0.214` max correlation.
   * **Design:** Accept a Tier-2 lock only when **both** minimum turning energy (`std(phone_yaw) >= 0.025 rad/s` & `peak >= 0.08 rad/s`) **and** tiered correlation confidence hold: `(corr >= 0.85 and snr >= 3.0)` **or** `(corr >= 0.65 and snr >= 4.5)`. Otherwise fall back cleanly to `TIER3_METADATA`.

## 3. File-by-File Changes

### [NEW] `tools/CAN_Processing/imu_aligner.py`
* Houses the production Tier-2 module (`extract_gravity_projected_yaw`, `load_phone_imu_yaw`, `lowpass_boxcar`, `fft_cross_correlate_lag`, `align_can_with_imu_tier2`):
  * Automatically resolves the phone's gravity axis `g_hat = median(accel)` and projects `(gx, gy, gz)` onto `g_hat`.
  * Low-pass filters at `1.5 Hz` (2-pass symmetric boxcar) to reject windshield/handlebar vibration.
  * Prioritizes **`TEL_IMU_Z_Axis_Raw.CNS_TEL_GYRO_Z_RAW`** (`~25 ms` zero-filter-delay raw gyro) with automatic fallback to **`VCU_IMU_Processed_Angular_Msg.ETS_VCU_imuProc_yawRate`**.
  * Performs dual-anchor FFT cross-correlation (`np.fft.rfft`), auto `+1 / -1` polarity selection, rolling `240 s / 90 s` windows on long drives (`>= 300 s`), and MAD outlier filtering + linear drift estimation (`ppm`).

### [MODIFY] `tools/CAN_Processing/can_mcap_config.json`
* Add `"TEL_IMU_Z_Axis_Raw"` to `important_messages` so `--can-mode whitelist` also loads the unfiltered hardware gyro channel.
* Add alignment defaults (`"tier2_window_sec": 240.0`, `"tier2_step_sec": 90.0`, `"tier2_max_search_lag_s": 120.0`).

### [MODIFY] `tools/CAN_Processing/time_aligner.py`
* Update `CanAlignmentResult.primary_tier` docstring (`"TIER1_RADAR_FRAME_ID" | "TIER2_IMU_GYRO" | "TIER3_METADATA" | "NONE"`).
* Extend `align_can_timeline(...)` with optional `enable_tier2: bool = True`, `tier2_window_sec: float = 240.0`, `tier2_step_sec: float = 90.0`, `tier2_max_search_lag_s: float = 120.0`.
* When Tier 1 (`RADAR_Debug_Info` ↔ `radar_frames.bin`) fails or is absent and `enable_tier2=True`, invoke `align_can_with_imu_tier2(...)` before falling back to `TIER3_METADATA`.

### [MODIFY] `tools/CAN_Processing/pipeline.py` & `tools/CAN_Processing/__init__.py`
* Pass `tier2_window_sec`, `tier2_step_sec`, `tier2_max_search_lag_s` from `can_mcap_config.json` into `align_can_timeline(...)`.
* Export Tier-2 symbols cleanly from `__init__.py` if needed.

### [NEW] `tools/CAN_Processing/tests/test_imu_aligner.py` & Cleanup of `tools/CAN_Sync_Lab/`
* Move/expand unit & regression tests into `tools/CAN_Processing/tests/test_imu_aligner.py` (testing synthetic drifted/shaky/tilted mount, stale `clockOffsetMs` `57.6 s` dual-anchor recovery, stationary bench Tier-3 fallback, short `119 s` drive acceptance, and `session_20261120_112453` Tier-1 GT benchmark).
* Remove temporary prototype `tools/CAN_Sync_Lab/` once migrated cleanly so zero duplicate code remains.

## 4. Verification Plan
1. Run full `tools/CAN_Processing/tests` suite (`python -m unittest discover -s tools/CAN_Processing/tests -v`) in `roadsense-mcap`.
2. Run production `tools/sync_and_process_sessions.py --process-only --mcap --force --session <id>` on:
   * `session_20261119_104518` (no Radar, stale metadata `57.6 s`) → expect **`TIER2_IMU_GYRO`**!
   * `session_20261119_105126` (no Radar, short `119 s` drive) → expect **`TIER2_IMU_GYRO`**!
   * `session_20261119_121554` (no Radar, stationary bench) → expect safe **`TIER3_METADATA`** fallback!
   * `session_20261120_112453` (full Radar+CAN drive) → confirm still uses **`TIER1_RADAR_FRAME_ID`** cleanly (`+0.635 s`, `-146.8 ppm`).
