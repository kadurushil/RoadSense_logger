# Walkthrough: Automotive Cross-Sensor Timing Proximity Audit

Upgraded [`scripts/audit_cross_sensor_sync.py`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/scripts/audit_cross_sensor_sync.py) to provide a rigorous, automotive-grade cross-sensor timing proximity audit across all sensor streams (**Camera @ 30 FPS, High-Rate IMU @ 100 Hz, Radar @ 20 Hz**).

---

## 1. Key Additions to `audit_cross_sensor_sync.py`

### Full-Stream Pairwise Temporal Proximity
Rather than only checking downsampled 5-second anchors, the auditor now ingests the complete high-rate stream from `imu/imu_frames.csv` ($3,000+$ to $100,000+$ frames) and evaluates exact bidirectional nearest-sample proximity using $O(\log N)$ binary search (`bisect_left`):
1. **Camera $\longleftrightarrow$ High-Rate IMU (100 Hz)**:
   - Evaluates every single steady-state Camera frame against all 100 Hz IMU samples.
   - Calculates Mean $\Delta t$, Median ($p_{50}$), $p_{90}$, $p_{95}$, $p_{99}$, Peak Max $\Delta t$, and Nyquist Compliance ($\% \le 5.00\text{ ms}$).
   - Detects and reports startup ramp lead time (e.g. Camera active $656\text{ ms}$ before IMU recorder started).
2. **Radar $\longleftrightarrow$ Camera (30 FPS)**:
   - Evaluates every steady-state Radar scan against Camera frame timestamps.
   - Calculates Mean, Median, $p_{95}$, Peak Max $\Delta t$, and Nyquist Compliance ($\% \le 16.67\text{ ms}$ half-frame window).
3. **Radar $\longleftrightarrow$ High-Rate IMU (100 Hz)**:
   - Evaluates every Radar scan against high-rate IMU samples.
   - Calculates proximity metrics and $\% \le 5.00\text{ ms}$.

### Upgraded 6-Panel Diagnostic Plot (`sync_audit_report.png`)
Added a dedicated 5th panel specifically tracking **Cross-Sensor Temporal Proximity ($\Delta t$) vs Elapsed Time**:
* **Panel 1:** Radar $\to$ Camera Alignment Latency Offset
* **Panel 2:** Sensor Frame Periods & Drops (Camera & Radar Streams)
* **Panel 3:** UI & User Touch Interactions from App Flight Recorder Log
* **Panel 4:** High-Rate IMU Consolidated Stream Interval Stability (100 Hz in `imu_frames.csv`)
* **Panel 5 (NEW):** **Cross-Sensor Temporal Proximity: Camera Frame to Nearest 100 Hz IMU Sample ($\Delta t$)**
  - Continuous time-series of proximity offset in vibrant cyan (`#38bdf8`).
  - Green dashed line at $5.00\text{ ms}$ (Theoretical Nyquist Optimum: $10\text{ ms} / 2$).
  - Amber dotted line at $10.00\text{ ms}$ (1-Period IMU Horizon).
  - Red scatter marker and arrow annotation highlighting the Peak Max $\Delta t$ across the session.
  - Inset legend box showing: `Mean`, `Median (p50)`, `Nyquist % <= 5ms`.
* **Panel 6:** Multithreaded Write Buffer Interleaving Latency

![6-Panel Cross-Sensor Synchronization Diagnostic Chart](C:\Users\rakadu1.AHEAD\.gemini\antigravity-ide\brain\8520fbad-d6a1-41f6-a76e-be1d203d0b15\sync_audit_report_6panel.png)

---

## 2. Real-World Audit Results

### A. Standalone Drive: Camera + High-Rate IMU ([`session_20261015_144546`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/logs/session_20261015_144546))

