# Walkthrough: Phase 2 — IMU-Assisted Camera Alignment & Radar Yaw Calibration

## Overview
Successfully implemented and verified **Phase 2: IMU-Assisted Camera Alignment & Radar Yaw Calibration** across the RoadSense Android application.

This release operationalizes real-time smartphone gravitational telemetry (`Sensor.TYPE_GAME_ROTATION_VECTOR`) to eliminate manual pitch guesswork during camera setup, delivers a visual digital spirit level, provides an interactive 25 Hz live horizon follow setup mode, introduces an ADAS windshield angle quality evaluator, and strictly isolates the calibration studio's yaw adjustment to **physical mechanical mounting offset of the front TI mmWave radar sensor**.

---

## Changes Implemented

### 1. Model & Domain Layer (`CalibrationParameters.kt`)
* **Physical Semantic Aliases:**
  - `cameraPitchDeg: Float get() = effectivePitchDeg` (Camera optical tilt relative to level ground).
  - `radarYawOffsetDeg: Float get() = effectiveYawDeg` (Front bumper bracket squint/offset).
  - `isClampLevel: Boolean get() = kotlin.math.abs(rollDeg) <= 0.8f` (Threshold for smartphone levelness).
* **ADAS Mounting Quality Evaluator:**
  - `enum class PitchQuality`: Categorizes optical pitch into `OPTIMAL` ($3^\circ$–$12^\circ$), `TOO_SHALLOW` ($<3^\circ$), `TOO_STEEP` ($>12^\circ$), and `FACING_UP` ($<0^\circ$).
* **Storage Schema Preservation:**
  - Retained `pitchDeg` and `yawDeg` in JSON serialization and deserialization, guaranteeing 100% backward compatibility for session recording, MCAP translation, and Python validation scripts.

### 2. ViewModel Layer (`RadarViewModel.kt`)
* **Reactive State Management:**
  - Added `_isLiveImuHorizonEnabled: MutableStateFlow<Boolean>` and public `isLiveImuHorizonEnabled: StateFlow<Boolean>`.
* **Sync & Lock Actions:**
  - `syncPitchFromImu()`: Instantly copies live IMU pitch ($\arcsin(R_8)$) and roll ($\arcsin(R_7)$) into the calibration baseline with nudges reset to $0^\circ$.
  - `lockLiveImuPitch()`: Seals the dynamic angle into the calibration baseline and terminates live follow mode.
  - `toggleLiveImuHorizon()` / `setLiveImuHorizonEnabled()`: Toggles 25 Hz interactive tracking.
  - `resetRadarYawToZero()`: Dedicated method to reset strictly the radar bumper mounting yaw offset to $0.0^\circ$ while leaving camera pitch untouched.

### 3. Fullscreen Calibration Studio (`FullscreenCalibrationStudio.kt`)
* **Reactive Calibration Synthesis:**
  - When `isLiveImuHorizonEnabled` is active, dynamically synthesizes `activeDisplayParams` at 25 Hz from `imuTelemetry.pitchDeg` and `imuTelemetry.rollDeg`.
* **Top Assistant Deck (Feature B, C, D):**
  - **`DigitalClampLevelPill` (Feature B):** Displays glowing green `● LEVEL %+.1f°` when within $\pm 0.8^\circ$, amber `◄ TILT L` / `TILT R ►` when tilted, and red `▲ HIGH ROLL` when $> 3.0^\circ$.
  - **`PitchQualityBadge` (Feature D):** Displays `OPTIMAL PITCH` (Green), `TOO SHALLOW` (Amber), `TOO STEEP` (Amber), or `FACING_UP` (Red).
  - **`Live IMU` FilterChip (Feature C):** Toggles real-time dynamic viewfinder tracking.
  - **`Sync Pitch` Button (Feature A):** 1-tap pitch sync from IMU directly from top bar.
