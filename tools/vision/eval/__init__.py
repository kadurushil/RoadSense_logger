"""
RoadSense Vision Evaluation Module
----------------------------------
Programmatic spatio-temporal validation and benchmarking of vision tracks vs. radar ground truth.
"""

from .spatial_associator import SpatialAssociator
from .error_metrics import ErrorMetricsCalculator
from .evaluate_vision_vs_radar import evaluate_session

__all__ = ["SpatialAssociator", "ErrorMetricsCalculator", "evaluate_session"]
