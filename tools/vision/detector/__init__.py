"""
RoadSense Vision Detector Module
--------------------------------
Wraps YOLO26 object detection and ByteTrack tracking into a modular, clean interface.
"""

from .yolo_detector import YoloDetector
from .track_manager import TrackManager

__all__ = ["YoloDetector", "TrackManager"]
