# RoadSense Local Environment & AI Assistant Operational Guide

> **Document Name:** `gemini.md`  
> **Purpose:** Authoritative reference for autonomous AI coding agents and developers working in this local environment. Contains all machine-specific configurations, known local environment quirks, build workarounds, and hardware guidelines.  
> **Workspace Path:** `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense`  
> **Last Updated:** 2026-09-11  

---

## 1. Local Java Home & Gradle Build Instructions

### ⚠️ Known Issue: Invalid System `JAVA_HOME`
* **Problem:** The Windows system environment variable `JAVA_HOME` is set to an invalid/uninstalled path:  
  `C:\Program Files\Amazon Corretto\jdk17.0.12_7`  
  Attempting to run `./gradlew` directly results in:  
  `ERROR: JAVA_HOME is set to an invalid directory: C:\Program Files\Amazon Corretto\jdk17.0.12_7`

### ✅ Solution / Workaround:
Always set `$env:JAVA_HOME` to Android Studio's bundled JetBrains Runtime (JBR) before invoking `gradlew` in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
./gradlew assembleDebug
```

### Quick Build & Test Commands:
```powershell
# Assemble Debug APK
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew assembleDebug

# Run JVM Unit Tests
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest

# Full Clean & Build
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew clean assembleDebug
```

---

## 2. Android SDK, ADB & Device Deployment

### Local Paths:
* **Android SDK Directory:** `C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk`
* **ADB Executable:** `C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe`
* **Built APK Target:** `app\build\outputs\apk\debug\app-debug.apk`

### Quick Deployment via ADB:
```powershell
# Check connected devices
& "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices

# Install APK onto phone (reinstall preserving data)
& "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk

# Monitor live RoadSense & CANedge logcat
& "C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s RoadSense:D CanedgeIngestion:D CanedgeHttpClient:D CanedgeRepository:D
```

---

## 3. Workspace Boundaries & Security Guardrails

### 🛡️ Strict Workspace Rules:
1. **Primary Working Repository:**  
   `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense` (All project work must take place here).
2. **External Reference Repository (STRICTLY READ-ONLY):**  
   `D:\Work\Repo\CANenbl_unifiedRadarTracker`  
   * **Rule:** NEVER modify, delete, edit, or commit to any files in this directory. It is strictly a read-only historical and algorithmic reference.
3. **Commit Authorization:**  
   * NEVER perform `git commit` or `git push` without explicit confirmation and instruction from the user.

---

## 4. CANedge2 Hardware & Networking Parameters

* **Device Model:** CSS Electronics CANedge2
* **Device ID:** `7AC5E17F`
* **Firmware Version:** `01.09.03` (Config: `01.09`)
* **Wi-Fi AP Credentials:** SSID: `M21` | Pass: `987654321` (2.4 GHz WPA2)
* **Default Fallback IP:** `http://192.168.x.x` or `http://7AC5E17F/`
* **Logging Mode:** 10-second MF4 file splitting, cyclic logging enabled (`"cyclic": 1`).
* **CAN Physical Mode:** CAN1 @ 500 kbit/s (RX mode, all standard and extended IDs accepted).

### Single-Connection MCU Web Server Quirks (Bug #10):
* The onboard ESP32 web server cannot handle concurrent HTTP connections or rapid hammer polling.
* OkHttpClient is configured with:
  * 30-second read timeout.
  * 1.5-second error backoff cooldown.
  * Sequential non-blocking downloads.
* **Never attempt HTTP DELETE** on the device API; SD card FIFO pruning is handled automatically by firmware cyclic logging.

---

## 5. Storage Directory Structure

```text
/sdcard/Android/data/com.bajajauto.roadsense/files/
 ├── app_logs/                         <-- Continuous app-wide flight recorder logs
 │    └── app_run_YYYYMMDD_HHMMSS/
 │         └── app_system.log          <-- Idle boot, discovery, sync & UI events
 └── sessions/
      ├── canedge_pool/                <-- Staging cache for top-5 session folders
      │    ├── 00000033_00000001.MF4
      │    └── 00000034_00000001.MF4
      └── session_YYYYMMDD_HHMMSS/     <-- Permanent recording session
           ├── session_metadata.json
           ├── session_debug.log        <-- Active recording flight recorder log
           ├── session_timeline.csv    <-- Shared monotonic sync index
           ├── radar/
           │    └── radar_raw_stream.bin
           ├── video/
           │    └── camera_preview.mp4
           ├── gnss/
           │    └── gnss_track.csv
           └── can/
                ├── 00000033_00000001.MF4
                └── 00000034_00000001.MF4
```

---

## 6. Project Documentation Map

* **`Future_stuff.md`**: Centralized backlog of future enhancements (Camera AF tap-to-focus, CANedge unified file explorer, always-on ingestion, post-processing DBC decoding).
* **`intel/debugging_and_troubleshooting_knowledge_base.md`**: In-depth post-mortem and architectural analysis of Bugs #1 through #10.
* **`intel/CANedge2_Android_Integration_Guide.md`**: Full hardware integration specification for CSS Electronics CANedge2.
* **`sync_and_process_logs.bat`**: PC ADB extraction and python visualizer pipeline.
