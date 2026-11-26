# Walkthrough 113: UI Frame Pacing Optimizations & Lightweight ADB Profiler

> **Scope Implemented:** Phase 0 (Instrumentation & `scripts/profile_ui_perf.py`) + Phase 1 (Global Cockpit Chrome Zero-Recomposition Readouts, Reverse Landscape Primary) + Phase 2 (Radar Card & BEV Canvas Hotpath) + Debug vs. Staging (Release Runtime) Validation  
> **Locked Baseline:** `logs/soak_runs/soak_20261126_111051` (`Total P50 = 15.90 ms`, `Total P95 = 35.15 ms`, `Max Window P95 = 64.26 ms`, `Draw P95 = 9.12 ms`, `36.0%` jank, `12` DisplayPacer drops)  
> **Final Staging (`release` runtime) Result:** `logs/ui_profiles/profile_radar_staging_20261126_151339` (**`Total P95 = 18.67 ms` (`-47%`)**, **`Max Window P95 = 19.53 ms` (`-70%`)**, **`Anim P95 = 0.33 ms`**, **`Draw P50 / P95 = 0.72 / 1.69 ms` (`-81%`)**, **`0 / 742` slow UI thread frames**, **`0` DisplayPacer drops**, **`0.0 / 5s` recompositions across all 8 tracked scopes**)

---

## 1. Summary of Implemented Changes

### Phase 0 — Centralized Profiling Toggles & 30-Second ADB Profiler
1. **Centralized `DebugToggles` & Storage UI Switch:**
   * Added `uiPerfProfilingLogs` (`isUiPerfProfilingActive`) to `DebugToggles.kt` (defaults `false` for normal drives), exposed `RadarViewModel.setUiPerfProfilingLogs(...)`, and added a **"UI Recomposition & Frame Jank Audit"** switch in `SessionDeckCard.kt`.
   * Added a debug-only `BroadcastReceiver` in `MainActivity.kt` (`com.bajajauto.roadsense.action.SET_UI_PROFILING`, gated on `ApplicationInfo.FLAG_DEBUGGABLE`) so `scripts/profile_ui_perf.py` arms profiling automatically on Debug builds and restores it on exit.
2. **UI Frame Metrics & Recomposition Audit:**
   * Added `animP50` / `animP95` to `FrameWindowStats` in `UiFrameMetricsProfiler.kt` and gated verbose `[JANK_FRAME] >24ms` logcat lines behind `DebugToggles.isUiPerfProfilingActive` while keeping lightweight 5-second `[WINDOW_5S]` summaries active across both Debug and Release/Staging builds.
   * Created lock-free `RecompAudit.kt` with `@NonRestartableComposable fun TrackRecomposition(tag: String)` (using `SideEffect`) so Strong Skipping cannot elide instrumentation, emitting `[RECOMP_5S]` rollups every 5 seconds on the profiler background thread.
3. **Lightweight PC ADB Profiler (`scripts/profile_ui_perf.py` + `scripts/soak_analyzer.py` + `scripts/tests/test_profile_ui_perf.py`):**
   * Auto-detects wired or wireless ADB, captures 30 s of `UiPerf`, `RecompAudit`, `DisplayPacer`, `RadarBevPlot`, and `MainActivity` logs, resets/collects `dumpsys gfxinfo`, pulls the latest `app_system.log` and `session_debug.log`, computes inter-jank period buckets and `[RECOMP_5S]` tables, and supports `--no-toggle`, `--baseline`, and `--compare`.

### Phase 1 — Global Cockpit Chrome Zero-Recomposition Fixes (`2 Hz` Cadence)
1. **`2 Hz` Session State Emission & Coarse Lifecycle Phase (`SessionRecordingState.kt`, `RadarSessionRecorder.kt`, `RadarViewModel.kt`):**
   * Throttled `UI_UPDATE_INTERVAL_MS` from `250L` → `500L` (`2 Hz`).
   * Added `SessionLifecyclePhase` (`IDLE`, `WARMING`, `RECORDING`, `FINALIZING`, `FINISHED`, `ERROR`) and exposed `sessionLifecyclePhase: StateFlow<SessionLifecyclePhase>` via `publishState(...)` so parent chrome subscribers receive **0 emissions/sec** during active recording.
2. **Zero-Recomposition Reverse Landscape Control Bar (`MainActivity.kt`):**
   * Switched `LandscapeSessionControlBar` to `viewModel.sessionLifecyclePhase.collectAsState()` (eliminating the `derivedStateOf` snapshot invalidation that caused `4.83–4.97 ms` `animP95` stalls every `500 ms`).
   * Replaced the Reverse Landscape status badge with fixed-size Direct-Canvas `LandscapeStatusPillCanvas` (`.graphicsLayer()` + `.clearAndSetSemantics {}`), collecting `sessionRecordingState` inside the leaf and reading it strictly inside `DrawScope`.
3. **Portrait Header Hardening (`TopSessionHeader.kt`):**
   * Fixed `rememberTransientSavedFlag(isFinished: Boolean)` so `LaunchedEffect` keys on `Boolean` rather than the mutable `SessionRecordingState.Recording` instance, and extracted `TopSessionStatusPill`.

