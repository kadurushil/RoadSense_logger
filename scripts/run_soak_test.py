#!/usr/bin/env python3
"""
RoadSense Unattended ADB Bench Soak Orchestrator (Tier 3 — Plan 112)
====================================================================

Monitors a live RoadSense Android instance over Wired or Wireless ADB for 2–60+ minutes:
  1. Periodic dumpsys polling (meminfo, gfxinfo, thermalservice, battery, pid, disk growth)
  2. Concurrent logcat watermark collector (UiPerf [WINDOW_5S], radar drops/salvaged,
     queue watermarks, USB detach/recovery, DisplayPacer drops, MediaRecorder errors, FATAL/ANR)
  3. Built-in Wireless ADB setup helper (`--setup-wireless-adb`, works on shared Wi-Fi or
     SM-M325F's own `M21` hotspot so the AWR1843BOOST radar can occupy the phone's USB-C port)
  4. Automatic post-soak session pull, MCAP conversion, 8-domain `session_health_check.py`,
     and `verify_conversion_parity.py` certification chain (on by default; opt out via `--skip-post-audit`)

Usage:
  python scripts/run_soak_test.py --setup-wireless-adb
  python scripts/run_soak_test.py --duration-min 30 --interval-sec 15
  python scripts/run_soak_test.py --duration-min 5 --interval-sec 10 --serial 192.168.43.1:5555
"""

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from scripts.soak_analyzer import (
    DEFAULT_SOAK_ROOT,
    analyze_soak_run,
    parse_battery,
    parse_gfxinfo,
    parse_meminfo,
    parse_thermalservice,
    parse_uiperf_window,
    run_post_soak_session_audit,
)

PACKAGE_NAME = "com.bajajauto.roadsense"
DEVICE_SESSIONS_DIR = f"/sdcard/Android/data/{PACKAGE_NAME}/files/sessions"