```text
[3/5] CROSS-SENSOR TIMING PROXIMITY & TEMPORAL ALIGNMENT:
  Camera <-> High-Rate IMU (100 Hz Stream Proximity):
    Startup Ramp Lead:   Camera active 656.7 ms before IMU recorder started
    Evaluated Frames:    849 steady-state camera frames
    Mean Proximity (dt): 2.96 ms (Theoretical optimum: 2.50 ms)
    Median (p50):        2.98 ms
    Percentiles:         p90=5.15 ms, p95=5.67 ms, p99=7.05 ms
    Peak Max Offset:     8.63 ms
    Nyquist Compliance:  87.75% <= 5.00 ms (Half-period @ 100 Hz), 99.41% <= 7.50 ms
    Status:              PASS (High-precision sub-3ms alignment)

  Radar -> Camera Alignment:
    Status:              SKIP (Radar stream absent)

  Radar -> High-Rate IMU Alignment:
    Status:              SKIP (Radar stream absent)
```

> [!NOTE]
> During steady state, **$99.41\%$ of all Camera frames have an IMU sample within $7.5\text{ ms}$**, with an average temporal distance of **$2.96\text{ ms}$**.

---

### B. Full Sensor Suite: Radar + Camera + High-Rate IMU ([`session_20261012_164153`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/logs/session_20261012_164153))

```text
[3/5] CROSS-SENSOR TIMING PROXIMITY & TEMPORAL ALIGNMENT:
  Camera <-> High-Rate IMU (100 Hz Stream Proximity):
    Startup Ramp Lead:   Camera active 749.1 ms before IMU recorder started
    Evaluated Frames:    32,634 steady-state camera frames
    Mean Proximity (dt): 2.67 ms (Theoretical optimum: 2.50 ms)
    Median (p50):        2.72 ms
    Percentiles:         p90=4.64 ms, p95=4.88 ms, p99=5.94 ms
    Peak Max Offset:     8.82 ms
    Nyquist Compliance:  97.06% <= 5.00 ms (Half-period @ 100 Hz), 99.79% <= 7.50 ms
    Status:              PASS (High-precision sub-3ms alignment)

  Radar -> Camera Alignment:
    Overlapping Frames:  21,751 radar frames analyzed
    Latency Offset:      mean=8.42 ms (Theoretical optimum: 8.33 ms)
    Percentiles:         p50=8.34 ms, p95=15.84 ms, p99=16.47 ms
    Peak Max Offset:     242.34 ms (Session startup pause)
    Nyquist Compliance:  99.93% <= 16.67 ms (Nominal 30 FPS half-frame)
    Status:              PASS (Excellent < 16.7 ms)

  Radar -> High-Rate IMU Alignment:
    Overlapping Frames:  21,746 radar frames analyzed
    Latency Offset:      mean=2.56 ms (Theoretical optimum: 2.50 ms), p50=2.48 ms, p95=4.84 ms
    Peak Max Offset:     8.77 ms
    Nyquist Compliance:  98.53% <= 5.00 ms (Half-period @ 100 Hz)
    Status:              PASS (Sub-3ms alignment)
```

---

## 3. Executive Scorecard

```text
================================================================================
CROSS-SENSOR SYNCHRONIZATION SUMMARY SCORECARD:
================================================================================
  Timeline Monotonic Integrity  : PASS (Multithreaded queue within normal buffer limits)
  High-Rate IMU (100 Hz)        : PASS (Healthy 100 Hz Continuous Stream)
  Camera-IMU Temporal Proximity : PASS (High-precision sub-3ms alignment)
  Radar-Camera Alignment        : PASS (Excellent < 16.7 ms)
  Radar-IMU Temporal Proximity  : PASS (Sub-3ms alignment)
  IMU Anchors (Timeline 5s)     : PASS (Synchronized within half-frame)
  GNSS-Camera Alignment         : PASS (Synchronized)
  Camera Frame Stability        : PASS (Steady state intact)
  UI Desync Correlation         : PASS (0 UI-induced drops)
  Diagnostic Plot Generation    : PASS (Plot Generated)
================================================================================
```

---

## 4. Verification Commands

To run this audit on any past or newly recorded session:
```powershell
# Audit latest recorded session
python scripts/audit_cross_sensor_sync.py --latest

# Audit specific session without generating plot
python scripts/audit_cross_sensor_sync.py logs/session_20261015_144546 --no-plot
```
