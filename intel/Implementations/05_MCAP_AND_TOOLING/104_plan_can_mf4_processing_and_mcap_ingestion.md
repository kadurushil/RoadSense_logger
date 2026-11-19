# Plan: Phase 2 Execution (CANedge2 RTC + Live Verifier + Android Sync Closure) & Phase 3 Blueprint

## Execution Strategy: Phase-by-Phase Isolation

Per review direction, we are **strictly locking our active scope to Phase 2 first**:
1. **Active Now — Phase 2:** Close out CANedge2 clock continuity (`config-01.09.json`), build the live PC verification tool (`scripts/verify_canedge_live.py`), settle the one remaining Android offline chunk-matching edge case explained below, and validate on the **working vehicle** until CAN logging and Android sync are 100% solid.
2. **Deferred Until After Vehicle Sign-Off — Phase 3:** Modular PC post-processing under `tools/CAN_Processing/` (30–60 s rolling offset alignment, completeness audit, Dynamic Protobuf + MCAP export in `roadsense-mcap`).

---

## Core Design Principles (Agreed)

1. **Zero Mutation of Raw Sensor Data:** `radar_frames.bin`, `camera_video.mp4`, `*.MF4`, `imu_frames.csv`, and `gnss_track.csv` remain completely untouched on disk.
2. **Single Continuous CAN Timeline Over Exact NTP Perfection:** On CANedge2, avoiding mid-drive UTC step jumps is far more important than zeroing a 1–2 s clock skew. Starting with **`"tolerance": 3`** (expandable to `5` s if needed) and `"ntp_server": "time.cloudflare.com"` prevents minor cellular NTP jitter from distorting timestamps mid-drive, while booting > 3 s off still snaps cleanly when internet is present.
3. **30–60 s Periodic Drift Verification (Phase 3):** Because empirical vehicle drives (`session_20261116_143131`) showed steady **`-25 to -33 ms` per 60 s chunk** (`-460 ppm` CANedge oscillator skew) even without step jumps, Phase 3 will **never trust a single `t=0` offset** — it will re-verify and update the CAN-to-UTC offset every **30–60 s** across the drive.

---

## Plain-English Explanation of Open Question: Offline Chunk-Matching Without Internet

In the previous draft, Open Question #2 was phrased abstractly. Here is what it means in a concrete real-world ride scenario:

