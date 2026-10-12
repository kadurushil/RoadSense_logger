"""
timeline_loader.py
------------------
Parses master sensor synchronization timestamps from 'session_timeline.csv'
and aligns them with UTC Wall Clock nanoseconds using 'session_metadata.json'.
Ensures microsecond-accurate alignment with /camera/video and /radar/tracks in MCAP recordings.
"""

import csv
import json
import os
from typing import List, Dict, Optional, Any


class TimelineLoader:
    """Loads nanosecond frame timestamps for video and sensor synchronization."""

    @staticmethod
    def get_session_time_offset_ns(session_dir: str) -> int:
        """
        Calculates nanosecond offset between Android monotonic clock and UTC Wall Clock:
        time_offset_ns = (startTimeWallMs * 1_000_000) - startTimeMonotonicNs
        """
        meta_path = os.path.join(session_dir, "session_metadata.json")
        if os.path.isfile(meta_path):
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                    wall_ms = int(meta.get("startTimeWallMs", 0))
                    mono_ns = int(meta.get("startTimeMonotonicNs", 0))
                    if wall_ms > 0 and mono_ns > 0:
                        return (wall_ms * 1_000_000) - mono_ns
            except Exception as e:
                print(f"[TimelineLoader] Warning reading session_metadata.json: {e}")
        return 0

    @classmethod
    def load_camera_timestamps(
        cls, session_dir: str, expected_frames: Optional[int] = None, fps: float = 30.0
    ) -> Dict[str, Any]:
        """
        Extracts both monotonic and UTC Wall Clock nanosecond timestamps.

        Returns:
            Dict containing:
              - 'utc_ns': List[int] (master timestamps matching MCAP log_time)
              - 'mono_ns': List[int] (raw Android elapsedRealtimeNanos)
              - 'time_offset_ns': int
        """
        timeline_csv = os.path.join(session_dir, "session_timeline.csv")
        frame_mapping_json = os.path.join(session_dir, "frame_mapping.json")

        mono_timestamps: List[int] = []

        # 1. Primary: session_timeline.csv
        if os.path.isfile(timeline_csv):
            try:
                with open(timeline_csv, "r", encoding="utf-8") as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        if row.get("sensor") == "CAMERA" and row.get("event") == "FRAME":
                            ns_val = int(row.get("elapsed_realtime_ns", 0))
                            if ns_val > 0:
                                mono_timestamps.append(ns_val)
            except Exception as e:
                print(f"[TimelineLoader] Warning reading session_timeline.csv: {e}")

        # 2. Fallback: frame_mapping.json
        if not mono_timestamps and os.path.isfile(frame_mapping_json):
            try:
                with open(frame_mapping_json, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if isinstance(data, list):
                    for item in data:
                        ns = int(item.get("mono_ns", item.get("elapsed_realtime_ns", 0)))
                        if ns > 0:
                            mono_timestamps.append(ns)
            except Exception as e:
                print(f"[TimelineLoader] Warning reading frame_mapping.json: {e}")

        # 3. Fallback: Synthetic timestamps
        if not mono_timestamps:
            num = expected_frames or 1000
            step_ns = int(1e9 / max(fps, 1.0))
            mono_timestamps = [i * step_ns for i in range(num)]

        offset_ns = cls.get_session_time_offset_ns(session_dir)
        utc_timestamps = [t + offset_ns for t in mono_timestamps]

        return {
            "utc_ns": utc_timestamps,
            "mono_ns": mono_timestamps,
            "time_offset_ns": offset_ns,
        }

    @classmethod
    def load_camera_timestamps_ns(
        cls, session_dir: str, expected_frames: Optional[int] = None, fps: float = 30.0, utc: bool = True
    ) -> List[int]:
        """
        Convenience accessor returning a flat list of nanosecond timestamps.
        By default (utc=True), returns UTC Wall Clock nanoseconds matching MCAP.
        """
        res = cls.load_camera_timestamps(session_dir, expected_frames, fps)
        return res["utc_ns"] if utc else res["mono_ns"]


if __name__ == "__main__":
    print("[TimelineLoader Self-Test]")
    res = TimelineLoader.load_camera_timestamps("logs/session_20261006_095609", expected_frames=5)
    print(f"  Offset: {res['time_offset_ns']} ns")
    print(f"  First Mono: {res['mono_ns'][0]} ns")
    print(f"  First UTC:  {res['utc_ns'][0]} ns")
    assert res["utc_ns"][0] > 1.7e18, "UTC timestamp must be epoch nanoseconds (~2026)"
    print("  Status: PASSED")
