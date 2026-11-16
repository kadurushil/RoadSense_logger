# Implementation Plan: Phase 2 — IMU-Assisted Camera Alignment & Radar Yaw Calibration

## Goal Description
In Phase 1, we developed a 60 FPS 3D smartphone orientation visualizer powered by `Sensor.TYPE_GAME_ROTATION_VECTOR`, resolving gimbal lock on flat surfaces and deriving exact closed-form optical pitch ($\arcsin(R_8)$) and clamp roll ($\arcsin(R_7)$) in vehicle Reverse Landscape mode.

In **Phase 2**, we operationalize this live gravitational telemetry to completely eliminate manual pitch guesswork during camera setup, provide a real-time digital spirit level, offer an interactive live horizon follow mode, evaluate ADAS windshield angle quality, and strictly dedicate the calibration studio's yaw adjustment to the **physical mechanical mounting offset of the front TI mmWave radar sensor**.

---

## User Review Required

> [!IMPORTANT]
> **All Four Features Included (A, B, C, D):**
> Per user direction, all four proposed features are included in this unified implementation plan:
> 1. **Feature A (One-Tap Sync Pitch from IMU):** Instantly captures the live phone IMU pitch into `pitchDeg` with one tap.
> 2. **Feature B (Digital Clamp Leveling Spirit Level):** Visual bubble/needle guiding the user to level the clamp ($\text{Roll} \to 0.0^\circ$).
> 3. **Feature C (Live Horizon Follow Setup Mode):** Dynamic setup mode where the artificial horizon line in the viewfinder actively tracks the phone tilt in real time at 25 Hz during ball-joint adjustment.
> 4. **Feature D (Pitch Quality Pill):** Automatic guidance badge alerting if the camera angle is optimal ($3^\circ$–$12^\circ$), too shallow ($<3^\circ$), or too steep ($>12^\circ$).

> [!IMPORTANT]
> **Strict Physical Separation of Degrees of Freedom:**
> * **Camera Angle (Pitch $\theta_{\text{cam}}$ & Roll $\phi_{\text{cam}}$):** Driven exclusively by phone mount orientation relative to level ground. Measured directly by the phone IMU.
> * **Radar Angle (Yaw Offset $\psi_{\text{radar}}$):** The front bumper mmWave radar has no gyro or compass. The calibration page's yaw controls are dedicated **strictly to compensating for physical radar bracket mounting angle** relative to vehicle forward heading (e.g. bracket squint, bumper curvature).
> * The camera yaw relative to the car is fixed at $0.0^\circ$ (pointing forward along windshield centerline).

> [!NOTE]
> **Storage & JSON Backward Compatibility:**
> The underlying storage keys in `radar_camera_calib.json` remain `pitchDeg` and `yawDeg` so that downstream Python analysis pipelines (`convert_session_to_mcap.py`, `camera_extrinsics.py`) and ROS/Foxglove tools continue to parse session records without schema changes.

---

## 1. UI & UX Architecture & User Interface Design

### 1.1 Fullscreen Calibration Studio: Landscape View (Vehicle Mount Orientation)

When the phone is docked in the windshield mount in **Reverse Landscape** (`ROTATION_270`), the edge-to-edge camera viewfinder displays two floating control decks (Top Assistant Deck and Bottom HUD Deck).

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [✕] CALIBRATION STUDIO  │  [● LEVEL -0.2°]  [OPTIMAL PITCH]  [☑ Live IMU]  │  [Horizon] [Grid] [SYNC PITCH] [SAVE]│
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                                  │
│                                       Camera Viewfinder (Live Video)                                             │
│                                                                                                                  │
│   - - - - - - - - - - - - - - - - - -  ARTIFICIAL HORIZON (θ: +8.4°, ϕ: 0.0°)  - - - - - - - - - - - - - -       │
│                                                     │                                                            │
│                                              /      |      \                                                     │
│                                             /  [TARGET CAR] \                                                    │
│                                            /     [10.0m]     \                                                   │
│                                           /                   \                                                  │
│                                          /                     \                                                 │
│                                         /                       \                                                │
│                                        /                         \                                               │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│    [ θ_cam: +8.4° ] [SYNC IMU]  │  [ ψ_rad: -1.0° ] [0°]  │  [▲] [▼] [◄] [►]  │  [⚙ Settings]                    │
│    Camera Windshield Pitch      │  RADAR BUMPER YAW       │  Nudge D-Pad     │  Extrinsics Setup                 │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

