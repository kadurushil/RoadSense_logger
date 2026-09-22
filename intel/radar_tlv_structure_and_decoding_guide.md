# TI AWR1843 Radar TLV Structure & Decoding Guide

> **Target Audience:** Engineers and AI coding agents maintaining or extending the radar acquisition, decoding, logging, and visualization pipelines in **RoadSense**.  
> **Last Updated:** September 22, 2026  
> **Reference Specification:** `D:\Gitea\CAN_unified_radar_tracker_py` (`TLV_info/1843_customMRR_UART_TLV_SPEC.md` & `src/radar_tracker/hardware/read_and_parse_frame.py`)  

---

## 1. Executive Summary & Physical Layer

RoadSense interfaces with the Texas Instruments **AWR1843BOOST** automotive mmWave radar sensor running **Custom MRR (Medium Range Radar) v2.1 / v2.2** firmware.

### Hardware & UART Parameters
* **Radar Model:** TI AWR1843BOOST (Single-chip 76–81 GHz FMCW transceiver + ARM Cortex-R4F MSS + TI C674x DSP)
* **Firmware Profile:** Custom MRR ADAS firmware
* **CLI Port:** Port 0 (Standard baud: `115,200`, 8-N-1) — Chirp configuration and sensor start/stop commands
* **Data Stream Port:** Port 1 (High-speed baud: **`3,125,000` baud (3.125 Mbps)**, 8-N-1) — DMA-accelerated binary telemetry stream
* **Packet Alignment:** Padded with trailing `0x0F` bytes to a **32-byte boundary**
* **Byte Endianness:** Little-Endian (`<`) for all payload values; Network Big-Endian (`>`) used exclusively for RoadSense session storage framing tags (`ROAD` header in `radar_frames.bin`).

---

## 2. Binary Packet Architecture & Framing

The radar streams discrete binary packets over UART at **20 Hz** (50.0 ms period). Each packet begins with a fixed 40-byte header followed by $N$ sequential TLV blocks, aligned to a 32-byte boundary:

```text
+-------------------------------------------------------------------------------+
|  40-Byte Master Frame Header (Starts with 8-byte Magic Word)                  |
+-------------------------------------------------------------------------------+
|  TLV 1: Type (4B) | Length (4B) | Descriptor (4B) | Points Array (N * 12B)    |
+-------------------------------------------------------------------------------+
|  TLV 2: Type (4B) | Length (4B) | Descriptor (4B) | Clusters Array (N * 16B)  |
+-------------------------------------------------------------------------------+
|  TLV 3: Type (4B) | Length (4B) | Descriptor (4B) | Tracks Array (N * 28B)    |
+-------------------------------------------------------------------------------+
|  TLV 4: Type (4B) | Length (4B) | Diagnostics Payload (48B)                   |
+-------------------------------------------------------------------------------+
|  TLV 5: Type (4B) | Length (4B) | Vehicle CAN Inputs Payload (56B)            |
+-------------------------------------------------------------------------------+
|  TLV 6: Type (4B) | Length (4B) | Vehicle Safety ADAS Outputs (24B)           |
+-------------------------------------------------------------------------------+
|  Trailing Padding (0 to 31 bytes of 0x0F to reach 32-byte alignment boundary) |
+-------------------------------------------------------------------------------+
```

### 2.1 Synchronization Magic Word (8 Bytes)
Frame synchronization scans for the 8-byte magic word sequence:
```text
Hex Bytes:   02 01 04 03 06 05 08 07
Uint16 (LE): {0x0102, 0x0304, 0x0506, 0x0708}
Uint64 (LE): 0x0708050603040102
```

### 2.2 40-Byte Master Frame Header Specification (`<4H6I`)
Directly following the magic word are 8 fields forming the 40-byte header:

