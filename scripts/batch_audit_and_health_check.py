"""
RoadSense Diagnostic Suite: Master Batch Audit & Health Check Suite
Performs a unified, single-command comprehensive verification across RoadSense sessions:
  1. Subsystem Health Audit (session_health_check.py):
     Validates all 8 subsystems (Metadata, Camera, Radar, GNSS, IMU, CANedge, Timeline, Flight Recorder)
     and persists health_scorecard.json.
  2. Cross-Sensor Synchronization Audit (cross_sensor_sync_engine.py):
     Validates microsecond timeline alignment, detects drops, correlates UI gestures,
     and generates standalone interactive HTML reports (sync_audit_report.html).

By default, processes all sessions present in logs/ (or latest/specific session).
Optionally skip already processed sessions via --skip-processed.

Usage:
  python scripts/batch_audit_and_health_check.py --all
  python scripts/batch_audit_and_health_check.py --all --skip-processed
  python scripts/batch_audit_and_health_check.py --latest
  python scripts/batch_audit_and_health_check.py [SESSION_PATH_OR_NAME]
"""

import os
import sys
import argparse
import io
import contextlib
from datetime import datetime

# Ensure local scripts directory is in sys.path
scripts_dir = os.path.dirname(os.path.abspath(__file__))
if scripts_dir not in sys.path:
    sys.path.insert(0, scripts_dir)

from session_health_check import (
    audit_session,
    get_all_session_dirs as get_all_health_session_dirs,
    resolve_session_dir as resolve_health_session_dir,
    DEFAULT_LOGS_DIR
)
from cross_sensor_sync_engine import (
    audit_timeline,
    get_all_session_dirs as get_all_sync_session_dirs,
    resolve_session_dir as resolve_sync_session_dir,
    colorize_status,
    ANSI_BOLD,
    ANSI_RESET
)

def is_session_processed(sess_dir):
    """
    A session is considered already processed if BOTH:
      1. health_scorecard.json exists (from session_health_check)
      2. sync_audit_report.html exists (from cross_sensor_sync_engine)
    """
    health_json = os.path.join(sess_dir, "health_scorecard.json")
    sync_html = os.path.join(sess_dir, "sync_audit_report.html")
    return os.path.isfile(health_json) and os.path.isfile(sync_html)

def run_session_suite(sess_dir, quiet=False, make_plot=False, make_html=True, show_gui=False):
    """
    Executes both the Subsystem Health Audit and Cross-Sensor Sync Audit for a single session.
    Returns:
      (health_scorecard, health_overall, sync_scorecard, sync_overall, plot_path, html_path)
    """
    sess_name = os.path.basename(sess_dir)
    
    # 1. Subsystem Health Audit
    if quiet:
        trap_health = io.StringIO()
        with contextlib.redirect_stdout(trap_health):
            health_scorecard, health_overall = audit_session(sess_dir, quiet=True, save_json=True)
    else:
        health_scorecard, health_overall = audit_session(sess_dir, quiet=False, save_json=True)

    # 2. Cross-Sensor Sync Audit
    tl_file = os.path.join(sess_dir, "session_timeline.csv")
    if not os.path.isfile(tl_file):
        sync_scorecard = {"Timeline": "FAIL (session_timeline.csv absent)"}
        sync_overall = "FAIL (Missing timeline index)"
        plot_path = None
        html_path = None
    else:
        if quiet:
            trap_sync = io.StringIO()
            with contextlib.redirect_stdout(trap_sync):
                sync_scorecard, sync_overall, plot_path, html_path = audit_timeline(
                    sess_dir,
                    make_plot=make_plot,
                    make_html=make_html,
                    show_gui=show_gui
                )
        else:
            sync_scorecard, sync_overall, plot_path, html_path = audit_timeline(
                sess_dir,
                make_plot=make_plot,
                make_html=make_html,
                show_gui=show_gui
            )

    return health_scorecard, health_overall, sync_scorecard, sync_overall, plot_path, html_path

