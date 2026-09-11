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
./gradlew testDebugUnitTest
```

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
& $ADB logcat -s RoadSense:D CanedgeIngestion:D RadarSerialService:D AppLogger:D
```

### 4.3 Log Extraction & Data Processing
```powershell
# Pull sessions and continuous diagnostics logs to PC
python tools/sync_and_process_sessions.py --sync-only

# Full pipeline (Sync from phone + Process radar & video to JSON)
python tools/sync_and_process_sessions.py
```
