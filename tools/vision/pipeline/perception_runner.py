"""
perception_runner.py
--------------------
Orchestrates offline 3D geometric perception:
  1. Ingests MP4 video frames and applies orientation correction.
  2. Runs YOLO26 + ByteTrack tracking.
  3. Projects tire-contact pixels to ground coordinates (Modality 1: Pure Geometry).
  4. Sizes 3D bounding boxes using optical extent or class priors.
  5. Stamps exact monotonic nanoseconds from session_timeline.csv.
  6. Non-destructively exports structured output to output/json/.
"""

import argparse
import json
import os
import sys
import time
from typing import Dict, Any, List, Optional

# Ensure repository root is on sys.path
repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if repo_root not in sys.path:
    sys.path.insert(0, repo_root)

import cv2
from tqdm import tqdm

from tools.vision.geometry.camera_extrinsics import CameraExtrinsics
from tools.vision.geometry.ground_plane_projector import GroundPlaneProjector, PinholeIntrinsics
from tools.vision.geometry.box_dimensions import BoxDimensions
from tools.vision.detector.track_manager import TrackManager
from tools.vision.pipeline.orientation_handler import OrientationHandler
from tools.vision.pipeline.timeline_loader import TimelineLoader


class PerceptionRunner:
    """Coordinates detection, geometric projection, and JSON export."""

    def __init__(
        self,
        session_dir: str,
        calib_file: Optional[str] = None,
        model_path: str = "tools/vision/models/yolo26n.pt",
        conf: float = 0.25,
        use_optical_sizing: bool = True,
    ):
        self.session_dir = session_dir
        self.calib_path = calib_file or os.path.join(session_dir, "radar_camera_calib.json")

        # Load extrinsics (or fallback defaults if uncalibrated)
        if os.path.isfile(self.calib_path):
            self.extrinsics = CameraExtrinsics.from_calib_json(self.calib_path)
        else:
            self.extrinsics = CameraExtrinsics()

        self.use_optical_sizing = use_optical_sizing
        self._model_path = model_path
        self._conf = conf
        self._tracker = None

    @property
    def tracker(self) -> TrackManager:
        if self._tracker is None:
            self._tracker = TrackManager(model_path=self._model_path, confidence_threshold=self._conf)
        return self._tracker

    def run(
        self,
        max_frames: Optional[int] = None,
        output_json_path: Optional[str] = None,
    ) -> str:
        """Runs the perception pipeline on the session video and exports JSON."""
        # Locate video file
        video_path = os.path.join(self.session_dir, "camera", "camera_video.mp4")
        if not os.path.isfile(video_path):
            # Check root of session_dir
            alt = os.path.join(self.session_dir, "camera_video.mp4")
            if os.path.isfile(alt):
                video_path = alt
            else:
                raise FileNotFoundError(f"Video file not found in {self.session_dir}")

        # Check orientation
        rotation_deg = OrientationHandler.detect_video_rotation(video_path)

        # Open video
        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            raise RuntimeError(f"Could not open video at: {video_path}")

        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        if rotation_deg in (90, 270):
            width, height = height, width

        intrinsics = PinholeIntrinsics.from_calib_json(self.calib_path, width=width, height=height)
        projector = GroundPlaneProjector(self.extrinsics, intrinsics, video_rotation_deg=0)

        # Load timestamps from session_timeline.csv and session_metadata.json
        ts_info = TimelineLoader.load_camera_timestamps(
            self.session_dir, expected_frames=total_frames, fps=fps
        )
        utc_timestamps_ns = ts_info["utc_ns"]
        mono_timestamps_ns = ts_info["mono_ns"]
        time_offset_ns = ts_info["time_offset_ns"]

        frames_to_process = min(total_frames, max_frames) if max_frames else total_frames

        # Output path setup
        session_name = os.path.basename(os.path.normpath(self.session_dir))
        if not output_json_path:
            out_dir = os.path.join("tools", "vision", "output", "json")
            os.makedirs(out_dir, exist_ok=True)
            output_json_path = os.path.join(out_dir, f"{session_name}_vision_tracks.json")
        else:
            os.makedirs(os.path.dirname(os.path.abspath(output_json_path)), exist_ok=True)

        print(f"\n[RoadSense Vision] Processing session: {session_name}")
        print(f"  Frames: {frames_to_process}/{total_frames} | Res: {width}x{height} | Rotation: {rotation_deg}°")
        print(f"  Camera Height: {self.extrinsics.camera_height_m:.2f}m | Pitch: {self.extrinsics.pitch_deg:.2f}°")
        print(f"  Time Offset: {time_offset_ns} ns (UTC Epoch Sync Enabled)")

        output_data: Dict[str, Any] = {
            "session_name": session_name,
            "time_offset_ns": time_offset_ns,
            "camera_extrinsics": {
                "height_m": self.extrinsics.camera_height_m,
                "pitch_deg": self.extrinsics.pitch_deg,
                "yaw_deg": self.extrinsics.yaw_deg,
                "roll_deg": self.extrinsics.roll_deg,
                "fx": intrinsics.fx,
                "fy": intrinsics.fy,
                "cx": intrinsics.cx,
                "cy": intrinsics.cy,
            },
            "frames": [],
        }

        start_time = time.time()
        last_prog_t = start_time
        for frame_idx in tqdm(range(frames_to_process), desc="Vision Tracking", unit="frame"):
            ret, frame = cap.read()
            if not ret:
                break

            # Orientation correction
            if rotation_deg != 0:
                frame = OrientationHandler.apply_rotation(frame, rotation_deg)

            # Nanosecond timestamps (UTC wall clock matching MCAP, plus monotonic for hardware diagnostics)
            utc_ns = utc_timestamps_ns[frame_idx] if frame_idx < len(utc_timestamps_ns) else int(time.time() * 1e9)
            mono_ns = mono_timestamps_ns[frame_idx] if frame_idx < len(mono_timestamps_ns) else 0

            # 2D ByteTrack Tracking
            tracks_2d = self.tracker.track(frame)
            frame_tracks_3d = []

            for det in tracks_2d:
                cx = det["cx"]
                bottom_y = det["bottom_y"]

                # Modality 1: Closed-form ground-plane ray projection
                ground_pt = projector.project_bottom_center_to_ground(cx, bottom_y)
                if not ground_pt:
                    continue  # Object is at or above the horizon

                # 3D Bounding box sizing
                box_3d = BoxDimensions.compute_3d_box(
                    x_fwd_m=ground_pt["x_fwd_m"],
                    y_left_m=ground_pt["y_left_m"],
                    class_name=det["class_name"],
                    w_px=det["w"],
                    h_px=det["h"],
                    fx=intrinsics.fx,
                    fy=intrinsics.fy,
                    use_optical_sizing=self.use_optical_sizing,
                )

                frame_tracks_3d.append({
                    "track_id": det["track_id"],
                    "class_name": det["class_name"],
                    "confidence": det["confidence"],
                    "bbox_2d": det["bbox_xyxy"],
                    "range_m": ground_pt["range_m"],
                    "position_3d": box_3d["center"],
                    "dimensions_3d": box_3d["size"],
                    "label_z_m": box_3d.get("label_z_m"),
                })

            output_data["frames"].append({
                "frame_index": frame_idx,
                "timestamp_ns": utc_ns,
                "timestamp_utc_ns": utc_ns,
                "timestamp_mono_ns": mono_ns,
                "track_count": len(frame_tracks_3d),
                "tracks": frame_tracks_3d,
            })

            now = time.time()
            if (frame_idx + 1) % 50 == 0 or (frame_idx + 1) == frames_to_process or (now - last_prog_t >= 0.5):
                last_prog_t = now
                curr_frames = frame_idx + 1
                curr_pct = round((curr_frames / max(frames_to_process, 1)) * 80.0, 1)  # 0% -> 80% for tracking
                curr_elapsed = now - start_time
                curr_fps = curr_frames / max(curr_elapsed, 0.001)
                rem_frames = frames_to_process - curr_frames
                eta_s = rem_frames / max(curr_fps, 0.1)
                prog_payload = {
                    "step": "vision_tracking",
                    "desc": f"[{session_name}] 3D Vision Tracking #{curr_frames:,}/{frames_to_process:,}",
                    "current": curr_frames,
                    "total": frames_to_process,
                    "pct": curr_pct,
                    "fps": round(curr_fps, 1),
                    "eta_s": round(eta_s, 1),
                }
                print(f"[PROGRESS] {json.dumps(prog_payload)}", flush=True)

        cap.release()
        elapsed = time.time() - start_time
        fps_achieved = frames_to_process / max(elapsed, 0.001)

        with open(output_json_path, "w", encoding="utf-8") as f:
            json.dump(output_data, f, indent=2)

        print(f"  Processed {frames_to_process} frames in {elapsed:.1f}s ({fps_achieved:.1f} FPS)")
        print(f"  Exported vision tracks: {output_json_path}\n")
        return output_json_path

    def run_from_2d_detections(
        self,
        detections_json_path: str,
        output_json_path: Optional[str] = None,
        max_frames: Optional[int] = None,
    ) -> str:
        """Projects pre-existing 2D detections to 3D ground coordinates without re-running YOLO."""
        with open(detections_json_path, "r", encoding="utf-8") as f:
            base_data = json.load(f)

        raw_frames = base_data.get("frames", [])
        total_frames = len(raw_frames)
        frames_to_process = min(total_frames, max_frames) if max_frames else total_frames

        intrinsics = PinholeIntrinsics.from_calib_json(self.calib_path, width=1920, height=1080)
        projector = GroundPlaneProjector(self.extrinsics, intrinsics, video_rotation_deg=0)

        ts_info = TimelineLoader.load_camera_timestamps(
            self.session_dir, expected_frames=total_frames, fps=30.0
        )
        utc_timestamps_ns = ts_info["utc_ns"]
        mono_timestamps_ns = ts_info["mono_ns"]
        time_offset_ns = ts_info["time_offset_ns"]

        session_name = os.path.basename(os.path.normpath(self.session_dir))
        if not output_json_path:
            out_dir = os.path.join("tools", "vision", "output", "json")
            os.makedirs(out_dir, exist_ok=True)
            output_json_path = os.path.join(out_dir, f"{session_name}_vision_tracks.json")
        else:
            os.makedirs(os.path.dirname(os.path.abspath(output_json_path)), exist_ok=True)

        print(f"\n[RoadSense Vision] Fast-projecting 3D geometry from 2D tracks: {detections_json_path}")
        print(f"  Frames: {frames_to_process}/{total_frames} | Time Offset: {time_offset_ns} ns (UTC Epoch Sync Active)")

        output_data: Dict[str, Any] = {
            "session_name": session_name,
            "time_offset_ns": time_offset_ns,
            "camera_extrinsics": {
                "height_m": self.extrinsics.camera_height_m,
                "pitch_deg": self.extrinsics.pitch_deg,
                "yaw_deg": self.extrinsics.yaw_deg,
                "roll_deg": self.extrinsics.roll_deg,
                "fx": intrinsics.fx,
                "fy": intrinsics.fy,
                "cx": intrinsics.cx,
                "cy": intrinsics.cy,
            },
            "frames": [],
        }

        start_time = time.time()
        for frame_idx in tqdm(range(frames_to_process), desc="3D Fast Projection", unit="frame"):
            f_in = raw_frames[frame_idx]
            utc_ns = utc_timestamps_ns[frame_idx] if frame_idx < len(utc_timestamps_ns) else int(time.time() * 1e9)
            mono_ns = mono_timestamps_ns[frame_idx] if frame_idx < len(mono_timestamps_ns) else 0

            frame_tracks_3d = []
            for det in f_in.get("detections", []):
                bb = det.get("bbox_pixels", {})
                x1, y1, x2, y2 = bb.get("x1", 0), bb.get("y1", 0), bb.get("x2", 0), bb.get("y2", 0)
                cx = (x1 + x2) / 2.0
                bottom_y = float(y2)
                w_px = float(x2 - x1)
                h_px = float(y2 - y1)

                ground_pt = projector.project_bottom_center_to_ground(cx, bottom_y)
                if not ground_pt:
                    continue

                box_3d = BoxDimensions.compute_3d_box(
                    x_fwd_m=ground_pt["x_fwd_m"],
                    y_left_m=ground_pt["y_left_m"],
                    class_name=det.get("class_name", "obj"),
                    w_px=w_px,
                    h_px=h_px,
                    fx=intrinsics.fx,
                    fy=intrinsics.fy,
                    use_optical_sizing=self.use_optical_sizing,
                )

                frame_tracks_3d.append({
                    "track_id": det.get("track_id", 0),
                    "class_name": det.get("class_name", "obj"),
                    "confidence": det.get("confidence", 0.0),
                    "bbox_2d": [x1, y1, x2, y2],
                    "range_m": ground_pt["range_m"],
                    "position_3d": box_3d["center"],
                    "dimensions_3d": box_3d["size"],
                    "label_z_m": box_3d.get("label_z_m"),
                })

            output_data["frames"].append({
                "frame_index": frame_idx,
                "timestamp_ns": utc_ns,
                "timestamp_utc_ns": utc_ns,
                "timestamp_mono_ns": mono_ns,
                "track_count": len(frame_tracks_3d),
                "tracks": frame_tracks_3d,
            })

            if (frame_idx + 1) % 500 == 0 or (frame_idx + 1) == frames_to_process:
                curr_frames = frame_idx + 1
                curr_pct = round((curr_frames / max(frames_to_process, 1)) * 80.0, 1)
                prog_payload = {
                    "step": "vision_tracking",
                    "desc": f"[{session_name}] 3D Re-projection #{curr_frames:,}/{frames_to_process:,}",
                    "current": curr_frames,
                    "total": frames_to_process,
                    "pct": curr_pct,
                }
                print(f"[PROGRESS] {json.dumps(prog_payload)}", flush=True)

        elapsed = time.time() - start_time
        fps_achieved = frames_to_process / max(elapsed, 0.001)

        with open(output_json_path, "w", encoding="utf-8") as f:
            json.dump(output_data, f, indent=2)

        print(f"  Projected {frames_to_process} frames in {elapsed:.1f}s ({fps_achieved:.1f} FPS)")
        print(f"  Exported vision tracks: {output_json_path}\n")
        return output_json_path