#### Detailed Element Specifications (Landscape):

1. **Top Control Bar (Surface container, alpha = 0.65, pill shape):**
   - **Left Cluster:**
     - Exit Button (`✕` icon): dismisses studio back to Camera tab.
     - Title Text: `"CALIBRATION STUDIO"` (12sp bold, white).
   - **Center Cluster (IMU Assistant Deck):**
     - **Digital Clamp Level Pill (Feature B):**
       - If $|\text{Roll}| \le 0.8^\circ$: `● LEVEL -0.2°` in glowing Emerald Green (`#4CAF50`).
       - If tilted Left ($\text{Roll} < -0.8^\circ$): `◄ TILT L 2.1°` in Amber (`#FFA726`).
       - If tilted Right ($\text{Roll} > +0.8^\circ$): `TILT R 1.8° ►` in Amber (`#FFA726`).
       - If severely tilted ($|\text{Roll}| > 3.0^\circ$): `▲ HIGH ROLL 4.2°` in Red (`#EF5350`).
     - **Pitch Quality Badge (Feature D):**
       - If $\theta \in [3.0^\circ, 12.0^\circ]$: `OPTIMAL PITCH` (Emerald Green container `#2E7D32`, 10sp bold).
       - If $\theta < 3.0^\circ$: `TOO SHALLOW (<3°)` (Amber container `#E65100`, 10sp bold).
       - If $\theta > 12.0^\circ$: `TOO STEEP (>12°)` (Amber container `#E65100`, 10sp bold).
       - If $\theta < 0.0^\circ$: `FACING UP (<0°)` (Red container `#C62828`, 10sp bold).
     - **Live IMU Follow Toggle Chip (Feature C):**
       - FilterChip `Live IMU` with gyro icon.
       - When active: Glowing Cyan background (`#00E5FF`), label `"Live IMU (Active)"`.
   - **Right Cluster:**
     - Overlay FilterChips: `Horizon` (toggles cyan horizon), `Grid` (toggles ground tracks/rungs), `Radar Arcs` (toggles range arcs).
     - `[SYNC PITCH]` (Feature A): OutlinedButton in Amber (`#FFA726`) with gyro icon. Directly copies live IMU pitch into baseline.
     - `[Save Baseline]` / `[Saved]`: Green button (`#4CAF50`) persisting config to `radar_camera_calib.json`.

2. **Bottom Floating HUD Deck (Ultra-compact, single row):**
   - **Camera Pitch Cluster (Left):**
     - Badge: `θ_cam: %+.1f°` in Sky Blue (`#64B5F6`). Tapping resets pitch to nominal default ($7.0^\circ$).
     - Action Button:
       - If `Live IMU` is **OFF**: `[SYNC IMU]` button (Feature A) with small flash/gyro icon.
       - If `Live IMU` is **ON**: Flashing pulsing button `[LOCK PITCH]` (Feature C) in bright Cyan/Gold to seal the angle.
   - **Radar Bumper Yaw Offset Cluster (Center-Left):**
     - Badge: `ψ_rad: %+.1f°` in Light Green (`#81C784`).
     - Subtitle: `"RADAR BUMPER YAW"` in 8sp monospace gray.
     - Action Button: `[0°]` reset button (resets strictly the radar yaw offset to $0.0^\circ$).
   - **Nudge D-Pad (Center-Right):**
     - Vertical (`▲` `▼`): Micro-nudges **Camera Pitch** by $\pm 0.1^\circ$.
     - Horizontal (`◄` `►`): Micro-nudges **Radar Bumper Yaw** by $\pm 0.1^\circ$.
   - **Extrinsics Settings Button (`⚙`):** Opens full 6-DOF physical vehicle mounting dialog.

---

### 1.2 Fullscreen Calibration Studio: Portrait View (Reflow & Responsive Layout)

