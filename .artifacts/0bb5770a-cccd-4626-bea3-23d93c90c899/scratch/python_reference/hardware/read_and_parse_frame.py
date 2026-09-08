import struct
import numpy as np

# MODIFICATION: Changed local imports to be relative
from . import hw_comms_utils
from . import parsing_utils
from ..console_logger import logger, log_debug

# --- TLV Type Constants ---
# As defined in read_and_parse_frame.m
MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS = 301
MMWDEMO_OUTPUT_EXT_MSG_STATS = 306
MMWDEMO_OUTPUT_EXT_MSG_TARGET_LIST_2D_BSD = 1035
# Added for AWR1843BOOST Support
MMWDEMO_OUTPUT_MSG_DETECTED_POINTS = 1
MMWDEMO_OUTPUT_MSG_CLUSTERS = 2
MMWDEMO_OUTPUT_MSG_TRACKS = 3
MMWDEMO_OUTPUT_MSG_PARKING_ASSIST = 4
MMWDEMO_OUTPUT_MSG_STATS = 6
MMWDEMO_OUTPUT_MSG_DETECTED_POINTS_SIDE_INFO = 7
MMWDEMO_OUTPUT_MSG_AZIMUT_ELEVATION_STATIC_HEAT_MAP = 8
MMWDEMO_OUTPUT_MSG_TEMPERATURE_STATS = 9

# WRL14xx/64xx (Low Power SDK) Specific TLV IDs
MMWDEMO_OUTPUT_MSG_WRL_STATS = 4
MMWDEMO_OUTPUT_MSG_WRL_DETECTED_POINTS_SIDE_INFO = 5
MMWDEMO_OUTPUT_MSG_WRL_EXT_DETECTED_POINTS = 6

# AWRL1432 ADAS BSD Specific TLV IDs
MMWDEMO_OUTPUT_EXT_STATS_MSG_BSD = 1034
MMWDEMO_OUTPUT_EXT_MSG_TARGET_INDEX = 309

# --- Platform Constants ---
PLATFORM_AWR1432 = 0xA1432 # Generic/Old
PLATFORM_WRL1432 = 0xA6432 # xWRL1432 / xWRL6432
PLATFORM_WRL1432_ALT = 0xA6431 # Alternative ID for some variants
PLATFORM_AWR1642 = 0xA1642
PLATFORM_AWR1843 = 0xA1843
PLATFORM_IWR6843 = 0xA6843

# --- Structure Definitions ---
# These dictionaries define the binary format of the headers and data payloads.
# Format: { 'field_name': ('struct_format_char', num_bytes) }

FRAME_HEADER_STRUCT = {
    'magicWord': ('Q', 8),      # uint64 (0x0102030405060708)
    'version': ('I', 4),        # uint32
    'totalPacketLen': ('I', 4), # uint32 (total length including header)
    'platform': ('I', 4),       # uint32 (xWR1843 = 0xA1843)
    'frameNumber': ('I', 4),    # uint32
    'timeCpuCycles': ('I', 4),  # uint32
    'numDetectedObj': ('I', 4), # uint32
    'numTLVs': ('I', 4),        # uint32
    'subFrameNumber': ('I', 4), # uint32
}

# Added for AWRL1432 BSD Support
FRAME_HEADER_STRUCT_BSD = {
    'magicWord': ('Q', 8),      # uint64 (0x0102030405060708)
    'version': ('I', 4),        # uint32
    'totalPacketLen': ('I', 4), # uint32
    'platform': ('I', 4),       # uint32
    'frameNumber': ('I', 4),    # uint32
    'uartOverflowFrmCntr': ('I', 4), # uint32 (Cumulative UART overflow)
    'numDetectedObj': ('I', 4), # uint32
    'numTLVs': ('I', 4),        # uint32
    'procOverflowFrmCntr': ('I', 4), # uint32 (Cumulative CPU overflow)
}

# Added for AWR1843 MRR Demo Support (Structure is identical, but kept for clarity)
FRAME_HEADER_STRUCT_MRR = {
    'magicWord': ('Q', 8),      # uint64
    'version': ('I', 4),        # uint32
    'totalPacketLen': ('I', 4), # uint32
    'platform': ('I', 4),       # uint32
    'frameNumber': ('I', 4),    # uint32
    'timeCpuCycles': ('I', 4),  # uint32
    'numDetectedObj': ('I', 4), # uint32
    'numTLVs': ('I', 4),        # uint32
    'subFrameNumber': ('I', 4), # uint32
}

TLV_HEADER_STRUCT = {
    'type': ('I', 4),           # uint32
    'length': ('I', 4)          # uint32
}

# MRR Point Cloud Descriptor (4 bytes)
MRR_POINT_CLOUD_DESCRIPTOR_STRUCT = {
    'numDetectedObj': ('H', 2), # uint16
    'xyzQFormat': ('H', 2)      # uint16
}

# MRR Point structure (10 bytes)
MRR_POINT_STRUCT = {
    'doppler': ('h', 2),        # int16
    'peakVal': ('H', 2),        # uint16
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'z': ('h', 2)               # int16
}

# MRR Cluster Descriptor (4 bytes)
MRR_CLUSTER_DESCRIPTOR_STRUCT = {
    'numClusters': ('H', 2),    # uint16
    'xyzQFormat': ('H', 2)      # uint16
}

# MRR Cluster Structure (8 bytes)
MRR_CLUSTER_STRUCT = {
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'xSize': ('h', 2),          # int16
    'ySize': ('h', 2)           # int16
}

# MRR Track Descriptor (4 bytes)
MRR_TRACK_DESCRIPTOR_STRUCT = {
    'numTracks': ('H', 2),      # uint16
    'xyzQFormat': ('H', 2)      # uint16
}

# MRR Track Structure (12 bytes)
MRR_TRACK_STRUCT = {
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'vx': ('h', 2),             # int16
    'vy': ('h', 2),             # int16
    'xSize': ('h', 2),          # int16
    'ySize': ('h', 2)           # int16
}

# --- CUSTOM MRR STRUCTS (Aligned with Standard MRR based on binary analysis) ---
# Custom MRR Point structure (10 bytes)
CUSTOM_MRR_POINT_STRUCT = {
    'doppler': ('h', 2),        # int16
    'peakVal': ('H', 2),        # uint16
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'z': ('h', 2)               # int16
}

