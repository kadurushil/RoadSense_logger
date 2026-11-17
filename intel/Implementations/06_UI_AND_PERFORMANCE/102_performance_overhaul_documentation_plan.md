# Documentation Plan: UI, SurfaceView, Threading & VSync Overhaul (`bdc5cbdd..b866676`)

## Goal Description
Since commit `bdc5cbdde71cf8d70c0da0773770f44e6d03c196`, 15 commits (`7e4dd84` through `b866676`) delivered major architectural and performance upgrades across camera rendering, Compose state isolation, recording thread priorities, spatial projection math, and VSync-locked radar pacing.

This plan outlines how we will document **how each problem was identified**, **how it was solved**, and **how it was verified** across `intel/Implementations/`, `context/`, and `intel/debugging_and_troubleshooting_knowledge_base.md`.

---

## Scope of Commits Covered (`bdc5cbdd..b866676`)
- **IMU & Calibration Decoupling:** `7e4dd84` (Phase 5 Calibration Studio IMU decoupling), `f97be04` (Canvas IMU telemetry)
- **Launch UX & Diagnostics:** `af8bd12`, `3046840`, `48e1e17` (Launch build overlay, app icon, black launch theme), `953d80f` (PC parity diagnostics), `d602ab2` (Implementations index), `edad9e8` (Gradle test task aliases)
- **4-Phase Performance Overhaul:**
  - `edc0d39` (Phase 1: Disable Road AE `getBitmap` readbacks, remove FBO rounded-corner clips, fix viewfinder aspect ratios)
  - `0f73430` (Phase 2: Single-tab composition replacing `KeepAliveTabSlot`, `GnssState.Active` transition-only emit, `@Stable RadarViewModel`, 10s battery polling + cached `cpuTempSupported`)
  - `13588cd` (Phase 3: Demote 7 disk writers to `THREAD_PRIORITY_BACKGROUND`, `DebugToggles.diskWriterPriorityLogs`, skip `camera_frames.csv` with `session_timeline.csv` fallback, 30 Hz lazy IMU Euler angles, `StringBuilder` CSV rows)
  - `26cd3dc` (Phase 4: Precomputed 3×3 extrinsic matrix in `SpatialProjectionEngine`, Foxglove-style lightweight BEV/HUD primitives, remove `BoxWithConstraints`)
- **Persistent `SurfaceView`, `ImageBitmap` Caching & Adaptive VSync Pacer:**
  - `0c5e146` (`AutoFitSurfaceView`, `PersistentCameraViewportHost`, single root `SurfaceView` in `MainActivity`, `.graphicsLayer()` overlay isolation, SBS transparent `Card`, `FullscreenCalibrationStudio` Compose `State` read)
  - `600c85e` (`AndroidView.update` guard, static `ImageBitmap` grid/arc caching)
  - `b866676` (Adaptive `Choreographer` VSync-locked `RadarDisplayPacer` with 4 pitfall mitigations)

---

## Proposed Documentation Changes

### 1. Comprehensive Engineering Walkthrough

#### [NEW] `intel/Implementations/06_UI_AND_PERFORMANCE/102_ui_surfaceview_and_vsync_overhaul_walkthrough.md`
Create an authoritative technical walkthrough structured into:
1. **Executive Before/After Scorecard:** Side-by-side table comparing baseline (`session_20261116_143131`) against final verified (`session_20261117_133844` & `session_20261117_132022`), covering `DRAW_BEV`, `DRAW_OVERLAY`, `gpuP95`, `unkP95`, `p50`, `deltaDrawMs`, fullscreen transition freezes, and 0-drop recording metrics.
2. **Problem Identification & Forensic Evidence:** Exact `[WINDOW_5S]`, `dumpsys media.camera`, and `[DRAW_BEV]`/`[DRAW_OVERLAY]`/`[EMIT]` evidence for each bottleneck (Road AE GPU stalls, FBO clips, `KeepAliveTabSlot` invalidation storms, disk priority inversion, per-vertex trig, `TextureView` GPU copy, double overlay draws, `22 Hz` vs `60 Hz` VSync beat).
3. **Architecture & Fixes Implemented:** Concise code-level explanations + Mermaid diagram of the final pipeline (`AutoFitSurfaceView` → `SurfaceFlinger`, `PersistentCameraViewportHost`, `ImageBitmap` static caches, Adaptive `Choreographer` `RadarDisplayPacer`).
4. **Verification Trail:** Session-by-session audit summary (`143131` → `174914` → `112258` → `114144` → `125829` → `132022` → `133844`) plus `./gradlew --no-daemon assembleDebug testDebugUnitTest` and `session_health_check.py` results.

---

### 2. Authoritative `context/` Synchronization

#### [MODIFY] `context/00_MASTER_EXECUTIVE_HANDOVER.md`
- Add the completed UI/SurfaceView/VSync overhaul milestone (`edc0d39..b866676`) and current verified baseline metrics.

#### [MODIFY] `context/01_ARCHITECTURE_AND_STORAGE.md`
- Update storage layout (note `camera/camera_frames.csv` removed; camera timestamps indexed in `session_timeline.csv`) and UI architecture (Single-Tab Composition + `PersistentCameraViewportHost`).

#### [MODIFY] `context/03_CAMERA_GNSS_AND_CROSS_SENSOR_SYNC.md`
- Document `AutoFitSurfaceView` + single persistent root `SurfaceView` host (`restarts=0`, 0-drop tab/fullscreen recording), Road AE currently disabled (`FULL_MATRIX`), `GnssState.Active` transition-only emit, and Adaptive `Choreographer` `RadarDisplayPacer`.

#### [MODIFY] `context/05_FLIGHT_RECORDER_AND_DIAGNOSTICS.md`
- Document `DebugToggles.diskWriterPriorityLogs`, `[SURFACE_VIEWPORT]`, `[VSYNC_RATE_ADAPT]`, and `THREAD_PRIORITY_BACKGROUND` (`+10`) verification logs.

---

### 3. Implementation Index & Knowledge Base

#### [MODIFY] `intel/Implementations/00_IMPLEMENTATION_INDEX.md`
- Register `#101` (`101_surfaceview_and_overlay_dedup_plan.md`) and `#102` (`102_ui_surfaceview_and_vsync_overhaul_walkthrough.md`).

#### [MODIFY] `intel/debugging_and_troubleshooting_knowledge_base.md`
- Add concise post-mortem entries for the newly resolved issues:
  - `TextureView` GPU copy & opaque Compose `Card` hiding `SurfaceView` hole-punching (`SbsDashboardCard`).
  - `StateFlow.value` vs Compose `State<T>` snapshot tracking inside `Canvas` (`FullscreenCalibrationStudio`).
  - `22 Hz` radar vs `60 Hz` VSync beat sawtooth (`RadarDisplayPacer` `frameTimeNanos` `Choreographer` fix).
  - JetBrains `kotlin-server` JRE missing `jlink.exe` pitfall.

---

## Verification Plan
- Verify all file paths, commit SHAs, symbol names, and numbers in the new/updated docs match `git log` and `session_debug.log` outputs directly.
- Present a concise summary of all updated documentation files and wait for user review before committing.
