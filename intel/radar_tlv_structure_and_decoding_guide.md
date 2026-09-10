# TI AWR1843 Radar TLV Structure & Decoding Guide

> **Target Audience:** Future AI Agents & Engineers maintaining or extending the radar acquisition, decoding, logging, and visualization pipelines in **RoadSense**.
> **Last Updated:** September 2026
> **Reference Python Repo:** `D:\Work\Repo\CANenbl_unifiedRadarTracker` (`src/radar_tracker/hardware/read_and_parse_frame.py`)

---

## 1. Executive Summary & Physical Layer

RoadSense interfaces with a Texas Instruments **AWR1843BOOST** automotive mmWave radar sensor. 

### Hardware & UART Parameters
* **Radar Model:** TI AWR1843BOOST (Single-chip 76–81 GHz FMCW transceiver + ARM Cortex-R4F MSS + TI C674x DSP)
* **Firmware Flashed:** Custom MRR (Medium Range Radar) ADAS firmware
* **CLI Port:** Port 0 (Standard baud: `115,200`, 8-N-1) — Configuration / sensor control
* **Data Stream Port:** Port 1 (High-speed baud: `3,125,000`, 8-N-1) — Binary telemetry output
* **Byte Endianness:** Little-Endian (`<`) for all payload values; Network Big-Endian (`>`) used exclusively for RoadSense session storage framing tags.

---

## 2. Binary Packet Architecture & Framing

The radar transmits continuous streams of discrete binary frames over UART. Every frame is aligned to a **32-byte boundary** (padded with `0x0F` or zeros at the end).

```
+-------------------------------------------------------------------------------+
|  40-Byte Radar Frame Header (Starts with 8-byte Magic Word)                   |
+-------------------------------------------------------------------------------+
|  TLV 1: Type (4B) | Length (4B) | Descriptor (4B) | Elements Array (N * M B)  |
+-------------------------------------------------------------------------------+
|  TLV 2: Type (4B) | Length (4B) | Descriptor (4B) | Elements Array (N * M B)  |
+-------------------------------------------------------------------------------+
|  ...                                                                          |
+-------------------------------------------------------------------------------+
|  TLV K: Type (4B) | Length (4B) | Descriptor (4B) | Elements Array (N * M B)  |
+-------------------------------------------------------------------------------+
|  Zero Padding (0 to 31 bytes to reach 32-byte alignment boundary)             |
+-------------------------------------------------------------------------------+
```

### 2.1 Synchronization Magic Word (8 Bytes)
To detect the start of a frame, scan the byte stream for the 8-byte magic sequence:
```text
Hex Bytes:   02 01 04 03 06 05 08 07
Uint16 (LE): {0x0102, 0x0304, 0x0506, 0x0708}
Uint64 (LE): 0x0708050603040102
```

### 2.2 40-Byte Frame Header Specification (`MmwDemo_output_message_header_t`)
Directly following the magic word are 8 fields of 4 bytes (`uint32`) each, forming a 40-byte header:

| Byte Offset | Field Name | Type | Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | **Magic Word** | `uint16[4]` | 8 B | Frame sync: `0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07` |
| `8` | **Version** | `uint32` | 4 B | SDK Version (`Major << 24 \| Minor << 16 \| Bugfix << 8 \| Build`) |
| `12` | **Total Packet Length** | `uint32` | 4 B | Total packet size in bytes (Header + all TLVs + 32B padding) |
| `16` | **Platform** | `uint32` | 4 B | Hardware platform ID (`0xA1843` or `0xA1642`) |
| `20` | **Frame Number** | `uint32` | 4 B | Monotonically increasing sequential frame counter |
| `24` | **Time CPU Cycles** | `uint32` | 4 B | DSP clock cycle timestamp (approx. 1.66 ns per cycle @ 600 MHz) |
| `28` | **Num Detected Obj** | `uint32` | 4 B | Total number of point cloud detections in this frame |
| `32` | **Num TLVs** | `uint32` | 4 B | Count of TLV data blocks following this header |
| `36` | **Subframe Number** | `uint32` | 4 B | Operating mode: **0 = MRR** (Medium Range), **1 = USRR** (Ultra Short Range) |

---

## 3. Subframe Modes & TLV Dispatch Architecture

