"""
RoadSense Vision Geometry Module
--------------------------------
Pure mathematical ground-plane projections, camera extrinsics, 3D bounding geometry,
and session-wide pitch back-calculation solver.
"""

from .camera_extrinsics import CameraExtrinsics
from .ground_plane_projector import GroundPlaneProjector
from .box_dimensions import BoxDimensions
from .dynamic_pitch_solver import DynamicPitchSolver

__all__ = ["CameraExtrinsics", "GroundPlaneProjector", "BoxDimensions", "DynamicPitchSolver"]
