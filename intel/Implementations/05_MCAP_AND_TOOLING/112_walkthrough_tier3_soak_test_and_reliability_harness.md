# Tier 3 Walkthrough (`112`): Automated Long-Duration Bench Soak & Reliability Verification Harness

> **Branch:** `Overhaul` (uncommitted working tree on top of Tier 2 commits `27593a9` / `cb65d0d`)  
> **Scope:** Option 1 (Pure PC Toolchain + Web Dashboard Integration — zero Android APK rebuild required).  
> **Verification:** **4 / 4** soak analyzer tests (`scripts/tests/test_soak_analyzer.py`), **2 / 2** MCAP event tests (`tools/tests/test_event_markers_mcap.py`), Web Dashboard `--test-init`, and `run_soak_test.py --help` all passing.

---

## 1. Implemented Components

### 1.1 Unattended Wired / Wireless ADB Soak Orchestrator
- **`scripts/run_soak_test.py`:**
  - **Wireless ADB Setup (`--setup-wireless-adb`):** Enables `adb tcpip 5555`, auto-probes `wlan0`, `swlan0`, and `ap0` (supporting both shared Wi-Fi and `SM-M325F`'s own `M21` hotspot), connects wirelessly, and guides unplugging USB so the AWR1843BOOST radar can occupy the phone's USB-C port during a full 5-sensor soak.
  - **Dual Concurrent Collectors:**
    - Periodic `dumpsys` poller (`--interval-sec`, default `15 s`): captures `pidof`, `meminfo`, `gfxinfo` (reset at soak start), `thermalservice`, `battery`, and active session folder disk growth (`du -sk`) into `logs/soak_runs/soak_YYYYMMDD_HHMMSS/soak_telemetry.jsonl`.
    - Background `adb logcat` watermark thread: tracks `UiPerf [WINDOW_5S]` P50/P95/Max rollups, `RADAR_FRAME_DROP` / `RADAR_FRAME_SALVAGED`, `rawByteQueue FULL`, `USB_DETACHED` / `USB_RECONNECTED`, `DisplayPacer` drops, `MediaRecorder` errors, and `FATAL EXCEPTION` / `ANR`.
  - **Passive Monitor Mode & Graceful `Ctrl+C`:** Auto-binds to an already-running or newly started recording session; pressing `Ctrl+C` cleanly finalizes telemetry and still generates the full certification report.
  - **Automatic Post-Soak Audit & Parity Chain (Default ON; opt-out via `--skip-post-audit`):** Once the soak session closes on device (waits up to 45 s for tap-Stop), automatically pulls + builds MCAP via `tools/sync_and_process_sessions.py --session <id> --mcap`, calls `scripts.session_health_check.audit_session()` (8-domain health check), and calls `tools.verify_conversion_parity.verify_session_parity()` — printing results live and embedding both verdicts into the soak certification report.

### 1.2 Certification Scorecard & Dark-Mode SVG Report Generator
- **`scripts/soak_analyzer.py`:**
  - Verified against real `SM-M325F` `dumpsys` outputs (`meminfo`, `gfxinfo`, `thermalservice`, `battery`) and `UiPerf [WINDOW_5S]`.
  - Computes post-warmup OLS memory slopes (`MB/hr` for Java Heap, Native Heap, Total PSS), early-vs-late UI P95 drift, peak thermal status/temps, battery drain rate, disk growth continuity, and evaluates 7–9 formal **PASS / WARN / FAIL** certification gates.
  - Outputs `soak_report.json`, zero-dependency dark-mode inline-SVG `soak_report.html` (4 trend charts + KPI cards + gate scorecard), and an ANSI colorized terminal summary.

### 1.3 Web Dashboard Integration & Repo Hygiene
- **`tools/web_dashboard/index.html`:**
  - Added **"🔥 Bench Soak & Reliability Harness"** card inside the **🩺 Diagnostics** tab (`2 / 5 / 15 / 30 / 60 min` duration selector, optional serial/IP field, Auto-Pull + Health Check + MCAP Parity toggle, `▶ Start Bench Soak`, and `📶 Setup Wireless ADB`), with live SSE `[PROGRESS]` streaming and automatic inline iframe preview of `soak_report.html`.
- **`.gitignore`:** Added `.vscode/` to ignore local editor settings.
- **`Future_stuff.md`:** Updated Section 9.2 and 9.3 statuses for completed Tiers 2 and 3.

---

## 2. Verification Results

| Check | Command | Result |
|---|---|---|
| **Soak Analyzer Unit Tests** | `conda run -n roadsense-mcap python -m unittest discover -s scripts/tests -p "test_soak_*.py" -v` | **4 / 4 PASSED** (`0.008 s`) |
| **MCAP Event Marker Tests** | `conda run -n roadsense-mcap python -m unittest discover -s tools/tests -p "test_*.py" -v` | **2 / 2 PASSED** (`0.057 s`) |
| **Web Dashboard Server Init** | `conda run -n roadsense-mcap python tools/roadsense_web_server.py --test-init` | **PASSED** (`http://127.0.0.1:8088`) |
| **CLI & Syntax Validation** | `conda run -n roadsense-mcap python scripts/run_soak_test.py --help` | **PASSED** (exit `0`) |

---

## 3. Quick Usage Commands

```powershell
# 1. Optional: Enable Wireless ADB (works over shared Wi-Fi or SM-M325F's M21 hotspot)
conda run -n roadsense-mcap python scripts/run_soak_test.py --setup-wireless-adb

# 2. Run a 2-minute smoke soak (or 30-minute certification soak)
conda run -n roadsense-mcap python scripts/run_soak_test.py --duration-min 2 --interval-sec 10
conda run -n roadsense-mcap python scripts/run_soak_test.py --duration-min 30 --interval-sec 15

# 3. Re-analyze any saved soak run offline
conda run -n roadsense-mcap python scripts/soak_analyzer.py --latest
```
