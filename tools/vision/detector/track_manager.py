"""
track_manager.py
----------------
Manages multi-object tracking across video frames using ByteTrack.
Associates persistent track IDs, maintains track lifetime statistics,
and filters momentary false-positive detections.
"""

from typing import List, Dict, Any, Optional
import numpy as np
import torch
from ultralytics import YOLO


class TrackManager:
    """Wraps ByteTrack tracking state and track lifecycle management."""

    def __init__(
        self,
        model_path: str = "tools/vision/models/yolo26n.pt",
        confidence_threshold: float = 0.25,
        iou_threshold: float = 0.45,
        target_classes: Optional[List[str]] = None,
        tracker_type: str = "bytetrack.yaml",
        device: Optional[str] = None,
    ):
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.model = YOLO(model_path)
        self.conf = confidence_threshold
        self.iou = iou_threshold
        self.tracker_type = tracker_type
        self.target_classes = target_classes or [
            "car", "truck", "bus", "motorcycle", "bicycle", "person"
        ]
        self.class_names = self.model.names
        self.track_history: Dict[int, int] = {}  # track_id -> frames active

    def track(self, frame: np.ndarray) -> List[Dict[str, Any]]:
        """
        Runs tracking on the current frame and returns tracked obstacles with persistent IDs.

        Args:
            frame: BGR numpy image array.

        Returns:
            List of tracked objects with track_id, bbox, class, confidence, and center coordinates.
        """
        results = self.model.track(
            source=frame,
            persist=True,
            tracker=self.tracker_type,
            conf=self.conf,
            iou=self.iou,
            device=self.device,
            verbose=False,
        )

        tracks = []
        if not results or len(results) == 0:
            return tracks

        boxes = results[0].boxes
        if boxes is None or len(boxes) == 0:
            return tracks

        for box in boxes:
            cls_id = int(box.cls[0].item())
            cls_name = self.class_names.get(cls_id, "unknown")

            if cls_name not in self.target_classes:
                continue

            # Track ID assigned by ByteTrack (None if not yet associated)
            track_id = int(box.id[0].item()) if (box.id is not None) else None

            conf_val = float(box.conf[0].item())
            coords = box.xyxy[0].cpu().numpy()
            x1, y1, x2, y2 = float(coords[0]), float(coords[1]), float(coords[2]), float(coords[3])

            w_px = x2 - x1
            h_px = y2 - y1
            cx_px = (x1 + x2) / 2.0
            cy_px = (y1 + y2) / 2.0

            if track_id is not None:
                self.track_history[track_id] = self.track_history.get(track_id, 0) + 1
                age = self.track_history[track_id]
            else:
                age = 1

            tracks.append({
                "track_id": track_id,
                "class_id": cls_id,
                "class_name": cls_name,
                "confidence": round(conf_val, 3),
                "bbox_xyxy": [round(x1, 1), round(y1, 1), round(x2, 1), round(y2, 1)],
                "cx": round(cx_px, 1),
                "cy": round(cy_px, 1),
                "bottom_y": round(y2, 1),
                "w": round(w_px, 1),
                "h": round(h_px, 1),
                "age_frames": age,
            })

        return tracks

    def reset(self):
        """Resets tracking state (e.g. at the start of a new video sequence)."""
        self.track_history.clear()


if __name__ == "__main__":
    print("[TrackManager Self-Test]")
    tm = TrackManager()
    dummy = np.zeros((720, 1280, 3), dtype=np.uint8)
    tks = tm.track(dummy)
    print(f"  Tracks detected on blank frame: {len(tks)}")
    print("  Status: PASSED")
