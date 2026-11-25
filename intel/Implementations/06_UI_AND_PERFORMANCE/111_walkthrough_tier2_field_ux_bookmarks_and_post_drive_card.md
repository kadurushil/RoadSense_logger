# Tier 2 Walkthrough — Field UX, 4-Way Swipe Bookmarks, Non-Blocking Stop, Preflight + Warmup & Post-Drive Summary (Plan 111)

> **Branch:** `Overhaul` (uncommitted working tree on top of Tier 1 commit `f64d08d`)  
> **Verification:** **133 / 133** Android JVM unit tests (`./gradlew testDebugUnitTest --no-daemon`) + **2 / 2** Python MCAP unit tests (`tools/tests/test_event_markers_mcap.py`) passing.  
> **Deferred by Design:** `AudioAlertManager` (Sub-Topic 2.2 in Rev 1) moved to `[BACKLOG — DEFERRED]` in `Future_stuff.md`. Physical volume keys remain untouched.

---

## 1. Summary of Implemented Subsystems

### 1.1 4-Direction Swipe Event Bookmarking (`EVENT_MARKER` + Foxglove `/events`)
- **Gesture Model:** `EventBookmark.kt` defines `EventBookmarkType` (`EVENT`, `CUT_IN`, `BRAKE`, `FALSE_ALARM`, `BUMP`) and pure `EventBookmarkGestureResolver` (`36.dp` deadzone threshold; `< 36.dp` tap = plain `EVENT`; `≥ 36.dp` swipe = `↑ CUT_IN`, `↓ BRAKE`, `← FALSE_ALARM`, `→ BUMP`).
- **Tactile Button + Radial Guide:** `EventBookmarkButton.kt` renders both `LARGE_DOCK` and `COMPACT_BAR` variants with an isolated translucent 4-way radial guide `Popup` (triggered on `≥ 8.dp` drag or `180 ms` press-hold), toggleable haptic lock-in (`bookmarkHapticsEnabled`), and a 1.6 s global `EventBookmarkToastBanner` confirmation pill.
- **Exact Placements (zero plot/panel overlap):**
  1. **Fullscreen Camera:** Left-edge thumb dock (`Alignment.CenterStart`, `16.dp` inset) mirroring the right-edge focus dock in `FullscreenCameraPreview.kt`.
  2. **Fullscreen SBS:** Separate large tactile `EventBookmarkButton` positioned `10.dp` to the **left of `EXIT`** in the top floating control row in `FullscreenSbsPreview.kt`.
  3. **Regular Cockpit (SBS, Camera & all tabs):** Directly to the **left of the `IDLE` pill** in `LandscapeSessionControlBar` (`MainActivity.kt`) and `TopSessionHeader.kt`.
- **PC Foxglove MCAP Pipeline:** `tools/convert_session_to_mcap.py` parses `USER,EVENT_MARKER` rows from `session_timeline.csv`, writes structured JSON (`roadsense.EventMarker`) onto `/events`, and mirrors `WARNING`-level `foxglove.Log(name="BOOKMARK")` messages onto `/diagnostics/logs`.

### 1.2 Non-Blocking `STOP`, 10× Faster `SessionSyncAuditor` & Seamless Back-to-Back CANedge2 Sync
- **Zero-Freeze Stop Lifecycle:** `RadarViewModel.kt` transitions immediately (`< 16 ms`) to `SessionRecordingState.Finalizing("SAVING")`, executes `MediaRecorder.stop()`, IMU/GNSS/Radar flushes, and `SessionManager.closeSessionFiles()` on `Dispatchers.IO`, and returns the UI to `Finished` (`Record` / `New`) immediately while running the audit asynchronously.
- **Zero-Allocation Auditor:** `SessionSyncAuditor.kt` replaces per-line `String.split(",")` and boxed `MutableList<Long>` allocations with `indexOf(',')` range scanning, primitive `LongArrayBuffer` / `FloatArrayBuffer`, and a single-pass `session_timeline.csv` scan, executed on a dedicated `SessionAudit-Worker` thread in `SessionManager.kt`.
- **UI Audit Indicator & Diagnostic Toggles:** While auditing runs in the background, the status pill displays `AUDITING…` (without blocking starting a new drive). Toggleable `[LIFECYCLE_DIAG]` and `[CAN_TRANSITION]` logs are wired through `DebugToggles.kt` and `CanedgeIngestionManager.kt`.

