#!/usr/bin/env python3
"""
Diagnostic utility to inspect raw binary UART dump files captured from TI AWR1843 radar.

Usage:
    python tools/inspect_raw_uart.py <path_to_raw_file.bin>
"""

import sys
import os
import struct

MAGIC_WORD = bytes([0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07])
HEADER_SIZE = 40  # 8 bytes magic + 8 * 4 bytes fields

def inspect_file(filepath):
    if not os.path.isfile(filepath):
        print(f"Error: File not found: {filepath}")
        return

    file_size = os.path.getsize(filepath)
    print("=" * 70)
    print(f"RoadSense - Raw UART Binary Dump Inspector")
    print(f"File: {filepath}")
    print(f"Size: {file_size:,} bytes ({file_size / (1024*1024):.2f} MB)")
    print("=" * 70)

    with open(filepath, "rb") as f:
        data = f.read()

    pos = 0
    magic_indices = []
    while True:
        idx = data.find(MAGIC_WORD, pos)
        if idx == -1:
            break
        magic_indices.append(idx)
        pos = idx + 8

    total_magics = len(magic_indices)
    print(f"\n[+] Total Magic Words Detected: {total_magics}")

    if total_magics == 0:
        print("[-] No TI AWR1843 magic words found in this file.")
        print("    Possible causes:")
        print("    1. Radar was not actively chirping/streaming data.")
        print("    2. Wrong port (received config CLI echo instead of high-speed data).")
        print("    3. Baud rate mismatch on radar data port.")
        return

    frames = []
    discontinuities = 0
    corrupt_headers = 0

    print("\n--- Scanning Frames ---")
    for i, idx in enumerate(magic_indices):
        remaining = len(data) - idx
        if remaining < HEADER_SIZE:
            print(f"[*] Frame {i+1} at offset {idx}: Incomplete header at end of file ({remaining} bytes remaining).")
            break

        header_bytes = data[idx + 8 : idx + HEADER_SIZE]
        version, total_len, platform, frame_num, cpu_cycles, num_det_obj, num_tlvs, subframe = struct.unpack(
            "<IIIIIIII", header_bytes
        )

        # Gap analysis
        gap = 0
        if i > 0:
            prev_idx = magic_indices[i-1]
            prev_total_len = frames[-1]["total_len"]
            expected_next = prev_idx + prev_total_len
            gap = idx - expected_next
            if gap != 0:
                discontinuities += 1

        is_valid = True
        if total_len < HEADER_SIZE or total_len > 65536:
            is_valid = False
            corrupt_headers += 1

        frame_info = {
            "index": i + 1,
            "offset": idx,
            "frame_num": frame_num,
            "subframe": subframe,
            "total_len": total_len,
            "num_det_obj": num_det_obj,
            "num_tlvs": num_tlvs,
            "cpu_cycles": cpu_cycles,
            "gap": gap,
            "valid": is_valid
        }
        frames.append(frame_info)

        # Print first 10 and periodic frames
        if i < 10 or (i + 1) % 25 == 0 or not is_valid or gap != 0:
            status = "OK" if is_valid else "CORRUPT_LEN"
            gap_str = f" [GAP: {gap:+d} B]" if gap != 0 else ""
            print(f"  Frame #{frame_num:6d} (Sub {subframe}) @ offset {idx:8d} | Len: {total_len:5d} B | "
                  f"Objs: {num_det_obj:3d} | TLVs: {num_tlvs:2d} | Status: {status}{gap_str}")

    print("\n" + "=" * 70)
    print("Summary:")
    print(f"  Total frames parsed:         {len(frames)}")
    print(f"  Valid packet headers:        {len(frames) - corrupt_headers}")
    print(f"  Corrupt/out-of-bounds:       {corrupt_headers}")
    print(f"  Stream discontinuities/gaps: {discontinuities}")

    if frames:
        first_frame = frames[0]["frame_num"]
        last_frame = frames[-1]["frame_num"]
        expected_frames = last_frame - first_frame + 1 if last_frame >= first_frame else len(frames)
        print(f"  Frame counter range:         #{first_frame} -> #{last_frame} (Span: {expected_frames})")
        if expected_frames > 0:
            loss_pct = ((expected_frames - len(frames)) / expected_frames) * 100
            print(f"  Frame sequence capture rate: {100.0 - max(0.0, loss_pct):.1f}%")

    print("=" * 70)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tools/inspect_raw_uart.py <path_to_raw_file.bin>")
        sys.exit(1)
    inspect_file(sys.argv[1])
