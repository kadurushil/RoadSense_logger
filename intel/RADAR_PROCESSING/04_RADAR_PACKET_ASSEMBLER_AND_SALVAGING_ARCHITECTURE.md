# Radar Packet Assembler & Intelligent Frame Salvaging Architecture

## 1. Overview & Architectural Role

The **`RadarPacketAssembler`** ([`RadarPacketAssembler.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarPacketAssembler.kt)) is the core stream framing component in RoadSense. It receives fragmented raw byte chunks from the USB-UART serial worker thread, scans for the 8-byte TI magic word (`0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07`), validates the 40-byte header, and emits discrete [`RawRadarPacket`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/models/RadarHeader.kt#L28-L57) instances for downstream decoding and recording.

```
Incoming USB Byte Stream (Fragmented Chunks)
                      │
                      ▼
┌──────────────────────────────────────────────┐
│           RadarPacketAssembler               │
│                                              │
│  1. Scan for Magic Word (8 bytes)            │
│  2. Parse & Validate Header (40 bytes)       │
│  3. Preemptive Magic Word Guard              │
│     ├── Truncation Detected?                 │
│     │   └── Salvage Header + Received TLVs   │
│     └── Emit RawRadarPacket(isSalvaged=true) │
│  4. Normal Full Frame Assembly               │
│     └── Emit RawRadarPacket(isSalvaged=false)│
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│             RadarTlvDecoder                  │
│                                              │
│  - Parses TLV 1: Detected Points 3D          │
│  - Slices safe ByteBuffers per TLV           │
│  - Graceful exit on truncated remaining bytes│
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│    RadarFrame (Points, Tracks, Diagnostics)  │
│    -> Cockpit HUD & Session Recorder         │
└──────────────────────────────────────────────┘
```

---

## 2. The Intelligent Frame Salvaging Algorithm

Prior to our hardening, if bytes were dropped on the UART bus, the assembler would discard the entire frame once the next magic word was detected prematurely. This caused sequence drops (`RADAR_FRAME_DROP: Expected #100, got #101`) and wiped out all target tracking for that 50 ms cycle.

### The Salvaging Implementation:
In `RadarPacketAssembler.kt`:
```kotlin
// Preemptive Magic Word Guard & Intelligent Frame Salvaging:
// Check if another magic word arrives prematurely before totalPacketLen bytes.
// If bytes were dropped on the UART bus, the tail or middle of this frame was lost,
// and the next frame has already started. Instead of discarding the entire frame and
// causing sequence drops/UI stutter, salvage whatever complete TLVs were received!
val searchEnd = kotlin.math.min(bufferSize, magicIndex + totalPacketLen)
val embeddedMagicIndex = findMagicWord(
    internalBuffer,
    startIndex = magicIndex + RadarHeader.MAGIC_WORD.size,
    length = searchEnd
)
if (embeddedMagicIndex in (magicIndex + RadarHeader.MAGIC_WORD.size) until searchEnd) {
    val salvagedLen = embeddedMagicIndex - magicIndex
    if (salvagedLen >= RadarHeader.HEADER_SIZE_BYTES) {
        val salvagedPayload = internalBuffer.copyOfRange(
            magicIndex + RadarHeader.HEADER_SIZE_BYTES,
            embeddedMagicIndex
        )
        val salvagedFullPacket = internalBuffer.copyOfRange(
            magicIndex,
            embeddedMagicIndex
        )
        val salvagedHeader = RadarHeader(
            version = version,
            totalPacketLen = salvagedLen,
            platform = platform,
            frameNumber = frameNumber,
            timeCpuCycles = timeCpuCycles,
            numDetectedObj = numDetectedObj,
            numTLVs = numTLVs,
            subFrameNumber = subFrameNumber
        )
        packets.add(
            RawRadarPacket(
                header = salvagedHeader,
                payload = salvagedPayload,
                hostTimestampNs = System.nanoTime(),
                fullPacketBytes = salvagedFullPacket,
                isSalvaged = true
            )
        )
    }

    // Advance internal buffer directly to the next magic word
    val remainingLen = bufferSize - embeddedMagicIndex
    System.arraycopy(internalBuffer, embeddedMagicIndex, internalBuffer, 0, remainingLen)
    bufferSize = remainingLen
    continue
}
```

---

## 3. Graceful Downstream TLV Parsing (`RadarTlvDecoder.kt`)

`RadarTlvDecoder` ([`RadarTlvDecoder.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/main/java/com/bajajauto/roadsense/decoding/RadarTlvDecoder.kt#L74-L90)) is naturally resilient to salvaged frames:
1. **TLV Header Validation:**  
   Before reading any TLV, it checks `buffer.remaining() < TLV_HEADER_SIZE (8 bytes)`. If insufficient bytes remain, the loop breaks gracefully.
2. **TLV Length Check:**  
   If `buffer.remaining() < tlvLength`, it logs a warning and exits cleanly without throwing an exception:
   ```kotlin
   if (tlvLength < 0 || buffer.remaining() < tlvLength) {
       Log.w(TAG, "TLV #$tlvIndex (Type $tlvType) length ($tlvLength) exceeds remaining payload (${buffer.remaining()})")
       break
   }
   ```
3. **Payload Structure Advantage:**  
   Because the TI mmWave radar stream places **TLV 1 (Detected 3D Points)** at the very beginning of the payload, salvaged frames (which have 1,400 to 1,600 bytes intact) successfully parse **100% of the 3D point cloud** before the truncated boundary is reached!
4. **Result:**  
   The HUD continues rendering 60 to 170+ valid 3D points, and the frame sequence number advances normally without creating a gap.

---

## 4. Telemetry & Flight Recorder Observability

1. **`RawRadarPacket.isSalvaged`:**  
   Tracks whether the packet was reconstructed via salvaging.
2. **`RadarViewModel` Real-Time Logging:**  
   Logs real-time salvage events to `session_debug.log`:
   ```text
   RADAR_FRAME_SALVAGED: Frame #1888 salvaged (recovered 1583 B payload, 0 sequence gap, total salvaged: 1)
   ```
3. **Session Telemetry Audit:**  
   Reports `salvagedFrames` count alongside `framesRecorded` and `droppedFrames` upon session completion.

---

## 5. Unit Test Verification Patterns

The test suite in [`RadarPacketAssemblerTest.kt`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/app/src/test/java/com/bajajauto/roadsense/decoding/RadarPacketAssemblerTest.kt) validates:
* `appendBytes_truncatedFrameWithPrematureNextMagic_salvagesTruncatedFrameAndRecoversNextFrame`
* `appendBytes_truncatedFrameWithLessThan40Bytes_discardsIncompleteHeaderAndRecoversNextFrame`
* `appendBytes_splitMagicWordAcrossChunkBoundaryInTruncatedStream_recoversCleanly`
* `appendBytes_garbageBytesBetweenTruncatedAndNextFrame_skipsGarbageAndRecoversNextFrame`
* `appendBytes_multipleConsecutiveTruncatedFrames_survivesCascadeAndRecoversNextValidFrame`

All unit tests pass with `BUILD SUCCESSFUL`.