The TI AWR1843 MRR firmware operates a dual-chirp timing engine. Knowing which subframe is active determines which TLVs to expect:

```
Subframe 0 (MRR Mode — Long/Medium Range <= 120m):
├── TLV Type 1: Point Cloud (Detected reflections)
└── TLV Type 3: Active Tracks (Kalman / EKF Tracker Table)

Subframe 1 (USRR Mode — Short Range / Wide Angle <= 20m):
├── TLV Type 1: Point Cloud (Detected reflections)
├── TLV Type 2: Clusters (DBSCAN clustered objects)
└── TLV Type 4: Parking Assist (32 angular distance bins)
```

> [!NOTE]
> In single-profile MRR configurations, all frames are **Subframe 0**. In this mode, the firmware streams **TLV 1** and **TLV 3** in 100% of frames, while **TLV 2** is only emitted sporadically during initial cluster creation / state transitions.

---

## 4. TLV (Type-Length-Value) Container Protocol

Following the 40-byte header, each TLV consists of an 8-byte TLV header:
- `Type` (`uint32`, 4 bytes)
- `Length` (`uint32`, 4 bytes) — Total length of payload value (includes the 4-byte descriptor).

### 4.1 The 4-Byte Object Descriptor
All primary TLV payloads (Types 1, 2, 3, 4) start with a standard 4-byte descriptor:

| Byte Offset | Field Name | Type | Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | **numObjects** | `uint16` | 2 B | Number of elements $N$ in the array |
| `2` | **xyzQFormat** | `uint16` | 2 B | Fixed-point scaling factor $Q$ ($0 \le Q \le 31$) |

### 4.2 Q-Format Metric Scaling Principle
The radar DSP outputs spatial and velocity coordinates as signed 16-bit integers (`int16`). To convert them into real-world floating-point values (meters and meters/second):

$$\text{Value} = \frac{\text{raw\_int16}}{2^Q} = \text{raw\_int16} \times \left(\frac{1}{2^Q}\right)$$

*If $Q > 31$ due to corruption, the decoder defaults safely to $Q = 15$.*

### 4.3 Why the TLV Header Lacks Element-Size Metadata (The Stride Ambiguity)
A critical architectural constraint of the TI mmWave TLV framing protocol is that **the TLV header only specifies the aggregate byte length of the payload, never the size of an individual element**:

```
┌─────────────────────────┬─────────────────────────┐
│     TLV Type (uint32)   │    TLV Length (uint32)  │  <-- 8-Byte TLV Header
├─────────────────────────┴─────────────────────────┤
│ numObjects (uint16)     │   xyzQFormat (uint16)   │  <-- 4-Byte Descriptor
├───────────────────────────────────────────────────┤
│ Flat Byte Stream: Element[0], Element[1], ...     │  <-- Data Payload (N × Stride)
└───────────────────────────────────────────────────┘
```

1. **No Stride in Header:** The 8-byte TLV header only reports `length = 4 + (N × ElementSize)`. Neither the element size nor the C struct schema version is encoded in the stream.
2. **Untrusted / Stale `numObjects`:** In many MRR / Custom MRR builds, the firmware sets the 4-byte descriptor's `numObjects` field statically or copies the tracking capacity rather than the active count.
3. **Hardcoded C Struct Presumption:** TI's reference protocol was designed under the assumption that the DSP firmware and the host receiver share identical, hardcoded C `struct` headers compiled at the same time. When firmware developers update the C struct (e.g., expanding `mrrTrackObj` from 12 bytes to 14 bytes or 20 bytes to add `tid`, `aux`, and `status`), the byte length increases, but the client receiver has no explicit schema tag to know the stride.
4. **Resulting Stride Shift:** If the client parser assumes a 12-byte stride when the radar is transmitting 20-byte structs, the parser drifts forward by $+8$ bytes on every successive track. By Track 3 or 4, the parser reads tracker status flags (`0x0001`, `0x0000`) as $(X, Y)$ coordinates, producing $(0, 0)$ phantom targets with extreme velocity spikes.
5. **Mitigation:** The receiver client MUST implement **adaptive stride detection** (factoring the payload size against known struct sizes $\{20, 14, 12\}$) rather than hardcoding a single element size.

---

## 5. Detailed TLV Payload Specifications

