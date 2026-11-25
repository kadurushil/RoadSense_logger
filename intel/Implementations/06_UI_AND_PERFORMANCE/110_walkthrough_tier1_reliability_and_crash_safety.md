# Walkthrough: Tier 1 (`110`) — Critical Reliability & Crash-Safety Shield

## Summary of Changes

Executed Pre-Work Step 0 and implemented all 6 Tier 1 reliability subsystems on branch **`Overhaul`**:

### Step 0 — Baseline Archive & Branch Hygiene
- Committed Update 4 presentation deliverables onto `validation` (`430777b`).
- Copied verified `validation` APK (`65,253,526` bytes) locally to `APK versions/v1.0 (stable).apk` and ignored `APK versions/` + `*.apk` in [`.gitignore`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/.gitignore).
- Branched **`Overhaul`** from `validation` and committed Master Roadmap [`109_plan_reliability_ux_soak_and_pitch_master_roadmap.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Implementations/08_HANDOVER_AND_DOCS/109_plan_reliability_ux_soak_and_pitch_master_roadmap.md), Tier 1 Plan [`110_plan_tier1_reliability_and_crash_safety.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Implementations/06_UI_AND_PERFORMANCE/110_plan_tier1_reliability_and_crash_safety.md), and [Future_stuff.md](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/Future_stuff.md) Section 9 (`bdd5f52`).

---

### Tier 1 Implementations (Uncommitted on `Overhaul` for Review)

1. **Foreground Service & Partial WakeLock (`targetSdk = 37` Safe):**
   - Added `FOREGROUND_SERVICE` (`camera | location | connectedDevice | dataSync`), `POST_NOTIFICATIONS`, and `WAKE_LOCK` in [AndroidManifest.xml](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/AndroidManifest.xml).
   - Created [RecordingForegroundService.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/RecordingForegroundService.kt) building dynamic permission-gated service types (with `DATA_SYNC` fallback), holding a 4-hour bounded `PARTIAL_WAKE_LOCK`, and surfacing a persistent notification with a **Stop Recording** action.
   - Added non-blocking Android 13+ notification permission request in [MainActivity.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt).

2. **Protected Single-File `MediaRecorder`, `StorageGuard` & `CrashRecoveryHandler`:**
   - Created [StorageGuard.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/StorageGuard.kt) enforcing **`1,500 MB`** low-storage warning and **`500 MB`** graceful recording auto-stop via `StatFs`, wired into start checks, 10 s periodic polling, and a dismissible alert banner in [MainActivity.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt).
   - Created [CrashRecoveryHandler.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/diagnostics/CrashRecoveryHandler.kt) and `awaitFlush()` in [RadarSessionRecorder.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/RadarSessionRecorder.kt) (`2,000 ms` bounded flush + `terminatedByCrash` flag) + thread-safe synchronized recording lifecycle in [RadarViewModel.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt).
   - Added `setOnErrorListener` & `setOnInfoListener` to [CameraEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraEngine.kt).

3. **USB Hot-Plug Detach & Auto-Recovery:**
   - Added `RadarConnectionState.Reconnecting`, `ACTION_USB_DEVICE_DETACHED` / `ACTION_USB_DEVICE_ATTACHED` receivers, bounded backoff retries, and `onUsbLifecycleEvent` in [RadarConnectionManager.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt).
   - Handled `Reconnecting` (`YELLOW / RECONN` pill & UI cancel button) in [SensorReadinessEvaluator.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/SensorReadinessEvaluator.kt) and [RadarDashboardCard.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/RadarDashboardCard.kt).

4. **Thermal Throttling Monitor & Zero-Alloc HUD Indicator:**
   - Created [ThermalMonitor.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/diagnostics/ThermalMonitor.kt) (`PowerManager` API 29+ listener + battery-temp fallback `>= 40°C / 45°C`), logging `SYSTEM / THERMAL_CHANGE` events and rendering `THRM` / `HOT!` & `LOW <1.5G` inside the existing single-node Canvas in [LiveMetricsBar.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/LiveMetricsBar.kt).