def main():
    parser = argparse.ArgumentParser(description="RoadSense Offline Geometric Vision Runner")
    parser.add_argument("session", help="Path to session folder (e.g. logs/session_20261006_095609)")
    parser.add_argument("-n", "--num_frames", type=int, default=None, help="Max frames to process")
    parser.add_argument("-o", "--output", default=None, help="Output JSON path")
    parser.add_argument("-c", "--conf", type=float, default=0.25, help="YOLO confidence threshold")
    parser.add_argument("--priors_only", action="store_true", help="Use fixed class priors instead of optical sizing")
    parser.add_argument(
        "--from_2d",
        default=None,
        help="Path to pre-existing 2D detections JSON (e.g. baseline_yolo_tracking.json) for instant 3D projection without re-running YOLO",
    )
    parser.add_argument(
        "--mcap",
        action="store_true",
        help="Automatically generate enriched Foxglove MCAP with /vision/geo_tracks in one single run",
    )
    args = parser.parse_args()

    session_name = os.path.basename(os.path.normpath(args.session))
    print(f"[PROGRESS] {json.dumps({'step': 'vision_start', 'desc': f'[{session_name}] Starting 3D Vision Pipeline...', 'pct': 5})}", flush=True)

    runner = PerceptionRunner(
        session_dir=args.session,
        conf=args.conf,
        use_optical_sizing=(not args.priors_only),
    )
    if args.from_2d:
        out_json = runner.run_from_2d_detections(
            detections_json_path=args.from_2d,
            max_frames=args.num_frames,
            output_json_path=args.output,
        )
    else:
        out_json = runner.run(max_frames=args.num_frames, output_json_path=args.output)

    if args.mcap:
        print(f"[PROGRESS] {json.dumps({'step': 'vision_mcap', 'desc': f'[{session_name}] Injecting 3D Vision tracks into Foxglove MCAP...', 'pct': 85})}", flush=True)
        from tools.vision.mcap.inject_vision_tracks import inject_vision_tracks_to_mcap
        inject_vision_tracks_to_mcap(input_source=out_json)

    print(f"[PROGRESS] {json.dumps({'step': 'done', 'desc': f'[{session_name}] 3D Vision Pipeline Complete', 'pct': 100, 'overall_pct': 100})}", flush=True)


if __name__ == "__main__":
    main()

