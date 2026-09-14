"""
RoadSense Diagnostic Suite: Radar Stream Validator
Performs low-level validation on TI AWR1843 mmWave radar binary streams.
"""

import os
import sys
import struct
import argparse

TI_MAGIC = b'\x02\x01\x04\x03\x06\x05\x08\x07'

def resolve_radar_file(target):
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
        candidate = os.path.join(target, "radar", "radar_raw_stream.bin")
        if os.path.exists(candidate):
            return candidate
        sys.exit(f"Error: No radar_raw_stream.bin found in {target}")

    if os.path.isfile(target):
        return os.path.abspath(target)

    sys.exit(f"Error: Cannot find radar file {target}")

def validate_radar(bin_path):
    print("=" * 80)
    print(f"TI AWR1843 RADAR BINARY STREAM VALIDATION")
    print(f"File: {bin_path} ({os.path.getsize(bin_path):,} bytes)")
    print("=" * 80)

    with open(bin_path, "rb") as f:
        data = f.read()

    total_len = len(data)
    idx = 0
    frame_count = 0
    frame_nums = []
    points_per_frame = []
    tlv_counts = {}
    dropped_frames = []
    resets = []
    first_magic_offset = None
    last_magic_offset = None

    while idx + 40 <= total_len:
        pos = data.find(TI_MAGIC, idx)
        if pos == -1 or pos + 40 > total_len:
            break

        if first_magic_offset is None:
            first_magic_offset = pos
        last_magic_offset = pos

        try:
            ver, total_len_pkt, plat, fnum, cpu_cycles, num_obj, num_tlvs, subframe = struct.unpack_from('<IIIIIIII', data, pos + 8)
            frame_count += 1
            frame_nums.append(fnum)
            points_per_frame.append(num_obj)

            # Check sequence drops vs sensor reset/wrap
            if len(frame_nums) > 1:
                expected = frame_nums[-2] + 1
                if fnum > expected:
                    dropped_frames.append((frame_nums[-2], fnum, fnum - expected))
                elif fnum < frame_nums[-2]:
                    resets.append((frame_nums[-2], fnum))

            # Parse TLVs if present within buffer boundaries
            tlv_ptr = pos + 40
            for _ in range(num_tlvs):
                if tlv_ptr + 8 > total_len or tlv_ptr + 8 > pos + total_len_pkt:
                    break
                tlv_type, tlv_len = struct.unpack_from('<II', data, tlv_ptr)
                tlv_counts[tlv_type] = tlv_counts.get(tlv_type, 0) + 1
                tlv_ptr += 8 + tlv_len

            idx = pos + max(40, total_len_pkt)
        except Exception:
            idx = pos + 8

    print(f"\n1. PACKET SUMMARY:")
    print(f"  Total Valid Frames:   {frame_count:,}")
    print(f"  First Frame Offset:   Byte {first_magic_offset}")
    print(f"  Last Frame Offset:    Byte {last_magic_offset}")
    if frame_nums:
        print(f"  Sequence Range:       Frame #{frame_nums[0]} to #{frame_nums[-1]}")
    if resets:
        print(f"  Sensor Re-sync/Wraps: {len(resets)} times (e.g. #{resets[0][0]} -> #{resets[0][1]})")
    
    print(f"\n2. POINT CLOUD METRICS:")
    if points_per_frame:
        tot_pts = sum(points_per_frame)
        avg_pts = tot_pts / len(points_per_frame)
        print(f"  Total Points Tracked: {tot_pts:,}")
        print(f"  Points/Frame (Min):   {min(points_per_frame)}")
        print(f"  Points/Frame (Avg):   {avg_pts:.1f}")
        print(f"  Points/Frame (Max):   {max(points_per_frame)}")

    print(f"\n3. TLV TYPE DISTRIBUTION:")
    tlv_names = {
        1: "DETECTED_POINTS",
        2: "RANGE_PROFILE",
        3: "NOISE_PROFILE",
        4: "AZIMUTH_STATIC_HEAT_MAP",
        5: "RANGE_DOPPLER_HEAT_MAP",
        6: "STATS",
        7: "SIDE_INFO",
        8: "AZIMUTH_ELEVATION_HEAT_MAP",
        10: "TARGET_LIST_3D",
        11: "TARGET_INDEX"
    }
    for tlv_id, count in sorted(tlv_counts.items()):
        name = tlv_names.get(tlv_id, f"CUSTOM_TLV_{tlv_id}")
        print(f"  TLV {tlv_id:2d} ({name:26s}): {count:5d} occurrences")

    print(f"\n4. PACKET LOSS & DROP ANALYSIS:")
    total_missed = sum(gap for _, _, gap in dropped_frames)
    delivery_rate = (frame_count / (frame_count + total_missed)) * 100.0 if (frame_count + total_missed) > 0 else 100.0
    print(f"  Sequence Drop Events: {len(dropped_frames)}")
    print(f"  Total Frames Dropped: {total_missed}")
    print(f"  Frame Delivery Rate:  {delivery_rate:.2f}%")
    if dropped_frames:
        print(f"  Drop Instances (Up to 5):")
        for prev_f, curr_f, gap in dropped_frames[:5]:
            print(f"    - Drop between frame #{prev_f} and #{curr_f} ({gap} frame{'s' if gap>1 else ''})")

    print("\n" + "=" * 80)
    print("RADAR VALIDATION COMPLETE")
    print("=" * 80)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RoadSense Radar Stream Validator")
    parser.add_argument("--latest", action="store_true", help="Audit latest session")
    parser.add_argument("target", nargs="?", default="latest", help="Path to radar_raw_stream.bin or session directory")
    args = parser.parse_args()
    validate_radar(resolve_radar_file("latest" if args.latest else args.target))
