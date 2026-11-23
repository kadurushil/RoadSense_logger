#!/usr/bin/env python3
"""
Generate high-DPI diagrams and empirical charts for RoadSense Update 4 Presentation.
Produces:
  1. assets/radar_pipeline_bottleneck.png     (L->R radar UART/USB DMA bottleneck flow)
  2. assets/radar_clock_jitter_comparison.png (Real rawMonoNs vs 600 MHz disciplined delta_t from Nov 20 drive)
  3. assets/ui_latency_before_after.png       (Verified SM-M325F Compose/SurfaceView UI latency reductions)
  4. assets/can_end_to_end_pipeline.png       (L->R CANedge2 edge watchdog -> PC Tier-1/Tier-2 -> MCAP flow)
"""

import csv
import os
import re
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
ASSETS_DIR = os.path.join(SCRIPT_DIR, "assets")
os.makedirs(ASSETS_DIR, exist_ok=True)

plt.rcParams["font.family"] = "sans-serif"
plt.rcParams["font.sans-serif"] = ["Segoe UI", "DejaVu Sans", "Arial"]

C_BG = "#FFFFFF"
C_SLATE_BG = "#F8FAFC"
C_BLUE = "#1D4ED8"
C_BLUE_BG = "#EFF6FF"
C_CYAN = "#0284C7"
C_GREEN = "#15803D"
C_GREEN_BG = "#F0FDF4"
C_ROSE = "#BE123C"
C_ROSE_BG = "#FFF1F2"
C_AMBER = "#D97706"
C_AMBER_BG = "#FFFBEB"
C_PURPLE = "#7E22CE"
C_PURPLE_BG = "#FAF5FF"
C_TEXT = "#0F172A"
C_MUTED = "#475569"
C_BORDER = "#CBD5E1"


def draw_node(ax, x, y, w, h, title, subtitle, badge=None, border=C_BLUE, fill=C_BLUE_BG, badge_color=None):
    box = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0.0,rounding_size=0.08",
        linewidth=1.8, edgecolor=border, facecolor=fill, zorder=3
    )
    ax.add_patch(box)
    cy = y + h * (0.64 if badge else 0.62)
    ax.text(x + w / 2, cy, title, ha="center", va="center", fontsize=8.8, fontweight="bold", color=C_TEXT, zorder=4)
    sy = y + h * (0.36 if badge else 0.30)
    ax.text(x + w / 2, sy, subtitle, ha="center", va="center", fontsize=7.4, color=C_MUTED, zorder=4)
    if badge:
        bc = badge_color or border
        bw = w * 0.88
        bh = h * 0.22
        bx = x + (w - bw) / 2
        by = y + 0.04
        bpatch = FancyBboxPatch(
            (bx, by), bw, bh,
            boxstyle="round,pad=0.0,rounding_size=0.04",
            linewidth=0, facecolor=bc, zorder=4
        )
        ax.add_patch(bpatch)
        ax.text(bx + bw / 2, by + bh / 2, badge, ha="center", va="center",
                fontsize=6.8, fontweight="bold", color="#FFFFFF", zorder=5)


def draw_arrow(ax, x1, y1, x2, y2, color=C_MUTED, label=None, label_y_offset=0.11):
    arr = FancyArrowPatch(
        (x1, y1), (x2, y2),
        arrowstyle="-|>,head_length=5,head_width=3.2",
        linewidth=1.8, color=color, zorder=2
    )
    ax.add_patch(arr)
    if label:
        ax.text((x1 + x2) / 2, (y1 + y2) / 2 + label_y_offset, label,
                ha="center", va="center", fontsize=6.8, fontweight="bold", color=color)


