#!/usr/bin/env python3
"""
RoadSense Web Automation Server

A zero-dependency local HTTP and Server-Sent Events (SSE) server that provides
a visual browser-based dashboard to control and monitor all RoadSense multi-sensor
tools, diagnostic scripts, and MCAP conversion pipelines.

Usage:
    python tools/roadsense_web_server.py [--port 8088] [--no-browser] [--test-init]
"""

import os
import sys
import glob
import json
import time
import socket
import shutil
import signal
import queue
import argparse
import threading
import subprocess
import webbrowser
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# Base directory paths
REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGS_DIR = os.path.join(REPO_DIR, "logs")
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web_dashboard")
INDEX_HTML = os.path.join(WEB_DIR, "index.html")

# Global Process Supervisor State
class ProcessSupervisor:
    def __init__(self):
        self.lock = threading.Lock()
        self.active_proc = None
        self.active_script = None
        self.exit_code = None
        self.subscribers = []
        self.log_history = []
        self.history_max = 500

    def subscribe(self):
        q = queue.Queue()
        with self.lock:
            # Send history to new subscriber
            for line in self.log_history:
                q.put(("log", line))
            # Also notify of current status
            is_running = self.active_proc is not None and self.active_proc.poll() is None
            q.put(("status", {
                "running": is_running,
                "script": self.active_script,
                "exit_code": self.exit_code
            }))
            self.subscribers.append(q)
        return q

    def unsubscribe(self, q):
        with self.lock:
            if q in self.subscribers:
                self.subscribers.remove(q)

    def broadcast_log(self, line):
        with self.lock:
            self.log_history.append(line)
            if len(self.log_history) > self.history_max:
                self.log_history.pop(0)
            dead = []
            for q in self.subscribers:
                try:
                    q.put_nowait(("log", line))
                except queue.Full:
                    dead.append(q)
            for d in dead:
                self.subscribers.remove(d)

    def broadcast_status(self):
        with self.lock:
            is_running = self.active_proc is not None and self.active_proc.poll() is None
            payload = {
                "running": is_running,
                "script": self.active_script,
                "exit_code": self.exit_code
            }
            dead = []
            for q in self.subscribers:
                try:
                    q.put_nowait(("status", payload))
                except queue.Full:
                    dead.append(q)
            for d in dead:
                self.subscribers.remove(d)

    def is_running(self):
        with self.lock:
            return self.active_proc is not None and self.active_proc.poll() is None

    def start_process(self, script_path, args):
        with self.lock:
            if self.active_proc is not None and self.active_proc.poll() is None:
                return False, "Another script is already running."

            self.log_history.clear()
            self.exit_code = None
            self.active_script = os.path.basename(script_path)

            full_script = os.path.normpath(os.path.join(REPO_DIR, script_path))
            if not os.path.isfile(full_script):
                return False, f"Script not found: {full_script}"

            cmd = [sys.executable, "-u", full_script] + args
            print(f"[Supervisor] Launching: {' '.join(cmd)}")

            creation_flags = 0
            if sys.platform == "win32":
                creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP

            try:
                self.active_proc = subprocess.Popen(
                    cmd,
                    cwd=REPO_DIR,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1,
                    universal_newlines=True,
                    creationflags=creation_flags
                )
            except Exception as e:
                return False, f"Failed to spawn process: {e}"

        # Notify clients
        self.broadcast_status()

        # Start non-blocking reader thread
        threading.Thread(target=self._reader_thread, args=(self.active_proc,), daemon=True).start()
        return True, "Process started successfully."

    def _reader_thread(self, proc):
        for line in iter(proc.stdout.readline, ''):
            if not line:
                break
            clean_line = line.rstrip('\r\n')
            self.broadcast_log(clean_line)
        
        proc.stdout.close()
        code = proc.wait()
        with self.lock:
            self.exit_code = code
            self.active_proc = None
        print(f"[Supervisor] Process exited with code {code}")
        self.broadcast_status()

    def stop_process(self):
        with self.lock:
            if self.active_proc is None or self.active_proc.poll() is not None:
                return False, "No active process to stop."

            proc = self.active_proc
            print(f"[Supervisor] Terminating process PID {proc.pid}...")

        # Kill outside lock to prevent deadlock
        try:
            if sys.platform == "win32":
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
            else:
                proc.terminate()
        except Exception as e:
            print(f"[Supervisor] Error killing PID {proc.pid}: {e}")

        with self.lock:
            self.active_proc = None
            self.exit_code = -1
        self.broadcast_status()
        self.broadcast_log("[-] Process execution stopped by user.")
        return True, "Process stopped."

SUPERVISOR = ProcessSupervisor()