# Custom MRR Track Structure (14 bytes)
CUSTOM_MRR_TRACK_STRUCT = {
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'vx': ('h', 2),             # int16
    'vy': ('h', 2),             # int16
    'xSize': ('h', 2),          # int16
    'ySize': ('h', 2),          # int16
    'tid': ('H', 2)             # uint16
}

# Custom MRR Cluster Structure (8 bytes)
CUSTOM_MRR_CLUSTER_STRUCT = {
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'xSize': ('h', 2),          # int16
    'ySize': ('h', 2)           # int16
}

# Point cloud TLV structures
POINT_UNIT_STRUCT = {
    'xyzUnit': ('f', 4),        # single
    'dopplerUnit': ('f', 4),    # single
    'snrUnit': ('f', 4),        # single
    'noiseUnit': ('f', 4),      # single
    'numDetPointsMajor': ('H', 2), # uint16
    'numDetPointsMinor': ('H', 2)  # uint16
}

# WRL14xx specific Point Unit (16 bytes)
POINT_UNIT_STRUCT_WRL = {
    'xyzUnit': ('f', 4),        # single
    'dopplerUnit': ('f', 4),    # single
    'snrUnit': ('f', 4),        # single
    'noiseUnit': ('f', 4)       # single
}

POINT_STRUCT_CARTESIAN = {
    'x': ('h', 2),              # int16
    'y': ('h', 2),              # int16
    'z': ('h', 2),              # int16
    'doppler': ('h', 2),        # int16
    'snr': ('B', 1),            # uint8
    'noise': ('B', 1)           # uint8
}

# Statistics TLV structures (Type 306)
STATS_TIMING_STRUCT = {
    'interFrameProcessingTime': ('I', 4), # uint32
    'transmitOutputTime': ('I', 4)        # uint32
}
STATS_POWER_STRUCT = {
    'p1v8': ('H', 2), 'p3v3': ('H', 2),
    'p1v2': ('H', 2), 'p1v2rf': ('H', 2)
}
STATS_TEMP_STRUCT = {
    'rx': ('h', 2), 'tx': ('h', 2),
    'pm': ('h', 2), 'dig': ('h', 2)
}

# Statistics TLV structures (Type 6)
STATS_TYPE6_STRUCT_STANDARD = {
    'interFrameProcessingTime': ('I', 4), # uint32
    'transmitOutputTime': ('I', 4),       # uint32
    'interFrameProcessingMargin': ('I', 4),# uint32
    'interChirpProcessingMargin': ('I', 4),# uint32
    'activeFrameCPULoad': ('I', 4),       # uint32
    'interFrameCPULoad': ('I', 4)         # uint32
}

STATS_TYPE6_STRUCT_LEGACY = {
    'interFrameProcessingTime': ('I', 4), # uint32
    'transmitOutputTime': ('I', 4),       # uint32
    'interChirpProcessingMargin': ('I', 4),# uint32
    'interFrameCPULoad': ('I', 4),        # uint32
    'activeFrameCPULoad': ('I', 4)        # uint32
}

# WRL14xx Specific Stats (40 bytes)
STATS_WRL_STRUCT = {
    'interFrameProcessingTime': ('I', 4), # uint32
    'transmitOutputTime': ('I', 4),       # uint32
    'interFrameProcessingMargin': ('I', 4),# uint32
    'chirpingTime': ('I', 4),             # uint32
    'activeFrameCPULoad': ('I', 4),       # uint32
    'interFrameCPULoad': ('I', 4),        # uint32
    'tempReading': ('h', 8),              # int16[4]
    'powerMeasured': ('H', 8)             # uint16[4]
}

# BSD Specific Stats (40 bytes payload)
STATS_BSD_STRUCT = {
    'interFrameProcTime': ('I', 4),   # uint32
    'transmitOutputTime': ('I', 4),   # uint32
    'p1': ('H', 2), 'p2': ('H', 2),   # uint16[4] (powerMeasured)
    'p3': ('H', 2), 'p4': ('H', 2),
    't1': ('h', 2), 't2': ('h', 2),   # int16[4] (tempReading)
    't3': ('h', 2), 't4': ('h', 2),
    'egoVs': ('f', 4),                # float (mph)
    'egoAlpha': ('f', 4),             # float (deg)
    'reserved': ('Q', 8)              # 8 bytes padding to 40B
}


class FrameData:
    """A class to hold the parsed data for a single frame."""
    def __init__(self):
        self.header = {}
        self.point_cloud = np.array([])
        self.num_points = 0
        self.target_list = {}
        self.num_targets = 0
        self.target_index = np.array([], dtype=np.uint8) # New: Target association
        self.stats_info = {}
        self.tlvs = [] # List to store TLV metadata
        self.sensor_stats = {} # For Type 6 stats
        self.heatmap = None # For Type 8 heatmap
        self.mrr_clusters = None # For Type 2 clusters
        self.mrr_tracks = None # For Type 3 tracks
        self.parking_assist = None # For Type 4 parking assist

