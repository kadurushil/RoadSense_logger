"""Non-destructive CANedge2 MDF4 chunk discovery, continuity audit, and VCU.dbc loader."""

from __future__ import annotations

import os
import re
import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# pyrefly: ignore[missing-import]
import asammdf
import numpy as np

from . import load_can_config

CHUNK_NAME_RE = re.compile(r"^(?:([0-9A-Za-z]+)[/_])?([0-9]+)\.MF4$", re.IGNORECASE)
GROUP_COMMENT_RE = re.compile(r"message\s+([A-Za-z0-9_]+)\s+(0x[0-9A-Fa-f]+)", re.IGNORECASE)
GROUP_ACQ_ID_RE = re.compile(r"ID=(0x[0-9A-Fa-f]+)", re.IGNORECASE)
IDBLOCK_SIZE = 64
MIN_VALID_EPOCH_MS = 946_684_800_000


@dataclass(frozen=True)
class ChunkHeaderInfo:
    path: str
    filename: str
    size_bytes: int
    folder_id: str
    split_seq: Optional[int]
    magic: str
    is_finalized: bool
    hd_start_time_ns: int
    raw_min_sec: Optional[float] = None
    raw_max_sec: Optional[float] = None

    @property
    def hd_start_time_sec(self) -> float:
        return self.hd_start_time_ns / 1e9


@dataclass
class DecodedCanMessage:
    name: str
    can_id: Optional[int]
    raw_can_sec: np.ndarray  # float64 epoch seconds in raw CANedge clock
    signals: Dict[str, np.ndarray]  # clean signal_name -> 1D numpy array (float64 / int64 / str object)
    field_types: Dict[str, str]  # signal_name -> "double" | "int64" | "bool" | "string"

    @property
    def row_count(self) -> int:
        return int(len(self.raw_can_sec))


@dataclass
class CanLoadResult:
    session_dir: str
    dbc_path: str
    ingest_mode: str
    chunks: List[ChunkHeaderInfo] = field(default_factory=list)
    continuity_warnings: List[str] = field(default_factory=list)
    concat_hd_start_sec: float = 0.0
    messages: Dict[str, DecodedCanMessage] = field(default_factory=dict)

    @property
    def has_data(self) -> bool:
        return bool(self.chunks) and any(m.row_count > 0 for m in self.messages.values())


def parse_chunk_folder_and_seq(filename: str) -> Tuple[str, Optional[int]]:
    m = CHUNK_NAME_RE.match(os.path.basename(filename))
    if not m:
        return "", None
    folder = m.group(1) or ""
    seq = int(m.group(2)) if m.group(2) is not None else None
    return folder, seq


def inspect_mf4_header(mf4_path: str | Path) -> ChunkHeaderInfo:
    """Zero-dependency IDBLOCK + HDBLOCK reader matching Android Mdf4HeaderInspector.kt."""
    p = Path(mf4_path)
    size_bytes = p.stat().st_size
    folder_id, split_seq = parse_chunk_folder_and_seq(p.name)
    with open(p, "rb") as fp:
        raw = fp.read(256)
    if len(raw) < IDBLOCK_SIZE + 32:
        raise ValueError(f"MDF4 file too short ({len(raw)} bytes): {p}")
    magic = raw[0:8].decode("ascii", errors="replace")
    if magic not in ("MDF     ", "UnFinMF "):
        raise ValueError(f"Invalid MDF4 magic {magic!r}: {p}")
    if raw[IDBLOCK_SIZE:IDBLOCK_SIZE + 4] != b"##HD":
        raise ValueError(f"Missing ##HD block in {p}")
    link_count = struct.unpack_from("<Q", raw, IDBLOCK_SIZE + 16)[0]
    if link_count > 64:
        raise ValueError(f"Unreasonable ##HD link_count={link_count} in {p}")
    t_off = IDBLOCK_SIZE + 24 + int(link_count) * 8
    if t_off + 8 > len(raw):
        raise ValueError(f"HDBLOCK header exceeds read buffer in {p}")
    hd_ns = int(struct.unpack_from("<Q", raw, t_off)[0])
    if hd_ns // 1_000_000 < MIN_VALID_EPOCH_MS:
        raise ValueError(f"Invalid HDBLOCK timestamp {hd_ns} in {p}")
    return ChunkHeaderInfo(
        path=str(p),
        filename=p.name,
        size_bytes=size_bytes,
        folder_id=folder_id,
        split_seq=split_seq,
        magic=magic,
        is_finalized=(magic == "MDF     "),
        hd_start_time_ns=hd_ns,
    )


def discover_can_chunks(can_dir: str | Path) -> List[ChunkHeaderInfo]:
    d = Path(can_dir)
    if not d.is_dir():
        return []
    mf4_paths = sorted(
        [p for p in d.iterdir() if p.is_file() and p.suffix.lower() == ".mf4"],
        key=lambda p: p.name,
    )
    chunks: List[ChunkHeaderInfo] = []
    for p in mf4_paths:
        if p.stat().st_size < IDBLOCK_SIZE + 32:
            continue
        chunks.append(inspect_mf4_header(p))
    # Sort deterministically by (folder_id, split_seq, hd_start_time_ns, filename)
    chunks.sort(
        key=lambda c: (
            c.folder_id,
            c.split_seq if c.split_seq is not None else -1,
            c.hd_start_time_ns,
            c.filename,
        )
    )
    return chunks