### 5.1 TLV Type 1: Detected Points (Point Cloud)
* **TLV Type ID:** `1` (`MMWDEMO_OUTPUT_MSG_DETECTED_POINTS`)
* **Element Size:** `10 bytes` per point
* **Payload Size:** $4 + (N \times 10)$ bytes

#### Element Struct (10 Bytes)
| Byte Offset | Field | Type | Scaling / Unit | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | **speed** | `int16` | $\text{raw} / 2^Q$ [m/s] | Doppler radial velocity (negative = approaching, positive = receding) |
| `2` | **peakVal** | `uint16` | Metric $[0, 65535]$ | Reflected signal peak magnitude |
| `4` | **x** | `int16` | $\text{raw} / 2^Q$ [m] | Lateral Cartesian position (positive = right, negative = left) |
| `6` | **y** | `int16` | $\text{raw} / 2^Q$ [m] | Longitudinal distance forward from radar sensor |
| `8` | **z** | `int16` | $\text{raw} / 2^Q$ [m] | Vertical elevation (positive = upward) |

#### Signal-to-Noise Ratio (SNR) Formula
To convert `peakVal` into physical decibels (dB):
$$\text{SNR (dB)} = \left(\frac{\text{peakVal}}{512.0}\right) \times 6.0206\text{ dB}$$

---

### 5.2 TLV Type 2: Target Clusters (Pre-Track Groups)
* **TLV Type ID:** `2` (`MMWDEMO_OUTPUT_MSG_CLUSTERS`)
* **Element Size:** `10 bytes` (Custom MRR with `cid`) or `8 bytes` (Standard MRR)
* **Payload Size:** $4 + (N \times \text{Stride})$ bytes
* **Subframe:** Emitted during Subframe 1 (USRR) and dynamically on Subframe 0 cluster transitions.

#### Element Structs:
1. **10-Byte Format (Custom MRR with Velocity & ID):**
   | Byte Offset | Field | Type | Scaling / Unit | Description |
   | :--- | :--- | :--- | :--- | :--- |
   | `0` | **x** | `int16` | $\text{raw} / 2^Q$ [m] | Cluster centroid lateral position |
   | `2` | **y** | `int16` | $\text{raw} / 2^Q$ [m] | Cluster centroid longitudinal distance |
   | `4` | **vx** | `int16` | $\text{raw} / 2^Q$ [m/s] | Cluster lateral velocity |
   | `6` | **vy** | `int16` | $\text{raw} / 2^Q$ [m/s] | Cluster longitudinal velocity |
   | `8` | **cid** | `uint16` | Discrete ID | Unique cluster ID assigned by DBSCAN |

2. **8-Byte Format (Standard MRR Dimensions):**
   | Byte Offset | Field | Type | Scaling / Unit | Description |
   | :--- | :--- | :--- | :--- | :--- |
   | `0` | **xCenter** | `int16` | $\text{raw} / 2^Q$ [m] | Cluster centroid lateral position |
   | `2` | **yCenter** | `int16` | $\text{raw} / 2^Q$ [m] | Cluster centroid longitudinal distance |
   | `4` | **xSize** | `int16` | $\text{raw} / 2^Q$ [m] | Cluster bounding box width |
   | `6` | **ySize** | `int16` | $\text{raw} / 2^Q$ [m] | Cluster bounding box depth |

---

### 5.3 TLV Type 3: Tracked Objects (EKF / Kalman Tracker Table)
* **TLV Type ID:** `3` (`MMWDEMO_OUTPUT_MSG_TRACKS`)
* **Subframe:** Subframe 0 (MRR)
* **Adaptive Stride Resolution:**
  The decoder adaptively determines the element stride per frame:
  $$\text{Stride} = \begin{cases}
  \frac{\text{PayloadSize}}{\text{numTracks}} & \text{if } \text{PayloadSize} \pmod{\text{numTracks}} = 0 \text{ and } \frac{\text{PayloadSize}}{\text{numTracks}} \in \{12, 14, 20\} \\
  20 & \text{else if } \text{PayloadSize} \pmod{20} = 0 \\
  14 & \text{else if } \text{PayloadSize} \pmod{14} = 0 \\
  12 & \text{otherwise (legacy fallback)}
  \end{cases}$$

#### 1. Current Vehicle Firmware Struct (20 Bytes - Full Custom MRR with Status & TID)
Empirically verified across 100% of packets in road-test sessions (`session_20260910_093816/`):

