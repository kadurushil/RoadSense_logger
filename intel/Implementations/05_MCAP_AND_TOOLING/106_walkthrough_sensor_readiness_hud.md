# Walkthrough: Sensor Readiness Audit Fixes & Unified Traffic-Light HUD

## Overview
Implemented across 4 verified components with separate per-component commits on branch `validation` (following the approved [Sensor Readiness Plan](106_plan_sensor_readiness_hud_and_imu_gnss_audit.md)):

---

## Component 1 — IMU Primary Stream Rate Fix (`200 Hz`)
* **Root Cause:** `ImuManager.kt` previously divided the combined sample counter across 4 registered sensors (`TYPE_ACCELEROMETER` & `TYPE_GYROSCOPE` at `200 Hz`, `TYPE_LINEAR_ACCELERATION` & `TYPE_GAME_ROTATION_VECTOR` at `100 Hz`) by `4`, reporting `(200 + 200 + 100 + 100) / 4 = 150 Hz` on the live HUD while actually recording `200 Hz` accel/gyro frames to CSV/MCAP.
* **Change:** Replaced shared-divider counting with primary-driver event counting on `Sensor.TYPE_ACCELEROMETER` (with gyroscope fallback) via `computePrimaryImuRateHz` (`ImuManager.kt#L46-L49`).
* **Tests:** `app/src/test/java/com/bajajauto/roadsense/imu/ImuRateCalculationTest.kt` (2 tests).

## Component 2 — GNSS Pre-Fix Satellite Stream, A-GPS Injection & True Lock Gate
* **Root Cause:** Offline backup phones without cellular/Wi-Fi retain stale XTRA ephemeris data, delaying cold TTFF. Meanwhile `NETWORK_PROVIDER` could emit coarse fixes while satellite view counts stayed blank until first GPS fix.
* **Change:** Updated `GnssLocationManager.kt` and `RadarViewModel.kt` to:
  * Stream pre-fix `satelliteStatus` (`used/inView`) live into `LiveMetricsSnapshot`.
  * Invoke best-effort `force_time_injection` / `force_xtra_injection` on `LocationManager.GPS_PROVIDER` when starting GNSS or refreshing A-GPS.
  * Require genuine satellite lock (`hasTrueSatelliteLock`: `GPS_PROVIDER`, `satellitesUsed >= 4`, live fix rate) so coarse `NETWORK_PROVIDER` never shows Green GPS ready.
* **Tests:** `app/src/test/java/com/bajajauto/roadsense/gnss/GnssReadinessTest.kt` (2 tests).

## Component 3 — CANedge2 `CALIBRATING` Readiness Gate & RTC Offset UI
* **Root Cause:** Connecting to the CANedge2 HTTP server was reported as `CONNECTED_SYNCED` (`OK`) immediately, even before the first 60-second chunk rollover measured its RTC skew against Android wall time.
* **Change:**
  * Added `CanedgeHudPhase.CALIBRATING` and `isClockOffsetVerified: Boolean` in `CanedgeFile.kt`.
  * Extracted pure `CanedgeSyncPlanner.resolveHudPhase` and wired it into `CanedgeIngestionManager`, gating on `hasVerifiedClockOffset(nowMs)` before transitioning `CALIBRATING` (`CAL`, Yellow) → `CONNECTED_SYNCED` (`OK`, Green).
  * Added an inline single-line `RTC` status pill + calibration banner inside `CanedgeConnectionStatusCard` (`CanedgeDashboardCard.kt`) and enriched `Measured RTC Offset` in diagnostics.
* **Tests:** `resolveHudPhaseEnforcesCalibrationGateUntilRtcOffsetVerified` in `CanedgeSyncPlannerTest.kt`.

## Component 4 — Unified Traffic-Light HUD & Width-Adaptive Pill Labels
* **Change:**
  * Added pure traffic-light classifier `SensorReadinessEvaluator` (`GREEN`, `YELLOW`, `CYAN`, `RED`) across all 5 sensors (`RADAR`, `CAM`, `GPS`, `IMU`, `CAN`):
    * **RED (`0xFFFF5252`)** for both intentional disconnection (`OFF`) and runtime faults or mid-recording Radar dropouts (`ERR`).
    * **YELLOW (`0xFFFFB300`)** for initializing / searching (`SRCH`) / coarse network fix (`NET`) / RTC calibrating (`CAL`) / waiting final chunk (`WAIT`) / paused during recording (`PAUSE`) / offline pending sync (`<N>P`) / degraded sample rates.
    * **GREEN (`0xFF00E676`)** for ready-to-record streams (`RADAR >= 15 Hz`, `CAM >= 20 fps`, true `GPS` lock `>= 4 SVs`, `IMU >= 80 Hz`, `CAN OK`).
    * **CYAN (`0xFF80D8FF`)** for active CANedge `.MF4` transfers.
  * Updated `RadarViewModel.kt` to track mid-session Radar connection drops (`radarWasConnectedInSession`, `radarStreamActiveInSession`) and feed precomputed `SensorPillStatus` entries into `LiveMetricsSnapshot`.
  * Updated `LiveMetricsCanvasHud` in `LiveMetricsBar.kt` to measure pixel width at draw time: attempts the **fuller** pill labels (`"20.0Hz OK"`, `"30fps OK"`, `"1.0Hz OK"`, `"200Hz OK"`, `"45% 2/3"`) first and automatically falls back to **compact** labels (`"20.0Hz"`, `"30fps"`, `"1.0Hz"`, `"200Hz"`, `"45%"`) with adaptive gap sizing so nothing clips or overlaps on compact screens.
* **Tests:** `app/src/test/java/com/bajajauto/roadsense/ui/SensorReadinessEvaluatorTest.kt` (4 test methods covering all 5 sensors and mid-recording dropout edge cases).

---

## Verification Results
* **Command:** `./gradlew testDebugUnitTest --no-daemon` (run after final Component 4 edits)
* **Result:** **PASS — `suites=27`, `tests=125`, `failures=0`, `skipped=0`**.
* **APK Note:** Build/install to physical Samsung Galaxy M32 (`RZ8T41HW0FM`) is left to the user per project policy (`GEMINI.md` Rule 5).