| Byte Offset | Field Name | Type | Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0 - 7` | **`magicWord`** | `uint16[4]` | 8 B | Sync pattern: `{0x0102, 0x0304, 0x0506, 0x0708}` |
| `8 - 11` | **`version`** | `uint32` | 4 B | Radar firmware SDK version identifier |
| `12 - 15` | **`totalPacketLen`** | `uint32` | 4 B | Total packet size in bytes (Header + TLVs + 32-byte padding) |
| `16 - 19` | **`platform`** | `uint32` | 4 B | Hardware platform ID (`0xA1843` for AWR1843) |
| `20 - 23` | **`frameNumber`** | `uint32` | 4 B | Monotonically increasing sequential frame counter |
| `24 - 27` | **`timeCpuCycles`** | `uint32` | 4 B | DSP clock cycle timestamp (approx. 1.66 ns per cycle @ 600 MHz) |
| `28 - 31` | **`numDetectedObj`** | `uint32` | 4 B | Total detected point reflections in point cloud TLV |
| `32 - 35` | **`numTLVs`** | `uint32` | 4 B | Number of active TLVs in this packet (typically $1 \dots 6$) |
| `36 - 39` | **`subframeNumber`** | `uint32` | 4 B | Subframe index (0 = MRR Long/Medium Range, 1 = USRR Ultra Short Range) |

---

## 3. Generic TLV Header & Q-Format Scaling

Each TLV is prefixed by an 8-byte header:
* **`type`** (`uint32`, 4 bytes): TLV Type Identifier ($1 \dots 6$)
* **`length`** (`uint32`, 4 bytes): Byte length of value payload (includes the 4-byte descriptor if present)

### 3.1 The 4-Byte Object Descriptor (`<2H`)
Present in primary array TLVs (Types 1, 2, 3):
* **`numObjects`** (`uint16`, 2 bytes): Number of elements $N$ in the array.
* **`xyzQFormat`** (`uint16`, 2 bytes): Fixed-point fractional scaling exponent $Q$ ($0 \le Q \le 31$).

### 3.2 Q-Format Metric Scaling Formula
The radar DSP outputs spatial and velocity coordinates as signed 16-bit integers (`int16`). To convert to real-world floating-point meters and meters/second:

$$\text{Value} = \frac{\text{raw\_int16}}{2^Q} = \text{raw\_int16} \times \left(\frac{1}{2^Q}\right)$$

*If $Q > 31$ due to buffer corruption, the decoder safely caps $Q = 15$.*

### 3.3 Adaptive Stride Resolution
To maintain 100% backward compatibility across firmware releases without brittle hardcoding, decoders determine element stride per frame:

$$\text{actualStride} = \frac{\text{tlvLength} - 4}{\text{numObjects}}$$

---

## 4. Multi-TLV v2.1 / v2.2 Detailed Specifications

### 4.1 TLV Type 1: Extended Point Cloud (`type = 1`)
* **Descriptor (4 Bytes):** `<2H` (`numPoints, xyzQFormat`)
* **Production Format (12 Bytes per point):** `<hHhhhBB`
  * `speed` (`int16`): Radial Doppler velocity [m/s] ($V_r = \text{raw} / 2^Q$; negative = approaching, positive = opening)
  * `peakVal` (`uint16`): Peak reflection magnitude ($\text{SNR (dB)} = \frac{\text{peakVal}}{512.0} \times 6.0206\,\text{dB}$)
  * `x, y, z` (`int16[3]`): 3D Cartesian coordinates [m] ($\text{raw} / 2^Q$)
  * `clusterID` (`uint8`): Associated DBSCAN cluster ID ($0 = \text{noise}$)
  * `isOutlier` (`uint8`): RANSAC dynamic motion classifier ($1 = \text{moving/outlier}, 0 = \text{static/inlier}$)
* *Legacy Fallback (10 Bytes):* `<hH3h` (`speed, peakVal, x, y, z`).

---

### 4.2 TLV Type 2: Extended DBSCAN Clusters (`type = 2`)
* **Descriptor (4 Bytes):** `<2H` (`numClusters, xyzQFormat`)
* **Production Format (16 Bytes per cluster):** `<hhhhHHBBBB`
  * `xCenter, yCenter` (`int16[2]`): Centroid Cartesian position [m] ($\text{raw} / 2^Q$)
  * `xSize, ySize` (`int16[2]`): Cluster physical bounding spread / velocity dispersion [m] ($\text{raw} / 2^Q$)
  * `clusterID` (`uint16`): Cluster Identifier
  * `numPoints` (`uint16`): Count of constituent reflections in this cluster
  * `isOutlier` (`uint8`): Dynamic motion flag
  * `isStationary` (`uint8`): In-corridor ground stationary flag
  * `isDeadZone` (`uint8`): Near-field blind-spot / bumper proximity flag
  * `reserved` (`uint8`): Word alignment padding
* *Legacy Fallbacks:*
  * 10 Bytes `<4h2B`: `xCenter, yCenter, xSize, ySize, clusterID (u1), padding (u1)`
  * 8 Bytes `<4h`: `xCenter, yCenter, xSize, ySize`

---

### 4.3 TLV Type 3: Extended Tracked Objects (`type = 3`)
* **Descriptor (4 Bytes):** `<2H` (`numTracks, xyzQFormat`)
* **Production Format (28 Bytes per track - v2.2):** `<hhhhhhhHHHhBBBBH`
  * `x, y` (`int16[2]`): Tracked Cartesian position [m] ($\text{raw} / 2^Q$)
  * `vx, vy` (`int16[2]`): Tracked velocity [m/s] ($\text{raw} / 2^Q$)
  * `majorSize, minorSize` (`int16[2]`): Oriented bounding box major/minor extents [m] ($\text{raw} / 2^Q$)
  * `orientation` (`int16`): Bounding box heading angle in tenths of a degree ($\text{raw} \times 0.1^\circ \in [-180.0^\circ, +180.0^\circ]$)
  * `tid` (`uint16`): Unique Track Identifier ($0 \dots 65535$)
  * `state` (`uint16`): EKF Lifecycle state enum:
    * `0` = Free / Unallocated
    * `1` = Tentative / Initializing
    * `3` = Confirmed / Active
    * `4` = Coasted / Occluded
    * `5` = Dead
  * `clusterID` (`uint16`): Associated parent cluster ID
  * `tti` (`int16`): Time-to-Interception in hundredths of a second ($\text{raw} \times 0.01\,\text{s}$; $-1$ if none)
  * `risk` (`uint8`): Collision risk level (`0: Safe`, `1: Warning`, `2: Critical`)
  * `isStationary` (`uint8`): Stationary flag (`1 = static`, `0 = moving`)
  * `ttcCategory` (`uint8`): Time-to-Collision severity category (`0: Safe`, `1: Warning`, `2: Critical`)
  * `confidence` (`uint8`): Filter estimation confidence ($0 \dots 100\%$)
  * `reserved` (`uint16`): Word alignment padding
* *Fallback Formats:*
  * **20 Bytes (`<7h3H`):** `x, y, vx, vy, majorSize, minorSize, orientation, tid, state, reserved`
  * **14 Bytes (`<6hH`):** `x, y, vx, vy, xSize, ySize, tid`
  * **12 Bytes (`<6h`):** `x, y, vx, vy, xSize, ySize`

#### Inactive Track Slot Filtering Rule
To prevent phantom/unallocated tracks from displaying on the cockpit canvas:
$$\text{Active Track} \iff (\text{state} \neq 0) \land \neg(x = 0 \land y = 0 \land vx = 0 \land vy = 0)$$

---

### 4.4 TLV Type 4: Tracker Diagnostics (`type = 4`, 48 Bytes)
Fixed **48-byte payload** unpacked via `<HBb10fHBB`:

| Offset | Field Name | Type | Size | Description |
| :---: | :--- | :---: | :---: | :--- |
| `0 - 1` | **`numInliers`** | `uint16` | 2 B | Count of stationary ground reflection inliers |
| `2` | **`ransacSuccessful`** | `uint8` | 1 B | RANSAC ego-motion convergence flag ($1 = \text{converged}$) |
| `3` | **`motionState`** | `int8` | 1 B | Dynamic motion state ($-2 = \text{Left Peak}, -1 = \text{Left}, 0 = \text{Straight}, +1 = \text{Right}, +2 = \text{Right Peak}$) |
| `4 - 7` | **`filteredVx_iir`** | `float32` | 4 B | IIR filtered lateral velocity $V_x$ (m/s) |
| `8 - 11` | **`filteredVy_iir`** | `float32` | 4 B | IIR filtered longitudinal velocity $V_y$ (m/s) |
| `12 - 15` | **`egoEkf_Vy`** | `float32` | 4 B | Ego EKF estimated forward speed (m/s) |
| `16 - 19` | **`egoEkf_Vx`** | `float32` | 4 B | Ego EKF estimated lateral speed (m/s) |
| `20 - 23` | **`egoEkf_Ax`** | `float32` | 4 B | Ego EKF estimated lateral acceleration (m/s²) |
| `24 - 27` | **`egoEkf_Ay`** | `float32` | 4 B | Ego EKF estimated longitudinal acceleration (m/s²) |
| `28 - 31` | **`egoEkf_YawRate`**| `float32` | 4 B | Ego EKF estimated yaw rate (rad/s) |
| `32 - 35` | **`roadBoundaryLeftX`** | `float32` | 4 B | Lateral position of detected left road barrier (m) |
| `36 - 39` | **`roadBoundaryRightX`**| `float32` | 4 B | Lateral position of detected right road barrier (m) |
| `40 - 43` | **`ax_dynamics`** | `float32` | 4 B | Powertrain tractive dynamic acceleration input (m/s²) |
| `44 - 45` | **`trackerProcTimeUs`** | `uint16` | 2 B | Tracker execution latency (µs) |
| `46` | **`imuStuckFlag`** | `uint8` | 1 B | Watchdog flag for frozen/stuck IMU sensor |
| `47` | *Padding* | `uint8` | 1 B | Struct alignment byte |

*(Note: In legacy standard MRR firmware, TLV 4 was 68 bytes containing a 32-bin Parking Assist distance array).*

---

### 4.5 TLV Type 5: Vehicle CAN Inputs (`type = 5`, 56 Bytes)
Fixed **56-byte payload** unpacked via `<fffffffffffIBBbBB3s`:

| Offset | Field Name | Type | Size | Description |
| :---: | :--- | :---: | :---: | :--- |
| `0 - 3` | **`speed_kmph`** | `float32` | 4 B | Native signed vehicle speed (km/h; negative in reverse) |
| `4 - 7` | **`yaw_rate_radps`** | `float32` | 4 B | Gyroscope yaw rate (rad/s) |
| `8 - 11` | **`pitch_rate_radps`**| `float32` | 4 B | Gyroscope pitch rate (rad/s) |
| `12 - 15`| **`roll_rate_radps`** | `float32` | 4 B | Gyroscope roll rate (rad/s) |
| `16 - 19`| **`accel_x_mps2`** | `float32` | 4 B | Longitudinal IMU acceleration $a_x$ (m/s²) |
| `20 - 23`| **`accel_y_mps2`** | `float32` | 4 B | Lateral IMU acceleration $a_y$ (m/s²) |
| `24 - 27`| **`accel_avg_mps2`**| `float32` | 4 B | Averaged chassis dynamic acceleration (m/s²) |
| `28 - 31`| **`road_grade_deg`** | `float32` | 4 B | Road grade inclination (degrees) |
| `32 - 35`| **`motor_torque_nm`**| `float32` | 4 B | Electric motor shaft torque (Nm) |
| `36 - 39`| **`roll_cf_deg`** | `float32` | 4 B | Complementary filtered roll angle (degrees) |
| `40 - 43`| **`yaw_cf_deg`** | `float32` | 4 B | Complementary filtered yaw angle (degrees) |
| `44 - 47`| **`timestamp_ms`** | `uint32` | 4 B | Synchronous VCU CAN timestamp (ms) |
| `48` | **`gear`** | `int8` | 1 B | Transmission gear ($0 = \text{Neutral}, 1 = \text{Drive}, 2 = \text{Park}$) |
| `49` | **`brake_status`** | `uint8` | 1 B | Brake pedal activation ($0 = \text{Off}, 1 = \text{Active}$) |
| `50` | **`motion_state`** | `int8` | 1 B | VCU motion classifier |
| `51` | **`is_vcu_can_valid`**| `uint8` | 1 B | Watchdog signal ($1 = \text{valid VCU data}$) |
| `52` | **`imu_stuck_flag`** | `uint8` | 1 B | Watchdog flag for frozen vehicle IMU |
| `53 - 55`| *Reserved* | `uint8[3]` | 3 B | 32-bit word alignment padding |

---

### 4.6 TLV Type 6: Vehicle Safety ADAS Outputs (`type = 6`, 24 Bytes)
Fixed **24-byte payload** containing 3 contiguous 8-byte bit-exact CAN messages:

#### 1. Bytes 0–7: Forward Collision Warning (FCW - CAN ID `0x320`)
* Byte 0: Bits 0–1 = `FCW_Stage_St_enum` (`0: None`, `1: Visual`, `2: Audible`), Bits 2–7 + Byte 1 = `FCW_TrackID_Act_ID`
* Byte 2: `FCW_TTC_Act_sec` (scale: 0.1 s)
* Byte 3: `FCW_TargetY_Act_m` (scale: 0.5 m)
* Byte 4: `FCW_TargetX_Act_m` (scale: 0.2 m, offset: -25.6 m)
* Byte 5: `FCW_TargetVy_mps` (scale: 0.5 m/s, offset: -64.0 m/s)
* Byte 6: `FCW_TargetVx_mps` (scale: 0.2 m/s, offset: -25.6 m/s)

#### 2. Bytes 8–15: Blind Spot Detection (BSD - CAN ID `0x328` / `0x321`)
* Byte 8: `RADAR_BSD_Left_Active_St_B` ($0 = \text{Inactive}, 1 = \text{Active}$)
* Byte 9: `RADAR_BSD_Right_Active_St_B` ($0 = \text{Inactive}, 1 = \text{Active}$)
* Byte 10: `LCA_Warning_Level` ($0 = \text{None}, 1 = \text{Amber}, 2 = \text{Red}$)
* Byte 11: `Approach_TTC` (scale: 0.1 s)

#### 3. Bytes 16–23: Adaptive Cruise Control (ACC - CAN ID `0x327`)
* Bytes 16–17: `ACC_POI_ID` (16-bit target Track ID)
* Byte 18: `ACC_TargetY_Act_m` (scale: 0.5 m)
* Byte 19: `ACC_TargetX_Act_m` (scale: 0.2 m, offset: -25.6 m)
* Byte 20: `ACC_TargetVy_mps` (scale: 0.5 m/s, offset: -64.0 m/s)
* Byte 21: `ACC_TTI_Act_sec` (scale: 0.1 s)
* Byte 22: `ACC_VSafe_mps` (scale: 0.1 m/s)
* Byte 23: `ACC_ARef_mps2` (scale: 0.02 m/s², offset: -2.56 m/s²)

---

## 5. Summary Mapping: RoadSense Architecture

| Subsystem | Android Class (`RoadSense`) | Python Class (`CAN_unified_radar_tracker_py`) |
| :--- | :--- | :--- |
| **Packet Assembly** | `RadarPacketAssembler.kt` | `hw_comms_utils.py` |
| **Header Definition** | `RadarHeader.kt` (`<4H6I`) | `FRAME_HEADER_STRUCT_MRR` |
| **Point Cloud (TLV 1)** | `RadarPoint.kt` + `RadarTlvDecoder.kt` | `parse_custom_mrr_point_cloud_tlv` |
| **Clusters (TLV 2)** | `RadarCluster.kt` + `RadarTlvDecoder.kt` | `parse_custom_mrr_clusters_tlv` |
| **Tracks (TLV 3)** | `RadarTrack.kt` + `RadarTlvDecoder.kt` | `parse_custom_mrr_tracks_tlv` |
| **Diagnostics (TLV 4)** | `RadarTrackerDiagnostics` (New) | `parse_custom_mrr_tracker_diags_tlv` |
| **CAN Inputs (TLV 5)** | `RadarCanInputs` (New) | `parse_custom_mrr_can_inputs_tlv` |
| **CAN Outputs (TLV 6)** | `RadarCanOutputs` (New) | `parse_custom_mrr_can_outputs_tlv` |
| **Binary Logger** | `RadarSessionRecorder.kt` (`ROAD` 24B header) | `raw_uart_*.bin` parser |