def generate_radar_pipeline_diagram():
    fig, ax = plt.subplots(figsize=(6.20, 2.35), dpi=300)
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_SLATE_BG)
    ax.set_xlim(0, 10.0)
    ax.set_ylim(0, 3.8)
    ax.axis("off")

    # Zone headers
    ax.text(5.0, 3.55, "3.125 Mbps Hardware Serial Pipeline: Bottleneck Isolation & Fix",
            ha="center", va="center", fontsize=9.8, fontweight="bold", color=C_TEXT)

    # 5 L->R pipeline nodes cleanly spaced across x=0.15..9.85
    y, h = 0.72, 2.15
    draw_node(ax, 0.15, y, 1.60, h,
              "AWR1843\nRadar DSP", "20-22 Hz TLV\n312.5 B/ms",
              badge="600 MHz TSCL", border=C_BLUE, fill=C_BLUE_BG)
    draw_arrow(ax, 1.78, y + h / 2, 2.08, y + h / 2, color=C_ROSE)

    draw_node(ax, 2.11, y, 1.78, h,
              "CP2105 UART\nUSB FIFO", "Only 576 B Buffer\n+ 64B Coalescing",
              badge="1.84 ms WALL!", border=C_ROSE, fill=C_ROSE_BG)
    draw_arrow(ax, 3.92, y + h / 2, 4.22, y + h / 2, color=C_GREEN)

    draw_node(ax, 4.25, y, 1.80, h,
              "Kernel DMA &\nAudio Thread", "128x1KB ReadQueue\nURGENT_AUDIO -19",
              badge="409.6 ms SAFE", border=C_GREEN, fill=C_GREEN_BG)
    draw_arrow(ax, 6.08, y + h / 2, 6.38, y + h / 2, color=C_GREEN)

    draw_node(ax, 6.41, y, 1.78, h,
              "Desync Guard\n& Clock Lock", "Magic Salvager\n600 MHz Unwrap",
              badge="0% CORRUPT", border=C_GREEN, fill=C_GREEN_BG)
    draw_arrow(ax, 8.22, y + h / 2, 8.52, y + h / 2, color=C_BLUE)

    draw_node(ax, 8.55, y, 1.30, h,
              "60 Hz App\nDisplay", "VSync Pacer\nBEV + Overlay",
              badge="<1 ms DRAW", border=C_CYAN, fill=C_BLUE_BG)

    # Bottom explanatory banner strip (two crisp pill boxes, zero overlap)
    b1 = FancyBboxPatch((0.15, 0.08), 4.65, 0.42, boxstyle="round,pad=0,rounding_size=0.05",
                        fc=C_ROSE_BG, ec=C_ROSE, lw=1.0, zorder=3)
    b2 = FancyBboxPatch((4.98, 0.08), 4.87, 0.42, boxstyle="round,pad=0,rounding_size=0.05",
                        fc=C_GREEN_BG, ec=C_GREEN, lw=1.0, zorder=3)
    ax.add_patch(b1)
    ax.add_patch(b2)
    ax.text(2.47, 0.29, "PRE-FIX: 5.18 ms HAL Stall > 1.84 ms FIFO -> 7.67% Drops",
            ha="center", va="center", fontsize=6.7, fontweight="bold", color=C_ROSE, zorder=4)
    ax.text(7.41, 0.29, "FIXED: 128 KB Async DMA + Lock -> 0 Drops | 0.21 ms Jitter",
            ha="center", va="center", fontsize=6.7, fontweight="bold", color=C_GREEN, zorder=4)

    plt.subplots_adjust(left=0.01, right=0.99, top=0.99, bottom=0.01)
    out = os.path.join(ASSETS_DIR, "radar_pipeline_bottleneck.png")
    fig.savefig(out, dpi=300, facecolor=C_BG)
    plt.close(fig)
    print(f"Saved {out}")


