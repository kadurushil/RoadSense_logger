import os
import time
import datetime
import subprocess
import signal
import glob
from ..console_logger import logger

class CameraRecorder:
    def __init__(self, device_name_filter="Brio 300", output_dir=".", requested_fps=30, frame_size=(640, 480)):
        self.device_name_filter = device_name_filter
        self.output_dir = output_dir
        self.requested_fps = requested_fps
        self.frame_size = frame_size
        
        self.process = None
        self.start_time = None
        self.device_path = "/dev/video0" # Default
        self.actual_filename = None
        
        self._find_device()

    def _find_device(self):
        # Linux: Scan /sys/class/video4linux
        if os.name == 'posix':
            try:
                # Scan /sys/class/video4linux/video*/name
                paths = glob.glob("/sys/class/video4linux/video*")
                candidates = []
                
                for p in paths:
                    try:
                        with open(os.path.join(p, "name"), "r") as f:
                            name = f.read().strip()
                            if self.device_name_filter.lower() in name.lower():
                                # Extract index from "videoN"
                                dirname = os.path.basename(p)
                                idx = int(dirname.replace("video", ""))
                                candidates.append((idx, f"/dev/{dirname}"))
                    except:
                        pass
                
                if candidates:
                    # Sort by index (lowest first -> usually capture device)
                    candidates.sort(key=lambda x: x[0])
                    self.device_path = candidates[0][1]
                    logger.info(f"[CameraRecorder] Found candidate devices: {candidates}")
                    logger.info(f"[CameraRecorder] Selected capture device: {self.device_path}")
                    return

            except Exception as e:
                logger.error(f"[CameraRecorder] Device scan error: {e}")
            
            logger.info(f"[CameraRecorder] Using default Linux device: {self.device_path}")

        # Windows: Parse 'ffmpeg -list_devices true -f dshow -i dummy'
        elif os.name == 'nt':
            self.device_path = f"video={self.device_name_filter}" # Default fallback
            try:
                cmd = ["ffmpeg", "-list_devices", "true", "-f", "dshow", "-i", "dummy"]
                # ffmpeg writes device list to stderr
                result = subprocess.run(cmd, stderr=subprocess.PIPE, text=True, encoding='utf-8')
                
                # Parse output for "DirectShow video devices"
                lines = result.stderr.split('\n')
                found = False
                for line in lines:
                    if "DirectShow video devices" in line:
                        found = True
                        continue
                    if found and "DirectShow audio devices" in line:
                        break
                    
                    if found and self.device_name_filter.lower() in line.lower():
                        # Line format: [dshow @ ...]  "Camera Name"
                        # Extract content between quotes
                        start = line.find('"')
                        end = line.rfind('"')
                        if start != -1 and end != -1:
                            full_name = line[start+1:end]
                            self.device_path = f"video={full_name}"
                            logger.info(f"[CameraRecorder] Found Windows device: {self.device_path}")
                            return
            except Exception as e:
                logger.error(f"[CameraRecorder] Windows device scan error: {e}")
            
            logger.info(f"[CameraRecorder] Using fallback Windows device: {self.device_path}")

    def start(self):
        if self.process:
            logger.warning("[CameraRecorder] Already recording.")
            return

        ts_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.actual_filename = f"cam_{ts_str}.mp4"
        out_path = os.path.join(self.output_dir, self.actual_filename)
        
        # Build Command based on OS
        cmd = ["ffmpeg", "-y"]
        
        if os.name == 'posix':
            # Linux (V4L2)
            cmd.extend([
                "-f", "v4l2",
                "-input_format", "yuyv422",
                "-video_size", f"{self.frame_size[0]}x{self.frame_size[1]}",
                "-framerate", str(self.requested_fps),
                "-i", self.device_path,
                "-c:v", "libx264",
                "-preset", "ultrafast"
            ])
        elif os.name == 'nt':
            # Windows (DirectShow)
            cmd.extend([
                "-f", "dshow",
                "-video_size", f"{self.frame_size[0]}x{self.frame_size[1]}",
                "-framerate", str(self.requested_fps),
                "-i", self.device_path,
                "-c:v", "libx264",
                "-preset", "ultrafast"
            ])
        
        # Common Output Settings
        cmd.extend([
            "-pix_fmt", "yuv420p", # Ensure compatibility
            out_path
        ])
        
        try:
            msg = (f"\n--- Camera Recording Configuration ---\n"
                   f"  Device:     {self.device_path}\n"
                   f"  Resolution: {self.frame_size[0]}x{self.frame_size[1]}\n"
                   f"  Framerate:  {self.requested_fps} FPS\n"
                   f"  Format:     H.264 (libx264 ultrafast)\n"
                   f"  Output:     {self.actual_filename}\n"
                   f"--------------------------------------\n")
            logger.info(msg)
            print(msg)

            logger.info(f"[CameraRecorder] Starting FFMPEG: {' '.join(cmd)}")
            # Capture stderr for debugging, open stdin for control
            self.process = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                text=True, # Decode to string
                bufsize=1 # Line buffered
            )
            
            # --- Wait for ffmpeg to actually start capturing ---
            # We look for "frame=" in stderr, which indicates frames are being processed.
            logger.info("[CameraRecorder] Waiting for ffmpeg to start capturing...")
            start_wait = time.time()
            captured_setup_logs = []
            while time.time() - start_wait < 5.0: # 5s timeout
                line = self.process.stderr.readline()
                if line:
                    captured_setup_logs.append(line.strip())
                    # logger.debug(f"[FFMPEG] {line.strip()}") # Too noisy for main log usually, but good for debug
                
                if "frame=" in line or "Press [q] to stop" in line:
                    self.start_time = time.time()
                    logger.info(f"[CameraRecorder] ffmpeg capture started successfully at {self.start_time}")
                    break
                
                # Check if process died during setup
                if self.process.poll() is not None:
                    remaining_err = self.process.stderr.read()
                    captured_setup_logs.append(remaining_err)
                    logger.error(f"[CameraRecorder] FFMPEG process exited unexpectedly during setup.")
                    break
            
            if not self.start_time:
                logger.warning("[CameraRecorder] ffmpeg start detection failed or timed out.")
                if captured_setup_logs:
                    logger.error("[CameraRecorder] Recent FFMPEG logs:\n" + "\n".join(captured_setup_logs[-10:]))
                self.start_time = time.time() # Fallback for now, but is_recording() will catch the failure
            
            logger.info(f"[CameraRecorder] Initialization phase complete (PID: {self.process.pid})")
        except Exception as e:
            logger.error(f"[CameraRecorder] Critical exception starting ffmpeg: {e}")
            self.process = None

    def stop(self, wait=True):
        if self.process:
            logger.info(f"[CameraRecorder] Stopping FFMPEG (PID: {self.process.pid})...")
            
            # Try graceful stop by sending 'q' to stdin
            try:
                if self.process.stdin:
                    self.process.stdin.write("q")
                    self.process.stdin.flush()
            except Exception as e:
                logger.warning(f"[CameraRecorder] Failed to send 'q' to ffmpeg: {e}")

            # Wait for graceful exit
            try:
                if wait:
                    stdout, stderr = self.process.communicate(timeout=5)
                    if stderr:
                        logger.info(f"[CameraRecorder] FFMPEG Exit Output:\n{stderr}")
            except subprocess.TimeoutExpired:
                logger.warning("[CameraRecorder] FFMPEG did not exit gracefully, killing...")
                self.process.terminate() # Try terminate first
                time.sleep(1)
                if self.process.poll() is None:
                    self.process.kill() # Force kill
            
            self.process = None
            logger.info("[CameraRecorder] Stopped.")

    def is_recording(self):
        if self.process:
            poll_result = self.process.poll()
            if poll_result is not None:
                # Process died
                logger.error(f"[CameraRecorder] FFMPEG process died (Exit Code: {poll_result})")
                try:
                    # Attempt to read any remaining error messages
                    if os.name == 'posix':
                        import fcntl
                        import os
                        # Make stderr non-blocking to read what's left
                        fd = self.process.stderr.fileno()
                        fl = fcntl.fcntl(fd, fcntl.F_GETFL)
                        fcntl.fcntl(fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)
                        stderr_content = self.process.stderr.read()
                    else:
                        # On Windows, just read normally since the process is already dead
                        stderr_content = self.process.stderr.read()
                    
                    if stderr_content:
                        logger.error(f"[CameraRecorder] Final FFMPEG Error Output:\n{stderr_content}")
                        print(f"\n[FFMPEG ERROR]\n{stderr_content}\n")
                except Exception as e:
                    logger.warning(f"[CameraRecorder] Could not read final stderr: {e}")
                return False
            return True
        return False
        
    def is_alive(self):
        return True

    def get_timestamps(self):
        """
        Returns a synthesized list of (index, timestamp) tuples.
        """
        if not self.start_time or not self.is_recording():
            return []

        now = time.time()
        duration_sec = now - self.start_time
        
        if duration_sec < 0: return []

        count = int(duration_sec * self.requested_fps)
        
        timestamps = []
        dt = 1.0 / self.requested_fps
        for i in range(count):
            t = self.start_time + i * dt
            timestamps.append((i, t))
            
        return timestamps
