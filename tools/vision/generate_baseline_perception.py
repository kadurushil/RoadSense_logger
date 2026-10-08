#!/usr/bin/env python3
"""
generate_baseline_perception.py
-------------------------------
RoadSense Vision Suite — Baseline Perception & Tracking Generator (Step 2)

Flexible multi-modal perception pipeline supporting:
  1. Full 3D Baseline Perception (YOLO26 + ByteTrack + Video Depth Anything).
  2. High-Speed Standalone 2D Tracking (-y / --skip_depth):
     Bypasses heavy depth models to run pure YOLO26 + ByteTrack at ~45-55+ FPS.
  3. Automatic Video Orientation & Inversion Correction:
     Detects MP4 container rotation metadata (e.g. 180° for reverse landscape phone mounts)
     and automatically rotates frames upright before inference and video recording.
  4. Ergonomic CLI: Accepts session directories directly with auto-discovery and short flags.

Outputs:
  - baseline_annotated_video.mp4 / baseline_yolo_tracking.mp4
  - baseline_tracking_with_depth.json / baseline_yolo_tracking.json (with semantic audit)

Author: RoadSense Engineering / Antigravity Pair Programming
Date: October 2026
"""

import argparse
import json
import os
import struct
import sys
import time
from collections import defaultdict

import cv2
import numpy as np
import torch
from tqdm import tqdm

# Ensure local vision tools directory is on path for video_depth_anything & utils imports
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from ultralytics import YOLO


def extract_video_orientation(mp4_path):
    """
    Parses MPEG-4 tkhd (Track Header) display matrix to determine rotation (0, 90, 180, 270).
    Performs fast ISO box-traversal directly on the container.
    """
    if not os.path.isfile(mp4_path):
        return 0
    try:
        file_size = os.path.getsize(mp4_path)
        with open(mp4_path, 'rb') as f:
            offset = 0
            while offset < file_size:
                f.seek(offset)
                hdr = f.read(8)
                if len(hdr) < 8:
                    break
                box_size, box_type = struct.unpack('>I4s', hdr)
                if box_size == 1:
                    hdr64 = f.read(8)
                    if len(hdr64) < 8:
                        break
                    box_size = struct.unpack('>Q', hdr64)[0]
                elif box_size == 0:
                    box_size = file_size - offset

                if box_type == b'moov':
                    f.seek(offset)
                    moov_data = f.read(min(box_size, 4 * 1024 * 1024))
                    tkhd_idx = moov_data.find(b'tkhd')
                    if tkhd_idx != -1 and tkhd_idx + 80 <= len(moov_data):
                        matrix_data = moov_data[tkhd_idx + 44 : tkhd_idx + 44 + 36]
                        m = struct.unpack('>9i', matrix_data)
                        a, b, u, c, d, v, x, y, w = [round(val / 65536.0, 2) for val in m]
                        if a == -1.0 and d == -1.0:
                            return 180
                        elif b == 1.0 and c == -1.0:
                            return 90
                        elif b == -1.0 and c == 1.0:
                            return 270
                        elif a == 1.0 and d == 1.0:
                            return 0
                    break
                offset += box_size

            # Fallback search last 10MB
            search_len = min(file_size, 10 * 1024 * 1024)
            f.seek(max(0, file_size - search_len))
            data = f.read(search_len)
            idx = data.find(b'tkhd')
            if idx != -1 and idx + 80 <= len(data):
                matrix_data = data[idx + 44 : idx + 44 + 36]
                m = struct.unpack('>9i', matrix_data)
                a, b, u, c, d, v, x, y, w = [round(val / 65536.0, 2) for val in m]
                if a == -1.0 and d == -1.0:
                    return 180
                elif b == 1.0 and c == -1.0:
                    return 90
                elif b == -1.0 and c == 1.0:
                    return 270
                elif a == 1.0 and d == 1.0:
                    return 0
    except Exception:
        pass
    return 0


