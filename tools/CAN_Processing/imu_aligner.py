"""Tier-2 passive IMU Gyro-Yaw CAN-to-UTC time alignment engine."""

from __future__ import annotations

import csv
import os
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Tuple

import numpy as np

from .completeness_auditor import _read_session_metadata
from .mf4_loader import CanLoadResult

if TYPE_CHECKING:
    from .time_aligner import CanAlignmentResult

GRID_DT_SEC = 0.02  # 50 Hz uniform comparison grid
DEFAULT_CUTOFF_HZ = 1.5
MIN_PHONE_YAW_STD_RADPS = 0.025
MIN_PHONE_YAW_PEAK_RADPS = 0.08


@dataclass(frozen=True)
class GyroCorrelationMatch:
    source_name: str
    sign: int
    lag_sec: float
    corr: float
    snr_sigma: float


@dataclass(frozen=True)
class PhoneImuYawSeries:
    utc_sec: np.ndarray
    yaw_radps: np.ndarray
    g_hat: np.ndarray


def lowpass_boxcar(signal: np.ndarray, sample_rate_hz: float, cutoff_hz: float = DEFAULT_CUTOFF_HZ) -> np.ndarray:
    """Zero-phase 2-pass symmetric moving-average low-pass filter using pure NumPy."""
    win = max(1, int(round(sample_rate_hz / max(cutoff_hz, 0.1))))
    if win <= 1 or len(signal) < win * 2:
        return signal.astype(np.float64, copy=True)
    kernel = np.ones(win, dtype=np.float64) / float(win)
    y = np.convolve(signal, kernel, mode="same")
    y = np.convolve(y, kernel, mode="same")
    return y


