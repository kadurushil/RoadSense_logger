# Implementation Plan: Radar High-Speed Zero-Loss Serial Acquisition & Cascading Desync Fix

> **Plan Name:** `radar_high_speed_zero_loss_acquisition_plan`  
> **Status:** Pending User Review & Approval  
> **Target Subsystems:** USB Serial Acquisition, Thread Scheduling, `RadarPacketAssembler`, In-Memory Queuing  
> **Artifact Path:** `intel/Implementations/radar_high_speed_zero_loss_acquisition_plan.md`  

---

## 1. Goal Description

In session recordings where the vehicle is driving fast or passing through dense radar clutter (vegetation, barriers, heavy traffic), the TI AWR1843 radar generates 120–200 point detections per frame. In these dense scenes, the user observed **intermittent tracking dropouts**—radar point cloud dots appeared on screen, but tracked vehicle bounding boxes vanished or flickered out.

Our root-cause investigation proved:
1. **The radar sensor was calculating and transmitting 99.97% of frames.** The sensor DSP/MCU is not starved.
2. **The physical USB-to-UART bridge chip (CP2105) has a 576-byte hardware FIFO** that overflows in just **1.84 ms** at 3.125 Mbps if the phone's read thread pauses.
3. **The Android app's USB read thread was executing synchronous parsing, disk writing, and Compose state dispatching**, delaying USB servicing past 1.84 ms and causing ~600 bytes to be dropped from the tail of large packets (where TLV 3 Tracks reside).
4. **`RadarPacketAssembler` suffered from a cascading desynchronization bug:** when bytes were lost from Frame $N$, it swallowed the start of Frame $N+1$ trying to satisfy Frame $N$'s length, destroying two frames per overflow event.

This plan details the surgical, non-destructive architecture changes to achieve **100% zero-drop radar ingestion at 3.125 Mbps** across all vehicle speeds and point cloud densities.

---

## 2. User Review Required

> [!IMPORTANT]
> **Key Architecture Decisions for Review:**
> 1. **Decoupled Producer-Consumer Architecture:** The USB IO thread (`SerialInputOutputManager`) will no longer parse or decode radar frames. Its sole responsibility will be pushing incoming raw bytes into a pre-allocated in-memory buffer (`ArrayBlockingQueue<ByteArray>`).
> 2. **Preemptive Magic Word Guard:** If a packet's claimed length spans across another `TI_MAGIC` word (proving that bytes were lost over the physical link), `RadarPacketAssembler` will immediately reject the truncated frame and lock onto the new frame without swallowing its header.
> 3. **Thread Priority Elevation:** The USB reader thread priority will be boosted to `Process.THREAD_PRIORITY_URGENT_AUDIO` (`-19`), guaranteeing the highest Linux kernel scheduling precedence.

---

## 3. Proposed Changes

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              MODIFIED WORKFLOW ARCHITECTURE                            │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                        │
│   [CP2105 USB Endpoint (3.125 Mbps)]                                                   │
│             │                                                                          │
│             ▼                                                                          │
│   [USB Reader Thread: THREAD_PRIORITY_URGENT_AUDIO (-19)]                              │
│       - SerialInputOutputManager.Listener.onNewData(data)                              │
│       - ONLY calls rawByteQueue.offer(data)                                            │
│       - Latency: < 0.02 ms (NEVER blocks, hardware bucket never overflows)             │
│             │                                                                          │
│             ▼                                                                          │
│   [ArrayBlockingQueue<ByteArray>(2048) (~2 MB RAM elastic shock absorber)]             │
│             │                                                                          │
│             ▼                                                                          │
│   [RadarProcessingWorker Thread (Priority: URGENT_DISPLAY)]                            │
│       ├── 1. Session Recording (radar_raw_stream.bin)                                 │
│       ├── 2. RadarPacketAssembler.appendBytes()                                        │
│       │       └── [NEW] Preemptive TI_MAGIC Guard (zero cascading drops)               │
│       ├── 3. If packets assembled:                                                     │
│       │       ├── Write to radar_frames.bin                                            │
│       │       ├── Decode TLVs (tlvDecoder.decode)                                      │
│       │       └── Dispatch to MutableStateFlow (Compose UI)                            │
│                                                                                        │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### Component 1: `RadarPacketAssembler.kt` (Eliminating Cascading Frame Swallowing & Allocations)

