"""Rolling 30-60s windowed 3-tier CAN-to-UTC time alignment engine (Stage 4)."""

from __future__ import annotations

import struct
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from .completeness_auditor import _read_session_metadata
from .mf4_loader import CanLoadResult

ROAD_MAGIC = b"ROAD"
ROAD_HEADER_SIZE = 24
TI_MAGIC = b"\x02\x01\x04\x03\x06\x05\x08\x07"
TI_HEADER_SIZE = 40


@dataclass(frozen=True)
class WindowAnchor:
    window_index: int
    t_start_raw_sec: float
    t_end_raw_sec: float
    t_center_raw_sec: float
    offset_sec: float
    tier: str
    pair_count: int
    residual_p95_p05_ms: float

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class CanAlignmentResult:
    primary_tier: str  # "TIER1_RADAR_FRAME_ID" | "TIER2_IMU_GYRO" | "TIER3_METADATA" | "NONE"
    window_sec: float
    step_sec: float
    anchor_t_raw_sec: np.ndarray
    anchor_offset_sec: np.ndarray
    windows: List[WindowAnchor] = field(default_factory=list)
    total_matched_pairs: int = 0
    global_median_offset_sec: float = 0.0
    estimated_drift_ppm: float = 0.0
    residual_p95_p05_ms_before: Optional[float] = None
    residual_p95_p05_ms_after: Optional[float] = None

    def map_raw_sec_to_utc_ns(self, raw_can_sec: np.ndarray) -> np.ndarray:
        if len(raw_can_sec) == 0:
            return np.empty(0, dtype=np.int64)
        if len(self.anchor_t_raw_sec) == 0:
            return np.round(raw_can_sec * 1_000_000_000.0).astype(np.int64)
        if len(self.anchor_t_raw_sec) == 1:
            offsets = np.full(len(raw_can_sec), float(self.anchor_offset_sec[0]), dtype=np.float64)
        else:
            # Piecewise linear across window centers; extrapolate boundary slopes gently
            offsets = np.interp(raw_can_sec, self.anchor_t_raw_sec, self.anchor_offset_sec)
            if len(self.anchor_t_raw_sec) >= 2:
                left_mask = raw_can_sec < self.anchor_t_raw_sec[0]
                if np.any(left_mask):
                    dt_l = self.anchor_t_raw_sec[1] - self.anchor_t_raw_sec[0]
                    if dt_l > 1e-6:
                        slope_l = (self.anchor_offset_sec[1] - self.anchor_offset_sec[0]) / dt_l
                        offsets[left_mask] = self.anchor_offset_sec[0] + slope_l * (raw_can_sec[left_mask] - self.anchor_t_raw_sec[0])
                right_mask = raw_can_sec > self.anchor_t_raw_sec[-1]
                if np.any(right_mask):
                    dt_r = self.anchor_t_raw_sec[-1] - self.anchor_t_raw_sec[-2]
                    if dt_r > 1e-6:
                        slope_r = (self.anchor_offset_sec[-1] - self.anchor_offset_sec[-2]) / dt_r
                        offsets[right_mask] = self.anchor_offset_sec[-1] + slope_r * (raw_can_sec[right_mask] - self.anchor_t_raw_sec[-1])
        utc_sec = raw_can_sec + offsets
        return np.round(utc_sec * 1_000_000_000.0).astype(np.int64)

    def to_audit_dict(self) -> Dict[str, Any]:
        return {
            "primary_tier": self.primary_tier,
            "window_sec": self.window_sec,
            "step_sec": self.step_sec,
            "total_matched_pairs": self.total_matched_pairs,
            "global_median_offset_sec": round(self.global_median_offset_sec, 6),
            "estimated_drift_ppm": round(self.estimated_drift_ppm, 2),
            "residual_p95_p05_ms_before": round(self.residual_p95_p05_ms_before, 3) if self.residual_p95_p05_ms_before is not None else None,
            "residual_p95_p05_ms_after": round(self.residual_p95_p05_ms_after, 3) if self.residual_p95_p05_ms_after is not None else None,
            "window_count": len(self.windows),
            "windows": [w.to_dict() for w in self.windows],
        }