def find_adb():
    """Locates the adb executable on the system or Android SDK platform-tools."""
    adb = shutil.which("adb")
    if adb:
        return adb
    candidates = [
        os.path.expanduser(r"~\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
        r"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe"
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def get_adb_status():
    """Checks connected ADB devices."""
    adb = find_adb()
    if not adb:
        return {"available": False, "connected": False, "devices": []}
    try:
        res = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=3)
        devices = [line.split()[0] for line in res.stdout.strip().splitlines()[1:] if "\tdevice" in line]
        return {
            "available": True,
            "connected": len(devices) > 0,
            "devices": devices
        }
    except Exception:
        return {"available": True, "connected": False, "devices": []}


def get_local_sessions():
    """Returns sorted list of local session directories in logs/."""
    if not os.path.isdir(LOGS_DIR):
        return []
    sessions = []
    for item in os.listdir(LOGS_DIR):
        if item.startswith("session_") and os.path.isdir(os.path.join(LOGS_DIR, item)):
            sessions.append(item)
    sessions.sort(reverse=True)
    return sessions


def get_gpu_status():
    """Detects GPU acceleration status via convert_session_to_mcap."""
    try:
        tools_dir = os.path.dirname(os.path.abspath(__file__))
        if tools_dir not in sys.path:
            sys.path.insert(0, tools_dir)
        from convert_session_to_mcap import detect_hardware_video_acceleration
        return detect_hardware_video_acceleration()
    except Exception as e:
        return {"available": False, "gpu_name": "", "error": str(e)}


class DashboardRequestHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress noisy SSE poll logs
        if args and isinstance(args[0], str) and "GET /api/stream" in args[0]:
            return
        super().log_message(format, *args)

    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            pass

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ["/", "/index.html"]:
            self.serve_file(INDEX_HTML, "text/html; charset=utf-8")
        elif path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
        elif path == "/api/status":
            self.serve_json({
                "adb": get_adb_status(),
                "gpu": get_gpu_status(),
                "sessions": get_local_sessions(),
                "process": {
                    "running": SUPERVISOR.is_running(),
                    "script": SUPERVISOR.active_script,
                    "exit_code": SUPERVISOR.exit_code
                }
            })
        elif path == "/api/sessions":
            self.serve_json(get_local_sessions())
        elif path == "/api/stream":
            self.serve_sse_stream()
        elif path.startswith("/logs/"):
            rel = path[6:]  # strip /logs/
            target = os.path.normpath(os.path.join(LOGS_DIR, rel))
            if target.startswith(LOGS_DIR) and os.path.isfile(target):
                ext = os.path.splitext(target)[1].lower()
                mime = "image/png" if ext == ".png" else ("image/jpeg" if ext in [".jpg", ".jpeg"] else "application/octet-stream")
                self.serve_file(target, mime)
            else:
                self.send_error(404, "File not found in logs/")
        else:
            self.send_error(404, "File not found")

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/run":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            try:
                data = json.loads(body.decode("utf-8"))
            except Exception as e:
                self.serve_json({"success": False, "error": f"Invalid JSON payload: {e}"}, status=400)
                return

            script = data.get("script", "")
            args = data.get("args", [])
            success, msg = SUPERVISOR.start_process(script, args)
            self.serve_json({"success": success, "message" if success else "error": msg})

        elif path == "/api/stop":
            success, msg = SUPERVISOR.stop_process()
            self.serve_json({"success": success, "message" if success else "error": msg})

        elif path == "/api/shutdown":
            self.serve_json({"success": True, "message": "Server shutting down..."})
            def _delayed_shutdown():
                time.sleep(0.5)
                SUPERVISOR.stop_process()
                print("\n[+] RoadSense Web Server stopped. Bye!")
                os._exit(0)
            threading.Thread(target=_delayed_shutdown, daemon=True).start()

        else:
            self.send_error(404, "Endpoint not found")

    def serve_file(self, filepath, content_type):
        if not os.path.isfile(filepath):
            self.send_error(404, "File not found")
            return
        with open(filepath, "rb") as f:
            content = f.read()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def serve_json(self, data, status=200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def serve_sse_stream(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        q = SUPERVISOR.subscribe()
        try:
            while True:
                try:
                    event_type, item = q.get(timeout=20.0)
                    if event_type == "log":
                        msg = f"data: {item}\n\n".encode("utf-8")
                    elif event_type == "status":
                        msg = f"event: process_status\ndata: {json.dumps(item)}\n\n".encode("utf-8")
                    self.wfile.write(msg)
                    self.wfile.flush()
                except queue.Empty:
                    # Keepalive comment
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            SUPERVISOR.unsubscribe(q)


def find_free_port(start_port=8088, max_attempts=20):
    """Auto-hunt for an available port starting at start_port."""
    for p in range(start_port, start_port + max_attempts):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", p))
                return p
            except OSError:
                continue
    # Fallback to system-allocated ephemeral port
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description="RoadSense Web Dashboard & Script Automation Server")
    parser.add_argument("--port", type=int, default=8088, help="Preferred port (default: 8088)")
    parser.add_argument("--no-browser", action="store_true", help="Do not automatically open default browser")
    parser.add_argument("--test-init", action="store_true", help="Initialize and verify port binding, then exit")
    args = parser.parse_args()

    port = find_free_port(args.port)
    server_address = ("127.0.0.1", port)
    url = f"http://127.0.0.1:{port}"

    try:
        httpd = ThreadingHTTPServer(server_address, DashboardRequestHandler)
    except Exception as e:
        print(f"[-] Failed to bind HTTP server to port {port}: {e}")
        sys.exit(1)

    print("=" * 80)
    print(" RoadSense Telemetry & Script Automation Hub (Web Dashboard)")
    print("=" * 80)
    print(f"  * Status: Online")
    print(f"  * Local Dashboard URL: {url}")
    print(f"  * Python Interpreter:  {sys.executable}")
    print(f"  * Press Ctrl+C in this console to stop the server at any time.")
    print("=" * 80)

    if args.test_init:
        print("[+] Test initialization successful! Closing test server.")
        httpd.server_close()
        sys.exit(0)

    # Launch browser in separate thread
    if not args.no_browser:
        def _open():
            time.sleep(0.6)
            webbrowser.open(url)
        threading.Thread(target=_open, daemon=True).start()

    # Graceful exit handling
    def signal_handler(sig, frame):
        print("\n[!] Shutdown signal received. Stopping active tasks...")
        SUPERVISOR.stop_process()
        httpd.server_close()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        SUPERVISOR.stop_process()
        httpd.server_close()
        print("[+] Server stopped.")


if __name__ == "__main__":
    main()
