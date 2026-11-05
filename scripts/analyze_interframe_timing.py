import os
import struct
import sys

SESSION_DIR = r"C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\logs\session_20261105_125830"
RADAR_FRAMES_BIN = os.path.join(SESSION_DIR, "radar", "radar_frames.bin")
RADAR_RAW_BIN = os.path.join(SESSION_DIR, "radar", "radar_raw_stream.bin")
SESSION_TIMELINE = os.path.join(SESSION_DIR, "session_timeline.csv")

MAGIC_WORD = bytes([0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07])
ROAD_MAGIC = b"ROAD"

def analyze_raw_stream(path):
    print("=" * 70)
    print("ANALYSIS OF RADAR_RAW_STREAM.BIN")
    print("=" * 70)
    if not os.path.exists(path):
        print(f"File not found: {path}")
        return []

    with open(path, "rb") as f:
        data = f.read()

    print(f"Total raw stream bytes: {len(data):,} bytes")
    
    # Scan for magic words
    idx = 0
    frames = []
    while True:
        pos = data.find(MAGIC_WORD, idx)
        if pos == -1:
            break
        
        # Check if we have at least 40 bytes for header
        if pos + 40 <= len(data):
            hdr_bytes = data[pos:pos+40]
            # Parse header (little-endian)
            # uint64 magic, uint32 ver, int32 totalPacketLen, uint32 platform, uint32 frameNum, uint32 timeCpuCycles, int32 numObj, int32 numTLVs, int32 subFrameNum
            _, version, totalPacketLen, platform, frameNum, timeCpuCycles, numObj, numTLVs, subFrameNum = struct.unpack("<QIIIIIIii", hdr_bytes)
            
            # Simple validity check
            if 40 <= totalPacketLen <= 65536 and 0 <= numTLVs <= 10 and 0 <= subFrameNum <= 7:
                frames.append({
                    "offset": pos,
                    "frameNum": frameNum,
                    "timeCpuCycles": timeCpuCycles,
                    "subFrameNum": subFrameNum,
                    "numObj": numObj,
                    "totalPacketLen": totalPacketLen
                })
        idx = pos + 1

    print(f"Valid frames found in raw stream: {len(frames)}")
    return frames

def analyze_framed_bin(path):
    print("=" * 70)
    print("ANALYSIS OF RADAR_FRAMES.BIN")
    print("=" * 70)
    if not os.path.exists(path):
        print(f"File not found: {path}")
        return []

    with open(path, "rb") as f:
        data = f.read()

    print(f"Total framed binary bytes: {len(data):,} bytes")
    
    idx = 0
    records = []
    while idx + 24 <= len(data):
        magic = data[idx:idx+4]
        if magic != ROAD_MAGIC:
            # Advance 1 byte if desynced
            idx += 1
            continue
        
        hostMonoNs, hostWallMs, payloadLen = struct.unpack(">QQi", data[idx+4:idx+24])
        idx += 24
        if idx + payloadLen > len(data):
            break
        
        payload = data[idx:idx+payloadLen]
        idx += payloadLen
        
        # Parse TI header from payload if >= 40 bytes
        if payloadLen >= 40 and payload[:8] == MAGIC_WORD:
            _, version, totalPacketLen, platform, frameNum, timeCpuCycles, numObj, numTLVs, subFrameNum = struct.unpack("<QIIIIIIii", payload[:40])
            records.append({
                "hostMonoNs": hostMonoNs,
                "hostWallMs": hostWallMs,
                "payloadLen": payloadLen,
                "frameNum": frameNum,
                "timeCpuCycles": timeCpuCycles,
                "subFrameNum": subFrameNum,
                "numObj": numObj,
                "totalPacketLen": totalPacketLen
            })
            
    print(f"Valid ROAD framed records found: {len(records)}")
    return records

raw_frames = analyze_raw_stream(RADAR_RAW_BIN)
framed_records = analyze_framed_bin(RADAR_FRAMES_BIN)

if not raw_frames:
    print("No raw frames found!")
    sys.exit(0)

# Analyze frame sequence and timing in raw_frames
print("\n" + "=" * 70)
print("RAW STREAM TIMING & SEQUENCE ANALYSIS")
print("=" * 70)

cpu_cycle_diffs = []
frame_num_diffs = []

for i in range(1, len(raw_frames)):
    prev = raw_frames[i-1]
    curr = raw_frames[i]
    
    fn_diff = curr["frameNum"] - prev["frameNum"]
    frame_num_diffs.append((prev["frameNum"], curr["frameNum"], fn_diff))
    
    # Handle 32-bit CPU cycle wrap if any
    cycle_diff = (curr["timeCpuCycles"] - prev["timeCpuCycles"]) & 0xFFFFFFFF
    cpu_cycle_diffs.append(cycle_diff)

# Check frame drops
drops = [d for d in frame_num_diffs if d[2] != 1]
print(f"Total frames: {len(raw_frames)}")
print(f"Sequence irregularities / drops: {len(drops)}")
if drops:
    print("First 10 drops:")
    for prev_fn, curr_fn, diff in drops[:10]:
        print(f"  Frame #{prev_fn} -> #{curr_fn} (gap: {diff - 1} frames)")

