# Forensic Engineering Audit: `session_20261113_101048`

**Session:** `logs/session_20261113_101048` (`samsung SM-M325F`, Android 13 / SDK 33)  
**Duration:** `542.37 s` (`9.04 minutes`, `2026-11-13T10:10:48.460` -> `10:19:50.826+05:30`)  
**Scope:** Critical evaluation of whether previous UI & pipeline performance fixes truly solved runtime lag, deep check for hidden radar/USB frame losses, root-cause decomposition of the ~452s stutter incident, and audit of script blind spots.

---

## Executive Verdict: Brutally Honest Scorecard

| Subsystem | Verdict | Reality Behind the Numbers |
| :--- | :---: | :--- |
| **Background Capture Pipeline** (Radar Worker, IMU, Camera HAL/MediaRecorder, GNSS, CANedge2) | **SOLVED (Rock-Solid)** | **0** worker queue drops (`chunksDropped=0`), **10,630 consecutive zero-loss radar frames** (`#11723..#22352`, `mean = 50.001 ms`), **108,322 IMU samples** @ `200.00 Hz` (`0` intervals `>20ms`), **16,234 Camera frames** (`0.01%` drops), **3 valid CAN MF4 chunks** (`7.12 MB`). |
| **Radar Initial Connect Hygiene** | **UNFIXED BUG** | The reported `"6,242 / 6,243 dropped frames"` is **NOT a runtime USB loss** — it was **1.5 stale frames (`#5479` + partial `#5480`, 1,023 B)** left in the CP2105/USB RX buffer from the previous run 316s earlier (`10:05:43`), flushed immediately on connect at `10:10:59.335`. After `#11723`, **0 wire frames were lost**. |
| **Live UI Rendering & Smoothness (`UiPerf`)** | **NOT SOLVED UNDER REAL LOAD** | Yesterday's desk verification (`p50 = 3.45 ms`) did **not** hold on the road. Under real drive workload, **`68%–100%` of UI frames exceeded `16.67 ms`**: `Radar` tab ran at **`p50 = 22.3 ms`** (`88.9%` jank), `SBS` at **`p50 = 31.3 ms`** (`96.2%` jank), and `Camera` tab degraded to **`p50 = 45.9–83.7 ms`** (`98.7%` jank; peaking **`p50 = 83.7 ms, p95 = 244.5 ms, max = 334.6 ms`** right around `t = 452–457 s`). |
| **`RadarDisplayPacer` & Logging Path** | **PARTIAL / REGRESSED** | Dropped **10 UI frames** (`#15899..#17133`), hit **`122.24 ms` timer wakeup jitter**, locked `qDepth = 5` (**250 ms permanent display lag**) for the final 4.5 minutes, and spammed **21,553 `[DisplayPacer]` debug lines** (`98.3%` of `session_debug.log`), causing **80+ synchronous disk `flush()` calls/sec** under `AppLogger.lock`. |

---

## 1. Did We Lose Radar Frames Due to USB Delay?

**No — zero radar frames were lost on the USB wire during the 8.86-minute streaming run (`#11723..#22352`), including around `t = 452s`.**  
However, the scripts flag a scary `6,242` / `6,243` frame drop (`63.00% Delivery Rate`) that obscures both what actually happened on connect and how the discipliner handles USB bursts.

### 1.1 Exact Anatomy of the `"6,243"` Frame Gap (`#5480 -> #11723`)
Direct binary parsing of `radar/radar_raw_stream.bin` and `radar/radar_frames.bin` shows:
1. Frame `idx=0` at byte `0`: `seq = #5479` (`tot_len = 660 B`, `33 objs`).
2. Frame `idx=1` at byte `672`: `seq = #5480` (`tot_len = 724 B`, truncated at `351 B`, salvaged as 311 B payload by `RadarPacketAssembler`).
3. Frame `idx=2` at byte `1023`: `seq = #11723` (`tot_len = 428 B`, `17 objs`).
4. Cross-referencing `logs/app_logs/app_run_20261113_100222/app_system.log` proves the prior app session terminated at **`10:05:43.588`** right after emitting **`#5477`**. When the user tapped **Scan & Connect USB radar** at **`10:10:59.285`** (`t = 10.82s` into the session), **38 ms later (`10:10:59.323`)** the CP2105 UART-to-USB bridge delivered those **1,023 stale bytes** sitting in its hardware RX FIFO from 315.7 seconds earlier (`6243 * 50 ms = 312.15 s`), immediately followed 6 ms later (`10:10:59.341`) by live frame **`#11723`**:
   - `[10:10:59.335]` `[ENQUEUE] seq=#5479`
   - `[10:10:59.338]` `RADAR_FRAME_SALVAGED: Frame #5480 salvaged`
   - `[10:10:59.339]` `RADAR_FRAME_DROP: Expected #5481, got #11723 (Gap of 6242 frames)`
   - `[10:10:59.341]` `[ENQUEUE] seq=#11723`