def _probe_chunk_raw_span(mf4_path: str) -> Tuple[Optional[float], Optional[float]]:
    """Read raw min/max CAN sample epoch seconds from an individual MF4 without full DBC decode."""
    mdf = asammdf.MDF(mf4_path)
    try:
        hd_sec = float(mdf.header.start_time.timestamp())
        mins: List[float] = []
        maxs: List[float] = []
        for gi in range(len(mdf.groups)):
            ts = mdf.get_master(gi)
            if len(ts) > 0:
                mins.append(float(ts[0]))
                maxs.append(float(ts[-1]))
        if not mins:
            return None, None
        return hd_sec + min(mins), hd_sec + max(maxs)
    finally:
        mdf.close()


def check_chunk_continuity(
    chunks: List[ChunkHeaderInfo],
    max_jump_warn_ms: float = 250.0,
) -> Tuple[List[ChunkHeaderInfo], List[str]]:
    """Populate raw min/max CAN timestamps per chunk and flag any mid-folder step jumps or sequence gaps."""
    enriched: List[ChunkHeaderInfo] = []
    warnings: List[str] = []
    for ch in chunks:
        r_min, r_max = _probe_chunk_raw_span(ch.path)
        enriched.append(
            ChunkHeaderInfo(
                path=ch.path,
                filename=ch.filename,
                size_bytes=ch.size_bytes,
                folder_id=ch.folder_id,
                split_seq=ch.split_seq,
                magic=ch.magic,
                is_finalized=ch.is_finalized,
                hd_start_time_ns=ch.hd_start_time_ns,
                raw_min_sec=r_min,
                raw_max_sec=r_max,
            )
        )

    for prev, curr in zip(enriched, enriched[1:]):
        if prev.folder_id and curr.folder_id and prev.folder_id != curr.folder_id:
            continue  # Power-cycle boundary; new session folder naturally starts later
        if prev.split_seq is not None and curr.split_seq is not None and curr.split_seq != prev.split_seq + 1:
            warnings.append(
                f"Split sequence gap in folder {curr.folder_id}: {prev.filename} -> {curr.filename}"
            )
        if prev.hd_start_time_ns != curr.hd_start_time_ns:
            delta_hd_s = (curr.hd_start_time_ns - prev.hd_start_time_ns) / 1e9
            warnings.append(
                f"HDBLOCK clock jump within folder {curr.folder_id} between {prev.filename} and {curr.filename}: {delta_hd_s:+.3f}s"
            )
        if prev.raw_max_sec is not None and curr.raw_min_sec is not None:
            seam_gap_ms = (curr.raw_min_sec - prev.raw_max_sec) * 1000.0
            if seam_gap_ms < -5.0 or seam_gap_ms > max_jump_warn_ms:
                warnings.append(
                    f"Chunk seam discontinuity {prev.filename} -> {curr.filename}: {seam_gap_ms:+.1f} ms"
                )
    return enriched, warnings


def _extract_group_message_info(comment: str, acq_name: str, fallback_idx: int) -> Tuple[str, Optional[int]]:
    m = GROUP_COMMENT_RE.search(comment or "")
    if m:
        return m.group(1), int(m.group(2), 16)
    id_m = GROUP_ACQ_ID_RE.search(acq_name or "")
    can_id = int(id_m.group(1), 16) if id_m else None
    clean_acq = re.sub(r"[^A-Za-z0-9_]", "_", acq_name or f"CAN_Group_{fallback_idx}")
    return clean_acq, can_id


def _clean_signal_name(col_name: str, msg_name: str) -> str:
    # Strip asammdf prefix like "CAN1.MCU_Speed_Info." when duplicate signal names exist in DBC
    if "." in col_name:
        col_name = col_name.split(".")[-1]
    cleaned = re.sub(r"[^A-Za-z0-9_]", "_", col_name)
    if cleaned and cleaned[0].isdigit():
        cleaned = f"sig_{cleaned}"
    return cleaned or f"{msg_name}_sig"


def _normalize_column(series_arr: np.ndarray) -> Tuple[np.ndarray, str]:
    kind = series_arr.dtype.kind
    if kind == "b":
        return series_arr.astype(bool), "bool"
    if kind in ("i", "u"):
        return series_arr.astype(np.int64), "int64"
    if kind == "f":
        return np.nan_to_num(series_arr.astype(np.float64), nan=0.0, posinf=0.0, neginf=0.0), "double"
    # Object / bytes / string enum labels (from DBC VAL_ tables)
    decoded = np.array(
        [
            v.decode("utf-8", errors="replace") if isinstance(v, (bytes, bytearray)) else ("" if v is None else str(v))
            for v in series_arr
        ],
        dtype=object,
    )
    return decoded, "string"


