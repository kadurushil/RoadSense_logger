#!/usr/bin/env python3
"""
generate_baseline_perception.py
-------------------------------
RoadSense Vision Suite — Baseline 3D Perception Pipeline (Step 2)

Faithfully executes the D:\\Work\\CV pipeline architecture AS-IS without camera alignment
or external calibration, establishing the performance and geometric baseline:
  1. 2D semantic object tracking using Ultralytics YOLO26 + ByteTrack.
  2. Dense streaming metric depth estimation using ByteDance Video Depth Anything (VDA).
  3. Patch-median depth sampling at each bounding box geometric centroid.
  4. Pinhole optical back-projection using uncalibrated baseline parameters (fx=500.0, Zero Extrinsics).

Outputs:
  - baseline_annotated_video.mp4: Video rendered with 3D coordinate badges, trails, and HUD telemetry.
  - baseline_tracking_with_depth.json: Complete structured frame-by-frame 3D detections.

Author: RoadSense Engineering / Antigravity Pair Programming
Date: October 2026
"""

import argparse
import json
import os
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
from video_depth_anything.video_depth_stream import VideoDepthAnything


def compute_resize(original_height, original_width, max_res):
    """
    Computes dimensions preserving aspect ratio if max_res is exceeded.
    Matches run_metric_grid.py and estimate_bbox_depths.py.
    """
    height, width = original_height, original_width
    if max_res > 0 and max(original_height, original_width) > max_res:
        scale = max_res / max(original_height, original_width)
        height = round(original_height * scale)
        width = round(original_width * scale)
    return height, width


