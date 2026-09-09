# RoadSense

RoadSense is a synchronized road-data logging application intended for automotive ADAS development and validation.

## Objective
A robust data acquisition and logging application designed to record synchronized data for later offline analysis and replay.

**Primary Data Sources:**
1. Camera/Video (Hardware-accelerated)
2. GNSS/GPS
3. Texas Instruments AWR1843 radar data (via high-speed USB/UART)

## Architectural Layers
- **Acquisition:** Isolated hardware data collection (UART, Camera, Location).
- **Decoding:** Conversion of raw sensor data (e.g., UART bytes -> TI packets -> TLVs -> decoded objects).
- **Synchronization:** Timestamp-based association of different sensor streams (preserving sensor-native and host-monotonic timestamps).
- **Recording:** Robust binary logging of raw packets and metadata for long-duration sessions.
- **Visualization:** Live UI for observing system state without blocking acquisition or file I/O.

## Hardware Specifications
* **Radar:** TI AWR1843 BOOST
* **Connection:** USB/UART
* **Baud Rates:** 115200 (Config), 3125000 (Data)