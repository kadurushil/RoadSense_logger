# Implementation Plan: Phase 5 - Calibration Studio IMU Decoupling & Root Recomposition Eradication

## Goal Description
In `session_20261113_101048` and subsequent traces, opening the Fullscreen Calibration Studio (`FullscreenCalibrationStudio.kt`) suffered severe frame drops (`p50 = 23.1–51.4 ms`, `87–100%` jank) even when **zero radar packets** were arriving.

### Forensic Root Cause
1. **Root Recomposition Thrashing:** `FullscreenCalibrationStudio` collects `val imuTelemetry by viewModel.imuTelemetry.collectAsState()` directly at the root composable level (`L85`). Whenever phone orientation shifts, `activeDisplayParams` is recomputed at 25 Hz (`L90`), triggering a full recomposition of:
   - The entire root hierarchy (AndroidView texture setup check, sliders, top action bar, drawer, bottom controls).
   - `ViewfinderRadarOverlay` re-passing `calibrationParams` by value.
2. **Per-Frame Skia Canvas Allocations:** Inside `FullscreenCalibrationStudio` (`L346–L453`), the horizon line, distance ladder rungs, boresight crosshairs, and text badges allocate new native `Paint()` instances, `PathEffect`s, and `Typeface` configurations **every single draw tick**.
3. **Pill & Badge High-Rate Recompositions:** `DigitalClampLevelPill` and `PitchQualityBadge` receive updated float roll/pitch values directly on the top floating bar.

### Verification without Radar
> [!IMPORTANT]
> **No radar board is needed to verify Phase 5.**
> Calibration Studio stutter is entirely driven by Camera preview rendering and internal phone IMU orientation telemetry (`ImuManager`). You can verify this fix directly on the bench with just the phone: open the app, enter Fullscreen Calibration Studio (Camera Tab -> Calibration Card -> Fullscreen button or SBS tab), move/tilt the phone, and inspect the `[WINDOW_5S]` stats.

---

## Proposed Changes

### Component 1: Hardware IMU Telemetry Cadence
#### [MODIFY] [`ImuManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)
- Keep UI update interval strictly capped at `<= 30 Hz` (`UI_UPDATE_INTERVAL_NS = 33_333_333L` = 30 Hz, or maintain 25 Hz `40_000_000L`).
- Document that live IMU telemetry is conflated for UI consumption and completely paused during recording to ensure zero background sensor competition.

---

### Component 2: Decouple IMU from FullscreenCalibrationStudio Root
#### [MODIFY] [`FullscreenCalibrationStudio.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCalibrationStudio.kt)

1. **Remove `imuTelemetry` from Root:**
   - Remove `val imuTelemetry by viewModel.imuTelemetry.collectAsState()` from the top of `FullscreenCalibrationStudio`.
   - Remove `val activeDisplayParams` derived root state calculation.
   - `FullscreenCalibrationStudio` root will now only recompose when manual calibration values or studio UI toggles change (slider moves, drawer open/close, toggle buttons).

2. **Extract Isolated Leaf Horizon Canvas (`CalibrationHorizonOverlay`):**
   - Create a dedicated `@Composable private fun CalibrationHorizonOverlay(...)` that collects `imuTelemetry` (or receives a lambda provider `imuProvider: () -> ImuTelemetryState`).
   - Draw the distance ladder rungs, boresight horizon crosshairs, and pitch/roll degree text strictly inside this leaf's `Canvas` draw phase.
   - **Cache all Paint and PathEffect instances outside the draw loop:**
     - `remember { PathEffect.dashPathEffect(floatArrayOf(25f, 15f), 0f) }`
     - Pre-created text `Paint` objects with configured typeface, anti-alias, and alignment.
     - Zero object allocations on the RenderThread per draw.

3. **Decouple `ViewfinderRadarOverlay` Calibration Feeding:**
   - Provide calibration parameters dynamically to `ViewfinderRadarOverlay` via lambda or memoized provider when `isLiveImuHorizonEnabled` is active, so the overlay updates without tearing down the parent composable.

4. **Isolate Top Bar IMU Pills (`DigitalClampLevelPill` & `PitchQualityBadge`):**
   - Wrap `DigitalClampLevelPill` and `PitchQualityBadge` in an isolated leaf composable `CalibrationImuTopBarCluster(viewModel: RadarViewModel, ...)` so high-rate roll/pitch changes only recompose those two tiny pill surfaces without invalidating the top floating bar, exit button, or title text.

---

## Verification Plan

### Automated Tests
1. Run full JVM unit test suite to ensure no regressions in spatial projection, math, or UI state:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest --rerun-tasks
```
2. Build debug APK:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug
```

### Manual Bench Verification (No Radar Required)
1. Install debug APK on phone.
2. Launch RoadSense (radar not connected).
3. Switch to **Camera** tab and tap **"Launch Studio"** (or open Calibration Studio from SBS).
4. Enable **"Live IMU"** toggle in the top bar.
5. Tilt and rotate phone continuously for 20–30 seconds.
6. Check `[WINDOW_5S]` in the debug log:
   - Confirm frame `p50` stays below `16.7 ms` (or matches camera preview rate).
   - Confirm `layoutP95` stays negligible (`< 0.3 ms`).
   - Confirm no visual hitching or stutter while the cyan horizon line and spirit level smoothly track phone motion.