5. **Root Cause in Code:** [`RadarViewModel.scanAndConnect`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt#L799-L806) resets `packetAssembler`, `clockDiscipliner`, and `displayPacer`, **but [`RadarConnectionManager.connectToDevice`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt#L179-L199) never purges the CP2105 hardware RX FIFO (`port.purgeHwBuffers`) or discards stale pre-connect UART bytes** before feeding `rawByteQueue`.

### 1.2 True Wire Continuity After `#11723` (10,630 Frames)
After `#11723`, direct parsing of `radar_frames.bin` across **`N = 10,630` frames (`#11723` to `#22352`)** confirms:
- **Sequence gaps:** **`0`**
- **Disciplined Δt range:** `min = 47.996 ms`, **`mean = 50.001 ms`**, `max = 52.150 ms` (`0` intervals `>55 ms`, `0` intervals `<45 ms`).
- **Specifically around `t = 445s–460s` (`#20406`..`#20705`):** Every single frame arrived continuously on a ~50 ms cadence with `0` worker drops.
- **Physical USB transport behavior:** `analyze_display_pacer_log.py` recorded `USB Arrival Interval: Mean=50.00ms | StdDev=7.59ms | Min=1.30ms | Max=190.46ms (N=10778)` with `2.1%` 70 ms delays and `1.9%` 28 ms bursts, while `audit_cross_sensor_sync.py` measured kernel/user USB pipeline latency `Mean = 0.24 ms, p95 = 13.80 ms, Peak = 33.23 ms`, confirming the 256 KB kernel DMA ring (`32 x 8192 B` buffers) absorbed every USB stall without losing a single wire packet.

### 1.3 Discipliner Anchor Bias & Negative `usbDelayMs`
Inspecting `session_timeline.csv` reveals **`5,853` of `10,632` radar frames (`55.1%`) have negative `usbDelayMs`** down to **`-47.92 ms`** (and `109` frames `>20 ms`, max `33.23 ms`).  
In [`RadarClockDiscipliner.discipline`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/RadarClockDiscipliner.kt#L58-L99), frame `#11723` triggered `reset()` and set `anchorMonoNs = rawHostMonoNs` unconditionally on the single first frame. Because `#11723` happened to arrive late in a USB batching window (~35–48 ms queued delay), all subsequent frames arriving promptly appeared `-10 ms` to `-47.92 ms` earlier than `disciplinedMonoNs`. This also contributed to [`CrossSensorSyncAssociator`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/CrossSensorSyncAssociator.kt#L258-L303) logging `radarCameraProximity` as `mean=13.48 ms, pctCompliant=64.77%, peakMaxMs=139.91 ms` online vs `mean=8.33 ms, 99.91% <= 16.67 ms, Peak=20.92 ms` offline (where offline uses symmetric nearest-neighbor while online searches the causal past ring buffer).

---

## 2. Why UI Still Lagged & Stuttered (Especially Around `t = 452 s`)

Your subjective experience during the drive is **100% corroborated by `UiFrameMetricsProfiler` telemetry in `session_debug.log`**. We did **not** eliminate UI lag in real road usage — yesterday's `p50 = 3.45 ms` reading only reflected a light/stationary desk state without concurrent camera capture, heavy dynamic point clouds, CANedge traffic, or `DisplayPacer` queue/log contention.

### 2.1 Measured `UiPerf` (`[WINDOW_5S]`) Breakdown Across the Entire Run
Threshold for 60 Hz (`MainActivity` enforced `60.0 Hz`) is **`16.67 ms`**:

| Phase / Active Tab | 5s Windows | Jank Rate (`>16.67 ms`) | `p50` Range (Median) | Worst Peak `max` | Mean Radar Objs/Frame |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Pre-Session (`Unknown`, CANedge sync)** | 2 | `100.0%` | `112.6 – 827.4 ms` (`470.0 ms`) | `3793.7 ms` | `0.0` (No Radar!) |
| **Pre-Session (`GNSS`, Camera paused)** | 6 | `68.7%` (`31–100%`) | `13.5 – 59.2 ms` (`27.0 ms`) | `251.2 ms` | `0.0` (No Radar!) |
| **Pre-Session (`Camera` + Calib FS)** | 10 | `93.6%` (`81–100%`) | `23.1 – 64.2 ms` (`33.1 ms`) | `1758.4 ms` | `0.0` (No Radar!) |
| **Recording — `GNSS` Tab** | 4 | `68.6%` (`40–93%`) | `15.1 – 28.1 ms` (`20.2 ms`) | `1561.5 ms` | `18.7` |
| **Recording — `IMU` Tab** | 1 | `68.3%` | `18.3 ms` | `104.5 ms` | `56.9` |
| **Recording — `Radar` Tab** | 55 | `88.9%` (`55–99%`) | **`17.4 – 38.6 ms` (`22.3 ms`)** | `1577.6 ms` | `68.0` |
| **Recording — `SBS` Tab** | 5 | `96.2%` (`81–100%`) | **`28.4 – 44.1 ms` (`31.3 ms`)** | `252.4 ms` | `64.0` |
| **Recording — `Camera` Tab** | 44 | `98.7%` (`80–100%`) | **`21.4 – 83.7 ms` (`45.9 ms`)** | `915.6 ms` | `76.1` |

#### Focus on the `t = 426–472 s` Camera-Tab Collapse (User's `~452 s` Stutter):
At `t = 426.24 s`, you switched from `Radar` (`p50 = 38.6 ms`) to **`Camera`**. Look at what happened immediately:
- `t = 432.1 s`: `frames=118 | jank=100.0% | p50=61.3ms | p95=137.2ms | max=185.8ms` (`avg_objs=46.5`)
- `t = 437.1 s`: `frames=104 | jank=100.0% | p50=73.8ms | p95=131.1ms | max=179.0ms` (`avg_objs=53.0`)
- `t = 442.1 s`: `frames=96  | jank=100.0% | p50=63.5ms | p95=154.8ms | max=311.7ms` (`avg_objs=65.9`)
- `t = 447.1 s`: `frames=101 | jank=100.0% | p50=76.6ms | p95=144.7ms | max=210.8ms` (`avg_objs=79.3`)
- `t = 452.1 s`: `frames=140 | jank=100.0% | p50=56.5ms | p95=107.7ms | max=149.2ms` (`avg_objs=97.4`)
- **`t = 457.2 s`: `frames=89  | jank=100.0% | p50=83.7ms | p95=244.5ms | max=334.6ms` (`avg_objs=118.0, max_objs=166`) — ~17.8 FPS UI cadence!**
- **`t = 547.5 s` (AFTER pressing STOP, `0` radar objects, Camera still previewing):** `frames=168 | jank=100.0% | p50=35.7ms | p95=57.7ms | max=245.4ms`!

Meanwhile, across that entire `426–540 s` window, **the background workers dropped 0 radar frames and 0 camera frames** (`Camera` only had 2 drops in the whole 9 minutes, way earlier at `t = 297.44 s` and `t = 368.33 s`).

---

## 2.2 Six Concrete Root Causes Still Breaking UI Smoothness

1. **Active Camera Preview (`TextureView` + 4 Hz `TextureView.getBitmap(32, 24)` Readback) Exceeds 16.67 ms Alone:**  
   Even with **`0` radar objects** (`PRE_Camera` `10:09:44–49` -> `p50 = 37.2–64.2 ms`; post-stop `t = 547.5 s` -> `p50 = 35.7 ms`), the Camera tab runs at **35–64 ms median**. [`CameraEngine.startLuminanceAnalyzer`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/camera/CameraEngine.kt#L949-L1021) runs every `250 ms` (4 Hz) calling **`textureView.getBitmap(32, 24)`**, forcing synchronous HWUI/GPU `SurfaceTexture` readbacks while Camera2 preview and `MediaRecorder` H.264 encoding compete on the same `SM-M325F` GPU/memory bus.

2. **`ViewfinderRadarOverlay` Projects & Draws Unfiltered Clutter + Allocates Objects Inside `Canvas` on Main Thread:**  
   [`ViewfinderRadarOverlay`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/ViewfinderRadarOverlay.kt#L182-L194) explicitly calls [`SpatialProjectionEngine.calculateRadarLollipops`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/fusion/engine/SpatialProjectionEngine.kt#L419-L605) with **`filterUntrackedClutter = false`** inside the Compose draw phase! At 120–200 raw detections/frame (`t = 452–532 s`), every frame allocates hundreds of `ScreenPoint`/`ProjectedRadarLollipop` objects, 8-segment footprint polygons, and **`Stroke(...)` instances inside the `for (lollipop in lollipops)` loop** ([`ViewfinderRadarOverlay.kt` L261, L301, L308, L350](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/ViewfinderRadarOverlay.kt#L261)), pushing Camera-tab `p50` up to **`83.7 ms` (`p95 = 244.5 ms`)**.

3. **`RadarBevPlot` Still Triggers Self-Invalidation & Per-Loop Allocations (`Radar` Tab `p50 = 22.3 ms`):**  
   [`RadarBevPlot.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt#L251-L353) still calls `egoPath.reset()` (L297) and allocates new `Stroke(width = ...)` objects inside range-ring/cluster loops (L258, L319) plus up to `400+` `drawCircle` calls at 200 raw points. Additionally, [`RadarDashboardCard.kt` L133](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/RadarDashboardCard.kt#L133) collects 5 Hz `radarUiTelemetry` and [`TopSessionHeader.kt` L32](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/TopSessionHeader.kt#L32) collects 4 Hz `sessionRecordingState` updates continuously in the live tree (while [`KeepAliveTabSlot`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt#L248-L266) keeps all 7 tabs composed in memory).

4. **`FullscreenCalibrationStudio` Root Recomposition at IMU Rate:**  
   [`FullscreenCalibrationStudio.kt` L85](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenCalibrationStudio.kt#L85) collects `imuTelemetry` in the root composable while `LiveImuHorizon` is enabled, causing pre-session calibration preview to sit at **`p50 = 23.1–51.4 ms` (`87–100%` jank)** with **zero radar connected**.

5. **Severe `AppLogger` Lock Contention & Synchronous Flush Spam (80+ Flushes/Sec):**  
   [`RadarDisplayPacer`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/RadarDisplayPacer.kt#L65) logs `[ENQUEUE]` on **`RadarProcessingWorker`** AND `[EMIT]` on **`Dispatchers.Default`** at **40 lines/sec** (`21,553` of `21,920` lines = **`98.3%`** of `session_debug.log`). Inside [`AppLogger.log`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/logging/AppLogger.kt#L192-L216), **every single log call acquires a global `synchronized(lock)` and calls `writer.flush()` twice** (once on `app_system.log`, once on `session_debug.log`). Under concurrent H.264 video, 200 Hz IMU CSV, binary radar I/O, and CANedge HTTP staging, synchronous flash flushes stall caller threads (including `RadarProcessingWorker`, `DisplayPacer`, `main`, and `UiFrameMetricsProfiler`).

6. **`RadarDisplayPacer` Wakeup Jitter (`122.24 ms`), 10 UI Drops & 250 ms Permanent Bufferbloat:**  
   `RadarDisplayPacer` runs on unprioritized `Dispatchers.Default`, where `delay()` wakeup jitter reached **`122.24 ms`** at `10:15:23.979` (`t = 275.5 s`, dropping frames `#17005–#17008`) alongside 6 other drops around `t = 220–281 s`. Worse, [`RadarDisplayPacer.kt` L115–L128](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/RadarDisplayPacer.kt#L115-L128) only prunes until `queue.size <= maxQueueDepth (6)` and then emits `1` frame every `50 ms` forever (`20 Hz` in = `20 Hz` out). Once `qDepth` rose to `5–6` at `t = 220 s`, **it stayed permanently locked at `qDepth = 5` (`250 ms` visual lag behind camera) for the remaining 4.5+ minutes until stop at `10:19:58`!**

---

## 3. Instrumentation & Tooling Blind Spots Found

| Tool / File | Gap or Misleading Output Observed on `session_20261113_101048` |
| :--- | :--- |
| [`UiFrameMetricsProfiler.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/diagnostics/UiFrameMetricsProfiler.kt#L64-L89) | `[JANK_FRAME]` (`anim`/`layout`/`draw`/`sync`/`gpu`/`input` breakdown for frames `>24 ms`) is logged **only to Logcat (`Log.w`)**, NOT `AppLogger` — hiding GPU/RenderThread vs UI-draw attribution in offline `session_debug.log` audits. |
| [`scripts/analyze_interframe_timing.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/analyze_interframe_timing.py#L5) | Hardcodes `SESSION_DIR = ...session_20261105_125830` and ignores CLI arguments. |
| [`scripts/analyze_display_pacer_log.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/analyze_display_pacer_log.py) | Hardcodes a `90.0 Hz` grid claim (app enforced `60.0 Hz`), blames `Dispatchers.Main` when `DisplayPacer` actually runs on `Dispatchers.Default`, claims `maxQueueDepth=6` is "too shallow" when it actually creates `250 ms` sustained bufferbloat, and prints `No samples` for BEV/SBS canvases. |
| [`scripts/verify_raw_data_parity.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/verify_raw_data_parity.py#L370) | Checks only `b'MDF     '` magic (falsely failing `UnFinMF ` CANedge2 files `0/3`), and fails `session_timeline.csv` monotonicity due to cross-thread CSV write order (`8,591` cross-sensor interleavings) + `1` stale radar flush (`#5480->#11723`) + `3` coarse (`~69–73 m`) Android network/fused GNSS fixes (`-267 ms` to `-474 ms` behind GPS). |
| [`scripts/session_health_check.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/session_health_check.py) | Reports `Warnings Logged: 1` despite `10` `[WARN ] [DisplayPacer] [DROP]` lines and `1` `[WARN ] [RadarSession]` line (`11` total warnings in `session_debug.log`). |
| [`app/build.gradle.kts`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/build.gradle.kts) | App runs as a debuggable `debug` APK (`assembleDebug`), amplifying ART debug overhead under full road multi-sensor contention (a `staging` non-debuggable buildType exists and should be used when benchmarking true release runtime). |

---

## 4. Recommended Next Priorities (Ranked by Impact)

1. **Stop `AppLogger` per-line flush & `DisplayPacer` 40 Hz debug spam:** Gate `[ENQUEUE]`/`[EMIT]` verbose logging behind a diagnostic flag and decouple `AppLogger` disk writes onto a dedicated batched writer thread (flushing once per 250–500 ms or on `WARN`/`ERROR`), eliminating `AppLogger.lock` contention on `RadarProcessingWorker` and `main`.
2. **Replace `TextureView.getBitmap(32, 24)` in `CameraEngine` & fix `ViewfinderRadarOverlay` overload:** Compute Road AE luminance without GPU `TextureView.getBitmap()` blocking readbacks (e.g., AE metering regions or low-res Y-plane `ImageReader` only if HAL supports, or hardware CaptureResult AE/luma metadata), enable/optimize clutter filtering or spatial culling + pre-allocated `Stroke` objects in `ViewfinderRadarOverlay`, and hoist `Stroke` / remove `egoPath.reset()` per-frame mutation in `RadarBevPlot`.
3. **Fix `RadarConnectionManager` stale UART buffer + `RadarDisplayPacer` bufferbloat:** Call `port.purgeHwBuffers(true, false)` (and/or drain initial RX bytes for ~20 ms) before attaching `SerialInputOutputManager` on connect; move `RadarDisplayPacer` to a dedicated prioritized thread and target `queue.size <= 1–2` (catching up smoothly instead of holding a permanent `250 ms` `qDepth=5` lag).
4. **Log `[JANK_FRAME]` phase breakdowns (aggregated or throttled) into `AppLogger`** and validate UI frame times on the **non-debuggable `staging` buildType** under live Camera + 200-object Radar road load rather than stationary desk tests.