def load_can_session(
    session_dir: str | Path,
    config: Optional[Dict[str, Any]] = None,
    config_path: Optional[str] = None,
    can_mode: Optional[str] = None,
) -> CanLoadResult:
    """
    Non-destructively load and DBC-decode all `.MF4` chunks inside `<session_dir>/can/`.
    Raw `.MF4` files are never modified.
    """
    cfg = config if config is not None else load_can_config(config_path)
    mode = (can_mode or cfg.get("ingest_mode", "all")).lower()
    if mode not in ("all", "whitelist"):
        raise ValueError(f"Unsupported CAN ingest_mode {mode!r}; expected 'all' or 'whitelist'")

    dbc_path = cfg["resolved_dbc_path"]
    if not os.path.isfile(dbc_path):
        raise FileNotFoundError(f"DBC file not found: {dbc_path}")

    whitelist = set(cfg.get("important_messages", []))
    excludes = set(cfg.get("exclude_messages", []))
    max_rate_hz = cfg.get("per_message_max_rate_hz")
    max_jump_warn_ms = float(cfg.get("alignment", {}).get("max_jump_warn_ms", 250.0))

    sess_path = Path(session_dir)
    can_dir = sess_path / "can"
    chunks = discover_can_chunks(can_dir)
    if not chunks:
        return CanLoadResult(session_dir=str(sess_path), dbc_path=dbc_path, ingest_mode=mode)

    enriched_chunks, warnings = check_chunk_continuity(chunks, max_jump_warn_ms=max_jump_warn_ms)
    file_paths = [c.path for c in enriched_chunks]

    mdf = asammdf.MDF.concatenate(file_paths, sync=True) if len(file_paths) > 1 else asammdf.MDF(file_paths[0])
    try:
        concat_hd_start_sec = float(mdf.header.start_time.timestamp())
        ext = mdf.extract_bus_logging({"CAN": [(dbc_path, 0)]})
    finally:
        mdf.close()

    messages: Dict[str, DecodedCanMessage] = {}
    try:
        for gi, grp in enumerate(ext.groups):
            cg = grp.channel_group
            msg_name, can_id = _extract_group_message_info(cg.comment, cg.acq_name, gi)
            if msg_name in excludes:
                continue
            if mode == "whitelist" and msg_name not in whitelist:
                continue

            df = ext.get_group(gi, raw=False, time_from_zero=False)
            if len(df) == 0:
                continue

            rel_ts = df.index.to_numpy(dtype=np.float64)
            raw_can_sec = concat_hd_start_sec + rel_ts

            # Optional rate-cap downsampling per message
            step_mask: Optional[np.ndarray] = None
            if max_rate_hz and float(max_rate_hz) > 0.0:
                min_dt = 1.0 / float(max_rate_hz)
                keep_idx: List[int] = []
                last_t = -1e18
                for idx_t, t_val in enumerate(raw_can_sec):
                    if t_val - last_t >= min_dt:
                        keep_idx.append(idx_t)
                        last_t = float(t_val)
                step_mask = np.array(keep_idx, dtype=np.int64)
                raw_can_sec = raw_can_sec[step_mask]

            signals: Dict[str, np.ndarray] = {}
            field_types: Dict[str, str] = {}
            used_names: Dict[str, int] = {}
            for col in df.columns:
                clean = _clean_signal_name(str(col), msg_name)
                if clean in used_names:
                    used_names[clean] += 1
                    clean = f"{clean}_{used_names[clean]}"
                else:
                    used_names[clean] = 0
                arr = df[col].to_numpy()
                if step_mask is not None:
                    arr = arr[step_mask]
                norm_arr, ftype = _normalize_column(arr)
                signals[clean] = norm_arr
                field_types[clean] = ftype

            # Merge rare duplicate groups with same message name if asammdf emits separate channels
            if msg_name in messages:
                existing = messages[msg_name]
                if existing.row_count == len(raw_can_sec) and np.allclose(existing.raw_can_sec, raw_can_sec):
                    existing.signals.update(signals)
                    existing.field_types.update(field_types)
                else:
                    alt_name = f"{msg_name}_{gi}"
                    messages[alt_name] = DecodedCanMessage(
                        name=alt_name,
                        can_id=can_id,
                        raw_can_sec=raw_can_sec,
                        signals=signals,
                        field_types=field_types,
                    )
            else:
                messages[msg_name] = DecodedCanMessage(
                    name=msg_name,
                    can_id=can_id,
                    raw_can_sec=raw_can_sec,
                    signals=signals,
                    field_types=field_types,
                )
    finally:
        ext.close()

    return CanLoadResult(
        session_dir=str(sess_path),
        dbc_path=dbc_path,
        ingest_mode=mode,
        chunks=enriched_chunks,
        continuity_warnings=warnings,
        concat_hd_start_sec=concat_hd_start_sec,
        messages=messages,
    )