When testing on a bench or holding the phone vertically:
- The top bar reflows into two compact rows or a horizontally scrollable chip row to prevent any text clipping or overlapping.
- The Level Pill displays a mini bubble: `[● 0.1°]`.
- The Pitch Quality Pill displays: `[OPT 7.2°]`.
- The bottom HUD wraps into two ergonomic rows:
  - Row 1: Pitch & Yaw readouts + quick sync/reset actions.
  - Row 2: Nudge D-pad + Extrinsics settings button.

---

### 1.3 Main Camera Tab: Dashboard Card (`CameraCalibrationCard.kt`)

Even without opening the fullscreen studio, the engineer can perform a complete 1-tap alignment right from the Camera dashboard tab:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ ⚙ RADAR-CAMERA CALIBRATION                              [IRVM Bonnet Mount] │
├─────────────────────────────────────────────────────────────────────────────┤
│  θ_cam: +8.4° (IMU Synced)   │   ψ_rad: -1.0° (Bumper Offset)                │
│  ϕ_roll: 0.2° [● LEVEL]      │   Quality: [OPTIMAL PITCH]                   │
│  Setback: 0.30m              │   Height Diff: 0.50m   │   Target: 10.0m     │
├─────────────────────────────────────────────────────────────────────────────┤
│  [  ⚡ SYNC PITCH FROM IMU  ]   [  ⛶ CALIBRATE VIEWFINDER (FULLSCREEN)  ]   │
├─────────────────────────────────────────────────────────────────────────────┤
│  [☑ Radar Reticle Overlay]                           [↺ Reset Defaults]     │
└─────────────────────────────────────────────────────────────────────────────┘
```

- **`[⚡ SYNC PITCH FROM IMU]` Button:** Primary outlined button with high visibility. Tapping reads the current phone tilt immediately and saves it into memory.
- **Roll Status Pill:** Shown inline next to the roll value (`[● LEVEL]` in green or `[◄ TILT L]` in amber).
- **Pitch Quality Badge:** Displayed inline next to pitch.

---

## 2. Interactive User Workflows

### Workflow 1: 10-Second Mount & Auto-Sync (Features A + B + D)
1. **Mount Phone:** Snap the phone into the vehicle windshield clamp in reverse landscape.
2. **Level Clamp (Feature B):** Look at the `DigitalClampLevelPill`. If it says `◄ TILT L 2.3°`, rotate the clamp slightly clockwise until it turns Emerald Green: `● LEVEL 0.1°`.
3. **Verify Pitch Quality (Feature D):** Check the badge. It says `OPTIMAL PITCH` (e.g. $+7.5^\circ$).
4. **One-Tap Sync (Feature A):** Tap `[SYNC PITCH]`. The artificial horizon instantly snaps to the true horizon.
5. **Save:** Tap `[Save Baseline]`. Calibration is done in under 10 seconds!

### Workflow 2: Interactive Ball-Joint Adjustment (Features B + C + D)
1. **Loosen Mount:** Loosen the ball-joint lock on the phone mount.
2. **Activate Live Follow (Feature C):** Tap the `Live IMU` chip in the top bar.
3. **Aim Viewfinder:** Look at the live camera video through the windshield while tilting the phone up and down. The cyan horizon line sweeps smoothly across the video at 25 Hz.
4. **Align Horizon:** Align the cyan horizon line with the physical horizon / road vanishing point outside.
5. **Tighten & Lock:** Tighten the ball-joint screw. The bottom button pulses `[LOCK PITCH]`. Tap `[LOCK PITCH]` to lock the angle.

### Workflow 3: Mechanical Radar Bumper Yaw Alignment ($\psi_{\text{radar}}$)
1. **Park Behind Target Vehicle:** Park 10 meters behind a target car aligned with your lane centerline.
2. **Inspect Radar Detections:** Observe the radar point cloud / vehicle reticle overlay in the viewfinder.
3. **Evaluate Bumper Squint:** If the radar reticle is sitting 20 cm to the left of the actual car's license plate, the radar mounting bracket has a squint angle.
4. **Nudge Yaw Offset:** Tap `►` (nudge yaw right) until the radar reticle centers perfectly on the physical car's bumper.
5. **Notice Separation:** Camera pitch and roll remain untouched; only $\psi_{\text{rad}}$ adjusts.
6. **Save:** Tap `[Save Baseline]`. The bracket offset is saved to `radar_camera_calib.json`.

---

## 3. Architecture & State Management

```mermaid
flowchart TD
    subgraph HardwareSensors [Hardware Sensor Layer]
        IMU[LSM6DSL IMU<br/>Game Rotation Vector]
        Radar[TI mmWave IWR6843<br/>Raw Point Cloud]
        Camera[Camera2 Engine<br/>Windshield Viewfinder]
    end

    subgraph ImuSubsystem [IMU Processing Engine]
        ImuMgr[ImuManager]
        RotMatrix[Rotation Matrix R<br/>Closed-Form Reverse Landscape]
        ImuTelemetry[ImuTelemetryState<br/>pitchDeg, rollDeg, yawDeg]
        IMU --> ImuMgr --> RotMatrix --> ImuTelemetry
    end

    subgraph ViewModelLayer [RadarViewModel State Management]
        LiveToggle[isLiveImuHorizonEnabled: Boolean]
        CalibParams[calibrationParams: CalibrationParameters]
        SavedBaseline[savedBaselineParams: CalibrationParameters]
        
        SyncMethod[syncPitchFromImu()]
        LockMethod[lockLiveImuPitch()]
        NudgeYaw[nudgeYaw() / resetYawToZero()]
        
        ImuTelemetry -->|Stream 25 Hz| ViewModelLayer
        LiveToggle -->|If True| DynamicSynthesis[Synthesize Active Calibration<br/>Pitch = imu.pitchDeg<br/>Roll = imu.rollDeg]
        LiveToggle -->|If False| StaticCalib[Static Calibration<br/>Pitch = params.effectivePitchDeg]
    end

    subgraph UILayer [User Interface Components]
        Studio[FullscreenCalibrationStudio]
        LevelPill[DigitalClampLevelPill - Feature B]
        PitchBadge[PitchQualityBadge - Feature D]
        HorizonLine[SpatialProjectionEngine::calculateHorizonLine]
        BottomHUD[Bottom HUD: θ_cam vs ψ_rad]
        CamCard[CameraCalibrationCard]
        
        DynamicSynthesis --> Studio
        StaticCalib --> Studio
        ImuTelemetry --> LevelPill
        DynamicSynthesis --> PitchBadge
        DynamicSynthesis --> HorizonLine
        CalibParams --> BottomHUD
        CalibParams --> CamCard
    end
