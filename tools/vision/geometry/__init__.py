"""
RoadSense Vision Geometry Module
--------------------------------
Pure mathematical ground-plane projections, camera extrinsics, and 3D bounding geometry.
"""

from .camera_extrinsics import CameraExtrinsics
from .ground_plane_projector import GroundPlaneProjector
from .box_dimensions import BoxDimensions

__all__ = ["CameraExtrinsics", "GroundPlaneProjector", "BoxDimensions"]