5. **`SessionSyncAuditor` 200 Hz IMU & Adaptive Radar JSON Parity:**
   - Extended [SessionInfo.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionInfo.kt) with `salvagedFrames`, `sequenceDrops`, `peakThermalSeverity`, `peakBatteryTempC`, and `terminatedByCrash`.
   - Updated [SessionSyncAuditor.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionSyncAuditor.kt) to auto-detect `200 Hz` IMU (`<= 7.5 ms` p50), compute adaptive `10–60 Hz` Radar cadence, and serialize `sequenceDrops`, `salvagedFrames`, `meanIntervalMs`, `p50IntervalMs`, `p95IntervalMs`, and `maxIntervalMs` under `"radar"`.

6. **Option A Static Hardware Road AE (`0%` CPU/GPU, `0` Mid-Drive HAL Calls):**
   - Deleted dead `startLuminanceAnalyzerLegacyDisabled()` `getBitmap()` code in [CameraEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraEngine.kt) and simplified `applyAeMeteringSettings()` to apply static hardware `CONTROL_AE_REGIONS` (bottom 65% road zone) only at capture-session setup or manual toggle.

---

## Verification Results

### Automated JVM Unit Tests
```powershell
$jbr = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
$env:JAVA_HOME = $jbr
./gradlew --stop
./gradlew testDebugUnitTest "-Dorg.gradle.java.home=$jbr" --console=plain
```
- **Result (`BUILD SUCCESSFUL in 26s`):** **30 test suites, 129 tests passed, 0 failures, 0 errors, 0 skipped.**
  - New suites: [StorageGuardTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/recording/StorageGuardTest.kt) (`1/1`), [ThermalMonitorTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/diagnostics/ThermalMonitorTest.kt) (`1/1`), [SessionSyncAuditorTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/recording/SessionSyncAuditorTest.kt) (`2/2`).
  - Updated suites: [SensorReadinessEvaluatorTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/ui/SensorReadinessEvaluatorTest.kt) (`4/4`), [SessionInfoTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/recording/SessionInfoTest.kt) (`1/1`).

### Post-Test Fixes (Verified After `session_20261125_101821`)
1. **Hot-Plug Permission Path:** Preserved `pendingUsbReconnect` across Android `ACTION_USB_PERMISSION` in [RadarConnectionManager.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt) so `USB_RECONNECTED` and pipeline buffer resets run even after a runtime permission prompt, and added `markRadarDisconnected()` in [CrossSensorSyncAssociator.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/CrossSensorSyncAssociator.kt) + [RadarViewModel.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt) to cut the 1.9 s stale `CONNECTED` keyframe tail.
2. **Orientation & Surface Detach Hardening:** Added `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` in [AndroidManifest.xml](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/AndroidManifest.xml), `if (!isChangingConfigurations)` around `AppLogger.closeAppLogging()` in [MainActivity.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt), generation-guarded `onConfigured`/`onConfigureFailed`, and `recorderSurface`-only reconfiguration on `detachPreviewSurface` mid-recording in [CameraEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraEngine.kt).
3. **Adaptive 20 Hz Radar Sync Threshold & Hygiene:** Updated `radarCameraProximity` in [SessionSyncAuditor.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionSyncAuditor.kt) to evaluate compliance against adaptive radar Nyquist half-period (`max(16.67 ms, radarP50 * 0.5)` = `25.0 ms` at `20 Hz`), logged `AUTO_ROAD` `MeteringRectangle` diagnostics in `CameraEngine.kt`, and restricted `camera/camera_frames.csv` in [SessionInfo.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionInfo.kt) `activeStreams` to existing files only.

### Remaining Manual Step (User Hardware Test)
Per `gemini.md` Rule 5, build & install the `Overhaul` APK on `SM-M325F` when ready to re-verify USB hot-plug (`USB_RECONNECTED`), rotation/background camera continuity (`0` drops), and `AUTO_ROAD` HAL region logs before committing Tier 1.
