# Phase 1 Walkthrough — Android CANedge2 Sync Engine & Live Metrics HUD Rebuild

## 1. Summary of Implemented Changes

Phase 1 ("Track A") replaces the ad-hoc, single-folder CANedge2 pull logic with an autonomous, idempotent background sync engine and adds a real-time `CAN` status pill in the top cockpit metrics bar.

### 1.1 MDF4 Binary Header Inspection & Persistent Sync State
- **MDF4 Header Inspector** (`app/src/main/java/com/bajajauto/roadsense/canedge/ingestion/Mdf4HeaderInspector.kt`):
  - Parses the 64-byte MDF4 `IDBLOCK` (`"MDF     "` / `"UnFinMF "`) and `HDBLOCK` (`"##HD"`), reads little-endian `linkCount`, and extracts `hd_start_time_ns` (nanoseconds since Unix epoch) directly from file prefixes (`<= 256` bytes) without reading full recordings or relying on `File.lastModified()`.
- **Persistent Manifest & Pool Index** (`app/src/main/java/com/bajajauto/roadsense/canedge/ingestion/CanedgeSyncManifest.kt`):
  - `CanSyncManifest` (`sessions/can_sync_manifest.json`) tracks every drive session across `PENDING`, `WAITING_FINAL_CHUNK`, `PARTIAL`, and `COMPLETE` states with wall-clock bounds, measured `clockOffsetMs`, matched remote paths, and staged filenames.
  - `PoolIndex` (`sessions/canedge_pool/pool_index.json`) caches `remoteWrittenEpochSec`, `headerStartTimeMs`, `remotePath`, and byte size for pooled `.MF4` files.
- **Persistent Clock-Skew Preferences** (`app/src/main/java/com/bajajauto/roadsense/storage/AppPreferences.kt`):
  - Stores `canedgeLastClockOffsetMs` and `canedgeLastClockMeasuredAtMs`.

### 1.2 Pure-Kotlin Sync Planner (`CanedgeSyncPlanner.kt`)
- **Cross-Directory Download Deduplication (`filterFilesNeedingDownload`)**: Checks `(localName, sizeBytes)` across **both** `sessions/canedge_pool/` and all `sessions/<session>/can/` folders, eliminating the 51 MB ping-pong re-download loop when staged files are pruned from `canedge_pool/`.
- **Live Rollover Clock-Offset Measurement (`measureClockOffsetFromLiveTransition`)**: Estimates `clockOffsetMs = phoneWallMs - canedgeEpochMs` strictly when a chunk rollover or new folder creation is observed between two consecutive polls (`1..25 s`), preventing old log ages from being misinterpreted as RTC skew.
- **Skew-Compensated Matching, Gap Fill & Tail Waiting (`planSessionChunks`)**: Maps each remote chunk to `[chunkStartWallMs, chunkEndWallMs]` using `clockOffsetMs`, performs contiguous sequence-gap fill (`minSeq..maxSeq`) per folder, and sets `waitingForFinalChunk` while waiting for the CANedge2's active 60-second chunk to close after recording stops.
- **Offline Pool Rescue & Safe Pruning (`planPoolRescueForSession`, `selectPoolFilesToPrune`)**: Replaces `File.lastModified()` with `PoolIndex` + MDF4 headers and never prunes chunks referenced by non-`COMPLETE` sessions.

### 1.3 Network Discovery, Repository Stability & Continuous Watchdog
- **Lightweight Watchdog Discovery** (`app/src/main/java/com/bajajauto/roadsense/canedge/discovery/CanedgeDiscovery.kt`):
  - Added `probeKnownHost()` (cached IP and `<deviceId>` hostname only, avoiding 254-IP sweeps) and `markConnectionLost()` for clean reconnects.
- **Skew-Aware File Stability & 10-Folder Scope** (`app/src/main/java/com/bajajauto/roadsense/canedge/repository/CanedgeRepository.kt`):
  - Expanded default `listRecentSessionMf4Files` scope to `maxFolders = 10`.
  - Updated `isFileStable()` to compensate for `estimatedClockOffsetMs`, enforce a `75_000 ms` age threshold on the newest chunk in the newest folder, and require two identical non-zero size checks separated by `>= 3_000 ms` (`SIZE_STABILITY_MIN_INTERVAL_MS`).
