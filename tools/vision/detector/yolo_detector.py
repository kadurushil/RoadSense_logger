"""
yolo_detector.py
----------------
Ultralytics YOLO model wrapper for 2D vehicle and pedestrian detection.
Filters automotive classes and returns clean, structured bounding box dictionaries.
"""

import os
from typing import List, Dict, Any, Optional
import numpy as np
import torch
from ultralytics import YOLO


# Standard COCO target classes for road perception
DEFAULT_TARGET_CLASSES = ["car", "truck", "bus", "motorcycle", "bicycle", "person"]


class YoloDetector:
    """Wraps YOLO inference with class filtering and device optimization."""

    def __init__(
        self,
        model_path: str = "tools/vision/models/yolo26n.pt",
        confidence_threshold: float = 0.25,
        iou_threshold: float = 0.45,
        target_classes: Optional[List[str]] = None,
        device: Optional[str] = None,
    ):
        if not os.path.isfile(model_path):
            raise FileNotFoundError(f"YOLO model not found at: {model_path}")

        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.model = YOLO(model_path)
        self.conf = confidence_threshold
        self.iou = iou_threshold
        self.target_classes = target_classes or DEFAULT_TARGET_CLASSES
        self.class_names = self.model.names  # Dict mapping class ID to name

    def detect(self, frame: np.ndarray) -> List[Dict[str, Any]]:
        """
        Runs 2D object detection on a single BGR image frame.

        Args:
            frame: BGR numpy image array.

        Returns:
            List of detected obstacles with bbox, class, confidence, and center coordinates.
        """
        results = self.model.predict(
            source=frame,
            conf=self.conf,
            iou=self.iou,
            device=self.device,
            verbose=False,
        )

        detections = []
        if not results or len(results) == 0:
            return detections

        boxes = results[0].boxes
        if boxes is None or len(boxes) == 0:
            return detections

        for box in boxes:
            cls_id = int(box.cls[0].item())
            cls_name = self.class_names.get(cls_id, "unknown")

            if cls_name not in self.target_classes:
                continue

            conf_val = float(box.conf[0].item())
            coords = box.xyxy[0].cpu().numpy()
            x1, y1, x2, y2 = float(coords[0]), float(coords[1]), float(coords[2]), float(coords[3])

            w_px = x2 - x1
            h_px = y2 - y1
            cx_px = (x1 + x2) / 2.0
            cy_px = (y1 + y2) / 2.0

            detections.append({
                "bbox_xyxy": [round(x1, 1), round(y1, 1), round(x2, 1), round(y2, 1)],
                "cx": round(cx_px, 1),
                "cy": round(cy_px, 1),
                "bottom_y": round(y2, 1),
                "w": round(w_px, 1),
                "h": round(h_px, 1),
                "class_id": cls_id,
                "class_name": cls_name,
                "confidence": round(conf_val, 3),
            })

        return detections


if __name__ == "__main__":
    # Self-test: check initialization and blank frame inference
    detector = YoloDetector()
    print("[YoloDetector Self-Test]")
    print(f"  Device: {detector.device}")
    print(f"  Target Classes: {detector.target_classes}")
    dummy = np.zeros((720, 1280, 3), dtype=np.uint8)
    dets = detector.detect(dummy)
    print(f"  Blank frame detections: {len(dets)}")
    print("  Status: PASSED")
