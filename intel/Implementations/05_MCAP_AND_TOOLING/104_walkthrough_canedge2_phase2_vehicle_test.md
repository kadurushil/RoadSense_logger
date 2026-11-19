# Walkthrough: Phase 2 Vehicle Verification (Internet ON vs Internet OFF)

## 1. Test Sessions & Environment

| Metric | Drive A — Internet ON | Drive B — Internet OFF |
|---|---|---|
| **Session ID** | `session_20261119_104518` | `session_20261119_105126` |
| **Phone Start / Stop (IST)** | `10:45:18.358` → `10:48:14.741` (`176.38 s`) | `10:51:26.781` → `10:53:25.981` (`119.20 s`) |
| **App Flight Recorder Log** | `app_run_20261119_104223/app_system.log` (`746` lines) | Same continuous app run |
| **Camera / IMU Cross-Sync** | `GOLD` (`5258` frames, `1.25 ms` Cam-IMU mean) | `GOLD` (`3545` frames, `1.25 ms` Cam-IMU mean) |
| **CAN Files Staged (`session/can/`)** | `4` (`00000120_00000003..06.MF4`) | `4` (`00000120_00000009`, `00000121_00000001..03.MF4`) |
| **Decoded CAN Channels (`tools/VCU.dbc`)** | `1,970` channels | `2,309` channels |
| **CANedge2 `HDBLOCK` Boot UTC** | `1795065151.000` (`10:42:31.000` IST) | `1795065657.000` (`10:50:57.000` IST) |
| **Reported Android `clockOffsetMs`** | `+57,614 ms` *(~60 s bug found & fixed below)* | `+55,673 ms` *(~60 s bug found & fixed below)* |

---

## 2. End-to-End Event Chain Verification (`app_run_20261119_104223/app_system.log`)

Running `python scripts/verify_canedge_logcat.py --file ...` on the pulled `app_system.log` verified **6/6 exercised checks PASS**:
- **Auto-Connect & Idle Fast-Path:** `connects=4, fastPathSkips=11, zeroDownloadPolls=8`
- **Zero Re-Download Idempotency:** `uniqueDownloads=12, duplicates=0`
- **Recording Pause & Zero-HTTP:** `pauses=2, httpCallsDuringRec=0`
- **Offline Sync & Legacy Backfill:** `tenFolderScans=8, reconciles=4`
- **Live Rollover Clock Offset:** Measured on every rollover (`55,673 ms` last recorded)
- **Pool Prune & Orphan Cleanup:** `prunes=6, orphanCleanups=0`

### Chronological State-Flow Audit (Matches Observed Vehicle Actions 1:1)
1. **`10:42:06` → `10:42:40` (Vehicle Boot & Discovery):** Watchdog started; subnet scan found nothing while CANedge2 was still booting, then cached IP `10.225.54.240` succeeded (`PROBE_OK device=7AC5E17F`).
2. **`10:43:06` → `10:45:16` (Idle Pre-Sync & Verbose Toggle):** Initial rollover measured `387 ms`; subsequent rollovers queued `00000120_00000001.MF4` and `00000120_00000002.MF4` into `canedge_pool/` and skipped unchanged polls via `SYNC_FASTPATH`.
3. **`10:45:18` → `10:48:14` (Drive A — Internet ON):** `RECORDING_PAUSED` stopped all CANedge HTTP traffic (`httpCallsDuringRec=0`). Upon Stop (`10:48:14.785`), `SESSION_QUEUED` ran 10-folder scan, downloaded `00000120_00000003..05.MF4` (`PENDING -> PARTIAL`), then downloaded `00000120_00000006.MF4` (`PARTIAL -> COMPLETE` at `10:48:38.834`).
4. **`10:50:40` → `10:51:08` (Vehicle Power-Off & Restart):** Three 3000 ms connect timeouts detected CANedge2 offline cleanly (`10:50:40..47`); after vehicle restart, `PROBE_OK` reconnected at `10:51:08.153` and queued `00000120_00000008.MF4` (`8/8` sync).
5. **`10:51:27` → `10:53:43` (Drive B — Internet OFF):** Recording paused CANedge sync cleanly (`httpCallsDuringRec=0`). After Stop (`10:53:26`), `RECONCILE` transitioned `PENDING -> PARTIAL -> COMPLETE` by `10:53:43.731` (`17.7 s` post-stop) and returned to idle fast-path.

