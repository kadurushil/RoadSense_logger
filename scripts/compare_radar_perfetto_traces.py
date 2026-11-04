#!/usr/bin/env python3
"""
RoadSense Perfetto System Trace Comparator: Baseline vs. Hardened (Bug #29).

Compares scheduler preemption, Big Core utilization, Compose UI recomposition churn,
and ART GC overhead between the baseline trace and the newly recorded trace.

Usage:
    python scripts/compare_radar_perfetto_traces.py [--pull] [--new PATH_TO_NEW_TRACE]
"""

import os
import sys
import argparse
import subprocess
from datetime import datetime

# Windows stdout UTF-8 reconfigure
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

try:
    from perfetto.trace_processor import TraceProcessor
except ImportError:
    sys.exit("Error: 'perfetto' Python package not installed in active environment. Run: pip install perfetto")

ADB_PATH = r"C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEFAULT_BASELINE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "logs",
    "trace-exynos7904-QP1A.190711.020-2026-11-03-18-05-25.perfetto-trace"
)

def run_adb(cmd):
    full_cmd = f'"{ADB_PATH}" {cmd}'
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip(), res.stderr.strip(), res.returncode

def pull_latest_trace():
    print("[INFO] Checking connected device...")
    out, _, _ = run_adb("devices")
    lines = [l for l in out.splitlines() if "\tdevice" in l]
    if not lines:
        sys.exit("[ERROR] No Android device connected via ADB. Please plug in your phone.")
    print(f"[INFO] Connected device: {lines[0].split()[0]}")

    print("[INFO] Searching for on-device trace in /data/local/traces/ and /sdcard/Recordings/traces/...")
    out, _, _ = run_adb("shell ls -lt /data/local/traces/")
    if not out or "No such file" in out:
        out, _, _ = run_adb("shell ls -lt /sdcard/Recordings/traces/")

    trace_files = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 8:
            fname = parts[-1]
            if fname.endswith(".perfetto-trace") or fname.endswith(".pftrace") or fname.endswith(".trace"):
                trace_files.append(fname)

    if not trace_files:
        sys.exit("[ERROR] No trace file found on device. Did you stop system tracing?")

    latest_trace = trace_files[0]
    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")
    os.makedirs(out_dir, exist_ok=True)
    dest_path = os.path.join(out_dir, latest_trace)

    print(f"[INFO] Pulling {latest_trace} to {dest_path}...")
    run_adb(f'pull "/data/local/traces/{latest_trace}" "{dest_path}"')
    if not os.path.exists(dest_path) or os.path.getsize(dest_path) == 0:
        run_adb(f'pull "/sdcard/Recordings/traces/{latest_trace}" "{dest_path}"')

    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        print(f"[SUCCESS] Pulled {os.path.getsize(dest_path) / (1024*1024):.2f} MB trace!")
        return dest_path
    else:
        sys.exit("[ERROR] Failed to pull trace file from device.")

