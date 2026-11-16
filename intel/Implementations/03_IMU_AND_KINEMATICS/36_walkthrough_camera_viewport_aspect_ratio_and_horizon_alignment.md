# Walkthrough: Camera Viewport 16:9 FIT, Square-Pixel Intrinsics & Horizon Alignment

## Overview
This implementation resolves the visual horizon divergence observed during Live IMU camera calibration on the Samsung Galaxy M30 (`1080 x 2340`, 19.5:9 aspect ratio). It introduces a unified **Standard 16:9 FIT** viewport model across all app screens, corrects anamorphic camera intrinsics distortion, aligns the artificial horizon with the physical world, relocates studio calibration controls into natural pillarbox wings, automates IMU monitoring during calibration, and corrects the physical roll angle sign.

---

## Changes

### 1. Unified 16:9 FIT Viewport Engine
- Created [CameraViewportTransform.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraViewportTransform.kt):
  - Enforces `scale = minOf(viewW / effStreamW, viewH / effStreamH)` (scale = 1.0 on Galaxy M30 in 1080p landscape).
  - Calculates symmetrical pillarbox wings: `offsetX = 210 px` left and right on a 2340 px wide screen.
  - Generates exact `Matrix` transforms without center-crop zooming or distortion.
- Updated [CameraEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraEngine.kt) to delegate `configureTransform` to `CameraViewportTransform.configureTransformMatrix`.

### 2. Standard 16:9 Aspect Ratio Across All Viewports
- Updated [SbsDashboardCard.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/SbsDashboardCard.kt) to strictly enforce `16f / 9f` aspect ratio for the camera viewport.
- Updated [CameraDashboardCard.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/CameraDashboardCard.kt) to strictly enforce `16f / 9f` aspect ratio in both landscape and portrait layouts.

### 3. Galaxy M30 Sensor Crop & Square-Pixel Intrinsics
- Overhauled [CameraIntrinsicsProvider.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/CameraIntrinsicsProvider.kt):
  - Fixed 4:3 sensor (`4128 x 3096`) to 16:9 stream vertical ISP crop calculation ($H_{\text{crop}} = 2322$ px).
  - Accurately computes square pixel focal length:
    $$f_x = f_y = 1920 \times \left(\frac{3.58}{4.624}\right) = 1486.5\text{ px}$$
    (eliminating previous 33.3% anamorphic focal length underestimation of 1114.9 px).
  - Aligns optical center strictly with view center: $c_x = 1170\text{ px}, c_y = 540\text{ px}$.

### 4. Natural Pillarbox Studio HUD Layout
- Redesigned [FullscreenCalibrationStudio.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCalibrationStudio.kt):
  - Removed bottom floating calibration bar that previously occluded the center road surface.
  - **Left Pillarbox Wing:** Camera pitch badge ($\theta_{\text{cam}}$), vertical nudge buttons (▲/▼), and Live IMU SYNC/LOCK button.
  - **Right Pillarbox Wing:** Radar yaw badge ($\psi_{\text{rad}}$), horizontal nudge buttons (◄/►), 0° yaw reset button, and Extrinsics Settings modal gear.
  - Central 16:9 video frame (`1920 x 1080`) remains 100% unobstructed.
- Updated [SpatialProjectionEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt):
  - Artificial horizon endpoints snap cleanly to `videoOffsetX` (210 px) and `videoOffsetX + videoWidth` (2130 px), keeping lines inside the video frame and off the pillarbox control wings.

### 5. Automated IMU Monitoring Lifecycle
- Updated [RadarViewModel.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt):
  - When opening calibration studio (`setCalibrationFullScreen(true)`), IMU monitoring automatically starts if not already active and `isLiveImuHorizonEnabled` is turned on by default.
  - When exiting calibration studio (`setCalibrationFullScreen(false)`), `isLiveImuHorizonEnabled` is turned off, and IMU monitoring automatically stops if it was initiated by calibration (preserving battery).
  - Added `BackHandler(onBack = onExit)` and `DisposableEffect` in [FullscreenCalibrationStudio.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCalibrationStudio.kt) to ensure graceful teardown on back navigation.

### 6. Physical Roll Angle Sign Correction
- Corrected lateral mount roll angle sign in [ImuManager.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt) and [ImuSample.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSample.kt):
  - In Reverse Landscape (`ROTATION_270`): `roll = -asin(R[7])`.
  - When rotating the phone to the left (CCW roll, left edge dips, right edge lifts), the measured roll angle is now negative ($\text{roll} < 0$).
  - In [SpatialProjectionEngine.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt), this causes the artificial horizon to rotate to the right (Clockwise), keeping it stable and in sync with the physical world horizon.

---

## Verification & Testing

### JVM Unit Tests
Ran `.\gradlew.bat testDebugUnitTest`:
```powershell
BUILD SUCCESSFUL in 4s
24 actionable tasks: 9 executed, 15 up-to-date
53 tests completed, 0 failed
```
- [CameraViewportTransformTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/camera/CameraViewportTransformTest.kt): Validates 16:9 FIT geometry, scale = 1.0, 210 px pillarbox offsets, and centered intrinsics.
- [SpatialProjectionEngineTest.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/fusion/SpatialProjectionEngineTest.kt):
  - `testDownwardPitchMovesHorizonLineUpward`: Verifies downward tilt moves horizon UP on screen.
  - `testRotatingPhoneToLeftRotatesHorizonLineToRightForPhysicalSync`: Verifies rotating phone left rotates horizon right for physical synchronization.

### Debug APK Build
Ran `.\gradlew.bat assembleDebug`:
```powershell
BUILD SUCCESSFUL in 2s
36 actionable tasks: 4 executed, 32 up-to-date
```
Output: `app/build/outputs/apk/debug/app-debug.apk` (ready for manual installation).
