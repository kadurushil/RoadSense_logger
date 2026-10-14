# Roadmap & Implementation Plan: 100% Reliable, Zero-Jitter Real-Time ADAS Logging (v2)

## Goal Description
The objective of this activity is to make **RoadSense** the gold standard for on-device ADAS data capture by ensuring that logging runs with **100% precision, zero data drops, and minimal timing jitter**, completely decoupled from and impervious to UI touches, tab swipes, camera preview reconfigurations, and Android operating system interrupts.

---

## 1. Diagnostics Tooling: The "Web DevTools" Equivalent in Android

In Chrome/Web DevTools, performance traces reveal flamecharts, task queues, and microsecond execution times. On Android, the exact equivalent—used internally by Google Android OS and Chrome teams—is **Perfetto** and **Android System Tracing (Systrace)**.

```
+-----------------------------------------------------------------------------------------+
|                                    ANDROID PROFILING STACK                              |
+-----------------------------------------------------------------------------------------+
|  Level 1: Perfetto (ui.perfetto.dev)                                                    |
|  - Microsecond-accurate system-wide timeline (CPU cores, scheduler slices, thread wakeups) |
|  - Custom user-space trace sections via android.os.Trace / androidx.tracing             |
|  - Visualizes Looper message queues, SurfaceFlinger, Camera2 HAL, and app threads       |
+-----------------------------------------------------------------------------------------+
|  Level 2: Android Studio CPU & Method Profiler                                          |
|  - Method Call Trace (Sampled or Exact Instrumentation)                                 |
|  - Inspects call stacks, invocation counts, and self-time for every Kotlin/Java method  |
+-----------------------------------------------------------------------------------------+
|  Level 3: RoadSense In-App Flight Recorder (Nanosecond Micro-Diagnostics)               |
|  - Custom circular latency buffer logging:                                              |
|    * Hardware HAL timestamp: event.timestamp                                            |
|    * Delivery timestamp:     SystemClock.elapsedRealtimeNanos()                         |
|    * Arrival Delta:          t_delivery - t_hal (HAL delivery latency)                  |
|    * Execution Time:         t_exit - t_entry                                           |
+-----------------------------------------------------------------------------------------+
```

### How to use Perfetto for RoadSense:
1. **Instrument Code with Custom Trace Markers**:
   ```kotlin
   import android.os.Trace

   Trace.beginSection("ImuManager:onSensorChanged")
   try {
       // listener processing
   } finally {
       Trace.endSection()
   }
   ```
2. **Record Trace via ADB**:
   ```bash
   adb shell perfetto \
     -c - --txt \
     -o /data/misc/perfetto-traces/roadsense_trace.pftrace <<EOF
   buffers: {
       size_kb: 65536
       fill_policy: RING_BUFFER
   }
   data_sources: {
       config {
           name: "linux.ftrace"
           ftrace_config {
               ftrace_events: "sched_switch"
               ftrace_events: "sched_wakeup"
               atrace_apps: "com.bajajauto.roadsense"
               atrace_categories: "am"
               atrace_categories: "sched"
               atrace_categories: "freq"
           }
       }
   }
   EOF
   ```