def run_batch_suite(session_dirs, skip_processed=False, quiet=False, make_plot=False, make_html=True):
    total = len(session_dirs)
    print("=" * 110)
    print("ROADSENSE MASTER BATCH AUDIT & HEALTH SUITE")
    print(f"Target: {total} recorded sessions in logs/")
    print(f"Policy: {'Skip already processed' if skip_processed else 'Process and recalculate all sessions (Default)'}")
    print(f"Reports: Standalone Interactive HTML {'Enabled' if make_html else 'Disabled'} | PNG Plot {'Enabled' if make_plot else 'Fast Mode (No Plot)'}")
    print("=" * 110)

    results = []
    skipped = 0

    for idx, sess_dir in enumerate(session_dirs, 1):
        sess_name = os.path.basename(sess_dir)

        if skip_processed and is_session_processed(sess_dir):
            skipped += 1
            if quiet:
                print(f"  [{idx:2d}/{total}] {sess_name:<32s} -> {colorize_status('SKIP (Already processed)')}")
            else:
                print(f"\n>>> [{idx}/{total}] Skipping already processed: {sess_name}")
            continue

        if not quiet:
            print(f"\n{'=' * 90}")
            print(f">>> [{idx}/{total}] Processing Session Suite: {sess_name}")
            print(f"{'=' * 90}")

        try:
            h_sc, h_ov, s_sc, s_ov, plot_path, html_path = run_session_suite(
                sess_dir,
                quiet=quiet,
                make_plot=make_plot,
                make_html=make_html
            )

            h_status = "PASS" if "PASS" in h_ov.upper() else ("WARN" if "WARN" in h_ov.upper() else "FAIL")
            s_status = "PASS" if "PASS" in s_ov.upper() else ("WARN" if "WARN" in s_ov.upper() else "FAIL")
            
            # Combined status
            if h_status == "FAIL" or s_status == "FAIL":
                comb_status = "FAIL"
            elif h_status == "WARN" or s_status == "WARN":
                comb_status = "WARN"
            else:
                comb_status = "PASS"

            dur_s = s_sc.get("_duration_s", 0.0) if isinstance(s_sc, dict) else 0.0

            results.append({
                "index": idx,
                "name": sess_name,
                "path": sess_dir,
                "duration": dur_s,
                "health_status": h_status,
                "health_overall": h_ov,
                "sync_status": s_status,
                "sync_overall": s_ov,
                "comb_status": comb_status,
                "html_path": html_path,
                "plot_path": plot_path
            })

            if quiet:
                msg = f"Health: {colorize_status(h_status)} | Sync: {colorize_status(s_status)} -> {colorize_status(comb_status)}"
                print(f"  [{idx:2d}/{total}] {sess_name:<32s} ({dur_s:5.1f}s) -> {msg}")

        except Exception as e:
            if quiet:
                print(f"  [{idx:2d}/{total}] {sess_name:<32s} -> {colorize_status('FAIL')} ({e})")
            else:
                print(f"Error processing session {sess_name}: {e}")
            results.append({
                "index": idx,
                "name": sess_name,
                "path": sess_dir,
                "duration": 0.0,
                "health_status": "FAIL",
                "health_overall": f"FAIL ({e})",
                "sync_status": "FAIL",
                "sync_overall": f"FAIL ({e})",
                "comb_status": "FAIL",
                "html_path": None,
                "plot_path": None
            })

    # Master Batch Table
    print("\n" + "=" * 110)
    print("MASTER BATCH AUDIT & HEALTH CHECK SUMMARY")
    print("=" * 110)
    print(f"{'#':<4s} {'Session Name':<32s} {'Dur (s)':<9s} {'Health':<10s} {'Sync':<10s} {'Combined':<12s} {'Interactive HTML Report'}")
    print("-" * 110)

    for r in results:
        h_col = colorize_status(r["health_status"])
        s_col = colorize_status(r["sync_status"])
        c_col = colorize_status(r["comb_status"])
        html_disp = f"file:///{r['html_path'].replace(os.sep, '/')}" if r["html_path"] else "--"
        print(f"{r['index']:<4d} {r['name']:<32s} {r['duration']:<9.1f} {h_col:<19s} {s_col:<19s} {c_col:<21s} {html_disp}")

    print("-" * 110)
    eval_cnt = len(results)
    pass_cnt = sum(1 for r in results if r["comb_status"] == "PASS")
    warn_cnt = sum(1 for r in results if r["comb_status"] == "WARN")
    fail_cnt = sum(1 for r in results if r["comb_status"] == "FAIL")

    print(f"Total Sessions: {total} | Evaluated: {eval_cnt} | Skipped: {skipped}")
    print(f"Combined Verdicts: {ANSI_BOLD}PASS: {pass_cnt}{ANSI_RESET} | {ANSI_BOLD}WARN: {warn_cnt}{ANSI_RESET} | {ANSI_BOLD}FAIL: {fail_cnt}{ANSI_RESET}")
    print("=" * 110)

def main():
    parser = argparse.ArgumentParser(description="RoadSense Master Batch Audit & Health Check Suite")
    parser.add_argument("--latest", action="store_true", help="Audit latest session only")
    parser.add_argument("--all", action="store_true", help="Audit all sessions in logs/ (Default if no session specified)")
    parser.add_argument("--skip-processed", action="store_true", help="Skip sessions that already have sync_audit_report.html and health_scorecard.json")
    parser.add_argument("-q", "--quiet", action="store_true", help="Quiet mode: show progress and summary table only")
    parser.add_argument("--no-html", action="store_true", help="Skip interactive HTML report generation")
    parser.add_argument("--plot", action="store_true", help="Generate legacy static PNG diagnostic plot (Slow mode)")
    parser.add_argument("--show", action="store_true", help="Display matplotlib GUI window (interactive plot)")
    parser.add_argument("session", nargs="?", default=None, help="Session folder name, full path, 'latest', or 'all'")

    args = parser.parse_args()

    # Determine execution scope
    is_all = args.all or (args.session in ["all", "--all"]) or (args.session is None and not args.latest)

    if is_all:
        session_dirs = get_all_health_session_dirs()
        if not session_dirs:
            print("Error: No recorded session folders found in logs/")
            sys.exit(1)
        run_batch_suite(
            session_dirs,
            skip_processed=args.skip_processed,
            quiet=args.quiet,
            make_plot=args.plot,
            make_html=not args.no_html
        )
    else:
        target = "latest" if args.latest or args.session == "latest" else args.session
        target_dir = resolve_health_session_dir(target)
        h_sc, h_ov, s_sc, s_ov, plot_path, html_path = run_session_suite(
            target_dir,
            quiet=args.quiet,
            make_plot=args.plot,
            make_html=not args.no_html,
            show_gui=args.show
        )
        print("\n" + "=" * 80)
        print("MASTER SUITE SUMMARY VERDICT:")
        print(f"  Subsystem Health: {colorize_status(h_ov)}")
        print(f"  Cross-Sensor Sync: {colorize_status(s_ov)}")
        if html_path:
            print(f"  Interactive HTML: file:///{html_path.replace(os.sep, '/')}")
        if plot_path:
            print(f"  Diagnostic Plot:  {plot_path}")
        print("=" * 80)

if __name__ == "__main__":
    main()
