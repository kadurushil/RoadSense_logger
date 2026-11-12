import sys
from perfetto.trace_processor import TraceProcessor

trace_path = sys.argv[1] if len(sys.argv) > 1 else 'logs/baseline_scroll.perfetto-trace'
print(f"=== Analyzing Perfetto Trace: {trace_path} ===\n")
tp = TraceProcessor(trace=trace_path)

# 1. Top slices in com.bajajauto.roadsense
query = """
SELECT
    t.name AS thread_name,
    s.name AS slice_name,
    COUNT(*) AS count,
    ROUND(AVG(s.dur) / 1e6, 2) AS avg_dur_ms,
    ROUND(MAX(s.dur) / 1e6, 2) AS max_dur_ms,
    ROUND(SUM(s.dur) / 1e6, 2) AS total_dur_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name = 'com.bajajauto.roadsense'
GROUP BY thread_name, slice_name
ORDER BY total_dur_ms DESC
LIMIT 25;
"""

qr = tp.query(query)
print(f"{'THREAD':<22} | {'SLICE':<38} | {'COUNT':<6} | {'AVG_MS':<8} | {'MAX_MS':<8} | {'TOTAL_MS':<8}")
print("-" * 98)
for row in qr:
    t_name = str(row.thread_name or '')
    s_name = str(row.slice_name or '')[:38]
    print(f"{t_name:<22} | {s_name:<38} | {row.count:<6} | {row.avg_dur_ms:<8} | {row.max_dur_ms:<8} | {row.total_dur_ms:<8}")

# 2. Semantics & Native Region Lock Contention Summary
sem_query = """
SELECT
    s.name AS slice_name,
    COUNT(*) AS count,
    ROUND(AVG(s.dur) / 1e6, 2) AS avg_dur_ms,
    ROUND(MAX(s.dur) / 1e6, 2) AS max_dur_ms,
    ROUND(SUM(s.dur) / 1e6, 2) AS total_dur_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name = 'com.bajajauto.roadsense'
  AND (s.name = 'checkForSemanticsChanges'
       OR s.name LIKE 'Lock contention on Region lock%'
       OR s.name = 'AndroidOwner:measureAndLayout'
       OR s.name = 'Recomposer:recompose'
       OR s.name LIKE 'Choreographer#doFrame%')
GROUP BY s.name
ORDER BY total_dur_ms DESC;
"""
print("\n--- KEY UI PIPELINE & CONTENTION SLICES ---")
for row in tp.query(sem_query):
    print(f"  {row.slice_name:<45} | count={row.count:<5} | avg={row.avg_dur_ms:<6} ms | max={row.max_dur_ms:<7} ms | total={row.total_dur_ms:<8} ms")

# 3. Check CPU frequency during trace (using cpu_counter_track.cpu)
freq_query = """
SELECT
    ct.cpu AS cpu_id,
    ROUND(AVG(c.value) / 1000, 0) AS avg_mhz,
    ROUND(MIN(c.value) / 1000, 0) AS min_mhz,
    ROUND(MAX(c.value) / 1000, 0) AS max_mhz
FROM counter c
JOIN cpu_counter_track ct ON c.track_id = ct.id
WHERE ct.name = 'cpufreq'
GROUP BY ct.cpu
ORDER BY ct.cpu;
"""
print("\n--- CPU FREQUENCIES DURING TRACE (MHz) ---")
try:
    for row in tp.query(freq_query):
        print(f"  CPU {row.cpu_id}: avg={row.avg_mhz} MHz, min={row.min_mhz} MHz, max={row.max_mhz} MHz")
except Exception as e:
    print(f"  Freq query: {e}")

tp.close()
