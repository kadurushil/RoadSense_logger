# Python Reference Codebase Extraction & Porting Guide
**Project:** RoadSense / CANenbl_unifiedRadarTracker  
**Source Repository:** `D:\Work\Repo\CANenbl_unifiedRadarTracker`  
**Scratch Artifacts Target:** `C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense\.artifacts\0bb5770a-cccd-4626-bea3-23d93c90c899/scratch/python_reference/`

---

## 1. Overview of Reference Codebase Structure (`CANenbl_unifiedRadarTracker`)

The reference repository is a robust Python/PyQt5 application designed for multi-sensor data acquisition, radar point cloud parsing (TI AWR1843, MRR, BSD, xWRL1432), CAN bus communication (KVASER / SocketCAN via `python-can`), video recording (`ffmpeg`), and real-time tracking (JPDA, IMM, EKF, DBSCAN).

### Key Directory Layout:
- `main.py` & `config.py`: Application entry points, configuration parameters, thread orchestration.
- `src/radar_tracker/hardware/`:
  - `read_and_parse_frame.py`: Core UART packet framing, header parsing, and TLV dispatching.
  - `parsing_utils.py`: CLI `.cfg` file parser and derived parameter calculations (numLoops, numChirpsPerFrame, etc.).
  - `hw_comms_utils.py`: Serial port configuration, DTR/RTS management, and sliding-window sync pattern matching.
- `src/radar_tracker/camera/`:
  - `camera_recorder.py`: `ffmpeg`-based webcam stream recording (Linux V4L2 & Windows DirectShow).
  - `camera_utils.py`: Frame formatting and device discovery utilities.
- `src/can_logger_app/`:
  - `can_handler.py`: Background thread worker for CAN bus reading (`python-can`).
- `src/radar_tracker/tracking/utils/`:
  - `can_packer.py`: Packs ADAS state (ACC, AEB, BSD) into 8-byte CAN payloads (0x300, 0x301, 0x302).
- `src/radar_tracker/live_visualizer.py`: Real-time PyQt5 / pyqtgraph point cloud and tracking visualization.

---

## 2. Exact Packet Framing Rules, Header Format, and TLV Structure

The TI AWR1843 radar firmware communicates over UART (high-speed data port) using binary packets preceded by a magic sync word and structured into TLVs (Type-Length-Value).

### A. Frame Synchronization & Header Format
- **Sync Pattern (Magic Word):** 8 bytes: `\x02\x01\x04\x03\x06\x05\x08\x07` (`0x0102030405060708` uint64 in little-endian representation).
- **Frame Header Structure (Standard & MRR):** Total length: 36 bytes (`<QIIIIIIII`).
  1. `magicWord`: `uint64` (8 bytes)
  2. `version`: `uint32` (4 bytes)
  3. `totalPacketLen`: `uint32` (4 bytes) — Total packet length including header and padding.
  4. `platform`: `uint32` (4 bytes) — e.g., `0xA1843` for AWR1843.
  5. `frameNumber`: `uint32` (4 bytes)
  6. `timeCpuCycles`: `uint32` (4 bytes)
  7. `numDetectedObj`: `uint32` (4 bytes)
  8. `numTLVs`: `uint32` (4 bytes)
  9. `subFrameNumber`: `uint32` (4 bytes) — Used in MRR/USRR alternate subframes.

### B. TLV Header & Types
Each TLV is preceded by a 8-byte TLV header (`<II`):
- `type`: `uint32` (4 bytes)
- `length`: `uint32` (4 bytes) — Length of the TLV payload in bytes (excluding TLV header).

#### Major TLV Types:
- `301` (`MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS`): Point cloud Cartesian coordinates, Doppler, SNR, noise.
- `1` (`MMWDEMO_OUTPUT_MSG_DETECTED_POINTS`): Standard point cloud.
- `2` (`MMWDEMO_OUTPUT_MSG_CLUSTERS`): Hardware detected clusters.
- `3` (`MMWDEMO_OUTPUT_MSG_TRACKS`): Hardware tracked objects.
- `4` (`MMWDEMO_OUTPUT_MSG_PARKING_ASSIST`): Parking assist data.
- `6` / `306` (`MMWDEMO_OUTPUT_MSG_STATS`): Inter-frame processing time, CPU load, power, temperature.
- `7` (`MMWDEMO_OUTPUT_MSG_DETECTED_POINTS_SIDE_INFO`): Snr and noise side info.
- `8` (`MMWDEMO_OUTPUT_MSG_AZIMUT_ELEVATION_STATIC_HEAT_MAP`): Static heatmap.
- `1035` (`MMWDEMO_OUTPUT_EXT_MSG_TARGET_LIST_2D_BSD`): BSD target list.