```

---

## 4. Component Implementation Details

### Component 1: Model & Storage Layer

#### [MODIFY] [`CalibrationParameters.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/model/CalibrationParameters.kt)

Add semantic helper properties and mount health evaluators while keeping JSON keys identical:

```kotlin
// --- Semantic Physical Aliases ---
/** Camera optical tilt relative to level ground (measured by IMU) */
val cameraPitchDeg: Float get() = effectivePitchDeg

/** Physical mechanical mounting squint/offset of the front bumper radar */
val radarYawOffsetDeg: Float get() = effectiveYawDeg

/** True if the smartphone windshield clamp is level to the road within ±0.8° */
val isClampLevel: Boolean get() = kotlin.math.abs(rollDeg) <= 0.8f

/** Pitch angle quality status for automotive ADAS windshield mounting */
enum class PitchQuality {
    OPTIMAL,       // 3.0° .. 12.0° (ideal road-to-sky ratio)
    TOO_SHALLOW,   // < 3.0° (too much sky, near bumper clipped)
    TOO_STEEP,     // > 12.0° (too much bonnet/dashboard)
    FACING_UP      // < 0.0° (tilted upwards)
}

val pitchQuality: PitchQuality get() = when {
    effectivePitchDeg < 0.0f -> PitchQuality.FACING_UP
    effectivePitchDeg < 3.0f -> PitchQuality.TOO_SHALLOW
    effectivePitchDeg > 12.0f -> PitchQuality.TOO_STEEP
    else -> PitchQuality.OPTIMAL
}
```

---

### Component 2: ViewModel State Layer

