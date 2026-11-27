# Walkthrough 116 — Foxglove-Style BEV Panel Settings, Adaptive SNR, Vertical Stixels & TLV 4 Road Lanes

**Status:** Implemented & Verified (APK Build + JVM Unit Tests + Python Profiler Tests Passing)  
**Primary Component Package:** `com.bajajauto.roadsense.ui.components.gl` & `com.bajajauto.roadsense.ui.components`

---

## 1. Summary of Changes

Building directly on the Tier C OpenGL ES 3.0 2D/3D BEV engine headroom (`20.1 FPS` at `P50 = 1.09 ms`, `P95 = 1.82 ms`, `0` `DisplayPacer` drops on `SM-M325F`), this milestone adds a persistent, Foxglove-inspired 2D/3D point-cloud and overlay control suite plus full architectural documentation.

### 1.1 Bottom Footer Re-Mapping, Fullscreen Mode & Sleek Top-Edge Legend
* **`RadarBevPlot.kt` (`Col:<mode>` cycler):** In OpenGL ES 3.0 mode, the leftmost footer chip now cycles point coloring modes (`Col:Auto -> Vel -> Rng -> SNR -> Elev -> In/Out -> Clst -> Auto`) while preserving `Moving` inside the Skia fallback and inside the GL `Bench` drawer.
* **Fullscreen Radar BEV (`FullscreenRadarBevPreview.kt` + `RadarDashboardCard.kt` + `MainActivity.kt`):** Added a fullscreen expand button in both landscape and portrait Radar headers launching an immersive edge-to-edge Radar BEV view with `TopEnd` floating controls (`EXIT`, Range, `Col`, `2D/3D`, `Reset`, `Tune`, Bookmark & `REC`) and the full 2-row Foxglove/Layer/Camera settings drawer.
* **`BevPointColorMode` (`BevPanelSettings.kt`):**
  * Initial mode is **`AUTO`** (`2D` resolves to Doppler Velocity; `3D` resolves to Longitudinal Range).
  * Selecting any explicit mode overrides **both** 2D and 3D views consistently across tab switches and restarts (`AppPreferences`).
* **Sleek 1-Row Colormap Legend (`RadarBevPlot.kt`):** Condensed the top-left legend into a single horizontal bar (`■FCW ■ACC ■Trk | <Map>: <min> [live gradient] <max>`) whose label and gradient stops (`BevPanelColorizer.evalColorMapStopsArgb`) update dynamically whenever the active colormap (`Turbo` / `Rain` / `Clsc`) or color mode changes.

### 1.2 Smart Adaptive SNR, `FlipZ`, Expanded Elevation Range & Outlier Coloring
* **`BevPanelColorizer.computeSmartSnrBounds(...)` (`BevPanelSettings.kt` + `BevGlVertexPacker.kt`):**
  * Eliminates fixed `5..35 dB` clipping when radar detections fall below `5 dB` or above `35 dB`.
  * Runs a zero-allocation `O(N)` pass over active visible frames to track observed min/max SNR, applying an asymmetric EMA smoothing filter with a **`>= 10.0 dB` span floor** so single reflectors never cause sudden flicker while keeping sub-5 dB and >35 dB returns spread smoothly across the colormap.
* **Temporary `FlipZ ON/OFF` Toggle & Wide `±20 m` Elevation Band:** Added `bevFlipZ` across `AppPreferences`, `DebugToggles`, `RadarViewModel`, `BevGlVertexPacker` (negates `pt.z` for 3D vertex placement, Elevation color scalar, and Vertical Stixel endpoints when enabled), plus expanded Elevation Z bounds from `[-1, +5] m` to **`[-20 m, +20 m]`** centered at `0 m`.
* **`OUTLIER` (`In/Out`) Coloring + `HideOut` Filter:**
  * Dedicated scheme (`SCHEME_OUTLIER`) paints RANSAC inliers emerald-cyan and `pt.isOutlier == true` reflections coral-red in real time, complemented by a `HideOut ON/OFF` clutter toggle in the `Bench` drawer.
* **Cluster Color & 2D Visibility (`#00E676`):** Switched 2D GL cluster rings, 3D cluster hexagons, Skia Canvas cluster circles, and legend dots from yellow to green (`#00E676`), and enlarged 2D cluster rings from `8 px` to an **`18 px` outer halo** so `TLV 2` clusters stay visible around co-located `14 px` tracks. Confirmed against recorded `session_20261127_150449/radar/radar_frames.bin` (`32,156 / 32,156` points have firmware `TLV 1 clusterID = 0` while `TLV 2` clusters emit cleanly on `165` frames).
* **Fullscreen 2D `Reset` Forwarding (`RadarBevPlot`, `FullscreenRadarBevPreview`, `FullscreenSbsPreview`):** Wired `onCameraModifiedChanged` through `showChrome = false` so the `Reset` chip appears automatically on 2D pan/pinch-zoom as well as 3D orbit.
* **Cluster ID Noise Sentinel (`255`):** Updated `BevPanelColorizer.normalizeScalar(CLUSTER_ID)` to treat both `0` and `255` (`0xFF`) as unclustered noise. Confirmed `RadarTlvDecoder` (`TLV 1` 12-byte `clusterId` and `TLV 2` 8/10/16-byte clustering) is intact.