### C. Alignment & Padding Rules
- Standard demos enforce 4-byte padding between TLVs.
- MRR, Custom MRR, and xWRL1432/BSD demos do **not** use per-TLV padding; instead, the entire packet is 32-byte aligned by the firmware, and TLVs are packed consecutively.

---

## 3. KVASER CAN Integration Details

- **Library:** `python-can` supporting KVASER (`canlib`).
- **Threading Model:** `CANReader` runs as a dedicated daemon thread (`threading.Thread`) to prevent UI blocking or dropouts.
- **Bus Parameters:** Configurable channel, bitrate, and interface type (e.g., `interface='kvaser'`, `channel=0`, `bitrate=500000`).
- **CAN Packer (`can_packer.py`):** Converts high-level ADAS states into three standardized 8-byte CAN frames:
  - `0x300` (50ms cycle): ACC Primary Target (ID, Distance, Relative Velocity, Relative Acceleration, Status).
  - `0x301` (20ms cycle): AEB & FCW Threats (TTI, Risk Level, FCW Stage, Brake Prefill, Confidence).
  - `0x302` (100ms cycle): BSD & Environment (BSD Left/Right, LCA Warning, Corridor Width, Sensor Blindness, Approach TTC).
  - *Note on Endianness:* While the specification is Motorola (Big-Endian), the reference code uses Little-Endian serialization (`<Q`) of 64-bit words to match MATLAB/firmware bit extraction parity.

---

## 4. Camera / Webcam Capture Flow

- **Tool:** `ffmpeg` subprocess wrapper (`CameraRecorder`).
- **Discovery:**
  - Linux: Scans `/sys/class/video4linux/video*/name` for device name filters (e.g., `"Brio 300"`).
  - Windows: Parses `ffmpeg -list_devices true -f dshow -i dummy` output for DirectShow video devices.
- **Capture & Encoding:**
  - Formats: YUYV422 / DirectShow input.
  - Encoder: `libx264` with preset `ultrafast` and `yuv420p` pixel format.
  - Output: Stamped `.mp4` video files (`cam_YYYYMMDD_HHMMSS.mp4`).

---

## 5. Point Cloud Visualization Approach

- **Framework:** PyQt5 + `pyqtgraph`.
- **UI Components:**
  - `PlotWidget`: 2D Cartesian plot (X: Lateral position in meters, Y: Longitudinal distance in meters).
  - Legend Panel: Color-coded indicators for MRR points (white), USRR points (yellow), Software Tracks (green), Hardware Clusters (cyan), and Hardware Tracks (magenta).
- **Rendering Performance:** Uses `pg.ScatterPlotItem` with high-performance vertex buffers for real-time 30-60 FPS point cloud rendering without garbage collection stalls.

---

## 6. Recommendations for Porting to Kotlin / Android Architecture

When porting this reference logic into our Kotlin/Android architecture (`RoadSense`), organize the modules as follows:

1. **`acquisition` (UART & CAN):**
   - Use `android.hardware.usb.UsbManager` and `UsbSerial` (e.g., `usb-serial-for-android`) for asynchronous USB-to-UART radar data streaming.
   - For CAN bus, integrate SocketCAN (`CanSocket`) or vendor-specific Android USB-CAN serial bridging APIs.

2. **`decoding` (Parsing & Framing):**
   - Port `read_and_parse_frame.py` and `parsing_utils.py` into Kotlin `ByteBuffer` parsing utilities.
   - Implement the sliding-window sync detector searching for `0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07`.
   - Parse packet headers and TLVs dynamically based on `numTLVs` and TLV type constants.

3. **`models` (Data Classes):**
   - Define Kotlin data classes for `FrameHeader`, `TlvHeader`, `PointCloudPoint`, `Target`, `Cluster`, and `SensorStats`.

4. **`recording` (Data & Video Sync):**
   - Use Jetpack CameraX for Android video recording, synchronized with incoming radar frame timestamps (`System.currentTimeMillis()` / `System.nanoTime()`).
   - Store synchronized logs in a structured local Room DB or JSON/Binary log files.

5. **`sync` (Timestamp Alignment):**
   - Align radar frames, CAN messages, and video frames using a shared monotonic clock timeline.

6. **`ui` (Jetpack Compose Visualization):**
   - Replace PyQt5/pyqtgraph with Jetpack Compose Canvas (`Canvas { drawCircle(...) }`) or Canvas graphics layers for high-performance 2D radar point cloud rendering, supporting zoom, pan, and custom coordinate mapping.
