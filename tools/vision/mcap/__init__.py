"""
RoadSense Vision MCAP Module
----------------------------
Non-destructive Foxglove MCAP topic injection for 3D geometric vision tracks.
"""

from .mcap_scene_writer import McapSceneWriter
from .inject_vision_tracks import inject_vision_tracks_to_mcap

__all__ = ["McapSceneWriter", "inject_vision_tracks_to_mcap"]
