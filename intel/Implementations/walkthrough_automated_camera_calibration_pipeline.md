# Walkthrough: Automated Camera Calibration Pipeline

## Summary of Changes

We have implemented an automated, end-to-end camera calibration pipeline that captures active camera mounting angles on the mobile device, syncs them to PC storage, and accurately propagates them into MCAP transforms and attachments.

---

## What Was Implemented

### 1. In-App Session Snapshotting & Live Calibration Updates
- **[SessionManager.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/SessionManager.kt):**
  - Added `CALIBRATION_FILE_NAME = "radar_camera_calib.json"`.
  - In `createSession()`, automatically copies `/files/calibration/radar_camera_calib.json` directly into the newly created session folder (`sessions/session_YYYYMMDD_HHMMSS/radar_camera_calib.json`).
  - Implemented `saveSessionCalibration(sessionInfo, params)` to serialize active `CalibrationParameters` to JSON.
- **[RadarViewModel.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt):**
  - In `startSessionRecording()`: Automatically writes the active calibration snapshot to the session folder.
  - In `saveBaselineCalibration(...)`: If a session is actively recording when the user recalibrates the mount, it updates the session's `radar_camera_calib.json` with the new consolidated angles.
  - In `stopSessionRecording()`: Ensures the latest calibration state is saved before finalizing the session.

---

### 2. ADB Device Sync & Global Backup Backfilling
- **[tools/sync_and_process_sessions.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/sync_and_process_sessions.py):**
  - Added `DEVICE_CALIB_DIR = "/sdcard/Android/data/com.bajajauto.roadsense/files/calibration/"`.
  - In `sync_sessions_from_device()`: Automatically pulls the device's global calibration directory to `logs/calibration/` on PC.
  - Loops through all synced sessions: If any session lacks `radar_camera_calib.json`, it backfills a copy from `logs/calibration/radar_camera_calib.json` as the global backup.

---

### 3. MCAP Conversion 4-Tier Resolution Hierarchy
- **[tools/convert_session_to_mcap.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py):**
  - Implemented `resolve_session_calibration(session_dir)` with a 4-tier resolution hierarchy:
    1. **Priority 1 (Session Snapshot):** Reads `session_dir/radar_camera_calib.json`.
    2. **Priority 2 (Flight-Recorder Log Extraction):** Scans `session_dir/session_debug.log` using regex to extract the latest `Saved baseline calibration: Pitch=X°, Yaw=Y°` or `Restored saved baseline calibration: Pitch=X°, Yaw=Y°`. Automatically synthesizes `session_dir/radar_camera_calib.json` for downstream tools.
    3. **Priority 3 (Device Global Backup):** Reads `logs/calibration/radar_camera_calib.json`.
    4. **Priority 4 (Hardcoded Vehicular Fallback):** Safe default geometry with an explicit console warning.
  - Propagates resolved pitch, yaw, and roll angles into the `/tf` transform (`base_link -> camera_optical`).
  - Automatically embeds `radar_camera_calib.json` as a native MCAP attachment.

---

## Verification Results

### 1. Android Build & Unit Tests
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
- **Unit Tests:** `BUILD SUCCESSFUL in 12s` (All tests passed, exit code 0).
- **APK Package:** Built fresh APK at `app/build/outputs/apk/debug/app-debug.apk` in 4s.

### 2. Priority 1 & 2 Python Verification
- **Session `session_20261006_095609` (Active Realigned Session):**
  ```
  [+] [Priority 1: Session Snapshot] Loaded calibration from radar_camera_calib.json:
      -> Pitch=4.143453598022461°, Yaw=-1.8958022594451904°, Roll=0.0°, Profile='Default Mount'
  ```
  Successfully loaded exact realigned angles `Pitch=4.143°`, `Yaw=-1.896°`!
- **Session `session_20260930_144431` (Historical Session without JSON):**
  ```
  [+] [Priority 2: Flight Recorder Log] Recovered calibration from session_debug.log:
      -> Pitch=8.020953°, Yaw=-1.0337551° (Synthesized radar_camera_calib.json)
  ```
  Successfully extracted pitch & yaw from flight recorder logs and synthesized the JSON!
- **Subsequent Run on `session_20260930_144431`:**
  ```
  [+] [Priority 1: Session Snapshot] Loaded calibration from radar_camera_calib.json:
      -> Pitch=8.020953°, Yaw=-1.0337551°, Roll=0.0°, Profile='Flight-Recorder Recovered Profile'
  ```
  Immediately promoted to Priority 1 snapshot on subsequent runs.