3. **Inspect in Browser**:
   Open **[ui.perfetto.dev](https://ui.perfetto.dev)** and drag-and-drop the `.pftrace` file. You will see every core, thread scheduling (`RoadSense-ImuThread`, `ImuSessionRecorder-Worker`), GC pauses, Looper messages, and camera frame shutters on an interactive timeline.

---

## 2. Sensor Architecture: Physical Sensors vs Synthetic Fusion

### A. The Physical Hardware
On our target hardware (Samsung Galaxy M30 / Exynos 7904), the physical IMU is the **STMicroelectronics LSM6DSL**:
- It contains **only two physical MEMS sensors**:
  1. **3-axis Accelerometer** ($a_x, a_y, a_z$)
  2. **3-axis Gyroscope** ($g_x, g_y, g_z$)
- The device may also have a separate 3-axis Magnetometer (or none).
- **Physical Output Data Rate (ODR):**
  LSM6DSL has fixed internal clock dividers:
  $$\text{ODR} \in \{12.5\text{ Hz}, 26\text{ Hz}, 52\text{ Hz}, \mathbf{104\text{ Hz}}, 208\text{ Hz}, 416\text{ Hz}\}$$
  Notice that **104 Hz** ($\approx 9.615\text{ ms}$) is the native clock. There is no 100.0 Hz physical clock!

### B. What is `GAME_ROTATION_VECTOR` and Where Does it Come From?
- `TYPE_GAME_ROTATION_VECTOR` and `TYPE_LINEAR_ACCELERATION` are **Synthetic / Composite Sensors**.
- They are computed by an Extended Kalman Filter (EKF) sensor fusion algorithm that integrates Gyroscope angular velocity with Accelerometer gravity vector to produce an orientation quaternion $[q_x, q_y, q_z, q_w]$:
  - It intentionally ignores the Magnetometer to avoid magnetic field distortions from vehicles/engines.
  - On Exynos devices, this EKF executes either inside the hardware Context Hub (CHUB MCU) or inside the Android `libsensors` HAL library on the CPU.
- When RoadSense requests `delayUs = 10000`, the HAL driver computes this fusion at high rate.
- **Critical Inefficiency Identified in Current Code**:
  In Android, `SensorEvent.values` for `GAME_ROTATION_VECTOR` **already contains the quaternion $[q_x, q_y, q_z, q_w]$ directly**:
  - `values[0]` = $q_x = x \cdot \sin(\theta/2)$
  - `values[1]` = $q_y = y \cdot \sin(\theta/2)$
  - `values[2]` = $q_z = z \cdot \sin(\theta/2)$
  - `values[3]` = $q_w = \cos(\theta/2)$
  
  In [`ImuManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt), our app was doing redundant CPU work on the high-rate thread:
  1. Converting the quaternion to a 3x3 rotation matrix via `SensorManager.getRotationMatrixFromVector()`.
  2. Converting the 3x3 matrix *back* into a quaternion via `rotationMatrixToQuaternion()`.
  3. Querying the Android `WindowManager` on every event (`getDisplayRotation()`) to compute Euler angles.
  4. Cloning 4 float arrays every 40 ms and posting to Compose `StateFlow`.

### C. Raw Logging vs Post-Processing: The Automotive Standard
In tier-1 automotive telemetry (Waymo, Mobileye, ROS 2):
1. **Raw is King**: Unfiltered raw Accel and Gyro measurements ($a_x, a_y, a_z, g_x, g_y, g_z$) are logged at native ODR. Raw data has **zero latency, zero phase lag from filtering**, and can be re-fused post-process using non-causal smoothing filters (e.g. forward-backward Rauch-Tung-Striebel smoothers or graph optimizers) with GPS/odometry.
2. **Real-time Quaternion in MCAP**: Logging the HAL's pre-computed quaternion is still very useful for immediate 3D visualization in Foxglove and live camera-horizon stabilization. We will retain it, but extract it directly from `event.values` without matrix conversion round-trips.

---

## 3. Four-Phase Engineering Roadmap

```mermaid
graph TD
    subgraph "Phase 1: Instrumentation & Tracing"
        P1A[Perfetto & Systrace Markers] --> P1B[Hardware vs App Delivery Timestamps]
        P1B --> P1C[Microsecond Latency Logging]
    end

    subgraph "Phase 2: Sensor HAL Characterization"
        P2A[Empirical Hardware Benchmark] --> P2B[Target Native 104 Hz ODR / SENSOR_DELAY_FASTEST]
        P2B --> P2C[Eliminate Redundant Matrix Math]
    end

    subgraph "Phase 3: Real-Time Logging Architecture"
        P3A[Dedicated High-Priority Raw ImuThread] --> P3B[Lockless Zero-Allocation Ring Buffer]
        P3B --> P3C[Decouple UI Telemetry from Raw Logging]
        P3C --> P3D[Batched Asynchronous File I/O]
    end

    subgraph "Phase 4: Stress Testing on Fresh Capture"
        P4A[Capture Fresh RoadSense Session] --> P4B[Heavy UI Touch / Tab Swipe Stress Test]
        P4B --> P4C[Run audit_cross_sensor_sync.py on New Session]
    end

    Phase 1 --> Phase 2 --> Phase 3 --> Phase 4
```

---

## User Review & Alignment

> [!NOTE]
> **Decisions Aligned with User Feedback**:
> 1. **Decouple Logging from UI Telemetry**:
>    - The high-rate recording pipeline will write to a pre-allocated lock-free ring buffer directly to disk on an urgent priority thread.
>    - A decoupled 25 Hz ticker will snapshot values for Compose UI, ensuring UI taps, tab swipes, and recompositions never touch or stall the logger.
> 2. **Native Hardware Rate (104 Hz)**:
>    - We will match the sensor's native hardware capability (104 Hz / `SENSOR_DELAY_FASTEST`) instead of requesting an arbitrary 100 Hz that forces the driver to drop ticks.
> 3. **Simplified Orientation Extraction**:
>    - Direct extraction of $[q_x, q_y, q_z, q_w]$ from `event.values` without heavy 3x3 matrix conversion or WindowManager queries in the fast path.
> 4. **Validation on Fresh Sessions**:
>    - Validation will be performed on newly recorded test sessions captured with the updated architecture under active UI stress.

---

## Proposed Changes

### Component 1: Diagnostic Instrumentation & Dual Timestamps

#### [MODIFY] [`app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)
- Add dual-timestamp tracking in `ImuFrame`:
  - `elapsedRealtimeNs = event.timestamp` (Driver/SensorHub hardware clock)
  - `deliveryNs = SystemClock.elapsedRealtimeNanos()` (App reception time)
  - `driverLatencyNs = deliveryNs - elapsedRealtimeNs`
- Add Android `Trace.beginSection()` and `Trace.endSection()` around listener callbacks.
- Direct quaternion extraction from `Sensor.TYPE_GAME_ROTATION_VECTOR` (`event.values[0..3]`).
- Configure registration delay to target native hardware rate (~104 Hz / 9615 µs).

### Component 2: Lockless, Zero-Allocation Logging Buffer

#### [NEW] [`app/src/main/java/com/bajajauto/roadsense/recording/ImuRingBuffer.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/ImuRingBuffer.kt)
- High-throughput Single-Producer Single-Consumer (SPSC) lock-free circular ring buffer with pre-allocated `ImuFrame` slot objects.
- Eliminates 100 `Runnable` allocations per second from `executor.execute { ... }`.
- Dedicated worker thread drains buffer in bulk 32 KB blocks to disk.

#### [MODIFY] [`app/src/main/java/com/bajajauto/roadsense/recording/ImuSessionRecorder.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/ImuSessionRecorder.kt)
- Replace `executor.execute { ... }` with `ImuRingBuffer`.
- Remove `_framesRecorded.value = frameCounter` from the critical 100 Hz path. Throttle UI counter updates to 5 Hz (every 200 ms) so Compose UI never recomposes at 100 Hz.
- Set worker thread priority to `Process.THREAD_PRIORITY_URGENT_AUDIO` (-16).

### Component 3: UI & Sensor Stream Decoupling

#### [MODIFY] [`app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)
- Decouple the 1-second CPU/telemetry polling coroutine from sensor event handlers.
- Throttle all telemetry state emissions to UI at a fixed 25 Hz.

### Component 4: Cross-Sensor Sync Audit Script Enhancements

#### [MODIFY] [`scripts/audit_cross_sensor_sync.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py)
- Support auditing sessions with native 104 Hz sampling rate.
- Include delivery latency analysis (`deliveryNs - elapsedRealtimeNs`) when present in new CSV format.
- Add automated jitter score report with pass/fail threshold (< 2.0 ms std dev, 0 intervals > 25 ms).

---

## Verification Plan

### Automated Tests
1. **Unit Tests**:
   - Run Gradle unit tests to ensure all models and buffers pass:
     ```powershell
     $env:JAVA_HOME="C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; .\gradlew.bat testDebugUnitTest
     ```

### Empirical On-Device Verification (Fresh Capture)
1. **Capture New Session**:
   - Record a new 2-minute session with continuous active UI interactions (swiping between Radar, Camera, and IMU tabs, toggling fullscreen).
2. **Audit New Session**:
   - Run `python scripts/audit_cross_sensor_sync.py <new_session_dir>`
   - Confirm in Panel 4 (IMU Stream) that interval $\Delta t$ remains rock-solid around nominal without 15–18 ms periodic spikes.
3. **Perfetto Tracing Session**:
   - Capture a 30-second Perfetto trace during active recording and verify on `ui.perfetto.dev` that `RoadSense-ImuThread` runs uninhibited.