---

## 3. Time Offset & `.MF4` Sample Span Analysis (Key Discovery!)

Inspecting the actual decoded CAN timestamps inside both sessions with `asammdf` + `tools/VCU.dbc` proved **two crucial facts**:

### A. CANedge2 `time.cloudflare.com` NTP Sync (`tolerance: 3`) Worked to `< 1 s`!
- In **Drive A (`10:45:18 .. 10:48:14`):**
  - `00000120_00000003.MF4`: `10:44:00.008 .. 10:44:59.983`
  - `00000120_00000004.MF4`: `10:44:59.993 .. 10:45:59.988`
  - `00000120_00000005.MF4`: `10:45:59.998 .. 10:46:59.993`
  - `00000120_00000006.MF4`: `10:47:00.003 .. 10:47:59.988`
- In **Drive B (Internet OFF, `10:51:26 .. 10:53:25`):**
  - `00000121_00000001.MF4`: `10:50:57.786 .. 10:50:59.988`
  - `00000121_00000002.MF4`: `10:50:59.998 .. 10:51:59.992`
  - `00000121_00000003.MF4`: `10:52:00.002 .. 10:52:59.996`
- Every single chunk rolls over cleanly at `:00.00` UTC seconds with **zero mid-drive step jumps** even after turning internet off and power-cycling CANedge2.

### B. Root Cause Found & Fixed: Measuring `prevLast` Instead of Newly Closed Chunk (`~60 s` Shift)
- **What happened in logs:**
  - CANedge2 (`FW 01.09.03`) buffers and exposes each completed 60 s chunk to `/api/LOG/` when it **closes**, stamping `lastWrittenEpochSec` with that chunk's **close time** (`10:44:00` for `0002`, `10:45:00` for `0003`, `10:48:00` for `0006`).
  - At `10:43:06`, `00000120_00000001.MF4` appeared (`mtime = 10:43:06`) → measured offset = **`387 ms`**.
  - At `10:44:06` (`0002` appeared) and `10:45:03` (`0003` appeared), `CanedgeSyncPlanner.measureClockOffsetFromLiveTransition()` measured against **`prevLast`** (`0002`, which had already closed **60 seconds earlier** at `10:44:00`) instead of `currInFolder.last()` (`0003`, which just closed at `10:45:00`), producing a false **`+60,645 ms`** (`+57,614 ms` / `+55,673 ms`) offset!
  - Because `+57.6 s` shifted the Android selection window by ~1 chunk earlier, Drive A pulled `00000003` (`10:44..10:45`, pre-drive) and missed `00000007` (`10:48..10:49`, last `14.7 s` of drive), and Drive B pulled `00000120_00000009` (`10:50:00..10:50:30`) and missed `00000121_00000004` (`10:53..10:54`, last `26 s` of drive).
- **Fix Applied in `CanedgeSyncPlanner.kt`:**
  1. `measureClockOffsetFromLiveTransition()` now measures `midWallMs - currInFolder.last().lastWrittenMs` (the newly closed `seq + 1` chunk) instead of `prevLast` (closed 60 s earlier).
  2. `planSessionChunks()` and `planPoolRescueForSession()` retain `endCanMs = file.lastWrittenMs` (close time) and `startCanMs = prev.lastWrittenMs`, which aligns `[start, end]` intervals 1:1 once the `~0 s` rollover offset is restored.