Suppose one morning:
- The hotspot (`M21`) has **no internet** (or CANedge2 sat disconnected long enough that its internal clock drifted by **2 minutes** behind Android's clock: CANedge2 thinks it is `13:58:00`, while Android knows it is `14:00:00`).
- You turn on the bike, wait only a few seconds (not long enough for a 60-second `.MF4` chunk rollover to happen while idle), and immediately press **Record** for a **5-minute drive** (`14:00:00` → `14:05:00` Android time), then press **Stop**.
- Because Android did not see a chunk rollover before the drive (and suppose the previous day's saved clock offset expired after 24 hours), Android temporarily has **no measured offset (`clockOffsetMs = 0`)**.
- So Android asks CANedge2: *"Give me the `.MF4` chunks timestamped `14:00:00` to `14:05:00` (±10 seconds padding)."*
- **The problem:** CANedge2 actually stamped that same drive's files as **`13:58:00` to `14:03:00`**. Because Android only looks within `±10 s` when it thinks offset is `0`, Android would **skip the first 2 minutes of the drive (`13:58..14:00`)** — or, if CANedge2's clock was off by 6 minutes, match **zero chunks** for that drive!

### How Should Phase 2 Handle This on Android?
Before closing out Android in Phase 2, please pick whichever approach matches how you operate the vehicle:

- **Option A — Automatic Post-Drive Rollover Calibration When Unverified (Recommended):**  
  Remember that `CanedgeIngestionManager` already measures the exact clock offset whenever a 60-second chunk finishes (`rollover`). If a session finishes recording while Android's clock offset was **still unverified** (`measuredClockOffsetMs == null` and no fresh saved offset < 24h), Android waits up to **1 chunk rollover (~60 s)** after recording stops while idle, learns the exact CANedge-vs-Android offset from that newly finished chunk, and **only then** picks the `.MF4` chunks for the session. (If the offset *was* already measured before the drive or saved < 24h ago, zero extra wait is added.)
- **Option B — Keep Current Logic + Rely on `canedge_pool/` Backup:**  
  Assume CANedge2's internal coin-cell battery / recent M21 boot keeps clock offset within `±10 s` (or already measured within 24h), leave Android's `CanedgeSyncPlanner.kt` untouched, and treat any rare >10 s offline miss via manual copy from `canedge_pool/`.

> [!IMPORTANT]
> **One Question Before Phase 2 Implementation:**  
> Do you want **Option A** (auto-wait for 1 rollover before finalizing when offset is completely unverified) added in Phase 2, or **Option B** (keep current Android sync logic as-is and focus Phase 2 on `config-01.09.json` + `scripts/verify_canedge_live.py`)?

---

## Active Scope: Phase 2 — CANedge2 Config, Live Verifier & Android Time Closure

### 1. [MANUAL SD CARD EDIT] CANedge2 `config-01.09.json` (`"rtc"`)
Per confirmation, you will manually apply this exact diff to `config-01.09.json` on the CANedge2 SD card (`7AC5E17F`, FW `01.09.03`):

```diff
   "rtc": {
     "sync": 2,
     "timezone": 0,
     "adjustment": 0,
-    "tolerance": 30,
-    "ntp_server": "*.pool.ntp.org"
+    "tolerance": 3,
+    "ntp_server": "time.cloudflare.com"
   },
```
- Start with **`"tolerance": 3`** seconds during Phase 2 vehicle testing; bump to `5` only if field testing shows cellular NTP jitter triggering mid-drive jumps.

### 2. [NEW] [verify_canedge_live.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/verify_canedge_live.py)
Dedicated Phase 2 PC desk & vehicle diagnostic tool (separate from Android Logcat checker `scripts/verify_canedge_logcat.py`):
- Discovers CANedge2 over Wi-Fi (`--ip`, cached IP, or `http://7AC5E17F/`) with clean diagnostics if PC is not on the `M21` subnet.
- Reads `/api/device.json`, `/api/config-01.09.json`, and `/LOG/` chunk list via sequential single-connection HTTP requests (respecting ESP32 single-socket safety).
- Audits `"rtc"` fields against target (`sync == 2`, `tolerance` in `2..5`, `ntp_server == "time.cloudflare.com"`).
- Benchmarks real-time NTP UTC from `time.cloudflare.com` (UDP/123) vs Android `adb shell date +%s.%N` vs latest `.MF4` `HDBLOCK` timestamp (read safely via `asammdf` in `roadsense-mcap`/`bsignalizer` or raw header inspection — never using crashing system `mdf_iter`).
- Audits Android phone storage via ADB (`sessions/canedge_pool/`, `sessions/can_sync_manifest.json`, and `session_*/can/`) for missing/overlapping chunks and reports exact clock skew (`ms` and `ppm` if multiple chunks exist).
- Supports `--watch-sync` live loop for testing boot NTP sync vs completely offline RTC continuity on the vehicle.

### 3. [OPTIONAL IF OPTION A CHOSEN] Android Offline Calibration Closure (`CanedgeIngestionManager.kt` / `CanedgeSyncPlanner.kt`)
- Only if Option A is selected above: defer finalizing a newly stopped recording's CAN sync by at most one rollover (`<= 70 s`) **solely when** `resolveEffectiveClockOffsetMs()` had neither a live session rollover nor a `< 24h` persisted offset, then solve the offset from the very first post-drive chunk rollover and proceed automatically.
- All changes unit-tested via `./gradlew testDebugUnitTest --no-daemon` (never built/installed onto phone by agent).

---

## Deferred Scope: Phase 3 — Modular `tools/CAN_Processing/` & Unified MCAP (After Vehicle Sign-Off)

Captured from review comments — **no Phase 3 code will be written until Phase 2 passes vehicle verification**:

1. **Modular Directory Layout (`tools/CAN_Processing/`):**
   Keep `tools/sync_and_process_sessions.py` lean (no monolithic additions). Place small, focused single-responsibility modules inside `tools/CAN_Processing/`:
   - `tools/CAN_Processing/mf4_loader.py` — `asammdf` + `tools/VCU.dbc` chunk loading, step-jump checks (`HDBLOCK` continuity), zero raw file mutation.
   - `tools/CAN_Processing/time_aligner.py` — **Windowed 30–60 s rolling alignment** (not single `t=0` offset):
     - **Tier 1:** Periodic 30–60 s piecewise affine/median lock on `BO_ 213 RADAR_Debug_Info` (`FrameNumber_Act` ↔ UART `hw_frame_num & 0xFFFF`), tracking `-460 ppm` crystal skew and any mid-drive clock shift.
     - **Tier 2:** Windowed kinematic cross-correlation (`VCU_Vehicle_Info1` / `MCU_Speed_Info` vs GNSS/IMU speed) when `ID 213` is absent.
     - **Tier 3:** Metadata / fallback offset (`can.clockOffsetMs`).
   - `tools/CAN_Processing/completeness_auditor.py` — Verifies CAN chunk sequence continuity and UTC coverage against `[startTimeWallMs .. stopTimeWallMs]`, emitting warnings or `--strict-can` failure.
   - `tools/CAN_Processing/proto_builder.py` — Dynamic Protobuf descriptors + `can_mcap_config.json` (`"all"` default / `"whitelist"` override) + Zstd framing for `/can/<MessageName>`.
   - `tools/CAN_Processing/pipeline.py` — Sequential orchestrator called by `tools/sync_and_process_sessions.py` right before `convert_session_to_mcap(...)`.
2. **Python Environment (`roadsense-mcap`):** Add `asammdf`, `cantools`, and `numpy` to `tools/requirements-mcap.txt` / `roadsense-mcap` conda env (`cv313` left strictly untouched for Computer Vision).

---

## Phase 2 Verification Plan

1. User edits `"rtc"` (`tolerance: 3`, `ntp_server: "time.cloudflare.com"`) manually on the CANedge2 SD card and boots CANedge2 on the vehicle / `M21` hotspot.
2. Run `python scripts/verify_canedge_live.py --watch-sync`:
   - **Test 1 (Hotspot WITH Internet):** Verify `config-01.09.json` `"rtc"` check passes and CANedge `.MF4` UTC offset drops within `<= 3 s` of Android / `time.cloudflare.com` NTP.
   - **Test 2 (Hotspot WITHOUT Internet):** Disable cellular/internet on `M21`, power-cycle CANedge2, wait across 2–3 chunk rollovers, and confirm continuous monotonic `HDBLOCK` timestamps with zero logging stalls and zero HTTP errors.
3. Perform 1+ working vehicle drives (both internet-on and offline), run `scripts/verify_canedge_logcat.py` + `scripts/verify_canedge_live.py`, and confirm 100% `.MF4` chunk completeness inside `session_*/can/` before unlocking Phase 3.