def read_and_parse_frame(h_data_port, params, raw_bin_queue=None):
    """
    Reads and parses one complete data frame from the UART stream.

    Args:
        h_data_port (serial.Serial): The open serial port for data.
        params (RadarParams): The parsed radar configuration parameters.
        raw_bin_queue (queue.Queue, optional): Queue for raw binary dump.

    Returns:
        FrameData or None: A FrameData object with parsed info, or None on failure.
    """
    if params and params.is_mrr:
        header_struct = FRAME_HEADER_STRUCT_MRR
    elif params and params.is_bsd:
        header_struct = FRAME_HEADER_STRUCT_BSD
    else:
        header_struct = FRAME_HEADER_STRUCT

    frame_header_length = parsing_utils.get_byte_length_from_struct(header_struct)
    tlv_header_length = parsing_utils.get_byte_length_from_struct(TLV_HEADER_STRUCT)

    # --- Read Frame Header and Payload ---
    try:
        rx_header_bytes, byte_count, _ = hw_comms_utils.read_frame_header(h_data_port, frame_header_length, raw_bin_queue=raw_bin_queue)
        if not rx_header_bytes or byte_count != frame_header_length:
            return None

        frame_header = parsing_utils.read_to_struct(rx_header_bytes, header_struct)
        if not frame_header:
            logger.warning("Could not parse frame header.")
            return None
            
        # Total length as specified by the header (including the header itself)
        total_packet_len = frame_header['totalPacketLen']
        
        # TI MRR demo packets are always multiples of 32 bytes.
        # totalPacketLen in the header ALREADY includes this padding.
        data_length = total_packet_len - frame_header_length
        
        # Read the rest of the frame (the payload + padding) in one go
        if data_length > 0:
            payload_bytes = h_data_port.read(data_length)
            
            # --- NEW: Raw binary dump interception ---
            if raw_bin_queue and payload_bytes:
                try:
                    raw_bin_queue.put_nowait(payload_bytes)
                except Exception:
                    pass

            if len(payload_bytes) != data_length:
                logger.warning(f"Incomplete payload received. Expected {data_length}, got {len(payload_bytes)}")
                return None
        else:
            payload_bytes = b''
    except (TypeError, OSError, hw_comms_utils.serial.SerialException) as e:
        logger.debug(f"Serial port error during frame acquisition: {e}")
        return None

    frame_data = FrameData()
    frame_data.header = frame_header
    platform = frame_header.get('platform', 0)
    is_mrr_platform = (params and params.is_mrr)
    is_custom_mrr_platform = (params and params.is_custom_mrr)
    is_wrl_platform = (platform == PLATFORM_WRL1432 or platform == PLATFORM_WRL1432_ALT or platform == PLATFORM_AWR1432)
    
    # --- Parse TLV Data from Payload ---
    offset = 0
    num_tlvs = frame_header['numTLVs']
    
    # MAGIC WORD for early sync detection (Sync pattern is 8 bytes)
    SYNC_PATTERN = hw_comms_utils.SYNC_PATTERN

    for i in range(num_tlvs):
        # RESILIENCE: Check if we've accidentally hit the next frame's magic word
        # (Handles "lying headers" where numTLVs or totalPacketLen is too large)
        if offset + 8 <= data_length:
            if payload_bytes[offset : offset + 8] == SYNC_PATTERN:
                logger.warning(f"Early Magic Word detected at offset {offset}. Truncating frame.")
                break

        if offset + tlv_header_length > data_length:
            break
        
        # Read TLV header
        tlv_header = parsing_utils.read_to_struct(
            payload_bytes[offset : offset + tlv_header_length], TLV_HEADER_STRUCT
        )
        if not tlv_header:
            break
            
        value_length = tlv_header['length']
        tlv_type = tlv_header['type']

        log_debug(lambda: f"[DEBUG] Found TLV #{i+1} of {num_tlvs}: Type={tlv_type}, Length={value_length} bytes, at offset={offset}", 'log_tlv_parsing')

        # Store TLV metadata for logging
        frame_data.tlvs.append({
            'type': tlv_type,
            'length': value_length,
            'offset': offset
        })

        total_tlv_length = value_length + tlv_header_length
        if offset + total_tlv_length > data_length:
            logger.warning(f"TLV (type {tlv_type}) length error. {offset + total_tlv_length} > {data_length}")
            break

        value_offset = offset + tlv_header_length
        value_bytes = payload_bytes[value_offset : value_offset + value_length]

        # --- Handle TLVs based on type and platform ---
        if tlv_type == MMWDEMO_OUTPUT_EXT_MSG_DETECTED_POINTS:
            parse_point_cloud_tlv(frame_data, value_bytes, params, is_wrl=False)
            
        elif tlv_type == MMWDEMO_OUTPUT_MSG_WRL_EXT_DETECTED_POINTS and is_wrl_platform:
            parse_point_cloud_tlv(frame_data, value_bytes, params, is_wrl=True)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_DETECTED_POINTS:
            if is_custom_mrr_platform:
                parse_custom_mrr_point_cloud_tlv(frame_data, value_bytes)
            elif is_mrr_platform:
                parse_mrr_point_cloud_tlv(frame_data, value_bytes)
            else:
                parse_standard_point_cloud_tlv(frame_data, value_bytes, params)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_CLUSTERS:
            if is_custom_mrr_platform:
                parse_custom_mrr_clusters_tlv(frame_data, value_bytes)
            elif is_mrr_platform:
                parse_mrr_clusters_tlv(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_TRACKS:
            if is_custom_mrr_platform:
                parse_custom_mrr_tracks_tlv(frame_data, value_bytes)
            elif is_mrr_platform:
                parse_mrr_tracks_tlv(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_PARKING_ASSIST:
            if is_custom_mrr_platform:
                parse_custom_mrr_parking_assist_tlv(frame_data, value_bytes)
            elif is_mrr_platform:
                parse_mrr_parking_assist_tlv(frame_data, value_bytes)
            
        elif tlv_type == MMWDEMO_OUTPUT_MSG_DETECTED_POINTS_SIDE_INFO:
            parse_side_info_tlv(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_WRL_DETECTED_POINTS_SIDE_INFO and is_wrl_platform:
            parse_side_info_tlv(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_EXT_MSG_STATS:
            parse_stats_tlv(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_EXT_MSG_TARGET_LIST_2D_BSD:
            parse_target_list_tlv(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_EXT_MSG_TARGET_INDEX:
            parse_target_index_tlv(frame_data, value_bytes)
            
        elif tlv_type == MMWDEMO_OUTPUT_EXT_STATS_MSG_BSD:
            parse_stats_tlv_bsd(frame_data, value_bytes)
            
        elif tlv_type == MMWDEMO_OUTPUT_MSG_STATS:
            parse_stats_tlv_type6(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_WRL_STATS and is_wrl_platform:
            parse_stats_tlv_wrl(frame_data, value_bytes)

        elif tlv_type == MMWDEMO_OUTPUT_MSG_AZIMUT_ELEVATION_STATIC_HEAT_MAP:
            parse_azimut_elevation_static_heatmap_tlv(frame_data, value_bytes)

        # Advance to the next TLV
        # --- MODIFICATION: MRR demo and WRL1432/BSD demos do NOT use 4-byte padding between TLVs ---
        # It only uses a 32-byte alignment for the TOTAL packet.
        if is_mrr_platform or is_custom_mrr_platform or is_wrl_platform or (params and params.is_bsd):
            offset += total_tlv_length
        else:
            padding = (4 - (total_tlv_length % 4)) % 4
            offset += total_tlv_length + padding
        
    return frame_data



def parse_point_cloud_tlv(frame_data, value_bytes, params, is_wrl=False):
    """Parses the point cloud TLV (Type 301 or WRL Type 6)."""
    if is_wrl:
        unit_struct = POINT_UNIT_STRUCT_WRL
    else:
        unit_struct = POINT_UNIT_STRUCT

    point_unit_len = parsing_utils.get_byte_length_from_struct(unit_struct)
    point_len = parsing_utils.get_byte_length_from_struct(POINT_STRUCT_CARTESIAN)

    point_unit = parsing_utils.read_to_struct(value_bytes[:point_unit_len], unit_struct)
    
    # --- MODIFICATION: Use internal count if available ---
    if not is_wrl:
        # Standard SDK Type 301 includes count in unit struct
        # BSD Updated Doc says: Count = (tlvHeader.length - 20) / 10
        if params and params.is_bsd:
            num_input_points = (len(value_bytes) - point_unit_len) // point_len
        else:
            num_input_points = int(point_unit['numDetPointsMajor']) + (int(point_unit['numDetPointsMinor']) << 16)
        
        # Verify it fits in the buffer
        max_possible = (len(value_bytes) - point_unit_len) // point_len
        if num_input_points > max_possible:
            logger.warning(f"Point Cloud count ({num_input_points}) exceeds buffer capacity ({max_possible}). Capping.")
            num_input_points = max_possible
    else:
        # WRL Type 6 does not have count in unit struct, rely on length
        num_input_points = (len(value_bytes) - point_unit_len) // point_len
        
    frame_data.num_points = num_input_points

    # --- NEW: Added debug message for point cloud data ---
    log_debug(lambda: f"[DEBUG] Point Cloud TLV: Found {num_input_points} detected points (is_wrl={is_wrl}).", 'log_tlv_parsing')

    if num_input_points > 0:
        points_offset = point_unit_len
        # Create a numpy structured array for efficiency
        dt = np.dtype([('x', 'i2'), ('y', 'i2'), ('z', 'i2'), 
                       ('doppler', 'i2'), ('snr', 'u1'), ('noise', 'u1')])
        
        point_cloud_data = np.frombuffer(
            value_bytes, dtype=dt, count=num_input_points, offset=points_offset
        )
        
        # Scale the raw data to get metric units
        # NOTE: The x and y fields from the sensor are relative to the sensor frame.
        # Typically, x is lateral (azimuth) and y is longitudinal (range).
        # We preserve this convention here.
        x = point_unit['xyzUnit'] * point_cloud_data['x']
        y = point_unit['xyzUnit'] * point_cloud_data['y']
        z = point_unit['xyzUnit'] * point_cloud_data['z']
        doppler = point_unit['dopplerUnit'] * point_cloud_data['doppler']
        snr = point_unit['snrUnit'] * point_cloud_data['snr']
        noise = point_unit['noiseUnit'] * point_cloud_data['noise']
        
        point_id = np.arange(num_input_points, dtype=np.float32)
        
        # Store as a (7, N) numpy array: [x, y, z, doppler, snr, noise, point_id]
        frame_data.point_cloud = np.vstack((x, y, z, doppler, snr, noise, point_id))

def parse_standard_point_cloud_tlv(frame_data, value_bytes, params=None):
    """
    Parses the Standard Detected Points TLV (Type 1).
    Format: Array of {float x, float y, float z, float doppler}
    """
    point_size = 16 # 4 * 4 bytes
    num_points = len(value_bytes) // point_size
    
    # --- MODIFICATION: BSD Updated Doc says: Do not rely on header.numDetectedObj ---
    is_bsd = (params and params.is_bsd)
    if not is_bsd:
        header_count = frame_data.header.get('numDetectedObj', 0)
        if header_count > 0 and header_count < num_points:
            log_debug(lambda: f"[DEBUG] Capping Type 1 points from {num_points} to {header_count} based on header.", 'log_tlv_parsing')
            num_points = header_count
        
    frame_data.num_points = num_points
    
    log_debug(lambda: f"[DEBUG] Point Cloud TLV (1): Found {num_points} detected points.", 'log_tlv_parsing')
    
    if num_points > 0:
        dt = np.dtype([('x', 'f4'), ('y', 'f4'), ('z', 'f4'), ('doppler', 'f4')])
        raw_points = np.frombuffer(value_bytes, dtype=dt, count=num_points)
        
        x = raw_points['x']
        y = raw_points['y']
        z = raw_points['z']
        doppler = raw_points['doppler']
        
        # SNR and Noise are not included in Type 1, initialize to 0. 
        # Type 7 (Side Info) will populate SNR if available.
        snr = np.zeros(num_points, dtype=np.float32)
        noise = np.zeros(num_points, dtype=np.float32)
        point_id = np.arange(num_points, dtype=np.float32)
        
        # Store as [x, y, z, doppler, snr, noise, point_id]
        frame_data.point_cloud = np.vstack((x, y, z, doppler, snr, noise, point_id))

def parse_side_info_tlv(frame_data, value_bytes):
    """
    Parses the Side Info TLV (Type 7).
    Format: Array of {uint16 snr, uint16 noise}
    """
    point_size = 4 # 2 * 2 bytes
    num_points = len(value_bytes) // point_size
    
    log_debug(lambda: f"[DEBUG] Side Info TLV (7): Found info for {num_points} points.", 'log_tlv_parsing')
    
    if num_points > 0:
        # Check if point cloud exists and matches size
        if frame_data.point_cloud.size == 0:
            logger.warning("Received Side Info TLV but no Point Cloud data exists yet.")
            return
        
        # --- MODIFICATION: Cap by point cloud count to handle padding ---
        pc_count = frame_data.point_cloud.shape[1]
        if pc_count < num_points:
            log_debug(lambda: f"[DEBUG] Capping Side Info points from {num_points} to {pc_count} to match point cloud.", 'log_tlv_parsing')
            num_points = pc_count
        elif pc_count > num_points:
            logger.warning(f"Side Info count ({num_points}) less than Point Cloud count ({pc_count}).")
            # We will only update the first num_points
            
        dt = np.dtype([('snr', 'u2'), ('noise', 'u2')])
        side_info = np.frombuffer(value_bytes, dtype=dt, count=num_points)
        
        # SNR and Noise are in 0.1 dB steps
        snr_vals = side_info['snr'].astype(np.float32) * 0.1
        noise_vals = side_info['noise'].astype(np.float32) * 0.1
        
        # Update Row 4 (SNR) and Row 5 (Noise) of point_cloud
        # Structure: [x, y, z, doppler, snr, noise, point_id]
        frame_data.point_cloud[4, :num_points] = snr_vals
        frame_data.point_cloud[5, :num_points] = noise_vals


def parse_stats_tlv(frame_data, value_bytes):
    """Parses the statistics TLV."""
    timing_len = parsing_utils.get_byte_length_from_struct(STATS_TIMING_STRUCT)
    power_len = parsing_utils.get_byte_length_from_struct(STATS_POWER_STRUCT)
    temp_len = parsing_utils.get_byte_length_from_struct(STATS_TEMP_STRUCT)
    
    offset = 0
    frame_data.stats_info['timing'] = parsing_utils.read_to_struct(
        value_bytes[offset : offset + timing_len], STATS_TIMING_STRUCT
    )
    offset += timing_len
    
    power_data = parsing_utils.read_to_struct(
        value_bytes[offset : offset + power_len], STATS_POWER_STRUCT
    )
    frame_data.stats_info['power'] = power_data
    offset += power_len
    
    frame_data.stats_info['temperature'] = parsing_utils.read_to_struct(
        value_bytes[offset : offset + temp_len], STATS_TEMP_STRUCT
    )
    # --- NEW: Added debug message for stats data ---
    log_debug(lambda: f"[DEBUG] Stats TLV: Parsed timing, power, and temperature info.", 'log_tlv_parsing')


def parse_target_list_tlv(frame_data, value_bytes):
    """Parses the target list (tracker) TLV."""
    target_length_in_bytes = 72  # As specified in read_and_parse_frame.m
    num_targets = len(value_bytes) // target_length_in_bytes
    frame_data.num_targets = num_targets
    
    # --- NEW: Added debug message for target list data ---
    log_debug(lambda: f"[DEBUG] Target List TLV: Found {num_targets} targets.", 'log_tlv_parsing')

    if num_targets > 0:
        targets = {
            'TID': np.zeros(num_targets, dtype='u4'),
            'S': np.zeros((6, num_targets), dtype='f4'),
            'EC': np.zeros((9, num_targets), dtype='f4'),
            'G': np.zeros(num_targets, dtype='f4'),
            'Conf': np.zeros(num_targets, dtype='f4'),
            'tPos': np.zeros((2, num_targets), dtype='f4')
        }
        offset = 0
        for i in range(num_targets):
            # Unpack each field for the current target
            targets['TID'][i] = struct.unpack('<I', value_bytes[offset : offset+4])[0]
            offset += 4
            targets['S'][:, i] = struct.unpack('<6f', value_bytes[offset : offset+24])
            offset += 24
            targets['EC'][:, i] = struct.unpack('<9f', value_bytes[offset : offset+36])
            offset += 36
            targets['G'][i] = struct.unpack('<f', value_bytes[offset : offset+4])[0]
            offset += 4
            targets['Conf'][i] = struct.unpack('<f', value_bytes[offset : offset+4])[0]
            offset += 4
            
            # Extract 2D position
            targets['tPos'][:, i] = targets['S'][0:2, i]

        frame_data.target_list = targets

def parse_target_index_tlv(frame_data, value_bytes):
    """
    Parses the Target Index TLV (Type 309).
    Value: Array of uint8 (TID) associated with each point.
    """
    num_points = len(value_bytes)
    log_debug(lambda: f"[DEBUG] Target Index TLV (309): Found index for {num_points} points.", 'log_tlv_parsing')
    
    if num_points > 0:
        # Check if point cloud exists and matches size
        pc_count = frame_data.point_cloud.shape[1] if frame_data.point_cloud.size > 0 else 0
        if pc_count > 0:
             if num_points > pc_count:
                 log_debug(lambda: f"[DEBUG] Capping Target Index from {num_points} to {pc_count}.", 'log_tlv_parsing')
                 num_points = pc_count
             frame_data.target_index = np.frombuffer(value_bytes, dtype=np.uint8, count=num_points)

def parse_stats_tlv_bsd(frame_data, value_bytes):
    """
    Parses the BSD Stats TLV (Type 1034).
    """
    bsd_stats_len = parsing_utils.get_byte_length_from_struct(STATS_BSD_STRUCT)
    if len(value_bytes) >= bsd_stats_len:
        stats = parsing_utils.read_to_struct(value_bytes[:bsd_stats_len], STATS_BSD_STRUCT)
        
        # --- NEW: Conversions from Updated Doc ---
        # 1. Total Power (mW) = sum(powerMeasured) * 0.1
        power_vals = [stats['p1'], stats['p2'], stats['p3'], stats['p4']]
        stats['power_mw'] = sum(power_vals) * 0.1
        
        # 2. Temperature: RFE = mean(temp[0:2]), Digital = temp[3]
        temp_vals = [stats['t1'], stats['t2'], stats['t3'], stats['t4']]
        stats['temp_rfe_c'] = float(np.mean(temp_vals[0:2]))
        stats['temp_digital_c'] = temp_vals[3]
        
        frame_data.sensor_stats = stats
        log_debug(lambda: f"[DEBUG] Stats BSD Type 1034 parsed. Power: {stats['power_mw']:.1f}mW, RFE: {stats['temp_rfe_c']:.1f}C", 'log_tlv_parsing')
    else:
        logger.warning(f"Stats BSD TLV length mismatch. Expected {bsd_stats_len}, got {len(value_bytes)}")

def parse_stats_tlv_type6(frame_data, value_bytes):
    """
    Parses the Type 6 Sensor Support/Stats TLV.
    Handles both standard (24 bytes) and legacy (20 bytes) formats.
    """
    length = len(value_bytes)
    standard_len = parsing_utils.get_byte_length_from_struct(STATS_TYPE6_STRUCT_STANDARD)
    legacy_len = parsing_utils.get_byte_length_from_struct(STATS_TYPE6_STRUCT_LEGACY)
    
    if length >= standard_len:
        frame_data.sensor_stats = parsing_utils.read_to_struct(value_bytes[:standard_len], STATS_TYPE6_STRUCT_STANDARD)
        log_debug(lambda: "[DEBUG] Stats Type 6 TLV (Standard) parsed.", 'log_tlv_parsing')
    elif length >= legacy_len:
        frame_data.sensor_stats = parsing_utils.read_to_struct(value_bytes[:legacy_len], STATS_TYPE6_STRUCT_LEGACY)
        # Populate missing field with 0
        frame_data.sensor_stats['guiTaskCPULoad'] = 0
        log_debug(lambda: "[DEBUG] Stats Type 6 TLV (Legacy) parsed.", 'log_tlv_parsing')
    else:
        logger.warning(f"Stats Type 6 TLV length mismatch. Expected >= {legacy_len}, got {length}")

def parse_stats_tlv_wrl(frame_data, value_bytes):
    """
    Parses the Type 4 Stats TLV for WRL14xx/64xx (Low Power SDK).
    """
    length = len(value_bytes)
    wrl_len = parsing_utils.get_byte_length_from_struct(STATS_WRL_STRUCT)
    
    if length >= wrl_len:
        frame_data.sensor_stats = parsing_utils.read_to_struct(value_bytes[:wrl_len], STATS_WRL_STRUCT)
        log_debug(lambda: "[DEBUG] Stats Type 4 TLV (WRL) parsed.", 'log_tlv_parsing')
    else:
        logger.warning(f"Stats Type 4 TLV (WRL) length mismatch. Expected >= {wrl_len}, got {length}")

def parse_azimut_elevation_static_heatmap_tlv(frame_data, value_bytes):
    """
    Parses the Type 8 Azimuth Elevation Static Heatmap TLV.
    """
    # Each sample is a complex number (int16 real, int16 imag) = 4 bytes
    num_samples = len(value_bytes) // 4
    
    # Create numpy structured array for reading int16 pairs
    dt = np.dtype([('imag', 'h'), ('real', 'h')])
    complex_pairs = np.frombuffer(value_bytes, dtype=dt, count=num_samples)
    
    # Construct complex array
    frame_data.heatmap = complex_pairs['real'].astype(np.float32) + 1j * complex_pairs['imag'].astype(np.float32)
    
    log_debug(lambda: f"[DEBUG] Heatmap Type 8 TLV parsed. {num_samples} samples.", 'log_tlv_parsing')

def parse_mrr_point_cloud_tlv(frame_data, value_bytes):
    """
    Parses the Detected Points TLV (Type 1) for the MRR Demo.
    Format: Descriptor {uint16 numObj, uint16 xyzQFormat} + Array of 10-byte structs
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_POINT_CLOUD_DESCRIPTOR_STRUCT)
    point_len = parsing_utils.get_byte_length_from_struct(MRR_POINT_STRUCT)
    
    if len(value_bytes) < descriptor_len:
        logger.warning("MRR Point Cloud TLV too short for descriptor.")
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_POINT_CLOUD_DESCRIPTOR_STRUCT)
    num_points = descriptor['numDetectedObj']
    xyz_q_format = descriptor['xyzQFormat']
    
    # Safety: Ensure Q-format is within reasonable range
    if xyz_q_format > 31:
        logger.warning(f"Unexpectedly large MRR Point Cloud Q-format: {xyz_q_format}. Capping at 15.")
        xyz_q_format = 15
        
    inv_q = 1.0 / (2**xyz_q_format)
    
    frame_data.num_points = num_points
    log_debug(lambda: f"[DEBUG] MRR Point Cloud TLV (1): Found {num_points} points, Q-format={xyz_q_format}", 'log_tlv_parsing')
    
    if num_points > 0:
        # Structured array for 10-byte points
        # Format: Doppler(i2), Peak(u2), X(i2), Y(i2), Z(i2)
        dt = np.dtype([('doppler', 'i2'), ('peakVal', 'u2'), ('x', 'i2'), ('y', 'i2'), ('z', 'i2')])
        
        # Check buffer length before unpacking
        expected_len = descriptor_len + num_points * point_len
        if len(value_bytes) < expected_len:
            logger.warning(f"MRR Point Cloud TLV buffer too short. Expected {expected_len}, got {len(value_bytes)}. Capping.")
            num_points = (len(value_bytes) - descriptor_len) // point_len
            frame_data.num_points = num_points
        
        if num_points > 0:
            raw_points = np.frombuffer(value_bytes, dtype=dt, count=num_points, offset=descriptor_len)
            
            x = raw_points['x'].astype(np.float32) * inv_q
            y = raw_points['y'].astype(np.float32) * inv_q
            z = raw_points['z'].astype(np.float32) * inv_q
            # Speed is scaled by same Q-format as per docs
            doppler = raw_points['doppler'].astype(np.float32) * inv_q
            
            # Peak value is in log2 scale with a factor of 512 for AWR1843 MRR.
            # Convert to dB: dB = (peakVal / 512.0) * 20 * log10(2) approx peakVal * 6.0206 / 512
            snr = (raw_points['peakVal'].astype(np.float32) / 512.0) * 6.0206
            noise = np.zeros(num_points, dtype=np.float32)
            point_id = np.arange(num_points, dtype=np.float32)
            
            # Store as [x, y, z, doppler, snr, noise, point_id]
            frame_data.point_cloud = np.vstack((x, y, z, doppler, snr, noise, point_id))

def parse_mrr_clusters_tlv(frame_data, value_bytes):
    """
    Parses the MRR Clusters TLV (Type 2).
    Format: Descriptor + Array of Clusters
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_CLUSTER_DESCRIPTOR_STRUCT)
    cluster_len = parsing_utils.get_byte_length_from_struct(MRR_CLUSTER_STRUCT)
    
    if len(value_bytes) < descriptor_len:
        logger.warning("MRR Cluster TLV too short for descriptor.")
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_CLUSTER_DESCRIPTOR_STRUCT)
    num_clusters = descriptor['numClusters']
    xyz_q_format = descriptor['xyzQFormat']
    
    # Safety check for large Q-format values
    if xyz_q_format > 31:
        logger.warning(f"Unexpectedly large MRR Cluster Q-format: {xyz_q_format}. Capping at 15.")
        xyz_q_format = 15
        
    inv_q = 1.0 / (2**xyz_q_format)
    
    log_debug(lambda: f"[DEBUG] MRR Cluster TLV (2): Found {num_clusters} clusters, Q-format={xyz_q_format}", 'log_tlv_parsing')
    
    if num_clusters > 0:
        dt = np.dtype([('x', 'i2'), ('y', 'i2'), ('xSize', 'i2'), ('ySize', 'i2')])
        # Note: We rely on offset to skip descriptor.
        # Check if buffer has enough data
        expected_len = descriptor_len + num_clusters * cluster_len
        if len(value_bytes) < expected_len:
            logger.warning(f"MRR Cluster TLV buffer too short. Expected {expected_len}, got {len(value_bytes)}")
            return
            
        raw_clusters = np.frombuffer(value_bytes, dtype=dt, count=num_clusters, offset=descriptor_len)
        
        # Store as list of dictionaries (AoS)
        clusters_list = []
        for i in range(num_clusters):
            clusters_list.append({
                'x': float(raw_clusters['x'][i]) * inv_q,
                'y': float(raw_clusters['y'][i]) * inv_q,
                'xSize': float(raw_clusters['xSize'][i]) * inv_q,
                'ySize': float(raw_clusters['ySize'][i]) * inv_q
            })
        frame_data.mrr_clusters = clusters_list

def parse_mrr_tracks_tlv(frame_data, value_bytes):
    """
    Parses the MRR Tracks TLV (Type 3).
    Format: Descriptor + Array of Tracks
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_TRACK_DESCRIPTOR_STRUCT)
    track_len = parsing_utils.get_byte_length_from_struct(MRR_TRACK_STRUCT)
    
    if len(value_bytes) < descriptor_len:
        logger.warning("MRR Track TLV too short for descriptor.")
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_TRACK_DESCRIPTOR_STRUCT)
    num_tracks = descriptor['numTracks']
    xyz_q_format = descriptor['xyzQFormat']
    
    # Safety check for large Q-format values
    if xyz_q_format > 31:
        logger.warning(f"Unexpectedly large MRR Track Q-format: {xyz_q_format}. Capping at 15.")
        xyz_q_format = 15
        
    inv_q = 1.0 / (2**xyz_q_format)
    
    log_debug(lambda: f"[DEBUG] MRR Track TLV (3): Found {num_tracks} tracks, Q-format={xyz_q_format}", 'log_tlv_parsing')
    
    # Set num_targets so the logger is aware of hardware tracks
    frame_data.num_targets = num_tracks

    if num_tracks > 0:
        dt = np.dtype([
            ('x', 'i2'), ('y', 'i2'), 
            ('vx', 'i2'), ('vy', 'i2'), 
            ('xSize', 'i2'), ('ySize', 'i2')
        ])
        
        expected_len = descriptor_len + num_tracks * track_len
        if len(value_bytes) < expected_len:
             logger.warning(f"MRR Track TLV buffer too short. Expected {expected_len}, got {len(value_bytes)}")
             return

        raw_tracks = np.frombuffer(value_bytes, dtype=dt, count=num_tracks, offset=descriptor_len)
        
        # Store as list of dictionaries (AoS)
        tracks_list = []
        for i in range(num_tracks):
            tracks_list.append({
                'tid': i + 1, # Use index as TID (1-based) since it's not in the stream
                'x': float(raw_tracks['x'][i]) * inv_q,
                'y': float(raw_tracks['y'][i]) * inv_q,
                'vx': float(raw_tracks['vx'][i]) * inv_q,
                'vy': float(raw_tracks['vy'][i]) * inv_q,
                'xSize': float(raw_tracks['xSize'][i]) * inv_q,
                'ySize': float(raw_tracks['ySize'][i]) * inv_q
            })
        frame_data.mrr_tracks = tracks_list

def parse_mrr_parking_assist_tlv(frame_data, value_bytes):
    """
    Parses the MRR Parking Assist TLV (Type 4).
    Format: Descriptor + Array of uint16 distances
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_TRACK_DESCRIPTOR_STRUCT) # Uses same descriptor
    
    if len(value_bytes) < descriptor_len:
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_TRACK_DESCRIPTOR_STRUCT)
    num_objects = descriptor['numTracks'] # Field name is numTracks in descriptor struct
    xyz_q_format = descriptor['xyzQFormat']
    inv_q = 1.0 / (2**xyz_q_format)
    
    log_debug(lambda: f"[DEBUG] MRR Parking Assist TLV (4): Found {num_objects} distances, Q-format={xyz_q_format}", 'log_tlv_parsing')
    
    if num_objects > 0:
        # Array of uint16
        distances = np.frombuffer(value_bytes, dtype='u2', count=num_objects, offset=descriptor_len)
        # Store scaled values in frame_data (e.g., as part of sensor_stats or a new field)
        frame_data.sensor_stats['parking_assist_distances'] = (distances.astype(np.float32) * inv_q).tolist()
    
# --- CUSTOM MRR PARSERS (cMRR_UART_SPEC.md) ---

def parse_custom_mrr_point_cloud_tlv(frame_data, value_bytes):
    """
    Parses the Custom MRR Point Cloud TLV (Type 1).
    Empirically verified to match standard MRR 10-byte structure.
    Format: Descriptor {uint16 numObj, uint16 xyzQFormat} + Array of 10-byte structs
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_POINT_CLOUD_DESCRIPTOR_STRUCT)
    point_len = 10 # Aligned with Standard MRR
    
    if len(value_bytes) < descriptor_len:
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_POINT_CLOUD_DESCRIPTOR_STRUCT)
    num_points = descriptor['numDetectedObj']
    xyz_q_format = descriptor['xyzQFormat']
    
    # Safety: Ensure Q-format is within reasonable range
    if xyz_q_format > 31:
        xyz_q_format = 15
        
    inv_q = 1.0 / (2**xyz_q_format)
    
    # Safety: Verify buffer has enough data for requested count
    expected_len = descriptor_len + num_points * point_len
    if len(value_bytes) < expected_len:
        num_points = (len(value_bytes) - descriptor_len) // point_len
        
    frame_data.num_points = num_points
    
    if num_points > 0:
        # Standard MRR Order: Doppler(i2), Peak(u2), X(i2), Y(i2), Z(i2)
        dt = np.dtype([('doppler', 'i2'), ('peakVal', 'u2'), ('x', 'i2'), ('y', 'i2'), ('z', 'i2')])
        raw_points = np.frombuffer(value_bytes, dtype=dt, count=num_points, offset=descriptor_len)
        
        x = raw_points['x'].astype(np.float32) * inv_q
        y = raw_points['y'].astype(np.float32) * inv_q
        z = raw_points['z'].astype(np.float32) * inv_q
        doppler = raw_points['doppler'].astype(np.float32) * inv_q
        
        # Peak value scaling for AWR1843 MRR (Convert to dB)
        snr = (raw_points['peakVal'].astype(np.float32) / 512.0) * 6.0206
        noise = np.zeros(num_points, dtype=np.float32)
        point_id = np.arange(num_points, dtype=np.float32)
        
        frame_data.point_cloud = np.vstack((x, y, z, doppler, snr, noise, point_id))

def parse_custom_mrr_clusters_tlv(frame_data, value_bytes):
    """
    Parses the Custom MRR Clusters TLV (Type 2).
    Format: Descriptor {uint16 numClusters, uint16 xyzQFormat} + Array of 8-byte structs
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_CLUSTER_DESCRIPTOR_STRUCT)
    cluster_len = 8 # Aligned with Standard MRR
    
    if len(value_bytes) < descriptor_len:
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_CLUSTER_DESCRIPTOR_STRUCT)
    num_clusters = descriptor['numClusters']
    xyz_q_format = descriptor['xyzQFormat']
    
    if xyz_q_format > 31:
        xyz_q_format = 15
        
    inv_q = 1.0 / (2**xyz_q_format)

    if num_clusters > 0:
        dt = np.dtype([('x', 'i2'), ('y', 'i2'), ('xSize', 'i2'), ('ySize', 'i2')])
        
        expected_len = descriptor_len + num_clusters * cluster_len
        if len(value_bytes) < expected_len:
             num_clusters = (len(value_bytes) - descriptor_len) // cluster_len

        if num_clusters > 0:
            raw_clusters = np.frombuffer(value_bytes, dtype=dt, count=num_clusters, offset=descriptor_len)
            
            clusters_list = []
            for i in range(num_clusters):
                clusters_list.append({
                    'x': float(raw_clusters['x'][i]) * inv_q,
                    'y': float(raw_clusters['y'][i]) * inv_q,
                    'xSize': float(raw_clusters['xSize'][i]) * inv_q,
                    'ySize': float(raw_clusters['ySize'][i]) * inv_q
                })
            frame_data.mrr_clusters = clusters_list

def parse_custom_mrr_tracks_tlv(frame_data, value_bytes):
    """
    Parses the Custom MRR Tracks TLV (Type 3).
    Matches cMRR_UART_SPEC.md (14-byte structure with hardware tid).
    Format: Descriptor + Array of 14-byte structs (x, y, vx, vy, xSize, ySize, tid)
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_TRACK_DESCRIPTOR_STRUCT)
    track_len = parsing_utils.get_byte_length_from_struct(CUSTOM_MRR_TRACK_STRUCT)
    
    if len(value_bytes) < descriptor_len:
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_TRACK_DESCRIPTOR_STRUCT)
    num_tracks = descriptor['numTracks']
    xyz_q_format = descriptor['xyzQFormat']
    
    if xyz_q_format > 31:
        xyz_q_format = 15
        
    inv_q = 1.0 / (2**xyz_q_format)
    frame_data.num_targets = num_tracks

    if num_tracks > 0:
        dt = np.dtype([
            ('x', 'i2'), ('y', 'i2'), 
            ('vx', 'i2'), ('vy', 'i2'), 
            ('xSize', 'i2'), ('ySize', 'i2'),
            ('tid', 'u2')
        ])
        
        expected_len = descriptor_len + num_tracks * track_len
        if len(value_bytes) < expected_len:
             return

        raw_tracks = np.frombuffer(value_bytes, dtype=dt, count=num_tracks, offset=descriptor_len)
        
        tracks_list = []
        for i in range(num_tracks):
            tracks_list.append({
                'tid': int(raw_tracks['tid'][i]),
                'x': float(raw_tracks['x'][i]) * inv_q,
                'y': float(raw_tracks['y'][i]) * inv_q,
                'vx': float(raw_tracks['vx'][i]) * inv_q,
                'vy': float(raw_tracks['vy'][i]) * inv_q,
                'xSize': float(raw_tracks['xSize'][i]) * inv_q,
                'ySize': float(raw_tracks['ySize'][i]) * inv_q
            })
        frame_data.mrr_tracks = tracks_list

def parse_custom_mrr_parking_assist_tlv(frame_data, value_bytes):
    """
    Parses the Custom MRR Parking Assist TLV (Type 4).
    Matches Standard MRR behavior by applying xyzQFormat if descriptor is present.
    """
    descriptor_len = parsing_utils.get_byte_length_from_struct(MRR_TRACK_DESCRIPTOR_STRUCT)

    if len(value_bytes) < descriptor_len:
        # Fallback for flat 32-bin format if descriptor is missing
        if len(value_bytes) >= 64:
            distances = np.frombuffer(value_bytes[:64], dtype=np.uint16)
            frame_data.parking_assist = distances.tolist()
        return

    descriptor = parsing_utils.read_to_struct(value_bytes[:descriptor_len], MRR_TRACK_DESCRIPTOR_STRUCT)
    num_objects = descriptor['numTracks']
    xyz_q_format = descriptor['xyzQFormat']
    inv_q = 1.0 / (2**xyz_q_format)

    if num_objects > 0:
        distances = np.frombuffer(value_bytes, dtype='u2', count=num_objects, offset=descriptor_len)
        frame_data.parking_assist = (distances.astype(np.float32) * inv_q).tolist()

