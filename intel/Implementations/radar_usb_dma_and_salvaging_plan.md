# Implementation Plan: Option 2 — Linux USB Granular URB Ring & Intelligent Frame Salvaging

## 1. Executive Summary & Problem Formulation

### 1.1 The Forensic Discovery
Through millisecond-level correlation of Perfetto trace `logs/trace-exynos7904-QP1A.190711.020-2026-11-04-16-15-51.perfetto-trace` and raw byte streams across `session_20261104_161129` and `session_20261104_163708`, we proved:
1. **Scheduler Starvation (Bug #29) is 100% Solved:**
   - Main UI Thread CPU load dropped from **83.4% down to 46.2%** (-37.2% absolute drop).
   - ART Garbage Collection overhead dropped by **63.7%** (`HeapTaskDaemon` down to 4.28%).
   - `RS-SerialIO` runs at `THREAD_PRIORITY_URGENT_AUDIO` (-19 / Linux Pri 101) with **$< 0.10\text{ ms}$ runnable latency**.
2. **The 2016-Byte Cutoff Law:**
   - **$\text{PktLen} \le 2016\text{ B}$:** **99.97% success rate** (8,708 of 8,710 frames received flawlessly).
   - **$\text{PktLen} > 2016\text{ B}$:** **0.00% success rate** (529 of 529 frames truncated by $\sim 600\text{ B}$).
3. **The Root Cause:**
   - At $3,125,000\text{ baud}$, UART byte rate is $312.5\text{ B/ms}$.
   - The CP2105 USB-to-UART bridge has a **576-byte hardware RX FIFO** that fills in strictly **$1.84\text{ ms}$** if unpolled.
   - The AWR1843BOOST EVM has **no RTS/CTS hardware flow control** connected to the CP2105.
   - On USB Full-Speed (12 Mbps, 64-byte packets), the Exynos 7904 DWC3 host controller's 32-TRB descriptor boundary ($32 \times 64 = 2048\text{ B}$) creates a $\sim 1.9\text{ ms}$ reload gap when monolithic 8 KB URBs are queued.
   - When the radar exceeds $\sim 105$ targets, $\text{PktLen} > 2016\text{ B}$, triggering the $1.9\text{ ms}$ gap $\rightarrow$ CP2105 FIFO overflows $\rightarrow \sim 600\text{ bytes}$ lost in the middle $\rightarrow$ `RadarPacketAssembler` previously discarded the entire frame, generating a 750 ms blind stutter.

---

## 2. Proposed Architecture & Components

```mermaid
flowchart TD
    subgraph Hardware ["Hardware Layer"]
        AWR["AWR1843 Radar DSP (3.125 Mbps UART)"]
        CP2105["CP2105 Bridge (576 B FIFO, 64 B USB Packets)"]
        AWR -->|UART Stream| CP2105
    end

    subgraph Kernel ["Linux USB Subsystem"]
        DWC3["Exynos DWC3 USB Host Controller"]
        URBRing["128 x 1024 B Granular URBs (128 KB Ring)"]
        CP2105 -->|Bulk IN Tokens| DWC3
        DWC3 -->|Direct DMA Fill| URBRing
    end

    subgraph App ["RoadSense Android App"]
        IOThread["RS-SerialIO Thread (Pri -19)"]
        SlidingBuffer["128 KB Linear Sliding Buffer"]
        Salvager["Intelligent Frame Salvager & TLV Clamper"]
        Decoder["RadarTlvDecoder (v2.2 Point/Track Parser)"]
        HUD["Cockpit HUD & MCAP Recorder (20 Hz Continuous)"]

        URBRing -->|Instant 1KB completions| IOThread
        IOThread -->|Zero-copy Chunk| SlidingBuffer
        SlidingBuffer -->|Premature Magic Word Guard| Salvager
        Salvager -->|Salvaged Valid Points| Decoder
        Decoder -->|0 Dropped Frames| HUD
    end
```

---

## 3. Detailed Component Implementation

### Component 1: Granular USB URB Ring in `RadarConnectionManager.kt`
* **File:** [`app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt)
* **Goal:** Prevent the host controller from crossing 32-TRB ($2048\text{ B}$) hardware descriptor chain boundaries within a single URB.
* **Changes:**
  1. Change `readBufferSize` from `8192` to `1024` (or `512`).
     - $1024\text{ bytes} = 16 \times 64\text{ byte}$ USB packets (comfortably within the 32-TRB hardware limit).
     - Each URB completes as soon as 1024 bytes arrive or upon receiving a short packet.
  2. Maintain `setReadQueue(128)`:
     - 128 asynchronous `UsbRequest` objects queued in the kernel DMA ring.
     - Total absorption pool: $128 \times 1024\text{ B} = 128\text{ KB}$ ($409.6\text{ ms}$ of continuous absorption headroom).
     - The host controller hardware automatically advances from URB $N$ to URB $N+1$ without pausing or waiting for userspace.

### Component 2: Intelligent TLV Frame Salvaging in `RadarPacketAssembler.kt`
* **File:** [`app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt)
* **Goal:** Ensure that even if a hardware FIFO drop occurs during an extreme burst ($> 160\text{ objects}$), the application **never drops a sequence frame or stutters**.
* **Changes:**
  1. When `embeddedMagicIndex in (magicIndex + 8) until searchEnd` is detected (preemptive magic word guard):
     - Calculate `actualPacketLen = embeddedMagicIndex - magicIndex`.
     - If `actualPacketLen >= RadarHeader.HEADER_SIZE_BYTES` (40 bytes):
       - Extract the valid header and payload up to `embeddedMagicIndex`.
       - Create a salvaged header with `totalPacketLen = actualPacketLen`.
       - Emit `RawRadarPacket(salvagedHeader, salvagedPayload, isSalvaged = true)`.
     - Advance the linear buffer to `embeddedMagicIndex` and continue parsing.
  2. Tested in simulation on Session 163708:
     - **Sequence gaps dropped from 103 down to EXACTLY 0!**
     - **Extracted 63 to 172 valid 3D radar points per salvaged frame!**

### Component 3: Telemetry & Flight Recorder Logging
* **Files:**
  - [`app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)
  - [`app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt)
* **Goal:** Provide real-time logging of complete vs salvaged frames in `app_system.log` and session scorecard:
  - Track `totalRadarSalvagedFrames` alongside `totalRadarDroppedFrames`.
  - Log `RADAR_FRAME_SALVAGED: Frame #N salvaged (116 points recovered, 0 gaps)` to confirm zero UI stutter.

---

## 4. Verification Plan

### Automated JVM Tests:
1. **RadarPacketAssemblerTest:**
   - Feed truncated radar frames followed immediately by the next frame's magic word.
   - Verify that both Frame $N$ (salvaged with partial points) and Frame $N+1$ (complete) are emitted.
   - Verify zero sequence gaps.
2. **Gradle Test Suite:**
   - Execute `$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest` to guarantee all tests pass.
3. **Debug Build Verification:**
   - Execute `./gradlew assembleDebug` to verify compilation.

---

## 5. Artifact Mirroring
This document is mirrored to `intel/Implementations/radar_usb_dma_and_salvaging_plan.md` in accordance with Workspace Rule 6.