### 1.3 GLSL `foxgloveTurbo` Shader, Shape, Size, Stixels, Decay & `±1.5 m` FCW + TLV 4 Lanes
* **`BevGlShaders.kt` (`POINT_VERTEX_SHADER` / `POINT_FRAGMENT_SHADER`):**
  * Evaluates **`foxgloveTurbo`** (5th-degree polynomial matching Foxglove `colorMode.ts`), **`Rainbow`** (`HSV`), **`Classic`**, **Doppler (`#FF5252`/`#40C4FF`/`#69F0AE`)**, **Cluster Categorical**, and **Inlier/Outlier** directly in the GPU vertex shader via uniform `uColorScheme`.
  * Adds `uPointShape` (`0 = Circle ●`, `1 = Square ■`) and `S / M / L` point size scaling (`0.78x / 1.0x / 1.32x`).
* **3D Vertical Stixels (`BevGlVertexPacker.packPoints3D`):**
  * Emits translucent color-matched `GL_LINES` tethers from the `z = 0.04 m` ground plane up/down to each newest-frame 3D detection `(x, y, z)` (`|z| >= 0.15 m`), toggleable via `Stix ON/OFF`.
* **FCW `±1.5 m` Corridor + TLV 4 Road Boundaries (`Lanes` toggle):**
  * Renders blue/cyan FCW corridor rails at exact **`±1.5 m`** from road center alongside live firmware `TLV 4` `roadBoundaryLeftX` / `roadBoundaryRightX` green lines (`#76FF03`) directly on both the 2D plane and 3D `z = 0` plane (`Lanes ON/OFF`).
* **Configurable Decay Ring Buffer (`BevDecayPreset`):**
  * Expanded `BevGlVertexPacker` ring buffer to `MAX_TRAIL_FRAMES = 20` (`Off = 1`, `0.2s = 4`, `0.5s = 10`, `1.0s = 20` frames) with smooth quadratic alpha fade.
* **3D Camera Angle Presets (`BevCameraController.applyPreset`):**
  * One-tap **`Cam:Chase`** (`20°`), **`Cam:Iso`** (`-38°` az, `38°` pitch), **`Cam:Top`** (`80°`), and **`Cam:Low`** (`7°`) buttons in the `Bench` drawer.

---

## 2. Modified & Added Files

* **New Files:**
  * [`BevPanelSettings.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevPanelSettings.kt)
  * [`BevPanelSettingsTest.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/ui/components/gl/BevPanelSettingsTest.kt)
* **Modified Code:**
  * [`BevGlShaders.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlShaders.kt)
  * [`BevGlVertexPacker.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlVertexPacker.kt)
  * [`BevCameraController.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevCameraController.kt)
  * [`RadarGlBevRenderer.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/RadarGlBevRenderer.kt)
  * [`RadarGlBevView.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/RadarGlBevView.kt)
  * [`RadarBevPlot.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt)
  * [`FullscreenRadarBevPreview.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenRadarBevPreview.kt) *(NEW)*
  * [`FullscreenSbsPreview.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenSbsPreview.kt)
  * [`RadarDashboardCard.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/RadarDashboardCard.kt)
  * [`MainActivity.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt)
  * [`DebugToggles.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/DebugToggles.kt)
  * [`AppPreferences.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/storage/AppPreferences.kt)
  * [`RadarViewModel.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)
  * [`BevGlVertexPackerTest.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/ui/components/gl/BevGlVertexPackerTest.kt), [`BevCameraControllerTest.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/ui/components/gl/BevCameraControllerTest.kt), [`DebugTogglesTest.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/DebugTogglesTest.kt)
* **Knowledge Base & Context Docs Updated:**
  * [`debugging_and_troubleshooting_knowledge_base.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/debugging_and_troubleshooting_knowledge_base.md) (`Bug #38` & `Engineering Rules 44–47`)
  * [`00_MASTER_EXECUTIVE_HANDOVER.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/context/00_MASTER_EXECUTIVE_HANDOVER.md)
  * [`01_ARCHITECTURE_AND_STORAGE.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/context/01_ARCHITECTURE_AND_STORAGE.md)
  * [`07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/context/07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md)

---

## 3. Build & Verification Results

Executed cleanly after the final code edit:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew assembleDebug testDebugUnitTest --no-daemon "-Dorg.gradle.java.home=$env:JAVA_HOME"
python -m unittest discover -s scripts/tests -p "test_profile_ui_perf.py" -v
```
* **Gradle Build & JVM Unit Tests:** `BUILD SUCCESSFUL in 34s` (0 compilation/test failures across all JVM tests including `BevPanelSettingsTest`, `BevGlVertexPackerTest`, `BevCameraControllerTest`, `DebugTogglesTest`).
* **Python Script Tests:** `Ran 3 tests in 0.004s` — `OK`.
* **Built Debug APK:** [`app-debug.apk`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/build/outputs/apk/debug/app-debug.apk) ready for manual device installation and validation.