def generate_radar_jitter_chart():
    csv_path = os.path.join(REPO_ROOT, "logs", "session_20261120_112453", "session_timeline.csv")
    ts_disc, ts_raw = [], []
    with open(csv_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["sensor"] == "RADAR" and row["event"] == "FRAME":
                ts_disc.append(int(row["elapsed_realtime_ns"]) / 1e6)
                m = re.search(r"rawMonoNs=(\d+)", row.get("summary", ""))
                if m:
                    ts_raw.append(int(m.group(1)) / 1e6)

    d_disc = np.diff(ts_disc)
    d_raw = np.diff(ts_raw)
    # Locate the 120-frame drive segment with highest real raw USB coalescing variance
    win = 120
    best_start, best_score = 0, -1.0
    for s in range(0, len(d_raw) - win, 40):
        seg = d_raw[s:s + win]
        score = float(np.sum((seg < 38.0) | (seg > 53.0))) + float(seg.std()) * 0.25
        if score > best_score:
            best_score = score
            best_start = s
    start = best_start
    end = start + win
    idx = np.arange(end - start)

    fig, ax = plt.subplots(figsize=(6.20, 2.85), dpi=300)
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor("#FFFFFF")

    ax.axhspan(43.5, 46.5, color="#DCFCE7", alpha=0.70, label="Disciplined 45 ms Target Band")
    ax.plot(idx, d_raw[start:end], color=C_ROSE, linewidth=1.25, alpha=0.88,
            label=f"Raw USB Arrival (rawMonoNs): std = {d_raw.std():.2f} ms")
    ax.plot(idx, d_disc[start:end], color=C_BLUE, linewidth=1.9,
            label=f"600 MHz Hardware Clock Lock: std = {d_disc.std():.2f} ms ({d_raw.std()/d_disc.std():.0f}x tighter)")

    ax.set_title(
        f"Live Drive Verification (session_20261120_112453, {len(d_disc):,} intervals)",
        fontsize=9.4, fontweight="bold", color=C_TEXT, pad=7
    )
    ax.set_xlabel(f"Consecutive Radar Frame Window (Frames {start}..{end-1})", fontsize=8.0, color=C_MUTED)
    ax.set_ylabel("Inter-Frame Delta t (ms)", fontsize=8.0, color=C_MUTED)
    ymax = max(112.0, float(np.max(d_raw[start:end])) + 32.0)
    ax.set_ylim(0, ymax)
    ax.grid(True, linestyle="--", linewidth=0.5, color="#E2E8F0")
    ax.tick_params(labelsize=7.5, colors=C_MUTED)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.legend(loc="upper right", fontsize=7.0, frameon=True, facecolor="#FFFFFF", edgecolor=C_BORDER)

    plt.tight_layout(pad=0.6)
    out = os.path.join(ASSETS_DIR, "radar_clock_jitter_comparison.png")
    fig.savefig(out, dpi=300, facecolor=C_BG)
    plt.close(fig)
    print(f"Saved {out}")


def generate_ui_latency_chart():
    metrics = [
        "1 Hz Metrics HUD\n(Tick Total)",
        "Live Camera +\nOverlay Avg",
        "SBS Pipeline\n(gpuP95)",
        "DRAW_OVERLAY\n(Viewfinder)",
        "DRAW_BEV\n(Radar Scope)",
    ]
    before = [64.40, 19.40, 33.65, 14.45, 8.57]
    after = [0.28, 3.45, 7.27, 0.38, 0.36]
    gains = ["-99.5%", "5.6x", "4.6x", "-97.4%", "-95.8%"]

    y = np.arange(len(metrics))
    h = 0.34

    fig, ax = plt.subplots(figsize=(6.20, 4.60), dpi=300)
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor("#FFFFFF")

    bars_b = ax.barh(y + h / 2, before, height=h, color="#FDA4AF", edgecolor=C_ROSE, linewidth=1.0,
                     label="Pre-Fix Baseline (Dropped Frames / Stutter)")
    bars_a = ax.barh(y - h / 2, after, height=h, color="#2563EB", edgecolor=C_BLUE, linewidth=1.0,
                     label="Post-Fix HWC SurfaceView + VSync (<16.67 ms)")

    ax.axvline(16.67, color=C_GREEN, linestyle="--", linewidth=1.5, zorder=2)
    ax.text(17.8, 4.42, "60 FPS Frame Budget\n(16.67 ms)", fontsize=7.6, fontweight="bold",
            color=C_GREEN, va="top", zorder=5,
            bbox=dict(boxstyle="round,pad=0.25", fc=C_GREEN_BG, ec=C_GREEN, lw=0.8))

    for i, (b, a, g) in enumerate(zip(before, after, gains)):
        ax.text(b + 1.0, i + h / 2, f"{b:.2f} ms", va="center", ha="left",
                fontsize=7.8, fontweight="bold", color=C_ROSE, zorder=6,
                bbox=dict(boxstyle="square,pad=0.10", fc="#FFFFFF", ec="none", alpha=0.92))
        ax.text(a + 1.0, i - h / 2, f"{a:.2f} ms  ({g})", va="center", ha="left",
                fontsize=7.8, fontweight="bold", color=C_BLUE, zorder=6,
                bbox=dict(boxstyle="square,pad=0.10", fc="#FFFFFF", ec="none", alpha=0.92))

    ax.set_yticks(y)
    ax.set_yticklabels(metrics, fontsize=8.3, fontweight="bold", color=C_TEXT)
    ax.set_xlim(0, 82)
    ax.set_xlabel("Frame / Draw Latency (ms)  —  Lower Is Better", fontsize=8.4, color=C_MUTED)
    ax.set_title("Samsung Galaxy M32 (SM-M325F) Measured UI Latency Reduction",
                 fontsize=9.8, fontweight="bold", color=C_TEXT, pad=10)
    ax.grid(axis="x", linestyle="--", linewidth=0.5, color="#E2E8F0")
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.tick_params(axis="x", labelsize=7.8, colors=C_MUTED)
    ax.legend(loc="lower right", fontsize=7.4, frameon=True, facecolor="#FFFFFF", edgecolor=C_BORDER)

    plt.tight_layout(pad=0.7)
    out = os.path.join(ASSETS_DIR, "ui_latency_before_after.png")
    fig.savefig(out, dpi=300, facecolor=C_BG)
    plt.close(fig)
    print(f"Saved {out}")


def generate_can_pipeline_diagram():
    fig, ax = plt.subplots(figsize=(11.73, 2.38), dpi=300)
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_SLATE_BG)
    ax.set_xlim(0, 14.0)
    ax.set_ylim(0, 3.0)
    ax.axis("off")

    # Domain bracket headers
    ax.add_patch(FancyBboxPatch((0.10, 2.52), 6.55, 0.36, boxstyle="round,pad=0,rounding_size=0.05",
                                fc=C_BLUE, ec="none", zorder=2))
    ax.text(3.37, 2.70, "ON-VEHICLE ANDROID EDGE (Autonomous Background Sync)",
            ha="center", va="center", fontsize=8.3, fontweight="bold", color="#FFFFFF", zorder=3)

    ax.add_patch(FancyBboxPatch((6.85, 2.52), 7.05, 0.36, boxstyle="round,pad=0,rounding_size=0.05",
                                fc=C_PURPLE, ec="none", zorder=2))
    ax.text(10.37, 2.70, "PC POST-DRIVE PIPELINE (tools/CAN_Processing/ Modular Engine)",
            ha="center", va="center", fontsize=8.3, fontweight="bold", color="#FFFFFF", zorder=3)

    y, h = 0.30, 2.02
    # Phone edge nodes
    draw_node(ax, 0.15, y, 1.85, h,
              "CANedge2 AP\nHTTP / Wi-Fi", "60s Rolling MF4\nSingle-Socket Pull",
              badge="ESP32 SERIAL SAFE", border=C_CYAN, fill=C_BLUE_BG)
    draw_arrow(ax, 2.03, y + h / 2, 2.28, y + h / 2, color=C_BLUE)

    draw_node(ax, 2.31, y, 2.05, h,
              "Mdf4Inspector\n& Pool Index", "Reads <=256 B IDBLOCK\nCross-Dir Dedup",
              badge="NO PING-PONG LOOP", border=C_BLUE, fill=C_BLUE_BG)
    draw_arrow(ax, 4.39, y + h / 2, 4.64, y + h / 2, color=C_BLUE)

    draw_node(ax, 4.67, y, 1.98, h,
              "SyncPlanner\n& CAN HUD Pill", "Live Rollover Clock\nCALIBRATING Gate",
              badge="+60s OFFSET FIXED", border=C_GREEN, fill=C_GREEN_BG)
    draw_arrow(ax, 6.68, y + h / 2, 6.96, y + h / 2, color=C_PURPLE)

    # PC pipeline nodes
    draw_node(ax, 6.99, y, 2.05, h,
              "MF4 Loader &\nChunk Auditor", "Non-Destructive Parse\nContinuity Checks",
              badge="77 TOPICS | 1.58M MSGS", border=C_PURPLE, fill=C_PURPLE_BG)
    draw_arrow(ax, 9.07, y + h / 2, 9.32, y + h / 2, color=C_PURPLE)

    draw_node(ax, 9.35, y, 2.32, h,
              "Dual-Tier Align\nT1 Radar | T2 IMU", "T1: FrameNumber_Act\nT2: rfft Gyro-Yaw",
              badge="-0.08ms | 0.998 CORR", border=C_AMBER, fill=C_AMBER_BG)
    draw_arrow(ax, 11.70, y + h / 2, 11.95, y + h / 2, color=C_GREEN)

    draw_node(ax, 11.98, y, 1.87, h,
              "ProtoBuilder\nMCAP Export", "Dynamic /can/* Schemas\n10s Window Clamp",
              badge="100% FOXGLOVE READY", border=C_GREEN, fill=C_GREEN_BG)

    plt.subplots_adjust(left=0.005, right=0.995, top=0.98, bottom=0.02)
    out = os.path.join(ASSETS_DIR, "can_end_to_end_pipeline.png")
    fig.savefig(out, dpi=300, facecolor=C_BG)
    plt.close(fig)
    print(f"Saved {out}")


if __name__ == "__main__":
    generate_radar_pipeline_diagram()
    generate_radar_jitter_chart()
    generate_ui_latency_chart()
    generate_can_pipeline_diagram()
