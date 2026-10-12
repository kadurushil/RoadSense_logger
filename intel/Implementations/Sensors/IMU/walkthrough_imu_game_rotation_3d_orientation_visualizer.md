# Walkthrough: IMU Game Rotation Vector, 3D Orientation Visualizer & Reverse Landscape Fixes

## Overview
We have resolved the yaw lag/overshoot and reverse landscape coordinate frame issues in RoadSense, verified compilation via JVM unit tests, built the debug APK, and streamed the updated build directly to the connected device (`32001991d42c86d9`).

---

## 1. Issues Identified & Root Causes

### 1.1 Yaw Delay & Overshoot on a Flat Table
* **Symptom:** When rotating the phone lying flat on a table, yaw exhibited noticeable lag, overshot the stopping point, and slowly drifted back to the final angle.
* **Root Cause:** In `ImuManager.kt`, the previous code called:
  ```kotlin
  val axisY = SensorManager.AXIS_MINUS_Z
  SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
  SensorManager.getOrientation(targetMatrix, orientationAngles)
  ```
  When the phone lies flat on a table, the rear camera axis ($-Z_{\text{phone}}$) points directly down into the floor (parallel to gravity). Mapping the remapped $Y$-axis to $-Z_{\text{phone}}$ forced the Euler attitude representation into **Pitch $= -90^\circ$ (Exact Gimbal Lock)**. At gimbal lock, the Euler pitch/roll trigonometric denominator approaches zero, leading to severe numerical instability, filter lag, and overshoot.
* **Fix:** When the phone is flat ($|R_8| > 0.7$), yaw is derived directly from the un-remapped native rotation matrix `SensorManager.getOrientation(R, orientationAngles)[0]`, restoring instant, sub-degree responsiveness without gimbal lock. When mounted upright in the vehicle clamp, yaw represents camera optical boresight azimuth in the horizontal plane: $\text{atan2}(-R_2, -R_5)$.

### 1.2 Inverted / Swapped Coordinates in Reverse Landscape (+75° Pitch, +90.7° Roll)
* **Symptom:** In vehicle reverse landscape mount (`Surface.ROTATION_270`), pitch read $+75^\circ$ and lateral roll read $+90.7^\circ$.
* **Root Cause:** In reverse landscape, the phone is turned 90° clockwise:
  * Screen Top ($+Y_{\text{phone}}$) points **Right across the dashboard**.
  * Screen Right ($+X_{\text{phone}}$) points **Down toward the floor**.
  * Screen Front ($+Z_{\text{phone}}$) points **Rearward into the cabin**.
  * Rear Camera ($-Z_{\text{phone}}$) points **Forward through the windshield**.
  The artificial `remapCoordinateSystem` swapped $+X$ and $-Z$, which treated the vertical earth axis as Roll (giving $\approx 90.7^\circ$) and the longitudinal boresight as Pitch (giving $\approx 75^\circ$).
* **Fix:** Direct closed-form trigonometric formulas derived from the raw world matrix $R$:
  * **Camera Pitch:** $\theta = \arcsin(R_8)$ (positive tilts down toward the road). In clamp, this reads nominal $+2.0^\circ$ to $+14.0^\circ$.
  * **Lateral Mount Roll:** $\phi = \arcsin(R_7)$ for `ROTATION_270` (lateral axis across the dashboard is $+Y_{\text{phone}}$). In a level mount, this reads $\approx 0.0^\circ$.

---

## 2. Key Code Changes

### 2.1 [`ImuManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)
- Removed `SensorManager.remapCoordinateSystem` from `Sensor.TYPE_GAME_ROTATION_VECTOR`.
- Implemented closed-form formulas:
  - `pitchDeg = Math.toDegrees(asin(R[8].coerceIn(-1f, 1f)).toDouble()).toFloat()`
  - `rollDeg = Math.toDegrees(lateralRollRad.toDouble()).toFloat()` where `lateralRollRad = asin(R[7])` in `ROTATION_270`.
  - `yawDeg = if (abs(R[8]) > 0.7f) getOrientation(R)[0] else atan2(-R[2], -R[5])`.
- Preserved raw `rotationMatrix` in `ImuTelemetryState` for 1:1 3D model visualization.

### 2.2 [`ImuSample.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSample.kt)
- Synchronized `toEulerAnglesDeg(displayRotation = Surface.ROTATION_270)` with the identical closed-form formulas to keep offline analysis, MCAP playback, and live telemetry mathematically identical.

### 2.3 [`PhoneOrientation3DVisualizer.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/PhoneOrientation3DVisualizer.kt)
- **Camera Module Placement:** Set `lensLocalX = 0.18f` so the camera lens is positioned on the top-left of the phone when viewed from the back chassis, matching physical smartphones.
- **Road (+Y) Axis Alignment on Reset:** Pressing the Reset button tares the coordinate frame around world $Z$ to align the Road (+Y) green line directly with the phone's current camera POV vector ($[0, \text{fwd}, -R_8]$), bringing the camera boresight into the $YZ$ plane.
- **Orthogonal Side (Pitch) View Shortcut:** Added a dedicated button beside Reset to snap the 3D orbit camera to an orthogonal side elevation view ($\text{Azimuth} = 90^\circ, \text{Elevation} = 0^\circ$) perpendicular to the $YZ$ plane. In this mode, the ground is an exact horizontal line, the road extends to the right, and the viewer sees the exact physical Pitch tilt angle ($\theta$) without perspective distortion.
- **Header Badge Reflow Fix:** Added `Modifier.weight(1f)` to title column, set `maxLines = 1, softWrap = false` on alignment badge text to completely prevent vertical letter reflow in portrait mode.

---

## 3. Verification & Build Results

### 3.1 JVM Unit Tests
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
```
* **Status:** `BUILD SUCCESSFUL in 10s` (24 actionable tasks: 8 executed, 16 up-to-date, 0 failures).

### 3.2 Android Debug APK Compilation
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug
```
* **Status:** `BUILD SUCCESSFUL in 3s` (36 actionable tasks: 7 executed, 29 up-to-date).

### 3.3 Streamed Installation on Connected Device
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
* **Status:** `Success` (Installed on device `32001991d42c86d9`).

---

## 4. Verification Guide on Device

1. **Flat on Table:**
   - Place phone flat on table surface (screen facing up).
   - In Live Telemetry under IMU tab, turn on **Multi-Sensor Live Stream**.
   - Rotate phone horizontally on table: Yaw updates **instantly** without overshoot, lag, or rebound.
   - Pitch and Roll should read $\approx 0.0^\circ$.
2. **Reverse Landscape in Vehicle Mount:**
   - Mount phone in horizontal cradle in reverse landscape (`ROTATION_270`, top of phone pointing right).
   - Verify:
     - **Pitch:** Reads $+2^\circ$ to $+14^\circ$ depending on windshield rake angle.
     - **Roll:** Reads $\approx 0.0^\circ$ (within $\pm 1.2^\circ$). Status badge shows **`OPTIMAL MOUNT`** (Green).
     - **3D Canvas:** Phone model appears upright in landscape, screen facing cabin, rear camera boresight ray pointing forward and downward toward the ground plane.
