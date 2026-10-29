"""
mcap_scene_writer.py
--------------------
Converts 3D and 2D vision tracks and telemetry diagnostics into standardized Foxglove
Protobuf messages and JSON Schema payloads:

Sub-Topics Supported:
  1. /vision/2d_boxes        : foxglove.ImageAnnotations (2D YOLO boxes, labels, contact points)
  2. /vision/geo_tracks      : foxglove.SceneUpdate (3D cuboids, velocity arrows, ground footprints)
  3. /vision/trajectories    : foxglove.SceneUpdate (3D multi-second trajectory breadcrumb line strips)
  4. /vision/camera_frustum  : foxglove.SceneUpdate (Camera ground-plane optical projection boundaries)
  5. /vision/diagnostics     : roadsense.VisionDiagnostics (3-tier pipeline telemetry & State Matrices)
"""

import math
from typing import List, Dict, Any, Optional
from collections import deque

from foxglove_schemas_protobuf.SceneUpdate_pb2 import SceneUpdate
from foxglove_schemas_protobuf.ImageAnnotations_pb2 import ImageAnnotations
from foxglove_schemas_protobuf.PointsAnnotation_pb2 import PointsAnnotation
from foxglove_schemas_protobuf.Point2_pb2 import Point2
from foxglove_schemas_protobuf.TextAnnotation_pb2 import TextAnnotation
from foxglove_schemas_protobuf.CircleAnnotation_pb2 import CircleAnnotation
from foxglove_schemas_protobuf.Color_pb2 import Color
from foxglove_schemas_protobuf.Point3_pb2 import Point3
from foxglove_schemas_protobuf.LinePrimitive_pb2 import LinePrimitive
from foxglove_schemas_protobuf.ArrowPrimitive_pb2 import ArrowPrimitive


# Category color palette for 2D bounding boxes and 3D cuboids
CATEGORY_COLORS = {
    "car": (0.85, 0.10, 0.95, 0.85),        # Electric Magenta
    "truck": (0.75, 0.15, 1.00, 0.85),      # Violet
    "bus": (0.65, 0.20, 1.00, 0.85),        # Deep Purple
    "motorcycle": (1.00, 0.80, 0.10, 0.85), # Vivid Amber / Gold
    "bicycle": (1.00, 0.65, 0.00, 0.85),    # Orange
    "pedestrian": (0.10, 0.85, 1.00, 0.85), # Bright Cyan
    "default": (0.85, 0.10, 0.95, 0.85),
}