| Byte Offset | Field | Type | Scaling / Unit | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | **`x`** | `int16` | $\text{raw} / 2^Q$ [m] | Track lateral coordinate (positive = right, negative = left) |
| `2` | **`y`** | `int16` | $\text{raw} / 2^Q$ [m] | Track longitudinal distance ahead (meters) |
| `4` | **`vx`** | `int16` | $\text{raw} / 2^Q$ [m/s] | Track lateral velocity |
| `6` | **`vy`** | `int16` | $\text{raw} / 2^Q$ [m/s] | Track longitudinal velocity (negative = approaching ego vehicle) |
| `8` | **`xSize`** | `int16` | $\text{raw} / 2^Q$ [m] | Estimated target width spread |
| `10` | **`ySize`** | `int16` | $\text{raw} / 2^Q$ [m] | Estimated target length spread |
| `12` | **`aux / accX`** | `int16` | $\text{raw} / 2^Q$ | Target acceleration or auxiliary motion parameter |
| `14` | **`tid`** | `uint16` | Discrete Integer | **Native Hardware Global Track ID** (e.g. #3168, #3181) |
| `16..19` | **`status`** | `uint32` | Discrete State | **EKF Tracker State Machine**:<br>• `0` = Free / Unallocated<br>• `1` = Initializing / Tentative<br>• `3` = Active / Converged Target<br>• `4` = Coasting / Occluded Target |

#### 2. Intermediate Specification Struct (14 Bytes - Custom MRR with TID)
Defined in reference commit `645d5eb9c` / `7ff63cd`:

| Byte Offset | Field | Type | Scaling / Unit | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | **`x`** | `int16` | $\text{raw} / 2^Q$ [m] | Track lateral coordinate |
| `2` | **`y`** | `int16` | $\text{raw} / 2^Q$ [m] | Track longitudinal distance |
| `4` | **`vx`** | `int16` | $\text{raw} / 2^Q$ [m/s] | Track lateral velocity |
| `6` | **`vy`** | `int16` | $\text{raw} / 2^Q$ [m/s] | Track longitudinal velocity |
| `8` | **`xSize`** | `int16` | $\text{raw} / 2^Q$ [m] | Estimated target width |
| `10` | **`ySize`** | `int16` | $\text{raw} / 2^Q$ [m] | Estimated target length |
| `12` | **`tid`** | `uint16` | Discrete Integer | Hardware Global Track ID |

#### 3. Legacy Struct (12 Bytes - Standard MRR Demo)
| Byte Offset | Field | Type | Scaling / Unit | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0` | **`x`** | `int16` | $\text{raw} / 2^Q$ [m] | Track lateral coordinate |
| `2` | **`y`** | `int16` | $\text{raw} / 2^Q$ [m] | Track longitudinal distance |
| `4` | **`vx`** | `int16` | $\text{raw} / 2^Q$ [m/s] | Track lateral velocity |
| `6` | **`vy`** | `int16` | $\text{raw} / 2^Q$ [m/s] | Track longitudinal velocity |
| `8` | **`xSize`** | `int16` | $\text{raw} / 2^Q$ [m] | Estimated target width |
| `10` | **`ySize`** | `int16` | $\text{raw} / 2^Q$ [m] | Estimated target length |
| *Note* | `tid` is synthesized as `i + 1` | | | Missing hardware ID |

#### Inactive Tracker Slot Filtering Rule
To prevent ghost / unallocated targets from displaying:
$$\text{Active Target} \iff (\text{status} \neq 0) \land \neg(x = 0 \land y = 0 \land vx = 0 \land vy = 0)$$

---

### 5.4 TLV Type 4: Parking Assist
* **TLV Type ID:** `4` (`MMWDEMO_OUTPUT_MSG_PARKING_ASSIST`)
* **Element Size:** `2 bytes` (`uint16`) per angular bin
* **Payload Size:** $4 + (32 \times 2) = 68$ bytes
* **Values:** Range in meters ($d = \text{raw} / 2^Q$) across 32 radial azimuth sectors.

---

## 6. Android Project Codebase Map

The following files in `com.bajajauto.roadsense` implement the acquisition, framing, decoding, and visualization:

```
app/src/main/java/com/bajajauto/roadsense/
├── models/
│   ├── RadarHeader.kt          <-- 40-byte header struct & RawRadarPacket
│   └── RadarFrame.kt           <-- Domain models: RadarPoint, RadarTrack, RadarCluster, RadarFrame
├── decoding/
│   ├── RadarPacketAssembler.kt <-- Magic word search, buffer boundary handling, full-packet assembly
│   └── RadarTlvDecoder.kt      <-- TLV parser, Q-format scaling, inactive slot filtering
├── recording/
│   ├── RadarSessionRecorder.kt <-- High-throughput binary logging (radar_frames.bin with 24B ROAD header)
│   ├── SessionManager.kt       <-- Session folders (session_YYYYMMDD_HHMMSS/) & session_metadata.json
│   └── SessionRecordingState.kt<-- Lifecycle state flow
└── ui/
    ├── MainActivity.kt         <-- GUI controls, recording card, telemetry cards
    ├── RadarViewModel.kt       <-- Background data dispatch, flow emission
    └── components/
        └── RadarBevPlot.kt     <-- Real-time 2D Cartesian BEV canvas, FOV cone, Doppler coloring

tools/
├── inspect_session.py          <-- Python tool to parse session_metadata.json & unpack radar_frames.bin
└── inspect_raw_uart.py         <-- Python tool to inspect raw UART streams (.bin)
```

---

## 7. How to Modify or Extend the TLV Pipeline

If a future firmware update changes an existing TLV or adds a new TLV (e.g., TLV Type 7 Side Info, or a new 3D tracking struct):

### Step 1: Update Domain Models
In [`RadarFrame.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/models/RadarFrame.kt):
- Add or modify the data class fields (e.g. adding `rcs` or covariance matrices).
- Ensure default parameter values are provided to maintain backwards compatibility.

