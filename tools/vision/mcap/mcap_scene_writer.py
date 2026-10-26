"""
mcap_scene_writer.py
--------------------
Converts 3D geometric vision tracks into Foxglove SceneUpdate protobuf messages.
Renders 3D oriented cuboids and text labels in Electric Magenta (rgba: 0.85, 0.1, 0.95, 0.75)
to visually contrast with Cyan / Amber radar tracks in Foxglove Studio.
"""

from typing import List, Dict, Any
from foxglove_schemas_protobuf.SceneUpdate_pb2 import SceneUpdate


class McapSceneWriter:
    """Encodes 3D vision detections into foxglove.SceneUpdate."""

    @staticmethod
    def create_scene_update(
        timestamp_ns: int,
        tracks: List[Dict[str, Any]],
        frame_id: str = "base_link",
        lifetime_ns: int = 150_000_000,
    ) -> SceneUpdate:
        """
        Creates a foxglove.SceneUpdate protobuf message containing 3D cuboids and labels.
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
            range_m = t.get("range_m", 0.0)

            x_fwd = float(pos.get("x_fwd_m", 0.0))
            y_left = float(pos.get("y_left_m", 0.0))
            z_up = float(pos.get("z_up_m", 0.75))

            length_m = float(dim.get("length_m", 2.0))
            width_m = float(dim.get("width_m", 1.8))
            height_m = float(dim.get("height_m", 1.5))

            # 1. 3D Bounding Box Cube with Orientation Quaternion
            cube = entity.cubes.add()
            cube.pose.position.x = x_fwd
            cube.pose.position.y = y_left
            cube.pose.position.z = z_up

            orient = t.get("orientation", {})
            cube.pose.orientation.x = float(orient.get("x", 0.0))
            cube.pose.orientation.y = float(orient.get("y", 0.0))
            cube.pose.orientation.z = float(orient.get("z", 0.0))
            cube.pose.orientation.w = float(orient.get("w", 1.0))

            cube.size.x = length_m
            cube.size.y = width_m
            cube.size.z = height_m

            # Electric Magenta / Violet (Visual separation from radar cyan)
            cube.color.r = 0.85
            cube.color.g = 0.10
            cube.color.b = 0.95
            cube.color.a = 0.75

            # 2. Floating Label (Aligned with RoadSense convert_session_to_mcap.py)
            txt = entity.texts.add()
            speed_mps = t.get("speed_mps")
            if speed_mps is not None and float(speed_mps) >= 0.6:
                speed_kmh = float(speed_mps) * 3.6
                txt.text = f"ID #{track_id} | {cls_name.upper()} | {range_m:.1f}m | {speed_kmh:.0f}km/h"
            else:
                txt.text = f"ID #{track_id} | {cls_name.upper()} | {range_m:.1f}m"
            txt.pose.position.x = x_fwd
            txt.pose.position.y = y_left
            txt.pose.position.z = z_up + (height_m / 2.0) + 0.35
            txt.font_size = 0.45
            txt.billboard = True  # Always face camera view, matching radar/tracks
            txt.color.r = 1.0
            txt.color.g = 0.85
            txt.color.b = 1.0
            txt.color.a = 1.0

        return msg


if __name__ == "__main__":
    print("[McapSceneWriter Self-Test]")
    sample_tracks = [{
        "track_id": 1,
        "class_name": "car",
        "range_m": 12.5,
        "position_3d": {"x_fwd_m": 12.5, "y_left_m": -0.8, "z_up_m": 0.75},
        "dimensions_3d": {"length_m": 4.2, "width_m": 1.8, "height_m": 1.5},
    }]
    su = McapSceneWriter.create_scene_update(1463695749944206, sample_tracks)
    assert len(su.entities) == 1, "Must contain 1 entity"
    assert len(su.entities[0].cubes) == 1, "Must contain 1 cube"
    print(f"  Entities: {len(su.entities)}, Cubes: {len(su.entities[0].cubes)}")
    print("  Status: PASSED")
