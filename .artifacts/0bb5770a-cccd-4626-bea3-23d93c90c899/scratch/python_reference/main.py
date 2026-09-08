import sys
import os
import platform
import subprocess

# Add the 'src' directory to the Python path
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), 'src')))


from radar_tracker.console_logger import logger
from datetime import datetime
if platform.system() == "Linux":
        from can_logger_app.gpio_handler import init_gpio, wait_for_switch_on, check_for_switch_off, cleanup_gpio, turn_on_led, turn_off_led
import threading
import multiprocessing
from pathlib import Path


import logging
from logging.handlers import QueueHandler, QueueListener
from src.radar_tracker.json_log_handler import JSONLogHandler

if __name__ == '__main__':
    # --- Imports are deferred to here to prevent issues with multiprocessing on Windows ---
    import config
    from can_logger_app.main import main as can_logger_main
    from radar_tracker.main_live import main as main_live
    from radar_tracker.main_playback import run_playback
    multiprocessing.freeze_support()
    
    # --- Create the Manager and shared data structures FIRST ---
    manager = multiprocessing.Manager()
    shutdown_flag = multiprocessing.Event()
    can_logger_ready = multiprocessing.Event() # Event to signal CAN logger readiness
    can_data_queue = multiprocessing.Queue() # This queue will be shared
    can_summary_data = manager.dict() # For sharing the final CAN summary

    # --- 1. Mode Selection and Path Setup ---
    print("--- Welcome to the Unified Radar Tracker ---")
    while True:
        mode = input("Select mode: (1) Live Tracking or (2) Playback from File\nEnter choice (1 or 2): ")
        if mode in ['1', '2']:
            break
        else:
            print("Invalid choice. Please enter 1 or 2.")

    source_dir = None
    output_dir = None
    if mode == '1':
        # Live Mode: Board selection is now handled here to get the project name for the folder
        print("\nStep 1: Select Radar Board Type / Demo")
        print("---------------------------------------")
        print("1: Single-Port (e.g., AWRL1432BOOST) - BSD Demo")
        print("2: Dual-Port   (e.g., AWR1843BOOST) - Standard Demo")
        print("3: Dual-Port   (e.g., AWR1843BOOST) - MRR Demo")
        print("4: Dual-Port   (e.g., AWR1843BOOST) - Custom MRR Demo")
        
        project_prefix = "BSD"
        board_mode = "1"
        is_mrr = False
        is_bsd = True 
        is_custom_mrr = False
        while True:
            selection = input("Select type (1-4, default: 1): ").strip()
            if selection == "" or selection == "1":
                project_prefix = "BSD"
                board_mode = "1"
                is_bsd = True
                break
            elif selection == "2":
                project_prefix = "Standard"
                board_mode = "2"
                is_mrr = False
                is_bsd = False
                break
            elif selection == "3":
                project_prefix = "MRR"
                board_mode = "2"
                is_mrr = True
                is_bsd = False
                break
            elif selection == "4":
                project_prefix = "Custom_MRR"
                board_mode = "2"
                is_mrr = True
                is_bsd = False
                is_custom_mrr = True
                break
            else:
                print("Invalid selection. Please enter 1, 2, 3 or 4.")

        # Create new timestamped output with project prefix
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        output_dir = os.path.join("output", f"{project_prefix}_{timestamp}")
    else:
        # Playback Mode: Interactive session selection
        print("\n--- Playback Session Selection ---")
        output_base = Path("output")
        valid_sessions = []
        
        if output_base.exists():
            # Scan for directories containing radar_log.json
            for d in output_base.iterdir():
                if d.is_dir() and (d / "radar_log.json").exists():
                    # Get modification time for sorting
                    mtime = (d / "radar_log.json").stat().st_mtime
                    valid_sessions.append((d, mtime))
        
        # Sort by mtime (newest first)
        valid_sessions.sort(key=lambda x: x[1], reverse=True)
        
        # Display the 10 most recent sessions
        display_count = min(len(valid_sessions), 10)
        if display_count > 0:
            print(f"Recent sessions in '{output_base}/':")
            for i in range(display_count):
                session_path = valid_sessions[i][0]
                # Format time for display
                dt_str = datetime.fromtimestamp(valid_sessions[i][1]).strftime('%Y-%m-%d %H:%M:%S')
                print(f"{i+1}: {session_path.name} ({dt_str})")
            
            print(f"{display_count + 1}: Enter manual path...")
            
            while True:
                choice = input(f"Select session (1-{display_count + 1}, default: 1): ").strip()
                if choice == "" or choice == "1":
                    source_dir = valid_sessions[0][0]
                    break
                try:
                    idx = int(choice) - 1
                    if 0 <= idx < display_count:
                        source_dir = valid_sessions[idx][0]
                        break
                    elif idx == display_count:
                        # Manual path entry
                        while True:
                            manual_path = input("Enter path to directory: ").strip()
                            source_dir = Path(manual_path)
                            if source_dir.exists() and source_dir.is_dir():
                                break
                            print("Invalid directory. Please try again.")
                        break
                except ValueError:
                    print("Invalid input.")
        else:
            # No sessions found, fall back to manual entry
            while True:
                source_dir_input = input("No sessions found in 'output/'. Enter path manually: ").strip()
                source_dir = Path(source_dir_input)
                if source_dir.exists() and source_dir.is_dir():
                    break
                print("Invalid directory. Please try again.")
        
        print(f"Selected session: {source_dir}")
        
        # For Playback, we use the simulations folder directly
        output_dir = source_dir / "simulations"
        
        board_mode = None
        is_mrr = None
        is_bsd = None

    os.makedirs(output_dir, exist_ok=True)


    # --- 2. Setup Logging (Now that we have the correct output_dir) ---
    log_queue = multiprocessing.Queue()

    # Create a directory for console logs
    console_out_dir = os.path.join(output_dir, "console_out")
    os.makedirs(console_out_dir, exist_ok=True)

    # Define filters for different log categories
    class CANFilter(logging.Filter):
        def filter(self, record):
            normalized_path = record.pathname.replace(os.sep, '/')
            return 'can_logger_app' in normalized_path

    class RadarProcessingFilter(logging.Filter):
        def filter(self, record):
            # Captures logs from radar_tracker's main_live.py and main_playback.py
            normalized_path = record.pathname.replace(os.sep, '/')
            return (
                'radar_tracker/' in normalized_path and
                'radar_tracker/tracking' not in normalized_path
            )

    class TrackingFilter(logging.Filter):
        def filter(self, record):
            # Captures logs from the tracking subdirectory
            normalized_path = record.pathname.replace(os.sep, '/')
            return 'radar_tracker/tracking' in normalized_path

    # Create handlers for each category
    can_handler = logging.FileHandler(os.path.join(console_out_dir, 'can_processing.log'), mode='w')
    can_handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(name)s - %(message)s'))
    can_handler.addFilter(CANFilter())

    radar_handler = logging.FileHandler(os.path.join(console_out_dir, 'radar_processing.log'), mode='w')
    radar_handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(name)s - %(message)s'))
    radar_handler.addFilter(RadarProcessingFilter())

    # Configure the listener to handle logs from the queue
    from src.radar_tracker.console_logger import json_log_handler
    
    # Conditionally include tracking_handler based on ENABLE_TRACKING_LOG
    handlers = [can_handler, radar_handler]
    if config.ENABLE_TRACKING_LOG:
        tracking_handler = logging.FileHandler(os.path.join(console_out_dir, 'tracking.log'), mode='w')
        tracking_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))
        tracking_handler.addFilter(TrackingFilter())
        handlers.append(tracking_handler)

    listener = QueueListener(log_queue, *handlers)
    listener.start()

    # The main process's root logger sends to the queue
    root_logger = logging.getLogger()
    root_logger.addHandler(QueueHandler(log_queue))
    
    # Truly suppress log generation overhead if tracking logs are disabled
    if getattr(config, 'ENABLE_TRACKING_LOG', False):
        # Set to INFO to match MATLAB simLog granularity. 
        # Set to DEBUG only if deep component tracing is required.
        root_logger.setLevel(logging.INFO)
    else:
        # Keep at WARNING to avoid info/debug overhead but still see errors
        root_logger.setLevel(logging.WARNING)


    # --- 3. Execution ---
    can_interface = None # Initialize can_interface
    effective_can_logger_ready = can_logger_ready # Assume CAN is used by default

    if mode == '1':
        # --- Ask for CAN interface ---
        logger.info("\n--- CAN Interface Selection ---")
        while True:
            can_interface_choice = input("Select CAN interface: (1) PEAK (pcan), (2) Kvaser, or (3) No CAN\nEnter choice (1, 2, or 3): ").lower().strip()
            if can_interface_choice in ['1', 'peak', 'pcan']:
                can_interface = 'peak'
                break
            elif can_interface_choice in ['2', 'kvaser']:
                can_interface = 'kvaser'
                break
            elif can_interface_choice in ['3', 'no can', 'none']:
                can_interface = None
                effective_can_logger_ready = None # Set to None for "No CAN" mode
                break
            else:
                logger.info("Invalid choice. Please enter 1, 2, or 3.")

    # If on Raspberry Pi, wait for switch to be turned on
    if platform.system() == "Linux":
        
        can_logger_process = None
        try:
            # init_gpio()
            # logger.info("Waiting for switch ON...")
            # wait_for_switch_on()
            # logger.info("Switch is ON!")
            # turn_on_led()

            # Start the CAN logger in a separate process for live mode
            if mode == '1' and can_interface is not None:
                # MODIFIED: Pass the shared dict and can_interface to the logger
                can_logger_process = multiprocessing.Process(
                    target=can_logger_main, 
                    args=(shutdown_flag, output_dir, can_data_queue, can_interface, can_logger_ready, log_queue, can_summary_data)
                )
                can_logger_process.start()

                # Start the stop signal checker in a separate thread
                # stop_thread = threading.Thread(target=check_for_switch_off, args=(shutdown_flag,))
                # stop_thread.start()

            # Launch the appropriate mode
            if mode == '1':
                logger.info("\nStarting in LIVE mode...")
                # Pass the multiprocessing.Queue if CAN is enabled, otherwise None
                can_queue_for_main_live = can_data_queue if can_interface is not None else None
                main_live(output_dir, shutdown_flag, can_queue_for_main_live, effective_can_logger_ready, log_queue,
                          board_mode=board_mode, is_mrr=is_mrr, is_bsd=is_bsd, is_custom_mrr=is_custom_mrr)
            elif mode == '2':
                logger.info("\nStarting in PLAYBACK mode...")
                # Pass both source and output directories
                run_playback(source_dir, output_dir)

        except KeyboardInterrupt:
            logger.info("\nCtrl+C detected. Shutting down...")
        finally:
            shutdown_flag.set() # Signal all processes to shutdown

            # if 'stop_thread' in locals() and stop_thread.is_alive():
            #     stop_thread.join(timeout=2)

            if can_logger_process and can_logger_process.is_alive():
                logger.info("Signaling CAN logger to shut down...")
                can_logger_process.join(timeout=5)
                if can_logger_process.is_alive():
                    logger.info("CAN logger did not shut down, terminating...")
                    can_logger_process.terminate()
                    can_logger_process.join()

            # if platform.system() == "Linux":
            #     turn_off_led()
            
            # cleanup_gpio()
            logger.info("Application shut down.")

    else: # Not on Linux
        
        can_logger_process = None
        live_thread = None
        try:
            # Start the CAN logger in a separate process for live mode (Windows/Other)
            if mode == '1' and can_interface is not None:
                can_logger_process = multiprocessing.Process(
                    target=can_logger_main, 
                    args=(shutdown_flag, output_dir, can_data_queue, can_interface, can_logger_ready, log_queue, can_summary_data)
                )
                can_logger_process.start()
            
            if mode == '1':
                logger.info("\nStarting in LIVE mode...")
                # Pass the multiprocessing.Queue if CAN is enabled, otherwise None
                can_queue_for_main_live = can_data_queue if can_interface is not None else None
                main_live(output_dir, shutdown_flag, can_queue_for_main_live, effective_can_logger_ready, log_queue,
                          board_mode=board_mode, is_mrr=is_mrr, is_bsd=is_bsd, is_custom_mrr=is_custom_mrr)

            elif mode == '2':
                logger.info("\nStarting in PLAYBACK mode...")
                run_playback(source_dir, output_dir)

        except KeyboardInterrupt:
            logger.info("\nCtrl+C detected. Shutting down...")
        finally:
            shutdown_flag.set() # Signal all processes to shutdown


            if can_logger_process and can_logger_process.is_alive():
                logger.info("Signaling CAN logger to shut down gracefully...")
                can_logger_process.join(timeout=5) 

                if can_logger_process.is_alive():
                    logger.info("CAN logger did not shut down, terminating...")
                    can_logger_process.terminate()
                    can_logger_process.join()
            logger.info("Application shut down.")

    # --- Print Final CAN Summary Report ---
    if can_summary_data:
        # ... (same as before)
        pass
        
    # --- Robust Listener Shutdown (Phase 2: Infrastructure Hardening) ---
    if listener:
        try:
            # 1. Enqueue the sentinel to tell the listener loop to exit.
            sentinel = getattr(listener, '_sentinel', None)
            try:
                # Use a blocking put with timeout to ensure the sentinel gets in 
                # even if the queue was temporarily full.
                listener.queue.put(sentinel, timeout=1.0)
            except Exception:
                # Fallback if queue is broken
                pass 

            # 2. Wait for the thread to finish with an adaptive timeout
            # Long simulations (2800 frames) generate >100k lines. We need to wait.
            if listener._thread and listener._thread.is_alive():
                # Increased timeout to 10s to allow flushing to slow SD cards
                max_shutdown_wait = 10.0 
                listener._thread.join(timeout=max_shutdown_wait)
                
                if listener._thread.is_alive():
                    # If still alive, we have to force it, but print a more informative warning
                    print(f"[WARNING] Logging listener thread timed out after {max_shutdown_wait}s. Some logs may be missing.")
                    
                    # Last resort: cancel join thread to prevent hanging on exit
                    if hasattr(log_queue, 'cancel_join_thread'):
                        log_queue.cancel_join_thread()
                else:
                    listener._thread = None

        except Exception as e:
            print(f"[WARNING] Error during log listener shutdown: {e}")