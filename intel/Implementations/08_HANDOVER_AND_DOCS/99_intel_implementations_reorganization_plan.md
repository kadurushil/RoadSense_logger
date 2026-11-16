# Implementation Plan: Reorganize & Number `intel/Implementations` Knowledge Archive

## Goal Description
`intel/Implementations/` currently contains **144 markdown files** (plans, walkthroughs, forensic reports, and `Sensors/` docs) accumulated between `2026-09-22` and `2026-11-16`. Most sit flat in the root directory without chronological prefixes, making it slow for future AI agents and developers to locate related plan/walkthrough pairs or understand implementation order.

This plan reorganizes all **144 files** into **8 subsystem subfolders**, assigns a **global chronological `NN_` prefix** (pairing each plan and its corresponding walkthrough under the same number), generates a master searchable [`00_IMPLEMENTATION_INDEX.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Implementations/00_IMPLEMENTATION_INDEX.md), and updates any broken cross-links in `context/`, `README.md`, and `intel/`.

---

## User Review Required

> [!IMPORTANT]
> Please review and confirm the **two-dimensional organization** below before I execute `git mv`:
> 1. **Global Chronological `NN_` Prefix (Pairs Share Same Number):** Every plan and its matching walkthrough(s) share the same `01_`..`~78_` prefix ordered by earliest git commit timestamp (`2026-09-22` → `2026-11-16`).
> 2. **8 Subsystem Subfolders:** Files are moved via `git mv` (preserving full git history) into subsystem folders, including merging `Sensors/`, `Sensors/IMU/`, and `Sensors/CAMERA/` into their respective numbered subsystem folders.

---

## Proposed Changes

### 1. Subsystem Folder Taxonomy (`intel/Implementations/`)

```text
intel/Implementations/
 ├── 00_IMPLEMENTATION_INDEX.md        <-- Master chronological & subsystem lookup table
 ├── 01_RADAR/                         <-- AWR1843 TLV, CP2105 UART, USB DMA, Bug #29, Display Pacer (~21 files)
 ├── 02_CAMERA_AND_CALIBRATION/        <-- Camera2, Road AE, 6-DOF spatial calib, horizon, Sensors/CAMERA (~19 files)
 ├── 03_IMU_AND_KINEMATICS/            <-- LSM6DSL, jitter, 3D viz, yaw/frame transforms, Sensors/IMU (~20 files)
 ├── 04_GNSS_AND_SYNC/                 <-- GNSS dual-stream, cross-sensor timing & HTML audit reports (~14 files)
 ├── 05_MCAP_AND_TOOLING/              <-- Foxglove MCAP, zero-cost video, web dashboard, Python tools (~20 files)
 ├── 06_UI_AND_PERFORMANCE/            <-- Compose isolation, 60 Hz jitter, stutter & Phase 1-6 plans (~13 files)
 ├── 07_PERCEPTION_AND_VALIDATION/     <-- ADAS FCW/BSD, 3D bounding boxes, CV fusion, road validation (~14 files)
 └── 08_HANDOVER_AND_DOCS/             <-- Handover docs, Week 2/3 decks, doc sync, cleanup, Sensors README (~23 files)
```

---

### 2. Numbering & Naming Convention
- **Paired Plan + Walkthrough:** Share the same global chronological prefix `NN_` (two digits `01_`–`99_`), e.g.:
  - `01_RADAR/` → `59_radar_scheduler_starvation_and_priority_hardening_plan.md` & `59_walkthrough_bug29_radar_scheduler_starvation_fixes.md`
  - `06_UI_AND_PERFORMANCE/` → `76_performance_mitigation_and_instrumentation_plan.md` & `76_bench_test_and_verification_plan.md`
- **Standalone Reports / Audits:** Receive their own chronological `NN_` prefix in their subsystem folder (e.g., `61_m20_vs_m32_deterministic_comparison_report.md`).
- **Existing `Sensors/` Subfolder:** `Sensors/CAMERA/*` moves into `02_CAMERA_AND_CALIBRATION/`, `Sensors/IMU/*` moves into `03_IMU_AND_KINEMATICS/`, and `Sensors/README.md` + `DEVICE_SENSOR_AND_CAMERA_CAPABILITIES.md` move into `08_HANDOVER_AND_DOCS/` (all numbered).

---

### 3. Master Lookup Index (`00_IMPLEMENTATION_INDEX.md`)
#### [NEW] [`intel/Implementations/00_IMPLEMENTATION_INDEX.md`](file:///C:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/intel/Implementations/00_IMPLEMENTATION_INDEX.md)
- Contains:
  1. **Quick-Start AI Agent Navigation Table** (by Subsystem: Radar, Camera, IMU, GNSS/Sync, MCAP/Tools, UI Perf, Perception, Handover).
  2. **Complete Chronological Master Table** (`# | Date | Subsystem | Plan | Walkthrough / Report | Git Commit`).

---

### 4. Broken Reference Repair
#### [MODIFY] Cross-links in `context/`, `intel/`, and `README.md`
- Scan all `.md` files in `context/`, `intel/`, `README.md`, and `GEMINI.md` for old `intel/Implementations/...` paths (such as `context/09_HANDOVER_RADAR_SCHEDULER_STARVATION_BUG29.md`) and rewrite them to point to the new numbered subfolder paths.

---

## Verification Plan

### Automated Checks
1. Verify total file count before and after (`144` original docs + `1` new `00_IMPLEMENTATION_INDEX.md` = `145` markdown files, zero files lost).
2. Run a Python link-integrity verifier across `context/`, `intel/`, `README.md`, and `GEMINI.md` confirming **0 broken markdown links** to `intel/Implementations/`.
3. Verify `git status` shows clean `R` (renamed) tracking via `git mv` with no untracked leftovers or empty `Sensors/` directories.