VISION_DIAGNOSTICS_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "frame_index": {"type": "integer"},
        "timestamp_utc_ns": {"type": "integer"},
        "track_count": {"type": "integer"},
        "moving_track_count": {"type": "integer"},
        "ego_speed_mps": {"type": "number"},
        "ego_speed_kmh": {"type": "number"},
        "ego_yaw_rate_can_radps": {"type": "number"},
        "ego_yaw_rate_smoothed_degps": {"type": "number"},
        "ego_turning_model_active": {"type": "boolean"},
        "ego_deadband_scale": {"type": "number"},
        "lead_obstacle": {
            "type": "object",
            "properties": {
                "track_id": {"type": "integer"},
                "class_name": {"type": "string"},
                "range_m": {"type": "number"},
                "x_fwd_m": {"type": "number"},
                "y_left_m": {"type": "number"},
                "speed_kmh": {"type": "number"},
                "vx_mps": {"type": "number"},
                "vy_mps": {"type": "number"},
                "yaw_deg": {"type": "number"},
                "ttc_sec": {"type": "number"},
            }
        },
        "tracks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "track_id": {"type": "integer"},
                    "class_name": {"type": "string"},
                    "confidence": {"type": "number"},
                    "range_m": {"type": "number"},
                    "azimuth_deg": {"type": "number"},
                    "is_moving": {"type": "boolean"},
                    "speed_kmh": {"type": "number"},
                    "basin": {"type": "string"},
                    "heading_jitter_deg": {"type": "number"},
                    "state_matrix": {
                        "type": "object",
                        "properties": {
                            "state_vector": {
                                "type": "object",
                                "properties": {
                                    "x_m": {"type": "number"},
                                    "y_m": {"type": "number"},
                                    "vx_mps": {"type": "number"},
                                    "vy_mps": {"type": "number"},
                                    "yaw_deg": {"type": "number"},
                                    "yaw_rate_degps": {"type": "number"},
                                }
                            },
                            "covariance_diag": {
                                "type": "object",
                                "properties": {
                                    "sigma_x_m": {"type": "number"},
                                    "sigma_y_m": {"type": "number"},
                                    "sigma_vx_mps": {"type": "number"},
                                    "sigma_vy_mps": {"type": "number"},
                                    "sigma_yaw_deg": {"type": "number"},
                                    "sigma_yaw_rate_degps": {"type": "number"},
                                }
                            },
                            "covariance_matrix": {
                                "type": "array",
                                "items": {
                                    "type": "array",
                                    "items": {"type": "number"}
                                }
                            },
                            "residuals": {
                                "type": "object",
                                "properties": {
                                    "dx_m": {"type": "number"},
                                    "dy_m": {"type": "number"},
                                    "range_residual_m": {"type": "number"},
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


class TrajectoryTracker:
    """
    Maintains rolling trajectory breadcrumbs for active obstacles.
    Stores the last 10-15 seconds of motion per track (default 15.0 seconds at 10 Hz = 150 points).
    Automatically prunes stale tracks that have not been observed for more than max_age_seconds
    to prevent memory growth and eliminate MCAP file bloat.
    """

    def __init__(
        self,
        max_age_seconds: float = 15.0,
        min_interval_seconds: float = 0.10,
    ):
        self.max_age_ns = int(max_age_seconds * 1_000_000_000)
        self.min_interval_ns = int(min_interval_seconds * 1_000_000_000)
        self.max_points = max(2, int(max_age_seconds / max(0.01, min_interval_seconds)))

        self.history: Dict[int, deque] = {}
        self.last_seen_ns: Dict[int, int] = {}
        self.last_point_ts_ns: Dict[int, int] = {}

    def update(
        self,
        timestamp_ns: int,
        tracks: List[Dict[str, Any]],
    ) -> Dict[int, deque]:
        """
        Updates trajectory breadcrumbs with current frame tracks and prunes stale tracks.
        """
        # 1. Ingest currently detected track positions
        for t in tracks:
            tid = t.get("track_id")
            if tid is None:
                continue

            pos = t.get("position_3d", {})
            x_fwd = float(pos.get("x_fwd_m", 0.0))
            y_left = float(pos.get("y_left_m", 0.0))
            z_up = 0.08  # Ground plane elevation for clear visualization

            if tid not in self.history:
                self.history[tid] = deque(maxlen=self.max_points)
                self.last_point_ts_ns[tid] = 0

            # Subsample points at min_interval_ns (e.g. 10 Hz) or if first point
            if (timestamp_ns - self.last_point_ts_ns[tid]) >= self.min_interval_ns:
                self.history[tid].append((x_fwd, y_left, z_up))
                self.last_point_ts_ns[tid] = timestamp_ns

            self.last_seen_ns[tid] = timestamp_ns

        # 2. Prune tracks that haven't been detected in the last max_age_ns
        stale_ids = [
            tid for tid, last_ts in self.last_seen_ns.items()
            if (timestamp_ns - last_ts) > self.max_age_ns or timestamp_ns < last_ts
        ]
        for st in stale_ids:
            self.history.pop(st, None)
            self.last_seen_ns.pop(st, None)
            self.last_point_ts_ns.pop(st, None)

        return self.history


class McapSceneWriter:
    """Encodes vision detections, 2D overlays, trajectories, and telemetry diagnostics into Foxglove messages."""

    @staticmethod
    def get_category_color(cls_name: str) -> Color:
        rgba = CATEGORY_COLORS.get(cls_name.lower(), CATEGORY_COLORS["default"])
        col = Color()
        col.r, col.g, col.b, col.a = rgba[0], rgba[1], rgba[2], rgba[3]
        return col

    @staticmethod
    def create_image_annotations(
        timestamp_ns: int,
        tracks: List[Dict[str, Any]],
        image_width: int = 1280,
        image_height: int = 720,
        video_rotation_deg: int = 0,
    ) -> ImageAnnotations:
        """
        Creates a foxglove.ImageAnnotations message containing:
          - 2D Bounding Boxes (PointsAnnotation, LINE_LOOP)
          - Track ID / Class / Confidence / Range labels (TextAnnotation)
          - Ground contact patch bottom-center dots (CircleAnnotation)
        Overlays directly onto /camera/video in Foxglove Studio's Image panel.

        Coordinates in `tracks` are in upright camera coordinates (as detected by YOLO).
        If the underlying /camera/video stream in MCAP has raw sensor orientation
        (e.g. video_rotation_deg = 180° for reverse landscape phone mounts displayed
        with "rotation": 180 in Foxglove's Image panel), coordinates are transformed
        to raw sensor space so that Foxglove's viewer rotation places the annotations
        directly on top of the physical vehicles.
        """
        msg = ImageAnnotations()
        sec = int(timestamp_ns // 1_000_000_000)
        nsec = int(timestamp_ns % 1_000_000_000)

        w = float(image_width)
        h = float(image_height)

        for t in tracks:
            bbox = t.get("bbox_2d")
            if not bbox or len(bbox) < 4:
                continue
            x1, y1, x2, y2 = float(bbox[0]), float(bbox[1]), float(bbox[2]), float(bbox[3])
            track_id = t.get("track_id", "?")
            cls_name = str(t.get("class_name", "obj"))
            conf = float(t.get("confidence", 0.0))
            range_m = float(t.get("range_m", 0.0))
            color = McapSceneWriter.get_category_color(cls_name)

            # Map from upright detection coordinates to raw sensor coordinates
            # based on video_rotation_deg (e.g. 180° reverse landscape)
            if video_rotation_deg == 180:
                # Upright (x1, y1) is top-left, (x2, y2) is bottom-right.
                # In 180° raw space:
                # rx1 = w - x2, ry1 = h - y2 (raw top-left)
                # rx2 = w - x1, ry2 = h - y1 (raw bottom-right)
                rx1, ry1 = w - x2, h - y2
                rx2, ry2 = w - x1, h - y1
                box_pts = [(rx1, ry1), (rx2, ry1), (rx2, ry2), (rx1, ry2)]
                # Contact patch is bottom-center in upright space ((x1 + x2)/2, y2)
                contact_x = w - ((x1 + x2) / 2.0)
                contact_y = h - y2
                # Label is above top-left of box in upright space (x1, y1 - 4)
                label_x = w - x1
                label_y = h - max(14.0, y1 - 4.0)
            elif video_rotation_deg == 90:
                rx1, ry1 = y1, w - x2
                rx2, ry2 = y2, w - x1
                box_pts = [(rx1, ry1), (rx2, ry1), (rx2, ry2), (rx1, ry2)]
                contact_x = y2
                contact_y = w - ((x1 + x2) / 2.0)
                label_x = max(14.0, y1 - 4.0)
                label_y = w - x1
            elif video_rotation_deg == 270:
                rx1, ry1 = h - y2, x1
                rx2, ry2 = h - y1, x2
                box_pts = [(rx1, ry1), (rx2, ry1), (rx2, ry2), (rx1, ry2)]
                contact_x = h - y2
                contact_y = (x1 + x2) / 2.0
                label_x = h - max(14.0, y1 - 4.0)
                label_y = x1
            else:
                # 0 degrees: raw video is upright
                box_pts = [(x1, y1), (x2, y1), (x2, y2), (x1, y2)]
                contact_x = (x1 + x2) / 2.0
                contact_y = y2
                label_x = x1
                label_y = max(14.0, y1 - 4.0)

            # 1. 2D Bounding Box Rectangle (LINE_LOOP)
            poly = msg.points.add()
            poly.timestamp.seconds = sec
            poly.timestamp.nanos = nsec
            poly.type = PointsAnnotation.Type.LINE_LOOP
            poly.thickness = 2.0
            poly.outline_color.CopyFrom(color)
            for px, py in box_pts:
                pt = poly.points.add()
                pt.x = float(px)
                pt.y = float(py)

            # 2. Text Label positioned above the box
            txt = msg.texts.add()
            txt.timestamp.seconds = sec
            txt.timestamp.nanos = nsec
            if conf > 0:
                txt.text = f"ID #{track_id} | {cls_name.upper()} {conf:.2f} | {range_m:.1f}m"
            else:
                txt.text = f"ID #{track_id} | {cls_name.upper()} | {range_m:.1f}m"
            txt.position.x = float(label_x)
            txt.position.y = float(label_y)
            txt.font_size = 12.0
            txt.text_color.r, txt.text_color.g, txt.text_color.b, txt.text_color.a = 1.0, 1.0, 1.0, 1.0
            txt.background_color.r, txt.background_color.g, txt.background_color.b, txt.background_color.a = 0.05, 0.05, 0.05, 0.65

            # 3. Ground Contact Patch Marker (CircleAnnotation)
            dot = msg.circles.add()
            dot.timestamp.seconds = sec
            dot.timestamp.nanos = nsec
            dot.position.x = float(contact_x)
            dot.position.y = float(contact_y)
            dot.diameter = 8.0
            dot.thickness = 1.5
            dot.fill_color.r, dot.fill_color.g, dot.fill_color.b, dot.fill_color.a = 0.10, 1.00, 0.20, 0.95
            dot.outline_color.r, dot.outline_color.g, dot.outline_color.b, dot.outline_color.a = 0.0, 0.0, 0.0, 1.0

        return msg

    @staticmethod
    def create_scene_update(
        timestamp_ns: int,
        tracks: List[Dict[str, Any]],
        frame_id: str = "base_link",
        lifetime_ns: int = 150_000_000,
    ) -> SceneUpdate:
        """
        Creates an enriched foxglove.SceneUpdate message containing:
          - 3D oriented cuboids (cubes)
          - Billboard text labels (texts)
          - 3D velocity vectors (arrows) for moving tracks
          - Ground contact footprints (lines) on Z=0
        """
        msg = SceneUpdate()
        entity = msg.entities.add()
        entity.id = "vision_tracks"
        entity.frame_id = frame_id
        entity.timestamp.seconds = int(timestamp_ns // 1_000_000_000)
        entity.timestamp.nanos = int(timestamp_ns % 1_000_000_000)
        entity.lifetime.nanos = lifetime_ns

        for t in tracks:
            pos = t.get("position_3d", {})
            dim = t.get("dimensions_3d", {})
            track_id = t.get("track_id", "?")
            cls_name = t.get("class_name", "obj")
            range_m = float(t.get("range_m", 0.0))

            x_fwd = float(pos.get("x_fwd_m", 0.0))
            y_left = float(pos.get("y_left_m", 0.0))
            z_up = float(pos.get("z_up_m", 0.75))

            length_m = float(dim.get("length_m", 2.0))
            width_m = float(dim.get("width_m", 1.8))
            height_m = float(dim.get("height_m", 1.5))

            orient = t.get("orientation", {})
            qx = float(orient.get("x", 0.0))
            qy = float(orient.get("y", 0.0))
            qz = float(orient.get("z", 0.0))
            qw = float(orient.get("w", 1.0))
            yaw_rad = float(t.get("yaw_rad", 0.0) or 0.0)

            # 1. 3D Bounding Box Cube with Orientation Quaternion
            cube = entity.cubes.add()
            cube.pose.position.x = x_fwd
            cube.pose.position.y = y_left
            cube.pose.position.z = z_up
            cube.pose.orientation.x = qx
            cube.pose.orientation.y = qy
            cube.pose.orientation.z = qz
            cube.pose.orientation.w = qw
            cube.size.x = length_m
            cube.size.y = width_m
            cube.size.z = height_m

            # Electric Magenta / Violet styling
            cube.color.r = 0.85
            cube.color.g = 0.10
            cube.color.b = 0.95
            cube.color.a = 0.75

            # 2. Floating Billboard Label
            txt = entity.texts.add()
            speed_mps = t.get("speed_mps")
            yaw_deg = t.get("yaw_deg")
            speed_str = f" | {float(speed_mps) * 3.6:.0f}km/h" if (speed_mps is not None and float(speed_mps) >= 0.6) else ""
            yaw_str = f" | {yaw_deg:.0f}°" if yaw_deg is not None else ""
            txt.text = f"ID #{track_id} | {cls_name.upper()} | {range_m:.1f}m{speed_str}{yaw_str}"
            txt.pose.position.x = x_fwd
            txt.pose.position.y = y_left
            txt.pose.position.z = z_up + (height_m / 2.0) + 0.35
            txt.font_size = 0.45
            txt.billboard = True
            txt.color.r = 1.0
            txt.color.g = 0.88
            txt.color.b = 1.0
            txt.color.a = 1.0

            # 3. 3D Velocity Vector (Arrow) for moving targets
            is_moving = t.get("is_moving", False)
            spd = float(speed_mps or 0.0)
            if is_moving and spd >= 0.6:
                arrow = entity.arrows.add()
                arrow.pose.position.x = x_fwd
                arrow.pose.position.y = y_left
                arrow.pose.position.z = max(0.15, z_up - (height_m / 2.0) + 0.15)
                # Arrow points along vehicle heading
                arrow.pose.orientation.x = qx
                arrow.pose.orientation.y = qy
                arrow.pose.orientation.z = qz
                arrow.pose.orientation.w = qw
                # Scaled arrow length (proportional to speed, clamp 0.8m to 4.5m)
                arrow_len = min(4.5, max(0.8, spd / 3.0))
                arrow.shaft_length = arrow_len * 0.70
                arrow.shaft_diameter = 0.12
                arrow.head_length = arrow_len * 0.30
                arrow.head_diameter = 0.28
                # Vivid Amber / Orange (rgba: 1.0, 0.70, 0.0, 0.90)
                arrow.color.r = 1.00
                arrow.color.g = 0.70
                arrow.color.b = 0.00
                arrow.color.a = 0.90

            # 4. Ground Contact Footprint (Line Loop on Z=0.02)
            footprint = entity.lines.add()
            footprint.type = LinePrimitive.Type.LINE_LOOP
            footprint.thickness = 0.06
            footprint.scale_invariant = False
            footprint.color.r = 0.85
            footprint.color.g = 0.10
            footprint.color.b = 0.95
            footprint.color.a = 0.65

            hl = length_m / 2.0
            hw = width_m / 2.0
            cos_y = math.cos(yaw_rad)
            sin_y = math.sin(yaw_rad)
            corners = [
                (+hl, +hw),
                (-hl, +hw),
                (-hl, -hw),
                (+hl, -hw),
            ]
            for dx_c, dy_c in corners:
                gx = x_fwd + (dx_c * cos_y - dy_c * sin_y)
                gy = y_left + (dx_c * sin_y + dy_c * cos_y)
                pt = footprint.points.add()
                pt.x = float(gx)
                pt.y = float(gy)
                pt.z = 0.02

        return msg

    @staticmethod
    def create_trajectories_update(
        timestamp_ns: int,
        trajectory_history: Dict[int, deque],
        frame_id: str = "base_link",
        lifetime_ns: int = 150_000_000,
    ) -> SceneUpdate:
        """
        Creates a foxglove.SceneUpdate message for /vision/trajectories.
        Renders multi-second breadcrumb line strips per active track ID.
        """
        msg = SceneUpdate()
        entity = msg.entities.add()
        entity.id = "vision_trajectories"
        entity.frame_id = frame_id
        entity.timestamp.seconds = int(timestamp_ns // 1_000_000_000)
        entity.timestamp.nanos = int(timestamp_ns % 1_000_000_000)
        entity.lifetime.nanos = lifetime_ns

        for track_id, pts in trajectory_history.items():
            if len(pts) < 2:
                continue
            line = entity.lines.add()
            line.type = LinePrimitive.Type.LINE_STRIP
            line.thickness = 0.08
            line.scale_invariant = False
            # Translucent Electric Magenta
            line.color.r = 0.85
            line.color.g = 0.15
            line.color.b = 0.95
            line.color.a = 0.70

            for pt in pts:
                # pt is (x, y, z)
                p = line.points.add()
                p.x = float(pt[0])
                p.y = float(pt[1])
                p.z = float(pt[2]) if len(pt) > 2 else 0.08

        return msg

    @staticmethod
    def create_camera_frustum_update(
        timestamp_ns: int,
        frame_id: str = "base_link",
        lifetime_ns: int = 500_000_000,
        cam_height_m: float = 1.25,
        near_range_m: float = 2.0,
        far_range_m: float = 55.0,
        half_fov_deg: float = 33.0,
    ) -> SceneUpdate:
        """
        Creates a foxglove.SceneUpdate message for /vision/camera_frustum.
        Renders the ground-plane projection trapezoid on Z=0.
        """
        msg = SceneUpdate()
        entity = msg.entities.add()
        entity.id = "camera_frustum"
        entity.frame_id = frame_id
        entity.timestamp.seconds = int(timestamp_ns // 1_000_000_000)
        entity.timestamp.nanos = int(timestamp_ns % 1_000_000_000)
        entity.lifetime.nanos = lifetime_ns

        tan_half = math.tan(math.radians(half_fov_deg))
        y_near = near_range_m * tan_half
        y_far = far_range_m * tan_half

        # 1. Ground trapezoid boundary
        trap = entity.lines.add()
        trap.type = LinePrimitive.Type.LINE_LOOP
        trap.thickness = 0.05
        trap.scale_invariant = False
        trap.color.r, trap.color.g, trap.color.b, trap.color.a = 0.20, 0.65, 1.00, 0.45
        for x, y in [
            (near_range_m, +y_near),
            (far_range_m, +y_far),
            (far_range_m, -y_far),
            (near_range_m, -y_near),
        ]:
            p = trap.points.add()
            p.x, p.y, p.z = float(x), float(y), 0.0

        # 2. Camera sight lines from camera origin to ground corners
        rays = entity.lines.add()
        rays.type = LinePrimitive.Type.LINE_LIST
        rays.thickness = 0.04
        rays.scale_invariant = False
        rays.color.r, rays.color.g, rays.color.b, rays.color.a = 0.20, 0.65, 1.00, 0.35
        for gx, gy in [
            (near_range_m, +y_near),
            (near_range_m, -y_near),
            (far_range_m, +y_far),
            (far_range_m, -y_far),
        ]:
            # Origin
            p0 = rays.points.add()
            p0.x, p0.y, p0.z = 0.0, 0.0, float(cam_height_m)
            # Ground Corner
            p1 = rays.points.add()
            p1.x, p1.y, p1.z = float(gx), float(gy), 0.0

        return msg

    @staticmethod
    def create_diagnostics_message(
        timestamp_ns: int,
        frame_idx: int,
        tracks: List[Dict[str, Any]],
        ego_speed_mps: float = 0.0,
        ego_yaw_rate_can_radps: float = 0.0,
        ego_yaw_rate_smoothed_radps: float = 0.0,
        turning_model_active: bool = False,
        deadband_scale: float = 1.0,
    ) -> Dict[str, Any]:
        """
        Creates a structured dictionary matching roadsense.VisionDiagnostics JSON schema:
          - Tier 1: Ego & pipeline dynamics
          - Tier 2: Lead obstacle summary for 1-click plotting
          - Tier 3: Per-track array with complete State Matrix x and Covariance P
        """
        moving_count = sum(1 for t in tracks if t.get("is_moving", False))

        # Find closest obstacle in front (Tier 2 Lead Obstacle)
        lead_obs: Optional[Dict[str, Any]] = None
        min_range = float("inf")
        for t in tracks:
            r = float(t.get("range_m", float("inf")))
            pos = t.get("position_3d", {})
            xf = float(pos.get("x_fwd_m", 0.0))
            if xf > 0.5 and r < min_range:
                min_range = r
                spd_mps = float(t.get("speed_mps") or 0.0)
                lead_obs = {
                    "track_id": int(t.get("track_id", 0)),
                    "class_name": str(t.get("class_name", "obj")),
                    "range_m": round(r, 2),
                    "x_fwd_m": round(xf, 2),
                    "y_left_m": round(float(pos.get("y_left_m", 0.0)), 2),
                    "speed_kmh": round(spd_mps * 3.6, 2),
                    "vx_mps": round(float(t.get("vx_mps") or 0.0), 2),
                    "vy_mps": round(float(t.get("vy_mps") or 0.0), 2),
                    "yaw_deg": round(float(t.get("yaw_deg") or 0.0), 2),
                    "ttc_sec": round(r / max(0.1, ego_speed_mps - float(t.get("vx_mps") or 0.0)), 2) if (ego_speed_mps - float(t.get("vx_mps") or 0.0)) > 0.5 else 99.9,
                }

        # Build Tier 3 Per-Track diagnostics & State Matrix
        diag_tracks = []
        for t in tracks:
            track_id = int(t.get("track_id", 0))
            pos = t.get("position_3d", {})
            xf = float(pos.get("x_fwd_m", 0.0))
            yl = float(pos.get("y_left_m", 0.0))
            vx = float(t.get("vx_mps") or 0.0)
            vy = float(t.get("vy_mps") or 0.0)
            spd = float(t.get("speed_mps") or 0.0)
            yaw_deg = float(t.get("yaw_deg") or 0.0)
            raw_secant_deg = float(t.get("raw_heading_deg", yaw_deg) or yaw_deg)
            jitter_deg = abs(raw_secant_deg - yaw_deg)
            if jitter_deg > 180.0:
                jitter_deg = 360.0 - jitter_deg

            # Depth-dependent positional uncertainty model
            sigma_x = max(0.15, 0.015 * xf)
            sigma_y = max(0.08, 0.008 * xf)
            sigma_vx = 0.35 if t.get("is_moving", False) else 0.10
            sigma_vy = 0.25 if t.get("is_moving", False) else 0.08
            sigma_yaw = max(1.5, 0.08 * xf) if t.get("is_moving", False) else 0.8

            cov_matrix = [
                [round(sigma_x**2, 4), 0.0, round(0.1 * sigma_x * sigma_vx, 4), 0.0, 0.0, 0.0],
                [0.0, round(sigma_y**2, 4), 0.0, round(0.1 * sigma_y * sigma_vy, 4), 0.0, 0.0],
                [round(0.1 * sigma_x * sigma_vx, 4), 0.0, round(sigma_vx**2, 4), 0.0, 0.0, 0.0],
                [0.0, round(0.1 * sigma_y * sigma_vy, 4), 0.0, round(sigma_vy**2, 4), 0.0, 0.0],
                [0.0, 0.0, 0.0, 0.0, round(sigma_yaw**2, 4), 0.0],
                [0.0, 0.0, 0.0, 0.0, 0.0, 0.25],
            ]

            diag_tracks.append({
                "track_id": track_id,
                "class_name": str(t.get("class_name", "obj")),
                "confidence": round(float(t.get("confidence", 0.0)), 3),
                "range_m": round(float(t.get("range_m", 0.0)), 2),
                "azimuth_deg": round(math.degrees(math.atan2(yl, max(0.001, xf))), 2),
                "is_moving": bool(t.get("is_moving", False)),
                "speed_kmh": round(spd * 3.6, 2),
                "basin": str(t.get("basin", "none")),
                "heading_jitter_deg": round(jitter_deg, 2),
                "state_matrix": {
                    "state_vector": {
                        "x_m": round(xf, 3),
                        "y_m": round(yl, 3),
                        "vx_mps": round(vx, 3),
                        "vy_mps": round(vy, 3),
                        "yaw_deg": round(yaw_deg, 2),
                        "yaw_rate_degps": 0.0,
                    },
                    "covariance_diag": {
                        "sigma_x_m": round(sigma_x, 3),
                        "sigma_y_m": round(sigma_y, 3),
                        "sigma_vx_mps": round(sigma_vx, 3),
                        "sigma_vy_mps": round(sigma_vy, 3),
                        "sigma_yaw_deg": round(sigma_yaw, 2),
                        "sigma_yaw_rate_degps": 0.50,
                    },
                    "covariance_matrix": cov_matrix,
                    "residuals": {
                        "dx_m": round(t.get("residual_dx_m", 0.0), 3),
                        "dy_m": round(t.get("residual_dy_m", 0.0), 3),
                        "range_residual_m": round(t.get("residual_range_m", 0.0), 3),
                    }
                }
            })

        return {
            "frame_index": int(frame_idx),
            "timestamp_utc_ns": int(timestamp_ns),
            "track_count": len(tracks),
            "moving_track_count": moving_count,
            "ego_speed_mps": round(ego_speed_mps, 3),
            "ego_speed_kmh": round(ego_speed_mps * 3.6, 2),
            "ego_yaw_rate_can_radps": round(ego_yaw_rate_can_radps, 4),
            "ego_yaw_rate_smoothed_degps": round(math.degrees(ego_yaw_rate_smoothed_radps), 2),
            "ego_turning_model_active": bool(turning_model_active),
            "ego_deadband_scale": round(deadband_scale, 3),
            "lead_obstacle": lead_obs,
            "tracks": diag_tracks,
        }


if __name__ == "__main__":
    print("[McapSceneWriter Comprehensive Self-Test]")
    sample_tracks = [{
        "track_id": 4991,
        "class_name": "car",
        "confidence": 0.89,
        "range_m": 28.05,
        "bbox_2d": [580.0, 270.0, 640.0, 320.0],
        "position_3d": {"x_fwd_m": 28.05, "y_left_m": 1.45, "z_up_m": 0.75},
        "dimensions_3d": {"length_m": 4.2, "width_m": 1.8, "height_m": 1.5},
        "orientation": {"x": 0.0, "y": 0.0, "z": 1.0, "w": 0.0},
        "yaw_rad": 3.1415,
        "yaw_deg": 180.0,
        "speed_mps": 11.2,
        "vx_mps": -11.2,
        "vy_mps": 0.0,
        "is_moving": True,
    }]
    ts = 1792750280123456789

    # 1. ImageAnnotations
    ia = McapSceneWriter.create_image_annotations(ts, sample_tracks)
    assert len(ia.points) == 1, "Must have 1 2D bbox"
    assert len(ia.circles) == 1, "Must have 1 contact point circle"
    assert len(ia.texts) == 1, "Must have 1 text label"
    print("  [+] ImageAnnotations OK")

    # 2. SceneUpdate
    su = McapSceneWriter.create_scene_update(ts, sample_tracks)
    assert len(su.entities[0].cubes) == 1, "Must have 1 cube"
    assert len(su.entities[0].arrows) == 1, "Must have 1 velocity arrow"
    assert len(su.entities[0].lines) == 1, "Must have 1 ground footprint line"
    print("  [+] SceneUpdate OK")

    # 3. Trajectories
    history = {4991: deque([(28.0, 1.4, 0.1), (27.0, 1.4, 0.1), (26.0, 1.4, 0.1)])}
    tu = McapSceneWriter.create_trajectories_update(ts, history)
    assert len(tu.entities[0].lines) == 1, "Must have 1 trajectory line strip"
    print("  [+] Trajectories OK")

    # 4. Camera Frustum
    fu = McapSceneWriter.create_camera_frustum_update(ts)
    assert len(fu.entities[0].lines) == 2, "Must have frustum lines"
    print("  [+] Camera Frustum OK")

    # 5. Diagnostics
    diag = McapSceneWriter.create_diagnostics_message(
        ts, 1420, sample_tracks, ego_speed_mps=10.0, ego_yaw_rate_can_radps=-0.02,
        ego_yaw_rate_smoothed_radps=-0.019, turning_model_active=True, deadband_scale=0.8
    )
    assert diag["ego_speed_kmh"] == 36.0
    assert diag["lead_obstacle"]["track_id"] == 4991
    assert "state_matrix" in diag["tracks"][0]
    print("  [+] Diagnostics OK")
    print("All Self-Tests PASSED successfully!")