- **Continuous 1s Watchdog Ingestion Manager** (`app/src/main/java/com/bajajauto/roadsense/canedge/ingestion/CanedgeIngestionManager.kt`):
  - Runs a 1-second background loop (`startWatchdog()`):
    - Probes known host every `5 s` while offline (unless user manually tapped Disconnect).
    - Pulls closed unsynced `.MF4` files when idle on Wi-Fi every `12 s`.
    - **Immediately cancels and pauses transfers** (`PAUSED_RECORDING`) when `onSessionStarted()` runs.
    - **Automatically resumes** after `onSessionStopped(stopped)` (`1.5 s` settle) using a `5 s` poll interval while `WAITING_FINAL_CHUNK`, then stages chunks into `session_<id>/can/` and updates `session_metadata.json` (`SessionInfo.kt`) and `session_timeline.csv` idempotently.
  - Cleans orphaned `*.MF4.download` partial transfers on init and before/after transfers, and adds `folderName` + `localName` helpers + `CanedgeHudPhase` to `CanedgeFile.kt`.

### 1.4 Stop-Order Fix & Cockpit HUD Indicator
- **ViewModel Lifecycle & Stop-Order Fix** (`app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt`):
  - Starts the 1 s watchdog at ViewModel init and shuts it down in `onCleared()`.
  - Fixed `stopSessionRecording()` to call `canedgeIngestionManager.onSessionStopped(stopped)` **after** `val stopped = sessionRecorder.stopSession()` so `stopTimeWallMs` is always populated.
  - Populates `canHudPhase` and compact `canHudLabel` (`OFF`, `<n>P`, `SCAN`, `OK`, `<pct>%`, `PAUSE`, `WAIT`, `ERR`) into `LiveMetricsSnapshot`.
- **Interactive `CAN` Pill in HUD** (`app/src/main/java/com/bajajauto/roadsense/ui/components/LiveMetricsBar.kt` & `MainActivity.kt`):
  - Draws a color-coded `CAN` status pill on both Landscape (next to `SYNC`) and Portrait (Row 2 left of `CPU`) with tap hit-bounds that switch directly to `CockpitTab.CANEDGE`.
- **Dashboard Action & Telemetry Updates** (`app/src/main/java/com/bajajauto/roadsense/ui/screens/CanedgeDashboardCard.kt`):
  - Surfaces `Watchdog State`, `Pending Reconciliation`, `Measured RTC Offset`, and `"10 Most Recent Folders"`, disables manual sync with `"Paused (Rec)"` during active recording, and applies single-line overflow guards (`maxLines = 1`, `softWrap = false`).

---

## 2. Verification Results (Post-Final-Edit)

Executed with Android Studio bundled JBR (`JAVA_HOME` + `org.gradle.java.home` set to `C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr`):

```powershell
./gradlew testDebugUnitTest assembleDebug
```

- **Build & Packaging (`:app:assembleDebug`)**: `BUILD SUCCESSFUL in 33s` (`44 actionable tasks: 15 executed, 29 up-to-date`).
- **Unit Test Suite (`:app:testDebugUnitTest`)**: All **24 JUnit test suites / 114 tests passed**, with **0 failures, 0 errors, 0 skipped**:
  - `Mdf4HeaderInspectorTest`: 3 passed, 0 failed, 0 skipped (synthetic `UnFinMF ` / `MDF     ` parsing, disk file inspection, corrupt/truncated header rejection).
  - `CanedgeSyncPlannerTest`: 9 passed, 0 failed, 0 skipped (cross-pool+session deduplication, live-rollover clock offset isolation, skew-compensated matching + sequence gap fill, `WAITING_FINAL_CHUNK` detection, `benchTestWithNoCanTrafficExitsWaitingTailQuickly`, offline pool rescue isolation, safe pool pruning, `idlePreSyncAndPruneMaintainAntiThrashInvariant`, manifest/index JSON round-trip).
  - `CanedgeRepositoryTest`: 3 passed, 0 failed, 0 skipped (including new `testClockSkewCompensationPreventsPrematureActiveDownload`).
  - Remaining 21 suites (`Radar*`, `SpatialProjectionEngineTest`, `Imu*`, `Camera*`, `Gnss*`, `Session*`, `AppLoggerAsyncTest`): all passed (`0` failures, `0` skipped).
