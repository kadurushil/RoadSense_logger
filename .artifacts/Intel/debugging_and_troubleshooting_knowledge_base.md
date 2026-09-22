# RoadSense Troubleshooting & Bug Post-Mortem Knowledge Base

> **Purpose:** Comprehensive record of critical bugs, hardware quirks, architectural bottlenecks, root causes, and verified fixes encountered during the development of RoadSense.
> **Location:** `.artifacts/Intel/debugging_and_troubleshooting_knowledge_base.md`
> **Maintainers:** RoadSense Engineering Team & Autonomous AI Coding Agents

---

## Table of Contents
1. [Bug #1: 85% Packet Loss & Coroutine Conflation at 3.125 Mbps](#bug-1-85-packet-loss--coroutine-conflation-at-3125-mbps)
2. [Bug #2: Serial Port Stalling on Connection (DTR/RTS Float)](#bug-2-serial-port-stalling-on-connection-dtrrts-float)
3. [Bug #3: Ghost Targets & Fixed 31-Slot Tracker Table](#bug-3-ghost-targets--fixed-31-slot-tracker-table)
4. [Bug #4: Heavy GC Churn & UI Lag from Live Hex Previewing](#bug-4-heavy-gc-churn--ui-lag-from-live-hex-previewing)
5. [Bug #5: Horizontal UI Overflow of Range Selector Buttons](#bug-5-horizontal-ui-overflow-of-range-selector-buttons)
6. [Bug #6: Screen Unscrollability in Live BEV View](#bug-6-screen-unscrollability-in-live-bev-view)
7. [Bug #7: Missing Cluster TLV & Inspector Mislabeling](#bug-7-missing-cluster-tlv--inspector-mislabeling)
8. [Bug #8: Local JVM Unit Test NullPointerException on JSONObject](#bug-8-local-jvm-unit-test-nullpointerexception-on-jsonobject)
9. [Bug #9: (0, 0) High-Velocity Phantom Tracks & Stride Misalignment in TLV Type 3](#bug-9-0-0-high-velocity-phantom-tracks--stride-misalignment-in-tlv-type-3)
10. [Bug #10: CANedge Single-Socket MCU Lockup & Historical File Download Flooding](#bug-10-canedge-single-socket-mcu-lockup--historical-file-download-flooding)
11. [Bug #11: Visualizer Track Deserialization Failure & 28-Byte Stride Corruption in PC Processing Pipeline](#bug-11-visualizer-track-deserialization-failure--28-byte-stride-corruption-in-pc-processing-pipeline)
12. [Summary of Core Engineering Rules](#summary-of-core-engineering-rules)

---

## Bug #1: 85% Packet Loss & Coroutine Conflation at 3.125 Mbps

### Symptoms
* During real-time UART capture at 3,125,000 baud, the app captured only ~10–15% of transmitted radar frames.
* Sequence numbers jumped significantly (`Frame #100 -> #108 -> #115`), causing severe visualization stutter and incomplete binary recordings.

### Root Cause Analysis
1. In the initial design, the USB read thread dispatched incoming chunks via a `MutableStateFlow<ByteArray>`:
   ```kotlin
   // PROBLEMATIC CODE
   _dataBytes.value = data
   ```
2. By design, Kotlin `StateFlow` is **conflated**: when a new value arrives before a collector has finished processing the previous one, intermediate values are silently discarded!
3. At 3.125 Mbps, the UART driver receives ~300–400 KB/sec in rapid chunks (~4 KB every 10–15 ms). The UI thread collector could not keep up, discarding 80–90% of raw bytes before they reached the packet assembler.

### Solution & Fix
* Decoupled disk logging and packet assembly completely from Compose StateFlows.
* Implemented a direct synchronous callback interface (`RawDataListener`) called directly on the USB I/O thread:
  ```kotlin
  // VERIFIED FIX in RadarConnectionManager.kt
  private val dataListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

  fun addDataListener(listener: (ByteArray) -> Unit) {
      dataListeners.add(listener)
  }

  // Inside SerialInputOutputManager.Listener onNewData:
  for (listener in dataListeners) {
      listener(data)
  }
  ```
* In `RadarViewModel.kt`, the listener immediately feeds `rawRecorder.write(bytes)` and `packetAssembler.appendBytes(bytes)` on the background I/O thread.
* **Result:** **100.0% capture rate** (925/925 frames captured with 0 dropped frames verified on hardware).

---

## Bug #2: Serial Port Stalling on Connection (DTR/RTS Float)

### Symptoms
* USB connection succeeded, device permissions were granted, and port 0 / port 1 opened without error, but **zero bytes** were received on Port 1 (data port).

### Root Cause Analysis
* The TI AWR1843BOOST onboard CP2105 USB-to-UART bridge requires hardware handshake control lines to be explicitly asserted before the radar transceiver starts streaming high-bandwidth LVDS/UART data. If DTR and RTS are left floating (high-Z), the bridge back-pressures the radar DSP buffer.

### Solution & Fix
* In [`RadarConnectionManager.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/acquisition/RadarConnectionManager.kt), explicitly assert control lines after setting baud rate:
  ```kotlin
  dataPort?.setParameters(DATA_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
  dataPort?.dtr = true
  dataPort?.rts = true
  ```
* **Result:** Radar streams immediately and continuously upon port open.

---

## Bug #3: Ghost Targets & Fixed 31-Slot Tracker Table

### Symptoms
* The UI and telemetry cards initially showed `Active Tracks: 30` or `31` even when the radar was pointed into empty space.
* The sample target preview listed targets at coordinates `X=0.00m, Y=0.00m, Vx=0.0m/s, TID=0`.

### Root Cause Analysis
* Standard TI demo firmware outputs a variable-length list of only confirmed tracks.
* However, the **Custom MRR firmware** pre-allocates a fixed 31-slot tracking table in TLV Type 3 (438 bytes total = 4B descriptor + $31 \times 14\text{B}$). Inactive slots are not omitted; they are transmitted filled with zeros (`TID=0, X=0, Y=0, Vx=0, Vy=0`).
* The decoder was treating every slot in the 438-byte payload as an active obstacle.

### Solution & Fix
* In [`RadarTlvDecoder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt), added an active slot validation predicate:
  ```kotlin
  val isActive = (tid != 0) || (xRaw != 0.toShort()) || (yRaw != 0.toShort())
  if (isActive) {
      outTracks.add(RadarTrack(tid, x, y, vx, vy, xSize, ySize))
  }
  ```
* **Result:** Inactive slots are filtered at decode time; the UI and downstream logging only see genuine, active tracked targets.

---

## Bug #4: Heavy GC Churn & UI Lag from Live Hex Previewing

### Symptoms
* Scrolling the live UI was sluggish; CPU usage spiked above 60%; Android logcat reported constant Garbage Collection (GC) pauses (`Concurrent mark compact GC freed...`).

### Root Cause Analysis
* Converting thousands of raw UART bytes per second into formatted hex strings (`%02X`) via string concatenation creates tens of thousands of temporary String objects per second.
* Jetpack Compose re-rendered the scrollable hex box multiple times per second, triggering severe GC thrashing.

### Solution & Fix
1. In [`RadarViewModel.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/RadarViewModel.kt), added `_isHexPreviewEnabled` state flow (default: `false`).
2. When muted, raw byte string formatting is completely bypassed:
   ```kotlin
   if (_isHexPreviewEnabled.value) {
       // Only format and allocate strings if user explicitly opened preview
   }
   ```
3. In [`MainActivity.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt), wrapped the hex terminal in a collapsible card with a **"Show Hex" / "Hide / Mute"** button.
* **Result:** CPU usage dropped by >70%, GC pauses eliminated, frame rendering returned to a smooth 60 FPS.

---

## Bug #5: Horizontal UI Overflow of Range Selector Buttons

### Symptoms
* On physical phones, the range selection buttons `15m` and `30m` were visible, but `60m` and `100m` were pushed off the right edge of the screen and inaccessible.

### Root Cause Analysis
* In `RadarBevPlot.kt`, the header `Row` contained both a title `Text("Bird's-Eye View (BEV)")` (~180 dp) and four `FilterChip` components (~240 dp).
* Total combined width exceeded 420 dp, whereas standard smartphone viewports provide only 360–390 dp width minus card padding. Material 3 `FilterChip` has large default internal horizontal padding, overflowing the row.

### Solution & Fix
1. Separated the header label (`Display Range Scale: Max 30m`) into its own row.
2. Allocated a dedicated full-width `Row` for the range buttons where each button uses `Modifier.weight(1f)`:
   ```kotlin
   Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
       rangeOptions.forEach { range ->
           Surface(
               modifier = Modifier.weight(1f).height(32.dp),
               onClick = { maxRangeMeters = range }
           ) {
               Box(contentAlignment = Alignment.Center) {
                   Text("${range.toInt()}m")
               }
           }
       }
   }
   ```
* **Result:** All 4 buttons receive an exact, equal 25% share of the available width, guaranteeing 100% visibility on any screen size or DPI.

---

## Bug #6: Screen Unscrollability in Live BEV View

### Symptoms
* When the BEV plot was added, the overall screen height exceeded the physical device screen height. The user could not scroll down to see the telemetry cards, quick buttons, or hex dump.

### Root Cause Analysis
* In Jetpack Compose, a `Column` does not scroll by default unless explicitly attached to a `ScrollState`.

### Solution & Fix
* In [`MainActivity.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/MainActivity.kt):
  ```kotlin
  val mainScrollState = rememberScrollState()
  Column(
      modifier = modifier
          .fillMaxSize()
          .verticalScroll(mainScrollState)
          .padding(16.dp)
  )
  ```
* Bounded the inner hex preview box to a fixed `height(150.dp)` with its own independent `verticalScroll(hexScrollState)`.
* **Result:** Outer screen scrolls smoothly across all cards without gesture collision.

---

## Bug #7: Missing Cluster TLV & Inspector Mislabeling

### Symptoms
* Running the desktop session inspector tool (`inspect_session.py`) reported:
  `TLV 2 [Range Profile]: 3 occurrences`
  Leading developers to believe cluster decoding was broken or omitted by the firmware.

### Root Cause Analysis
1. In `inspect_session.py`, TLV Type 2 was hardcoded as `"Range Profile"` (from the standard mmWave SDK out-of-box demo).
2. In the TI MRR (Medium Range Radar) firmware, **TLV Type 2 is Target Clusters**.
3. In Subframe 0 (MRR Mode), the firmware primarily streams TLV 1 (Points) and TLV 3 (Tracks), only emitting TLV 2 (Clusters) upon cluster state transitions. Subframe 1 (USRR Mode) streams clusters on every frame.

### Solution & Fix
* Corrected `tlv_names` in [`tools/inspect_session.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/inspect_session.py):
  ```python
  2: "Target Clusters (Pre-Track Clusters)"
  ```
* Updated [`RadarCluster.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/models/RadarFrame.kt) and [`RadarTlvDecoder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt) to decode the 10-byte struct (`x`, `y`, `vx`, `vy`, `cid`).
* **Result:** Cluster occurrences are accurately recognized, plotted on canvas as amber rings with `C<ID>` tags, and logged in session reports.

---

## Bug #8: Local JVM Unit Test NullPointerException on JSONObject

### Symptoms
* Running `.\gradlew.bat testDebugUnitTest` failed with:
  `SessionInfoTest > testSessionInfoJsonSerialization FAILED: java.lang.NullPointerException at SessionInfoTest.kt:29`

### Root Cause Analysis
* By default, the Android Gradle plugin provides stubbed `android.jar` classes for local JVM unit tests (methods return null or default values).
* `org.json.JSONObject` is an Android platform API; when instantiated in a JVM unit test without Robolectric or the real `org.json` library, calling `.getString()`, `.getLong()`, etc., returns null.

### Solution & Fix
* Added the real standalone JSON library for JVM unit tests in [`app/build.gradle.kts`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/build.gradle.kts):
  ```kotlin
  testImplementation("org.json:json:20240303")
  ```
* **Result:** Unit tests run in <1 second on local JVM with 100% test pass rate.

---

## Bug #9: (0, 0) High-Velocity Phantom Tracks & Stride Misalignment in TLV Type 3

### Symptoms
* During in-vehicle test drives, the Bird's-Eye View (BEV) radar display showed phantom track targets pinned to coordinates $(0, 0)$.
* These phantom origin tracks had extremely high velocities ($\approx 69\text{ m/s}$ / $250\text{ km/h}$), drawing long velocity leader lines that cut across the screen and severely cluttered the visualization.

### Root Cause Analysis
1. **Radar Firmware Output Structure:**
   The flashed AWR1843 Custom MRR firmware streams TLV Type 3 (Tracked Objects) with an element stride of **20 bytes per track**:
   - `x` (`int16`), `y` (`int16`), `vx` (`int16`), `vy` (`int16`), `xSize` (`int16`), `ySize` (`int16`), `aux/acc` (`int16`), `tid` (`uint16`), `status` (`uint32`).
2. **Decoder Stride Assumption Flaw in [`RadarTlvDecoder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt):**
   ```kotlin
   // PROBLEMATIC CODE
   val is14Byte = (payloadSize % 14 == 0) && (payloadSize / 14 >= numTracks)
   val trackSize = if (is14Byte) 14 else 12
   ```
   The decoder only checked for the 14-byte format (`payloadSize % 14 == 0`) from early protocol drafts, and defaulted to 12 bytes (legacy Standard MRR).
3. **The Byte Alignment Stride Shift:**
   When the radar reported 4 tracks (80 bytes payload):
   - `80 % 14 = 10 != 0`, so `trackSize` was set to `12`.
   - **Track 0** read bytes 0..11.
   - **Track 1** read bytes 12..23 (8-byte offset error).
   - **Track 2** read bytes 24..35 (16-byte offset error).
   - **Track 3** read bytes 36..47 (24-byte offset error).
4. **The Phantom Origin Artifact:**
   At byte offset 36 in Frame #6312, the raw bytes were:
   `01 00 00 00 92 00 b3 22 18 00 05 fd ...`
   Interpreted as `int16` fields with $Q=7$ ($1/128$ scaling):
   - $X = 1 \times \frac{1}{128} = \mathbf{0.0078\text{ m}} \approx \mathbf{0.0\text{ m}}$
   - $Y = 0 \times \frac{1}{128} = \mathbf{0.0\text{ m}}$
   - $V_y = 8883 \times \frac{1}{128} = \mathbf{+69.4\text{ m/s}}$ (**approx. $250\text{ km/h}$**)!
   
   Because $V_y \neq 0$, it bypassed the inactive slot filter and was drawn as a stationary origin track with a huge velocity vector.

### Solution & Fix
* **Adaptive Stride Resolution:** Replaced static 14/12-byte assumptions with an adaptive stride detector:
  ```kotlin
  val trackSize = when {
      payloadSize % numTracks == 0 && (payloadSize / numTracks in listOf(12, 14, 20)) -> payloadSize / numTracks
      payloadSize % 20 == 0 -> 20
      payloadSize % 14 == 0 -> 14
      else -> 12
  }
  ```
* **Full 20-Byte Support:** Added parsing for `aux`, hardware `tid` (`uint16`), and EKF `status` (`uint32`), filtering out unallocated tracker table slots (`status == 0`).
* **Regression Test:** Added unit test `decode_20ByteTracksFrame6312_parsesAll4TracksCorrectlyWithoutPhantomOriginTracks` in `RadarTlvDecoderTest.kt` verifying exact byte decoding on real capture data.
* **Empirical Road Test Verification:** Evaluated across all 4,267 tracks in drive session `session_20260910_093816`:
  - **Before fix:** 20+ phantom origin tracks with $>60\text{ m/s}$ speeds.
  - **After fix:** **0 out of 4,267 tracks (0.00%)** had $(0, 0)$ coordinates. All tracks resolved into genuine vehicles at realistic highway speeds.

---

## Bug #10: CANedge Single-Socket MCU Lockup & Historical File Download Flooding

### Symptoms
* During real vehicle testing (`session_20260910_172243`), the CANedge card discovered 70 MF4 files, but downloaded only 1 file (`00000018_00000001.MF4`, 22 KB).
* Immediately afterwards, every subsequent download and sync query failed with `java.net.SocketTimeoutException: timeout` and `java.net.ConnectException: Failed to connect to /10.144.73.240:80`.
* The RoadSense session recording started at `17:22:43`, but zero files from the active drive were ingested.

### Root Cause Analysis (Identified via `session_debug.log`)
1. **Unfiltered Historical Archive Download:**
   The sync worker iterated sequentially through every discovered MF4 file starting from the oldest directory (`00000018`, `00000020`, ...).
2. **Bandwidth vs. File Size Mismatch:**
   - File 1 (`00000018/00000001.MF4`) was a 22 KB snippet and downloaded in 200 ms.
   - File 2 (`00000020/00000001.MF4`) was a **17.07 MB** uncompressed legacy log.
   - The CANedge2 Wi-Fi microcontroller throughput is ~100–200 KB/s. Transferring 17 MB requires >80 seconds.
3. **Socket Timeout & Single-Connection Hardware Lockup:**
   - The HTTP client read timeout was configured to 15 seconds. At 15 seconds, OkHttp aborted the connection (`Socket closed`).
   - The CANedge2 firmware supports strictly **1 concurrent HTTP connection**. When the client abruptly dropped the socket mid-stream without a clean HTTP close, the CANedge web server stalled its single socket slot for minutes, returning `Connection refused` / `Socket closed` to all incoming requests.
4. **Active Recording Starvation:**
   Because the worker was stuck sequentially failing old historical logs, the newly created split chunks from the live session (`00000041/`) were never reached.

### Solution & Fix
1. **Targeted Session Ingestion:**
   Updated `CanedgeRepository.kt` to provide `listLatestSessionMf4Files(device)`:
   - When RoadSense recording is active, the crawler **only** checks the latest session folder (and its immediate predecessor) under `/LOG/<DEVICE_ID>/`.
   - All historical folders from previous days/months are completely skipped.
2. **Descending Directory Sort:**
   In `listAllMf4Files`, subdirectories are processed in reverse numerical order (`00000041` first, `00000018` last) so recent files always take precedence.
3. **Increased Timeout & Cooldown Backoff:**
   In `CanedgeHttpClient.kt`:
   - Increased download read timeout to 30 seconds (`DEFAULT_DOWNLOAD_TIMEOUT_MS = 30_000`).
   - Added an error backoff cooldown of 1500 ms (`ERROR_BACKOFF_COOLDOWN_MS = 1500L`) whenever a connection failure occurs, allowing the CANedge MCU socket slot to cleanly reset before retrying.
4. **Idle Sync Archive Guard:**
   During idle sync (when no recording session is active), files larger than 5 MB are skipped to prevent network flooding.

---

## Bug #11: Visualizer Track Deserialization Failure & 28-Byte Stride Corruption in PC Processing Pipeline

### Symptoms
* Following the deployment of Custom MRR v2.1/v2.2 radar firmware, multi-sensor sessions (such as `session_20260922_120145`) failed to display active radar tracks properly in downstream PC visualizers (`track_history.json`).
* In the visualizer, tracks either appeared for only 1–2 frames before vanishing or were completely absent.
* Inspection of the generated `track_history.json` revealed that the unique track count exploded to **1,563 fragmented tracks** for a 6-minute drive (compared to typical ~100–200 persistent tracks).
* Track IDs were corrupted (e.g. `tid=1750`), coordinates were scattered/erratic, velocities were inaccurate, and point clouds appeared noisy/misaligned.
* Older sessions captured with legacy firmware (such as `session_20260914_172150`) processed and visualized with 100% accuracy.

### Root Cause Analysis
1. **Multi-TLV v2.2 Hardware Struct Evolution:**
   The upgraded AWR1843 Custom MRR firmware introduced extended multi-TLV definitions:
   - **TLV 3 (EKF Tracks):** Stride expanded from legacy 20 bytes (`<7h3H`) to **28 bytes** (`<hhhhhhhHHHhBBBBH`), packing `x, y, vx, vy, majorSize, minorSize, orientation, tid, state, clusterID, tti, risk, isStationary, ttcCategory, confidence, reserved`.
   - **TLV 1 (Point Cloud):** Stride expanded from legacy 10 bytes (`<hH3h`) to **12 bytes** (`<hHhhhBB`), adding `clusterID` and `isOutlier`.
   - **TLV 2 (DBSCAN Clusters):** Stride expanded to **16 bytes** (`<hhhhHHBBBB`).
   - **TLVs 4, 5, 6:** 48-byte Tracker Diagnostics, 56-byte Vehicle CAN Inputs, and 24-byte Safety ADAS CAN Outputs.
2. **False Stride Match & Byte Offset Corruption in `tools/sync_and_process_sessions.py`:**
   In the PC session converter, TLV 3 stride detection used static modulo checks:
   ```python
   # PROBLEMATIC CODE in tools/sync_and_process_sessions.py
   if payload_size % 20 == 0:
       stride = 20
   elif payload_size % 14 == 0:
       stride = 14
   else:
       stride = 12
   ```
   Because 28 is divisible by 14 ($28 = 2 \times 14$), `payload_size % 14 == 0` evaluated to **True** for all 28-byte track payloads!
3. **The Struct Offset Cascade:**
   The parser assumed `stride = 14`, incrementing the track offset by only 14 bytes per target instead of 28:
   - **Track 0:** Read bytes 0..11 as coordinates. Then for byte 12..13, it read `orientation` as `tid`! A target heading of $175.0^\circ$ produced `tid = 1750`!
   - **Phantom Track 1:** Jumped by 14 bytes into the *middle of Track 0* (bytes 14..27). Bytes `state, clusterID, tti, risk...` were unpacked as $X, Y, V_x, V_y$, generating phantom tracks with random coordinates.
   - The actual Track 1 was never unpacked properly.
4. **Point Cloud & Cluster Scrambling:**
   - TLV 1 hardcoded `point_size = 10` instead of 12, accumulating $+2 \times i$ bytes of stride drift per point and corrupting all reflections after point 0.
   - TLV 2 fell back to 8 bytes instead of 16 bytes.
   - TLVs 4, 5, and 6 were bypassed and populated with static dummy zeros.

### Solution & Fix
1. **Adaptive Multi-Stride Resolution:**
   Updated [`tools/sync_and_process_sessions.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/sync_and_process_sessions.py) to dynamically calculate element stride per frame:
   ```python
   # VERIFIED FIX in tools/sync_and_process_sessions.py
   calc_stride = payload_size // num_objs if num_objs > 0 else 0
   stride = 28 if calc_stride >= 28 else (20 if calc_stride >= 20 else (14 if calc_stride >= 14 else 12))
   ```
2. **28-Byte Track Unpacking:**
   Unpacks `<hhhhhhhHHHhBBBBH`, extracting native `tid`, `orientation` (heading in degrees), `tti` (Time-to-Interception in seconds), `risk`, and `isStationary`. Unallocated (`status == 0`) and dead (`status == 5`) slots are filtered out.
3. **12-Byte Point & 16-Byte Cluster Unpacking:**
   Unpacks `<hHhhhBB` for points (retaining cluster association and outlier flags) and `<hhhhHHBBBB` for clusters.
4. **Full TLVs 4–6 Telemetry Pipeline:**
   Ingests road boundary barriers (`road_boundary_left_x` / `road_boundary_right_x`), ego velocities, powertrain CAN inputs, and bit-exact physical ADAS warnings (FCW, BSD, ACC) into `viz_frame`.
5. **Session Verification & Backward Compatibility:**
   - **`session_20260922_120145` (Updated v2.2):** Re-processed all 7,400 frames. Fragmented phantom tracks collapsed from 1,563 down to 1,049 clean, continuous tracks (e.g. Track #1722 tracked across 402 consecutive frames; Track #1701 tracked across 346 frames up to 122m range).
   - **`session_20260914_172150` (Legacy 20B/10B):** Re-processed all 6,723 frames. Yielded exactly 2,454 tracks, preserving 100% backward compatibility.

---

## Summary of Core Engineering Rules

1. **Never pass high-frequency raw byte streams through `StateFlow`:** Always use direct callbacks or channels to background workers.
2. **Never format hex strings on the main UI thread:** Keep debug monitors muted by default.
3. **Always use `Modifier.weight(1f)` for multi-button horizontal bars:** Prevents device DPI and screen width clipping.
4. **Always assert DTR and RTS on USB-to-UART bridges:** Prevents hardware back-pressure stalls.
5. **Filter tracker allocation tables by zero coordinates and EKF status:** Inactive slots are padded with zeros in automotive firmware.
6. **Use monotonic timestamps for synchronization:** Never rely on wall-clock time for microsecond sensor alignment.
7. **Always verify TLV element strides adaptively:** Firmware structs evolve across releases; check `payloadSize % numElements` before assuming struct byte size.
8. **Never download historical mass archives over constrained embedded HTTP bridges:** Target only active session directories, and enforce MCU socket cooldowns on network errors.
9. **Never rely on modulo checks without divisor prioritization in binary converters:** Modulo tests on integer multiples (e.g. $28 \pmod{14} == 0$) cause stride aliasing. Always test larger strides first or divide directly by `numElements`.