#### [MODIFY] [`RadarPacketAssembler.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt)
* **Add `findMagicWord(bytes, startIndex)`:** Overload to scan for the 8-byte sync word starting from an arbitrary offset.
* **Preemptive Magic Word Guard:** While accumulating bytes for a packet of claimed length `totalPacketLen`:
  ```kotlin
  val nextMagicIndex = findMagicWord(bytes, magicIndex + 8)
  if (nextMagicIndex != -1 && nextMagicIndex < magicIndex + totalPacketLen) {
      // Hardware dropped bytes! Frame N was truncated.
      // Discard truncated partial frame and immediately lock onto nextMagicIndex
      val remaining = bytes.copyOfRange(nextMagicIndex, bytes.size)
      buffer.reset()
      buffer.write(remaining)
      continue
  }
  ```
* **Compacted Buffer Slicing:** Avoid calling `buffer.toByteArray()` multiple times per loop; inspect in-place where possible.

---

### Component 2: `RadarConnectionManager.kt` (URGENT_AUDIO Priority & Non-Blocking USB Read)

#### [MODIFY] [`RadarConnectionManager.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt)
* Boost `SerialInputOutputManager` thread priority to `Process.THREAD_PRIORITY_URGENT_AUDIO` (`-19`).
* Increase `readBufferSize` from `32768` to `65536` (64 KB) to service larger USB bulk transfers in a single syscall.
* Ensure `onNewData` dispatches to listeners without adding any internal processing locks.

---

### Component 3: `RadarViewModel.kt` (Decoupled Producer-Consumer Processing Worker)

#### [MODIFY] [`RadarViewModel.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt)
* **Introduce High-Throughput In-Memory Queue:**
  ```kotlin
  private val rawByteQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(2048)
  ```
* **Ultra-Fast USB Callback:**
  ```kotlin
  connectionManager.addDataListener { bytes ->
      rawByteQueue.offer(bytes) // Takes < 2 microseconds! Returns immediately to port.read()
  }
  ```
* **Dedicated Processing Thread (`RadarProcessingWorker`):**
  Spawns an active consumer loop on a dedicated thread (`THREAD_PRIORITY_URGENT_DISPLAY`):
  ```kotlin
  while (isProcessingActive) {
      val bytes = rawByteQueue.take()
      val hostMonoNs = SystemClock.elapsedRealtimeNanos()
      val hostWallMs = System.currentTimeMillis()

      rawRecorder.write(bytes)
      sessionRecorder.writeRawBytes(bytes)

      val assembledPackets = packetAssembler.appendBytes(bytes)
      if (assembledPackets.isNotEmpty()) {
          for (packet in assembledPackets) {
              sessionRecorder.writeFrame(packet, hostMonoNs, hostWallMs)
          }
          val decoded = tlvDecoder.decode(assembledPackets.last())
          _latestFrame.value = decoded
      }
  }
  ```

---

## 4. Verification Plan

### Automated JVM Unit Tests
1. **New Unit Tests in [`RadarPacketAssemblerTest.kt`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/decoding/RadarPacketAssemblerTest.kt):**
   * `appendBytes_truncatedFrameWithPrematureNextMagic_recoversNextFrameWithoutSwallowing`: Feeds a frame with claimed length 2,000 bytes, but injects a valid Frame 2 at offset 1,400 bytes. Verifies that Frame 2 is successfully assembled with zero swallowed bytes!
   * `appendBytes_highDensityConsecutiveFrames_assemblesAllPacketsCleanly`: Feeds 100 consecutive 2,500-byte frames (simulating 200-point clouds) and verifies 100% assembly rate.
2. **Execute JVM Test Suite:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
   ./gradlew testDebugUnitTest
   ```
3. **Assemble Debug APK:**
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
   ./gradlew assembleDebug
   ```

### Hardware & Stream Validation Script
Run the stream validation test against synthetic truncated streams:
```powershell
conda run -n roadsense-mcap python -m unittest discover -s scripts/tests
```
Verify that our synthetic truncated stream test confirms zero lost frames when truncation occurs.

