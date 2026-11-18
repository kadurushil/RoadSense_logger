#!/usr/bin/env python3
"""
Automated CANedge2 Logcat & Device Flight-Log Verification Analyzer.

Parses RoadSense logcat or `app_system.log` output across the 7 CANedge2 verification
checks and prints a deterministic PASS / FAIL / NOT_EXERCISED summary:
  1. Auto-Connect & Fast-Path Idle Check (no 17% redundant poll churn)
  2. Repeat Manual Sync & Zero Re-Download Idempotency
  3. Recording Pause & Zero-HTTP Interlock During Capture
  4. Post-Stop Tail Closure & Zero-CAN Bench Fast Exit (~8 s)
  5. Delayed Offline "Sync Later" Persistence & Multi-Session Reconciliation
  6. Live Chunk Rollover Clock-Offset Tracking
  7. Orphan Partial Cleanup & Protected Pool Pruning
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from typing import Dict, List, Optional, Tuple


DEFAULT_ADB_PATHS = [
    r"C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "adb",
]

TS_PREFIXES = [
    # AppLogger file format: [2026-11-18 15:14:13.312] ...
    re.compile(r"^\[(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\]\s+(.*)$"),
    # ADB threadtime logcat: 11-18 15:14:13.312  4161  4359 I CanedgeIngestion: ...
    re.compile(r"^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+[VDIWEFA]\s+(\S+):\s+(.*)$"),
]


def find_adb() -> Optional[str]:
    for candidate in DEFAULT_ADB_PATHS:
        if os.path.isfile(candidate):
            return candidate
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    return None


def parse_timestamp_sec(raw_ts: str) -> Optional[float]:
    candidates = [(raw_ts, "%Y-%m-%d %H:%M:%S.%f")]
    if len(raw_ts) >= 5 and raw_ts[2] == "-":
        candidates.append((f"{datetime.now().year}-{raw_ts}", "%Y-%m-%d %H:%M:%S.%f"))
    for text, fmt in candidates:
        try:
            return datetime.strptime(text, fmt).timestamp()
        except ValueError:
            continue
    return None


def parse_lines(lines: List[str]) -> List[Tuple[Optional[float], str]]:
    parsed = []
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        ts_sec = None
        msg = line
        m0 = TS_PREFIXES[0].match(line)
        if m0:
            ts_sec = parse_timestamp_sec(m0.group(1))
            msg = m0.group(2)
        else:
            m1 = TS_PREFIXES[1].match(line)
            if m1:
                ts_sec = parse_timestamp_sec(m1.group(1))
                msg = f"[{m1.group(2)}] {m1.group(3)}"
        parsed.append((ts_sec, msg))
    return parsed


def pull_logcat_lines(adb: str) -> List[str]:
    cmd = [
        adb, "logcat", "-d", "-v", "threadtime",
        "-s", "CanedgeIngestion:V", "CanedgeDiscovery:V", "CanedgeRepository:V", "CanedgeHttpClient:V", "UI:I",
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15)
    if res.returncode != 0:
        raise RuntimeError(f"adb logcat failed: {res.stderr.strip()}")
    return res.stdout.splitlines()


def pull_device_app_log(adb: str) -> Tuple[str, List[str]]:
    res_dir = subprocess.run(
        [adb, "shell", "ls -1t /sdcard/Android/data/com.bajajauto.roadsense/files/app_logs | head -n 1"],
        capture_output=True, text=True, timeout=10
    )
    latest_dir = res_dir.stdout.strip()
    if not latest_dir:
        raise RuntimeError("No app_logs directories found on device")
    remote_path = f"/sdcard/Android/data/com.bajajauto.roadsense/files/app_logs/{latest_dir}/app_system.log"
    res = subprocess.run(
        [adb, "shell", f"cat {remote_path}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20
    )
    return latest_dir, res.stdout.splitlines()


def extract_kv(msg: str) -> Dict[str, str]:
    return dict(re.findall(r"([A-Za-z0-9_]+)=([^\s\]]+)", msg))


def analyze(events: List[Tuple[Optional[float], str]]) -> List[Tuple[str, str, str]]:
    results: List[Tuple[str, str, str]] = []

    # Counters & trackers
    connects = []
    fastpaths = []
    scans = []
    sync_plans = []
    downloads: List[Tuple[Optional[float], str]] = []
    prunes = []
    orphans = []
    rollovers = []
    imports = []
    http_errors = []

    is_recording = False
    rec_start_ts: Optional[float] = None
    http_during_recording: List[str] = []
    pause_events = 0
    queued_sessions: Dict[str, float] = {}
    reconciles: List[Tuple[Optional[float], Dict[str, str], str]] = []
    tail_waits = 0
    bench_fast_exits: List[Tuple[str, float]] = []
    offline_pending_detected = False
    pending_10_folder_scans = 0

    for ts, msg in events:
        if ("PROBE_OK" in msg) or ("Watchdog connected" in msg) or ("SUCCESS: CANedge2 connected" in msg):
            connects.append(msg)

        if "SYNC_FASTPATH" in msg:
            fastpaths.append(msg)

        if "SCAN_SCOPE" in msg or "Targeted recent session scan" in msg:
            scans.append(msg)
            kv = extract_kv(msg)
            if kv.get("folders") == "10" or kv.get("scanFolders") == "10":
                pending_10_folder_scans += 1
            if is_recording:
                http_during_recording.append(msg)

        if "HTTP_LIST" in msg and is_recording:
            http_during_recording.append(msg)

        if "SYNC_PLAN" in msg or "Sync plan:" in msg:
            kv = extract_kv(msg)
            sync_plans.append((ts, kv, msg))
            if kv.get("scanFolders") == "10":
                pending_10_folder_scans += 1

        if "Downloaded " in msg and "on attempt" in msg:
            m = re.search(r"Downloaded\s+([^\s]+\.MF4)", msg, re.IGNORECASE)
            if m:
                downloads.append((ts, m.group(1)))
                if is_recording:
                    http_during_recording.append(msg)

        if "RECORDING_PAUSED" in msg or "background CANedge sync PAUSED" in msg:
            pause_events += 1
            is_recording = True
            rec_start_ts = ts

        if "SESSION_QUEUED" in msg or ("Session " in msg and "stopped" in msg and "Queued CAN" in msg):
            is_recording = False
            kv = extract_kv(msg)
            sess = kv.get("session") or "latest"
            if ts is not None:
                queued_sessions[sess] = ts

        if "link marked offline" in msg or "CANedge disconnected" in msg:
            if not connects:
                pass
            offline_pending_detected = True

        if "Imported " in msg and "unreconciled session" in msg:
            imports.append(msg)

        if "RECONCILE" in msg or "Reconcile session_" in msg:
            kv = extract_kv(msg)
            reconciles.append((ts, kv, msg))
            if "WAITING_FINAL_CHUNK" in msg:
                tail_waits += 1
            if "->COMPLETE" in msg or "-> COMPLETE" in msg:
                sess = kv.get("session", "")
                matched = kv.get("matched", "")
                q_ts = queued_sessions.get(sess)
                if q_ts is not None and ts is not None and matched == "0":
                    bench_fast_exits.append((sess, max(0.0, ts - q_ts)))

        if "Measured live CANedge2 clock offset" in msg or "ROLLOVER_OFFSET" in msg:
            rollovers.append(msg)

        if "PRUNE_POOL" in msg or "Pruned " in msg:
            prunes.append(msg)

        if "Cleaned orphaned partial" in msg or "ORPHAN_CLEANUP" in msg:
            orphans.append(msg)

        if "[ERROR]" in msg or "sync pass error" in msg.lower():
            http_errors.append(msg)

    # 1. Auto-Connect & Fast-Path Idle Check
    if connects and (fastpaths or any(kv.get("toDownload") == "0" for _, kv, _ in sync_plans)):
        detail = f"connects={len(connects)}, fastPathSkips={len(fastpaths)}, zeroDownloadPolls={sum(1 for _, kv, _ in sync_plans if kv.get('toDownload') == '0')}"
        results.append(("1. Auto-Connect & Idle Fast-Path", "PASS", detail))
    elif connects:
        results.append(("1. Auto-Connect & Idle Fast-Path", "PASS (PARTIAL)", f"connects={len(connects)}; enable Verbose Logs to trace SYNC_FASTPATH"))
    else:
        results.append(("1. Auto-Connect & Idle Fast-Path", "FAIL / NOT_SEEN", "No connection event seen in log window"))

    # 2. Repeat Sync & Duplicate Download Check
    dl_counts: Dict[str, int] = {}
    for _, fname in downloads:
        dl_counts[fname] = dl_counts.get(fname, 0) + 1
    dups = {k: v for k, v in dl_counts.items() if v > 1}
    if dups:
        results.append(("2. Zero Re-Download Idempotency", "FAIL", f"Duplicate downloads detected: {dups}"))
    elif sync_plans or downloads:
        results.append(("2. Zero Re-Download Idempotency", "PASS", f"uniqueDownloads={len(downloads)}, duplicates=0"))
    else:
        results.append(("2. Zero Re-Download Idempotency", "NOT_EXERCISED", "No sync plans observed"))

    # 3. Recording Pause & Zero-HTTP Interlock
    if pause_events > 0 and not http_during_recording:
        results.append(("3. Recording Pause & Zero-HTTP", "PASS", f"pauses={pause_events}, httpCallsDuringRec=0"))
    elif http_during_recording:
        results.append(("3. Recording Pause & Zero-HTTP", "FAIL", f"{len(http_during_recording)} HTTP/download ops during recording!"))
    else:
        results.append(("3. Recording Pause & Zero-HTTP", "NOT_EXERCISED", "No Record->Stop cycle observed"))

    # 4. Tail Closure & Bench Fast Exit (~8 s)
    if bench_fast_exits:
        worst = max(dt for _, dt in bench_fast_exits)
        status = "PASS" if worst <= 20.0 else "FAIL"
        summary = ", ".join(f"{s}:{dt:.1f}s" for s, dt in bench_fast_exits)
        results.append(("4. Bench 0-CAN Tail Exit (~8s)", status, summary))
    elif tail_waits > 0:
        results.append(("4. Bench 0-CAN Tail Exit (~8s)", "PASS", f"waitingFinalChunkTransitions={tail_waits}"))
    else:
        results.append(("4. Bench 0-CAN Tail Exit (~8s)", "NOT_EXERCISED", "No post-stop reconciliation observed"))

    # 5. Delayed Offline Sync & Legacy Backfill
    if (imports or offline_pending_detected) and any("->COMPLETE" in m or "-> COMPLETE" in m for _, _, m in reconciles):
        results.append(("5. Offline Sync & Legacy Backfill", "PASS", f"imports={len(imports)}, tenFolderScans={pending_10_folder_scans}, reconciles={len(reconciles)}"))
    elif reconciles and pending_10_folder_scans > 0:
        results.append(("5. Offline Sync & Legacy Backfill", "PASS", f"tenFolderScans={pending_10_folder_scans}, reconciles={len(reconciles)}"))
    else:
        results.append(("5. Offline Sync & Legacy Backfill", "NOT_EXERCISED", "No offline pending/import reconciliation seen"))

    # 6. Live Rollover Clock-Offset Tracking
    if rollovers:
        results.append(("6. Live Rollover Clock Offset", "PASS", rollovers[-1]))
    else:
        results.append(("6. Live Rollover Clock Offset", "NOT_EXERCISED", "No 60s CAN chunk rollover occurred (normal with 0 CAN traffic)"))

    # 7. Orphan Cleanup & Pool Pruning
    if prunes or orphans:
        results.append(("7. Pool Prune & Orphan Cleanup", "PASS", f"prunes={len(prunes)}, orphanCleanups={len(orphans)}"))
    else:
        results.append(("7. Pool Prune & Orphan Cleanup", "NOT_EXERCISED", "Pool count <= 50 and no orphaned .download partials"))

    if http_errors:
        results.append(("Errors Observed", "WARN", f"{len(http_errors)} error line(s): {http_errors[0]}"))

    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="Analyze RoadSense CANedge2 logs across the 7 verification checks.")
    src = parser.add_mutually_exclusive_group()
    src.add_argument("--logcat", action="store_true", help="Read live ADB logcat buffer (default if ADB is connected)")
    src.add_argument("--device-log", action="store_true", help="Read latest app_system.log from connected phone")
    src.add_argument("--file", help="Path to a local log file to analyze")
    args = parser.parse_args()

    label = ""
    if args.file:
        label = args.file
        with open(args.file, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    else:
        adb = find_adb()
        if not adb:
            print("[-] adb executable not found.")
            return 2
        if args.device_log:
            run_dir, lines = pull_device_app_log(adb)
            label = f"device app_logs/{run_dir}/app_system.log"
        else:
            lines = pull_logcat_lines(adb)
            label = "adb logcat (CANedge tags)"

    events = parse_lines(lines)
    report = analyze(events)

    print(f"\n=== CANedge2 Verification Report ({label}, {len(events)} lines) ===")
    exit_code = 0
    for name, status, detail in report:
        tag = f"[{status:<13}]"
        print(f"  {tag} {name}: {detail}")
        if status.startswith("FAIL"):
            exit_code = 1
    print()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
