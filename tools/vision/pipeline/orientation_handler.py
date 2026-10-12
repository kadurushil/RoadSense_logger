"""
orientation_handler.py
----------------------
Detects MP4 container rotation metadata from 'tkhd' atom and applies
lossless in-memory orientation corrections (e.g. 180° reverse landscape).
"""

import os
import struct
from typing import Optional
import cv2
import numpy as np


class OrientationHandler:
    """Detects and corrects video frame rotation."""

    @staticmethod
    def detect_video_rotation(video_path: str) -> int:
        """
        Parses MP4 'tkhd' track header atom to extract container rotation angle.

        Returns:
            Rotation in degrees (0, 90, 180, 270). Defaults to 0 if not found.
        """
        if not os.path.isfile(video_path):
            return 0

        try:
            file_size = os.path.getsize(video_path)
            with open(video_path, "rb") as f:
                # 1. Box-by-box search
                offset = 0
                while offset + 8 <= file_size and offset < 50 * 1024 * 1024:
                    f.seek(offset)
                    hdr = f.read(8)
                    if len(hdr) < 8:
                        break
                    box_size, box_type = struct.unpack(">I4s", hdr)
                    if box_size == 1:
                        f.seek(offset + 8)
                        large_size = f.read(8)
                        if len(large_size) < 8:
                            break
                        box_size = struct.unpack(">Q", large_size)[0]
                    if box_size <= 0:
                        break

                    if box_type == b"moov":
                        f.seek(offset)
                        moov_data = f.read(min(box_size, 4 * 1024 * 1024))
                        tkhd_idx = moov_data.find(b"tkhd")
                        if tkhd_idx != -1 and tkhd_idx + 80 <= len(moov_data):
                            matrix_data = moov_data[tkhd_idx + 44 : tkhd_idx + 44 + 36]
                            m = struct.unpack(">9i", matrix_data)
                            a, b, u, c, d, v, x, y, w = [round(val / 65536.0, 2) for val in m]
                            if a == -1.0 and d == -1.0:
                                return 180
                            elif b == 1.0 and c == -1.0:
                                return 90
                            elif b == -1.0 and c == 1.0:
                                return 270
                            elif a == 1.0 and d == 1.0:
                                return 0
                        break
                    offset += box_size

                # 2. Tail search (Android Camera2 often appends moov at file end)
                search_len = min(file_size, 10 * 1024 * 1024)
                f.seek(max(0, file_size - search_len))
                data = f.read(search_len)
                idx = data.find(b"tkhd")
                if idx != -1 and idx + 80 <= len(data):
                    matrix_data = data[idx + 44 : idx + 44 + 36]
                    m = struct.unpack(">9i", matrix_data)
                    a, b, u, c, d, v, x, y, w = [round(val / 65536.0, 2) for val in m]
                    if a == -1.0 and d == -1.0:
                        return 180
                    elif b == 1.0 and c == -1.0:
                        return 90
                    elif b == -1.0 and c == 1.0:
                        return 270
                    elif a == 1.0 and d == 1.0:
                        return 0
        except Exception:
            pass

        return 0

    @classmethod
    def apply_rotation(cls, frame: np.ndarray, rotation_deg: int) -> np.ndarray:
        """
        Rotates an image frame upright based on the detected angle.
        """
        if rotation_deg == 180:
            return cv2.rotate(frame, cv2.ROTATE_180)
        elif rotation_deg == 90:
            return cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
        elif rotation_deg == 270:
            return cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
        return frame


if __name__ == "__main__":
    print("[OrientationHandler Self-Test]")
    dummy = np.zeros((720, 1280, 3), dtype=np.uint8)
    rot = OrientationHandler.apply_rotation(dummy, 180)
    assert rot.shape == dummy.shape, "Shape must remain identical after 180 rotation"
    print(f"  Rotated shape: {rot.shape}")
    print("  Status: PASSED")
