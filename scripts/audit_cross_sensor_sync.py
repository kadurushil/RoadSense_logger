"""
RoadSense Diagnostic Suite: Cross-Sensor Synchronization Auditor (CLI)
Audits the master session_timeline.csv to verify monotonic alignment across all sensor streams,
investigates high-rate IMU logging vs periodic timeline anchors, pinpoints de-sync events,
correlates timing anomalies with user screen touch / UI interactions from the App Flight Recorder log,
and generates an automated multi-panel visual synchronization diagnostic plot and interactive HTML report.

Usage:
  python scripts/audit_cross_sensor_sync.py [SESSION_PATH_OR_NAME]
  python scripts/audit_cross_sensor_sync.py --latest [--no-plot] [--no-html] [--show]
"""

import sys
import os
import argparse

# Ensure local scripts directory is in sys.path for direct module import
scripts_dir = os.path.dirname(os.path.abspath(__file__))
if scripts_dir not in sys.path:
    sys.path.insert(0, scripts_dir)

from cross_sensor_sync_engine import (
    audit_timeline,
    resolve_session_dir,
    generate_sync_plots,
    generate_sync_html_report,
    extract_ui_events_from_log,
    colorize_status
)


def main():
    parser = argparse.ArgumentParser(description="RoadSense Cross-Sensor Sync Auditor")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("--no-plot", action="store_true", help="Skip generating diagnostic plot PNG")
    parser.add_argument("--no-html", action="store_true", help="Skip generating standalone interactive HTML report")
    parser.add_argument("--show", action="store_true", help="Display interactive plot GUI window")
    parser.add_argument("target", nargs="?", default="latest", help="Session directory or session name")
    args = parser.parse_args()

    target_dir = resolve_session_dir("latest" if args.latest else args.target)
    scorecard, overall, plot_path, html_path = audit_timeline(
        target_dir,
        make_plot=not args.no_plot,
        make_html=not args.no_html,
        show_gui=args.show
    )

    if any("FAIL" in v.upper() for v in scorecard.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
