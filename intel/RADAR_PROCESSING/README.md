# RoadSense Radar Processing Knowledge Base & Technical Archives

> **Directory:** `intel/RADAR_PROCESSING/`  
> **Status:** Active Reference & Production Runbook  
> **Topic:** TI mmWave Radar Ingestion, 3.125 Mbps High-Speed UART Mechanics, CP2105 Hardware Overflows & Multi-Device Compatibility

---

## 1. Directory Overview & Navigation Map

This directory serves as the authoritative, centralized knowledge repository for the RoadSense Radar Processing subsystem. It documents the complete history, theoretical modeling, forensic trace analysis, and final breakthrough regarding the **3.125 Mbps High-Speed UART Data Loss Bug** (Bugs #29 & #30).

| Document | Title | Purpose |
| :--- | :--- | :--- |
| [`01_POST_MORTEM_HIGH_SPEED_UART_DATA_LOSS.md`](01_POST_MORTEM_HIGH_SPEED_UART_DATA_LOSS.md) | **Engineering Post-Mortem: The 3.125 Mbps Data Loss Bug** | Complete post-mortem detailing the physical root cause, the "2016-Byte Wall", CP2105 transceiver limitations, and why software buffering could not prevent FIFO overflows. |
| [`02_DETERMINISTIC_COMPARISON_M20_VS_M32.md`](02_DETERMINISTIC_COMPARISON_M20_VS_M32.md) | **Deterministic Comparison: Galaxy M20 vs. Galaxy M32** | Ground-truth empirical proof comparing Exynos 7904 (DWC3) vs. Helio G80 (`musb-hdrc` + QMU DMA) with binary data and companion Perfetto trace analysis. |
| [`03_GALAXY_M20_PARITY_AND_MITIGATION_RUNBOOK.md`](03_GALAXY_M20_PARITY_AND_MITIGATION_RUNBOOK.md) | **Legacy Device Parity & Mitigation Strategies** | Actionable engineering strategies to achieve stable ingestion on older/budget hardware (Baud rate reduction, CLI packet capping, and native JNI USB drivers). |
| [`04_RADAR_PACKET_ASSEMBLER_AND_SALVAGING_ARCHITECTURE.md`](04_RADAR_PACKET_ASSEMBLER_AND_SALVAGING_ARCHITECTURE.md) | **Packet Assembler & Intelligent Frame Salvaging Architecture** | Technical specification of `RadarPacketAssembler.kt`, Preemptive Magic Word Guards, and partial frame recovery under burst packet truncation. |
| [`05_CODEBASE_CHANGE_AUDIT_KEEP_VS_REVERT_ANALYSIS.md`](05_CODEBASE_CHANGE_AUDIT_KEEP_VS_REVERT_ANALYSIS.md) | **Codebase Change Audit: Keep vs. Revert Analysis** | File-by-file audit of all past mitigation changes, architectural justifications, and what happens if reverted on Galaxy M32. |
| [`06_HARDWARE_CLOCK_DISCIPLINING_AND_USB_BULK_JITTER_ELIMINATION.md`](06_HARDWARE_CLOCK_DISCIPLINING_AND_USB_BULK_JITTER_ELIMINATION.md) | **Hardware Clock Disciplining & USB Bulk Jitter Elimination** | Technical specification of 64-byte bulk coalescing, 600 MHz C674x DSP cycle disciplining, dual-timestamp observability, dynamic baud rate scaling, and cross-sensor sync. |

---

## 2. Core Takeaways for Future Engineers

1. **Hardware Flow Control is Mandatory at 3.125 Mbps:**  
   The TI AWR1843BOOST does not route RTS/CTS from the radar DSP to the Silicon Labs CP2105 transceiver. At 3.125 Mbps, the CP2105's 576-byte hardware FIFO overflows in **1.84 ms** if host polling pauses.
2. **User-Space Buffers Cannot Save Dropped Hardware Bytes:**  
   Software DMA pools (`setReadQueue`) buffer data that has *already crossed the USB cable*. Bytes dropped inside the CP2105 FIFO *never reach the USB bus*.
3. **USB Host Controller Architecture Matters:**  
   Modern chipsets with autonomous hardware DMA engines (such as MediaTek's QMU or Qualcomm's High-Speed DMA) sustain continuous Full-Speed transfers without host pauses. Budget legacy controllers (like Exynos 7904 DWC3) experience periodic $1.9\text{ ms}$ descriptor reload stalls when packets exceed 2,016 bytes.
4. **Target Device Recommendation:**  
   The **Samsung Galaxy M32 (Android 13)** is verified to achieve **100.00% zero-loss ingestion** across dense 200-object radar point clouds ($> 3,388\text{ B/frame}$) at 20.0 Hz.

