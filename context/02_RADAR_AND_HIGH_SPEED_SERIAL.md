# RoadSense: Radar Subsystem & High-Speed UART Pipeline

> **Document Name:** `02_RADAR_AND_HIGH_SPEED_SERIAL.md`  
> **Location:** `context/02_RADAR_AND_HIGH_SPEED_SERIAL.md`  
> **Audience:** Autonomous AI Coding Agents & Embedded Firmware Engineers  

---

## 1. Hardware Overview & USB Architecture

RoadSense interfaces with the **Texas Instruments AWR1843BOOST** automotive mmWave radar (77–81 GHz FMCW transceiver with hardware DSP and ARM Cortex-R4F MCU).

### Dual-Port Virtual COM Ports:
The onboard FTDI/XDS110 debug interface enumerates two distinct USB CDC serial endpoints:
1. **CLI / Command Port:**
   * **Baud Rate:** `115,200 baud` (8-N-1)
   * **Function:** Transmits chirp configurations, frame rates, and profile commands (e.g. `sensorStart`, `guiMonitor`, `cfarCfg`, `trackingCfg`).
2. **Data Streaming Port:**
   * **Baud Rate:** **`3,125,000 baud` (3.125 Mbps)**
   * **Function:** High-throughput streaming of raw binary TLV (Type-Length-Value) detection matrices, 3D point clouds, and target tracking vectors at 20 Hz (50 ms frame period).

---

## 2. High-Speed Serial Pipeline (3.125 Mbps)

Standard Android serial implementations drop packets or corrupt buffers above 921,600 baud. RoadSense overcomes this via optimized low-latency driver tuning in `RadarSerialService.kt`:

### Driver Tuning & Zero-Copy Circular Buffering:
* **Underlying Library:** `usb-serial-for-android` (custom FTDI / CDC ACM driver classes).
* **Buffer Size:** Dedicated 64 KB kernel direct `ByteBuffer` ring buffer.
* **Polling Loop:** Dedicated high-priority background worker thread (`Process.THREAD_PRIORITY_URGENT_AUDIO`) bypassing the Android Looper/Handler to guarantee zero dropped bytes under heavy CPU loads.
* **Flow Control:** Hardware RTS/CTS flow control enabled where supported by the hardware cable.

---

## 3. Binary Packet Framing & TLV Structure

Every binary radar frame emitted by the TI firmware begins with a fixed 8-byte sync magic pattern followed by a 40-byte header:

### 3.1 TI mmWave Standard Magic Pattern (8 Bytes)
```hex
0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07
```

### 3.2 Frame Header Structure (40 Bytes)
| Offset | Type | Field Name | Description |
|---|---|---|---|
| `0..7` | `uint8[8]` | `magicWord` | Sync sequence (`0x0102, 0x0304, 0x0506, 0x0708`) |
| `8..11` | `uint32` | `version` | Firmware & SDK format version |
| `12..15` | `uint32` | `totalPacketLen` | Total byte length of entire packet including all TLVs |
| `16..19` | `uint32` | `platform` | Hardware platform ID (e.g. `0xA1843`) |
| `20..23` | `uint32` | `frameNumber` | Monotonically incrementing frame counter |
| `24..27` | `uint32` | `timeCpuCycles` | DSP timestamp in CPU cycles |
| `28..31` | `uint32` | `numDetectedObj` | Number of detected point targets |
| `32..35` | `uint32` | `numTLVs` | Number of TLV payloads following the header |
| `36..39` | `uint32` | `subFrameNumber` | Sub-frame index (for multi-profile configurations) |

### 3.3 Supported TLV Payloads (Type-Length-Value)
Following the header, `numTLVs` payloads are streamed sequentially. Each TLV begins with an 8-byte header:
* `uint32 type` (TLV Type ID)
* `uint32 length` (Length of value payload in bytes)

| TLV Type ID | Name | Payload Data Structure |
|---|---|---|
| **`1`** | **Detected Points (Cartesian)** | Point coordinates: `float32 x`, `float32 y`, `float32 z`, `float32 velocity (Doppler)`, `uint16 snr`, `uint16 noise` |
| **`2`** | **Range Profile** | 1D FFT array of range bin intensities |
| **`3`** | **Noise Profile** | Noise floor per range bin |
| **`6`** | **Stats Information** | Inter-frame processing time, transmit time, active margin |
| **`7`** | **Target List (Tracker Output)** | Hardware EKF tracking filter objects: `uint32 targetId`, `float32 posX`, `float32 posY`, `float32 posZ`, `float32 velX`, `float32 velY`, `float32 accX`, `float32 accY`, `float32[9] covariance` |
| **`8`** | **Target Index** | Associates point cloud detections with specific target IDs |

---

## 4. On-Disk Binary Storage Framing (`radar_frames.bin`)

When active recording is engaged, `RadarPacketParser.kt` writes every validated radar frame into `session_YYYYMMDD_HHMMSS/radar/radar_frames.bin` wrapped in RoadSense binary framing:

```text
[ROAD Header (24 Bytes)] + [TI Raw Packet (totalPacketLen Bytes)]
```

### ROAD Binary Header Format (24 Bytes Big-Endian):
```text
Offset 00..03: 'R', 'O', 'A', 'D' (Magic identifier)
Offset 04..11: uint64 elapsedRealtimeNs (Android monotonic realtime clock)
Offset 12..19: uint64 currentTimeMillis (Unix epoch wall-clock time)
Offset 20..23: uint32 packetLength (Byte length of raw TI packet following immediately)
```

This layout allows the PC visualizer pipeline (`tools/sync_and_process_sessions.py`) to stream and unpack frames without re-parsing raw UART stream fragments.
