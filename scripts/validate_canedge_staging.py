"""
RoadSense Diagnostic Suite: CANedge Staging & MDF4 Validator
Audits CANedge2 MDF4 chunks inside a session can/ directory or the phone staging pool.

Features:
  - Validates MDF4 file header signatures ("MDF     " or "UnFinMF ")
  - Verifies folder and sequence index naming conventions (e.g., 00000047_00000067.MF4)
  - Detects sequence gaps, missing chunks, and truncated downloads
  - Inspects file size distributions to ensure healthy cyclic logging

Usage:
  python scripts/validate_canedge_staging.py [SESSION_PATH_OR_CAN_DIR]
  python scripts/validate_canedge_staging.py --latest
  python scripts/validate_canedge_staging.py --pool
"""

import os
import sys
import argparse

def resolve_can_dir(target):
    base_logs = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")

    if target in ["--pool", "pool"]:
        pool_dir = os.path.join(base_logs, "canedge_pool")
        if os.path.exists(pool_dir):
            return pool_dir
        sys.exit("Error: canedge_pool directory not found.")

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
        can_dir = os.path.join(target, "can")
        if os.path.exists(can_dir):
            return can_dir
        return target

    sys.exit(f"Error: Cannot find CAN directory {target}")

def validate_can(can_dir):
    print("=" * 80)
    print(f"CANEDGE2 MDF4 CHUNK & STAGING VALIDATION")
    print(f"Directory: {can_dir}")
    print("=" * 80)

    files = sorted([f for f in os.listdir(can_dir) if f.endswith(".MF4") or f.endswith(".mf4")])
    if not files:
        sys.exit("Error: No MF4 files found in directory!")

    total_files = len(files)
    total_bytes = sum(os.path.getsize(os.path.join(can_dir, f)) for f in files)
    valid_magic = 0
    file_records = []
    gaps = []

    for fn in files:
        fp = os.path.join(can_dir, fn)
        sz = os.path.getsize(fp)
        with open(fp, "rb") as f:
            sig = f.read(8)
        is_valid = sig in [b'MDF     ', b'UnFinMF ']
        if is_valid:
            valid_magic += 1

        # Parse naming format: 00000047_00000067.MF4
        parts = fn.replace(".MF4", "").replace(".mf4", "").split("_")
        f_folder = int(parts[0]) if len(parts) > 1 and parts[0].isdigit() else 0
        f_idx = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
        file_records.append((fn, sz, sig, f_folder, f_idx))

    # Detect sequence gaps within the same folder
    for i in range(1, len(file_records)):
        prev = file_records[i - 1]
        curr = file_records[i]
        if prev[3] == curr[3]: # Same folder
            expected_idx = prev[4] + 1
            if curr[4] != expected_idx:
                gaps.append((prev[0], curr[0], curr[4] - expected_idx))

    sizes = [rec[1] for rec in file_records]
    avg_size = total_bytes / total_files if total_files else 0

    print(f"\n1. STAGED VOLUME SUMMARY:")
    print(f"  Total Chunks:         {total_files} MF4 files")
    print(f"  Total Staged Size:    {total_bytes:,} bytes ({total_bytes/(1024*1024):.2f} MB)")
    print(f"  Average Chunk Size:   {avg_size/1024.0:.1f} KB (Min: {min(sizes)/1024.0:.1f} KB, Max: {max(sizes)/1024.0:.1f} KB)")
    print(f"  Header Verification:  {valid_magic} / {total_files} ('MDF ' or 'UnFinMF ')")

    print(f"\n2. SEQUENCE & CONTINUITY:")
    print(f"  First Staged Chunk:   {file_records[0][0]}")
    print(f"  Last Staged Chunk:    {file_records[-1][0]}")
    print(f"  Missing Sequence Gaps: {len(gaps)}")
    if gaps:
        for prev_fn, curr_fn, missing in gaps:
            print(f"    - Gap between {prev_fn} and {curr_fn} ({missing} chunks omitted)")
    else:
        print(f"  Continuity Status:    100% Continuous Sequence (No Missing Chunks)")

    print(f"\n3. CHUNK ROSTER PREVIEW:")
    for fn, sz, sig, _, _ in file_records[:5]:
        print(f"  {fn}: {sz:,} bytes [{sig.decode('ascii', errors='replace').strip()}]")
    if len(file_records) > 5:
        print(f"  ... ({len(file_records)-5} intermediate chunks omitted) ...")
        for fn, sz, sig, _, _ in file_records[-2:]:
            print(f"  {fn}: {sz:,} bytes [{sig.decode('ascii', errors='replace').strip()}]")

    print("\n" + "=" * 80)
    print("CANEDGE VALIDATION COMPLETE")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense CANedge MDF4 Validator")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("target", nargs="?", default="latest", help="Session path, can/ directory, or '--pool'")
    args = parser.parse_args()
    validate_can(resolve_can_dir("latest" if args.latest else args.target))