### 1.3 Post-Drive Summary Card (`10 s` Auto-Dismiss)
- `PostDriveSummaryOverlay.kt` mirrors `LaunchBuildOverlay.kt` (`Color(0xFF131A24)` dark glass modal, zero-scroll 2-column landscape layout, `10 s` auto-dismiss countdown bar, tap-to-Hold & `Done`).
- Displays Sync Grade (`GOLD` / `SILVER` / `WARN`), Camera/Radar/IMU/GNSS counts & drops, cross-sensor Δt, any runtime warnings/errors (`USB_DETACHED`, thermal, storage, recorder errors), bookmark count, active tags, and CANedge2 background sync status (`"safe to start next drive"`). Auto-dismisses immediately if the user starts a new recording.

### 1.4 Clickable `IDLE` Zero-Scroll Landscape Preflight Modal + Sensor Warmup (`WarmingUp`)
- **Clickable `IDLE ⓘ` / `✓ SAVED` Pill:** Tapping the status pill beside `Record` opens `PreflightChecklistOverlay.kt` in a single-view, zero-scroll 2-column landscape dialog covering Radar, Camera, IMU, GNSS, CANedge2, Storage, Battery/Thermal, Calibration, active Run Tags, and an opt-in `"Prompt before REC"` checkbox (`AppPreferences.promptPreflightBeforeRec`).
- **Pre-Record Sensor Warmup (`≤ 2,000 ms`):** `CameraEngine.kt` adds `warmUpForRecording(targetFrames = 5, timeoutMs = 2000L)` (using the live preview surface or a lightweight draining `32×32` `ImageReader` when muted), coordinated with IMU startup in `RadarViewModel.startSessionRecording()` *before* creating the session and calling `MediaRecorder.start()` so frame #0 `VIDEO_START` alignment remains exact.

### 1.5 Structured Run Metadata (`SessionTags`), UI Polish & Dead-Code Cleanup
- `SessionInfo.kt` and `AppPreferences.kt` persist and serialize `sessionTags` (`vehicleId`, `dspFirmwareBuild`, `mountType`, `lightingCondition`, `notes`), `bookmarksCount`, and `systemHealth.warnings`.
- `SessionDeckCard.kt` adds the collapsible `"Run Metadata & Field Toggles"` card (chips + inputs + Bookmark Haptics & Lifecycle Debug switches) and fixes the legacy hardcoded `<= 16.7ms` label to use `complianceThresholdMs`.
- Deleted 3 verified dead legacy files: `AcquisitionPlaceholder.kt`, `RecordingPlaceholder.kt`, `AutoFitTextureView.kt`.

---

## 2. Verification Results

| Test Suite | Command | Result |
|---|---|---|
| **Android JVM Unit Tests** (incl. new `EventBookmarkTest`, large-log `SessionSyncAuditorTest`, updated `SessionInfoTest`) | `$env:JAVA_HOME = $jbr; ./gradlew testDebugUnitTest --no-daemon "-Dorg.gradle.java.home=$jbr"` | **133 / 133 PASSED** (`BUILD SUCCESSFUL in 20s`) |
| **Python MCAP `/events` Tests** | `conda run -n roadsense-mcap python -m unittest discover -s tools/tests -p "test_*.py" -v` | **2 / 2 PASSED** (`0.059s`) |

---

## 3. On-Device Field Test Fixes (`SM-M325F` — `session_20261125_150830` & `session_20261125_151205`)

Verified directly against `app_run_20261125_150801` and `app_run_20261125_151121` device logs:

1. **Stuck `AUDITING…` Pill on Very Short Logs (Fixed):**
   - **Root Cause (confirmed to the millisecond in `app_run_20261125_150801`):** For the 0.3 s session `session_20261125_150830`, `SessionAudit-Worker` finished auditing in `24 ms` (`15:08:31.735`) and cleared `_isSessionAuditing = false` *before* `stopSessionRecordingInternal()` finished CANedge/foreground teardown (`15:08:31.757`) and set `_isSessionAuditing = true`!
   - **Fix:** Added atomic `activeAuditToken` in `RadarViewModel.kt`, setting `_isSessionAuditing.value = true` *before* invoking `stopSessionRecordingInternal`, clearing on matching token (or abort), and guaranteeing `onAuditCompleted` invokes even on `IOException` inside `RadarSessionRecorder.kt`.
