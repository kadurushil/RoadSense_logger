import csv, math

def get_stats(csv_path):
    with open(csv_path, 'r') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    dts = []
    for i in range(1, len(rows)):
        t_prev = int(rows[i-1]['elapsed_realtime_ns'])
        t_curr = int(rows[i]['elapsed_realtime_ns'])
        dts.append((t_curr - t_prev) / 1e6)
    duration_s = (int(rows[-1]['elapsed_realtime_ns']) - int(rows[0]['elapsed_realtime_ns'])) / 1e9
    spikes_16 = [dt for dt in dts if dt > 16.0]
    spikes_15 = [dt for dt in dts if dt > 15.0]
    mean_dt = sum(dts) / len(dts)
    std_dt = math.sqrt(sum((x - mean_dt)**2 for x in dts) / len(dts))
    dts_sorted = sorted(dts)
    return {
        'total_frames': len(rows),
        'duration_s': duration_s,
        'effective_hz': len(rows) / duration_s,
        'mean_dt': mean_dt,
        'std_dt': std_dt,
        'min_dt': dts_sorted[0],
        'p50_dt': dts_sorted[int(len(dts)*0.5)],
        'p90_dt': dts_sorted[int(len(dts)*0.90)],
        'p95_dt': dts_sorted[int(len(dts)*0.95)],
        'p99_dt': dts_sorted[int(len(dts)*0.99)],
        'max_dt': dts_sorted[-1],
        'spikes_16_count': len(spikes_16),
        'spikes_16_pct': len(spikes_16) / len(dts) * 100,
        'spikes_per_sec': len(spikes_16) / duration_s,
        'spikes_15_count': len(spikes_15),
        'spikes_15_pct': len(spikes_15) / len(dts) * 100,
        'spikes_15_per_sec': len(spikes_15) / duration_s
    }

s1 = get_stats('logs/session_20261012_164153/imu/imu_frames.csv')
s2 = get_stats('logs/session_20261014_143426/imu/imu_frames.csv')
s3 = get_stats('logs/session_20261014_151927/imu/imu_frames.csv')
s4 = get_stats('logs/session_20261014_160013/imu/imu_frames.csv')
s5 = get_stats('logs/session_20261014_164430/imu/imu_frames.csv')
s6 = get_stats('logs/session_20261014_174450/imu/imu_frames.csv')

headers = ['METRIC', 'BASELINE', 'TEST 1434', 'TEST 1519', 'LEAD 1 (1600)', 'LEAD 2 (1644)', 'LEAD 3 (1744)']
print(f"{headers[0]:20} | {headers[1]:9} | {headers[2]:9} | {headers[3]:9} | {headers[4]:13} | {headers[5]:13} | {headers[6]:13}")
print('-' * 115)
for k in s1.keys():
    v1, v2, v3, v4, v5, v6 = s1[k], s2[k], s3[k], s4[k], s5[k], s6[k]
    if isinstance(v1, float):
        print(f"{k:20} | {v1:9.3f} | {v2:9.3f} | {v3:9.3f} | {v4:13.3f} | {v5:13.3f} | {v6:13.3f}")
    else:
        print(f"{k:20} | {v1:9} | {v2:9} | {v3:9} | {v4:13} | {v5:13} | {v6:13}")