### Step 2: Update the TLV Decoder
In [`RadarTlvDecoder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt):
- Add the TLV constant in the `companion object`:
  ```kotlin
  const val TLV_NEW_TYPE = 1010
  ```
- Add the branch in `decode(packet: RawRadarPacket)`:
  ```kotlin
  when (tlvType) {
      TLV_NEW_TYPE -> parseNewType(valueBuffer, tlvLength, outputList)
      ...
  }
  ```
- Implement the parsing method:
  - Read `numObjects` and `xyzQFormat` from the 4-byte descriptor.
  - Calculate `invQ = 1.0f / (1 shl q).toFloat()`.
  - Slice elements from the `ByteBuffer`.

### Step 3: Add an Offline JVM Unit Test
In [`RadarTlvDecoderTest.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/decoding/RadarTlvDecoderTest.kt):
- Provide a base64-encoded payload from hardware.
- Assert expected metric output coordinates and field counts.
- Run tests via: `.\gradlew.bat testDebugUnitTest`.

### Step 4: Update the Visualization & Inspection Scripts
- In [`RadarBevPlot.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/ui/components/RadarBevPlot.kt): Update canvas drawing or the target diagnostic HUD.
- In [`tools/inspect_session.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/inspect_session.py): Add the new TLV type to `tlv_names` dictionary.

---

## 8. Cross-Reference Table with Python Reference Implementation

| Concept | Python File (`CANenbl_unifiedRadarTracker`) | Android Kotlin File (`RoadSense`) |
| :--- | :--- | :--- |
| Magic Word & Header | `read_and_parse_frame.py` (Lines 45–60) | `RadarHeader.kt` (Lines 6–23) |
| Packet Boundary Buffering | `hw_comms_utils.py` | `RadarPacketAssembler.kt` (Lines 25–123) |
| Point Cloud Parsing (TLV 1) | `read_and_parse_frame.py` (Lines 921–965) | `RadarTlvDecoder.kt` (Lines 100–130) |
| Cluster Parsing (TLV 2) | `read_and_parse_frame.py` (Lines 967–1005) | `RadarTlvDecoder.kt` (Lines 135–170) |
| Track Parsing (TLV 3) | `read_and_parse_frame.py` (Lines 1007–1055)| `RadarTlvDecoder.kt` (Lines 175–230) |
| Parking Assist (TLV 4) | `read_and_parse_frame.py` (Lines 1056–1078)| `RadarTlvDecoder.kt` (`TLV_PARKING_ASSIST`) |
