#!/usr/bin/env python3
"""
Phase 2 Live CANedge2 + Android Verification Tool (verify_canedge_live.py)

Audits CANedge2 over Wi-Fi (sequential single-connection HTTP per ESP32 safety),
benchmarks time.cloudflare.com NTP UTC vs Android ADB clock vs CANedge .MF4
HDBLOCK timestamps, and verifies phone canedge_pool/ + can_sync_manifest.json
+ session_*/can/ completeness.

Usage:
    python scripts/verify_canedge_live.py
    python scripts/verify_canedge_live.py --ip 10.225.54.240 --watch-sync
    python scripts/verify_canedge_live.py --no-adb --ip 7AC5E17F
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


DEFAULT_DEVICE_ID = "7AC5E17F"
DEFAULT_HOSTS = ["7AC5E17F", "7AC5E17F.local", "192.168.1.1"]
PHONE_BASE_DIR = "/sdcard/Android/data/com.bajajauto.roadsense/files"
NTP_SERVER = "time.cloudflare.com"
NTP_EPOCH_DELTA = 2208988800  # 1900-01-01 to 1970-01-01 in seconds
MIN_VALID_EPOCH_MS = 946_684_800_000  # 2000-01-01T00:00:00Z


def find_adb(explicit_adb: Optional[str] = None) -> Optional[str]:
    if explicit_adb and os.path.isfile(explicit_adb):
        return explicit_adb
    which_adb = shutil.which("adb")
    if which_adb:
        return which_adb
    candidates = [
        os.path.expanduser(r"~\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def run_adb(adb_path: str, args: List[str], timeout: float = 8.0) -> Tuple[int, str, str]:
    try:
        proc = subprocess.run(
            [adb_path] + args,
            capture_output=True,
            text=True,
            timeout=timeout,
            encoding="utf-8",
            errors="replace",
        )
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except subprocess.TimeoutExpired:
        return 124, "", "adb timeout"
    except Exception as exc:
        return 1, "", str(exc)


def query_ntp_utc(server: str = NTP_SERVER, timeout: float = 3.0) -> Optional[Tuple[float, float]]:
    """Returns (utc_epoch_sec, rtt_ms) from UDP/123 SNTP query, or None on offline/error."""
    pkt = b"\x1b" + 47 * b"\x00"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        t0 = time.monotonic()
        sock.sendto(pkt, (server, 123))
        data, _ = sock.recvfrom(1024)
        rtt_ms = (time.monotonic() - t0) * 1000.0
        if len(data) < 48:
            return None
        sec, frac = struct.unpack("!II", data[40:48])
        utc_sec = (sec - NTP_EPOCH_DELTA) + (frac / 4294967296.0)
        return utc_sec, rtt_ms
    except Exception:
        return None
    finally:
        sock.close()


def parse_mdf4_hdblock(raw_bytes: bytes) -> Optional[Dict[str, Any]]:
    """Zero-dependency MDF4 IDBLOCK + HDBLOCK parser matching Mdf4HeaderInspector.kt."""
    if len(raw_bytes) < 96:
        return None
    try:
        magic = raw_bytes[0:8].decode("ascii", errors="replace")
    except Exception:
        return None
    if magic not in ("MDF     ", "UnFinMF "):
        return None
    if raw_bytes[64:68] != b"##HD":
        return None
    link_count = struct.unpack_from("<Q", raw_bytes, 64 + 16)[0]
    if link_count > 64:
        return None
    time_offset = 64 + 24 + (link_count * 8)
    if time_offset + 8 > len(raw_bytes):
        return None
    start_time_ns = struct.unpack_from("<Q", raw_bytes, time_offset)[0]
    start_time_ms = start_time_ns // 1_000_000
    if start_time_ms < MIN_VALID_EPOCH_MS:
        return None
    return {
        "magic": magic.strip(),
        "finalized": magic == "MDF     ",
        "start_time_ns": start_time_ns,
        "start_time_ms": start_time_ms,
        "start_time_utc": dt.datetime.fromtimestamp(start_time_ms / 1000.0, tz=dt.timezone.utc).isoformat(),
    }


def http_get_sequential(url: str, timeout: float = 4.0, headers: Optional[Dict[str, str]] = None) -> Tuple[Optional[int], bytes]:
    """Single-connection sequential HTTP GET with Connection: close for ESP32 safety."""
    req_headers = {"Connection": "close"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, b""
    except Exception:
        return None, b""


def discover_canedge_base(explicit_ip: Optional[str], adb_path: Optional[str]) -> Optional[str]:
    candidates: List[str] = []
    if explicit_ip:
        host = explicit_ip.strip().rstrip("/")
        if not host.startswith("http://") and not host.startswith("https://"):
            host = f"http://{host}"
        candidates.append(host)

    # Check ADB logcat or ARP/ip neigh on Android if available to find hotspot-assigned IP
    if adb_path and not explicit_ip:
        rc, out, _ = run_adb(adb_path, ["shell", "ip", "neigh"])
        if rc == 0 and out:
            for line in out.splitlines():
                m = re.match(r"^(\d+\.\d+\.\d+\.\d+)\s+", line.strip())
                if m:
                    ip = m.group(1)
                    if ip not in [c.replace("http://", "") for c in candidates]:
                        candidates.append(f"http://{ip}")

    for h in DEFAULT_HOSTS:
        url = f"http://{h}"
        if url not in candidates:
            candidates.append(url)

    for base in candidates:
        for endpoint in ("/device.json", "/api/device.json"):
            status, body = http_get_sequential(f"{base}{endpoint}", timeout=2.5)
            if status == 200 and body:
                try:
                    data = json.loads(body.decode("utf-8", errors="replace"))
                    if isinstance(data, dict) and ("id" in data or "fw_ver" in data or "device_id" in data):
                        return base
                except Exception:
                    pass
    return None


def fetch_canedge_json(base_url: str, filename: str) -> Optional[Dict[str, Any]]:
    for path in (f"/{filename}", f"/api/{filename}"):
        status, body = http_get_sequential(f"{base_url}{path}", timeout=4.0)
        if status == 200 and body:
            try:
                return json.loads(body.decode("utf-8", errors="replace"))
            except Exception:
                return None
        time.sleep(0.15)
    return None


def list_http_hrefs(base_url: str, path: str) -> List[str]:
    status, body = http_get_sequential(f"{base_url}{path}", timeout=4.0)
    if status != 200 or not body:
        return []
    text = body.decode("utf-8", errors="replace")
    # Handle JSON array if firmware returns JSON index
    if text.lstrip().startswith("["):
        try:
            arr = json.loads(text)
            return [str(item.get("name", item) if isinstance(item, dict) else item) for item in arr]
        except Exception:
            pass
    hrefs = re.findall(r'href=["\']([^"\']+)["\']', text, flags=re.IGNORECASE)
    cleaned: List[str] = []
    for h in hrefs:
        if h in ("../", "./", "/", "/LOG/"):
            continue
        cleaned.append(h)
    return cleaned


def audit_rtc_config(cfg: Dict[str, Any]) -> Tuple[bool, List[str]]:
    rtc = cfg.get("rtc")
    if not isinstance(rtc, dict):
        return False, ["Missing 'rtc' object in config-01.09.json"]
    notes: List[str] = []
    ok = True

    sync_val = rtc.get("sync")
    tol_val = rtc.get("tolerance")
    ntp_val = rtc.get("ntp_server")

    if sync_val != 2:
        ok = False
        notes.append(f"rtc.sync={sync_val} (expected 2)")
    else:
        notes.append("rtc.sync=2 [OK]")

    if isinstance(tol_val, (int, float)) and 2 <= tol_val <= 5:
        notes.append(f"rtc.tolerance={tol_val}s [OK]")
    else:
        ok = False
        notes.append(f"rtc.tolerance={tol_val} (expected 3, safe range 2..5)")

    if ntp_val == "time.cloudflare.com":
        notes.append("rtc.ntp_server=time.cloudflare.com [OK]")
    else:
        ok = False
        notes.append(f"rtc.ntp_server={ntp_val!r} (expected 'time.cloudflare.com')")

    return ok, notes


def inspect_phone_via_adb(adb_path: str) -> Dict[str, Any]:
    report: Dict[str, Any] = {"connected": False}
    rc, devs, _ = run_adb(adb_path, ["devices"])
    if rc != 0:
        return report
    lines = [l.strip() for l in devs.splitlines()[1:] if l.strip() and "\tdevice" in l]
    if not lines:
        return report
    report["connected"] = True
    report["device_serial"] = lines[0].split("\t")[0]

    rc_t, out_t, _ = run_adb(adb_path, ["shell", "date", "+%s.%N"])
    if rc_t == 0:
        try:
            report["phone_utc_sec"] = float(out_t.strip())
        except ValueError:
            pass

    # Manifest
    rc_m, out_m, _ = run_adb(adb_path, ["shell", "cat", f"{PHONE_BASE_DIR}/sessions/can_sync_manifest.json"])
    if rc_m == 0 and out_m.strip().startswith("{"):
        try:
            report["manifest"] = json.loads(out_m)
        except Exception:
            report["manifest_parse_error"] = True

    # Pool listing
    rc_p, out_p, _ = run_adb(adb_path, ["shell", "ls", "-1", f"{PHONE_BASE_DIR}/sessions/canedge_pool"])
    if rc_p == 0:
        pool_files = sorted([f.strip() for f in out_p.splitlines() if f.strip().lower().endswith(".mf4")])
        report["pool_files"] = pool_files
        if pool_files:
            newest = pool_files[-1]
            report["newest_pool_file"] = newest
            # Read first 256 bytes via adb exec-out head -c 256
            try:
                proc = subprocess.run(
                    [adb_path, "exec-out", f"head -c 256 {PHONE_BASE_DIR}/sessions/canedge_pool/{newest}"],
                    capture_output=True,
                    timeout=5.0,
                )
                if proc.returncode == 0 and len(proc.stdout) >= 96:
                    hd = parse_mdf4_hdblock(proc.stdout)
                    if hd:
                        report["newest_pool_hdblock"] = hd
            except Exception:
                pass

    # Recent sessions summary
    rc_s, out_s, _ = run_adb(adb_path, ["shell", "ls", "-1", f"{PHONE_BASE_DIR}/sessions"])
    if rc_s == 0:
        sess_dirs = sorted([d.strip() for d in out_s.splitlines() if d.strip().startswith("session_")])
        recent_sessions = []
        for sdir in sess_dirs[-5:]:
            rc_c, out_c, _ = run_adb(adb_path, ["shell", "ls", "-1", f"{PHONE_BASE_DIR}/sessions/{sdir}/can"])
            mf4s = []
            if rc_c == 0:
                mf4s = sorted([f.strip() for f in out_c.splitlines() if f.strip().lower().endswith(".mf4")])
            recent_sessions.append({"session": sdir, "can_mf4_count": len(mf4s), "files": mf4s})
        report["recent_sessions"] = recent_sessions

    return report


def run_single_audit(args: argparse.Namespace, adb_path: Optional[str]) -> int:
    now_pc = time.time()
    print("=" * 76)
    print(f"RoadSense Phase 2 Live Verifier | {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 76)

    # 1. NTP Benchmark
    ntp_res = query_ntp_utc()
    if ntp_res:
        ntp_utc, rtt_ms = ntp_res
        pc_delta_ms = (ntp_utc - now_pc) * 1000.0
        print(f"[NTP] {NTP_SERVER} reachable: RTT={rtt_ms:.1f} ms | NTP-PC={pc_delta_ms:+.1f} ms")
    else:
        ntp_utc = None
        print(f"[NTP] {NTP_SERVER} unreachable (offline hotspot / no internet mode)")

    # 2. Phone ADB Audit
    phone_report: Dict[str, Any] = {}
    if adb_path and not args.no_adb:
        phone_report = inspect_phone_via_adb(adb_path)
        if phone_report.get("connected"):
            p_utc = phone_report.get("phone_utc_sec")
            delta_str = ""
            if p_utc and ntp_utc:
                delta_str = f" | NTP-Phone={(ntp_utc - p_utc)*1000.0:+.1f} ms"
            print(f"[ADB] Phone connected ({phone_report.get('device_serial')}) | Phone UTC={p_utc}{delta_str}")

            manifest = phone_report.get("manifest")
            if isinstance(manifest, dict):
                off = manifest.get("lastClockOffsetMs")
                sessions = manifest.get("sessions", [])
                pending = [s.get("sessionDirName") for s in sessions if s.get("status") != "COMPLETE"]
                print(f"[ADB] Manifest: lastClockOffsetMs={off} ms | sessions={len(sessions)} | pending={pending}")
            pool_files = phone_report.get("pool_files", [])
            print(f"[ADB] canedge_pool/: {len(pool_files)} .MF4 files"
                  + (f" (newest: {pool_files[-1]})" if pool_files else ""))
            hd = phone_report.get("newest_pool_hdblock")
            if hd and p_utc:
                hd_utc = hd["start_time_ms"] / 1000.0
                # Note: start_time_ms is chunk START; compare reasonably against phone time
                print(
                    f"[ADB] Newest Pool HDBLOCK: magic={hd['magic']!r} start_utc={hd['start_time_utc']} "
                    f"(PhoneNow - ChunkStart = {p_utc - hd_utc:+.2f} s)"
                )
            for rs in phone_report.get("recent_sessions", []):
                print(f"      - {rs['session']}: {rs['can_mf4_count']} CAN .MF4 file(s)")
        else:
            print("[ADB] No Android device detected over ADB")

    # 3. CANedge2 Wi-Fi Audit
    base_url = discover_canedge_base(args.ip, adb_path if not args.no_adb else None)
    if not base_url:
        print("\n[CANEDGE] Not reachable over Wi-Fi from this PC.")
        print("          Ensure this PC is on the M21 Wi-Fi hotspot (or pass --ip <canedge-ip>)")
        print("          if CANedge2 is currently powered on.")
        return 2

    print(f"\n[CANEDGE] Connected at {base_url}")
    dev_json = fetch_canedge_json(base_url, "device.json")
    if dev_json:
        print(
            f"[CANEDGE] Device ID={dev_json.get('id', dev_json.get('device_id', '?'))} "
            f"| FW={dev_json.get('fw_ver', '?')} | LogSpace={dev_json.get('space_used_mb', '?')} MB"
        )
    else:
        print("[CANEDGE] Warning: Could not read device.json")

    cfg_json = fetch_canedge_json(base_url, "config-01.09.json")
    rtc_ok = False
    if cfg_json:
        rtc_ok, notes = audit_rtc_config(cfg_json)
        print(f"[CANEDGE] config-01.09.json RTC audit: {'PASS' if rtc_ok else 'NEEDS UPDATE'}")
        for n in notes:
            print(f"          - {n}")
    else:
        print("[CANEDGE] Warning: Could not read config-01.09.json")

    # 4. Sequential /LOG/ inspection + Range HDBLOCK probe on newest chunk
    time.sleep(0.15)
    dev_folders = sorted([f.strip("/") for f in list_http_hrefs(base_url, "/LOG/") if not f.startswith("?")])
    print(f"[CANEDGE] /LOG/ entries: {dev_folders}")
    newest_chunk_url: Optional[str] = None
    newest_chunk_name: Optional[str] = None
    for folder in dev_folders:
        if folder.upper() == DEFAULT_DEVICE_ID or len(dev_folders) == 1:
            sub_folders = sorted([s.strip("/") for s in list_http_hrefs(base_url, f"/LOG/{folder}/") if s.strip("/").isdigit()])
            if sub_folders:
                latest_dir = sub_folders[-1]
                files = sorted([
                    fn.strip("/") for fn in list_http_hrefs(base_url, f"/LOG/{folder}/{latest_dir}/")
                    if fn.lower().endswith(".mf4")
                ])
                print(f"[CANEDGE] Latest SD folder /LOG/{folder}/{latest_dir}/: {len(files)} .MF4 file(s)"
                      + (f" (latest: {files[-1]})" if files else ""))
                if files:
                    newest_chunk_name = f"{latest_dir}/{files[-1]}"
                    newest_chunk_url = f"{base_url}/LOG/{folder}/{latest_dir}/{files[-1]}"

    if newest_chunk_url:
        time.sleep(0.15)
        st, hdr_bytes = http_get_sequential(newest_chunk_url, timeout=4.0, headers={"Range": "bytes=0-255"})
        if st in (200, 206) and len(hdr_bytes) >= 96:
            hd = parse_mdf4_hdblock(hdr_bytes[:256])
            if hd:
                ref_utc = ntp_utc or phone_report.get("phone_utc_sec") or time.time()
                age_s = ref_utc - (hd["start_time_ms"] / 1000.0)
                print(
                    f"[CANEDGE] Live SD Chunk {newest_chunk_name} HDBLOCK: magic={hd['magic']!r} "
                    f"| start_utc={hd['start_time_utc']} | RefNow - ChunkStart = {age_s:+.2f} s"
                )
        else:
            print(f"[CANEDGE] Live chunk header probe skipped (HTTP {st})")

    return 0 if rtc_ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="RoadSense Phase 2 CANedge2 Live Desk & Vehicle Verifier")
    parser.add_argument("--ip", default=None, help="Explicit CANedge2 IP or hostname (e.g. 10.225.54.240)")
    parser.add_argument("--watch-sync", action="store_true", help="Continuously poll every --interval seconds")
    parser.add_argument("--interval", type=float, default=8.0, help="Polling interval in seconds for --watch-sync")
    parser.add_argument("--adb", default=None, help="Explicit path to adb executable")
    parser.add_argument("--no-adb", action="store_true", help="Skip Android ADB checks")
    args = parser.parse_args()

    adb_path = None if args.no_adb else find_adb(args.adb)
    if not args.no_adb and not adb_path:
        print("[!] adb not found; continuing with Wi-Fi / NTP checks only.")

    if not args.watch_sync:
        return run_single_audit(args, adb_path)

    print(f"[*] Starting --watch-sync loop (interval={args.interval}s). Press Ctrl+C to stop.")
    try:
        while True:
            run_single_audit(args, adb_path)
            time.sleep(max(2.0, args.interval))
    except KeyboardInterrupt:
        print("\n[*] Stopped --watch-sync.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
