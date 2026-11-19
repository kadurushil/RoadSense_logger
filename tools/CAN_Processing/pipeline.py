"""Sequential CAN Processing orchestrator & MCAP ingestion bridge (Stage 6)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import numpy as np

from . import load_can_config
from .completeness_auditor import (
    CompletenessReport,
    _read_session_metadata,
    audit_can_completeness,
)
from .mf4_loader import CanLoadResult, load_can_session
from .proto_builder import _sanitize_proto_ident, build_can_protobuf_classes
from .time_aligner import CanAlignmentResult, align_can_timeline


@dataclass
class CanSessionBundle:
    session_dir: str
    load_result: CanLoadResult
    alignment: CanAlignmentResult
    completeness: CompletenessReport
    aligned_utc_ns_by_msg: Dict[str, np.ndarray]
    audit_dict: Dict[str, Any]


def prepare_can_bundle(
    session_dir: str | Path,
    time_offset_ns: Optional[int] = None,
    config_path: Optional[str] = None,
    can_mode: Optional[str] = None,
    strict_can: bool = False,
) -> CanSessionBundle:
    """
    Execute the complete CAN post-processing chain sequentially:
      1. Non-destructive MDF4 load + VCU.dbc decode + chunk continuity check (`mf4_loader`)
      2. Rolling 30-60s 3-tier time alignment onto primary UTC ns (`time_aligner`)
      3. Drive-window completeness & dropout audit (`completeness_auditor`)
    """
    cfg = load_can_config(config_path)
    align_cfg = cfg.get("alignment", {})
    window_sec = float(align_cfg.get("window_sec", 45.0))
    step_sec = float(align_cfg.get("step_sec", 30.0))
    min_pairs = int(align_cfg.get("min_tier1_pairs_per_window", 10))

    sess_path = Path(session_dir)
    meta = _read_session_metadata(sess_path)
    load_res = load_can_session(sess_path, config=cfg, can_mode=can_mode)

    alignment = align_can_timeline(
        load_res,
        session_meta=meta,
        time_offset_ns=time_offset_ns,
        window_sec=window_sec,
        step_sec=step_sec,
        min_tier1_pairs_per_window=min_pairs,
    )

    aligned_utc_ns_by_msg: Dict[str, np.ndarray] = {}
    aligned_span_utc_sec: Optional[Tuple[float, float]] = None
    if load_res.has_data:
        min_ns: Optional[int] = None
        max_ns: Optional[int] = None
        for msg_name, msg in load_res.messages.items():
            utc_ns = alignment.map_raw_sec_to_utc_ns(msg.raw_can_sec)
            aligned_utc_ns_by_msg[msg_name] = utc_ns
            if len(utc_ns) > 0:
                m0 = int(utc_ns[0])
                m1 = int(utc_ns[-1])
                min_ns = m0 if min_ns is None else min(min_ns, m0)
                max_ns = m1 if max_ns is None else max(max_ns, m1)
        if min_ns is not None and max_ns is not None:
            aligned_span_utc_sec = (min_ns / 1e9, max_ns / 1e9)

    completeness = audit_can_completeness(
        load_res,
        session_meta=meta,
        aligned_span_utc_sec=aligned_span_utc_sec,
        strict_can=strict_can,
    )

    audit_dict: Dict[str, Any] = {
        "session_id": completeness.session_id,
        "ingest_mode": load_res.ingest_mode,
        "dbc_path": load_res.dbc_path,
        "completeness": completeness.to_dict(),
        "alignment": alignment.to_audit_dict(),
        "chunks": [
            {
                "filename": c.filename,
                "size_bytes": c.size_bytes,
                "folder_id": c.folder_id,
                "split_seq": c.split_seq,
                "hd_start_time_ns": c.hd_start_time_ns,
                "raw_min_sec": c.raw_min_sec,
                "raw_max_sec": c.raw_max_sec,
            }
            for c in load_res.chunks
        ],
    }

    return CanSessionBundle(
        session_dir=str(sess_path),
        load_result=load_res,
        alignment=alignment,
        completeness=completeness,
        aligned_utc_ns_by_msg=aligned_utc_ns_by_msg,
        audit_dict=audit_dict,
    )


def write_can_bundle_to_mcap(
    writer: Any,
    bundle: CanSessionBundle,
    count_msg: Optional[Callable[[str], None]] = None,
    start_wall_ms: Optional[int] = None,
    bucket_sec: float = 5.0,
) -> int:
    """
    Write aligned `/can/<MessageName>` topics and `/can/sync_audit` into an open
    `mcap_protobuf.writer.Writer` in 5-second time-interleaved buckets for fast seeking,
    plus embed `can_sync_audit.json` as an MCAP attachment.
    """
    load_res = bundle.load_result
    comp = bundle.completeness
    align = bundle.alignment

    audit_wall_ns = int((start_wall_ms or int(comp.drive_start_utc_sec * 1000.0)) * 1_000_000)
    if not load_res.has_data:
        writer._writer.add_attachment(
            create_time=audit_wall_ns,
            log_time=audit_wall_ns,
            name="can_sync_audit.json",
            media_type="application/json",
            data=json.dumps(bundle.audit_dict, indent=2).encode("utf-8"),
        )
        return 0

    cls_map, audit_cls = build_can_protobuf_classes(load_res.messages)

    # Precompute clean field lists and Python lists once per topic
    prepared_topics: List[Tuple[str, str, Any, np.ndarray, np.ndarray, List[Tuple[str, List[Any]]]]] = []
    global_min_ns: Optional[int] = None
    global_max_ns: Optional[int] = None

    for msg_name in sorted(load_res.messages.keys()):
        decoded = load_res.messages[msg_name]
        utc_ns_arr = bundle.aligned_utc_ns_by_msg[msg_name]
        if len(utc_ns_arr) == 0:
            continue
        raw_ns_arr = np.round(decoded.raw_can_sec * 1_000_000_000.0).astype(np.int64)
        off_ms_arr = (utc_ns_arr - raw_ns_arr) / 1_000_000.0

        m0, m1 = int(utc_ns_arr[0]), int(utc_ns_arr[-1])
        global_min_ns = m0 if global_min_ns is None else min(global_min_ns, m0)
        global_max_ns = m1 if global_max_ns is None else max(global_max_ns, m1)

        used_fields = {"raw_can_ts_ns", "applied_offset_ms"}
        col_items: List[Tuple[str, List[Any]]] = []
        for sig_name, arr in decoded.signals.items():
            clean_sig = _sanitize_proto_ident(sig_name, fallback_prefix="sig")
            while clean_sig in used_fields:
                clean_sig = f"{clean_sig}_f"
            used_fields.add(clean_sig)
            col_items.append((clean_sig, arr.tolist()))

        col_items.append(("raw_can_ts_ns", raw_ns_arr.tolist()))
        col_items.append(("applied_offset_ms", off_ms_arr.tolist()))

        topic = f"/can/{_sanitize_proto_ident(msg_name, fallback_prefix='CANMsg')}"
        prepared_topics.append((topic, msg_name, cls_map[msg_name], utc_ns_arr, utc_ns_arr.tolist(), col_items))

    # Write in time-localized buckets so MCAP chunks stay temporally coherent across all CAN topics
    total_written = 0
    if global_min_ns is not None and global_max_ns is not None:
        bucket_ns = max(1_000_000_000, int(bucket_sec * 1_000_000_000))
        b_start = global_min_ns
        while b_start <= global_max_ns:
            b_end = b_start + bucket_ns
            for topic, _, msg_cls, utc_np, utc_list, col_items in prepared_topics:
                i0 = int(np.searchsorted(utc_np, b_start, side="left"))
                i1 = int(np.searchsorted(utc_np, b_end, side="left"))
                if i1 <= i0:
                    continue
                field_names = [fn for fn, _ in col_items]
                sliced_cols = [vals[i0:i1] for _, vals in col_items]
                for idx_local in range(i1 - i0):
                    ts_ns = utc_list[i0 + idx_local]
                    msg = msg_cls()
                    for fn, col_slice in zip(field_names, sliced_cols):
                        setattr(msg, fn, col_slice[idx_local])
                    writer.write_message(topic, msg, log_time=ts_ns)
                    total_written += 1
                    if count_msg is not None:
                        count_msg(topic)
            b_start = b_end

    # Emit /can/sync_audit summary message + embed JSON attachment
    audit_msg = audit_cls(
        primary_tier=align.primary_tier,
        completeness_status=comp.status,
        coverage_pct=float(comp.coverage_pct),
        global_median_offset_ms=float(align.global_median_offset_sec * 1000.0),
        estimated_drift_ppm=float(align.estimated_drift_ppm),
        residual_before_ms=float(align.residual_p95_p05_ms_before or 0.0),
        residual_after_ms=float(align.residual_p95_p05_ms_after or 0.0),
        matched_pairs=int(align.total_matched_pairs),
    )
    audit_ts = global_min_ns if global_min_ns is not None else audit_wall_ns
    writer.write_message("/can/sync_audit", audit_msg, log_time=audit_ts)
    if count_msg is not None:
        count_msg("/can/sync_audit")
    total_written += 1

    writer._writer.add_attachment(
        create_time=audit_wall_ns,
        log_time=audit_wall_ns,
        name="can_sync_audit.json",
        media_type="application/json",
        data=json.dumps(bundle.audit_dict, indent=2).encode("utf-8"),
    )
    return total_written
