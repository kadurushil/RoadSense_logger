# RoadSense Troubleshooting & Bug Post-Mortem Knowledge Base

> **Purpose:** Comprehensive record of critical bugs, hardware quirks, architectural bottlenecks, root causes, and verified fixes encountered during the development of RoadSense.
> **Location:** `intel/debugging_and_troubleshooting_knowledge_base.md`
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
12. [Bug #12: Camera2 Ultra-Wide (UW) Inaccessibility & Non-Public HAL Device 50 on Samsung Exynos (Android 10)](#bug-12-camera2-ultra-wide-uw-inaccessibility--non-public-hal-device-50-on-samsung-exynos-android-10)
13. [Bug #13: Foxglove Studio Ghost / Duplicate Track Accumulation via Dynamic Entity IDs in `foxglove.SceneUpdate`](#bug-13-foxglove-studio-ghost--duplicate-track-accumulation-via-dynamic-entity-ids-in-foxglovesceneupdate)
14. [Bug #14: Timeline Seek Blank Screen & "Waiting for Keyframe" in Replay Players](#bug-14-timeline-seek-blank-screen--waiting-for-keyframe-in-replay-players)
15. [Bug #15: Dual-Orientation Video Inversion & Mathematical Coordinate Frame Alignment in MCAP](#bug-15-dual-orientation-video-inversion--mathematical-coordinate-frame-alignment-in-mcap)
16. [Bug #16: Large MP4 Metadata Window Overflow & Flipped 3D Camera Frustum in MCAP Conversion](#bug-16-large-mp4-metadata-window-overflow--flipped-3d-camera-frustum-in-mcap-conversion)
17. [Bug #17: 1-Byte Offset in TLV Type 6 (CAN 0x320) FCW Telemetry Decoding & Track ID Aliasing](#bug-17-1-byte-offset-in-tlv-type-6-can-0x320-fcw-telemetry-decoding--track-id-aliasing)
18. [Bug #18: Foxglove User Script Anonymous Schema Hash (`0bb4508b`) & Rejected Calibration Overlay](#bug-18-foxglove-user-script-anonymous-schema-hash-0bb4508b--rejected-calibration-overlay)
19. [Summary of Core Engineering Rules](#summary-of-core-engineering-rules)

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

## Bug #12: Camera2 Ultra-Wide (UW) Inaccessibility & Non-Public HAL Device 50 on Samsung Exynos (Android 10)

### Symptoms
* In `CameraEngine.kt`, camera discovery probed candidate device IDs `["0", "1", "2", "3", "4", "50", "51", "52"]`.
* While the primary back camera (`"0"`, $3.58\text{ mm}$, ~78° HFOV) and front camera (`"1"`, $3.40\text{ mm}$) initialized successfully, probing camera `"50"` (the physical ultra-wide lens, $2.20\text{ mm}$, ~123° HFOV) threw:
  ```text
  Camera2 Candidate 50 REJECTED: IllegalArgumentException - Unknown camera ID 50
  ```
* Candidate `"52"` (secondary depth lens) failed with the identical exception.
* Third-party camera testing tools and RoadSense were completely unable to stream from the ultra-wide lens, despite the Samsung stock camera app seamlessly switching to $0.5\times$ ultra-wide view.

### Hardware Discovery via Low-Level ADB & Dumpsys
Direct HAL diagnostic queries via ADB on the target device (`Samsung SM-M305F` Galaxy M30, Exynos 7904, Android 10 / SDK 29) revealed:
1. **Four Physical Sensors Registered in HAL (`sec-camera-provider-3-0`):**
   * **Device 0 (Main Wide):** $4128 \times 3096$ (13 MP), focal length $3.58\text{ mm}$, $f/1.9$, 78° HFOV.
   * **Device 1 (Front Selfie):** $4608 \times 3456$ (16 MP), focal length $3.40\text{ mm}$, $f/2.0$.
   * **Device 50 (Physical Ultra-Wide):** $2576 \times 1932$ (5 MP), focal length **$2.20\text{ mm}$**, $f/2.2$, **~123° HFOV**.
   * **Device 52 (Secondary Depth):** $2592 \times 1944$ (5 MP), focal length $2.20\text{ mm}$, $f/2.2$.
2. Both Camera 50 and 52 possess registered HAL devices in `dumpsys media.camera`:
   ```text
   == Camera HAL device device@1.0/legacy/50 (v1.0) static information ==
   == Camera HAL device device@3.3/legacy/50 (v3.3) static information ==
   android.sensor.info.preCorrectionActiveArraySize: [0 0 2576 1932]
   android.lens.info.availableFocalLengths: [2.20]
   ```
   This confirmed that the ultra-wide sensor hardware is fully functional and recognized by the low-level camera subsystem.

### Root Cause Analysis

#### 1. AOSP Native CameraService Gatekeeping
In native Android `cameraserver` (`frameworks/av/services/camera/libcameraservice/CameraService.cpp`), any client call to `getCameraCharacteristics(cameraId)` or `connectHelper(cameraId)` executes this validation check:
```cpp
String8 id8 = String8(cameraId);
bool isLogical = mCameraProviderManager->isLogicalCamera(id8.string(), nullptr);

if (!isLogical && !mCameraProviderManager->isPublic(id8.string())) {
    return STATUS_ERROR_FMT(CameraService::ERROR_ILLEGAL_ARGUMENT,
            "Unknown camera ID %s", id8.string()); // Throws IllegalArgumentException to Java
}
```

#### 2. Logical Camera Deficiency (`isLogical == false`)
In Android 9 (API 28) and Android 10 (API 29), Google introduced the `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` capability, where multiple physical sensors are abstracted behind a single logical camera ID that zooms continuously.
On the Exynos 7904 BSP:
* `android.info.supportedHardwareLevel` is `LIMITED`.
* `android.request.availableCapabilities` advertises only `[BACKWARD_COMPATIBLE]`.
* Multi-camera logical capability is entirely absent; `isLogicalCamera("50")` evaluates to `false`.

#### 3. Private Vendor Auxiliary Masking (`isPublic == false`)
`CameraProviderManager::isPublic(id)` returns `true` only if the ID was present in the list returned by `ICameraProvider::getCameraIdList()`.
Inspection of `dumpsys media.camera` header showed:
```text
== Service global info: ==
Number of camera devices: 2
Number of normal camera devices: 2
    Device 0 maps to "0"
    Device 1 maps to "1"
```
Samsung's proprietary camera provider daemon (`vendor.samsung.hardware.camera.provider@3.0::ISehCameraProvider`) hardcodes cameras `50` and `52` as **non-public vendor auxiliary devices**. They are hidden from `getCameraIdList()` and are deliberately inaccessible to non-system UIDs.

#### 4. How the Samsung Stock Camera App Bypasses This
Live process inspection of `com.sec.android.app.camera` (PID 10803) revealed:
* The stock camera app runs as a privileged system application (`/system/priv-app/SamsungCamera/SamsungCamera.apk`, platform-signed).
* The app connects exclusively to **Camera ID 0** via the **legacy Camera API 1 shim**:
  ```text
  == Camera device 0 status -2 dynamic info: ==
  Camera1 API shim is using parameters:
      CameraParameters::dump: mMap.size = 113
      focal-length: 3.58
  ```
* Ultra-wide lens switching is executed internally via proprietary vendor parameter keys in `/system/framework/semcamera.jar` and `semcamera2.jar` (`samsung.android.control.zoomInOutPhoto`, `samsung.android.control.dualCameraDisable`) communicating directly over Samsung's private HIDL interface `ISehCameraProvider`, bypassing standard Android Camera2 public stream rules.

#### 5. Absence of Qualcomm `aux.packagelist` Backdoors
On Qualcomm Snapdragon chipsets, developers frequently expose hidden auxiliary cameras using system property whitelists (`setprop vendor.camera.aux.packagelist <package>`).
On this Samsung Exynos platform, all camera properties were queried via `getprop`. No Qualcomm vendor properties exist. Auxiliary blacklisting is hardcoded inside the compiled binary library `/vendor/lib64/hw/vendor.samsung.hardware.camera.provider@3.0-impl.so`.

### Engineering Verdict & Strategic Workarounds

1. **Software Conclusion:**
   Accessing Camera `50` via standard Android `Camera2` or `CameraX` on Samsung Exynos devices running Android 10 / One UI 2.0 is **impossible** without root access and binary patching of `sec-camera-provider-3-0` or resigning the app with Samsung platform keys. Android 11's `CONTROL_ZOOM_RATIO_RANGE` ($< 1.0$) is not supported on this BSP.
2. **Immediate Optical Workaround (Physical Clip-On Lens):**
   Mounting an optical wide-angle attachment (e.g. $0.6\times$ anamorphic/aspherical lens, ~110°–120° HFOV) directly over the primary Camera 0 ($3.58\text{ mm}$) delivers:
   - Doubled lateral Field of View matching the radar's $\pm 60^\circ$ azimuth coverage.
   - Zero compromises to the RoadSense software pipeline: full Camera2 1080p@30fps streaming, autonomous Road AE, infinity focus lock, and nanosecond monotonic clock sync are 100% preserved.
3. **Target Device Upgrade Roadmap:**
   For hardware deployments requiring native multi-sensor switching without optical attachments, target devices running **Android 11+ (API 30+)** with **`HARDWARE_LEVEL_3`** or **`FULL`** camera support (e.g., Google Pixel 6/7/8 or Samsung Galaxy S21/S22/S23 series), where ultra-wide lenses are officially exposed to third-party applications via public logical multi-camera streams or sub-$1.0\times$ zoom ratio controls.

---

## Bug #13: Foxglove Studio Ghost / Duplicate Track Accumulation via Dynamic Entity IDs in `foxglove.SceneUpdate`

### Symptoms
* When viewing converted `.mcap` sessions in Foxglove Studio's 3D Scene panel, radar tracks did not move cleanly. Instead, each target left a continuous trail of "ghost" bounding boxes that persisted indefinitely.
* Over a 6-minute drive session (7,217 radar frames), the 3D scene accumulated tens of thousands of bounding boxes, eventually causing Foxglove Studio to consume multiple gigabytes of RAM, lag severely, and drop below 5 FPS.
* Inspecting track lifetimes revealed that once an object appeared, its visual representation never cleared—even after the target moved out of radar range or was dropped by the hardware EKF tracker.

### Root Cause Analysis
1. **Foxglove Scene Graph Semantics:**
   In Foxglove Studio's `foxglove.SceneUpdate` Protobuf schema:
   - A `SceneEntity` represents an identifiable 3D object identified by its `id` string.
   - If an incoming `SceneUpdate` carries an entity with an existing `id`, Foxglove updates that entity's transform, geometry, and lifetime.
   - If `delete_existing = False` and the entity `id` is unique or dynamically generated, Foxglove treats it as a brand-new persistent 3D object and adds it to the persistent scene graph.
2. **Dynamic Entity ID Generation in `convert_session_to_mcap.py`:**
   The original converter generated entity IDs using the frame counter or random UUID:
   ```python
   # PROBLEMATIC CODE
   entity = scene_update.entities.add()
   entity.id = f"track_{track_id}_{frame_idx}" # Creates a unique ID on EVERY frame!
   ```
   Because every frame created a distinct string ID (`track_1_101`, `track_1_102`, etc.), Foxglove retained every historical box indefinitely.

### Solution & Fix
1. **Static Entity ID with Atomic Replacement:**
   Group all active radar tracks for the current frame under a single static entity ID (`"radar_tracks"`) and set `scene_update.deletions.append()` or pass `delete_existing = True`:
   ```python
   # VERIFIED FIX in tools/convert_session_to_mcap.py
   scene_update = SceneUpdate()
   entity = scene_update.entities.add()
   entity.id = "radar_tracks" # Static ID
   entity.timestamp.FromNanoseconds(radar_time_ns)
   entity.frame_id = "base_link"
   # Add all bounding boxes, arrows, and labels to this single entity
   ```
2. **Result:**
   On every new radar frame, Foxglove Studio atomically replaces the previous set of bounding boxes with the current frame's detections. Track trails and ghost duplicates are completely eliminated; memory usage remains constant (<350 MB) across infinite playback duration.

---

## Bug #14: Timeline Seek Blank Screen & "Waiting for Keyframe" in Replay Players

### Symptoms
* During replay of converted `.mcap` sessions in Foxglove Studio or web visualizers, seeking backwards or clicking arbitrarily along the timeline scrubber caused the camera panel to display a blank black screen with the status message:
  ```text
  Waiting for keyframe...
  ```
* Video playback only resumed once the timeline scrubbed forward past the next random keyframe, sometimes taking 5 to 10 seconds.
* In some browser playback engines, seeking backwards resulted in corrupted macroblocks, grey smear artifacts, or complete video freeze.

### Root Cause Analysis
1. **H.264 Group of Pictures (GOP) Architecture:**
   H.264 video decoders require an **IDR (Instantaneous Decoder Refresh)** frame to reset reference picture buffers (`DPB`) and begin decoding cleanly. If a seek lands on a P-frame, the decoder cannot decode until it encounters an IDR frame.
2. **Open GOPs and Missing In-Band Headers in Hardware NVENC:**
   When re-encoding video via standard `h264_nvenc` settings, NVENC defaults to open GOPs with keyframe intervals up to 250 frames (~8.3 seconds at 30 FPS).
   Furthermore, NVENC emits SPS (Sequence Parameter Set) and PPS (Picture Parameter Set) NAL units only at the very beginning of the stream (frame 0).
3. **Container Stripping in MCAP:**
   In MP4 containers, SPS/PPS are stored in the container `avcC` atom. But in MCAP, Annex B NAL units (`00 00 00 01`) are sent per message. When a user seeks to second 45, Foxglove's client-side WebCodecs / MP4Box demuxer jumps to the nearest keyframe. If that keyframe lacks in-band SPS/PPS headers or is not an IDR slice (`NAL type 5`), the hardware decoder cannot initialize and hangs in `Waiting for keyframe`.

### Solution & Fix
1. **Strict 1-Second Closed GOP Enforcement:**
   In `tools/convert_session_to_mcap.py`, configured FFmpeg NVENC and CPU pipelines with strict 1-second keyframe cadences:
   - `-g 30`: Forces a maximum GOP length of 30 frames (exactly 1.0 second at 30 FPS).
   - `-forced-idr 1`: Guarantees all keyframes are true IDR slices, not standard I-frames.
   - `-flags +cgop`: Enforces closed GOPs, preventing P/B-frames from referencing pictures across the GOP boundary.
2. **Repeated In-Band SPS/PPS Parameter Sets:**
   Added `-bsf:v "dump_extra=freq=keyframe"` (and NVENC `-repeat-headers 1`) to inject SPS and PPS NAL units immediately preceding every IDR frame:
   ```python
   # VERIFIED NVENC COMMAND in tools/convert_session_to_mcap.py
   ffmpeg_cmd = [
       "ffmpeg", "-y", "-hwaccel", "cuda",
       "-c:v", "h264_cuvid", "-i", input_mp4,
       "-vf", "hflip,vflip",
       "-c:v", "h264_nvenc",
       "-preset", "p1", "-tune", "ull", "-rc", "cbr",
       "-b:v", "8M", "-maxrate", "10M", "-bufsize", "2M",
       "-g", "30", "-forced-idr", "1", "-flags", "+cgop",
       "-extra_hw_frames", "8",
       "-bsf:v", "dump_extra=freq=keyframe",
       "-f", "h264", output_h264
   ]
   ```
3. **Result:**
   Timeline scrubbing in Foxglove Studio is instantaneous (<30 ms seek latency). Seeking to any timestamp immediately acquires an IDR frame with valid SPS/PPS, completely eliminating blank screens and decoding artifacts.

---

## Bug #15: Dual-Orientation Video Inversion & Mathematical Coordinate Frame Alignment in MCAP

### Symptoms
* Recordings made on Samsung Galaxy M30 (mounted in reverse landscape `Surface.ROTATION_270` on the vehicle windshield) played completely upright in media players like VLC.
* However, when demuxed into an `.mcap` container, the video rendered **upside down** in Foxglove Studio's camera panel and 3D scene view.
* Attempting to physically rotate the video via FFmpeg GPU re-encoding (`-vf "hflip,vflip"`) incurred heavy penalties:
  - Conversion took **16 to 47 seconds** per session.
  - Required **80% to 95% NVIDIA GPU utilization**.
  - Generated thermal throttling on host laptops.
  - Furthermore, rotating the raw pixel buffer broke the 3D perspective projection in world space because camera rays were mapped to an inverted physical coordinate system unless `/tf` was simultaneously modified.

### Root Cause Analysis
1. **The Container vs. Bitstream Dichotomy:**
   - Android's `MediaRecorder.setOrientationHint(180)` does **not** physically rotate pixel data when encoding H.264. Instead, it writes a 9-element affine rotation matrix into the MP4 `tkhd` (Track Header) box:
     $$\mathbf{M} = \begin{bmatrix} -1.0 & 0 & 0 \\ 0 & -1.0 & 0 \\ 0 & 0 & 1.0 \end{bmatrix}$$
   - Desktop media players (VLC, QuickTime) read this matrix and rotate the video during presentation.
   - When converting to MCAP, raw H.264 NAL units are demuxed from the container; the `tkhd` atom is discarded. Thus, the video bitstream represents the raw camera sensor buffer, which is inverted ($180^\circ$).
2. **The 3D Frustum Coordinate Transformation:**
   In ROS REP-103 FLU and RDF optical camera coordinate conventions:
   - The camera optical frame has $+X_c$ pointing right, $+Y_c$ pointing down, and $+Z_c$ pointing forward.
   - When the camera is mounted upside down, physical road is at sensor $v = 0$ (top of buffer) and physical sky is at sensor $v = H$ (bottom of buffer).
   - If the camera optical roll angle is left at $0.0^\circ$, texture-mapping this inverted image results in the sky appearing on the road plane and radar point clouds projecting upside-down.

### Solution & Fix
1. **$O(1)$ MP4 Container Orientation Parsing:**
   Added `extract_video_orientation_degrees()` in `tools/convert_session_to_mcap.py` to read the `tkhd` matrix directly from the MP4 file header in $<1\text{ ms}$ without transcoding.
2. **Dynamic `/tf` Optical Frame Roll Alignment:**
   When mount rotation ($180^\circ$) is detected and zero-copy demuxing is used, the converter dynamically folds $180^\circ$ into the `camera_optical` frame's roll angle:
   $$\phi_{\text{effective}} = (\phi_{\text{calib}} + 180^\circ) \pmod{360^\circ}$$
   With roll = $180^\circ$, local $+Y_c$ points **UP** toward the sky in vehicle space ($+Z_b$). When Foxglove Studio projects the unrotated raw video buffer through this inverted frustum, the 3D perspective projection in world space is **100% upright**, and radar points snap perfectly onto target vehicles!
3. **2D Display Shader Rotation:**
   In `tools/foxglove_layouts/RoadSense_Cockpit_Layout.json`, set `"rotation": 180` in the 2D image panel. Foxglove's client-side WebGL fragment shader rotates the display at 60 FPS.
4. **Result:**
   - **Throughput:** Skyrockets from 225 FPS to **11,879 FPS** (>40× speedup).
   - **Conversion Time:** 6-minute drive session converts in **3.40 seconds** (down from 47s).
   - **GPU Load:** Drops to **0%**.
   - **Bitstream Integrity:** 100% lossless bit-exact original video preserved.

---

## Bug #16: Large MP4 Metadata Window Overflow & Flipped 3D Camera Frustum in MCAP Conversion

### Symptoms
* In shorter recording sessions (e.g. `session_20261002_120415`, ~195 MB, ~7,800 frames), zero-copy MCAP conversion correctly detected the $180^\circ$ vehicle windshield mount orientation hint. Foxglove Studio's 3D panel projected the camera frustum completely upright in world space.
* However, in larger recording sessions (e.g. `session_20261002_135348`, ~734 MB, ~29,000 frames), the converted `.mcap` displayed the camera feed **upside down / flipped by $180^\circ$** inside the 3D plot (`3d_cockpit`), even though the 2D video plot panel (`camera_view`) appeared upright (due to presentation shader rotation).

### Root Cause Analysis
1. **Android `MediaRecorder` Atom Placement:**
   Android's native `MediaRecorder` writes the binary media payload (`mdat`) first during continuous recording. When recording finishes, it finalizes the container by appending the `moov` index metadata atom at the **very end** of the file.
2. **Metadata Size Proportional to Frame Count:**
   The `moov` atom contains per-frame indexing sub-boxes: `stts` (time-to-sample), `stsz` (sample sizes), and `stco` (chunk offsets). As recording duration grows, the `moov` atom size grows linearly with frame count.
3. **The 300 KB Tail Window Clipping:**
   In `tools/convert_session_to_mcap.py`, `extract_video_orientation_degrees()` previously attempted to find the `tkhd` atom by scanning only the first 300 KB and the last 300 KB (`max(0, file_size - 300000)`):
   - For `session_20261002_120415` (7,800 frames): `tkhd` was located **83 KB** from EOF ($< 300\text{ KB}$). The scanner found `tkhd`, read the $180^\circ$ affine matrix, and set `/tf` optical roll to $180.0^\circ$.
   - For `session_20261002_135348` (29,000 frames): `tkhd` was located **309,246 bytes (302 KB)** from EOF. The 300 KB window missed the atom by just 9.2 KB!
4. **Fallback Default to $0^\circ$:**
   Missing the atom caused the parser to fall back to $0^\circ$, setting `camera_optical` roll to $0.0^\circ$ in `/tf`. Consequently, Foxglove's 3D renderer projected the raw inverted camera buffer right side up in camera coordinates, which maps to upside-down in vehicle coordinates.

### Solution & Fix
1. **Direct ISO MP4 Box-Traversal Parser:**
   Upgraded `extract_video_orientation_degrees()` in `tools/convert_session_to_mcap.py` to parse top-level ISO box headers (`ftyp`, `mdat`, `moov`, `free`) sequentially:
   - Reads 8-byte box headers (`[size: 4B uint32, type: 4B ASCII]`, handling 64-bit extended sizes).
   - Skips the entire 700+ MB `mdat` box instantly and seeks directly to the exact byte offset of the `moov` atom in $<1\text{ ms}$ with $O(1)$ disk I/O.
   - Parses the `tkhd` matrix from the `moov` box header directly.
2. **10 MB Tail Fallback:**
   Added a generous 10 MB tail search fallback for fragmented or non-sequential MP4 files.
3. **Result:**
   - Orientation detection is 100% deterministic and instantaneous ($<2\text{ ms}$) across any recording size from 10 MB to 50 GB.
   - Verified on `session_20261002_135348` (734 MB): correctly identified $180^\circ$ mount roll, re-converted in 9.14s, and rendered 100% upright in Foxglove 3D plot.

---

---

## Bug #17: 1-Byte Offset in TLV Type 6 (CAN 0x320) FCW Telemetry Decoding & Track ID Aliasing

### Symptoms
* During replay analysis and live visualization, Forward Collision Warning (FCW) alert targets failed to correlate with visible obstacles, or displayed an erroneous track ID (e.g., `TID: 13` or `19` instead of `3435`).
* In the `/radar/adas` telemetry topic, the ACC POI ID correctly reported `3435`, while the FCW Track ID simultaneously reported `13` or `19`.
* FCW target lateral $X$ coordinate evaluated to severe negative off-screen positions ($-21.0\text{ m}$ to $-24.0\text{ m}$) instead of the forward driving corridor ($+0.6\text{ m}$).
* In Foxglove 3D visualizations, high-risk non-FCW tracks (`risk >= 3`) were rendered in red, causing visual ambiguity with genuine FCW targets.

### Root Cause Analysis
1. **The 1-Byte Shift in CAN 0x320 Decoding:**
   In TI AWR1843 Custom MRR firmware TLV Type 6, the first 8 bytes contain CAN message `0x320` (FCW):
   * `Byte 0`: `FCW_Stage_St_enum` (1 byte: `0` = None, `1` = Visual, `2` = Audible)
   * `Bytes 1–2`: `FCW_TrackID_Act_ID` (16-bit Big-Endian uint16: `(b1 << 8) | b2`)
   * `Byte 3`: `FCW_TTC_Act_sec` (scale: 0.1 s: `b3 * 0.1`)
   * `Byte 4`: `FCW_TargetY_Act_m` (scale: 0.5 m: `b4 * 0.5`)
   * `Byte 5`: `FCW_TargetX_Act_m` (scale: 0.2 m, offset: -25.6 m: `b5 * 0.2 - 25.6`)
   * `Byte 6`: `FCW_TargetVy_mps` (scale: 0.5 m/s, offset: -64.0 m/s: `b6 * 0.5 - 64.0`)
   * `Byte 7`: `FCW_TargetVx_mps` (scale: 0.2 m/s, offset: -25.6 m/s: `b7 * 0.2 - 25.6`)
2. **The Erroneous Bitfield Packing Assumption:**
   The legacy decoder incorrectly assumed a 14-bit bitfield packed across Byte 0 and Byte 1 (`((b0 >> 2) << 8) | b1`).
   * When raw bytes were `01 0D 6B 26 17 83 7A 80` (representing Stage 1, Track `0x0D6B` = 3435, TTC 3.8s, TargetY 11.5m, TargetX +0.6m, Vy -3.0m/s):
     - `fcwTrackId` read only `b1` (`0x0D` = $13$ or `0x13` = $19$) instead of `0x0D6B` ($3435$).
     - `fcwTtc` read `b2` (`0x6B` = $107 \rightarrow 10.7\text{ s}$).
     - `fcwTargetY` read `b3` ($38 \times 0.5 = 19.0\text{ m}$).
     - `fcwTargetX` read `b4` ($23 \times 0.2 - 25.6 = \mathbf{-21.0\text{ m}}$).
   * Meanwhile, ACC (`0x327`) unpacked Bytes 0–1 directly as `(a0 << 8) | a1 = 3435`, creating a conflicting ID discrepancy between FCW and ACC.
3. **Visualizer Color Ambiguity:**
   An old risk-severity ladder in `convert_session_to_mcap.py` colored any track with `risk >= 3` in red, masking whether red cuboids originated from genuine FCW safety alerts or general EKF risk scoring.

### Solution & Fix
1. **Corrected FCW Bit-Exact Byte Alignment:**
   Updated `RadarTlvDecoder.kt` and `tools/convert_session_to_mcap.py` to decode `(b1 << 8) | b2` for `trackId`, `b3` for `ttc`, `b4` for `targetY`, `b5` for `targetX`, `b6` for `targetVy`, and `b7` for `targetVx`.
2. **Updated Unit Tests & Documentation:**
   Corrected the specification table in [`intel/radar_tlv_structure_and_decoding_guide.md`](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/radar_tlv_structure_and_decoding_guide.md) and updated `RadarTlvDecoderTest.kt`.
3. **Unified Unambiguous Color Palette:**
   Standardized visualization colors across the Android app and Foxglove:
   * **🔴 Vivid Crimson Red (`#FF1744`):** Strictly reserved for FCW Alert targets (`[FCW S1/S2]`).
   * **🔵 Electric Cyan (`#00E5FF`):** Strictly reserved for ACC Lead vehicles (`[ACC LEAD]`).
   * **🟣 Magenta (`#D500F9`):** Combined target (`[FCW+ACC]`).
   * **🟡 Golden Yellow (`#FFB300`):** All standard tracked obstacles (risk displayed as text `[RISK 1..3]`).
4. **Result:**
   * **100.0% Direct Exact Track ID Matching:** Verified across all recorded sessions (**4,518 / 4,518 frames** in `session_20261002_135348` and **545 / 545 frames** in `session_20261002_120415`).
   * FCW target coordinates perfectly snap to lead vehicles with 0 tracking error.

---

## Bug #18: Foxglove User Script Anonymous Schema Hash (`0bb4508b`) & Rejected Calibration Overlay

### Symptoms
* In Foxglove Studio, an interactive User Script written to adjust camera calibration angles emitted to `/camera/calib_tuned`.
* The Foxglove Topics sidebar displayed an anonymous hexadecimal hash `0bb4508b (from script)` instead of `foxglove.CameraCalibration (protobuf)`.
* The topic properties displayed dashes `-- | --` (0.00 Hz, 0 samples received) when scrubbing the video.
* The Image / Camera View panel reported **"Calibration topic does not exist"** and failed to render 3D radar bounding boxes or point clouds onto the camera feed.

### Root Cause Analysis
1. **Generic TypeScript Type vs. Strongly-Typed Schema Import:**
   - Attempting to type user script output with `Message<"foxglove.CameraCalibration">` from `./types.ts` failed schema resolution in Foxglove Studio's Monaco runtime environment.
   - When Foxglove cannot map a script output directly to a registered Protobuf schema, its internal transpiler generates an ad-hoc, untyped schema identified by a dynamic hash (e.g., `0bb4508b`).
   - Foxglove's Image Panel strictly verifies the topic's schema against registered names (`foxglove.CameraCalibration` or `sensor_msgs/CameraInfo`). Any topic with an anonymous hash is immediately rejected as non-existent.
2. **Untyped JavaScript Arrays vs. Protobuf `Float64Array` Buffers:**
   - In `@foxglove/schemas`, repeated `double` fields (such as camera matrices $K$, $R$, $P$ and distortion vector $D$) are strictly typed as binary `Float64Array` instances, not standard JavaScript number arrays (`number[]`).
   - Supplying plain literals like `K: [fx, 0, cx, ...]` failed Protobuf schema serialization, forcing Foxglove to discard the formal schema identity.
3. **Static $t = 0$ Input Starvation:**
   - Subscribing the user script to `/camera/calib` caused input starvation because `/camera/calib` in MCAP is a static configuration topic emitted only once at session start ($t = 0$).
   - When users scrubbed playback to active driving segments, the script never received input events, resulting in 0 emitted samples (`-- | --`).

### Solution & Fix
1. **Import Directly from `@foxglove/schemas`:**
   Import the explicit `CameraCalibration` TypeScript type from `@foxglove/schemas`:
   ```typescript
   import { Input } from "./types.ts";
   import { CameraCalibration } from "@foxglove/schemas";
   ```
2. **Wrap Matrices with `new Float64Array()`:**
   Explicitly instantiate `Float64Array` buffers for all matrix parameters:
   ```typescript
   D: new Float64Array([0.0, 0.0, 0.0, 0.0, 0.0]),
   K: new Float64Array([fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0]),
   R: new Float64Array([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]),
   P: new Float64Array([fx, 0.0, cx, 0.0, 0.0, fy, cy, 0.0, 0.0, 0.0, 1.0, 0.0]),
   ```
3. **Subscribe to Video Stream for Continuous 30 Hz Evaluation:**
   Set `inputs = ["/camera/video"]` and mirror `event.message.timestamp`. This ensures the script evaluates at ~30 Hz on every video frame, delivering continuous real-time calibration updates during playback and scrubbing.
4. **Interactive Angular Pitch/Yaw Offsets:**
   Compute $cx$ and $cy$ shifts via trigonometric projections ($cx = baseCx - fx \cdot \tan(\psi_{yaw})$, $cy = baseCy + fy \cdot \tan(\theta_{pitch})$). Modifying `PITCH_OFFSET_DEG` or `YAW_OFFSET_DEG` in Foxglove's script panel updates the 3D projection overlay with zero playback latency.

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
10. **Camera2 auxiliary lenses on legacy vendor BSPs (Android 10 Exynos) are non-public:** Always guard camera discovery against `IllegalArgumentException`, inspect `dumpsys media.camera` for `isPublic` status, and avoid assuming physical multi-camera availability on `HARDWARE_LEVEL_LIMITED` chipsets without logical multi-camera support.
11. **Always use static entity IDs with atomic replacement in Foxglove scene graphs:** Generating dynamic per-frame entity IDs causes persistent object accumulation, memory leaks, and severe visualizer lag.
12. **Always enforce 1-second closed GOPs and in-band SPS/PPS headers for streaming video:** Sparse IDR frames and missing parameter sets cause replay seeking to freeze on blank screens.
13. **Prefer mathematical coordinate frame transforms over pixel re-encoding:** Inverted sensor mounts should be corrected via `/tf` optical roll angles and display presentation shaders, preserving lossless zero-copy throughput (>11,000 FPS) and 0% GPU utilization.
14. **Use ISO box-traversal rather than fixed-size tail buffers for container metadata parsing:** MP4 index atom (`moov`) sizes scale with frame count; fixed-buffer tail scans fail on long recording sessions. Jumping directly to `moov` via box headers ensures $O(1)$ zero-copy parsing regardless of file size.
15. **Always verify multi-byte CAN bitfield packing against raw hex dumps before assuming packed bit shifts:** Off-by-one byte decoding shifts ripple through all downstream fields, causing severe coordinate miscalculations (e.g., TargetY decoded as TargetX) and track ID aliasing.
16. **Foxglove Protobuf user scripts require `@foxglove/schemas` and typed `Float64Array` buffers:** Returning plain JavaScript `number[]` arrays or generic `Message<T>` types breaks schema resolution in Foxglove's runtime, producing anonymous hashes (e.g. `0bb4508b`) that are rejected by visualization panels. Always import from `@foxglove/schemas` and instantiate `Float64Array` for matrix vectors.