def sample_depth(raw_depth, x, y, patch_size=5):
    """
    Samples metric depth at (x, y). If patch_size > 1, computes median of a small
    square patch to guard against edge noise and object boundary discontinuities.
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
    """Generates distinct, high-contrast HSL-derived RGB colors per track ID."""
    np.random.seed(int(track_id) * 31 % 10000)
    color = np.random.randint(50, 255, size=3).tolist()
    return int(color[0]), int(color[1]), int(color[2])


def draw_hud_badge(image, x, y, text_lines, bg_color=(20, 20, 20), text_color=(255, 255, 255), alpha=0.75):
    """Draws a semi-transparent HUD card with crisp text lines."""
    font = cv2.FONT_HERSHEY_SIMPLEX
    font_scale = 0.45
    thickness = 1
    line_spacing = 18
    padding = 6

    # Measure max text width
    widths = [cv2.getTextSize(line, font, font_scale, thickness)[0][0] for line in text_lines]
    box_w = max(widths) + 2 * padding
    box_h = len(text_lines) * line_spacing + padding

    # Keep box inside frame boundaries
    h_img, w_img = image.shape[:2]
    box_x = max(2, min(x, w_img - box_w - 2))
    box_y = max(2, min(y, h_img - box_h - 2))

    # Semi-transparent background
    overlay = image.copy()
    cv2.rectangle(overlay, (box_x, box_y), (box_x + box_w, box_y + box_h), bg_color, -1)
    cv2.addWeighted(overlay, alpha, image, 1.0 - alpha, 0, image)
    cv2.rectangle(image, (box_x, box_y), (box_x + box_w, box_y + box_h), (80, 80, 80), 1)

    # Render text lines
    for i, line in enumerate(text_lines):
        text_y = box_y + padding + (i + 1) * line_spacing - 4
        cv2.putText(image, line, (box_x + padding, text_y), font, font_scale, text_color, thickness, cv2.LINE_AA)


def resolve_input_video(user_input):
    """
    Intelligently resolves the target video from user input:
      1. Direct video path (e.g. 'path/to/video.mp4')
      2. RoadSense session directory (e.g. 'logs/session_20261002_141835')
      3. Camera subdirectory (e.g. 'logs/session_20261002_141835/camera')
      4. Default sample test video fallback
    """
    if not user_input:
        default_video = r"D:\Work\CV\Logs\WIN_20250902_14_47_18_Pro.mp4"
        if os.path.isfile(default_video):
            return os.path.abspath(default_video)
        raise FileNotFoundError("No input specified and default test video was not found.")

    clean_path = user_input.strip('"\'')

    # Direct file match
    if os.path.isfile(clean_path):
        return os.path.abspath(clean_path)

    # Directory match (session folder auto-discovery)
    if os.path.isdir(clean_path):
        standard_candidates = [
            os.path.join(clean_path, "camera", "camera_video.mp4"),
            os.path.join(clean_path, "camera_video.mp4"),
            os.path.join(clean_path, "camera", "video.mp4"),
            os.path.join(clean_path, "video.mp4")
        ]
        for candidate in standard_candidates:
            if os.path.isfile(candidate):
                return os.path.abspath(candidate)

        # Look for any .mp4 or .mkv in the folder
        for root, _, files in os.walk(clean_path):
            for f in sorted(files):
                if f.lower().endswith((".mp4", ".mkv", ".avi")):
                    return os.path.abspath(os.path.join(root, f))

    raise FileNotFoundError(f"Could not locate a valid video file from input: '{user_input}'")


def main():
    parser = argparse.ArgumentParser(
        description="RoadSense Baseline 3D Perception Generator (YOLO26 + ByteTrack + Video Depth Anything)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    # Positional shorthand for video or session directory
    parser.add_argument(
        "input_target",
        nargs="?",
        default=None,
        help="Input video file OR RoadSense session directory (e.g. logs/session_20261002_141835)"
    )
    parser.add_argument(
        "-i", "--input", "--input_video",
        dest="input_video",
        type=str,
        default=None,
        help="Input video file or session directory path"
    )
    parser.add_argument(
        "-e", "--encoder",
        type=str,
        default="vits",
        choices=["vits", "vitb", "vitl"],
        help="Depth model encoder variant: 'vits' (fast, default) or 'vitl' (high-precision)"
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

    # 1. Resolve target video from positional or named flag
    raw_input = args.input_target or args.input_video
    args.input_video = resolve_input_video(raw_input)

    os.makedirs(args.output_dir, exist_ok=True)
    device = torch.device(args.device)

    print("=" * 72)
    print(" RoadSense Vision Suite — Baseline Perception Generator (Step 2)")
    print("=" * 72)
    print(f" Video Source:    {args.input_video}")
    print(f" Output Folder:   {args.output_dir}")
    print(f" Compute Device:  {device} ({torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'})")
    print(f" VDA Model:       {args.encoder.upper()} (Input Size: {args.input_size})")
    print(f" Baseline Fx:     {args.focal_length_x} px (CV Uncalibrated Baseline)")
    print(f" Extrinsics:      NONE (Camera Optical Frame RDF: +X right, +Y down, +Z fwd)")
    print("=" * 72)

    # 2. Model Initialization
    print(f"[+] Loading YOLO26 detector from {args.yolo_weights}...")
    if not os.path.isfile(args.yolo_weights):
        # Check parent folder fallback
        alt_yolo = r"D:\Work\CV\YOLO26 Script\yolo26n.pt"
        if os.path.isfile(alt_yolo):
            args.yolo_weights = alt_yolo
    yolo_model = YOLO(args.yolo_weights)

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

    # 3. Video Reader & Dimensions
    cap = cv2.VideoCapture(args.input_video)
    if not cap.isOpened():
        raise RuntimeError(f"Failed to open video: {args.input_video}")

    orig_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    orig_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total_video_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    frames_to_process = min(args.max_frames, total_video_frames) if args.max_frames else total_video_frames

    # Compute internal VDA downscale dimensions
    vda_h, vda_w = compute_resize(orig_h, orig_w, args.max_res)
    scale_x = vda_w / orig_w
    scale_y = vda_h / orig_h

    # Intrinsics defaults
    fx = args.focal_length_x
    fy = args.focal_length_y if args.focal_length_y is not None else fx
    cx = args.principal_x if args.principal_x is not None else orig_w / 2.0
    cy = args.principal_y if args.principal_y is not None else orig_h / 2.0

    print(f"[+] Video Properties: {orig_w}x{orig_h} @ {fps:.2f} FPS | Total: {total_video_frames} frames")
    print(f"[+] Model Resolution: {vda_w}x{vda_h} (Scale: X={scale_x:.3f}, Y={scale_y:.3f})")
    print(f"[+] Processing Target: {frames_to_process} frames")

    # 4. Output Handlers
    video_writer = None
    annotated_video_path = None
    if not args.skip_video:
        annotated_video_path = os.path.join(args.output_dir, "baseline_annotated_video.mp4")
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        video_writer = cv2.VideoWriter(annotated_video_path, fourcc, fps, (orig_w, orig_h))

    json_export_path = os.path.join(args.output_dir, "baseline_tracking_with_depth.json")

    # Tracking motion trails (last 30 centroids per track)
    track_history = defaultdict(list)
    output_frames = []
    total_detections_count = 0
    unique_track_ids = set()

    t_start = time.time()
    pbar = tqdm(total=frames_to_process, desc="Running Baseline Perception", unit="frame")

    # 5. Execution Loop
    frame_idx = 0
    with torch.no_grad():
        while cap.isOpened() and frame_idx < frames_to_process:
            ret, frame = cap.read()
            if not ret:
                break
            frame_idx += 1
            timestamp_s = (frame_idx - 1) / fps

            # Step 5A: YOLO26 + ByteTrack Tracking
            yolo_results = yolo_model.track(
                frame,
                persist=True,
                tracker="bytetrack.yaml",
                verbose=False
            )

            # Step 5B: Streaming Metric Depth (Video Depth Anything)
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

            # Step 5C: Process Detections & Back-Project 3D Coordinates
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

                    # Rescale center pixel to depth model space
                    cx_scaled = cx_px * scale_x
                    cy_scaled = cy_px * scale_y

                    # Sample patch median metric depth
                    depth_z = sample_depth(raw_depth, cx_scaled, cy_scaled, patch_size=args.patch_size)

                    # Pinhole optical back-projection (CV Baseline formula)
                    # +X = Right, +Y = Down, +Z = Forward optical axis
                    metric_z = depth_z
                    metric_x = ((cx_px - cx) / fx) * metric_z
                    metric_y = ((cy_px - cy) / fy) * metric_z

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
                        },
                        "position_optical_m": {
                            "x": round(metric_x, 3),
                            "y": round(metric_y, 3),
                            "z": round(metric_z, 3)
                        }
                    }
                    current_frame_detections.append(detection_entry)

                    # Step 5D: Visual Rendering (if video output enabled)
                    if canvas is not None:
                        color = get_color_for_id(track_id)

                        # Draw bounding box
                        cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)

                        # Update & draw motion trail
                        track_history[track_id].append((int(cx_px), int(cy_px)))
                        if len(track_history[track_id]) > 30:
                            track_history[track_id].pop(0)

                        pts = np.array(track_history[track_id], np.int32).reshape((-1, 1, 2))
                        cv2.polylines(canvas, [pts], isClosed=False, color=color, thickness=2, lineType=cv2.LINE_AA)

                        # Draw HUD badge above box
                        badge_lines = [
                            f"ID #{track_id} | {class_name} ({conf:.0%})",
                            f"Z:{metric_z:.1f}m | X:{metric_x:.1f}m | Y:{metric_y:.1f}m"
                        ]
                        badge_y = max(10, y1 - 42)
                        draw_hud_badge(canvas, x1, badge_y, badge_lines, bg_color=(25, 25, 25))

            # Render Global Diagnostics Telemetry Bar on canvas
            if canvas is not None:
                # Top header bar
                header_h = 42
                overlay = canvas.copy()
                cv2.rectangle(overlay, (0, 0), (orig_w, header_h), (15, 15, 15), -1)
                cv2.addWeighted(overlay, 0.8, canvas, 0.2, 0, canvas)
                cv2.line(canvas, (0, header_h), (orig_w, header_h), (60, 60, 60), 1)

                status_left = f"[BASELINE CV - NO CALIBRATION] Frame: {frame_idx}/{frames_to_process} ({timestamp_s:.2f}s)"
                status_mid = f"fx={fx:.0f}px | cx={cx:.0f}, cy={cy:.0f} | Extrinsics: NONE (RDF Optical Frame)"
                status_right = f"Active Tracks: {len(current_frame_detections)} | VDA: {args.encoder.upper()}"

                cv2.putText(canvas, status_left, (15, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 220, 255), 1, cv2.LINE_AA)
                cv2.putText(canvas, status_mid, (orig_w // 2 - 240, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.50, (200, 200, 200), 1, cv2.LINE_AA)
                cv2.putText(canvas, status_right, (orig_w - 380, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.50, (0, 255, 180), 1, cv2.LINE_AA)

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

    # 6. Save JSON Data Export
    json_payload = {
        "metadata": {
            "pipeline": "RoadSense Baseline 3D Perception Pipeline",
            "version": "1.0.0-baseline",
            "video_source": os.path.abspath(args.input_video),
            "video_resolution": {"width": orig_w, "height": orig_h},
            "total_frames_processed": frame_idx,
            "source_fps": round(fps, 2),
            "elapsed_time_s": round(t_elapsed, 2),
            "inference_fps": round(avg_fps, 2),
            "parameters": {
                "encoder": args.encoder,
                "vda_inference_resolution": {"width": vda_w, "height": vda_h},
                "focal_length_x": fx,
                "focal_length_y": fy,
                "principal_point": {"cx": cx, "cy": cy},
                "patch_size": args.patch_size,
                "extrinsics_applied": False,
                "coordinate_frame": "Camera Optical RDF (+X Right, +Y Down, +Z Forward optical axis)"
            },
            "baseline_limitations_documented": [
                "Guessed / uncalibrated focal length (fx=500.0) inflates lateral X coordinates.",
                "Zero extrinsics: Uncalibrated camera downward pitch manifests as negative Y (apparent levitation) at long range.",
                "Geometric center depth sampling captures vehicle roof/cabin instead of ground contact point."
            ],
            "timestamp_created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        },
        "summary": {
            "total_detections": total_detections_count,
            "unique_tracks": len(unique_track_ids),
            "frames_count": len(output_frames)
        },
        "frames": output_frames
    }

    print(f"[+] Writing baseline JSON export to: {json_export_path}...")
    with open(json_export_path, "w", encoding="utf-8") as jf:
        json.dump(json_payload, jf, indent=2)

    print("\n" + "=" * 72)
    print(" BASELINE EXECUTION COMPLETE")
    print("=" * 72)
    print(f" Processed:     {frame_idx} frames in {t_elapsed:.1f}s ({avg_fps:.1f} FPS)")
    print(f" Unique Tracks: {len(unique_track_ids)} objects across {total_detections_count} detections")
    if annotated_video_path and os.path.isfile(annotated_video_path):
        print(f" Video Output:  {annotated_video_path} ({os.path.getsize(annotated_video_path) / (1024*1024):.1f} MB)")
    print(f" JSON Output:   {json_export_path} ({os.path.getsize(json_export_path) / (1024*1024):.2f} MB)")
    print("=" * 72)


if __name__ == "__main__":
    main()
