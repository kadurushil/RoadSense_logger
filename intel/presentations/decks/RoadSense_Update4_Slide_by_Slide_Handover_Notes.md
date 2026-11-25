# RoadSense Update 4 Presentation: Slide-by-Slide Handover & Speaker Notes

> **Document Name:** `RoadSense_Update4_Slide_by_Slide_Handover_Notes.md`  
> **Workspace Location:** `intel/presentations/decks/RoadSense_Update4_Slide_by_Slide_Handover_Notes.md`  
> **Target Deck:** [`RoadSense_Update4_Executive_Progress.pptx`](./RoadSense_Update4_Executive_Progress.pptx)  
> **Coverage Window:** October 23 – November 23, 2026 (`50305c0` $\rightarrow$ `eb8adc5`: **91 commits**, **368 files changed**, **+44,780 / -6,242 lines**)  
> **Audience:** Engineering Executives, ADAS Leads, Embedded & Perception Engineers  
> **Last Updated:** November 23, 2026

---

## Table of Contents
1. [How to Insert Native Equations (`Alt + =` / OMML) & Screenshots](#how-to-insert-native-equations-alt---omml--screenshots)
2. [Slide 01: Title Slide (Update 4 Monthly Briefing)](#slide-01-title-slide-update-4-monthly-briefing)
3. [Slide 02: One-Month Executive Summary (91 Commits)](#slide-02-one-month-executive-summary-91-commits)
4. [Slide 03: 3.125 Mbps Radar Pipeline & Clock Discipline](#slide-03-3125-mbps-radar-pipeline--clock-discipline)
5. [Slide 04: 60 FPS Compose UI & `AutoFitSurfaceView` Spotlight](#slide-04-60-fps-compose-ui--autofitsurfaceview-spotlight)
6. [Slide 05: Immersive SBS, ISP Parity, Traffic HUD & GNSS/IMU Fusion](#slide-05-immersive-sbs-isp-parity-traffic-hud--gnssimu-fusion)
7. [Slide 06: Autonomous CANedge2 Logging, Tier-1/Tier-2 Sync & Improvements](#slide-06-autonomous-canedge2-logging-tier-1tier-2-sync--improvements)
8. [Slide 07: Vision-vs-Radar Benchmark Overhaul & `DynamicPitchSolver`](#slide-07-vision-vs-radar-benchmark-overhaul--dynamicpitchsolver)
9. [Slide 08: Root Cause Analysis Part 1 — Bugs #26 Through #31](#slide-08-root-cause-analysis-part-1--bugs-26-through-31)
10. [Slide 09: Root Cause Analysis Part 2 — Bugs #32–#35 & Pipeline Fixes](#slide-09-root-cause-analysis-part-2--bugs-3235--pipeline-fixes)
11. [Slide 10: 100.0% MCAP Parity, Impact Scorecard & Test Gates](#slide-10-1000-mcap-parity-impact-scorecard--test-gates)
12. [Slide 11: Update 5 Roadmap — Physical Hard Mounting, DSP Qualification & `>66%` Gate](#slide-11-update-5-roadmap--physical-hard-mounting-dsp-qualification--66-gate)
13. [Boardroom Q&A Cheat Sheet](#boardroom-qa-cheat-sheet)

---

## How to Insert Native Equations (`Alt + =` / OMML) & Screenshots

1. **Screenshot Placeholders (3 Dashed Boxes Left Intentionally Blank):**
   - **Slide 05 (`PHONE SCREENSHOT SLOT`):** Drop in a phone screenshot from `SM-M325F` showing **Immersive Fullscreen SBS**, the `±60°` BEV overlay (`100 m / 150 m`), and the live **5-pill Traffic-Light Readiness HUD (`CAN`, `CAM`, `RAD`, `GNSS`, `IMU`)**.
   - **Slide 07 (`DASHBOARD SCREENSHOT SLOT`):** Drop in your Web Dashboard ANSI-highlighted Vision Benchmark + `DynamicPitchSolver` screenshot (`session_20261124_120846` Pass 1 `8.997°` vs Ideal `7.550°` Pass 2 hitting `45.7%`).
   - **Slide 10 (`FOXGLOVE SCREENSHOT SLOT`):** Drop in a Foxglove Studio 3D viewport capture showing synchronized `/radar/points`, TLV 2 `/radar/clusters`, `/vision/geo_tracks`, `/vision/camera_frustum`, `/can/*` signals, and upright `cy = 360` alignment.
2. **Native Equations (`Alt + =` / OMML):**
   - Click inside the dashed `[ NATIVE EQUATION SLOT ]` on **Slide 05** or **Slide 07**, delete the placeholder text, press **`Alt + =`** to open a native PowerPoint equation box, paste the LaTeX string below (one equation per line), and press `Enter`.

---

## Slide 01: Title Slide (Update 4 Monthly Briefing)

### Executive Pitch
> "Since Update 3 on October 23, we executed 91 commits touching 368 files across the Android data acquisition app and the entire PC verification stack. This past month transformed RoadSense from an experimental prototype into a deterministic, production-grade logging and validation platform: zero-loss 3.125 Mbps radar capture with 0.21 ms hardware clock discipline, sub-millisecond 60 FPS SurfaceView UI rendering, autonomous end-to-end CAN logging and Tier-1/Tier-2 synchronization, 100.0% verified Foxglove MCAP parity, and automated session-wide camera pitch calibration."

---

## Slide 02: One-Month Executive Summary (91 Commits)

### Executive Pitch
> "Update 4 rests on four hard-measured engineering pillars. First, we eradicated high-speed USB radar drops (`7.67%` down to `0.00%`) and compressed live timing jitter 41-fold. Second, we eliminated Compose UI jank completely, dropping viewfinder and radar draw durations from over 10 ms down under 0.4 ms while adding Immersive SBS and a 99.7% drift-free EKF-ZUPT IMU pipeline. Third, CANedge2 Wi-Fi logging and modular Python processing now run end-to-end across 77 CAN topics. Fourth, we achieved 100.0% multi-sensor MCAP parity and eliminated benchmark center/clutter biases via our session-wide pitch solver."

---

## Slide 03: 3.125 Mbps Radar Pipeline & Clock Discipline

### Executive Pitch
> "Our first major breakthrough happened in the hardware radar pipeline. Look at the Left-to-Right flow on the top right: at 3.125 Mbps, the Silicon Labs CP2105 bridge only holds 576 bytes — giving the phone just 1.84 milliseconds to read before hardware overflow. Perfetto proved Camera HAL threads stalled reads for 5.18 ms, causing cascading 'Frankenstein' frame corruption. By promoting our USB reader pre-launch to `URGENT_AUDIO`, enabling a 128 KB asynchronous kernel DMA queue (409.6 ms buffer), and adding magic-word frame salvaging, corruption dropped to 0.00%. Below that, you see real 40,023-interval drive data from November 20: 52% of frames lacked USB short packets due to 64-byte multiples, creating wild bimodal timing spikes. Locking onto the radar DSP's 600 MHz hardware cycle counter crushed live timing jitter 41x — from 8.73 ms down to 0.21 ms."

### Talking Points (Visual / Non-Formula Presentation)
* Emphasize the **left-to-right physical bottleneck** (`576 B` bridge FIFO $\rightarrow$ `1.84 ms` wall vs `5.18 ms` OS stall) resolved by widening the buffer upstream in Linux DMA (`128 × 1 KB = 128 KB` $\rightarrow$ `409.6 ms` headroom).
* Point directly at the live chart (`session_20261120_112453`, Frames `17880..17999`): red raw USB arrival bounces wildly between `15 ms` and `75 ms`, while the blue `600 MHz` disciplined timeline stays razor-flat inside the `45 ms` chirp band (`std = 0.21 ms`). Both `rawMonoNs` and `usbDelayMs` remain logged so real USB stalls stay observable.

---

## Slide 04: 60 FPS Compose UI & `AutoFitSurfaceView` Spotlight

### Executive Pitch
> "Slide 4 highlights one of our biggest UX wins of the month: cutting UI draw latency on our mid-range Galaxy M32 test phone from 10 to 64 milliseconds down under 1 millisecond. First, Perfetto exposed a silent build sabotage in `build.gradle.kts` overwriting fresh APKs with a stale November 6 binary. Next, we systematically eliminated five Compose stutter culprits: mutating `Path()` objects triggering an 86.3 FPS idle redraw loop, 18 discrete `Text` composables triggering Samsung accessibility Binder IPC stalls (`64.4 ms -> 0.28 ms`), cold tab subcomposition freezes (`385 ms -> 55 ms`), and `TextureView` GPU copy restarts (`1.48 s` freezes replaced by zero-restart hardware-overlay `AutoFitSurfaceView` with `Yellow STBY`). Everything now renders comfortably under our 16.67 ms 60 FPS budget — with `DRAW_BEV` at 0.36 ms and `DRAW_OVERLAY` at 0.38 ms."

### Quantitative Proof (`SM-M325F`)
* **1 Hz Metrics HUD Tick:** `26.6–64.4 ms` $\rightarrow$ **`0.28 ms`** (**`-99.5%`**, `0.00 ms` semantics overhead)
* **`DRAW_BEV` (Radar Scope):** `7.43–8.57 ms` $\rightarrow$ **`0.36–1.10 ms`** (**`-87%` to `-95.8%`**)
* **`DRAW_OVERLAY` (Viewfinder):** `10.26–14.45 ms` $\rightarrow$ **`0.38–0.41 ms`** (**`-95%` to `-97.4%`**, 1:1 draw deduplication)
* **SBS GPU P95 Latency:** `21.84–33.65 ms` $\rightarrow$ **`7.27–8.83 ms`**
* **Fullscreen Enter/Exit:** `1,315–1,478 ms` freeze $\rightarrow$ **`0` camera restarts**

---

## Slide 05: Immersive SBS, ISP Parity, Traffic HUD & GNSS/IMU Fusion

### Executive Pitch
> "We unified four cockpit and sensor-state breakthroughs into a single cohesive driver experience. In UI and geometry, Immersive Fullscreen SBS now clamps `±60°` BEV lateral span cleanly while a 16:9 active-array ISP crop fix eliminated a 120-pixel (`7.5°`) Foxglove vertical shift on Galaxy M32. Across health telemetry, our 5-sensor traffic-light pill bar gives instant `CAN/CAM/RAD/GNSS/IMU` confidence alongside true 200 Hz IMU rate reporting and isolated GPS-vs-network streams (`100%` of coarse jump artifacts removed). Finally, our modular `tools/imu/` body-frame pipeline centers vehicle pitch at `0.26°` and runs a 2-state EKF with Zero-Velocity Updates, cutting 39-second red-light yaw drift from `-56.29°` down to `+0.18°` — a 99.7% reduction."

### Native Equations for Slot 1 (`Alt + =` / OMML)
Paste these cleanly inside **Native Equation Slot 1**:

```latex
R_{base \leftarrow phone} = (R_z(\phi_{calib}) R_x(\theta_{calib}) R_y(\psi_{calib}) R_0)^T R_{opt \leftarrow phone}
```
```latex
x_k = [\psi_k,\ b_{z,k}]^T,\quad \psi_k = \psi_{k-1} + (-g_{z,k} - b_z)\Delta t
```
*(ZUPT Bias Update: $z_{\text{ZUPT}} = -g_{z,k},\ \mathbf{H}=[0,\ 1]$ when stationary; GPS Course Update: $z_{\text{GPS}} = \psi_{\text{doppler}},\ \mathbf{H}=[1,\ 0]$ when $v > 1.5\text{ m/s}$.)*

---

## Slide 06: Autonomous CANedge2 Logging, Tier-1/Tier-2 Sync & Improvements

### Executive Pitch
> "CANedge2 integration now works cleanly from vehicle Wi-Fi pull all the way to Foxglove `/can/*` topics. On the phone, a 1-second background watchdog inspects 256-byte MDF4 headers directly (`UnFinMF`), deduplicates staged files across pools to stop 51 MB thrashing, enforces a `CALIBRATING` HUD gate, and fixes a subtle `+60 s` live rollover clock bug (`329429c`). On the PC, our modular `tools/CAN_Processing/` suite emits 77 `/can/*` signals (`1.58M` messages) aligned by Tier-1 `FrameNumber_Act` (`-0.08 ms` median delta, shrinking sync spread `220 -> 38.9 ms`) and Tier-2 FFT IMU gyro correlation (`0.998` correlation on zero-radar drives). Looking ahead, we highlight three clean CAN improvements: source-side NTP/GPS-PPS RTC locking, live bus-off/error-frame alerts in the Android CAN pill, and automatic DBC/VIN binding."

---

## Slide 07: Vision-vs-Radar Benchmark Overhaul & `DynamicPitchSolver`

### Executive Pitch
> "Earlier radar-vision benchmarks understated real hardware alignment due to three methodological flaws: comparing radar bumper reflections against the center of 3D vehicle boxes (`+length/2` bias), dropping valid nearby matches under greedy 1-to-1 assignment, and letting a ~1.5-degree windshield mount mismatch erode medium-range overlap. Switching to front ground-contact patch anchoring, nearest-radius existence with `0–50 m` unrestricted lateral evaluation, and integrating `DynamicPitchSolver` fixed this. On our latest validated-DSP drive (`session_20261124_120846`, GOLD sync, 9,279 radar / 13,900 camera frames, 0 drops), the solver detected that configured pitch was `1.447°` too steep (`8.997° -> 7.550°`), lifting overall `0–50 m` association from `36.0%` to **`45.7%`** (`62.5%` at `0–15 m`, **`39.9%`** at `15–30 m` — a **`3.35×` jump**, `76.2%` on motorcycles, `49.6%` on trucks), cutting Fwd MAE from `1.496 m` down to **`1.209 m`**, and slashing Mutual-NN bias `7.6×` (`-0.887 -> -0.117 m`)."

### Native Equations for Slot 2 (`Alt + =` / OMML)
Paste these inside **Native Equation Slot 2**:

```latex
\theta^* = \arg\min_{\theta} \left[ \frac{1}{N}\sum_{i=1}^{N} \rho_\delta\!\left(\left\|\mathbf{p}_{v,i}(\theta) - \mathbf{p}_{r,i}\right\|_2\right) + \lambda \left|\operatorname{median}_i\!\left(x_{v,i}(\theta) - x_{r,i}\right)\right| \right]
```
*(Evaluated over Mutual Nearest Neighbors at $r_{\text{fwd}} \ge 4.0\text{ m}$ across all object classes, optimized via Golden-Section search.)*

---

## Slide 08: Root Cause Analysis Part 1 — Bugs #26 Through #31
* Concise executive framing of the 6 core runtime/physics bugs resolved in weeks 1–3 (`#26` `0°-Yaw Squeeze Revert`, `#27` `Cross-Axis IMU Yaw Leakage & Hermite Soft Knee`, `#28` `CP2105 1.84 ms FIFO & Frankenstein Frames`, `#29` `Exynos 5.18 ms Stall & -4% Baud`, `#30` `USB 64B Coalescing`, `#31` `90 Hz Path Invalidation + Stale Gradle APK Hook`).

---

## Slide 09: Root Cause Analysis Part 2 — Bugs #32–#35 & Pipeline Fixes
* Concise completion of hardware/tooling stability fixes (`#32` `Display Roll Sign Inversion`, `#33` `SBS BEV Lateral Clipping`, `#34` `Missing MCAP Video w/o CSV`, `#35` `SM-M325F cy=480 16:9 ISP Crop`, `CANedge +60 s Rollover & IMU /4 Rate Illusion`, and `Benchmark Center Bias + Trailing MP4 Extrapolation + UTF-8 Pipe`).

---

## Slide 10: 100.0% MCAP Parity, Impact Scorecard & Test Gates

### Executive Pitch
> "Slide 10 brings together Foxglove data integrity and our full monthly impact scorecard. Our automated 8-category parity validator lifted MCAP conversion accuracy from 58.0% to a verified 100.0% across all 23,340 frames — adding TLV 6 FCW, TLV 5 17 CAN telemetry channels, TLV 2 clusters, 5 enriched `/vision/*` topics, binary Protobuf IMU, atomic writer closing, and trailing MP4 timestamp extrapolation. Across the stack, corruption dropped to zero, live radar jitter improved 41x, UI draw overhead fell over 95%, yaw drift was eliminated by 99.7%, CAN spread collapsed 5.7x, and the codebase now passes over 200 automated tests across Android and Python."

---

## Slide 11: Update 5 Roadmap — Physical Hard Mounting, DSP Qualification & `>66%` Gate

### Executive Pitch
> "Our final slide frames the clear, disciplined path forward for Update 5. We have proven that our software pipeline, USB acquisition, clocking, UI, CAN alignment, MCAP serialization, and validated radar DSP are solid—already pushing overall `0–50 m` association past 45% (`45.7%`, with `62.5%` under 15 m and `76.2%` on motorcycles). What remains our #1 bottleneck is physical hard mounting: flexible windshield mounts introduced a `1.447°` pitch offset on `session_20261124_120846` (`8.997° -> 7.550°`) that alone cut `15–30 m` matching from `39.9%` down to `11.9%`. Our #1 priority is rigid machined hard mounting, followed by locking this validated DSP baseline with 200 Hz IMU/CAN dynamic pitch-bounce compensation, and automated pitch auto-trim toward our >66% sustained association gate."

---

## Boardroom Q&A Cheat Sheet

### Q1: "We hit 45.7% overall (and 62.5% within 15 m)—what closes the remaining gap to >66%?"
* **Answer:** "Two concrete physics factors. First, `session_20261124_120846` proved that just `1.45°` of flexible windshield mount shift dropped `15–30 m` association from `39.9%` to `11.9%` (`3.35×` loss); rigid machined hard mounts lock static extrinsics permanently. Second, dynamic suspension pitch bounce over bumps and braking still shifts monocular ground rays by 2–4 meters per frame (`dX/dθ ≈ -X²/h`). Combining hard mounts with 200 Hz IMU/CAN dynamic pitch-bounce compensation directly bridges the gap from `45.7%` past our `>66%` gate."

### Q2: "What happens if radar is disconnected on a drive—does CAN alignment fail?"
* **Answer:** "No. Under commit `d2265c8`, `tools/CAN_Processing/` automatically falls back from Tier-1 radar `FrameNumber_Act` sync to Tier-2 dual-anchor FFT (`rfft`) cross-correlation between CAN steering/IMU yaw rate and phone IMU `gz`, achieving `0.998` correlation (`4.2σ` SNR) and even recovering 57.6 seconds of stale session metadata."

### Q3: "How do we know the Android UI optimization actually persists and isn't regression-prone?"
* **Answer:** "We removed the stale APK copy trap in Gradle, enabled `UiFrameMetricsProfiler` using Android's native `Window.OnFrameMetricsAvailableListener` window summaries (`p50`, `p95`), and backed all sync/projection/HUD evaluators with 125 Android JVM unit tests."
