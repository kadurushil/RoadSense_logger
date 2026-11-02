import struct
import os
import csv

session_dir = 'logs/session_20261102_165601'
raw_path = os.path.join(session_dir, 'radar', 'radar_raw_stream.bin')
frames_path = os.path.join(session_dir, 'radar', 'radar_frames.bin')
timeline_path = os.path.join(session_dir, 'session_timeline.csv')
camera_csv_path = os.path.join(session_dir, 'camera', 'camera_frames.csv')

TI_MAGIC = b'\x02\x01\x04\x03\x06\x05\x08\x07'

with open(raw_path, 'rb') as f:
    raw_data = f.read()

idx = 0
frames_info = []

while True:
    pos = raw_data.find(TI_MAGIC, idx)
    if pos == -1:
        break
    if pos + 40 <= len(raw_data):
        ver, total_len, plat, fnum, cycles, num_obj, num_tlvs, subframe = struct.unpack_from('<IIIIIIII', raw_data, pos + 8)
        next_pos = raw_data.find(TI_MAGIC, pos + 8)
        actual_bytes = (next_pos - pos) if next_pos != -1 else (len(raw_data) - pos)
        frames_info.append({
            'pos': pos,
            'fnum': fnum,
            'cycles': cycles,
            'total_len': total_len,
            'actual_bytes': actual_bytes,
            'truncated': actual_bytes < total_len,
            'num_obj': num_obj,
            'num_tlvs': num_tlvs
        })
    idx = pos + 8

print(f"Total frames in raw stream: {len(frames_info)}")

target = 19667
surrounding = [f for f in frames_info if target - 10 <= f['fnum'] <= target + 15]

print(f"\n--- Surrounding frames in raw_stream around #{target} ---")
for f in surrounding:
    print(f"  Frame #{f['fnum']:5d}: cycles={f['cycles']:10d}, declared={f['total_len']:4d}B, actual={f['actual_bytes']:4d}B, trunc={f['truncated']!s:5s}, objs={f['num_obj']:3d}, tlvs={f['num_tlvs']}")

# Now let's inspect radar_frames.bin around 19667
with open(frames_path, 'rb') as f:
    framed_data = f.read()

pos = 0
file_len = len(framed_data)
assembled_frames = []

while pos + 24 <= file_len:
    magic, mono, wall, plen = struct.unpack('>4sQQI', framed_data[pos:pos+24])
    payload = framed_data[pos+24:pos+24+plen]
    if payload.startswith(TI_MAGIC) and len(payload) >= 40:
        ver, total_len, plat, fnum, cycles, num_obj, num_tlvs, subframe = struct.unpack_from('<IIIIIIII', payload, 8)
        assembled_frames.append({
            'fnum': fnum,
            'mono_ns': mono,
            'wall_ms': wall,
            'cycles': cycles,
            'plen': plen,
            'num_obj': num_obj,
            'num_tlvs': num_tlvs
        })
    pos += 24 + plen

print(f"\nTotal assembled frames in radar_frames.bin: {len(assembled_frames)}")
surrounding_assembled = [f for f in assembled_frames if target - 10 <= f['fnum'] <= target + 15]

print(f"\n--- Surrounding assembled frames in radar_frames.bin around #{target} ---")
prev_mono = None
for f in surrounding_assembled:
    dt_ms = (f['mono_ns'] - prev_mono) / 1e6 if prev_mono else 0.0
    print(f"  Frame #{f['fnum']:5d}: mono_ns={f['mono_ns']}, dt={dt_ms:6.2f}ms, objs={f['num_obj']:3d}, tlvs={f['num_tlvs']}")
    prev_mono = f['mono_ns']

# Now let's check camera frame 4679
with open(camera_csv_path, 'r', encoding='utf-8') as f:
    cam_rows = list(csv.reader(f))[1:]

cam_4679 = None
for r in cam_rows:
    if len(r) >= 3 and int(r[0]) == 4679:
        cam_4679 = r
        break

if cam_4679:
    print(f"\n--- Camera HW Frame 4679 ---")
    print(f"  Frame: {cam_4679[0]}, MonoNs: {cam_4679[1]}, UTC: {cam_4679[2]}")