def find_adb() -> Optional[str]:
    adb = shutil.which("adb")
    if adb:
        return adb
    candidates = [
        os.path.expanduser(r"~\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
        r"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def adb_run(adb_path: str, args: List[str], serial: Optional[str] = None, timeout: int = 15) -> Tuple[int, str, str]:
    cmd = [adb_path]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        return res.returncode, res.stdout, res.stderr
    except subprocess.TimeoutExpired:
        return -1, "", f"ADB command timed out after {timeout}s"
    except Exception as e:
        return -1, "", str(e)


def list_connected_devices(adb_path: str) -> List[str]:
    rc, out, _ = adb_run(adb_path, ["devices"], timeout=5)
    if rc != 0:
        return []
    devices = []
    for line in out.strip().splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def resolve_device_serial(adb_path: str, requested_serial: Optional[str]) -> str:
    devices = list_connected_devices(adb_path)
    if requested_serial:
        if requested_serial in devices:
            return requested_serial
        # If IP:port passed and not yet connected, try adb connect
        if ":" in requested_serial:
            print(f"[*] Attempting wireless ADB connect to {requested_serial}...")
            adb_run(adb_path, ["connect", requested_serial], timeout=8)
            devices = list_connected_devices(adb_path)
            if requested_serial in devices:
                return requested_serial
        sys.exit(f"[!] Requested ADB device '{requested_serial}' not found. Connected: {devices}")

    if not devices:
        sys.exit("[!] No ADB devices connected. Connect via USB or run --setup-wireless-adb.")
    # Prefer wireless TCP device if both USB and wireless are connected (so user can unplug USB freely)
    tcp_devs = [d for d in devices if ":" in d]
    return tcp_devs[0] if tcp_devs else devices[0]


def setup_wireless_adb(adb_path: str, port: int = 5555) -> int:
    """
    Switches a USB-connected phone into ADB TCP/IP mode, discovers its Wi-Fi or Hotspot
    (`wlan0` / `ap0` / `swlan0`, including `M21` hotspot) IPv4 address, and connects wirelessly.
    """
    devices = list_connected_devices(adb_path)
    usb_devs = [d for d in devices if ":" not in d]
    if not usb_devs:
        if devices:
            print(f"[+] Wireless ADB already connected: {devices[0]}")
            return 0
        print("[!] Connect the phone via USB cable first, then run --setup-wireless-adb.")
        return 1

    usb_serial = usb_devs[0]
    print(f"[*] Enabling Wireless ADB (TCP port {port}) on {usb_serial}...")
    rc, out, err = adb_run(adb_path, ["tcpip", str(port)], serial=usb_serial, timeout=10)
    if rc != 0:
        print(f"[!] adb tcpip {port} failed: {err or out}")
        return 1
    time.sleep(1.5)

    # Probe wlan0, swlan0, ap0 (covers both shared Wi-Fi and Samsung's M21 Mobile Hotspot!)
    candidate_ips: List[Tuple[str, str]] = []
    for iface in ("wlan0", "swlan0", "ap0"):
        rc_ip, ip_out, _ = adb_run(adb_path, ["shell", f"ip -f inet addr show {iface}"], serial=usb_serial, timeout=5)
        if rc_ip == 0:
            for m in re.finditer(r"inet\s+(\d+\.\d+\.\d+\.\d+)/", ip_out):
                ip = m.group(1)
                if not ip.startswith("127."):
                    candidate_ips.append((iface, ip))

    if not candidate_ips:
        print("[!] Could not detect a Wi-Fi or Hotspot IP on the phone.")
        print("    Tip: Connect your PC and phone to the same Wi-Fi, OR enable the phone's 'M21' hotspot and connect your PC to 'M21'.")
        return 1

    connected_endpoint = None
    for iface, ip in candidate_ips:
        endpoint = f"{ip}:{port}"
        print(f"[*] Trying adb connect {endpoint} (interface {iface})...")
        rc_c, c_out, c_err = adb_run(adb_path, ["connect", endpoint], timeout=8)
        msg = (c_out or c_err).strip()
        print(f"    -> {msg}")
        if rc_c == 0 and ("connected to" in msg.lower() or "already connected" in msg.lower()):
            connected_endpoint = endpoint
            break

    if not connected_endpoint:
        print("[!] Could not reach the phone over Wi-Fi from this PC.")
        print(f"    Detected phone IP(s): {candidate_ips}")
        print("    Ensure your PC is on the exact same Wi-Fi network (or connected to the phone's 'M21' hotspot).")
        return 1

    print("\n" + "=" * 80)
    print(f"[+] WIRELESS ADB READY: {connected_endpoint}")
    print("    1. You can now safely UNPLUG the USB-C cable from this PC.")
    print("    2. Plug the TI AWR1843BOOST Radar into the phone's USB-C port via OTG.")
    print(f"    3. Run your full 5-sensor soak:")
    print(f"       python scripts/run_soak_test.py --duration-min 30 --serial {connected_endpoint}")
    print("=" * 80)
    return 0


class LogcatWatermarkCollector:
    """
    Background thread streaming `adb logcat` during the soak to track crashes, ANRs,
    UiPerf [WINDOW_5S] rollups, radar drops/salvages, queue watermarks, and USB events.
    """

    def __init__(self, adb_path: str, serial: str, log_path: str, soak_start_monotonic: float):
        self.adb_path = adb_path
        self.serial = serial
        self.log_path = log_path
        self.soak_start_monotonic = soak_start_monotonic
        self.proc: Optional[subprocess.Popen] = None
        self.thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.counters: Dict[str, Any] = {
            "fatal_crashes": 0,
            "anr_count": 0,
            "radar_frame_drops": 0,
            "radar_frame_salvaged": 0,
            "queue_chunks_dropped": 0,
            "usb_detaches": 0,
            "usb_reconnects": 0,
            "recorder_errors": 0,
            "display_pacer_drops": 0,
            "thermal_changes": 0,
            "low_storage_warns": 0,
            "uiperf_windows": [],
            "fatal_lines": [],
        }

    def start(self) -> None:
        self.thread = threading.Thread(target=self._run, name="SoakLogcatWatcher", daemon=True)
        self.thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        proc = self.proc
        if proc is not None:
            try:
                proc.terminate()
                proc.wait(timeout=3)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
        if self.thread is not None:
            self.thread.join(timeout=3)

    def snapshot_summary(self) -> Dict[str, Any]:
        with self.lock:
            return {
                **{k: v for k, v in self.counters.items() if k not in ("uiperf_windows", "fatal_lines")},
                "uiperf_windows": list(self.counters["uiperf_windows"]),
                "fatal_lines": list(self.counters["fatal_lines"]),
            }

    def _run(self) -> None:
        tags = [
            "UiPerf:I",
            "DisplayPacer:W",
            "RadarSession:I",
            "RadarQueue:W",
            "RadarConnectionManager:I",
            "CameraEngine:W",
            "Thermal:W",
            "Storage:W",
            "CanedgeIngestion:I",
            "LifecycleDiag:I",
            "AndroidRuntime:E",
            "ActivityManager:E",
            "*:S",
        ]
        cmd = [self.adb_path, "-s", self.serial, "logcat", "-v", "time", "-T", "1"] + tags
        try:
            self.proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
            with open(self.log_path, "w", encoding="utf-8") as lf:
                assert self.proc.stdout is not None
                for raw_line in self.proc.stdout:
                    if self.stop_event.is_set():
                        break
                    line = raw_line.rstrip("\r\n")
                    lf.write(line + "\n")
                    self._process_line(line)
        except Exception:
            pass

    def _process_line(self, line: str) -> None:
        elapsed_sec = round(time.monotonic() - self.soak_start_monotonic, 1)
        with self.lock:
            if "[WINDOW_5S]" in line:
                parsed = parse_uiperf_window(line)
                if parsed:
                    parsed["elapsed_sec"] = elapsed_sec
                    self.counters["uiperf_windows"].append(parsed)
                return

            if "FATAL EXCEPTION" in line or ("AndroidRuntime" in line and "Process: " + PACKAGE_NAME in line):
                self.counters["fatal_crashes"] += 1
                if len(self.counters["fatal_lines"]) < 20:
                    self.counters["fatal_lines"].append(line)
            elif "ANR in " + PACKAGE_NAME in line:
                self.counters["anr_count"] += 1
                if len(self.counters["fatal_lines"]) < 20:
                    self.counters["fatal_lines"].append(line)
            elif "RADAR_FRAME_DROP" in line:
                m_gap = re.search(r"Gap of (\d+) frames", line)
                self.counters["radar_frame_drops"] += int(m_gap.group(1)) if m_gap else 1
            elif "RADAR_FRAME_SALVAGED" in line:
                self.counters["radar_frame_salvaged"] += 1
            elif "rawByteQueue FULL" in line:
                self.counters["queue_chunks_dropped"] += 1
            elif "USB_DETACHED" in line:
                self.counters["usb_detaches"] += 1
            elif "USB_RECONNECTED" in line:
                self.counters["usb_reconnects"] += 1
            elif "MediaRecorder error" in line or "RECORDER_ERROR" in line:
                self.counters["recorder_errors"] += 1
            elif "DisplayPacer" in line and ("[DROP]" in line or "[BLOAT_TRIM]" in line):
                self.counters["display_pacer_drops"] += 1
            elif "THERMAL_CHANGE" in line:
                self.counters["thermal_changes"] += 1
            elif "LOW_STORAGE_WARN" in line or "CRITICAL_AUTO_STOP" in line:
                self.counters["low_storage_warns"] += 1


def query_device_metadata(adb_path: str, serial: str) -> Dict[str, Any]:
    def _prop(name: str) -> str:
        rc, out, _ = adb_run(adb_path, ["shell", "getprop", name], serial=serial, timeout=5)
        return out.strip() if rc == 0 else "Unknown"

    app_version = "Unknown"
    rc, pkg_out, _ = adb_run(adb_path, ["shell", f"dumpsys package {PACKAGE_NAME}"], serial=serial, timeout=8)
    if rc == 0:
        m_vn = re.search(r"versionName=([^\s]+)", pkg_out)
        m_vc = re.search(r"versionCode=(\d+)", pkg_out)
        if m_vn:
            app_version = m_vn.group(1) + (f" (code {m_vc.group(1)})" if m_vc else "")

    return {
        "serial": serial,
        "connection_mode": "wireless_adb" if ":" in serial else "usb_adb",
        "model": _prop("ro.product.model"),
        "manufacturer": _prop("ro.product.manufacturer"),
        "android_release": _prop("ro.build.version.release"),
        "sdk_int": _prop("ro.build.version.sdk"),
        "app_version": app_version,
    }


def list_remote_sessions(adb_path: str, serial: str) -> List[str]:
    rc, out, _ = adb_run(adb_path, ["shell", f"ls -1 {DEVICE_SESSIONS_DIR} 2>/dev/null"], serial=serial, timeout=5)
    if rc != 0:
        return []
    return sorted(line.strip() for line in out.splitlines() if line.strip().startswith("session_"))


def get_remote_session_size_mb(adb_path: str, serial: str, session_id: str) -> Optional[float]:
    rc, out, _ = adb_run(adb_path, ["shell", f"du -sk {DEVICE_SESSIONS_DIR}/{session_id} 2>/dev/null"], serial=serial, timeout=5)
    if rc == 0 and out.strip():
        parts = out.strip().split()
        if parts and parts[0].isdigit():
            return round(int(parts[0]) / 1024.0, 2)
    return None


def is_remote_session_closed(adb_path: str, serial: str, session_id: str) -> bool:
    rc, out, _ = adb_run(
        adb_path,
        ["shell", f"cat {DEVICE_SESSIONS_DIR}/{session_id}/session_metadata.json 2>/dev/null"],
        serial=serial,
        timeout=5,
    )
    if rc == 0 and out.strip():
        try:
            meta = json.loads(out)
            return bool(meta.get("stopTimeWallMs") or meta.get("stopTimeIso"))
        except Exception:
            pass
    return False


def pull_and_process_session(session_id: str) -> Optional[str]:
    """
    Invokes `tools/sync_and_process_sessions.py --session <id> --mcap` to pull the soak
    session from the device and build track_history.json + frame_mapping.json + .mcap.
    """
    sync_script = os.path.join(REPO_ROOT, "tools", "sync_and_process_sessions.py")
    if not os.path.isfile(sync_script):
        print(f"[-] Sync script not found: {sync_script}")
        return None

    cmd = [sys.executable, "-u", sync_script, "--session", session_id, "--mcap"]
    print(f"\n[*] Pulling & converting soak session '{session_id}' via sync_and_process_sessions.py...")
    res = subprocess.run(cmd, cwd=REPO_ROOT)
    local_dir = os.path.join(REPO_ROOT, "logs", session_id)
    if res.returncode == 0 and os.path.isdir(local_dir):
        print(f"[+] Pulled & processed soak session -> {local_dir}")
        return local_dir
    print(f"[-] Session sync/processing returned exit code {res.returncode}")
    return local_dir if os.path.isdir(local_dir) else None


def main() -> None:
    parser = argparse.ArgumentParser(description="RoadSense Unattended ADB Bench Soak Orchestrator")
    parser.add_argument("--duration-min", type=float, default=30.0, help="Soak duration in minutes (default: 30)")
    parser.add_argument("--interval-sec", type=int, default=15, help="Telemetry poll interval in seconds (default: 15)")
    parser.add_argument("--serial", type=str, default=None, help="ADB device serial or Wireless IP:port")
    parser.add_argument("--setup-wireless-adb", action="store_true", help="Configure Wireless ADB (TCP 5555) and exit")
    parser.add_argument("--skip-post-audit", action="store_true", help="Skip automatic session pull, health check & MCAP parity after soak")
    parser.add_argument("--no-parity", action="store_true", help="Run health check after soak but skip MCAP conversion parity")
    parser.add_argument("--no-html", action="store_true", help="Skip generating soak_report.html")
    parser.add_argument("--no-wait-for-record", action="store_true", help="Start soak timer immediately even if no recording is active yet")
    parser.add_argument("--auto-stop", action="store_true", help="Send ADB debug broadcast to cleanly stop recording when soak window ends")
    parser.add_argument("--output-dir", type=str, default=None, help="Custom directory for soak artifacts")
    args = parser.parse_args()

    adb_path = find_adb()
    if not adb_path:
        sys.exit("[!] Could not locate adb.exe. Ensure Android SDK platform-tools is installed.")

    if args.setup_wireless_adb:
        sys.exit(setup_wireless_adb(adb_path))

    serial = resolve_device_serial(adb_path, args.serial)
    print("=" * 80)
    print(" ROADSENSE BENCH SOAK & RELIABILITY CERTIFICATION HARNESS")
    print("=" * 80)
    print(f"  ADB Target:   {serial}")
    print(f"  Duration:     {args.duration_min:.1f} min  |  Poll Interval: {args.interval_sec} s")
    print(f"  Post-Audit:   {'DISABLED (--skip-post-audit)' if args.skip_post_audit else ('Health Check only' if args.no_parity else 'Auto Pull + MCAP + Health Check + Conversion Parity')}")
    print("=" * 80)

    # Verify RoadSense process is running
    rc_pid, pid_out, _ = adb_run(adb_path, ["shell", f"pidof {PACKAGE_NAME}"], serial=serial, timeout=5)
    initial_pid = pid_out.strip().split()[0] if (rc_pid == 0 and pid_out.strip()) else None
    if not initial_pid:
        sys.exit(f"[!] {PACKAGE_NAME} is not currently running on {serial}. Launch RoadSense on the phone first.")

    device_meta = query_device_metadata(adb_path, serial)
    print(f"[+] Connected to {device_meta['model']} (Android {device_meta['android_release']}, App {device_meta['app_version']}, PID={initial_pid})")

    # Prepare soak output folder
    run_stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    soak_dir = args.output_dir or os.path.join(DEFAULT_SOAK_ROOT, f"soak_{run_stamp}")
    os.makedirs(soak_dir, exist_ok=True)
    with open(os.path.join(soak_dir, "soak_meta.json"), "w", encoding="utf-8") as mf:
        json.dump(device_meta, mf, indent=2)

    stop_requested = threading.Event()

    def _handle_sig(sig, _frame):
        print("\n[!] Interrupt received — stopping soak polling cleanly and generating certification report...")
        stop_requested.set()

    signal.signal(signal.SIGINT, _handle_sig)
    signal.signal(signal.SIGTERM, _handle_sig)

    baseline_sessions = set(list_remote_sessions(adb_path, serial))

    # Check if a session is already actively recording right now (not yet closed)
    active_session_id: Optional[str] = None
    if baseline_sessions:
        newest = sorted(baseline_sessions)[-1]
        if not is_remote_session_closed(adb_path, serial, newest):
            active_session_id = newest
            print(f"[+] Detected active recording session already in progress: {active_session_id}", flush=True)

    # By default, wait for the user to tap RECORD on the phone before starting the soak timer
    if active_session_id is None and not args.no_wait_for_record:
        print(
            f"[*] Waiting for RECORD on {device_meta['model']} before starting the {args.duration_min:.1f}-min soak timer...",
            flush=True,
        )
        print("    Tap RECORD on the phone whenever ready (press Stop / Ctrl+C to cancel).", flush=True)
        wait_t0 = time.monotonic()
        while not stop_requested.is_set() and active_session_id is None:
            waited_s = int(time.monotonic() - wait_t0)
            print(
                f"[PROGRESS] {json.dumps({'step': 'wait_record', 'phase': 'WAITING FOR RECORD', 'desc': f'Waiting for RECORD on phone... ({waited_s}s)', 'pct': 0})}",
                flush=True,
            )
            cur_sessions = set(list_remote_sessions(adb_path, serial))
            new_sessions = sorted(cur_sessions - baseline_sessions)
            if new_sessions:
                active_session_id = new_sessions[-1]
                print(f"[+] Recording started! Bound to session: {active_session_id} — starting {args.duration_min:.1f}-min soak timer now.", flush=True)
                # Brief 2s settle so initial session directory & MediaRecorder buffers initialize cleanly
                time.sleep(2.0)
                break
            time.sleep(1.0)

        if stop_requested.is_set() and active_session_id is None:
            print("[-] Soak cancelled before recording started.", flush=True)
            sys.exit(0)
    elif active_session_id is None:
        print("[*] Starting soak timer immediately (--no-wait-for-record); will auto-bind if a recording starts.", flush=True)

    # Reset gfxinfo stats cleanly right as the active soak window begins
    adb_run(adb_path, ["shell", f"dumpsys gfxinfo {PACKAGE_NAME} reset"], serial=serial, timeout=8)

    t0 = time.monotonic()
    target_sec = max(10.0, args.duration_min * 60.0)
    telemetry_path = os.path.join(soak_dir, "soak_telemetry.jsonl")
    logcat_path = os.path.join(soak_dir, "soak_logcat.log")

    logcat_watcher = LogcatWatermarkCollector(adb_path, serial, logcat_path, t0)
    logcat_watcher.start()

    poll_idx = 0
    try:
        with open(telemetry_path, "a", encoding="utf-8") as tf:
            while not stop_requested.is_set():
                now_elapsed = time.monotonic() - t0
                if now_elapsed >= target_sec:
                    break

                poll_idx += 1
                rc_p, out_p, _ = adb_run(adb_path, ["shell", f"pidof {PACKAGE_NAME}"], serial=serial, timeout=5)
                pid_val = int(out_p.strip().split()[0]) if (rc_p == 0 and out_p.strip() and out_p.strip().split()[0].isdigit()) else None

                _, mem_out, _ = adb_run(adb_path, ["shell", f"dumpsys meminfo {PACKAGE_NAME}"], serial=serial, timeout=10)
                _, gfx_out, _ = adb_run(adb_path, ["shell", f"dumpsys gfxinfo {PACKAGE_NAME}"], serial=serial, timeout=10)
                _, thm_out, _ = adb_run(adb_path, ["shell", "dumpsys thermalservice"], serial=serial, timeout=8)
                _, bat_out, _ = adb_run(adb_path, ["shell", "dumpsys battery"], serial=serial, timeout=8)

                mem = parse_meminfo(mem_out)
                gfx = parse_gfxinfo(gfx_out)
                thm = parse_thermalservice(thm_out)
                bat = parse_battery(bat_out)

                # Detect newly started session if --no-wait-for-record was used
                if active_session_id is None:
                    cur_sessions = set(list_remote_sessions(adb_path, serial))
                    new_sessions = sorted(cur_sessions - baseline_sessions)
                    if new_sessions:
                        active_session_id = new_sessions[-1]
                        print(f"[+] Bound to newly started recording session: {active_session_id}", flush=True)

                sess_size_mb = (
                    get_remote_session_size_mb(adb_path, serial, active_session_id)
                    if active_session_id
                    else None
                )

                record = {
                    "poll_index": poll_idx,
                    "timestamp": datetime.now().isoformat(timespec="seconds"),
                    "elapsed_sec": round(now_elapsed, 1),
                    "pid": pid_val,
                    "active_session_id": active_session_id,
                    "session_size_mb": sess_size_mb,
                    "mem": mem,
                    "gfx": gfx,
                    "thermal": thm,
                    "battery": bat,
                }
                tf.write(json.dumps(record) + "\n")
                tf.flush()

                pss_s = f"{mem['total_pss_mb']:.0f}MB" if mem.get("total_pss_mb") is not None else "?"
                bat_s = f"{bat['temp_c']:.1f}C/{bat['level_pct']}%" if bat.get("temp_c") is not None else "?"
                sz_s = f"{sess_size_mb:.1f}MB" if sess_size_mb is not None else "idle"
                print(
                    f"  [{now_elapsed/60.0:5.1f}m / {args.duration_min:4.1f}m] "
                    f"PID={pid_val} | PSS={pss_s} (J={mem.get('java_heap_mb') or 0:.0f} N={mem.get('native_heap_mb') or 0:.0f}) | "
                    f"Therm={thm['thermal_label']} Bat={bat_s} | Disk={sz_s}",
                    flush=True,
                )

                def _emit_soak_progress(cur_el: float) -> None:
                    pct_val = min(99.0, round((cur_el / target_sec) * 100.0, 1))
                    rem_s = max(0.0, target_sec - cur_el)
                    print(
                        f"[PROGRESS] {json.dumps({'step': 'soak', 'phase': 'SOAK RECORDING', 'desc': f'Bench Soak ({cur_el/60.0:.1f}/{args.duration_min:.1f} min) • PSS {pss_s} • Bat {bat_s}', 'pct': pct_val, 'eta_s': round(rem_s, 0), 'soak': {'pss': pss_s, 'bat': bat_s, 'therm': thm['thermal_label'], 'disk': sz_s, 'session': active_session_id or 'idle'}})}",
                        flush=True,
                    )

                _emit_soak_progress(now_elapsed)

                # Sleep in 1.0s increments so progress bar animates smoothly & Stop responds immediately
                next_wake = time.monotonic() + max(2, args.interval_sec)
                while not stop_requested.is_set() and time.monotonic() < next_wake:
                    cur_el = time.monotonic() - t0
                    if cur_el >= target_sec:
                        break
                    time.sleep(1.0)
                    cur_el = min(target_sec, time.monotonic() - t0)
                    _emit_soak_progress(cur_el)
    finally:
        logcat_watcher.stop()

    logcat_summary = logcat_watcher.snapshot_summary()
    with open(os.path.join(soak_dir, "soak_logcat_summary.json"), "w", encoding="utf-8") as lsf:
        json.dump(logcat_summary, lsf, indent=2)

    print(f"\n[+] Soak telemetry window finished ({poll_idx} polls saved to {soak_dir}).")

    # Automatic Post-Soak Pull, MCAP Conversion, Health Check & Parity Chain (ON by default)
    health_dict, parity_dict = None, None
    if active_session_id and not is_remote_session_closed(adb_path, serial, active_session_id):
        if args.auto_stop:
            print(f"\n[*] Soak target reached! Sending ADB auto-stop broadcast to close '{active_session_id}' cleanly...", flush=True)
            print(
                f"[PROGRESS] {json.dumps({'step': 'wait_stop', 'phase': 'AUTO-STOPPING', 'desc': f'Sending ADB auto-stop for {active_session_id}...', 'pct': 95})}",
                flush=True,
            )
            adb_run(
                adb_path,
                [
                    "shell",
                    "am",
                    "broadcast",
                    "-a",
                    f"{PACKAGE_NAME}.action.STOP_RECORDING",
                    "-p",
                    PACKAGE_NAME,
                ],
                serial=serial,
                timeout=8,
            )
        elif not args.skip_post_audit:
            print(f"\n[*] Soak target reached! Tap STOP on the phone to close '{active_session_id}' (waiting up to 45 s for automatic pull & audit)...", flush=True)

        if args.auto_stop or not args.skip_post_audit:
            wait_deadline = time.monotonic() + 45.0
            while time.monotonic() < wait_deadline:
                rem_wait = max(0, int(wait_deadline - time.monotonic()))
                desc_msg = (
                    f"Waiting for '{active_session_id}' to finalize MP4/metadata ({rem_wait}s left)"
                    if args.auto_stop
                    else f"Soak target reached — tap STOP on phone ({rem_wait}s left)"
                )
                print(
                    f"[PROGRESS] {json.dumps({'step': 'wait_stop', 'phase': 'WAITING FOR STOP', 'desc': desc_msg, 'pct': 95})}",
                    flush=True,
                )
                if is_remote_session_closed(adb_path, serial, active_session_id):
                    print(f"[+] Detected session closure on device: {active_session_id}", flush=True)
                    time.sleep(1.5)  # allow deferred on-device audit metadata flush
                    break
                time.sleep(1.5)

    if not args.skip_post_audit and active_session_id:
        if is_remote_session_closed(adb_path, serial, active_session_id):
            print(
                f"[PROGRESS] {json.dumps({'step': 'post_audit', 'phase': 'POST-SOAK AUDIT', 'desc': f'Pulling & auditing {active_session_id}...', 'pct': 96})}",
                flush=True,
            )
            local_session_dir = pull_and_process_session(active_session_id)
            if local_session_dir:
                health_dict, parity_dict = run_post_soak_session_audit(
                    local_session_dir,
                    run_parity=not args.no_parity,
                )
        else:
            print(f"[-] Session '{active_session_id}' is still open on device — skipping automatic pull (run sync_and_process_sessions.py once stopped).", flush=True)
    elif not args.skip_post_audit and not active_session_id:
        print("[-] No recording session was started during this soak window — skipping post-soak session health & parity audit.", flush=True)

    report = analyze_soak_run(
        soak_dir=soak_dir,
        post_soak_health=health_dict,
        post_soak_parity=parity_dict,
        write_html=not args.no_html,
    )
    verdict = report.get("overall_verdict", "DONE")
    print(f"[PROGRESS] {json.dumps({'step': 'done', 'desc': f'Soak Complete: {verdict}', 'pct': 100})}", flush=True)
    sys.exit(0 if verdict != "FAIL" else 1)


if __name__ == "__main__":
    main()
