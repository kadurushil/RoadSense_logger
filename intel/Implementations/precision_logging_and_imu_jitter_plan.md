# Roadmap & Implementation Plan: 100% Reliable, Zero-Jitter Real-Time ADAS Logging

## Goal Description
The objective of this activity is to make **RoadSense** the gold standard for on-device ADAS data capture by ensuring that logging runs with **100% precision, zero data drops, and minimal timing jitter**, completely decoupled from and impervious to UI touches, tab swipes, camera preview reconfigurations, and Android operating system interrupts.

In this plan, we:
1. Answer the question regarding **Web DevTools-like profiling in Android** (how to trace every function call and timing down to the microsecond).
2. Provide a **forensic mathematical diagnosis** of the 15–18 ms IMU jitter observed in `session_20261012_164153`.
3. Resolve whether the jitter originates from the **Hardware/HAL layer (ST LSM6DSL)** or the **App architecture**.
4. Define a structured, 4-phase engineering roadmap to refactor our multi-threading pipeline into a **lockless, zero-allocation real-time logging architecture**.

---

## 1. Diagnostics Tooling: The "Web DevTools" Equivalent in Android

In Chrome/Web DevTools, you record performance traces showing flamecharts, task queues, and frame execution times. On Android, the exact equivalent—used internally by Google Android OS and Chrome teams—is **Perfetto** and **Android System Tracing (Systrace)**.

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
   Open **[ui.perfetto.dev](https://ui.perfetto.dev)** and drag-and-drop the `.pftrace` file. You will see every core, every thread (`RoadSense-ImuThread`, `ImuSessionRecorder-Worker`), GC pause, Looper message, and camera frame shutter on an interactive timeline.

---

## 2. Forensic Analysis of `session_20261012_164153`

### A. Empirical Findings
We conducted a deep quantitative analysis of `logs/session_20261012_164153/imu/imu_frames.csv` (107,547 samples over 18.1 minutes):

| Metric | Measured Value | Target / Ideal |
| :--- | :--- | :--- |
| **Total Frames** | 107,547 | ~108,823 @ 100 Hz |
| **Mean Sample $\Delta t$** | **10.111 ms** (~98.82 Hz) | 10.000 ms (100 Hz) |
| **Median $\Delta t$** | **10.003 ms** | 10.000 ms |
| **Sample Interval Std Dev** | **0.860 ms** | < 0.20 ms |
| **Min Interval** | **8.000 ms** | 10.0 ms |
| **Max Interval** | **17.994 ms** | 10.0 ms |
| **Intervals $\Delta t \in [9.5, 10.5]$ ms** | **87.20%** (93,789 samples) | > 99.5% |
| **Spikes > 15.0 ms** | **1,115 spikes** (1.04%) | 0 |
| **Mean Inter-Spike Time** | **0.975 seconds (~ 1 Hz)** | N/A |

### B. The "Smoking Gun": Why Do Spikes Peak at 17.99 ms?
Examining the intervals immediately preceding and following the spikes revealed an exact mathematical identity:

```
Sample window around Frame 84:
  [10.25 ms,  12.22 ms,  17.25 ms,  9.99 ms,  9.97 ms]
              ^prev_dt    ^spike_dt
  Sum (prev_dt + spike_dt) = 12.22 + 17.25 = 29.47 ms (~ 30.0 ms)

Sample window around Frame 175:
  [10.18 ms,  13.18 ms,  17.56 ms,  9.11 ms,  10.43 ms]
  Sum (prev_dt + spike_dt) = 13.18 + 17.56 = 30.74 ms (~ 30.0 ms)

Sample window around Frame 282:
  [10.09 ms,  12.06 ms,  17.82 ms,  9.91 ms,  10.08 ms]
  Sum (prev_dt + spike_dt) = 12.06 + 17.82 = 29.88 ms (~ 30.0 ms)
```

Across all 1,115 spikes, **$\text{prev\_dt} + \text{spike\_dt} = 29.63 \pm 1.2\text{ ms}$**.
This equals **$3 \times 10.0\text{ ms}$**!
In an ideal 100 Hz stream, a 30 ms window contains 4 samples (3 intervals: $10\text{ms} + 10\text{ms} + 10\text{ms}$). Here, there are only 3 samples (2 intervals: $\sim 12.5\text{ms} + \sim 17.5\text{ms}$). **One sample was omitted or skipped approximately every second (~1 Hz)!**

### C. Root Cause: HAL vs App Architecture

Is it the HAL or the App? The answer is a combination of **Hardware Clock Mismatch** and **App Threading/Allocation Contention**:

1. **Hardware ODR Mismatch (HAL Factor)**:
   - Device: Samsung Galaxy M30 (`SM-M305F`) with **ST LSM6DSL** IMU.
   - The ST LSM6DSL hardware has fixed Output Data Rates (ODRs) based on power-of-two clock dividers:
     $$\text{ODR} \in \{12.5\text{ Hz}, 26\text{ Hz}, 52\text{ Hz}, \mathbf{104\text{ Hz}}, 208\text{ Hz}, 416\text{ Hz}\}$$
   - Notice that LSM6DSL has **NO native 100.0 Hz clock**! Its nominal period is $1/104\text{ Hz} \approx 9.615\text{ ms}$.
   - When Android Sensor HAL is asked for `delayUs = 10000` (100 Hz), the HAL driver samples the FIFO or decimates. Because $104\text{ Hz} \neq 100\text{ Hz}$, there is a periodic phase slip: $(104 - 100) = 4\text{ events/sec}$, which the HAL driver adjusts by dropping or stretching ticks to avoid FIFO overflow.

2. **Multiplexing Contention on a Single HandlerThread (App Factor)**:
   - In [`ImuManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt), 4 sensors are registered on the **same** `HandlerThread` Looper:
     1. `TYPE_ACCELEROMETER` (100 Hz)
     2. `TYPE_LINEAR_ACCELERATION` (100 Hz)
     3. `TYPE_GYROSCOPE` (100 Hz)
     4. `TYPE_GAME_ROTATION_VECTOR` (100 Hz)
   - That is **400 callbacks per second** hitting a single `HandlerThread` queue.
   - For `GAME_ROTATION_VECTOR`, the handler executes heavy rotation matrix transformations, `asin`, `atan2`, and quaternion math.
   - On **every event**, it checks a UI interval, clones 4 float arrays (`currentRawAccel.clone()`), and updates `_telemetryState.value` (`MutableStateFlow`), which triggers Compose UI thread synchronization.

3. **Per-Frame Object Allocation & StateFlow Thrashing (App Factor)**:
   - In [`ImuSessionRecorder.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/ImuSessionRecorder.kt), `recordFrame(frame)` submits an anonymous `Runnable` via `executor.execute { ... }` on **every single 100 Hz sample** (100 allocations/sec).
   - Inside the worker, it executes `_framesRecorded.value = frameCounter`, causing StateFlow emissions that trigger Jetpack Compose recomposition 100 times a second on the UI thread!
   - In [`RadarViewModel.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt), a 1-second coroutine ticker (`delay(1000)`) polls CPU metrics and pushes 4 StateFlow updates simultaneously, coinciding with the ~1 Hz spike frequency!

---

## 3. Four-Phase Roadmap to Gold-Standard Reliability

```mermaid
graph TD
    subgraph "Phase 1: Instrumentation & Tracing"
        P1A[Perfetto & Systrace Markers] --> P1B[Hardware vs App Delivery Timestamps]
        P1B --> P1C[Microsecond Latency Logging]
    end

    subgraph "Phase 2: Sensor HAL Characterization"
        P2A[Standalone Hardware Benchmark] --> P2B[Empirical ODR at 10000us vs FASTEST]
        P2B --> P2C[Isolate Accel vs Multi-Sensor Fusion]
    end

    subgraph "Phase 3: Real-Time Logging Architecture"
        P3A[Dedicated High-Priority Raw ImuThread] --> P3B[Lockless Zero-Allocation Ring Buffer]
        P3B --> P3C[Decouple UI Telemetry from Raw Logging]
        P3C --> P3D[Batched Asynchronous File I/O]
    end

    subgraph "Phase 4: Stress Testing & Verification"
        P4A[UI Touch & Tab Swipe Stress Harness] --> P4B[Android Interruption / Surface Tests]
        P4B --> P4C[Automated Jitter Pass/Fail Verification]
    end

    Phase 1 --> Phase 2 --> Phase 3 --> Phase 4
```

---

## User Review Required

> [!IMPORTANT]
> **Key Architecture Decisions for User Approval**:
> 1. **Decoupling Logging from UI Telemetry**:
>    - Current: Raw sensor events trigger UI StateFlow updates and array cloning in the same path.
>    - Proposed: Raw IMU stream writes directly to a zero-allocation pre-allocated ring buffer for disk logging. A separate, downsampled 25 Hz ticker reads the latest state for Compose UI gauges, ensuring UI activity never stalls the logger.
> 2. **Sensor Registration Strategy**:
>    - For recording: Should we log individual raw sensor streams (`TYPE_ACCELEROMETER` + `TYPE_GYROSCOPE_UNCALIBRATED`) directly, or continue recording the consolidated fused frame?
>    - Fusing Game Rotation Vector on the high-rate thread adds computational overhead. We propose running raw Accel + Gyro on the ultra-low latency thread and offloading sensor fusion or sampling it at 25–50 Hz.
> 3. **Sampling Rate Target**:
>    - If the physical sensor (LSM6DSL) native ODR is 104 Hz (9.61 ms), requesting 100 Hz (10.0 ms) forces the driver to interpolate/skip. We can evaluate whether matching the native hardware ODR (or capturing raw FIFO events) is superior for ADAS/MCAP downstream consumption.

---

## Proposed Changes

### Component 1: Diagnostic Instrumentation & Benchmarking

#### [MODIFY] [`app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/imu/ImuManager.kt)
- Add dual-timestamp tracking in `ImuFrame`:
  - `hwTimestampNs = event.timestamp` (Driver/SensorHub clock)
  - `arrivalTimestampNs = SystemClock.elapsedRealtimeNanos()` (App reception time)
  - `driverLatencyNs = arrivalTimestampNs - hwTimestampNs`
- Add Android `Trace.beginSection()` and `Trace.endSection()` around listener callbacks.
- Add an empirical hardware ODR characterization test to test `SENSOR_DELAY_FASTEST` vs `delayUs = 10000` with 0 UI overhead.

### Component 2: Lockless, Zero-Allocation Logging Buffer

#### [NEW] [`app/src/main/java/com/bajajauto/roadsense/recording/ImuRingBuffer.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/ImuRingBuffer.kt)
- High-throughput Single-Producer Single-Consumer (SPSC) lock-free circular ring buffer with pre-allocated `ImuFrame` slot objects.
- Eliminates 100 `Runnable` allocations per second from `executor.execute { ... }`.
- Writer thread drains buffer in bulk 32 KB blocks to disk.

#### [MODIFY] [`app/src/main/java/com/bajajauto/roadsense/recording/ImuSessionRecorder.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/recording/ImuSessionRecorder.kt)
- Replace `executor.execute { ... }` with the lockless ring buffer.
- Remove `_framesRecorded.value = frameCounter` from the critical 100 Hz path. Throttle UI counter updates to 5 Hz (every 200 ms) so Compose UI never recomposes at 100 Hz.
- Set thread priority to `Process.THREAD_PRIORITY_URGENT_AUDIO` (-16) or `URGENT_DISPLAY` (-8).

### Component 3: UI & Sensor Stream Decoupling

#### [MODIFY] [`app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)
- Decouple the 1-second CPU/telemetry polling coroutine from sensor event handlers.
- Throttle all telemetry state emissions to UI at a fixed 25 Hz.

### Component 4: Cross-Sensor Sync Audit Script Enhancements

#### [MODIFY] [`scripts/audit_cross_sensor_sync.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py)
- Add automated quantification of hardware ODR vs driver drop spikes.
- Add hardware timestamp vs arrival timestamp latency plot when available in `imu_frames.csv`.
- Add automated PASS/FAIL jitter threshold (< 2.0 ms std dev, 0 intervals > 25 ms).

---

## Verification Plan

### Automated Tests
1. **Unit Tests**:
   - Run Gradle unit tests to ensure all models and buffers pass:
     ```powershell
     $env:JAVA_HOME="C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; .\gradlew.bat testDebugUnitTest
     ```
2. **Offline Log Audit**:
   - Run audit script on session logs:
     ```powershell
     python scripts/audit_cross_sensor_sync.py session_20261012_164153
     ```

### Manual & On-Device Verification
1. **Perfetto Tracing Session**:
   - Capture a 30-second Perfetto trace during active recording with heavy UI interaction (swiping tabs between Radar, Camera, and IMU).
   - Verify on `ui.perfetto.dev` that `ImuThread` never gets descheduled or preempted by UI recomposition or GC.
2. **Live Benchmarking**:
   - Run the updated IMU benchmark on the Samsung M30 device with screen on, screen off, and active recording to measure the raw sensor jitter floor.
