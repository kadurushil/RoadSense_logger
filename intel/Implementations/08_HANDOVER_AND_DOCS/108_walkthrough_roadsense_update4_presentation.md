# Walkthrough: RoadSense Update 4 Executive & Technical Presentation

## Summary of Deliverables

Implemented the approved 11-slide **Update 4** executive and technical progress presentation covering the 1-month window since Update 3 (`50305c0` on **2026-10-23** $\rightarrow$ `eb8adc5` on **2026-11-23**: **91 commits**, **368 files changed**, **+44,780 / -6,242 lines**).

### Created Files
1. **Deck Generator Script:** [`intel/presentations/generate_roadsense_update4_deck.js`](../../presentations/generate_roadsense_update4_deck.js)
2. **Visual Diagram & Empirical Chart Generator:** [`intel/presentations/generate_update4_visuals.py`](../../presentations/generate_update4_visuals.py)
3. **Final Presentation Deck (11 Slides):** [`intel/presentations/decks/RoadSense_Update4_Executive_Progress.pptx`](../../presentations/decks/RoadSense_Update4_Executive_Progress.pptx)
4. **Slide-by-Slide Handover & `Alt + =` OMML Equation Notes:** [`intel/presentations/decks/RoadSense_Update4_Slide_by_Slide_Handover_Notes.md`](../../presentations/decks/RoadSense_Update4_Slide_by_Slide_Handover_Notes.md)
5. **Custom 300-DPI Assets (`intel/presentations/assets/`):**
   - `radar_pipeline_bottleneck.png` — Left-to-Right serial hardware bottleneck (`576 B / 1.84 ms` FIFO vs `128 KB / 409.6 ms` DMA fix).
   - `radar_clock_jitter_comparison.png` — Empirical `session_20261120_112453` (`40,023` intervals) trace comparing raw USB arrival (`8.73 ms` std dev) vs `600 MHz` disciplined clock lock (`0.21 ms` std dev, **`41×` tighter**).
   - `ui_latency_before_after.png` — Verified `SM-M325F` UI before/after reductions (`1 Hz HUD 64.4 -> 0.28 ms`, `DRAW_OVERLAY 14.45 -> 0.38 ms`, `DRAW_BEV 8.57 -> 0.36 ms`, all below `16.67 ms` 60 FPS frame budget).
   - `can_end_to_end_pipeline.png` — Full Left-to-Right Android CANedge2 watchdog $\rightarrow$ `tools/CAN_Processing/` Tier-1/Tier-2 alignment $\rightarrow$ Dynamic Protobuf MCAP pipeline diagram.

---

## Final 11-Slide Architecture Implemented

| Slide | Focus | Design Highlights |
| :---: | :--- | :--- |
| **01** | **Title Slide** | 3 Pillar Cards covering `91` commits across Radar/Clock, `<1 ms` SurfaceView UI & HUD, and E2E CAN/MCAP/Pitch Solver |
| **02** | **Executive Summary** | 4-pillar quantitative summary cards across Android app & PC toolchain |
| **03** | **Radar Pipeline & Clock Timing** | No math formulas on slide; visual explanation (`Bugs #28–#30`) + embedded `L->R` bottleneck diagram + live Nov 20 jitter chart |
| **04** | **60 FPS UI Spotlight (`Bug #31`)** | Generous spotlight on APK sabotage discovery, idle loop/semantics IPC fixes, persistent `AutoFitSurfaceView`, `Yellow STBY`, & bar chart |
| **05** | **SBS, ISP Parity, HUD & EKF-ZUPT** | Merged Immersive SBS (`±60°`, `SM-M325F cy=360`), 5-pill HUD + `200 Hz` IMU + `/gnss/network` isolation, `99.7%` EKF-ZUPT yaw lock, `Eq. Slot 1` + **Phone Screenshot Slot** |
| **06** | **E2E CAN Pipeline & Improvements** | Generated `L->R` diagram, Tier-1 `5,957` matches (`-0.08 ms`), Tier-2 `rfft` (`0.998 corr`), + **3 Suggested Next CAN Improvements** |
| **07** | **Benchmark Overhaul & Pitch Solver** | `contact_patch`, `nearest`, `0–50 m` zero-lateral scope, `Eq. Slot 2` (Huber+Bias), `session_20261120_112453` (`5.232° -> 4.238°`), + **Dashboard Screenshot Slot** |
| **08** | **Hardening Pt 1 (`Bugs #26–#31`)** | Compact `3×2` 6-card root-cause post-mortem grid |
| **09** | **Hardening Pt 2 (`Bugs #32–#35+`)** | Compact `3×2` 6-card grid adding CANedge `+60 s` rollover, IMU `/4` rate fix, benchmark center bias & trailing MP4 extrapolation |
| **10** | **100.0% MCAP Parity & Scorecard** | Folded Foxglove 5-topic enrichment + `23,340`-frame `100.0%` parity scorecard + **Foxglove Screenshot Slot** + full 9-row KPI table + `200+` tests |
| **11** | **Update 5 Strategic Roadmap** | **Priority #1: Physical Hard Mounting Rigidity**, Priority #2: AWR1843 DSP Firmware A/B Qualification, Priority #3: Online Pitch Auto-Trim & `>66%` Gate |

---

## Verification Results

1. **Deterministic Asset Generation (`roadsense-mcap`):**
   - `C:\Users\rakadu1.AHEAD\.conda\envs\roadsense-mcap\python.exe intel/presentations/generate_update4_visuals.py` exited `0`, creating all 4 PNG visuals (`300 DPI`). Visually inspected every PNG to eliminate text clipping and line collisions.
2. **PPTX Generation (`Node v24.13.1` + `PptxGenJS`):**
   - `node intel/presentations/generate_roadsense_update4_deck.js` exited `0`, emitting all 11 slides (`SLIDE_COUNT: 11`).
3. **OpenXML Metadata Audit:**
   - Inspected `docProps/app.xml` and `docProps/core.xml` inside `RoadSense_Update4_Executive_Progress.pptx`: verified clean `"and"` strings (`Bajaj Auto Ltd. - Advanced Perception and Telemetry`) with zero unescaped `&`.
4. **Full Visual Render Verification (Microsoft PowerPoint COM Export):**
   - Exported all 11 slides at `1280×720` via local `POWERPNT.EXE` with zero repair warnings, visually inspected all 11 rendered PNGs, caught and fixed a 1-line wrap in the Slide 07 table and Slide 11 top banner, and verified clean single-line alignment on re-export.