#### [MODIFY] [`RadarViewModel.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)

Add reactive state flows and handler functions for live horizon following, IMU pitch syncing, and radar yaw resetting:

```kotlin
// Feature C: Live Horizon Follow Setup Mode state flow
private val _isLiveImuHorizonEnabled = MutableStateFlow(false)
val isLiveImuHorizonEnabled: StateFlow<Boolean> = _isLiveImuHorizonEnabled.asStateFlow()

fun toggleLiveImuHorizon() {
    val next = !_isLiveImuHorizonEnabled.value
    _isLiveImuHorizonEnabled.value = next
    AppLogger.i("Calibration", "Live IMU Horizon Follow toggled: $next")
}

fun setLiveImuHorizonEnabled(enabled: Boolean) {
    _isLiveImuHorizonEnabled.value = enabled
}

/**
 * Feature A: One-Tap Sync Pitch & Roll from IMU.
 * Instantly transfers the smartphone's gravitational pitch and clamp roll
 * into the camera calibration baseline.
 */
fun syncPitchFromImu() {
    val imuState = imuTelemetry.value
    val current = _calibrationParams.value
    val updated = current.copy(
        pitchDeg = imuState.pitchDeg,
        rollDeg = imuState.rollDeg,
        nudgePitchDeg = 0f
    )
    _calibrationParams.value = updated
    AppLogger.i("Calibration", "Synced camera pitch from IMU: ${imuState.pitchDeg}°, roll: ${imuState.rollDeg}°")
}

/**
 * Feature C: Lock Live IMU Pitch.
 * Freezes the active live-followed pitch and roll into the calibration baseline
 * and terminates live follow mode.
 */
fun lockLiveImuPitch() {
    syncPitchFromImu()
    _isLiveImuHorizonEnabled.value = false
    AppLogger.i("Calibration", "Locked live IMU pitch into calibration baseline")
}

/**
 * Resets strictly the Radar Bumper Yaw offset to 0.0°.
 * Leaves camera pitch and clamp roll unaffected.
 */
fun resetRadarYawToZero() {
    val current = _calibrationParams.value
    _calibrationParams.value = current.copy(
        yawDeg = 0f,
        nudgeYawDeg = 0f
    )
    AppLogger.i("Calibration", "Reset Radar Bumper Yaw offset to 0.0°")
}
```

---

### Component 3: Fullscreen Calibration Studio

#### [MODIFY] [`FullscreenCalibrationStudio.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCalibrationStudio.kt)

1. **Reactive Calibration Synthesis:**
   When `isLiveImuHorizonEnabled` is active, synthesize a dynamic display parameters instance:
   ```kotlin
   val imuTelemetry by viewModel.imuTelemetry.collectAsState()
   val isLiveImuEnabled by viewModel.isLiveImuHorizonEnabled.collectAsState()

   val activeDisplayParams = remember(calibrationParams, imuTelemetry, isLiveImuEnabled) {
       if (isLiveImuEnabled) {
           calibrationParams.copy(
               pitchDeg = imuTelemetry.pitchDeg,
               rollDeg = imuTelemetry.rollDeg,
               nudgePitchDeg = 0f
           )
       } else {
           calibrationParams
       }
   }
   ```
2. **Digital Clamp Level Pill Composable (`DigitalClampLevelPill`):**
   - Renders `[● LEVEL 0.2°]` (Green) or `[◄ TILT L 2.1°]` / `[TILT R 1.8° ►]` (Amber).
   - Interactive click copies current live roll into calibration roll.
3. **Pitch Quality Badge Composable (`PitchQualityBadge`):**
   - Displays `OPTIMAL PITCH`, `TOO SHALLOW`, or `TOO STEEP`.
4. **Top Bar IMU Controls Cluster:**
   - Adds `DigitalClampLevelPill`, `PitchQualityBadge`, and `Live IMU` FilterChip.
   - Adds `[SYNC PITCH]` OutlinedButton.
