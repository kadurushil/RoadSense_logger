import serial
import serial.tools.list_ports
import time
from ..console_logger import logger

# Define the 8-byte sync pattern for frame synchronization
SYNC_PATTERN = b'\x02\x01\x04\x03\x06\x05\x08\x07'

def configure_control_port(com_port_num, baud_rate):
    """
    Configures and opens the control serial port with a standard terminator.
    This version is compatible with both Windows (numbers) and Linux (strings).

    Args:
        com_port_num (int or str): The COM port number (e.g., 11) or device path (e.g., '/dev/ttyACM1').
        baud_rate (int): The initial baud rate for communication.

    Returns:
        serial.Serial or None: A pySerial object if successful, otherwise None.
    """
    # --- BUG FIX: Check the type of the port identifier ---
    # If it's an integer, assume Windows and prepend "COM".
    # If it's a string, assume Linux/macOS and use it directly.
    if isinstance(com_port_num, int):
        com_port_string = f'COM{com_port_num}'
    else:
        com_port_string = com_port_num

    try:
        # List available ports and check if the desired port exists
        available_ports = [p.device for p in serial.tools.list_ports.comports()]
        if com_port_string not in available_ports:
            logger.error(f'\nERROR: CONTROL port {com_port_string} is NOT in the list of available ports.')
            logger.info(f'Available ports are: {available_ports}')
            return None

        # Create and open the serial port object
        sphandle = serial.Serial(
            com_port_string,
            baud_rate,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=1.0, # Set a timeout for read operations
            dsrdtr=False,
            rtscts=False
        )
        
        # Explicitly control DTR/RTS to prevent board reset or flow control hangs
        sphandle.dtr = False
        sphandle.rts = False
        
        # --- NEW: Add a small delay for port stability on Linux ---
        time.sleep(0.1)
        sphandle.reset_input_buffer()
        sphandle.reset_output_buffer()
        sphandle.flush()

        logger.info(f'--- Opened serial port {com_port_string} at {baud_rate} baud. ---')
        return sphandle
    except serial.SerialException as e:
        logger.error(f'ERROR: Failed to open serial port {com_port_string}: {e}')
        return None

def reconfigure_port_for_data(sphandle):
    """
    Reconfigures an open serial port for continuous data streaming.
    """
    if sphandle and sphandle.is_open:
        sphandle.reset_input_buffer()
        sphandle.reset_output_buffer()
        sphandle.flush()
        logger.info('--- Port configured for data mode (binary streaming). ---')
    return sphandle

def open_data_port(port_name, baud_rate):
    """
    Opens a serial port specifically for high-speed data reading.
    
    Args:
        port_name (str): The COM port device name.
        baud_rate (int): The baud rate.
        
    Returns:
        serial.Serial: The opened port object or None.
    """
    print(f"[DEBUG] Attempting to open DATA port: {port_name} at {baud_rate} baud...")
    
    # Introduce a delay to allow the OS to release the port or stabilize USB
    time.sleep(1.0)
    
    try:
        data_port = serial.Serial(
            port_name,
            baud_rate,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=1.0, # Blocking read with timeout
            xonxoff=False,
            rtscts=False,
            dsrdtr=False
        )
        
        if not data_port.is_open:
            print(f"[ERROR] Port {port_name} created but reported as closed.")
            return None

        data_port.reset_input_buffer()
        data_port.reset_output_buffer()
        
        logger.info(f"--- Opened DATA port {port_name} at {baud_rate} baud ---")
        print(f"[SUCCESS] DATA port {port_name} opened successfully.")
        return data_port
    except Exception as e:
        logger.error(f"Failed to open DATA port {port_name}: {e}")
        print(f"[ERROR] Exception opening DATA port: {e}")
        return None

def read_frame_header(h_data_serial_port, frame_header_length_bytes, raw_bin_queue=None):
    """
    Reads from the serial port until a complete frame header is found.
    Uses a sliding window search for the sync pattern.
    """
    if h_data_serial_port is None:
        logger.error("read_frame_header called with None port.")
        return None, 0, 0
    if not h_data_serial_port.is_open:
        logger.error("read_frame_header called with closed port.")
        return None, 0, 0

    out_of_sync_bytes = 0
    sync_buffer = b""
    
    while True:
        try:
            byte = h_data_serial_port.read(1)
            if not byte:
                # logger.warning("Timeout occurred while reading from serial port.")
                return None, 0, out_of_sync_bytes
            
            # --- NEW: Raw binary dump interception ---
            if raw_bin_queue:
                try:
                    raw_bin_queue.put_nowait(byte)
                except Exception:
                    pass # Buffer full or logger stopped

            sync_buffer += byte
            
            if len(sync_buffer) > 8:
                sync_buffer = sync_buffer[1:]
                out_of_sync_bytes += 1
            
            if sync_buffer == SYNC_PATTERN:
                # Found the sync pattern!
                header_rest_len = frame_header_length_bytes - 8
                header_rest = h_data_serial_port.read(header_rest_len)

                # --- NEW: Raw binary dump interception ---
                if raw_bin_queue and header_rest:
                    try:
                        raw_bin_queue.put_nowait(header_rest)
                    except Exception:
                        pass

                if len(header_rest) == header_rest_len:
                    rx_header = sync_buffer + header_rest
                    return rx_header, frame_header_length_bytes, out_of_sync_bytes
                else:
                    # Incomplete header rest, synchronization lost again
                    out_of_sync_bytes += 8 + len(header_rest)
                    sync_buffer = b""
                    continue

        except serial.SerialException as e:
            logger.error(f"Serial port read failed: {e}")
            return None, 0, out_of_sync_bytes
        except TypeError as e:
             logger.error(f"Serial port low-level error (fd might be None/closed): {e}")
             return None, 0, out_of_sync_bytes
        except Exception as e:
             logger.error(f"Unexpected error in read_frame_header: {e}")
             return None, 0, out_of_sync_bytes