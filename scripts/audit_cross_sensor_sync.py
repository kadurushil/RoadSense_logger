"""
RoadSense Diagnostic Suite: Cross-Sensor Synchronization Auditor (CLI)
Audits the master session_timeline.csv to verify monotonic alignment across all sensor streams,
investigates high-rate IMU logging vs periodic timeline anchors, pinpoints de-sync events,
correlates timing anomalies with user screen touch / UI interactions from the App Flight Recorder log,
and generates an automated multi-panel visual synchronization diagnostic plot and interactive HTML report.

Usage:
  python scripts/audit_cross_sensor_sync.py [SESSION_PATH_OR_NAME]
  python scripts/audit_cross_sensor_sync.py --latest [--no-plot] [--no-html] [--show]
  python scripts/audit_cross_sensor_sync.py --all [--no-plot] [--no-html] [--quiet]
"""

import sys
import os
import argparse
import io
import contextlib

# Ensure local scripts directory is in sys.path for direct module import
scripts_dir = os.path.dirname(os.path.abspath(__file__))
if scripts_dir not in sys.path:
    sys.path.insert(0, scripts_dir)

from cross_sensor_sync_engine import (
    audit_timeline,
    resolve_session_dir,
    get_all_session_dirs,
    colorize_status,
    ANSI_BOLD,
    ANSI_RESET
)


def run_batch_audit(args):
    session_dirs = get_all_session_dirs()
    if not session_dirs:
        print("Error: No recorded sessions with session_timeline.csv found in logs/")
        sys.exit(1)

    total_sessions = len(session_dirs)
    print("=" * 100)
    print(f"ROADSENSE BATCH CROSS-SENSOR SYNCHRONIZATION AUDIT")
    print(f"Discovered {total_sessions} recorded sessions with master timeline logs.")
    print("=" * 100)

    results = []
    for idx, sess_dir in enumerate(session_dirs, 1):
        sess_name = os.path.basename(sess_dir)
        if not args.quiet:
            print(f"\n{'=' * 80}")
            print(f">>> [{idx}/{total_sessions}] Auditing Session: {sess_name}")
            print(f"{'=' * 80}")

        try:
            if args.quiet:
                # Capture standard output to keep batch output clean
                trap_out = io.StringIO()
                with contextlib.redirect_stdout(trap_out):
                    scorecard, overall, plot_path, html_path = audit_timeline(
                        sess_dir,
                        make_plot=args.make_plot,
                        make_html=not args.no_html,
                        show_gui=args.show
                    )
            else:
                scorecard, overall, plot_path, html_path = audit_timeline(
                    sess_dir,
                    make_plot=args.make_plot,
                    make_html=not args.no_html,
                    show_gui=args.show
                )

            dur_s = scorecard.get("_duration_s", 0.0)
            status = "PASS" if "PASS" in overall.upper() else ("WARN" if "WARN" in overall.upper() else "FAIL")
            results.append({
                "index": idx,
                "name": sess_name,
                "path": sess_dir,
                "duration": dur_s,
                "overall": overall,
                "status": status,
                "scorecard": scorecard,
                "html_path": html_path,
                "plot_path": plot_path
            })
            if args.quiet:
                print(f"  [{idx:2d}/{total_sessions}] {sess_name:<30s} ({dur_s:5.1f}s) -> {colorize_status(status)}")

        except Exception as e:
            if args.quiet:
                print(f"  [{idx:2d}/{total_sessions}] {sess_name:<30s} -> {colorize_status('FAIL')} ({e})")
            else:
                print(f"Error auditing session {sess_name}: {e}")
            results.append({
                "index": idx,
                "name": sess_name,
                "path": sess_dir,
                "duration": 0.0,
                "overall": f"FAIL ({e})",
                "status": "FAIL",
                "scorecard": {},
                "html_path": None,
                "plot_path": None
            })

    # Aggregate Batch Summary Table
    print("\n" + "=" * 120)
    print(f"BATCH CROSS-SENSOR SYNCHRONIZATION AUDIT SUMMARY ({total_sessions} SESSIONS)")
    print("=" * 120)
    print(f"{'#':<4s} | {'Session Name':<30s} | {'Duration':<10s} | {'Verdict':<8s} | {'Interactive HTML Report URL'}")
    print("-" * 120)

    pass_cnt = sum(1 for r in results if r["status"] == "PASS")
    warn_cnt = sum(1 for r in results if r["status"] == "WARN")
    fail_cnt = sum(1 for r in results if r["status"] == "FAIL")

    for r in results:
        dur_str = f"{r['duration']:.2f}s" if r['duration'] > 0 else "--"
        html_url = f"file:///{r['html_path'].replace(os.sep, '/')}" if r['html_path'] else "N/A"
        colored_verdict = colorize_status(f"{r['status']:<8s}")
        print(f"{r['index']:<4d} | {r['name']:<30s} | {dur_str:<10s} | {colored_verdict} | {html_url}")

    print("=" * 120)
    print("AGGREGATE BATCH METRICS:")
    print(f"  Total Audited Sessions: {total_sessions}")
    print(f"  PASS:                   {pass_cnt} ({pass_cnt / total_sessions * 100:.1f}%)")
    print(f"  WARN:                   {warn_cnt} ({warn_cnt / total_sessions * 100:.1f}%)")
    print(f"  FAIL:                   {fail_cnt} ({fail_cnt / total_sessions * 100:.1f}%)")
    print("=" * 120)

    if fail_cnt > 0:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="RoadSense Cross-Sensor Sync Auditor")
    parser.add_argument("--all", action="store_true", help="Audit all recorded sessions in logs/")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    
    plot_group = parser.add_mutually_exclusive_group()
    plot_group.add_argument("--plot", dest="make_plot", action="store_true", default=False, help="Generate legacy Matplotlib PNG diagnostic plot (disabled by default; HTML report is standard)")
    plot_group.add_argument("--no-plot", dest="make_plot", action="store_false", help="Skip generating diagnostic plot PNG (default: False)")

    parser.add_argument("--no-html", action="store_true", help="Skip generating standalone interactive HTML report")
    parser.add_argument("--show", action="store_true", help="Display interactive plot GUI window")
    parser.add_argument("--quiet", "-q", action="store_true", help="Quiet mode for batch audit (suppress detailed session stdout)")
    parser.add_argument("target", nargs="?", default="latest", help="Session directory or session name")
    args = parser.parse_args()

    if args.all:
        run_batch_audit(args)
        return

    try:
        target_dir = resolve_session_dir("latest" if args.latest else args.target)
    except FileNotFoundError as e:
        sys.exit(f"Error: {e}")

    scorecard, overall, plot_path, html_path = audit_timeline(
        target_dir,
        make_plot=args.make_plot,
        make_html=not args.no_html,
        show_gui=args.show
    )

    if any("FAIL" in str(v).upper() for k, v in scorecard.items() if not k.startswith("_")):
        sys.exit(1)


if __name__ == "__main__":
    main()
