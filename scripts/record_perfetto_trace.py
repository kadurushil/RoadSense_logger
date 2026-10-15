#!/usr/bin/env python3
"""
RoadSense On-Device Perfetto Trace Capture Utility.
Captures nanosecond kernel scheduler events, hardware IRQs, CPU frequencies,
and RoadSense atrace slices on the connected Samsung Galaxy M30 (Android 10).

Usage:
    python scripts/record_perfetto_trace.py [--duration 20] [--output-dir traces]
"""

import os
import sys
import time
import argparse
import subprocess
from datetime import datetime

# Windows stdout UTF-8 reconfigure
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

def run_adb(cmd_args, capture_output=True, check=True):
    cmd = ["adb"] + cmd_args
    res = subprocess.run(cmd, capture_output=capture_output, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"ADB command failed ({res.returncode}): {' '.join(cmd)}\n{res.stderr}")
    return res

def main():
    parser = argparse.ArgumentParser(description="Capture Perfetto System Trace during RoadSense logging")
    parser.add_argument("--duration", "-d", type=int, default=20, help="Trace duration in seconds (default: 20s)")
    parser.add_argument("--buffer", "-b", type=int, default=64, help="In-memory ring buffer size in MB (default: 64MB)")
    parser.add_argument("--output-dir", "-o", default="traces", help="Host directory to save trace files (default: traces)")
    args = parser.parse_args()

    # 1. Verify ADB connection
    print("=" * 75)
    print("ROADSENSE PERFETTO TRACE CAPTURE")
    print("=" * 75)
    print("[1/5] Verifying ADB connection...")
    dev_res = run_adb(["devices"])
    lines = [l.strip() for l in dev_res.stdout.splitlines() if l.strip() and not l.startswith("List of devices")]
    active_devices = [l for l in lines if "\tdevice" in l]
    if not active_devices:
        sys.exit("Error: No active Android device detected via ADB. Please connect your phone.")
    device_id = active_devices[0].split("\t")[0]
    print(f"  Connected Device: {device_id}")

    # 2. Configure tracing flags
    print("[2/5] Configuring on-device tracing daemon...")
    run_adb(["shell", "setprop", "persist.traced.enable", "1"])
    run_adb(["shell", "setprop", "debug.atrace.tags.enableflags", "0xffffffff"])

    remote_trace_path = "/data/misc/perfetto-traces/roadsense_trace.pftrace"
    # Ensure any previous trace is removed
    run_adb(["shell", "rm", "-f", remote_trace_path], check=False)

    # 3. Setup output directory
    os.makedirs(args.output_dir, exist_ok=True)
    timestamp_str = datetime.now().strftime("%Y%m%d_%H%M%S")
    local_trace_path = os.path.join(args.output_dir, f"trace_{timestamp_str}.pftrace")

    # 4. Trigger Perfetto trace
    categories = [
        "sched/sched_switch",
        "sched/sched_wakeup",
        "sched/sched_waking",
        "irq/*",
        "power/cpu_frequency",
        "power/cpu_idle",
        "am", "app", "view", "camera", "hal"
    ]
    perfetto_cmd = (
        f"perfetto -t {args.duration}s -b {args.buffer}mb -o {remote_trace_path} "
        + " ".join(categories)
    )

    print(f"[3/5] Starting Perfetto trace capture ({args.duration}s duration)...")
    print("  -> Recording kernel scheduler, IRQs, CPU frequencies, and RoadSense app slices.")
    print("  -> Keep RoadSense actively recording on your phone!")
    start_time = time.time()
    
    trace_res = run_adb(["shell", perfetto_cmd], check=False)
    elapsed = time.time() - start_time
    print(f"  -> Tracing completed in {elapsed:.1f}s.")
    if "Wrote" not in trace_res.stdout and "Wrote" not in trace_res.stderr:
        print(f"Trace output warning: {trace_res.stdout} {trace_res.stderr}")

    # 5. Pull trace to host
    print(f"[4/5] Pulling trace to host: {local_trace_path}...")
    run_adb(["pull", remote_trace_path, local_trace_path])
    run_adb(["shell", "rm", "-f", remote_trace_path], check=False)

    file_size_mb = os.path.getsize(local_trace_path) / (1024 * 1024)
    print(f"  Successfully saved {file_size_mb:.2f} MB trace to: {local_trace_path}")

    print("[5/5] Trace capture complete!")
    print("=" * 75)
    print(f"Next Steps:")
    print(f"  1. Run automated analysis:")
    print(f"     python scripts/analyze_perfetto_trace.py {local_trace_path}")
    print(f"  2. Or open in visual web visualizer:")
    print(f"     https://ui.perfetto.dev (Click 'Open trace file' -> select {local_trace_path})")
    print("=" * 75)

if __name__ == "__main__":
    main()
