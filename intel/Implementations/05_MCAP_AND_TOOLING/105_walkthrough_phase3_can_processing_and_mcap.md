# Walkthrough: Phase 3 — Modular `CAN_Processing` & Millisecond-Aligned MCAP Ingestion

## 1. Modular Package Summary (`tools/CAN_Processing/`)

All CAN post-processing logic lives cleanly inside `tools/CAN_Processing/` and executes sequentially before MCAP export (leaving `tools/sync_and_process_sessions.py` lean and raw `.MF4` files untouched):

1. **[can_mcap_config.json](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/can_mcap_config.json) & [__init__.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/__init__.py):** Centralizes DBC path (`tools/VCU.dbc`), `"all"` vs `"whitelist"` mode, exclusions, and rolling window parameters (`45 s` window / `30 s` step). Dependencies (`asammdf>=8.0.0`, `cantools>=40.0.0`) added to `tools/requirements-mcap.txt` (`roadsense-mcap` Conda env only; `cv313` untouched).
2. **[mf4_loader.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/mf4_loader.py):** Reads `IDBLOCK`/`HDBLOCK` headers directly, verifies same-folder split sequence and seam continuity, and decodes all active DBC groups via `asammdf` (`sync=True`, `time_from_zero=False`) while preserving UTF-8 enum labels from DBC `VAL_` tables.
3. **[completeness_auditor.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/completeness_auditor.py):** Audits chunk coverage `%`, start/tail gaps against `session_metadata.json`, and internal dropouts, reporting `COMPLETE`/`PARTIAL`/`EMPTY` and enforcing `--strict-can`.
4. **[time_aligner.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/time_aligner.py):** Evaluates rolling `45 s / 30 s` windows across the drive using **Tier 1** (`RADAR_Debug_Info.FrameNumber_Act` ↔ UART `hw_frame_num & 0xFFFF`, wraparound-safe across 54.6 min rollovers) with **Tier 3** (`session_metadata.json -> can.clockOffsetMs`) fallback when Radar is disconnected.
5. **[proto_builder.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/proto_builder.py):** Dynamically synthesizes isolated `roadsense.can.<MessageName>` and `CanSyncAudit` Protobuf classes inside fresh `DescriptorPool`s.
6. **[pipeline.py](file:///c:/Users/rakadu1.AHEAD/AndroidStudioProjects/RoadSense/tools/CAN_Processing/pipeline.py):** Sequential orchestrator (`prepare_can_bundle`, `write_can_bundle_to_mcap`) emitting 5-second time-interleaved `/can/<MessageName>` topics, `/can/sync_audit`, and an embedded `can_sync_audit.json` attachment, wired cleanly into `tools/sync_and_process_sessions.py` and `tools/convert_session_to_mcap.py`.

---

## 2. Verification Results (`roadsense-mcap` Env)

### Unit & Regression Suite (`python -m unittest discover -s tools/CAN_Processing/tests -v`)
- **Result:** **16/16 tests passed (`OK` in `24.85 s`)** covering header parsing, enum normalization, multi-boot concatenation (`session_20261119_105126`), 16-bit counter wraparound + injected drift/jumps, completeness gaps/`--strict-can`, Dynamic Protobuf round-trip across 77 DBC messages, and full pipeline MCAP serialization (`433,867` whitelist messages in `2.66 s`).

### End-to-End Field Drive Conversions (`tools/convert_session_to_mcap.py`)
| Drive Verified | Outcome | Key Metrics Observed in `.mcap` |
|---|---|---|
| **`session_20261116_143131`** *(Pre-Phase-2 Radar + CAN, 11 chunks, ~11 min)* | **PASS (`COMPLETE`, `26.71 s`, `475.30 MB`)** | `TIER1_RADAR_FRAME_ID`: **`5,957` matches**, corrected **`+30.942 s`** offset & **`-451.3 ppm`** crystal drift (`1,581,453` CAN msgs across `77` `/can/*` topics). Cross-topic `/can/RADAR_Debug_Info` ↔ `/radar/diagnostics` delta inside `.mcap`: **median `-0.08 ms`**, `p05..p95 = [-18.33, +20.57] ms` (`38.90 ms` spread, cut from `220.17 ms`). |
| **`session_20261119_121554`** *(CAN only, `--no-video --can-mode whitelist`)* | **PASS (`PARTIAL`, `4.20 s`, `8.63 MB`)** | `TIER3_METADATA`: `102,279` CAN messages (`88.7%` coverage, warned `18.53 s` start gap). |
| **`session_20261113_101048`** *(Partial tail, `--no-video`)* | **PASS default / EXIT `1` under `--strict-can`** | Warned `[WARN] CAN Audit: CAN stream ends 470.83s before drive stop (13.2% coverage)` and aborted cleanly under `--strict-can`. |