def compute_resize(original_height, original_width, max_res):
    """
    Computes dimensions preserving aspect ratio if max_res is exceeded.
    """
    height, width = original_height, original_width
    if max_res > 0 and max(original_height, original_width) > max_res:
        scale = max_res / max(original_height, original_width)
        height = round(original_height * scale)
        width = round(original_width * scale)
    return height, width


def sample_depth(raw_depth, x, y, patch_size=5):
    """
    Samples metric depth at (x, y) with median patch filtering.
    """
    h, w = raw_depth.shape[:2]
    x_int = int(round(x))
    y_int = int(round(y))

    if patch_size <= 1:
        x_c = min(max(x_int, 0), w - 1)
        y_c = min(max(y_int, 0), h - 1)
        return float(raw_depth[y_c, x_c])

    half = patch_size // 2
    x1 = max(0, x_int - half)
    x2 = min(w, x_int + half + 1)
    y1 = max(0, y_int - half)
    y2 = min(h, y_int + half + 1)

    patch = raw_depth[y1:y2, x1:x2]
    if patch.size == 0:
        return 0.0
    return float(np.median(patch))


def get_color_for_id(track_id):
    """Generates distinct, high-contrast RGB colors keyed by track ID."""
    np.random.seed(int(track_id) * 31 % 10000)
    color = np.random.randint(50, 255, size=3).tolist()
    return int(color[0]), int(color[1]), int(color[2])


def draw_hud_badge(image, x, y, text_lines, bg_color=(20, 20, 20), text_color=(255, 255, 255), alpha=0.75):
    """Draws a semi-transparent HUD card with text."""
    font = cv2.FONT_HERSHEY_SIMPLEX
    font_scale = 0.45
    thickness = 1
    line_spacing = 18
    padding = 6

    widths = [cv2.getTextSize(line, font, font_scale, thickness)[0][0] for line in text_lines]
    box_w = max(widths) + 2 * padding
    box_h = len(text_lines) * line_spacing + padding

    h_img, w_img = image.shape[:2]
    box_x = max(2, min(x, w_img - box_w - 2))
    box_y = max(2, min(y, h_img - box_h - 2))

    overlay = image.copy()
    cv2.rectangle(overlay, (box_x, box_y), (box_x + box_w, box_y + box_h), bg_color, -1)
    cv2.addWeighted(overlay, alpha, image, 1.0 - alpha, 0, image)
    cv2.rectangle(image, (box_x, box_y), (box_x + box_w, box_y + box_h), (80, 80, 80), 1)

    for i, line in enumerate(text_lines):
        text_y = box_y + padding + (i + 1) * line_spacing - 4
        cv2.putText(image, line, (box_x + padding, text_y), font, font_scale, text_color, thickness, cv2.LINE_AA)


def resolve_input_video(user_input):
    """
    Intelligently resolves target video path:
      1. Direct video path (e.g. 'path/to/video.mp4')
      2. RoadSense session directory (e.g. 'logs/session_20261006_095609')
      3. Camera subdirectory (e.g. 'logs/session_.../camera')
      4. Default test video fallback
    """
    if not user_input:
        default_video = r"D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4"
        if os.path.isfile(default_video):
            return os.path.abspath(default_video)
        raise FileNotFoundError("No input specified and default test video was not found.")

    clean_path = user_input.strip('"\'')

    # Direct file
    if os.path.isfile(clean_path):
        return os.path.abspath(clean_path)

    # Directory auto-discovery
    if os.path.isdir(clean_path):
        candidates = [
            os.path.join(clean_path, "camera", "camera_video.mp4"),
            os.path.join(clean_path, "camera_video.mp4"),
            os.path.join(clean_path, "camera", "video.mp4"),
            os.path.join(clean_path, "video.mp4")
        ]
        for c in candidates:
            if os.path.isfile(c):
                return os.path.abspath(c)

        # Search for any .mp4 or .mkv in the tree
        for root, _, files in os.walk(clean_path):
            for f in sorted(files):
                if f.lower().endswith((".mp4", ".mkv", ".avi")):
                    return os.path.abspath(os.path.join(root, f))

    raise FileNotFoundError(f"Could not locate a valid video file from input: '{user_input}'")