def extract_gravity_projected_yaw(
    accel_xyz: np.ndarray,
    gyro_xyz: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Estimate device gravity unit axis from median acceleration and project 3D gyro
    onto that axis to recover true chassis yaw rate regardless of phone mount angle.
    """
    if len(accel_xyz) == 0 or len(gyro_xyz) == 0:
        return np.empty(0, dtype=np.float64), np.array([0.0, 0.0, 1.0], dtype=np.float64)
    g_vec = np.median(accel_xyz, axis=0)
    g_norm = float(np.linalg.norm(g_vec))
    g_hat = (g_vec / g_norm) if g_norm > 1.0 else np.array([0.0, 0.0, 1.0], dtype=np.float64)
    return (gyro_xyz @ g_hat).astype(np.float64), g_hat


def load_phone_imu_yaw(session_dir: str, time_offset_ns: int) -> Optional[PhoneImuYawSeries]:
    """Load `imu/imu_frames.csv` and return UTC-timestamped gravity-projected yaw rate."""
    imu_csv = os.path.join(session_dir, "imu", "imu_frames.csv")
    if not os.path.isfile(imu_csv):
        return None

    t_list: List[float] = []
    accel_rows: List[Tuple[float, float, float]] = []
    gyro_rows: List[Tuple[float, float, float]] = []

    with open(imu_csv, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            mono_ns = int(row["elapsed_realtime_ns"])
            t_list.append((mono_ns + time_offset_ns) / 1e9)
            accel_rows.append((float(row["ax"]), float(row["ay"]), float(row["az"])))
            gyro_rows.append((float(row["gx"]), float(row["gy"]), float(row["gz"])))

    if len(t_list) < 50:
        return None

    t_arr = np.asarray(t_list, dtype=np.float64)
    a_mat = np.asarray(accel_rows, dtype=np.float64)
    g_mat = np.asarray(gyro_rows, dtype=np.float64)
    yaw, g_hat = extract_gravity_projected_yaw(a_mat, g_mat)
    return PhoneImuYawSeries(utc_sec=t_arr, yaw_radps=yaw, g_hat=g_hat)


def _zscore(x: np.ndarray) -> np.ndarray:
    xc = x - np.mean(x)
    std = float(np.std(xc))
    return xc / std if std > 1e-12 else np.zeros_like(xc)


def _passes_confidence_gate(corr: float, snr_sigma: float, is_rolling_window: bool = False) -> bool:
    """Tiered acceptance gate supporting both short 1-turn captures and noisier long drives."""
    min_snr_high = 3.0
    min_snr_std = 3.5 if is_rolling_window else 4.5
    return (corr >= 0.85 and snr_sigma >= min_snr_high) or (corr >= 0.65 and snr_sigma >= min_snr_std)


def fft_cross_correlate_lag(
    ref_sig: np.ndarray,
    can_sig: np.ndarray,
    dt_s: float = GRID_DT_SEC,
    max_lag_s: float = 120.0,
) -> Tuple[float, float, float]:
    """
    Fast O(N log N) normalized cross-correlation over [-max_lag_s, +max_lag_s] using zero-padded FFT,
    followed by exact overlap Pearson verification around the peak and parabolic sub-sample refinement.

    Convention: positive `tau` means `t_ref ≈ t_can + tau`.
    Returns `(best_lag_s, pearson_corr, snr_sigma)`.
    """
    r = _zscore(ref_sig)
    c = _zscore(can_sig)
    n = len(r)
    max_shift = min(int(round(max_lag_s / dt_s)), (n // 2) - 2)
    if n < 20 or max_shift < 2 or float(np.std(r)) < 1e-9 or float(np.std(c)) < 1e-9:
        return 0.0, 0.0, 0.0

    n_fft = 1 << (2 * n - 1).bit_length()
    fr = np.fft.rfft(r, n=n_fft)
    fc = np.fft.rfft(c, n=n_fft)
    # fr * conj(fc) peaks at +s where sum_n r[n + s] * c[n] is maximal
    raw_xcorr = np.fft.irfft(fr * np.conj(fc), n=n_fft)

    pos = raw_xcorr[: max_shift + 1]
    neg = raw_xcorr[n_fft - max_shift :]
    raw_slice = np.concatenate([neg, pos])
    lags = np.arange(-max_shift, max_shift + 1, dtype=np.int64)

    overlap_counts = n - np.abs(lags)
    approx_corr = raw_slice / np.maximum(overlap_counts, 1)

    peak_coarse_idx = int(np.argmax(approx_corr))
    med = float(np.median(approx_corr))
    mad = float(np.median(np.abs(approx_corr - med))) * 1.4826

    lo = max(0, peak_coarse_idx - 10)
    hi = min(len(lags), peak_coarse_idx + 11)
    best_corr = -2.0
    best_idx = peak_coarse_idx
    local_corrs: Dict[int, float] = {}
    for idx in range(lo, hi):
        s = int(lags[idx])
        if s > 0:
            a, b = r[s:], c[:-s]
        elif s < 0:
            a, b = r[:s], c[-s:]
        else:
            a, b = r, c
        an = _zscore(a)
        bn = _zscore(b)
        na = float(np.linalg.norm(an))
        nb = float(np.linalg.norm(bn))
        val = float(np.dot(an, bn) / (na * nb)) if (na > 1e-9 and nb > 1e-9) else 0.0
        local_corrs[idx] = val
        if val > best_corr:
            best_corr = val
            best_idx = idx

    snr = (best_corr - med) / max(mad, 1e-6)

    best_steps = float(lags[best_idx])
    if (best_idx - 1) in local_corrs and (best_idx + 1) in local_corrs:
        y0, y1, y2 = local_corrs[best_idx - 1], local_corrs[best_idx], local_corrs[best_idx + 1]
        denom = 2.0 * (y0 - 2.0 * y1 + y2)
        if abs(denom) > 1e-12:
            delta = (y0 - y2) / denom
            if abs(delta) <= 1.0:
                best_steps += delta

    return best_steps * dt_s, best_corr, snr


def _select_can_gyro_candidates(load_result: CanLoadResult) -> List[Tuple[str, np.ndarray, np.ndarray]]:
    """
    Extract prioritized CAN gyro signals:
    1. `TEL_IMU_Z_Axis_Raw.CNS_TEL_GYRO_Z_RAW` (unfiltered raw gyro, ~25 ms accuracy)
    2. `VCU_IMU_Processed_Angular_Msg.ETS_VCU_imuProc_yawRate` (VCU filtered yaw rate, ~100 ms filter lag)
    """
    candidates: List[Tuple[str, np.ndarray, np.ndarray]] = []
    if "TEL_IMU_Z_Axis_Raw" in load_result.messages:
        m = load_result.messages["TEL_IMU_Z_Axis_Raw"]
        if "CNS_TEL_GYRO_Z_RAW" in m.signals and m.row_count >= 50:
            val = m.signals["CNS_TEL_GYRO_Z_RAW"].astype(np.float64)
            if float(np.std(val)) > 1e-3:
                candidates.append(("TEL_IMU_Z_Axis_Raw", m.raw_can_sec, val))

    if "VCU_IMU_Processed_Angular_Msg" in load_result.messages:
        m = load_result.messages["VCU_IMU_Processed_Angular_Msg"]
        if "ETS_VCU_imuProc_yawRate" in m.signals and m.row_count >= 50:
            val = m.signals["ETS_VCU_imuProc_yawRate"].astype(np.float64)
            if float(np.std(val)) > 1e-4:
                candidates.append(("VCU_IMU_Processed_Angular_Msg", m.raw_can_sec, val))

    return candidates


def _evaluate_window_match(
    phone_filt: np.ndarray,
    can_filt: np.ndarray,
    dt_s: float,
    max_lag_s: float,
    forced_sign: Optional[int] = None,
) -> Optional[Tuple[int, float, float, float]]:
    """Test +1 / -1 mount polarity and return `(sign, lag_s, corr, snr)` if turning energy exists."""
    if float(np.std(phone_filt)) < MIN_PHONE_YAW_STD_RADPS or float(np.max(np.abs(phone_filt))) < MIN_PHONE_YAW_PEAK_RADPS:
        return None

    signs = (forced_sign,) if forced_sign in (-1, 1) else (1, -1)
    best: Optional[Tuple[int, float, float, float]] = None
    for sgn in signs:
        lag_s, corr, snr = fft_cross_correlate_lag(sgn * phone_filt, can_filt, dt_s=dt_s, max_lag_s=max_lag_s)
        if best is None or corr > best[2]:
            best = (sgn, lag_s, corr, snr)
    return best


def _mad_filter_linear(t_pts: np.ndarray, y_pts: np.ndarray) -> Tuple[np.ndarray, float, float]:
    """Apply MAD residual rejection and return `(inlier_mask, median_y, drift_ppm)`."""
    if len(y_pts) == 0:
        return np.zeros(0, dtype=bool), 0.0, 0.0
    if len(y_pts) == 1:
        return np.ones(1, dtype=bool), float(y_pts[0]), 0.0

    med = float(np.median(y_pts))
    mad = float(np.median(np.abs(y_pts - med))) * 1.4826
    gate = max(0.35, 3.5 * mad)
    inliers = np.abs(y_pts - med) <= gate
    if np.sum(inliers) < 2:
        inliers = np.ones_like(y_pts, dtype=bool)

    ti = t_pts[inliers]
    yi = y_pts[inliers]
    if len(ti) >= 2 and (ti[-1] - ti[0]) > 30.0:
        slope, _ = np.polyfit(ti - ti[0], yi, 1)
        drift_ppm = float(slope * 1e6)
    else:
        drift_ppm = 0.0
    return inliers, float(np.median(yi)), drift_ppm


def align_can_with_imu_tier2(
    load_result: CanLoadResult,
    session_meta: Optional[Dict[str, Any]] = None,
    time_offset_ns: Optional[int] = None,
    phone_imu: Optional[PhoneImuYawSeries] = None,
    max_search_lag_s: float = 120.0,
    window_sec: float = 240.0,
    step_sec: float = 90.0,
    cutoff_hz: float = DEFAULT_CUTOFF_HZ,
) -> Optional[CanAlignmentResult]:
    """
    Attempt Tier-2 Gyro-Yaw cross-correlation alignment (`TIER2_IMU_GYRO`).
    Searches around both `0.0 s` (NTP assumption) and metadata `clockOffsetMs` so stale
    metadata never hides a real peak. Returns None if phone/CAN gyro are missing or no turns occurred.
    """
    from .time_aligner import CanAlignmentResult, WindowAnchor

    if not load_result.messages:
        return None

    meta = session_meta if session_meta is not None else _read_session_metadata(load_result.session_dir)
    if time_offset_ns is None:
        start_wall_ms = int(meta.get("startTimeWallMs", 0))
        start_mono_ns = int(meta.get("startTimeMonotonicNs", 0))
        time_offset_ns = (start_wall_ms * 1_000_000) - start_mono_ns if (start_wall_ms and start_mono_ns) else 0

    if phone_imu is None:
        phone_imu = load_phone_imu_yaw(load_result.session_dir, time_offset_ns=time_offset_ns)
    if phone_imu is None or len(phone_imu.utc_sec) < 50:
        return None

    can_candidates = _select_can_gyro_candidates(load_result)
    if not can_candidates:
        return None

    meta_offset_sec = float((meta.get("can") or {}).get("clockOffsetMs", 0) or 0.0) / 1000.0
    anchor_guesses = [0.0]
    if abs(meta_offset_sec) > 5.0:
        anchor_guesses.append(meta_offset_sec)

    p_min_s, p_max_s = float(phone_imu.utc_sec[0]), float(phone_imu.utc_sec[-1])

    chosen: Optional[Tuple[GyroCorrelationMatch, str, np.ndarray, np.ndarray]] = None
    for src_name, can_raw_sec, can_val in can_candidates:
        best_for_src: Optional[GyroCorrelationMatch] = None
        for guess_s in anchor_guesses:
            c_shifted = can_raw_sec + guess_s
            ov_start = max(p_min_s, float(c_shifted[0])) + 2.0
            ov_end = min(p_max_s, float(c_shifted[-1])) - 2.0
            if (ov_end - ov_start) < 25.0:
                continue

            grid_t = np.arange(ov_start, ov_end, GRID_DT_SEC, dtype=np.float64)
            p_grid = lowpass_boxcar(np.interp(grid_t, phone_imu.utc_sec, phone_imu.yaw_radps), 1.0 / GRID_DT_SEC, cutoff_hz)
            c_grid = lowpass_boxcar(np.interp(grid_t, c_shifted, can_val), 1.0 / GRID_DT_SEC, cutoff_hz)

            eff_lag = min(max_search_lag_s, 0.70 * (ov_end - ov_start))
            res = _evaluate_window_match(p_grid, c_grid, dt_s=GRID_DT_SEC, max_lag_s=eff_lag)
            if res is None:
                continue
            sgn, delta_lag_s, corr, snr = res
            if _passes_confidence_gate(corr, snr, is_rolling_window=False):
                cand_match = GyroCorrelationMatch(
                    source_name=src_name,
                    sign=sgn,
                    lag_sec=guess_s + delta_lag_s,
                    corr=corr,
                    snr_sigma=snr,
                )
                if best_for_src is None or cand_match.corr > best_for_src.corr:
                    best_for_src = cand_match

        if best_for_src is not None:
            chosen = (best_for_src, src_name, can_raw_sec, can_val)
            break

    if chosen is None:
        return None

    global_match, src_name, can_raw_sec, can_val = chosen
    coarse_offset_s = global_match.lag_sec
    sign = global_match.sign

    c_aligned_sec = can_raw_sec + coarse_offset_s
    ov_start = max(p_min_s, float(c_aligned_sec[0])) + 2.0
    ov_end = min(p_max_s, float(c_aligned_sec[-1])) - 2.0
    grid_t = np.arange(ov_start, ov_end, GRID_DT_SEC, dtype=np.float64)
    p_filt = lowpass_boxcar(np.interp(grid_t, phone_imu.utc_sec, sign * phone_imu.yaw_radps), 1.0 / GRID_DT_SEC, cutoff_hz)
    c_filt = lowpass_boxcar(np.interp(grid_t, c_aligned_sec, can_val), 1.0 / GRID_DT_SEC, cutoff_hz)

    windows: List[WindowAnchor] = []
    dur_s = ov_end - ov_start
    win_samples = int(round(window_sec / GRID_DT_SEC))
    step_samples = int(round(step_sec / GRID_DT_SEC))

    if dur_s >= max(window_sec * 1.4, 300.0) and len(grid_t) > win_samples:
        idx_start = 0
        w_idx = 0
        win_lag_search = min(25.0, 0.25 * window_sec)
        while idx_start + win_samples <= len(grid_t):
            sl = slice(idx_start, idx_start + win_samples)
            w_res = _evaluate_window_match(p_filt[sl], c_filt[sl], dt_s=GRID_DT_SEC, max_lag_s=win_lag_search, forced_sign=1)
            if w_res is not None:
                _, d_lag, w_corr, w_snr = w_res
                if abs(d_lag) <= 2.5 and _passes_confidence_gate(w_corr, w_snr, is_rolling_window=True):
                    t0_w = float(grid_t[idx_start] - coarse_offset_s)
                    t1_w = float(grid_t[idx_start + win_samples - 1] - coarse_offset_s)
                    windows.append(
                        WindowAnchor(
                            window_index=w_idx,
                            t_start_raw_sec=t0_w,
                            t_end_raw_sec=t1_w,
                            t_center_raw_sec=0.5 * (t0_w + t1_w),
                            offset_sec=coarse_offset_s + d_lag,
                            tier=f"TIER2_{src_name}",
                            pair_count=win_samples,
                            residual_p95_p05_ms=round(w_corr, 4),
                        )
                    )
                    w_idx += 1
            idx_start += step_samples

    if len(windows) >= 2:
        t_centers = np.array([w.t_center_raw_sec for w in windows], dtype=np.float64)
        offs = np.array([w.offset_sec for w in windows], dtype=np.float64)
        inliers, med_off, drift_ppm = _mad_filter_linear(t_centers, offs)
        filtered_windows = [w for w, keep in zip(windows, inliers) if keep]
        if len(filtered_windows) >= 2:
            t_anc = np.array([w.t_center_raw_sec for w in filtered_windows], dtype=np.float64)
            y_anc = np.array([w.offset_sec for w in filtered_windows], dtype=np.float64)
            slope, intercept = np.polyfit(t_anc - t_anc[0], y_anc, 1)
            y_smooth = intercept + slope * (t_anc - t_anc[0])
            return CanAlignmentResult(
                primary_tier="TIER2_IMU_GYRO",
                window_sec=window_sec,
                step_sec=step_sec,
                anchor_t_raw_sec=t_anc,
                anchor_offset_sec=y_smooth,
                windows=filtered_windows,
                total_matched_pairs=sum(w.pair_count for w in filtered_windows),
                global_median_offset_sec=med_off,
                estimated_drift_ppm=drift_ppm,
            )

    mid_raw = float(np.mean(grid_t) - coarse_offset_s)
    return CanAlignmentResult(
        primary_tier="TIER2_IMU_GYRO",
        window_sec=window_sec,
        step_sec=step_sec,
        anchor_t_raw_sec=np.array([mid_raw], dtype=np.float64),
        anchor_offset_sec=np.array([coarse_offset_s], dtype=np.float64),
        windows=[
            WindowAnchor(
                window_index=0,
                t_start_raw_sec=float(grid_t[0] - coarse_offset_s),
                t_end_raw_sec=float(grid_t[-1] - coarse_offset_s),
                t_center_raw_sec=mid_raw,
                offset_sec=coarse_offset_s,
                tier=f"TIER2_{src_name}",
                pair_count=len(grid_t),
                residual_p95_p05_ms=round(global_match.corr, 4),
            )
        ],
        total_matched_pairs=len(grid_t),
        global_median_offset_sec=coarse_offset_s,
        estimated_drift_ppm=0.0,
    )