# Cycle diff analysis
# What is the radar DSP clock?
# AWR1843 user guide: timeCpuCycles is DSP or MSS cycles.
# MSS R4F runs at 200 MHz. (1 cycle = 5 ns).
# 50 ms at 200 MHz = 10,000,000 cycles.
# 60 ms at 200 MHz = 12,000,000 cycles.
# If DSP clock is 600 MHz (1 cycle = 1.667 ns), 50 ms = 30,000,000 cycles.
print(f"\n--- Radar DSP timeCpuCycles analysis ---")
mean_cycles = sum(cpu_cycle_diffs) / len(cpu_cycle_diffs) if cpu_cycle_diffs else 0
print(f"Mean CPU cycle diff between frames: {mean_cycles:,.1f}")

# Histogram of CPU cycle diffs
import collections
cycle_hist = collections.Counter()
# Round to nearest 10,000 cycles
for c in cpu_cycle_diffs:
    # approximate ms assuming 200 MHz (c / 200,000) or 600 MHz
    cycle_hist[c] += 1

print(f"Top 10 most frequent exact CPU cycle diffs:")
for c, count in cycle_hist.most_common(10):
    ms_at_200mhz = c / 200000.0
    ms_at_600mhz = c / 600000.0
    print(f"  Cycles: {c:,} (occurred {count} times, {count*100/len(cpu_cycle_diffs):.1f}%) -> {ms_at_200mhz:.3f} ms @ 200MHz | {ms_at_600mhz:.3f} ms @ 600MHz")

# Check if there is an alternating pattern in CPU cycles
print("\nFirst 30 raw frames sequence:")
print("Idx | Frame# | SubFrame | Objs | PktLen | DSP Cycles | Cycle Delta | ~ms (@200MHz)")
print("-" * 80)
for i in range(min(30, len(raw_frames))):
    f = raw_frames[i]
    delta_c = cpu_cycle_diffs[i-1] if i > 0 else 0
    ms_200 = delta_c / 200000.0 if i > 0 else 0
    print(f"{i:3d} | #{f['frameNum']:6d} | {f['subFrameNum']:8d} | {f['numObj']:4d} | {f['totalPacketLen']:6d} | {f['timeCpuCycles']:10d} | {delta_c:11d} | {ms_200:6.2f} ms")

if framed_records:
    print("\n" + "=" * 70)
    print("HOST MONOTONIC TIMING ANALYSIS (RADAR_FRAMES.BIN)")
    print("=" * 70)
    host_mono_diffs_ms = []
    for i in range(1, len(framed_records)):
        prev = framed_records[i-1]
        curr = framed_records[i]
        diff_ms = (curr["hostMonoNs"] - prev["hostMonoNs"]) / 1_000_000.0
        host_mono_diffs_ms.append(diff_ms)
        
    print(f"Mean host delta T: {sum(host_mono_diffs_ms)/len(host_mono_diffs_ms):.2f} ms")
    print(f"Min host delta T:  {min(host_mono_diffs_ms):.2f} ms")
    print(f"Max host delta T:  {max(host_mono_diffs_ms):.2f} ms")
    
    # Check distribution in buckets: <45, 45-55, 55-65, >65
    b_under45 = sum(1 for d in host_mono_diffs_ms if d < 45)
    b_50 = sum(1 for d in host_mono_diffs_ms if 45 <= d <= 55)
    b_60 = sum(1 for d in host_mono_diffs_ms if 55 < d <= 65)
    b_over65 = sum(1 for d in host_mono_diffs_ms if d > 65)
    
    print(f"Host Delta T Distribution:")
    print(f"  < 45 ms:       {b_under45:4d} ({b_under45*100/len(host_mono_diffs_ms):.1f}%)")
    print(f"  50 ms (45-55): {b_50:4d} ({b_50*100/len(host_mono_diffs_ms):.1f}%)")
    print(f"  60 ms (55-65): {b_60:4d} ({b_60*100/len(host_mono_diffs_ms):.1f}%)")
    print(f"  > 65 ms:       {b_over65:4d} ({b_over65*100/len(host_mono_diffs_ms):.1f}%)")
    
    print("\nFirst 30 framed records comparison (Host vs DSP):")
    print("Idx | Frame# | Host Mono Delta | DSP Cycle Delta | DSP Calc ms")
    print("-" * 65)
    for i in range(min(30, len(framed_records))):
        rec = framed_records[i]
        h_diff = host_mono_diffs_ms[i-1] if i > 0 else 0
        c_diff = (rec["timeCpuCycles"] - framed_records[i-1]["timeCpuCycles"]) & 0xFFFFFFFF if i > 0 else 0
        c_ms = c_diff / 200000.0 if i > 0 else 0
        print(f"{i:3d} | #{rec['frameNum']:6d} | {h_diff:12.2f} ms | {c_diff:15d} | {c_ms:9.2f} ms")