5. **Bottom Floating HUD Deck Redesign:**
   - **Left Cluster (Camera Pitch):** `θ_cam: %+.1f°` in Sky Blue. Beside it: if `isLiveImuEnabled`, a pulsing `[LOCK PITCH]` button; if static, a `[SYNC IMU]` button.
   - **Center-Left Cluster (Radar Bumper Yaw):** `ψ_rad: %+.1f°` labeled `"RADAR BUMPER YAW"`. Beside it: `[0°]` reset button.
   - **Nudge D-Pad:** Clarified labels (`▲▼ Cam Pitch`, `◄► Radar Yaw`).
6. **Extrinsics Dialog (`CalibrationExtrinsicsDialog`):**
   - Update yaw descriptions to explicitly refer to **Radar Mounting Bumper Angle**.

---

### Component 4: Camera Calibration Card

#### [MODIFY] [`CameraCalibrationCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/CameraCalibrationCard.kt)

1. Receive `imuTelemetry: ImuTelemetryState` and callback `onSyncImuPitch: () -> Unit`.
2. Display `[⚡ SYNC PITCH FROM IMU]` button.
3. Display `DigitalClampLevelPill` and `PitchQualityBadge` inline within the card.
4. Update invocations in [`CameraDashboardCard.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/CameraDashboardCard.kt).

---

## 5. Verification Plan

### Automated Unit Tests
Run existing and new unit test suites:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
```
1. `CalibrationParametersTest`:
   - Verify `cameraPitchDeg` and `radarYawOffsetDeg` aliases match `effectivePitchDeg` and `effectiveYawDeg`.
   - Verify `isClampLevel` returns true for $|\text{Roll}| \le 0.8^\circ$ and false otherwise.
   - Verify `pitchQuality` correctly identifies `OPTIMAL`, `TOO_SHALLOW`, `TOO_STEEP`, and `FACING_UP`.
   - Verify JSON serialization and deserialization remain 100% backward-compatible.
2. `RadarViewModelTest`:
   - Verify `syncPitchFromImu()` sets `pitchDeg` to `imuTelemetry.pitchDeg` and `rollDeg` to `imuTelemetry.rollDeg`.
   - Verify `toggleLiveImuHorizon()` flips `isLiveImuHorizonEnabled`.
   - Verify `lockLiveImuPitch()` copies IMU angles and disables live follow mode.
   - Verify `resetRadarYawToZero()` zeroes only `yawDeg` and `nudgeYawDeg` without touching pitch or roll.
3. `SpatialProjectionEngineTest`:
   - Verify horizon calculation correctly reflects pitch and roll tilts.

### Manual Physical Verification Runbook on Connected Device (`32001991d42c86d9`)
1. **Build & Install:**
   ```powershell
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. **Feature B (Digital Clamp Level):**
   - Dock the phone in the windshield clamp.
   - Intentionally tilt the phone laterally to the left. Observe the amber `◄ TILT L` badge.
   - Rotate the clamp until the bubble centers. Observe the emerald green `● LEVEL` badge.
3. **Feature C (Live Horizon Follow):**
   - In Fullscreen Studio, tap `Live IMU`.
   - Loosen the mount and tilt the phone up and down. Verify the cyan artificial horizon line floats synchronously with your hand at 25 Hz.
   - Tap the pulsing `[LOCK PITCH]` button. Verify live follow ceases and the angle locks in place.
4. **Feature A (One-Tap Sync Pitch):**
   - On the Camera tab or Fullscreen Studio, tap `[SYNC PITCH]`. Verify the pitch value updates instantaneously to match the IMU.
5. **Feature D (Pitch Quality Pill):**
   - Tilt the phone past $12^\circ$. Verify badge shows `TOO STEEP`. Tilt below $3^\circ$. Verify badge shows `TOO SHALLOW`. Set to $7^\circ$. Verify `OPTIMAL PITCH`.
6. **Radar Yaw Bumper Offset Isolation:**
   - Tap `►` on the D-Pad to adjust `ψ_rad`. Verify that radar track positions shift horizontally across the video without changing the camera horizon line elevation.
   - Tap `[0°]` on the `ψ_rad` badge. Verify radar yaw returns to $0.0^\circ$ while camera pitch remains locked.
7. **Session Persistence:**
   - Tap `[Save Baseline]`. Force-stop the app and restart. Verify all calibration angles reload identically.
