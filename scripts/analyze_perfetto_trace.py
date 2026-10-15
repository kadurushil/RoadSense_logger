#!/usr/bin/env python3
"""
RoadSense Perfetto System Trace Analyzer.
Analyzes nanosecond thread states, scheduler wakeups, preemption, and IRQs
during high-rate IMU logging spikes.

Usage:
    python scripts/analyze_perfetto_trace.py [PATH_TO_TRACE.pftrace]
"""

import os
import sys
import argparse

# Windows stdout UTF-8 reconfigure
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

try:
    from perfetto.trace_processor import TraceProcessor
except ImportError:
    sys.exit("Error: 'perfetto' Python package not installed. Run: pip install perfetto")

def resolve_trace_file(target):
    if target and os.path.isfile(target):
        return target
    traces_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "traces")
    if os.path.isdir(traces_dir):
        files = sorted([os.path.join(traces_dir, f) for f in os.listdir(traces_dir) if f.endswith(".pftrace")])
        if files:
            return files[-1]
    sys.exit(f"Error: No trace file found. Specify path or run 'python scripts/record_perfetto_trace.py' first.")

def main():
    parser = argparse.ArgumentParser(description="Analyze Perfetto trace for IMU timing jitter and CPU scheduling")
    parser.add_argument("trace_path", nargs="?", default=None, help="Path to .pftrace file (defaults to latest in traces/)")
    args = parser.parse_args()

    trace_file = resolve_trace_file(args.trace_path)
    print("=" * 80)
    print(f"ROADSENSE PERFETTO TIMING & SCHEDULER ANALYZER")
    print(f"Target Trace: {trace_file}")
    print("=" * 80)

    print("[1/4] Loading trace into Perfetto TraceProcessor engine...")
    tp = TraceProcessor(trace_file)

    # 1. Locate RoadSense-ImuThread
    print("[2/4] Locating 'RoadSense-ImuThread' and sensor callbacks...")
    thread_query = """
        SELECT utid, tid, name
        FROM thread
        WHERE name LIKE '%ImuThread%' OR name LIKE '%RoadSense%'
    """
    threads = list(tp.query(thread_query))
    if not threads:
        print("  Warning: 'RoadSense-ImuThread' not explicitly found by name. Searching all app threads...")
        thread_query = """
            SELECT DISTINCT t.utid, t.tid, t.name
            FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id
            JOIN thread t ON tt.utid = t.utid
            WHERE s.name LIKE '%onSensorChanged%'
        """
        threads = list(tp.query(thread_query))

    if not threads:
        sys.exit("Error: No IMU sensor callback slices found in trace. Was RoadSense actively recording?")

    imu_thread = threads[0]
    imu_utid = imu_thread.utid
    imu_tid = imu_thread.tid
    imu_name = imu_thread.name
    print(f"  Target Thread: '{imu_name}' (tid={imu_tid}, utid={imu_utid})")

    # 2. Extract sensor events: via atrace slices if available, or kernel thread_state transitions
    slice_query = f"""
        SELECT s.ts, s.dur, s.name
        FROM slice s
        JOIN thread_track tt ON s.track_id = tt.id
        WHERE tt.utid = {imu_utid} AND s.name LIKE '%onSensorChanged%'
        ORDER BY s.ts ASC
    """
    slices = list(tp.query(slice_query))
    dts = []
    spike_indices = []

    if len(slices) >= 2:
        print(f"  Found {len(slices)} userspace 'RoadSense:onSensorChanged' atrace events.")
        print("[3/4] Computing inter-arrival intervals from atrace slices...")
        for i in range(1, len(slices)):
            dt_ms = (slices[i].ts - slices[i-1].ts) / 1_000_000.0
            dts.append((dt_ms, slices[i-1].ts, slices[i].ts, slices[i].dur / 1_000_000.0))
            if dt_ms > 15.0:
                spike_indices.append(i - 1)
        t_base = slices[0].ts
    else:
        print("  Notice: Userspace atrace slices not enabled. Reconstructing wakeups directly from kernel scheduler...")
        state_query = f"""
            SELECT ts, dur, state, cpu
            FROM thread_state
            WHERE utid = {imu_utid}
            ORDER BY ts ASC
        """
        rows = list(tp.query(state_query))
        print(f"  Captured {len(rows)} kernel thread_state transitions for '{imu_name}'.")

        # Identify wakeups (transition from Sleeping 'S' to Runnable 'R' or 'Running')
        wakeups = []
        prev_state = None
        for r in rows:
            if prev_state == 'S' and r.state in ['R', 'Running']:
                wakeups.append(r.ts)
            prev_state = r.state

        print(f"  Reconstructed {len(wakeups)} hardware event wakeups from kernel scheduler.")
        if len(wakeups) < 2:
            sys.exit("Error: Not enough thread wakeups detected in trace.")

        print("[3/4] Computing inter-arrival intervals from kernel scheduler wakeups...")
        for i in range(1, len(wakeups)):
            dt_ms = (wakeups[i] - wakeups[i-1]) / 1_000_000.0
            dts.append((dt_ms, wakeups[i-1], wakeups[i], 0.0))
            if dt_ms > 15.0:
                spike_indices.append(i - 1)
        t_base = wakeups[0]

    mean_dt = sum(x[0] for x in dts) / len(dts)
    print(f"  Total Intervals:  {len(dts)}")
    print(f"  Mean Interval:   {mean_dt:.3f} ms (~{1000.0/mean_dt:.1f} Hz)")
    print(f"  Spikes > 15.0ms: {len(spike_indices)} ({len(spike_indices)/len(dts)*100:.2f}%)")

    # 4. Detailed analysis of each spike window
    print("\n[4/4] DETAILED NANOSECOND SCHEDULER BREAKDOWN PER SPIKE:")
    print("-" * 80)

    for count, idx in enumerate(spike_indices[:10]):
        dt_spike, t_start, t_end, exec_dur = dts[idx]
        dt_prev = dts[idx-1][0] if idx > 0 else 0.0
        dt_next = dts[idx+1][0] if idx < len(dts)-1 else 0.0

        print(f"Spike #{count+1} @ t_rel = {(t_start - t_base)/1e9:.3f}s:")
        print(f"  Interval: {dt_spike:.3f} ms (dt_prev={dt_prev:.3f} ms, sum={dt_prev+dt_spike:.3f} ms, dt_next={dt_next:.3f} ms)")
        print(f"  Callback Execution Duration: {exec_dur*1000:.1f} µs ({exec_dur:.4f} ms)")

        # Query thread state breakdown for RoadSense-ImuThread in this window
        state_query = f"""
            SELECT state, SUM(dur) as total_dur_ns, COUNT(*) as count
            FROM thread_state
            WHERE utid = {imu_utid} AND ts >= {t_start} AND ts <= {t_end}
            GROUP BY state
        """
        states = list(tp.query(state_query))
        total_window_dur_ms = (t_end - t_start) / 1_000_000.0
        
        print("  Thread State Breakdown:")
        sleeping_ms = 0.0
        runnable_ms = 0.0
        running_ms = 0.0

        for st in states:
            dur_ms = st.total_dur_ns / 1_000_000.0
            pct = (dur_ms / total_window_dur_ms) * 100.0
            state_label = {
                "Running": "Running on CPU",
                "R": "Runnable (Waiting for CPU core)",
                "R+": "Runnable (Preempted)",
                "S": "Sleeping (Waiting in epoll/socket/lock)",
                "D": "Uninterruptible Sleep (Kernel I/O block)"
            }.get(st.state, st.state)

            if st.state in ["S"]:
                sleeping_ms += dur_ms
            elif st.state in ["R", "R+"]:
                runnable_ms += dur_ms
            elif st.state in ["Running"]:
                running_ms += dur_ms

            print(f"    - {state_label:36}: {dur_ms:6.3f} ms ({pct:5.1f}%) [{st.count} transitions]")

        # Diagnostic Verdict
        if sleeping_ms / total_window_dur_ms > 0.90:
            print(f"  >>> VERDICT: [HARDWARE/HAL OMISSION]")
            print(f"      The thread spent {sleeping_ms:.2f} ms ({sleeping_ms/total_window_dur_ms*100:.1f}%) SLEEPING in epoll_wait.")
            print(f"      The CPU was completely available; Android HAL / kernel driver simply delivered zero sensor events for 18 ms.")
        elif runnable_ms > 1.0:
            print(f"  >>> VERDICT: [CPU SCHEDULER STARVATION / PREEMPTION]")
            print(f"      The thread was RUNNABLE (ready to run) but starved for {runnable_ms:.2f} ms waiting for CPU time.")
            
            # Find which other threads ran on the CPU during this starvation
            preempt_query = f"""
                SELECT t.name as thread_name, p.name as process_name, SUM(s.dur) as cpu_dur_ns
                FROM sched s
                JOIN thread t ON s.utid = t.utid
                LEFT JOIN process p ON t.upid = p.upid
                WHERE s.ts >= {t_start} AND s.ts <= {t_end} AND s.utid != {imu_utid}
                GROUP BY t.utid
                ORDER BY cpu_dur_ns DESC
                LIMIT 3
            """
            preempts = list(tp.query(preempt_query))
            print("      Top CPU consumers during starvation window:")
            for p in preempts:
                print(f"        * {p.process_name or 'kernel'} -> {p.thread_name}: {p.cpu_dur_ns/1e6:.2f} ms")
        else:
            print(f"  >>> VERDICT: [NORMAL SCHEDULING INTERACTION]")
        print("-" * 80)

    print("\nSUMMARY OF ROOT CAUSE FINDINGS:")
    print("=" * 80)
    print("1. If all spikes show >90% SLEEPING state: The app and scheduler are innocent.")
    print("   The ST LSM6DSL hardware FIFO / Samsung kernel SPI driver is omitting 1 tick.")
    print("2. If spikes show RUNNABLE wait: Setting Linux real-time priority (SCHED_FIFO)")
    print("   or CPU affinity will eliminate the preemption.")
    print(f"\nTrace analysis complete. You can also view this trace visually at: https://ui.perfetto.dev")
    print(f"File: {trace_file}")
    print("=" * 80)

if __name__ == "__main__":
    main()
