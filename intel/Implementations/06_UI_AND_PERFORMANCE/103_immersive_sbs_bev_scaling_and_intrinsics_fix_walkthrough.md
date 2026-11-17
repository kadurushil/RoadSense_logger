# Walkthrough: Immersive Fullscreen SBS, BEV FOV Scaling & Stream Intrinsics/MCAP Fixes

**Date:** 2026-11-17  
**Branch:** `validation`  
**Commits:** `94d6ffc`, `0d9840e`  
**Status:** Verified via `./gradlew --no-daemon assembleDebug testDebugUnitTest` (exit code `0`) and live MCAP regeneration on `session_20261117_143826` (`SM-M325F`)

---

## 1. Immersive Fullscreen SBS & BEV Scope Scaling (`94d6ffc`)

### 1.1 Problem Identified
1. **Low Screen Utilization in SBS Mode:** In Landscape SBS, `LiveMetricsBar`, `CockpitTabNavigationBar`, 2-row camera telemetry headers, BEV range header, and BEV filter/legend footers consumed >50% of vertical screen real estate, leaving only a small strip for the Camera Viewfinder and Radar BEV Scope.
2. **BEV Lateral Clipping & Upper Canvas Blank Space:** Once vertical headers/footers were removed in Fullscreen SBS, the BEV canvas height roughly doubled while width remained at 50%. Because `RadarBevCanvas` computed `scale = usableHeight / maxRangeMeters` solely from height, the lateral width of the 130 m rings (`2 * usableHeight`) exceeded `canvasWidth`, clipping the sides of the range arcs. After constraining `scale` laterally, empty black vertical space remained above `maxRangeMeters` (e.g. above 60 m in 60 m mode) because the ring loop stopped at `maxRangeMeters`.
3. **Zoom Granularity:** Added a `100m` preset and replaced `130m` with `150m` (`15m`, `30m`, `60m`, `100m`, `150m`).

### 1.2 Solution Implemented
- **`RadarViewModel.kt` / `MainActivity.kt` / `SbsDashboardCard.kt`:**
  - Added `isSbsFullScreen` / `setSbsFullScreen(enabled)` in `RadarViewModel`.
  - Added an Expand (`Icons.Default.Fullscreen`) button in `SbsCameraFeedPane`'s header row and gated `shouldRenderPreview` on `!isSbsFullScreen`.
  - Made `FullscreenRecordButton` in `FullscreenCameraPreview.kt` `internal` for shared reuse without recomposing the parent pill.
- **`FullscreenSbsPreview.kt`:**
  - 50/50 split layout (Landscape side-by-side / Portrait stacked) binding the single persistent `AutoFitSurfaceView` via `Modifier.bindPersistentCameraViewport(slotId = "FullscreenSbs", ...)` with `Color.Transparent` hole and `ViewfinderRadarOverlay`.
  - Floating translucent top pill (`alpha = 0.65f`) with **EXIT** (`BackHandler` also exits), **HUD ON/OFF**, **HUD Mode cycle**, **Range cycle (`15/30/60/100/150m`)**, expandable **Quick Settings** (`Moving Only`, `SNR >= 15dB`, `Arcs`, `Log`), and **REC/STOP**.
- **`RadarBevPlot.kt` & `AppPreferences.kt`:**
  - Added `showChrome: Boolean = true` to `RadarBevPlot` so Fullscreen SBS renders `RadarBevCanvas` edge-to-edge without outer card/header/footer chrome.
  - Constrained scale by the `±60°` radar FOV cone width:
    `scale = minOf(usableHeight / maxRangeMeters, halfWidth / (maxRangeMeters * 0.8660254f))` with `.clipToBounds()`.
  - Extended range ring drawing up to `(usableHeight / scale) + 0.01f` and `±60°` FOV lines to `hypot(canvasWidth / 2f, originY)` so rings and FOV guides fill all vertical space above `maxRangeMeters`.
  - Updated `rangeOptions` across `RadarBevPlot.kt`, `FullscreenSbsPreview.kt`, and `RadarDashboardCard.kt` KDoc to `15m, 30m, 60m, 100m, 150m`, with `130f -> 150f` migration in `AppPreferences.radarMaxRange`.

---

## 2. Missing `/camera/video` in MCAP & M32 16:9 Stream Intrinsics Alignment (`0d9840e`)

### 2.1 Problem Identified
1. **Missing `/camera/video` & `/sync/frame_info` in `session_20261117_143826.mcap`:**
   - Phase 3 stopped writing `camera/camera_frames.csv` at 30 Hz because `MasterTimelineSynchronizer` (`sync_master`) reads `sync/sync_keyframes.csv` and `session_timeline.csv`.
   - However, `tools/convert_session_to_mcap.py` still required `os.path.isfile(frames_csv)` inside both `parse_camera_video_frames()` and the `/camera/video` ingestion guard, silently skipping video demuxing.
2. **Foxglove `/camera/calib` Vertical Misalignment on `SM-M325F` (Bug #35):**
   - Unlike older `HARDWARE_LEVEL_LIMITED` phones that fell back to physical sensor size calculation (`cx = W/2, cy = H/2`), Galaxy M32 exposes `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`.
   - While on-screen `CameraIntrinsicsProvider.getIntrinsics()` subtracted the 4:3-to-16:9 ISP center crop (`cropX`, `cropY`), `getStreamIntrinsics()` (saved to `radar_camera_calib.json` for Foxglove) used uncropped `activeRect` (`cropY = activeRect.top = 0`).
   - As a result, `radar_camera_calib.json` recorded `cy = 480.0` on a `1280×720` stream instead of `cy = 360.0` — a `120 px` (`~7.5°` at `fy = 909.54`) vertical projection error in Foxglove, whereas overriding `baseCx = 640, baseCy = 360` aligned projections cleanly.

### 2.2 Solution Implemented & Verified
- **`tools/convert_session_to_mcap.py`:** Made `camera_frames.csv` optional whenever `sync_master` is active (`if include_video and os.path.isfile(video_mp4) and (sync_master is not None or os.path.isfile(frames_csv)):` and guarded CSV reading inside `parse_camera_video_frames`).
- **`CameraIntrinsicsProvider.kt`:** Applied the exact 4:3-to-16:9 ISP crop (`cropW`, `cropH`, `cropX`, `cropY`) inside `getStreamIntrinsics()` when reading `LENS_INTRINSIC_CALIBRATION`.
- **Verification:**
  - `./gradlew --no-daemon assembleDebug testDebugUnitTest` exited `0` (clean build + all unit tests passing).
  - Updated `logs/session_20261117_143826/radar_camera_calib.json` (`cy = 360`) and re-ran `conda run -n roadsense-mcap python tools/convert_session_to_mcap.py logs/session_20261117_143826` (**7.42s**, **256.68 MB**): confirmed `/camera/calib` (`1` msg), `/camera/video` (`9,562` msgs), and `/sync/frame_info` (`9,562` msgs) aligned cleanly in Foxglove.
