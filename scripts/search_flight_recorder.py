"""
RoadSense Diagnostic Suite: Flight Recorder Log Search
Searches and filters continuous app logs (app_logs/) and active session logs (session_debug.log).

Features:
  - Search across all app runs and sessions simultaneously
  - Filter by query keywords (e.g. "Canedge", "prune", "staged", "radar")
  - Filter for errors, exceptions, or warnings only
  - Filter by specific session ID

Usage:
  python scripts/search_flight_recorder.py --query "prune"
  python scripts/search_flight_recorder.py --errors
  python scripts/search_flight_recorder.py --session session_20260911_141256
"""

import os
import sys
import glob
import argparse

def search_logs(logs_dir, query=None, errors_only=False, session_id=None, max_results=100):
    print("=" * 80)
    print(f"ROADSENSE FLIGHT RECORDER LOG SEARCH")
    print(f"Directory: {logs_dir}")
    print(f"Filters:   query='{query}', errors_only={errors_only}, session='{session_id}'")
    print("=" * 80)

    # Collect all log files
    log_files = []
    # app_logs
    app_logs_dir = os.path.join(logs_dir, "app_logs")
    if os.path.exists(app_logs_dir):
        log_files.extend(glob.glob(os.path.join(app_logs_dir, "**", "*.log"), recursive=True))

    # session logs
    session_logs = glob.glob(os.path.join(logs_dir, "session_*", "session_debug.log"))
    if session_id:
        session_logs = [f for f in session_logs if session_id in f]
    log_files.extend(session_logs)

    total_matches = 0
    matched_files = set()

    for lf in sorted(log_files):
        try:
            with open(lf, "r", encoding="utf-8", errors="replace") as f:
                for line_num, line in enumerate(f, 1):
                    # Check errors filter
                    if errors_only and not (" E/" in line or " ERROR " in line or "Exception" in line):
                        continue

                    # Check query filter
                    if query and query.lower() not in line.lower():
                        continue

                    # Check session filter
                    if session_id and session_id not in line and session_id not in lf:
                        continue

                    rel_path = os.path.relpath(lf, logs_dir)
                    matched_files.add(rel_path)
                    total_matches += 1
                    if total_matches <= max_results:
                        print(f"[{rel_path}:{line_num}] {line.strip()}")

                    if total_matches == max_results:
                        print(f"\n... Reached display limit of {max_results} matches ...")
        except Exception:
            continue

    print(f"\nTotal Matches Found: {total_matches} across {len(matched_files)} log files.")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense Flight Recorder Search Tool")
    parser.add_argument("-q", "--query", help="Search keyword (e.g. 'Canedge', 'radar', 'staged')")
    parser.add_argument("-e", "--errors", action="store_true", help="Filter for errors and exceptions only")
    parser.add_argument("-s", "--session", help="Filter for specific session ID")
    parser.add_argument("-m", "--max", type=int, default=80, help="Maximum results to print (default 80)")
    args = parser.parse_args()

    default_logs = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")
    search_logs(default_logs, query=args.query, errors_only=args.errors, session_id=args.session, max_results=args.max)
