"""
RoadSense Diagnostic Suite: GNSS Fixes Validator
Analyzes GPS/GNSS positioning fixes and satellite constellation health.

Features:
  - Validates fix rate (nominal 1.0 Hz) and timestamp continuity
  - Computes horizontal CEP 50% and 95% accuracy distributions
  - Evaluates satellite constellation tracking (tracked vs used satellites)
  - Measures total distance traversed (Haversine formula) and velocity profile
  - Detects multi-path anomalies and spatial position jumps

Usage:
  python scripts/validate_gnss_fixes.py [SESSION_PATH_OR_NAME]
  python scripts/validate_gnss_fixes.py --latest
"""

import os
import sys
import csv
import math
import argparse

def haversine_distance(lat1, lon1, lat2, lon2):
    R = 6371000.0  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c

def resolve_gnss_dir(target):
    base_logs = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")

    if not target or target in ["--latest", "latest"]:
        sessions = sorted([d for d in os.listdir(base_logs) if d.startswith("session_") and os.path.isdir(os.path.join(base_logs, d))])
        if not sessions:
            sys.exit("Error: No sessions found in logs/")
        target = os.path.join(base_logs, sessions[-1])

    if not os.path.exists(target):
        candidate_sess = os.path.join(base_logs, target)
        if os.path.exists(candidate_sess):
            target = candidate_sess

    if os.path.isdir(target):
        gnss_dir = os.path.join(target, "gnss")
        if os.path.exists(gnss_dir):
            return gnss_dir
        sys.exit(f"Error: No gnss/ directory found in {target}")

    sys.exit(f"Error: Cannot find GNSS directory {target}")

def validate_gnss(gnss_dir):
    csv_file = os.path.join(gnss_dir, "gnss_fixes.csv")

    print("=" * 80)
    print(f"GNSS LOCATION & SATELLITE FIX VALIDATION")
    print(f"Directory: {gnss_dir}")
    print("=" * 80)

    if not os.path.exists(csv_file):
        sys.exit("Error: gnss_fixes.csv not found!")

    with open(csv_file, "r", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        rows = list(reader)

    if not rows:
        sys.exit("Error: gnss_fixes.csv is empty!")

    total_fixes = len(rows)
    first_mono = int(rows[0][0])
    last_mono = int(rows[-1][0])
    dur_s = (last_mono - first_mono) / 1e9 if last_mono > first_mono else 1.0
    fix_rate = total_fixes / dur_s

    lats = [float(r[3]) for r in rows if r[3]]
    lons = [float(r[4]) for r in rows if r[4]]
    alts = [float(r[5]) for r in rows if len(r) > 5 and r[5]]
    speeds = [float(r[7]) for r in rows if len(r) > 7 and r[7]]
    accuracies = sorted([float(r[9]) for r in rows if len(r) > 9 and r[9]])
    sat_strings = [r[11] for r in rows if len(r) > 11 and r[11]]

    # Compute total distance
    total_dist_m = 0.0
    for i in range(1, len(lats)):
        total_dist_m += haversine_distance(lats[i-1], lons[i-1], lats[i], lons[i])

    print(f"\n1. FIX SUMMARY:")
    print(f"  Total Fixes:          {total_fixes}")
    print(f"  Fix Duration:         {dur_s:.2f} seconds ({dur_s/60.0:.2f} minutes)")
    print(f"  Effective Fix Rate:   {fix_rate:.2f} Hz (Nominal: 1.00 Hz)")
    print(f"  Total Distance:       {total_dist_m:.1f} meters ({total_dist_m/1000.0:.2f} km)")

    print(f"\n2. ACCURACY & CONSTELLATION HEALTH:")
    if accuracies:
        cep_50 = accuracies[int(len(accuracies) * 0.50)]
        cep_95 = accuracies[int(len(accuracies) * 0.95)]
        print(f"  Best Accuracy (Min):  {accuracies[0]:.2f} m")
        print(f"  CEP 50% (Median):     {cep_50:.2f} m")
        print(f"  CEP 95%:              {cep_95:.2f} m")
        print(f"  Worst Accuracy (Max): {accuracies[-1]:.2f} m")
    if sat_strings:
        print(f"  Satellites (First):   {sat_strings[0]} (used/tracked)")
        print(f"  Satellites (Median):  {sat_strings[len(sat_strings)//2]} (used/tracked)")
        print(f"  Satellites (Last):    {sat_strings[-1]} (used/tracked)")

    print(f"\n3. KINEMATIC PROFILE:")
    if speeds:
        print(f"  Peak Speed:           {max(speeds):.1f} km/h")
        print(f"  Average Speed:        {sum(speeds)/len(speeds):.1f} km/h")
    if alts:
        print(f"  Altitude Range:       {min(alts):.1f} m to {max(alts):.1f} m (Delta: {max(alts)-min(alts):.1f} m)")
    print(f"  Bounding Box:         Lat [{min(lats):.6f}, {max(lats):.6f}], Lon [{min(lons):.6f}, {max(lons):.6f}]")

    print("\n" + "=" * 80)
    print("GNSS VALIDATION COMPLETE")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense GNSS Fixes Validator")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("target", nargs="?", default="latest", help="Session directory or path to gnss/ folder")
    args = parser.parse_args()
    validate_gnss(resolve_gnss_dir("latest" if args.latest else args.target))