def main():
    parser = argparse.ArgumentParser(
        description="RoadSense Baseline Perception & Tracking Generator (YOLO26 + ByteTrack + VDA)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    # Positional input shorthand
    parser.add_argument(
        "input_target",
        nargs="?",
        default=None,
        help="Input video file OR RoadSense session directory (e.g. logs/session_20261006_095609)"
    )
    parser.add_argument(
        "-i", "--input", "--input_video",
        dest="input_video",
        type=str,
        default=None,
        help="Input video file or session directory path"
    )
    parser.add_argument(
        "-y", "--skip_depth", "--yolo_only",
        dest="skip_depth",
        action="store_true",
        help="Skip Video Depth Anything entirely: runs standalone YOLO26 + ByteTrack at ~45-55+ FPS"
    )
    parser.add_argument(
        "-c", "--conf", "--confidence",
        dest="conf_threshold",
        type=float,
        default=0.25,
        help="Minimum confidence threshold for YOLO detections"
    )
    parser.add_argument(
        "-r", "--rotate",
        dest="rotate",
        type=str,
        default="auto",
        choices=["auto", "0", "90", "180", "270"],
        help="Video frame rotation in degrees: 'auto' (detects MP4 metadata), '0', '90', '180', or '270'"
    )
    parser.add_argument(
        "-e", "--encoder",
        type=str,
        default="vits",
        choices=["vits", "vitb", "vitl"],
        help="VDA depth model encoder variant: 'vits' (fast) or 'vitl' (high-precision)"
    )
    parser.add_argument(
        "-n", "-m", "--frames", "--max_frames",
        dest="max_frames",
        type=int,
        default=None,
        help="Number of frames to process (e.g. -n 100 for a quick test, default: all frames)"
    )
    parser.add_argument(
        "-o", "--output", "--output_dir",
        dest="output_dir",
        type=str,
        default=r"tools\vision\output",
        help="Output directory for annotated video and JSON"
    )
    parser.add_argument(
        "-f", "--fx", "--focal_length_x",
        dest="focal_length_x",
        type=float,
        default=500.0,
        help="Baseline optical focal length along X in pixels"
    )
    parser.add_argument(
        "--fy", "--focal_length_y",
        dest="focal_length_y",
        type=float,
        default=None,
        help="Focal length along Y (defaults to fx)"
    )
    parser.add_argument(
        "--cx", "--principal_x",
        dest="principal_x",
        type=float,
        default=None,
        help="Optical center X (defaults to width / 2.0)"
    )
    parser.add_argument(
        "--cy", "--principal_y",
        dest="principal_y",
        type=float,
        default=None,
        help="Optical center Y (defaults to height / 2.0)"
    )
    parser.add_argument(
        "--patch_size",
        type=int,
        default=5,
        help="Square patch size for median depth sampling at bbox center"
    )
    parser.add_argument(
        "--max_res",
        type=int,
        default=1280,
        help="Downscale resolution ceiling for depth inference"
    )
    parser.add_argument(
        "--input_size",
        type=int,
        default=392,
        help="Input size dimension for VDA model"
    )
    parser.add_argument(
        "--yolo_weights",
        type=str,
        default=os.path.join(SCRIPT_DIR, "models", "yolo26n.pt"),
        help="Path to YOLO weights (.pt)"
    )
    parser.add_argument(
        "--checkpoint_dir",
        type=str,
        default=os.path.join(SCRIPT_DIR, "checkpoints"),
        help="Path to Video Depth Anything checkpoints directory"
    )
    parser.add_argument(
        "--fp32",
        action="store_true",
        help="Use float32 depth inference (default: float16 on CUDA)"
    )
    parser.add_argument(
        "--skip_video",
        action="store_true",
        help="Skip generating annotated video and export JSON only"
    )
    parser.add_argument(
        "--device",
        type=str,
        default="cuda" if torch.cuda.is_available() else "cpu",
        help="Inference compute device ('cuda' or 'cpu')"
    )

    args = parser.parse_args()

    # 1. Resolve Target Video
    raw_input = args.input_target or args.input_video
    args.input_video = resolve_input_video(raw_input)
    os.makedirs(args.output_dir, exist_ok=True)
    device = torch.device(args.device)

    # 2. Determine Video Rotation
    if args.rotate == "auto":
        detected_rot = extract_video_orientation(args.input_video)
        effective_rot = detected_rot
    else:
        effective_rot = int(args.rotate)

    rotate_code = None
    if effective_rot == 180:
        rotate_code = cv2.ROTATE_180
    elif effective_rot == 90:
        rotate_code = cv2.ROTATE_90_CLOCKWISE
    elif effective_rot == 270:
        rotate_code = cv2.ROTATE_90_COUNTERCLOCKWISE

    print("=" * 72)
    print(" RoadSense Vision Suite — Perception & Tracking Generator")
    print("=" * 72)
    print(f" Video Source:    {args.input_video}")
    print(f" Output Folder:   {args.output_dir}")
    print(f" Compute Device:  {device} ({torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'})")
    if args.skip_depth:
        print(f" Mode:            STANDALONE YOLO26 TRACKING (-y / --skip_depth active)")
        print(f" Pipeline:        YOLO26n (5MB) + ByteTrack @ ~45-55+ FPS (Depth Skipped)")
    else:
        print(f" Mode:            FULL 3D BASELINE PERCEPTION (YOLO26 + ByteTrack + VDA)")
        print(f" VDA Model:       {args.encoder.upper()} (Input Size: {args.input_size})")
        print(f" Baseline Fx:     {args.focal_length_x} px | Extrinsics: NONE (RDF Optical Frame)")
    print(f" Conf Threshold:  {args.conf_threshold:.2f}")
    if effective_rot != 0:
        print(f" Frame Rotation:  {effective_rot}° (Auto-Corrected to Upright Landscape)")
    else:
        print(f" Frame Rotation:  0° (Standard Upright)")
    print("=" * 72)

    # 3. Load YOLO Model
    print(f"[+] Loading YOLO26 detector from {args.yolo_weights}...")
    if not os.path.isfile(args.yolo_weights):
        alt_yolo = r"D:\Work\CV\YOLO26 Script\yolo26n.pt"
        if os.path.isfile(alt_yolo):
            args.yolo_weights = alt_yolo
    yolo_model = YOLO(args.yolo_weights)

    # 4. Conditionally Load VDA Model
    vda_model = None
    if not args.skip_depth:
        from video_depth_anything.video_depth_stream import VideoDepthAnything

        model_configs = {
            'vits': {'encoder': 'vits', 'features': 64, 'out_channels': [48, 96, 192, 384]},
            'vitb': {'encoder': 'vitb', 'features': 128, 'out_channels': [96, 192, 384, 768]},
            'vitl': {'encoder': 'vitl', 'features': 256, 'out_channels': [256, 512, 1024, 1024]},
        }
        ckpt_path = os.path.join(args.checkpoint_dir, f"metric_video_depth_anything_{args.encoder}.pth")
        if not os.path.isfile(ckpt_path):
            alt_ckpt = os.path.join(r"D:\Work\CV\Video-Depth-Anything\checkpoints", f"metric_video_depth_anything_{args.encoder}.pth")
            if os.path.isfile(alt_ckpt):
                ckpt_path = alt_ckpt
            else:
                raise FileNotFoundError(f"VDA checkpoint not found at {ckpt_path} or {alt_ckpt}")

        print(f"[+] Loading Video Depth Anything checkpoint: {ckpt_path}...")
        vda_model = VideoDepthAnything(**model_configs[args.encoder])
        vda_model.load_state_dict(torch.load(ckpt_path, map_location='cpu'), strict=True)
        vda_model = vda_model.to(device).eval()

    # 5. Video Reader & Dimensions
    cap = cv2.VideoCapture(args.input_video)
    if not cap.isOpened():
        raise RuntimeError(f"Failed to open video: {args.input_video}")

    raw_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    raw_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total_video_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    frames_to_process = min(args.max_frames, total_video_frames) if args.max_frames else total_video_frames

    # Adjust dimensions if rotated 90 or 270 degrees
    if effective_rot in (90, 270):
        orig_w, orig_h = raw_h, raw_w
    else:
        orig_w, orig_h = raw_w, raw_h

    # VDA downscale dimensions
    vda_h, vda_w = compute_resize(orig_h, orig_w, args.max_res)
    scale_x = vda_w / orig_w
    scale_y = vda_h / orig_h

    # Pinhole intrinsics
    fx = args.focal_length_x
    fy = args.focal_length_y if args.focal_length_y is not None else fx
    cx = args.principal_x if args.principal_x is not None else orig_w / 2.0
    cy = args.principal_y if args.principal_y is not None else orig_h / 2.0

    print(f"[+] Video Properties: {orig_w}x{orig_h} @ {fps:.2f} FPS | Total: {total_video_frames} frames")
    if not args.skip_depth:
        print(f"[+] Depth Resolution: {vda_w}x{vda_h} (Scale: X={scale_x:.3f}, Y={scale_y:.3f})")
    print(f"[+] Processing Target: {frames_to_process} frames")

    # 6. Output Files
    prefix = "baseline_yolo" if args.skip_depth else "baseline"
    video_writer = None
    annotated_video_path = None
    if not args.skip_video:
        annotated_video_path = os.path.join(args.output_dir, f"{prefix}_annotated_video.mp4")
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        video_writer = cv2.VideoWriter(annotated_video_path, fourcc, fps, (orig_w, orig_h))

    json_export_path = os.path.join(args.output_dir, f"{prefix}_tracking.json" if args.skip_depth else f"{prefix}_tracking_with_depth.json")

    # Tracking metrics
    track_history = defaultdict(list)
    output_frames = []
    total_detections_count = 0
    unique_track_ids = set()
    class_detections_count = defaultdict(int)
    class_confidences_map = defaultdict(list)
    track_durations = defaultdict(int)

    t_start = time.time()
    desc_label = "Running YOLO Tracking" if args.skip_depth else "Running Perception"
    pbar = tqdm(total=frames_to_process, desc=desc_label, unit="frame")

    # 7. Execution Loop
    frame_idx = 0
    with torch.no_grad():
        while cap.isOpened() and frame_idx < frames_to_process:
            ret, frame = cap.read()
            if not ret:
                break
            frame_idx += 1
            timestamp_s = (frame_idx - 1) / fps

            # Step 7A: Inversion & Rotation Correction
            if rotate_code is not None:
                frame = cv2.rotate(frame, rotate_code)

            # Step 7B: 2D Detection & Tracking (YOLO26 + ByteTrack)
            yolo_results = yolo_model.track(
                frame,
                conf=args.conf_threshold,
                persist=True,
                tracker="bytetrack.yaml",
                verbose=False
            )

            # Step 7C: Metric Depth (Only if depth is enabled)
            raw_depth = None
            if not args.skip_depth and vda_model is not None:
                if (vda_w != orig_w) or (vda_h != orig_h):
                    frame_vda = cv2.resize(frame, (vda_w, vda_h))
                else:
                    frame_vda = frame
                frame_rgb = cv2.cvtColor(frame_vda, cv2.COLOR_BGR2RGB)
                raw_depth = vda_model.infer_video_depth_one(
                    frame_rgb,
                    input_size=args.input_size,
                    device=device.type,
                    fp32=args.fp32
                )

            # Step 7D: Process Detections
            current_frame_detections = []
            canvas = frame.copy() if video_writer is not None else None

            if (
                yolo_results is not None
                and len(yolo_results) > 0
                and yolo_results[0].boxes is not None
                and yolo_results[0].boxes.id is not None
            ):
                boxes = yolo_results[0].boxes.xyxy.cpu().numpy()
                track_ids = yolo_results[0].boxes.id.int().cpu().tolist()
                class_ids = yolo_results[0].boxes.cls.int().cpu().tolist()
                confidences = yolo_results[0].boxes.conf.float().cpu().tolist()

                for box, track_id, class_id, conf in zip(boxes, track_ids, class_ids, confidences):
                    x1, y1, x2, y2 = map(int, box)
                    cx_px = (x1 + x2) / 2.0
                    cy_px = (y1 + y2) / 2.0
                    class_name = yolo_model.names.get(class_id, f"cls_{class_id}")
                    unique_track_ids.add(track_id)
                    total_detections_count += 1
                    class_detections_count[class_name] += 1
                    class_confidences_map[class_name].append(conf)
                    track_durations[track_id] += 1

                    detection_entry = {
                        "track_id": track_id,
                        "class_name": class_name,
                        "confidence": round(conf, 3),
                        "bbox_pixels": {
                            "x1": x1,
                            "y1": y1,
                            "x2": x2,
                            "y2": y2
                        },
                        "center_pixel": {
                            "x": round(cx_px, 1),
                            "y": round(cy_px, 1)
                        }
                    }

                    # Attach 3D coordinates if depth was computed
                    metric_z, metric_x, metric_y = None, None, None
                    if not args.skip_depth and raw_depth is not None:
                        cx_scaled = cx_px * scale_x
                        cy_scaled = cy_px * scale_y
                        metric_z = sample_depth(raw_depth, cx_scaled, cy_scaled, patch_size=args.patch_size)
                        metric_x = ((cx_px - cx) / fx) * metric_z
                        metric_y = ((cy_px - cy) / fy) * metric_z
                        detection_entry["position_optical_m"] = {
                            "x": round(metric_x, 3),
                            "y": round(metric_y, 3),
                            "z": round(metric_z, 3)
                        }

                    current_frame_detections.append(detection_entry)

                    # Step 7E: Render Overlays
                    if canvas is not None:
                        color = get_color_for_id(track_id)
                        cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)

                        track_history[track_id].append((int(cx_px), int(cy_px)))
                        if len(track_history[track_id]) > 30:
                            track_history[track_id].pop(0)

                        pts = np.array(track_history[track_id], np.int32).reshape((-1, 1, 2))
                        cv2.polylines(canvas, [pts], isClosed=False, color=color, thickness=2, lineType=cv2.LINE_AA)

                        if not args.skip_depth and metric_z is not None:
                            badge_lines = [
                                f"ID #{track_id} | {class_name} ({conf:.0%})",
                                f"Z:{metric_z:.1f}m | X:{metric_x:.1f}m | Y:{metric_y:.1f}m"
                            ]
                        else:
                            badge_lines = [
                                f"ID #{track_id} | {class_name} ({conf:.0%})",
                                f"Bbox: {int(x2 - x1)}x{int(y2 - y1)}px"
                            ]

                        badge_y = max(10, y1 - 42)
                        draw_hud_badge(canvas, x1, badge_y, badge_lines, bg_color=(25, 25, 25))

            # Step 7F: Render Telemetry Diagnostics Bar
            if canvas is not None:
                header_h = 42
                overlay = canvas.copy()
                cv2.rectangle(overlay, (0, 0), (orig_w, header_h), (15, 15, 15), -1)
                cv2.addWeighted(overlay, 0.8, canvas, 0.2, 0, canvas)
                cv2.line(canvas, (0, header_h), (orig_w, header_h), (60, 60, 60), 1)

                elapsed_now = max(time.time() - t_start, 0.001)
                fps_instant = frame_idx / elapsed_now

                if args.skip_depth:
                    status_left = f"[YOLO26 STANDALONE TRACKER] Frame: {frame_idx}/{frames_to_process} ({timestamp_s:.2f}s)"
                    status_mid = f"Model: YOLO26n (5MB) | Conf: >={args.conf_threshold:.2f} | Rot: {effective_rot}°"
                    status_right = f"Active Tracks: {len(current_frame_detections)} | FPS: {fps_instant:.1f}"
                else:
                    status_left = f"[BASELINE CV + DEPTH] Frame: {frame_idx}/{frames_to_process} ({timestamp_s:.2f}s)"
                    status_mid = f"fx={fx:.0f}px | VDA: {args.encoder.upper()} | Rot: {effective_rot}°"
                    status_right = f"Active Tracks: {len(current_frame_detections)} | FPS: {fps_instant:.1f}"

                cv2.putText(canvas, status_left, (15, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (0, 220, 255), 1, cv2.LINE_AA)
                cv2.putText(canvas, status_mid, (orig_w // 2 - 220, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (200, 200, 200), 1, cv2.LINE_AA)
                cv2.putText(canvas, status_right, (orig_w - 360, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (0, 255, 180), 1, cv2.LINE_AA)

                video_writer.write(canvas)

            output_frames.append({
                "frame_number": frame_idx,
                "timestamp_s": round(timestamp_s, 3),
                "detections": current_frame_detections
            })

            pbar.update(1)

    pbar.close()
    cap.release()
    if video_writer is not None:
        video_writer.release()

    t_elapsed = max(time.time() - t_start, 0.001)
    avg_fps = frame_idx / t_elapsed

    # 8. Compute Semantic Audit Summary
    semantic_summary = {}
    for cname, count in sorted(class_detections_count.items(), key=lambda x: -x[1]):
        confs = class_confidences_map[cname]
        semantic_summary[cname] = {
            "detections_count": count,
            "mean_confidence": round(float(np.mean(confs)), 3),
            "min_confidence": round(float(np.min(confs)), 3),
            "max_confidence": round(float(np.max(confs)), 3)
        }

    avg_track_duration = float(np.mean(list(track_durations.values()))) if track_durations else 0.0

    # 9. Save JSON Data Export
    json_payload = {
        "metadata": {
            "pipeline": "RoadSense Standalone YOLO Tracker" if args.skip_depth else "RoadSense Baseline 3D Perception",
            "version": "1.1.0-upright",
            "video_source": os.path.abspath(args.input_video),
            "video_resolution": {"width": orig_w, "height": orig_h},
            "rotation_applied_deg": effective_rot,
            "total_frames_processed": frame_idx,
            "source_fps": round(fps, 2),
            "elapsed_time_s": round(t_elapsed, 2),
            "inference_fps": round(avg_fps, 2),
            "depth_enabled": not args.skip_depth,
            "parameters": {
                "yolo_model": os.path.basename(args.yolo_weights),
                "confidence_threshold": args.conf_threshold,
                "depth_encoder": None if args.skip_depth else args.encoder,
                "coordinate_frame": "2D Image Pixels (Upright)" if args.skip_depth else "Camera Optical RDF (+X Right, +Y Down, +Z Forward)"
            },
            "timestamp_created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        },
        "summary": {
            "total_detections": total_detections_count,
            "unique_tracks": len(unique_track_ids),
            "frames_count": len(output_frames),
            "average_track_duration_frames": round(avg_track_duration, 1),
            "classes_detected": semantic_summary
        },
        "frames": output_frames
    }

    print(f"\n[+] Writing JSON export to: {json_export_path}...")
    with open(json_export_path, "w", encoding="utf-8") as jf:
        json.dump(json_payload, jf, indent=2)

    print("\n" + "=" * 72)
    print(" EXECUTION COMPLETE")
    print("=" * 72)
    print(f" Mode:          {'STANDALONE YOLO TRACKING' if args.skip_depth else 'FULL 3D PERCEPTION'}")
    print(f" Processed:     {frame_idx} frames in {t_elapsed:.1f}s ({avg_fps:.1f} FPS)")
    print(f" Unique Tracks: {len(unique_track_ids)} objects across {total_detections_count} detections")
    top_classes_list = [f"{k}: {v['detections_count']}" for k, v in list(semantic_summary.items())[:4]]
    top_classes_str = ", ".join(top_classes_list) if top_classes_list else "None"
    print(f" Top Classes:   {top_classes_str}")
    if annotated_video_path and os.path.isfile(annotated_video_path):
        print(f" Video Output:  {annotated_video_path} ({os.path.getsize(annotated_video_path) / (1024*1024):.1f} MB)")
    print(f" JSON Output:   {json_export_path} ({os.path.getsize(json_export_path) / (1024*1024):.2f} MB)")
    print("=" * 72)


if __name__ == "__main__":
    main()
