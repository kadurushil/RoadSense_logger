import os
import subprocess
import sys
import glob

ADB_PATH = r"C:\Users\rakadu1.AHEAD\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def run_adb(cmd):
    full_cmd = f'"{ADB_PATH}" {cmd}'
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip(), res.stderr.strip(), res.returncode

def main():
    print("=" * 65)
    print(" RoadSense On-Device System Trace Extractor")
    print("=" * 65)

    # 1. Check devices
    out, _, _ = run_adb("devices")
    lines = [l for l in out.splitlines() if "\tdevice" in l]
    if not lines:
        print("[ERROR] No Android device connected via ADB. Please plug in your phone.")
        sys.exit(1)
    
    device_id = lines[0].split("\t")[0]
    print(f"[INFO] Connected device: {device_id}")

    # 2. Look for traces in /data/local/traces/
    print("[INFO] Checking for saved system traces in /data/local/traces/...")
    out, _, _ = run_adb("shell ls -lt /data/local/traces/")
    if not out or "No such file" in out:
        print("[WARN] No traces found in /data/local/traces/. Checking /sdcard/Recordings/traces/...")
        out, _, _ = run_adb("shell ls -lt /sdcard/Recordings/traces/")
    
    print(out)
    trace_files = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 8:
            fname = parts[-1]
            if fname.endswith(".perfetto-trace") or fname.endswith(".trace") or fname.endswith(".pftrace"):
                trace_files.append(fname)
    
    if not trace_files:
        print("[ERROR] No trace files found on device. Did you stop tracing via Quick Settings tile?")
        sys.exit(1)

    latest_trace = trace_files[0]
    print(f"\n[SUCCESS] Found latest trace: {latest_trace}")

    # 3. Pull latest trace to PC
    out_dir = os.path.abspath("logs")
    os.makedirs(out_dir, exist_ok=True)
    dest_path = os.path.join(out_dir, latest_trace)

    source_path = f"/data/local/traces/{latest_trace}"
    print(f"[INFO] Pulling {source_path} to {dest_path}...")
    run_adb(f'pull "{source_path}" "{dest_path}"')

    if not os.path.exists(dest_path) or os.path.getsize(dest_path) == 0:
        source_path = f"/sdcard/Recordings/traces/{latest_trace}"
        run_adb(f'pull "{source_path}" "{dest_path}"')

    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        size_mb = os.path.getsize(dest_path) / (1024 * 1024)
        print(f"[SUCCESS] Trace extracted successfully! Size: {size_mb:.2f} MB")
        print("\n" + "=" * 65)
        print(" HOW TO VIEW THIS TRACE:")
        print(f" 1. Open Google Chrome and go to: https://ui.perfetto.dev")
        print(f" 2. Click 'Open trace file' in the top-left sidebar.")
        print(f" 3. Select: {dest_path}")
        print(" 4. Use 'W' and 'S' to zoom in/out, 'A' and 'D' to pan left/right.")
        print(" 5. Look for:")
        print("    - RS_Serial_onNewData (green slice for every incoming UART packet)")
        print("    - RS_Worker_Assemble & RS_Worker_DecodeTLV (worker processing)")
        print("    - RS_UI_DrawLollipops (UI Canvas rendering time)")
        print("=" * 65)
    else:
        print("[ERROR] Failed to pull trace.")

if __name__ == "__main__":
    main()

