"""
RoadSense IMU Processing Package
================================
Modular processing, 6-DOF spatial transformation, and GNSS-IMU course fusion.

Exports:
  - ImuFrameTransformer: 3D mounting extrinsics and coordinate rotations.
  - GnssImuHeadingFilter: 2-state EKF with ZUPT and GPS Doppler course fusion.
  - ImuSessionProcessor: Stream processor yielding synchronized raw and vehicle samples.
"""

from .transforms import ImuFrameTransformer
from .heading_filter import GnssImuHeadingFilter, wrap_pi, wrap_360
from .processor import ImuSessionProcessor

__all__ = [
    "ImuFrameTransformer",
    "GnssImuHeadingFilter",
    "ImuSessionProcessor",
    "wrap_pi",
    "wrap_360",
]

