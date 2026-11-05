import os
import struct

session_dir = r"logs/session_20261105_153856"
bin_path = os.path.join(session_dir, "radar", "radar_frames.bin")
with open(bin_path, "rb") as f:
    data = f.read()

ROAD_MAGIC = b"ROAD"
MAGIC_WORD = bytes([0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07])
records = []
idx = 0
while idx + 24 <= len(data):
    if data[idx:idx+4] != ROAD_MAGIC:
        idx += 1
        continue
    hostMonoNs, hostWallMs, payloadLen = struct.unpack(">QQi", data[idx+4:idx+24])
    idx += 24
    if idx + payloadLen > len(data):
        break
    payload = data[idx:idx+payloadLen]
    idx += payloadLen
    if payloadLen >= 40 and payload[:8] == MAGIC_WORD:
        _, version, totalPacketLen, platform, frameNum, timeCpuCycles, numObj, numTLVs, subFrameNum = struct.unpack("<QIIIIIIii", payload[:40])
        records.append({
            "frameNum": frameNum,
            "hostMonoNs": hostMonoNs,
            "timeCpuCycles": timeCpuCycles,
        })

class OnlineDiscipliner:
    def __init__(self, nominal_freq_hz=600_000_000.0):
        self.nominal_freq = nominal_freq_hz
        self.anchor_mono = None
        self.anchor_cycles = None
        self.last_raw_cycles = None
        self.wrap_offset = 0
        self.alpha = 1.0e9 / nominal_freq_hz # ~1.666667 ns/cycle
        self.filtered_alpha = self.alpha
        
    def feed(self, raw_cycles, raw_mono_ns):
        if self.last_raw_cycles is not None:
            if raw_cycles < self.last_raw_cycles and (self.last_raw_cycles - raw_cycles) > (1 << 31):
                self.wrap_offset += (1 << 32)
        self.last_raw_cycles = raw_cycles
        unwrapped = raw_cycles + self.wrap_offset
        
        if self.anchor_mono is None:
            self.anchor_mono = raw_mono_ns
            self.anchor_cycles = unwrapped
            return raw_mono_ns
            
        elapsed_cycles = unwrapped - self.anchor_cycles
        disciplined_mono = self.anchor_mono + int(elapsed_cycles * self.filtered_alpha)
        
        # slow low-pass update of alpha (time constant ~ 100 frames = 5 seconds)
        if elapsed_cycles > 0:
            current_obs_alpha = (raw_mono_ns - self.anchor_mono) / elapsed_cycles
            self.filtered_alpha += (current_obs_alpha - self.filtered_alpha) * 0.005
            
        return disciplined_mono

disc = OnlineDiscipliner()
disciplined_timestamps = [disc.feed(r['timeCpuCycles'], r['hostMonoNs']) for r in records]

d_deltas = [(disciplined_timestamps[i] - disciplined_timestamps[i-1])/1e6 for i in range(1, len(disciplined_timestamps))]
print(f"Online Disciplined Deltas: Min={min(d_deltas):.2f}ms, Max={max(d_deltas):.2f}ms, Mean={sum(d_deltas)/len(d_deltas):.2f}ms")
anomalies = [d for d in d_deltas if d < 45 or d > 55]
print(f"Anomalies outside 45-55ms: {len(anomalies)} (out of {len(d_deltas)})")
for i in range(20):
    fn = records[i+1]['frameNum']
    raw_dt = (records[i+1]['hostMonoNs'] - records[i]['hostMonoNs'])/1e6
    print(f"  Frame #{fn:4d}: Disciplined dt={d_deltas[i]:6.2f} ms | Raw dt={raw_dt:6.2f} ms")