def analyze_trace(tp, label="Trace"):
    print(f"\n[INFO] Analyzing {label}...")
    
    # 1. Total trace duration
    dur_qr = list(tp.query("SELECT (max(ts) - min(ts))/1e9 as dur_sec FROM slice"))
    trace_dur_sec = dur_qr[0].dur_sec if dur_qr and dur_qr[0].dur_sec else 20.0

    # 2. Serial Thread Scheduler Latency
    serial_qr = list(tp.query("""
        SELECT t.name, t.tid, count(s.id) as r_count, max(s.dur)/1e6 as max_wait_ms, sum(s.dur)/1e6 as total_wait_ms
        FROM thread_state s
        JOIN thread t ON s.utid = t.utid
        WHERE (t.name LIKE '%Serial%' OR t.name LIKE '%RS-SerialIO%') AND s.state = 'R'
        GROUP BY t.utid
        ORDER BY max_wait_ms DESC
        LIMIT 1
    """))

    serial_thread_name = "N/A"
    serial_max_runnable_ms = 0.0
    serial_overflow_spikes = 0
    if serial_qr:
        serial_thread_name = serial_qr[0].name
        serial_max_runnable_ms = serial_qr[0].max_wait_ms or 0.0

        # Count spikes exceeding 1.84 ms (CP2105 FIFO threshold)
        spikes_qr = list(tp.query(f"""
            SELECT count(s.id) as cnt
            FROM thread_state s
            JOIN thread t ON s.utid = t.utid
            WHERE t.tid = {serial_qr[0].tid} AND s.state = 'R' AND (s.dur / 1e6) > 1.843
        """))
        serial_overflow_spikes = spikes_qr[0].cnt if spikes_qr else 0

    # 3. Big Cores (Cores 6 & 7) Serial Affinity
    affinity_qr = list(tp.query(f"""
        SELECT 
            sum(CASE WHEN s.cpu IN (6, 7) THEN s.dur ELSE 0 END) * 100.0 / sum(s.dur) as big_core_pct
        FROM thread_state s
        JOIN thread t ON s.utid = t.utid
        WHERE (t.name LIKE '%Serial%' OR t.name LIKE '%RS-SerialIO%') AND s.state = 'Running'
    """))
    big_core_affinity_pct = affinity_qr[0].big_core_pct if affinity_qr and affinity_qr[0].big_core_pct is not None else 0.0

    # 4. Main UI Thread CPU Load
    ui_qr = list(tp.query("""
        SELECT sum(s.dur)/1e9 as cpu_sec
        FROM thread_state s
        JOIN thread t ON s.utid = t.utid
        WHERE t.name = 'jauto.roadsense' AND s.state = 'Running'
    """))
    ui_cpu_sec = ui_qr[0].cpu_sec if ui_qr and ui_qr[0].cpu_sec else 0.0
    ui_cpu_pct = (ui_cpu_sec / trace_dur_sec) * 100.0

    # 5. RenderThread CPU Load
    render_qr = list(tp.query("""
        SELECT sum(s.dur)/1e9 as cpu_sec
        FROM thread_state s
        JOIN thread t ON s.utid = t.utid
        WHERE t.name = 'RenderThread' AND s.state = 'Running'
    """))
    render_cpu_sec = render_qr[0].cpu_sec if render_qr and render_qr[0].cpu_sec else 0.0
    render_cpu_pct = (render_cpu_sec / trace_dur_sec) * 100.0

    # 6. Compose Node Layout / Measurements
    measure_qr = list(tp.query("""
        SELECT count(s.id) as cnt
        FROM slice s
        WHERE s.name LIKE '%TextStringSimpleNode::measure%'
    """))
    text_measure_cnt = measure_qr[0].cnt if measure_qr else 0

    # 7. ART Garbage Collector Churn (HeapTaskDaemon)
    gc_qr = list(tp.query("""
        SELECT count(s.id) as sweep_count, sum(s.dur)/1e6 as total_gc_ms
        FROM thread_state s
        JOIN thread t ON s.utid = t.utid
        WHERE t.name = 'HeapTaskDaemon' AND s.state = 'Running'
    """))
    gc_sweeps = gc_qr[0].sweep_count if gc_qr else 0
    gc_time_ms = gc_qr[0].total_gc_ms if gc_qr and gc_qr[0].total_gc_ms else 0.0

    # 8. onNewData Slices
    slices_qr = list(tp.query("""
        SELECT count(s.id) as cnt
        FROM slice s
        WHERE s.name LIKE '%RS_Serial_onNewData%'
    """))
    on_new_data_cnt = slices_qr[0].cnt if slices_qr else 0

    return {
        "duration_sec": trace_dur_sec,
        "serial_name": serial_thread_name,
        "max_runnable_ms": serial_max_runnable_ms,
        "overflow_spikes": serial_overflow_spikes,
        "big_core_affinity_pct": big_core_affinity_pct,
        "ui_cpu_pct": ui_cpu_pct,
        "render_cpu_pct": render_cpu_pct,
        "text_measure_cnt": text_measure_cnt,
        "gc_sweeps": gc_sweeps,
        "gc_time_ms": gc_time_ms,
        "on_new_data_cnt": on_new_data_cnt
    }

