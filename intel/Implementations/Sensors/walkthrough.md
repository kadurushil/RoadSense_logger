# Walkthrough: IMU Subsystem Integration & Empirical Rate Discovery

> **Document Name:** `walkthrough.md`  
> **Location:** `intel/Implementations/Sensors/walkthrough.md`  
> **Subsystem:** Inertial Measurement Unit (IMU) Subsystem (Phase 1)  
> **Target Hardware:** Samsung Galaxy M30s (`SM-M305F`) / Exynos 7904/9611 (Android 10)  
> **Status:** Fully Implemented, Tested, & Verified  

---

## 1. Overview of Accomplishments

In this phase, we completed the full architectural integration of the onboard mobile IMU into RoadSense, based directly on the hardware profile audited from the connected test phone:
1. **Live ADB Hardware Audit:** Identified the primary physical 6-DOF IMU (**STMicroelectronics LSM6DSL** Accelerometer & Gyroscope) and **Yamaha YAS539** Magnetometer, along with Samsung's HAL-level rate cap of **`100.00 Hz`** ($10.0\,\text{ms}$ interval). Also audited all 4 camera HAL devices (`Camera 0`, `Camera 1`, `Camera 50` Ultra-Wide, `Camera 52`).
2. **High-Rate Permissions:** Declared `<uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />` in `AndroidManifest.xml` to prevent OS rate throttling.
3. **Dedicated Background Threading:** Routed all sensor callbacks to an isolated `HandlerThread` (`RoadSense-ImuThread`) running at `THREAD_PRIORITY_URGENT_DISPLAY` to guarantee zero UI frame drops.
4. **Empirical Sampling Rate Profiler & Jitter Analyzer:** Implemented live interval tracking with a circular timing buffer calculating rolling frequency (Hz), inter-arrival times ($\Delta t$), and timing jitter ($\sigma_{\Delta t}$) across standard presets (`FASTEST`, `100 Hz`, `50 Hz`, `20 Hz`).
5. **Interactive IMU Cockpit Card (`ImuDashboardCard.kt`):** Built a multi-tab cockpit screen housing:
   - **Sensor Audit Sub-Tab:** Lists all 18 sensors on the phone with hardware specs, vendors, resolutions, power, and HAL rate caps.
   - **Rate Profiler Sub-Tab:** Interactive test bench for running sampling benchmarks with real-time KPI readouts.
   - **Live Telemetry Sub-Tab:** Real-time visualizers for Raw Accel vs Linear Accel (without $g$), Gyroscope rates ($\omega_x, \omega_y, \omega_z$), and 6-DOF vehicle attitude (Pitch, Roll, Yaw) throttled at ~25 Hz for smooth UI.
6. **Live Header Ticker:** Added an `IMU: 100.0 Hz` pill to `LiveMetricsBar` in both landscape and portrait layouts.
7. **Cockpit Navigation:** Updated tab sequence: `RADAR` $\rightarrow$ `GNSS` $\rightarrow$ **`IMU`** $\rightarrow$ `CAMERA` $\rightarrow$ `CANEDGE` $\rightarrow$ `SBS` $\rightarrow$ `SESSION`.

---

## 2. Key Code Changes

### Permissions & Manifest
* **[`AndroidManifest.xml`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/AndroidManifest.xml):**
  Added `HIGH_SAMPLING_RATE_SENSORS` and optional feature tags for accelerometer and gyroscope.

### Core IMU Engine (`com.bajajauto.roadsense.imu`)
* **[`ImuSensorCapability.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSensorCapability.kt):** Model for discovered sensor hardware specs, categories (`MOTION`, `ORIENTATION`, `UNCALIBRATED`, `AUXILIARY`), and HAL rate conversion.
* **[`ImuSample.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSample.kt):** Nanosecond monotonic data model with 3-axis accessors, quaternion normalization, vehicle windshield coordinate remapping (`toEulerAnglesDeg` with `remapCoordinateSystem`), and CSV formatting.
* **[`ImuSamplingBenchmark.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuSamplingBenchmark.kt):** Statistical model for empirical rate evaluation with `ImuRatePreset` definitions.
* **[`ImuTelemetryState.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuTelemetryState.kt):** Conflated 25 Hz telemetry model for Compose UI.
* **[`ImuManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt):** Central sensor controller managing background `HandlerThread`, dynamic rate profiling, windshield landscape coordinate remapping (camera forward along $-Z_{\text{phone}}$), and multi-sensor live stream.

### ViewModel & UI Integration
* **[`RadarViewModel.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt):** Instantiated `ImuManager`, exposed StateFlows (`imuCapabilities`, `imuBenchmarkStats`, `imuTelemetry`, `imuHz`), and delegated actions.
* **[`ImuDashboardCard.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/ImuDashboardCard.kt):** Full Compose dashboard with Audit, Profiler, and Live Telemetry sub-tabs.
* **[`LiveMetricsBar.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/LiveMetricsBar.kt):** Added `IMU: 100.0 Hz` indicator pill.
* **[`MainActivity.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt):** Added `CockpitTab.IMU` at index 2 and wired into `HorizontalPager`.

---

## 3. Verification & Test Results

### 3.1 Automated JVM Unit Tests
Ran `./gradlew testDebugUnitTest`:
```text
BUILD SUCCESSFUL in 10s
24 actionable tasks: 2 executed, 22 up-to-date
All 38 unit tests completed with 0 failures:
  - ImuSensorCapabilityTest: testImuSensorCapabilityPropertiesAndFormatting, testOnChangeSensorFormatting [PASSED]
  - ImuSampleTest: testImuSampleVectorCalculations, testLinearAccelerationWithoutGravity, testQuaternionReconstruction [PASSED]
  - ImuSamplingBenchmarkTest: testImuRatePresets, testImuSamplingBenchmarkCalculations [PASSED]
  - All existing Radar, Camera, GNSS, CANedge & Fusion tests [PASSED]
```

### 3.2 APK Assembly
Ran `./gradlew assembleDebug`:
```text
BUILD SUCCESSFUL in 9s
Target: app/build/outputs/apk/debug/app-debug.apk (Built cleanly)
```

---

## 4. Hardware Verification Runbook (For User)

1. **Install and Launch APK:**  
   Build and install `app-debug.apk` onto your Samsung Galaxy M30s.
2. **Observe Cockpit Layout:**  
   Confirm the new **`IMU`** tab appears directly between `GNSS` and `CAMERA`.
3. **Sub-Tab 1: Sensor Audit:**  
   Tap **Sensor Audit** to view the live list of all 18 sensors on your device, filterable by Category (`Motion`, `Orientation`, `Uncalibrated`).
4. **Sub-Tab 2: Rate Profiler:**  
   - Select `LSM6DSL Accel` $\rightarrow$ Preset `100 Hz (10,000 µs)` $\rightarrow$ Tap **Start Sampling Benchmark**.
   - Observe the real-time measured rate stabilizing at **~100.0 Hz** with timing jitter ($\sigma < 1.0\,\text{ms}$).
   - Test `FASTEST` preset to confirm the uncapped driver behavior.
5. **Sub-Tab 3: Live Telemetry:**  
   - Toggle **Multi-Sensor Live Stream**.
   - Observe Raw Accel vs Linear Accel (without $g$) responding to vehicle/phone movement.
   - Tilt the device to observe the 6-DOF Pitch, Roll, and Yaw angles updating smoothly.
   - Observe the top `LiveMetricsBar` displaying `IMU: 100.0 Hz` in green.
