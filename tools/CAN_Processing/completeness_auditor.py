"""CANedge2 chunk sequence and drive-window completeness auditor (Stage 3)."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from .mf4_loader import CanLoadResult


class CanCompletenessError(RuntimeError):
    """Raised under --strict-can when CAN coverage or continuity fails audit checks."""


@dataclass
class CompletenessReport:
    session_id: str
    status: str  # "COMPLETE" | "PARTIAL" | "EMPTY"
    chunk_count: int
    total_bytes: int
    message_count: int
    total_decoded_rows: int
    drive_start_utc_sec: float
    drive_stop_utc_sec: float
    drive_duration_sec: float
    can_start_utc_sec: Optional[float]
    can_end_utc_sec: Optional[float]
    can_duration_sec: float
    start_gap_sec: Optional[float]
    end_gap_sec: Optional[float]
    coverage_pct: float
    internal_dropouts_sec: List[Tuple[float, float]] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def _read_session_metadata(session_dir: str | Path) -> Dict[str, Any]:
    meta_path = Path(session_dir) / "session_metadata.json"
    if not meta_path.is_file():
        return {}
    with open(meta_path, "r", encoding="utf-8") as f:
        return json.load(f)


def _find_internal_dropouts(
    load_result: CanLoadResult,
    offset_sec: float,
    dropout_threshold_sec: float = 2.5,
) -> List[Tuple[float, float]]:
    if not load_result.messages:
        return []
    # Use the highest-rate periodic message as bus-liveness proxy
    denser = max(load_result.messages.values(), key=lambda m: m.row_count)
    ts = denser.raw_can_sec + offset_sec
    if len(ts) < 2:
        return []
    diffs = np.diff(ts)
    dropouts: List[Tuple[float, float]] = []
    # Ignore known power-cycle gaps across distinct folder IDs when identifiable
    folder_ids = {c.folder_id for c in load_result.chunks if c.folder_id}
    gap_indices = np.where(diffs > dropout_threshold_sec)[0]
    for idx in gap_indices:
        t0 = float(ts[idx])
        gap = float(diffs[idx])
        # Always report internal dropouts; caller/report includes context
        dropouts.append((round(t0, 3), round(gap, 3)))
    if len(folder_ids) > 1 and dropouts:
        # Leave in internal_dropouts_sec so downstream audits remain transparent
        pass
    return dropouts


def audit_can_completeness(
    load_result: CanLoadResult,
    session_meta: Optional[Dict[str, Any]] = None,
    aligned_span_utc_sec: Optional[Tuple[float, float]] = None,
    offset_estimate_sec: Optional[float] = None,
    strict_can: bool = False,
    gap_tolerance_sec: float = 3.0,
) -> CompletenessReport:
    """
    Evaluate CAN chunk continuity, start/tail coverage against session_metadata.json,
    and internal bus dropouts without modifying raw files.
    """
    meta = session_meta if session_meta is not None else _read_session_metadata(load_result.session_dir)
    session_id = meta.get("sessionId") or Path(load_result.session_dir).name
    drive_start_ms = float(meta.get("startTimeWallMs", 0))
    drive_stop_ms = float(meta.get("stopTimeWallMs", 0))
    drive_start_sec = drive_start_ms / 1000.0
    drive_stop_sec = drive_stop_ms / 1000.0
    drive_dur = max(0.0, drive_stop_sec - drive_start_sec)

    total_bytes = sum(c.size_bytes for c in load_result.chunks)
    total_rows = sum(m.row_count for m in load_result.messages.values())

    if not load_result.has_data:
        warnings = list(load_result.continuity_warnings) + ["No decoded CAN messages available in session/can/"]
        report = CompletenessReport(
            session_id=session_id,
            status="EMPTY",
            chunk_count=len(load_result.chunks),
            total_bytes=total_bytes,
            message_count=0,
            total_decoded_rows=0,
            drive_start_utc_sec=drive_start_sec,
            drive_stop_utc_sec=drive_stop_sec,
            drive_duration_sec=drive_dur,
            can_start_utc_sec=None,
            can_end_utc_sec=None,
            can_duration_sec=0.0,
            start_gap_sec=drive_dur if drive_dur > 0 else None,
            end_gap_sec=drive_dur if drive_dur > 0 else None,
            coverage_pct=0.0,
            warnings=warnings,
        )
        if strict_can:
            raise CanCompletenessError(f"[{session_id}] Strict CAN audit failed: {'; '.join(warnings)}")
        return report

    raw_min = min(float(m.raw_can_sec[0]) for m in load_result.messages.values() if m.row_count > 0)
    raw_max = max(float(m.raw_can_sec[-1]) for m in load_result.messages.values() if m.row_count > 0)

    if aligned_span_utc_sec is not None:
        can_start_sec, can_end_sec = float(aligned_span_utc_sec[0]), float(aligned_span_utc_sec[1])
        used_offset = can_start_sec - raw_min
    else:
        if offset_estimate_sec is not None:
            used_offset = float(offset_estimate_sec)
        else:
            meta_off_ms = (meta.get("can") or {}).get("clockOffsetMs", 0)
            used_offset = float(meta_off_ms) / 1000.0
        can_start_sec = raw_min + used_offset
        can_end_sec = raw_max + used_offset

    can_dur = max(0.0, can_end_sec - can_start_sec)
    warnings = list(load_result.continuity_warnings)

    if drive_dur > 0.0:
        start_gap = max(0.0, can_start_sec - drive_start_sec)
        end_gap = max(0.0, drive_stop_sec - can_end_sec)
        overlap = max(0.0, min(drive_stop_sec, can_end_sec) - max(drive_start_sec, can_start_sec))
        coverage_pct = min(100.0, max(0.0, 100.0 * overlap / drive_dur))
    else:
        start_gap = 0.0
        end_gap = 0.0
        coverage_pct = 100.0

    dropouts = _find_internal_dropouts(load_result, used_offset)

    if start_gap > gap_tolerance_sec:
        warnings.append(f"CAN stream begins {start_gap:.2f}s after drive start")
    if end_gap > gap_tolerance_sec:
        warnings.append(f"CAN stream ends {end_gap:.2f}s before drive stop (missing tail chunk)")
    for _, gap_s in dropouts:
        warnings.append(f"Internal CAN bus gap of {gap_s:.2f}s detected")

    if not warnings and coverage_pct >= 98.0:
        status = "COMPLETE"
    else:
        status = "PARTIAL"

    report = CompletenessReport(
        session_id=session_id,
        status=status,
        chunk_count=len(load_result.chunks),
        total_bytes=total_bytes,
        message_count=len(load_result.messages),
        total_decoded_rows=total_rows,
        drive_start_utc_sec=drive_start_sec,
        drive_stop_utc_sec=drive_stop_sec,
        drive_duration_sec=drive_dur,
        can_start_utc_sec=can_start_sec,
        can_end_utc_sec=can_end_sec,
        can_duration_sec=can_dur,
        start_gap_sec=round(start_gap, 3),
        end_gap_sec=round(end_gap, 3),
        coverage_pct=round(coverage_pct, 2),
        internal_dropouts_sec=dropouts,
        warnings=warnings,
    )

    if strict_can and status != "COMPLETE":
        raise CanCompletenessError(f"[{session_id}] Strict CAN audit failed ({status}): {'; '.join(warnings)}")

    return report
