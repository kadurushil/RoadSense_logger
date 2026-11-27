# Walkthrough: Tier C — Native OpenGL ES 3.0 2D & 3D BEV Renderer (`Plan 115`)

## 1. Phase 1 — Native OpenGL ES 3.0 2D BEV Renderer (Verified on `SM-M325F`)

- Implemented zero-dependency OpenGL ES 3.0 (`GLSurfaceView` + `GLES30`) renderer alongside the existing Tier A Skia Canvas backend, sharing all TLV decoding, projection math ([BevProjectionMath.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevProjectionMath.kt)), and ADAS FCW/ACC track-association logic ([BevTrackAssociator.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevTrackAssociator.kt)).
- Wired direct push frame delivery via `RadarDisplayPacer.addFrameListener(...)` ([RadarDisplayPacer.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/sync/RadarDisplayPacer.kt)) and decoupled `SbsDashboardCard` / `FullscreenSbsPreview` from top-level `latestFrame.collectAsState()`.
- Added a 1-tap mutually exclusive **`GL` / `Skia`** toggle pill right below the BEV plot ([RadarBevPlot.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt)) and in **Storage → Run Metadata & Field Toggles** ([SessionDeckCard.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/screens/SessionDeckCard.kt)).
- **Verified On-Device (`SM-M325F`, Mali-G52 MC2, Debug):**
  - `GL ES 3.0 BEV`: **`20.1–20.4 FPS`**, **`P50 = 1.09 ms`**, **`P95 = 1.82 ms`**, **`0` DisplayPacer Drops**, **`0.0` `RadarBevPlot` recompositions**.
  - Compose App Window redraws dropped **~65%** (`~110` → `37–40` frames/5 s); Fullscreen SBS with Camera + GL BEV + HUD all active hit **`P95 = 15.96–16.38 ms` (`3–5%` jank)** in Debug mode.

---

## 2. Phase 2 — Interactive 3D Perspective Orbit Mode, Longitudinal Distance Coloring & Controls

- [BevCameraController.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevCameraController.kt): Lock-free `BevCameraState` + pure-Kotlin 3D perspective/lookAt matrix math (`+X` right, `+Y` forward, `+Z` up) & 3D-to-screen billboard projection (`projectWorldToScreen`). Supports 1-finger 3D orbit, 2-finger ground-plane pan, pinch-to-zoom, 2D zoom/pan, and double-tap / button reset.
- [BevGlShaders.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlShaders.kt): Added `GRID_3D` ground plane (`z = 0`, `fwidth()` AA polar arcs, FOV rays, Cartesian grid & horizon fade), 5-stop **Longitudinal Distance Color Ramp** in 3D (`#FF2E2E → #FFAB00 → #00E676 → #00E5FF → #448AFF`), perspective-attenuated point sizing (+15% size: `9.2 px` 2D / `9.8 px` base 3D), and 3D wireframe/solid shading.
- [BevGlVertexPacker.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlVertexPacker.kt): Added `packFrame3D(...)` packing unclamped raw `pt.z` elevations, 3D ego silhouette, 3D EKF track bounding boxes, 3D velocity vector arrows, billboard labels, and optional 4-frame persistence trails (`MAX_TRAIL_FRAMES = 4`).
- [RadarGlBevRenderer.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/RadarGlBevRenderer.kt) & [RadarGlBevView.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/RadarGlBevView.kt): Enabled 16-bit depth buffer (`8-8-8-8-16-0`), hardware depth testing in 3D mode, touch gesture handling directly on `InteractiveRadarGlSurfaceView`, and `mode=2D|3D` telemetry in `[GL_BEV_5S]`.
- [RadarBevPlot.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt) & [FullscreenSbsPreview.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/FullscreenSbsPreview.kt):
  - Moved the point/target legend into a compact translucent overlay in the top-left corner (`Alignment.TopStart`) to maximize vertical plot space.
  - Switched footer buttons (`Moving`, `SNR≥15`, `GL`/`Skia`, `2D`/`3D`, `Reset`, `Bench`) to equal `Modifier.weight(1f)` sizing so `Reset` never shifts buttons off-screen.
  - Disabled clusters by default (`bevShowClusters = false` in [DebugToggles.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/DebugToggles.kt) & `KEY_BEV_SHOW_CLUSTERS = "bev_show_clusters_v2"` in [AppPreferences.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/storage/AppPreferences.kt)).

---

## 3. Deep Audit Fixes Verified

Cross-checking the GLES 3.0 implementation against [RadarTlvDecoder.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt) and Foxglove's MCAP exporter ([convert_session_to_mcap.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/convert_session_to_mcap.py)) resolved 6 subtle issues:
1. **3D Track Box Dimensions & Yaw Alignment ([BevGlVertexPacker.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlVertexPacker.kt#L541-L553)):** Mapped semi-axes `halfW = minorSize` (`>= 0.40 m`), `halfL = majorSize` (`>= 0.75 m`), and `yawRad = -track.orientationDeg` rad (matching Foxglove's 3D cuboids 1:1 instead of swapping `xSize`/`ySize`, halving semi-axes, and inverting CW heading).
2. **Unclamped 3D `pt.z` & Wider Vertical Frustum ([BevCameraController.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevCameraController.kt#L30-L39), [BevGlVertexPacker.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlVertexPacker.kt#L669-L685)):** Removed the `-2..12 m` Z clamp and `1.25× maxRange` cutoff on 3D points/tracks; widened `DEFAULT_FOV_Y_DEG = 60°`, lowered `DEFAULT_PITCH_3D_DEG = 20°`, and set `MIN_PITCH_3D_DEG = -10°` so high-Z reflections never clip above the default frustum.
3. **2D Pan/Zoom Baseline Ticks ([BevGlShaders.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlShaders.kt#L94-L109)):** Bounded lateral 5 m ticks to screen margins (`vPx.x in 20..uViewport.x - 20`).
4. **3D Point Fringe Depth-Buffer Halos ([BevGlShaders.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/BevGlShaders.kt#L282-L291)):** Raised fragment alpha discard (`edge <= 0.12`) so anti-aliased fringes do not occlude wireframes behind them.
5. **Double-Tap Drift & Reset Nonce Guard ([RadarGlBevView.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/gl/RadarGlBevView.kt#L63-L157)):** Suppressed trailing `ACTION_MOVE` drag until `ACTION_UP` after `onDoubleTap`, and guarded `LaunchedEffect(cameraResetNonce)` with `lastHandledResetNonce`.
6. **Stale `Reset` Pill After `GL → Skia` ([RadarBevPlot.kt](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt#L152-L157)):** Cleared `isGlCameraModified` whenever `useGlBackend` becomes `false`.

---

## 4. Automated Verification Results

| Check | Result |
| :--- | :---: |
| `./gradlew assembleDebug` | **PASS** |
| `./gradlew testDebugUnitTest` (`BevCameraControllerTest`, `BevGlVertexPackerTest`, `BevProjectionMathTest`, `BevTrackAssociatorTest`, `RadarDisplayPacerTest`, `DebugTogglesTest`, etc.) | **PASS** |
| `python -m unittest discover -s scripts/tests -p "test_profile_ui_perf.py" -v` | **PASS (3/3)** |
