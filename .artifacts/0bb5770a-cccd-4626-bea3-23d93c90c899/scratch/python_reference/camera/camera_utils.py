import sys
import os
import cv2
from ..console_logger import logger

def list_available_cameras():
    """
    Returns a list of (index, name) tuples for all available cameras.
    """
    cameras = []
    if sys.platform.startswith('linux'):
        # Linux: Scan /sys/class/video4linux/
        v4l_path = "/sys/class/video4linux"
        if os.path.exists(v4l_path):
            video_devices = sorted([d for d in os.listdir(v4l_path) if d.startswith("video")])
            for dev_name in video_devices:
                try:
                    index = int(dev_name.replace("video", ""))
                    # Read the friendly name
                    name_file = os.path.join(v4l_path, dev_name, "name")
                    if os.path.exists(name_file):
                        with open(name_file, "r") as f:
                            friendly_name = f.read().strip()
                            cameras.append((index, friendly_name))
                except:
                    continue
    
    elif sys.platform == 'win32':
        # Windows: Try pygrabber first
        try:
            from pygrabber.dshow_graph import FilterGraph
            graph = FilterGraph()
            devices = graph.get_input_devices()
            for i, name in enumerate(devices):
                cameras.append((i, name))
            if cameras: return cameras
        except:
            pass

        # Fallback: Parse 'ffmpeg -list_devices true' with robust encoding
        try:
            import subprocess
            cmd = ["ffmpeg", "-list_devices", "true", "-f", "dshow", "-i", "dummy"]
            result = subprocess.run(cmd, stderr=subprocess.PIPE, text=True, errors='replace')
            
            lines = result.stderr.split('\n')
            for line in lines:
                # Look for lines like: [dshow @ ...]  "Camera Name" (video)
                # We look for the quotes and the "(video)" tag seen in the user's log
                if '"' in line and "(video)" in line and "Alternative name" not in line:
                    parts = line.split('"')
                    if len(parts) >= 3:
                        device_name = parts[1]
                        if device_name not in [c[1] for c in cameras]:
                            cameras.append((len(cameras), device_name))
        except Exception as e:
            logger.warning(f"FFMPEG device scan failed: {e}")
            print(f"[DEBUG] FFMPEG scan error: {e}")

        # If no cameras found, print the raw output to help the user
        if not cameras and 'result' in locals():
            print("\n[DEBUG] Raw FFMPEG Device List Output:")
            print(result.stderr)
            print("-" * 30)

        # Last resort fallback to indices only if still empty
        if not cameras:
            for i in range(3):
                cap = cv2.VideoCapture(i, cv2.CAP_DSHOW)
                if cap.isOpened():
                    cameras.append((i, f"Camera {i}"))
                    cap.release()
    
    return cameras

def find_camera_index(target_name="Brio 300"):
    """
    Finds the index of the camera matching 'target_name'.
    Returns (index, name).
    """
    logger.info(f"Searching for camera: '{target_name}'...")
    print(f"Searching for camera: '{target_name}'...")

    if sys.platform.startswith('linux'):
        # Linux: Scan /sys/class/video4linux/
        v4l_path = "/sys/class/video4linux"
        if os.path.exists(v4l_path):
            video_devices = sorted([d for d in os.listdir(v4l_path) if d.startswith("video")])
            for dev_name in video_devices:
                try:
                    index = int(dev_name.replace("video", ""))
                except ValueError:
                    continue

                # Read the friendly name
                name_file = os.path.join(v4l_path, dev_name, "name")
                if os.path.exists(name_file):
                    with open(name_file, "r") as f:
                        friendly_name = f.read().strip()
                        if target_name.lower() in friendly_name.lower():
                            msg = f"Found '{target_name}' at index {index} ({friendly_name})"
                            logger.info(msg)
                            print(msg)
                            return index, friendly_name
    
    elif sys.platform == 'win32':
        # Windows: Use pygrabber if available
        try:
            from pygrabber.dshow_graph import FilterGraph
            graph = FilterGraph()
            devices = graph.get_input_devices()
            for i, name in enumerate(devices):
                if target_name.lower() in name.lower():
                    msg = f"Found '{target_name}' at index {i} ({name})"
                    logger.info(msg)
                    print(msg)
                    return i, name
            
            # If we are here, we didn't find the target. Try to find *any* camera.
            if devices:
                msg = f"Target camera '{target_name}' not found. Using first available: {devices[0]} (Index 0)"
                logger.warning(msg)
                print(msg)
                return 0, devices[0]

        except ImportError:
            msg = "'pygrabber' not installed on Windows. Cannot search by name. Defaulting to index 0."
            logger.warning(msg)
            print(msg)
            return 0, "Default Camera (Index 0)"
        except Exception as e:
            msg = f"Error during camera search on Windows: {e}. Defaulting to index 0."
            logger.error(msg)
            print(msg)
            return 0, "Default Camera (Index 0)"

    msg = f"Camera '{target_name}' not found. Defaulting to index 0."
    logger.warning(msg)
    print(msg)
    return 0, "Default Camera (Index 0)"