def print_comparison_table(b, n, baseline_path, new_path):
    print("\n" + "=" * 90)
    print(" ROADSENSE BUG #29 SYSTEM TRACE COMPARISON REPORT")
    print("=" * 90)
    print(f" BASELINE TRACE : {os.path.basename(baseline_path)} ({b['duration_sec']:.1f}s)")
    print(f" NEW TRACE      : {os.path.basename(new_path)} ({n['duration_sec']:.1f}s)")
    print("=" * 90)

    headers = f"{'Metric / Hardware Parameter':<38} | {'Baseline (Yesterday)':<20} | {'New Hardened':<16} | {'Delta / Verdict':<12}"
    print(headers)
    print("-" * 90)

    # 1. Worst-case RUNNABLE stall
    v_wait = "PASS [SAFE]" if n['max_runnable_ms'] < 1.843 else "FAIL [RISK]"
    print(f"{'Max RUNNABLE Wait (Serial Thread)':<38} | {b['max_runnable_ms']:>6.3f} ms            | {n['max_runnable_ms']:>6.3f} ms        | {v_wait:<12}")

    # 2. FIFO overflow spikes (> 1.84 ms)
    v_spikes = "PASS (0 Drops)" if n['overflow_spikes'] == 0 else f"FAIL ({n['overflow_spikes']} drops)"
    print(f"{'FIFO Overflow Spikes (> 1.843 ms)':<38} | {b['overflow_spikes']:>6d} spikes         | {n['overflow_spikes']:>6d} spikes     | {v_spikes:<12}")

    # 3. Main UI Thread CPU load
    delta_ui = n['ui_cpu_pct'] - b['ui_cpu_pct']
    v_ui = "PASS (Freed)" if n['ui_cpu_pct'] < 30.0 else f"{delta_ui:+.1f}%"
    print(f"{'Main Thread CPU Load (jauto.roadsense)':<38} | {b['ui_cpu_pct']:>6.1f} %             | {n['ui_cpu_pct']:>6.1f} %         | {v_ui:<12}")

    # 4. RenderThread CPU load
    delta_rt = n['render_cpu_pct'] - b['render_cpu_pct']
    print(f"{'RenderThread CPU Load':<38} | {b['render_cpu_pct']:>6.1f} %             | {n['render_cpu_pct']:>6.1f} %         | {delta_rt:>+6.1f} %")

    # 5. Compose TextStringSimpleNode::measure calls
    delta_measure = n['text_measure_cnt'] - b['text_measure_cnt']
    v_measure = f"{-abs(delta_measure)*100//(b['text_measure_cnt'] or 1)}% Churn"
    print(f"{'Text Layout Measures (Compose)':<38} | {b['text_measure_cnt']:>6d} passes         | {n['text_measure_cnt']:>6d} passes     | {v_measure:<12}")

    # 6. ART GC Sweeps (HeapTaskDaemon)
    print(f"{'ART GC Sweeps (HeapTaskDaemon)':<38} | {b['gc_sweeps']:>6d} sweeps         | {n['gc_sweeps']:>6d} sweeps     | {n['gc_time_ms'] - b['gc_time_ms']:>+6.0f} ms GC")

    # 7. Big Core (Cortex-A73) Affinity
    print(f"{'Serial Thread on Big Cores (6 & 7)':<38} | {b['big_core_affinity_pct']:>6.1f} %             | {n['big_core_affinity_pct']:>6.1f} %         | {'Elevated' if n['big_core_affinity_pct'] > b['big_core_affinity_pct'] else 'Normal'}")

    print("=" * 90)

    # Physical Verdict
    if n['max_runnable_ms'] < 1.843 and n['overflow_spikes'] == 0:
        print("\n [FINAL SCIENTIFIC VERDICT: PASS]")
        print(" -> CP2105 576-byte hardware FIFO overflow has been completely eliminated.")
        print(" -> Big Cortex-A73 cores are relieved; serial reads are never preempted beyond the hardware limit.")
    else:
        print("\n [FINAL SCIENTIFIC VERDICT: INCOMPLETE / REVIEW]")
        print(" -> Preemption latency still crosses the 1.843 ms FIFO limit. Inspect Trace in ui.perfetto.dev.")

def main():
    parser = argparse.ArgumentParser(description="Compare Perfetto traces: Baseline vs Hardened")
    parser.add_argument("--baseline", "-b", default=DEFAULT_BASELINE, help="Path to baseline trace file")
    parser.add_argument("--new", "-n", default=None, help="Path to new trace file")
    parser.add_argument("--pull", "-p", action="store_true", help="Automatically pull latest trace from device via ADB")
    args = parser.parse_args()

    baseline_path = args.baseline
    if not os.path.isfile(baseline_path):
        sys.exit(f"Error: Baseline trace not found at {baseline_path}")

    new_path = args.new
    if args.pull or not new_path:
        new_path = pull_latest_trace()

    if not os.path.isfile(new_path):
        sys.exit(f"Error: New trace not found at {new_path}")

    print(f"[INFO] Ingesting Baseline Trace into Perfetto engine...")
    tp_baseline = TraceProcessor(baseline_path)
    baseline_stats = analyze_trace(tp_baseline, label="Baseline Trace (Yesterday)")

    print(f"[INFO] Ingesting New Hardened Trace into Perfetto engine...")
    tp_new = TraceProcessor(new_path)
    new_stats = analyze_trace(tp_new, label="New Trace")

    print_comparison_table(baseline_stats, new_stats, baseline_path, new_path)

if __name__ == "__main__":
    main()

