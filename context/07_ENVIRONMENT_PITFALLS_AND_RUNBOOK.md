# RoadSense: Environment Pitfalls & Operational Runbook

> **Document Name:** `07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md`  
> **Location:** `context/07_ENVIRONMENT_PITFALLS_AND_RUNBOOK.md`  
> **Audience:** Autonomous AI Coding Agents & DevOps Engineers  

---

## 1. Machine-Specific Local Environment Quirks

### 1.1 ⚠️ The Invalid System `JAVA_HOME`
* **Symptom:** Running `./gradlew assembleDebug` directly produces:  
  `ERROR: JAVA_HOME is set to an invalid directory: C:\Program Files\Amazon Corretto\jdk17.0.12_7`
* **Root Cause:** The Windows system environment variable `JAVA_HOME` points to an uninstalled Amazon Corretto JDK.
* **Workaround:** Always set `$env:JAVA_HOME` to Android Studio's bundled JetBrains Runtime (JBR) in PowerShell before calling `./gradlew`:

```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew testDebugUnitTest --no-daemon
```

> **Note (`jlink.exe` Daemon Quirk):** If a background Gradle daemon was spawned by JetBrains' `IntelliJServer/servers/kotlin-server/*/jbr` (which lacks `jlink.exe`), `:app:compileDebugJavaWithJavac` will fail in `JdkImageTransform`. Passing `--no-daemon` guarantees Gradle uses Android Studio's full JBR.

---

## 2. Strict Workspace Guardrails

| Guardrail Rule | Strict Policy | Reason / Impact |
|---|---|---|
| **Git Commits & Pushes** | **STRICTLY PROHIBITED** without explicit confirmation | User maintains personal version control discipline. Never run `git commit` or `git push` autonomously. |
| **Direct APK Installation** | **STRICTLY PROHIBITED** without user instruction | User will test and install manually when ready. Always provide the built APK path. |
| **External Reference Repo** | **STRICTLY READ-ONLY:**<br>`D:\Work\Repo\CANenbl_unifiedRadarTracker` | Historical and algorithmic reference codebase. NEVER edit, delete, or commit files in this path. |

---

## 3. Hardware & Networking Quirks

### 3.1 CANedge2 ESP32 Web Server (Bug #10)
* **Single Connection Limit:** The onboard ESP32 web server cannot handle concurrent HTTP requests or rapid hammer polling. Doing so causes `EOFException`, connection resets, or AP crashes.
* **Sequential Non-Blocking Access:** All HTTP downloads and file listings must be executed sequentially with backoff cooldowns.
* **No HTTP DELETE:** Do not attempt HTTP DELETE on CANedge endpoints. The firmware uses cyclic FIFO logging.

### 3.2 TI mmWave Radar USB Serial (3.125 Mbps)
* **High Baud Rate:** Operates at `3,125,000 baud`. Standard USB cables or unpowered OTG adapters may introduce bit errors or packet drops.
* **Android USB Permissions:** Android requires explicit runtime user consent to access USB devices. `RadarSerialService` manages the `UsbManager.requestPermission` flow.

### 3.3 Camera2 HAL Streams & Hardware OIS Constraints
* **HAL Surface Allocation Limits:** Many midrange Android chipsets (e.g. Exynos 9611 on Samsung M21) limit concurrent Camera2 HAL output streams. RoadSense uses a single persistent `AutoFitSurfaceView` (`PersistentCameraViewportHost.kt`) on a `SurfaceFlinger` HWC plane (0 ms HWUI copy). (`CameraEngine.startLuminanceAnalyzer()` is currently disabled as a no-op with `RoadAeMode.FULL_MATRIX` to avoid `14–22 ms` GPU readback stalls.)
* **Hardware Optical Image Stabilization (OIS):** Query `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` before presenting OIS controls. Devices without physical OIS voice-coil actuators will fail silently or log warnings if `LENS_OPTICAL_STABILIZATION_MODE_ON` is requested. UI should hide or grey out OIS toggles on unsupported hardware.

### 3.4 Tier C OpenGL ES 3.0 BEV Viewport & `SurfaceView` Constraints
* **Mutual Exclusion (`GL` vs `Skia`):** `RadarBevPlot` swaps between `RadarGlBevView` (`GLES30`) and `RadarBevCanvas` (Skia) cleanly via the footer toggle so only the active renderer runs. Inside scrollable portrait cards (`!isLandscape && showChrome`), RoadSense forces Skia Canvas to prevent `SurfaceView` Z-order punch-hole lag while scrolling.
* **Lock-Free Touch & Persistence:** `BevCameraController` holds camera orbit/pan/zoom in an `AtomicReference` (`0` recompositions per gesture); Foxglove Panel settings (`Col`, `Map`, `Sz`, `●/■`, `Stixels`, `Lanes`, `Decay`, `HideOut`) persist across restarts via `AppPreferences`.

---

## 4. Operational Runbook & Command Cheat Sheet

### 4.1 Building & Testing
```powershell
# Set JBR Java Home
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"

# Run JVM Unit Tests
./gradlew testDebugUnitTest

# Clean and Assemble Debug APK
./gradlew clean assembleDebug

# Check built APK details
Get-Item app\build\outputs\apk\debug\app-debug.apk | Select-Object Name, Length, LastWriteTime
```

### 4.2 ADB Device Management
```powershell
$ADB = "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# List connected devices
& $ADB devices

# Manual install of APK (preserving data)
& $ADB install -r app\build\outputs\apk\debug\app-debug.apk

# Monitor live multi-sensor logcat
& $ADB logcat -s RoadSense:D CanedgeIngestion:D RadarSerialService:D AppLogger:D CameraEngine:D SpatialProjection:D
```

### 4.3 Log Extraction & Data Processing
```powershell
# Pull sessions and continuous diagnostics logs to PC
python tools/sync_and_process_sessions.py --sync-only

# Full pipeline (Sync from phone + Process radar & video to JSON)
python tools/sync_and_process_sessions.py
```

### 4.4 Web Dashboard & Foxglove MCAP Runbook
```powershell
# 1-Click Launch: Sync, Dashboard & Foxglove
.\sync_and_process_logs.bat

# Start the Web Dashboard server directly (http://localhost:8088)
python tools/roadsense_web_server.py

# Activate the MCAP conversion Conda environment
conda activate roadsense-mcap

# Instant zero-copy MCAP conversion (>11,500 FPS, ~3.4s, zero GPU load)
python tools/convert_session_to_mcap.py logs/session_YYYYMMDD_HHMMSS

# Physical video re-encoding (rotates video frames 180° via NVENC / libx264)
python tools/convert_session_to_mcap.py logs/session_YYYYMMDD_HHMMSS --flip-video

# Activate the 3D Vision Conda environment & run Vision-vs-Radar benchmark + pitch solver
conda activate cv313
python tools/vision/eval/evaluate_vision_vs_radar.py logs/session_YYYYMMDD_HHMMSS
```