* **Bottom Floating HUD Deck (Feature A & Dedicated Radar Yaw):**
  - Camera pitch cluster (`θ_cam: %+.1f°` in Sky Blue) with `[SYNC IMU]` or pulsing `[LOCK PITCH]` button.
  - Radar yaw offset cluster (`ψ_rad: %+.1f°` in Light Green) with subtitle `"RADAR BUMPER YAW"` and `[0°]` reset button.
  - D-Pad nudges labeled with sky blue `▲▼` (Camera Pitch) and light green `◄►` (Radar Yaw).
  - Extrinsics Dialog with updated labels for Windshield Clamp Roll ($\phi$) and Radar Bumper Squint ($\psi_{\text{radar}}$).

### 4. Camera Dashboard Card (`CameraCalibrationCard.kt` & `CameraDashboardCard.kt`)
* Updated `CameraCalibrationCard` to take `imuTelemetry` and `onSyncImuPitch`.
* Added primary `[Sync IMU]` button directly to the card on the Camera tab, enabling instant auto-leveling without entering Fullscreen Studio.
* Integrated `DigitalClampLevelPill` and `PitchQualityBadge` inline within the card's telemetry grid.

---

### 5. Coordinate System & Sign Convention Fixes (`SpatialProjectionEngine.kt`)
* **Optical Pitch Elevation Convention:**
  - In Android / OpenCV screen coordinates, $v = 0$ is top of screen (sky) and $v = H$ is bottom (road).
  - Positive pitch ($\theta > 0$, e.g. $+10^\circ$) denotes phone camera pointed DOWN towards the road.
  - When the phone tilts downward, the true road horizon and physical features shift UPWARD on screen ($v < c_y$).
  - Updated horizon calculation: $v_{\text{center}} = c_y - f_y \tan(\theta)$.
  - Updated $R_x(\theta)$ extrinsic rotation:
    $$y_2 = y_1 \cos P - z_1 \sin P, \quad z_2 = y_1 \sin P + z_1 \cos P$$
  - Updated interactive touch inverse solver: $\theta = \text{targetPitch} - \alpha_Y$.
* **Windshield Mount Roll Slope Convention:**
  - Clockwise roll ($+R$, right edge of phone tilts downward) shifts the world horizon on the right side UP ($v_{\text{right}} < v_{\text{center}}$) and the left side DOWN ($v_{\text{left}} > v_{\text{center}}$).
  - Updated horizon endpoints: $v_{\text{left}} = v_{\text{center}} + \frac{W}{2} \tan(R)$ and $v_{\text{right}} = v_{\text{center}} - \frac{W}{2} \tan(R)$, matching 3D projection $y_{\text{Cam}} = -x_2 \sin R + y_2 \cos R$.

---

## Verification & Testing Results

### Automated Unit Tests
Command executed:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
```

**Results:**
* `BUILD SUCCESSFUL in 3s` (24 actionable tasks: 5 executed, 19 up-to-date).
* 100% of unit tests passed, including:
  1. `testPhase2SemanticAliasesAndClampLeveling`: Verified `cameraPitchDeg` and `radarYawOffsetDeg` aliases, $\pm 0.8^\circ$ clamp level thresholds, and ADAS `PitchQuality` categorization across the spectrum.
  2. `testPhase2LiveImuSynthesisAndRadarYawIsolation`: Verified that live IMU angle synthesis accurately tracks phone orientation while radar yaw remains strictly isolated, and verified that bumper yaw adjustments do not disturb camera horizon line geometry.
  3. `testDownwardPitchMovesHorizonLineUpward`: Verified that $+10^\circ$ pitch moves horizon line upward on screen ($v < c_y$) and $-10^\circ$ pitch moves it downward ($v > c_y$).
  4. `testCalculateHorizonLineRollSlopeMatches3DProjection`: Verified that horizon line roll slope precisely matches 3D projected far-field radar points under non-zero roll angles.
  5. `testJsonSerialization`: Verified 100% schema integrity and backward compatibility.

### APK Compilation & Target Installation
Command executed:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug
adb -s 32001991d42c86d9 install -r app/build/outputs/apk/debug/app-debug.apk
```
**Results:**
* `BUILD SUCCESSFUL in 1s` (Debug APK generated at `app/build/outputs/apk/debug/app-debug.apk`).
* `Performing Streamed Install` -> `Success` on device `32001991d42c86d9`.