2. **Preflight Modal Bottom-Left Checkbox Labeling (Fixed):**
   - Replaced the size-constrained Material 3 `Checkbox` (whose 48 dp touch-target padding could squeeze/obscure its label in compact landscape) with a clean custom 18 dp checkbox indicator paired with explicit two-line text: **"Show pre-flight check before REC"** / *"When off, tapping Record starts immediately"*.
3. **Radar & Camera Minor-Drop Color Parity (Fixed):**
   - Aligned `PostDriveSummaryOverlay.kt` and `SessionDeckCard.kt`: Radar `< 5` drops (`1..4`) and Camera `1` startup pause now render in **Light Green (`#69F0AE`)** (instead of yellow/red), and minor `< 5` radar drops or salvaged frames no longer trigger the amber warning box.
4. **Live Metrics Bar Camera FPS Ramp-Up After Warmup (Fixed):**
   - Replaced the partial-second raw frame bucket in `RadarViewModel.kt` with shutter-timestamp span normalization (`(count - 1) / spanSec` for partial first-second bursts `0.06..0.85 s`), so `LiveMetricsBar` reports `30fps OK` immediately once shutter frames begin streaming instead of lagging `9 → 19 → 30 fps`, and increased cold warmup settle to `8` frames (`~270 ms` ISP 3A settle).
5. **Preflight Landscape Fit & Dynamic Cockpit Status Pill (Fixed):**
   - Compacted `PreflightChecklistOverlay.kt` into a symmetrical 5×2 landscape grid (~110 dp shorter) with `.verticalScroll(...)` safety fallback and stacked portrait footer, and applied the same landscape safety net to `PostDriveSummaryOverlay.kt`.
   - Upgraded the cockpit status pill (`TopSessionHeader.kt` & `MainActivity.kt`) to dynamically display `WARMING…`, `● REC`, `SAVING…`, `AUDITING…`, background CANedge2 activity (`CAN SYNC %`, `CAN WAIT…`, `CAN CAL…`, `CAN SCAN`), a transient 4 s `✓ SAVED` flash, and automatic return to `IDLE ⓘ`.
6. **Edge-Case Concurrency & Resource Hardening (Fixed):**
   - Guaranteed `MediaRecorder` `reset()` / `release()` and camera recovery in a `finally` block inside `CameraEngine.stopVideoRecording()` (preventing codec leaks on `< 1s` stops).
   - Added `sessionRecorder.awaitFlush(1500L)` in `RadarViewModel.beginSessionRecordingInternal()` to prevent back-to-back recording stream wipeout races.
   - Guarded warm-start `Record` double-taps synchronously via `setWarmingUp("Starting…")`, and prevented `CameraEngine.closeCamera()` from calling `.release()` on `SurfaceHolder`-owned persistent `SurfaceView` surfaces.

---

## 4. Recommended On-Device Re-Verification Checklist (`SM-M325F`)
1. **Preflight & Warmup:** Tap `IDLE ⓘ` in landscape → confirm all 5 sensors + storage/power/calib/tags fit cleanly without scrolling. With Camera preview muted, tap `Record` → confirm brief `WARMING…` state (`< 2 s`) and immediate 30 FPS lock on start.
2. **4-Way Swipe Bookmarks:** During recording, test tap (`EVENT`) and 4-way swipes (`↑ CUT-IN`, `↓ BRAKE`, `← FALSE`, `→ BUMP`) from:
   - Regular cockpit (`MARK` left of `IDLE`),
   - Fullscreen Camera (left-edge thumb dock),
   - Fullscreen SBS (large button left of `EXIT`).
   Verify toast pill, haptics toggle in Storage tab, and `/events` + `/diagnostics/logs` in Foxglove after MCAP conversion.
3. **Non-Blocking Stop & Back-to-Back CANedge2 Sync:** Enable `"Lifecycle & CAN Transition Debug Logs"` in Storage tab → record → tap `Stop` → verify instant `SAVING…` → `New` transition, `AUDITING…` pill, and 10 s `PostDriveSummaryOverlay` auto-dismiss. Start a second drive immediately while CANedge2 syncs and inspect `[LIFECYCLE_DIAG]` and `[CAN_TRANSITION]` in `app_system.log`.
