# Engineering Post-Mortem: The 3.125 Mbps High-Speed UART Data Loss Bug

## 1. Executive Summary & Incident Timeline

| Incident Metric | Value |
| :--- | :--- |
| **Bug Tracking ID** | Bug #29 (Exynos Scheduler Starvation) & Bug #30 (3.125 Mbps CP2105 FIFO Overflow) |
| **Subsystem Impacted** | Radar Data Acquisition, USB-Serial Ingestion, Driving HUD Point Cloud |
| **Hardware Involved** | TI AWR1843BOOST (CP2105-GM) + Samsung Galaxy M20 (Exynos 7904, Android 10) |
| **Primary Symptom** | Intermittent frame loss, sequence gaps, and HUD stuttering during driving sessions |
| **Root Cause Classification** | Hardware Transceiver Buffer Underrun (Physical Silicon Limit) |
| **Final Status** | Solved (Defensive Frame Salvaging on Legacy M20; 100% Zero-Loss Verified on M32) |

---

## 2. Chronological Investigation Journey

### Phase 1: Main Thread & UI Recomposition Suspect
* **Initial Observation:** During fullscreen driving and calibration, point clouds froze or stuttered.
* **Initial Action:** Profiled with Perfetto. Found the Main UI Thread was heavily loaded (83.4% CPU) due to Jetpack Compose recomposition across offscreen dashboard cards.
* **Fix Implemented:** Unmounted background cards during fullscreen mode (`RootCompositionOptimization`). Main thread CPU load dropped by 44.6% (from 83.4% to 46.2%).
* **Result:** UI responsiveness improved dramatically, but raw radar packet loss *still occurred*.

### Phase 2: Linux Kernel USB Multi-Buffering (`setReadQueue`)
* **Hypothesis:** The single synchronous `UsbRequest` read buffer in `usb-serial-for-android` was being starved when the Android Camera2 ISP thread stalled the CPU.
* **Fix Implemented:** Configured `setReadQueue(128)` with 1 MB of direct kernel URB memory, providing 409.6 ms of absorption headroom.
* **Result:** Stalls were absorbed when packets were small, but sessions *still experienced packet loss during active driving*.

### Phase 3: The 2016-Byte Wall Discovery
* **Empirical Audit:** Ran automated binary parsers across all historical raw binary streams (`radar_raw_stream.bin`) recorded between September and November.
* **The Revelation:**
  * For all frames with $\text{PktLen} \le 2,016\text{ bytes}$: **99.97% success rate**.
  * For all frames with $\text{PktLen} > 2,016\text{ bytes}$: **0.00% success rate (100% loss)**.
  * In every single truncated frame, the missing chunk was consistently **$\approx 600\text{ bytes}$** (median 605 bytes).

### Phase 4: Silicon-Level Root Cause Isolation
* **Datasheet Audit (Silicon Labs CP2105):**
  1. The Enhanced COM Interface (ECI) on the CP2105 has an **internal RX FIFO of only 576 bytes**.
  2. The rated maximum baud rate is **2.0 Mbps**. Running at **3.125 Mbps** is an overclock recommended by TI for PC visualizers.
  3. The AWR1843BOOST PCB **does not route RTS/CTS hardware flow control** lines from the TI DSP to the CP2105.
* **The Mathematics of Overflow:**
  $$\text{UART Wire Speed} = \frac{3,125,000\text{ bits/s}}{10\text{ bits/char}} = 312,500\text{ B/s} = 312.5\text{ bytes/ms}$$
  $$\text{CP2105 FIFO Headroom} = \frac{576\text{ bytes}}{312.5\text{ bytes/ms}} = \mathbf{1.843\text{ milliseconds}}$$
* **The Exynos 7904 USB Reload Gap:**
  * In USB 2.0 Full-Speed (12 Mbps), the host controller operates on 1 ms frames.
  * When a continuous burst exceeds 2,016 bytes ($> 6.45\text{ ms}$ of transmission), the Exynos 7904's Synopsys DWC3 USB host controller experiences a **$1.9\text{ ms}$ descriptor ring reload pause** under concurrent Camera ISP and CPU load.
  * During that $1.9\text{ ms}$, the unbuffered CP2105 FIFO fills up and discards incoming bytes from the radar DSP.
  * Missing bytes: $1.92\text{ ms} \times 312.5\text{ B/ms} \approx \mathbf{600\text{ bytes}}$.

---

## 3. Why Software Buffers Cannot Fix Silicon Overflows

A common misconception was that allocating larger user-space buffers (`readBufferSize = 8192`, `setReadQueue(128)`) would insulate the system against data loss.

```
TI Radar DSP (3.125 Mbps)
       │
       ▼ [No RTS/CTS Flow Control]
┌────────────────────────┐
│  CP2105 Transceiver    │  <─── 576-byte FIFO overflows in 1.84 ms!
│  Hardware RX FIFO      │       Bytes are dropped onto the floor HERE!
└──────────┬─────────────┘
           │ [USB Physical Cable]
           ▼
┌────────────────────────┐
│  Android USB Host      │
│  Kernel Driver (DWC3)  │  <─── 1.9 ms descriptor reload pause!
└──────────┬─────────────┘
           │
           ▼
┌────────────────────────┐
│  Android RAM / App     │  <─── setReadQueue(128) buffers live here!
│  User-Space Buffers    │       Cannot buffer data that never reached the USB cable!
└────────────────────────┘
```

User-space buffers only absorb bytes that have **already crossed the USB cable**. Bytes discarded by the CP2105 hardware FIFO never reach the USB cable, making user-space software buffering completely powerless against transceiver overflows.

---

## 4. The Auditor False Alarm: Arrival Jitter vs. Real Drops

When Option 2 (Intelligent Frame Salvaging) was implemented, `radar_frames.bin` recorded **3,064 out of 3,064 frames with zero sequence gaps**. All 377 truncated frames were salvaged and yielded valid 3D points.

Yet, `health_scorecard.json` printed `"FAIL (76 drops detected)"`.

### Root Cause of the False Alarm:
In `SessionSyncAuditor.kt`:
```kotlin
val baseStats = computeStreamStats(
    timestampsNs = radarMonoList,
    nominalRateHz = 20f,
    anomalyThresholdMs = 75f, // Any interval > 75ms is flagged!
    streamName = "Radar"
)
```
The auditor checked host timestamp intervals (`dt > 75 ms`) rather than sequence numbers. Due to the USB FIFO recovery pause, the arrival interval for 76 salvaged frames was **$76.0\text{--}86.8\text{ ms}$**. The auditor flagged this arrival timing jitter as "drops", even though the frames were physically present and parsed in the container.