### Phase 2 — Radar Card & BEV Canvas Hotpath
1. **Zero-Recomposition Radar Telemetry Strip (`RadarDashboardCard.kt`):**
   * Switched `RadarDashboardCard` to `viewModel.sessionLifecyclePhase.collectAsState()`, removed root `latestFrame` collection and the dead `5 Hz` `LaunchedEffect` loop, and replaced the telemetry readout with Direct-Canvas `RadarTelemetryStripCanvas` reading `5 Hz` `radarUiTelemetry` strictly inside `DrawScope`.
2. **Batched BEV Point Cloud & Offscreen Grid Cache (`RadarBevPlot.kt`):**
   * Isolated `RadarBevCanvas` with `.graphicsLayer()`.
   * Kept `BevGeometryCache.cachedGridBitmap` (`ImageBitmap` offscreen cache via `CanvasDrawScope`) after verifying that stroked multi-arc `Path`s triggered a `774.57 KB SW Path Mask` texture on Mali-G52.
   * Replaced per-point `drawCircle(...)` calls with 3 pre-allocated `FloatArray(512)` buffers and 3 batched `nativeCanvas.drawPoints(...)` calls (`Paint.Cap.SQUARE`, `strokeWidth = 8f`, `isAntiAlias = false`), plus zero-allocation indexed cluster/track loops.

---

## 2. Iterative Hardware Benchmark Progression (`SM-M325F`, Reverse Landscape, Radar + Recording Active)

| Run | Active Configuration | Total P50 | Total P95 (`<=16.67`) | Max Win P95 | Jank % (Win / gfxinfo) | Anim P95 | Draw P50 / P95 | GPU P95 | Swap P95 | Drops | Slow UI Thread |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Baseline** (`soak_20261126_111051`) | Pre-Plan 113 (`drawCircle`, 4 Hz header, dead 5 Hz loop) | `15.90 ms` | **`35.15 ms`** | `64.26 ms` | `36.0%` / — | N/A | — / `9.12 ms` | `7.60 ms` | N/A | `12` | — |
| **Phase 1+2** (`radar_phase12`) | 2 Hz header, Landscape Canvas pill, `cachedGridBitmap` + `drawPoints` | `17.01 ms` | `22.02 ms` | `26.80 ms` | `59.0%` / `55.3%` | `4.83 ms` | `2.91 / 4.01 ms` | `7.31 ms` | `3.91 ms` | `0` | — |
| **Phase 2b** (`radar_phase2b`) | Vector `Path` grid (`774.57 KB SW Path Mask`) + `drawCircle` (heavy swaying) | `17.28 ms` | `25.89 ms` | `26.59 ms` | `64.2%` / `52.01%` | `4.97 ms` | `2.98 / 5.99 ms` | `8.22 ms` | `3.74 ms` | `0` | — |
| **Phase 2c** (`radar_phase2c`) | Restored `cachedGridBitmap` + `SessionLifecyclePhase` + `drawCircle` | `17.77 ms` | `23.86 ms` | `114.29 ms`* | `61.2%` / `53.48%` | `0.72 ms` | `4.33 / 8.84 ms` | `7.76 ms` | `3.67 ms` | `0` | `85 / 647` |
| **Phase 2d** (`radar_phase2d`, Debug) | Added batched `drawPoints(Cap.SQUARE)` + zero-alloc loops | **`14.75 ms`** | `22.84 ms` | `25.79 ms` | `26.4%` / `23.85%` | `0.55 ms` | `1.77 / 4.17 ms` | `8.29 ms` | `6.59 ms` | `0` | `13 / 759` |
| **Staging** (`radar_staging`, Release runtime) | Same code built with `staging` (`isDebuggable = false`) | `15.41 ms` | **`18.67 ms`** | **`19.53 ms`** | **`21.8%` / `20.22%`** | **`0.33 ms`** | **`0.72 / 1.69 ms`** | `8.77 ms` | `4.09 ms` | **`0`** | **`0 / 742`** |

*\*`phase2c` `Max Window P95` included a manual USB radar connect tap during the capture window.*

### Key Takeaways
1. **CPU / UI Thread Bottleneck Resolved:** Removing parent `derivedStateOf` subscriptions via `SessionLifecyclePhase` cut `animP95` from `4.83–4.97 ms` → `0.33–0.55 ms`, and batched `drawPoints(Cap.SQUARE)` cut `drawP95` from `8.84–9.12 ms` → `4.17 ms` (Debug) / `1.69 ms` (Staging).
2. **Zero Steady-State Recompositions Verified:** `RecompAudit` confirmed `0.0 / 5s (max = 0)` across all 8 tracked scopes (`CockpitTabs`, `LandscapeBar`, `LandscapePill`, `RadarBev`, `RadarBevPlot`, `RadarCard`, `RadarHwCard`, `RadarTelemetry`).
3. **Operational Decision:** Use the non-debuggable **`staging` / release** variant for road data collection (`18.67 ms` P95, `0` slow UI thread frames, only `1` frame `>24 ms` in 30 s). Remaining `~2 ms` headroom above `16.67 ms` is Mali-G52 GPU texture/blending overhead (`gpuP95 = 8.77 ms` on Radar tab vs `4.74 ms` on Storage tab), deferred for a future GPU-only pass if needed.
