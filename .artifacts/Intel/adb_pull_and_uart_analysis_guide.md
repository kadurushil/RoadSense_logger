# Raw UART Data Extraction & Analysis Guide

**Project:** RoadSense  
**Target:** TI AWR1843 BOOST / Android Logger  
**Date:** September 2026  

---

## 1. Overview & Purpose

During high-speed serial streaming (3,125,000 baud / ~312 KB/s), UI rendering or coroutine context switches can drop packets if parsing occurs synchronously on the main thread. To verify whether the underlying UART data stream is 100% intact before building complex real-time decoders, RoadSense includes a **Raw UART Binary Dump** feature.

This guide details:
1. Where the raw binary dump files originate and how they are written.
2. How the `adb pull` command works to retrieve these files from an unrooted Android device.
3. How the binary data is verified using diagnostic tools.
4. Step-by-step commands to repeat this process manually from `cmd.exe` or PowerShell.

---

## 2. Where the Data Comes From

```text
  [ TI AWR1843 Radar ]
           │
           │  USB OTG @ 3,125,000 Baud
           ▼
  [ Android USB Host / UsbSerialPort ]
           │
           │  Direct Thread Callback (RawDataListener)
           ▼
  [ RawUartRecorder ]
           │
           │  64 KB Buffered File I/O (fos.fd.sync())
           ▼
  /sdcard/Android/data/com.bajajauto.roadsense/files/raw_uart_YYYYMMDD_HHMMSS.bin
```

### Storage Location
- **On-Device Path:**  
  `/sdcard/Android/data/com.bajajauto.roadsense/files/raw_uart_<YYYYMMDD_HHMMSS>.bin`
- **Internal Storage Equivalence:**  
  `/storage/emulated/0/Android/data/com.bajajauto.roadsense/files/`
- **Why this path:**  
  This is the app-specific external files directory (`context.getExternalFilesDir(null)`). It complies with modern Android Scoped Storage (Android 10+ / API 28–37), requires no runtime storage permissions, and is fully accessible via ADB without root.

---

## 3. How `adb pull` Works

`adb` (Android Debug Bridge) runs a background daemon (`adbd`) on the Android device communicating with the `adb` client on your PC. 

The `adb pull` command copies files directly from the device filesystem over USB:
```bash
adb pull <remote_device_path> <local_pc_destination>
```

### Key Considerations:
1. **Device Authorization:** USB Debugging must be enabled on the Android device, and your PC RSA key fingerprint must be authorized ("Always allow from this computer").
2. **Path Quoting:** Use forward slashes `/` for Android paths and double quotes if paths contain spaces.
3. **Target Syncing:** RoadSense calls `fos.fd.sync()` when "Stop Dump" is tapped, ensuring the OS writes all cached buffers from RAM to physical flash memory before you pull the file.

---

## 4. How the Incoming Data is Analyzed

The raw binary dump is inspected using [`tools/inspect_raw_uart.py`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/inspect_raw_uart.py).

### Verification Logic:
1. **Magic Word Search:**  
   Searches for the 8-byte TI synchronization pattern:  
   `0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07` (`0x0102030405060708` uint64 LE).
2. **Header Parsing (40 Bytes):**  
   Extracts:
   - `version` (uint32)
   - `totalPacketLen` (uint32)
   - `platform` (uint32, e.g. `0xA1843`)
   - `frameNumber` (uint32)
   - `timeCpuCycles` (uint32)
   - `numDetectedObj` (uint32)
   - `numTLVs` (uint32)
   - `subFrameNumber` (uint32)
3. **Frame Continuity & Gap Analysis:**  
   Compares the offset of frame $N+1$ against `offset(N) + totalPacketLen(N)`.  
   *Note on Padding:* The TI firmware pads transmissions to a multiple of 32 bytes, producing clean `+8 B` or `+30 B` padding intervals between frames without any lost data.
4. **Frame Counter Verification:**  
   Validates that `frameNumber[N+1] - frameNumber[N] == 1`. If the difference is greater than 1, frames were dropped by the USB transport.
5. **TLV Breakdown:**  
   Inspects TLV headers (8 bytes: `type` uint32, `length` uint32) inside the payload:
   - **Type 1 (Point Cloud):** 4-byte descriptor + $N \times 10$ bytes per point.
   - **Type 2 (Clusters):** 4-byte descriptor + $N \times 8$ bytes per cluster.
   - **Type 3 (Tracks):** 4-byte descriptor + $N \times 14$ bytes per track.

---

## 5. Step-by-Step Manual Guide (CMD / PowerShell)

When you disconnect the phone, perform a test run with the radar, and reconnect the phone to your PC, follow these steps to pull and inspect the data.

### Step 1: Open Terminal in Project Root
Open `cmd.exe` or PowerShell in:
```text
C:\Users\rakadu1.AHEAD\AndroidStudioProjects\RoadSense
```

### Step 2: Check Connected Device
Ensure the phone is recognized:
```cmd
adb devices
```
*Expected Output:*
```text
List of devices attached
32001991d42c86d9    device
```

### Step 3: List Files on the Device
See the latest captured binary files:
```cmd
adb shell ls -la /sdcard/Android/data/com.bajajauto.roadsense/files/
```
*Example Output:*
```text
-rw-rw---- 1 u0_a226 sdcard_rw 483328 2026-09-09 09:41 raw_uart_20260909_094035.bin
```

### Step 4: Pull the File to Your PC
Pull the file into the local `logs\` directory:

**Option A: Pull a specific file**
```cmd
adb pull /sdcard/Android/data/com.bajajauto.roadsense/files/raw_uart_20260909_094035.bin logs\
```

**Option B: Pull all files in the directory**
```cmd
adb pull /sdcard/Android/data/com.bajajauto.roadsense/files/. logs\
```

### Step 5: Run the Inspection Script
Analyze the pulled binary file:
```cmd
python tools/inspect_raw_uart.py logs/raw_uart_20260909_094035.bin
```

*Expected Verification Output:*
```text
======================================================================
Summary:
  Total frames parsed:         906
  Valid packet headers:        906
  Corrupt/out-of-bounds:       0
  Stream discontinuities/gaps: 905
  Frame counter range:         #511 -> #1416 (Span: 906)
  Frame sequence capture rate: 100.0%
======================================================================
```

### Step 6: Detailed TLV Inspection (Quick One-Liner)
To inspect the exact distribution of TLVs (points, tracks, clusters) inside the captured file:
```cmd
python -c "import struct; from collections import Counter; data=open(r'logs\raw_uart_20260909_094035.bin','rb').read(); MAGIC=bytes([2,1,4,3,6,5,8,7]); pos=0; counts=Counter(); fc=0; (lambda: [exec('global pos, fc\nwhile True:\n idx=data.find(MAGIC, pos)\n if idx==-1 or idx+40>len(data): break\n fc+=1\n num_tlvs=struct.unpack(\"<I\", data[idx+32:idx+36])[0]\n off=idx+40\n for _ in range(num_tlvs):\n  t=struct.unpack(\"<I\", data[off:off+4])[0]\n  l=struct.unpack(\"<I\", data[off+4:off+8])[0]\n  counts[t]+=1\n  off+=8+l\n pos=idx+8') or print(f'Frames: {fc}\nTLVs: {dict(counts)}')])()"
```