def extract_radar_uart_frame_anchors(
    radar_bin_path: str | Path,
    time_offset_ns: int,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Fast header-only scanner for `radar_frames.bin` returning `(uart_utc_sec, hw_frame_num_16bit)`.
    Raw binary file is opened read-only and never modified.
    """
    p = Path(radar_bin_path)
    if not p.is_file() or p.stat().st_size < ROAD_HEADER_SIZE + TI_HEADER_SIZE:
        return np.empty(0, dtype=np.float64), np.empty(0, dtype=np.int64)

    data = p.read_bytes()
    total_len = len(data)
    offset = 0
    utc_secs: List[float] = []
    frame_ids: List[int] = []

    while offset + ROAD_HEADER_SIZE <= total_len:
        if data[offset : offset + 4] != ROAD_MAGIC:
            nxt = data.find(ROAD_MAGIC, offset + 1)
            if nxt == -1 or nxt + ROAD_HEADER_SIZE > total_len:
                break
            offset = nxt
        _, mono_ns, _, pkt_len = struct.unpack_from(">4sQQI", data, offset)
        frame_off = offset + ROAD_HEADER_SIZE
        if frame_off + pkt_len > total_len:
            break
        offset = frame_off + pkt_len
        if pkt_len < TI_HEADER_SIZE or data[frame_off : frame_off + 8] != TI_MAGIC:
            continue
        _, _, _, _, frame_num, _, _, _, _ = struct.unpack_from("<8sIIIIIIII", data, frame_off)
        utc_ns = int(mono_ns) + int(time_offset_ns)
        utc_secs.append(utc_ns / 1_000_000_000.0)
        frame_ids.append(int(frame_num) & 0xFFFF)

    return np.asarray(utc_secs, dtype=np.float64), np.asarray(frame_ids, dtype=np.int64)


def match_radar_frame_id_pairs(
    can_raw_sec: np.ndarray,
    can_id16: np.ndarray,
    uart_utc_sec: np.ndarray,
    uart_id16: np.ndarray,
    match_tolerance_sec: float = 5.0,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Match 16-bit `FrameNumber_Act` (CAN ID 213) to `(hw_frame_num & 0xFFFF)` (UART)
    safely across 16-bit counter wraparounds (~54.6 min @ 20 Hz).

    Returns paired `(matched_can_raw_sec, matched_offset_sec)` where `offset = uart_utc - can_raw`.
    """
    if len(can_raw_sec) == 0 or len(uart_utc_sec) == 0:
        return np.empty(0, dtype=np.float64), np.empty(0, dtype=np.float64)

    # Index CAN timestamps by 16-bit frame ID
    can_by_id: Dict[int, List[float]] = {}
    for t_c, fid in zip(can_raw_sec, can_id16):
        can_by_id.setdefault(int(fid) & 0xFFFF, []).append(float(t_c))

    # 1. Estimate coarse offset via 1-second histogram mode across all candidate ID pairs
    candidate_offsets: List[float] = []
    # Stride cleanly on huge drives for O(N) coarse histogram
    step_u = max(1, len(uart_utc_sec) // 8000)
    for idx_u in range(0, len(uart_utc_sec), step_u):
        fid = int(uart_id16[idx_u]) & 0xFFFF
        c_list = can_by_id.get(fid)
        if c_list:
            t_u = float(uart_utc_sec[idx_u])
            for t_c in c_list:
                candidate_offsets.append(t_u - t_c)

    if not candidate_offsets:
        return np.empty(0, dtype=np.float64), np.empty(0, dtype=np.float64)

    # 2. Multi-pass mode matching so rare multi-second mid-drive NTP jumps (> +/-5s)
    # match all segments cleanly rather than dropping the post-jump half.
    paired_can: List[float] = []
    paired_off: List[float] = []
    used_u_indices: set[int] = set()
    used_c_keys: set[Tuple[int, float]] = set()

    for _pass_idx in range(3):
        rem_candidates: List[float] = []
        u_indices = [i for i in range(len(uart_utc_sec)) if i not in used_u_indices]
        if len(u_indices) < 10 and _pass_idx > 0:
            break
        step_u = max(1, len(u_indices) // 8000)
        for idx_u in u_indices[::step_u]:
            fid = int(uart_id16[idx_u]) & 0xFFFF
            c_list = can_by_id.get(fid)
            if c_list:
                t_u = float(uart_utc_sec[idx_u])
                for t_c in c_list:
                    if (fid, t_c) not in used_c_keys:
                        rem_candidates.append(t_u - t_c)
        if not rem_candidates:
            break

        cand_arr = np.asarray(rem_candidates, dtype=np.float64)
        bins = np.round(cand_arr).astype(np.int64)
        uniq_bins, counts = np.unique(bins, return_counts=True)
        peak_idx = int(np.argmax(counts))
        if _pass_idx > 0 and int(counts[peak_idx]) < 10:
            break
        peak_bin = float(uniq_bins[peak_idx])
        in_peak = cand_arr[np.abs(cand_arr - peak_bin) <= 3.0]
        coarse_offset = float(np.median(in_peak)) if len(in_peak) > 0 else peak_bin

        matched_this_pass = 0
        for idx_u in u_indices:
            fid = int(uart_id16[idx_u]) & 0xFFFF
            c_list = can_by_id.get(fid)
            if not c_list:
                continue
            t_u = float(uart_utc_sec[idx_u])
            best_tc: Optional[float] = None
            best_err = match_tolerance_sec
            for t_c in c_list:
                if (fid, t_c) in used_c_keys:
                    continue
                err = abs((t_u - t_c) - coarse_offset)
                if err <= best_err:
                    best_err = err
                    best_tc = t_c
            if best_tc is not None:
                used_u_indices.add(idx_u)
                used_c_keys.add((fid, best_tc))
                paired_can.append(best_tc)
                paired_off.append(t_u - best_tc)
                matched_this_pass += 1
        if matched_this_pass == 0:
            break

    if not paired_can:
        return np.empty(0, dtype=np.float64), np.empty(0, dtype=np.float64)

    p_can = np.asarray(paired_can, dtype=np.float64)
    p_off = np.asarray(paired_off, dtype=np.float64)
    order = np.argsort(p_can)
    return p_can[order], p_off[order]


def _robust_median_and_spread_ms(offsets_sec: np.ndarray) -> Tuple[float, float]:
    med = float(np.median(offsets_sec))
    mad = float(np.median(np.abs(offsets_sec - med)))
    tol = max(0.050, 3.5 * 1.4826 * mad)
    inliers = offsets_sec[np.abs(offsets_sec - med) <= tol]
    if len(inliers) == 0:
        inliers = offsets_sec
    med_clean = float(np.median(inliers))
    if len(inliers) >= 2:
        spread_ms = (float(np.percentile(inliers, 95)) - float(np.percentile(inliers, 5))) * 1000.0
    else:
        spread_ms = 0.0
    return med_clean, spread_ms


def _build_rolling_tier1_windows(
    matched_can_sec: np.ndarray,
    matched_off_sec: np.ndarray,
    raw_min_sec: float,
    raw_max_sec: float,
    window_sec: float = 45.0,
    step_sec: float = 30.0,
    min_pairs: int = 10,
) -> List[WindowAnchor]:
    windows: List[WindowAnchor] = []
    span = max(0.0, raw_max_sec - raw_min_sec)
    if span <= window_sec:
        centers_starts = [raw_min_sec]
    else:
        centers_starts = list(np.arange(raw_min_sec, raw_max_sec - 0.25 * window_sec, step_sec))
        if not centers_starts or (raw_max_sec - centers_starts[-1]) > window_sec * 0.6:
            centers_starts.append(max(raw_min_sec, raw_max_sec - window_sec))

    for w_idx, w_start in enumerate(centers_starts):
        w_end = min(raw_max_sec + 1e-6, w_start + window_sec)
        mask = (matched_can_sec >= w_start) & (matched_can_sec <= w_end)
        sub_can = matched_can_sec[mask]
        sub_off = matched_off_sec[mask]
        if len(sub_off) < min_pairs:
            continue
        med_off, spread_ms = _robust_median_and_spread_ms(sub_off)
        t_center = float(np.median(sub_can))
        windows.append(
            WindowAnchor(
                window_index=w_idx,
                t_start_raw_sec=round(float(w_start), 3),
                t_end_raw_sec=round(float(w_end), 3),
                t_center_raw_sec=round(t_center, 3),
                offset_sec=round(med_off, 6),
                tier="TIER1_RADAR_FRAME_ID",
                pair_count=int(len(sub_off)),
                residual_p95_p05_ms=round(spread_ms, 3),
            )
        )
    return windows


def align_can_timeline(
    load_result: CanLoadResult,
    session_meta: Optional[Dict[str, Any]] = None,
    time_offset_ns: Optional[int] = None,
    radar_bin_path: Optional[str | Path] = None,
    radar_anchors: Optional[Tuple[np.ndarray, np.ndarray]] = None,
    window_sec: float = 45.0,
    step_sec: float = 30.0,
    min_tier1_pairs_per_window: int = 10,
    enable_tier2: bool = True,
    tier2_window_sec: float = 240.0,
    tier2_step_sec: float = 90.0,
    tier2_max_search_lag_s: float = 120.0,
) -> CanAlignmentResult:
    """
    Compute rolling 30-60s alignment from raw CAN time onto RoadSense primary UTC nanoseconds.
    Tier 1: Hardware Frame-ID Lock (CAN RADAR_Debug_Info.FrameNumber_Act <-> UART hw_frame_num).
    Tier 2: Passive Phone-vs-CAN Gyro-Yaw Cross-Correlation (when Radar is absent).
    Tier 3: session_metadata.json can.clockOffsetMs fallback (or 0.0s when NTP-synced).
    """
    meta = session_meta if session_meta is not None else _read_session_metadata(load_result.session_dir)
    if not load_result.has_data:
        return CanAlignmentResult(
            primary_tier="NONE",
            window_sec=window_sec,
            step_sec=step_sec,
            anchor_t_raw_sec=np.empty(0, dtype=np.float64),
            anchor_offset_sec=np.empty(0, dtype=np.float64),
        )

    raw_min = min(float(m.raw_can_sec[0]) for m in load_result.messages.values() if m.row_count > 0)
    raw_max = max(float(m.raw_can_sec[-1]) for m in load_result.messages.values() if m.row_count > 0)

    if time_offset_ns is None:
        start_wall_ms = int(meta.get("startTimeWallMs", 0))
        start_mono_ns = int(meta.get("startTimeMonotonicNs", 0))
        time_offset_ns = (start_wall_ms * 1_000_000) - start_mono_ns if (start_wall_ms and start_mono_ns) else 0

    # Locate UART radar_frames.bin if not passed explicitly
    if radar_anchors is not None:
        uart_utc_sec, uart_id16 = radar_anchors
    else:
        if radar_bin_path is None:
            sess_p = Path(load_result.session_dir)
            cand1 = sess_p / "radar" / "radar_frames.bin"
            cand2 = sess_p / "radar_frames.bin"
            radar_bin_path = cand1 if cand1.is_file() else cand2
        uart_utc_sec, uart_id16 = extract_radar_uart_frame_anchors(radar_bin_path, time_offset_ns)

    r_dbg = load_result.messages.get("RADAR_Debug_Info")
    if r_dbg is not None and "FrameNumber_Act" in r_dbg.signals and len(uart_utc_sec) > 0:
        can_id16 = np.asarray(r_dbg.signals["FrameNumber_Act"], dtype=np.int64) & 0xFFFF
        matched_can, matched_off = match_radar_frame_id_pairs(
            r_dbg.raw_can_sec, can_id16, uart_utc_sec, uart_id16
        )
        if len(matched_can) >= min_tier1_pairs_per_window:
            _, spread_before_ms = _robust_median_and_spread_ms(matched_off)
            windows = _build_rolling_tier1_windows(
                matched_can,
                matched_off,
                raw_min_sec=raw_min,
                raw_max_sec=raw_max,
                window_sec=window_sec,
                step_sec=step_sec,
                min_pairs=min_tier1_pairs_per_window,
            )
            if windows:
                anchor_t = np.array([w.t_center_raw_sec for w in windows], dtype=np.float64)
                anchor_off = np.array([w.offset_sec for w in windows], dtype=np.float64)
                res = CanAlignmentResult(
                    primary_tier="TIER1_RADAR_FRAME_ID",
                    window_sec=window_sec,
                    step_sec=step_sec,
                    anchor_t_raw_sec=anchor_t,
                    anchor_offset_sec=anchor_off,
                    windows=windows,
                    total_matched_pairs=int(len(matched_can)),
                    global_median_offset_sec=float(np.median(matched_off)),
                    residual_p95_p05_ms_before=spread_before_ms,
                )
                # Measure post-alignment residual across all matched pairs
                aligned_ns = res.map_raw_sec_to_utc_ns(matched_can)
                true_uart_ns = np.round((matched_can + matched_off) * 1_000_000_000.0).astype(np.int64)
                err_ms = (true_uart_ns - aligned_ns) / 1_000_000.0
                res.residual_p95_p05_ms_after = float(np.percentile(err_ms, 95) - np.percentile(err_ms, 5))
                if len(anchor_t) >= 2 and (anchor_t[-1] - anchor_t[0]) > 1.0:
                    slope, _ = np.polyfit(anchor_t, anchor_off, 1)
                    res.estimated_drift_ppm = float(slope * 1e6)
                return res

    # Tier 2 Fallback: Passive IMU Gyro-Yaw Cross-Correlation (no Radar required)
    if enable_tier2:
        from .imu_aligner import align_can_with_imu_tier2

        tier2_res = align_can_with_imu_tier2(
            load_result=load_result,
            session_meta=meta,
            time_offset_ns=time_offset_ns,
            max_search_lag_s=tier2_max_search_lag_s,
            window_sec=tier2_window_sec,
            step_sec=tier2_step_sec,
        )
        if tier2_res is not None:
            return tier2_res

    # Tier 3 Fallback: session_metadata.json can.clockOffsetMs (or 0.0s if calibrated)
    meta_off_ms = float((meta.get("can") or {}).get("clockOffsetMs", 0.0) or 0.0)
    fallback_off_sec = meta_off_ms / 1000.0
    t_mid = 0.5 * (raw_min + raw_max)
    win = WindowAnchor(
        window_index=0,
        t_start_raw_sec=round(raw_min, 3),
        t_end_raw_sec=round(raw_max, 3),
        t_center_raw_sec=round(t_mid, 3),
        offset_sec=round(fallback_off_sec, 6),
        tier="TIER3_METADATA",
        pair_count=0,
        residual_p95_p05_ms=0.0,
    )
    return CanAlignmentResult(
        primary_tier="TIER3_METADATA",
        window_sec=window_sec,
        step_sec=step_sec,
        anchor_t_raw_sec=np.array([t_mid], dtype=np.float64),
        anchor_offset_sec=np.array([fallback_off_sec], dtype=np.float64),
        windows=[win],
        total_matched_pairs=0,
        global_median_offset_sec=fallback_off_sec,
    )
