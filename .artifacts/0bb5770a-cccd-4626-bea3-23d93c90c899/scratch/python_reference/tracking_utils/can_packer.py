import numpy as np
import struct

def pack_adas_can_frames(adas_state):
    """
    Converts ADAS state into three 8-byte CAN frame payloads (0x300, 0x301, 0x302).
    
    This implementation achieves bit-parity with the "Experimental" MATLAB reference
    (pack_adas_can_frames.m) and adheres to the AWR1843 TCAN bit-level specifications.
    
    Note on Byte Order: Although the specification refers to "Motorola (Big-Endian)",
    the reference implementation utilizes Little-Endian serialization of the 64-bit
    payload words into the byte array. This Python module matches that behavior 
    to ensure hardware-level compatibility.
    
    Args:
        adas_state (dict): The current ADAS state.
        
    Returns:
        dict: A dictionary mapping CAN IDs (int) to 8-byte payloads (bytes).
    """
    can_frames = {}
    
    # --- Frame 0: 0x300 (ACC Primary Target) ---
    # Cycle Time: 50ms
    # Signals: ID (6), Dist (12), RelV (12), RelA (10), Status (3)
    
    # 1. Target ID (6 bits, 0-63)
    id_bits = int(adas_state.get('poi_id', 0)) & 0x3F
    
    # 2. Longi Dist (12 bits, res: 0.1m, range: 409.5m)
    dist_val = int(min(max(adas_state.get('acc_dist', 0.0) * 10.0, 0.0), 4095))
    
    # 3. Rel Vel (12 bits, res: 0.05, offset: 102.4, range: +/- 102.4)
    # Value = (Phys + 102.4) / 0.05
    vel_val = int(min(max((adas_state.get('acc_rel_v', 0.0) + 102.4) / 0.05, 0.0), 4095))
    
    # 4. Rel Acc (10 bits, res: 0.05, offset: 25.6, range: +/- 25.6)
    acc_val = int(min(max((adas_state.get('acc_rel_a', 0.0) + 25.6) / 0.05, 0.0), 1023))
    
    # 5. Status (3 bits)
    status_bits = int(adas_state.get('acc_status', 0)) & 0x07
    
    # Pack into 64-bit payload using MATLAB-aligned bitshifts
    payload0 = 0
    payload0 |= (id_bits << 0)
    payload0 |= (dist_val << 6)
    payload0 |= (vel_val << 18)
    payload0 |= (acc_val << 30)
    payload0 |= (status_bits << 40)
    
    # Serialize to 8 bytes (Little-Endian to match MATLAB can_frames(1, i) extraction)
    can_frames[0x300] = struct.pack('<Q', payload0)
    
    # --- Frame 1: 0x301 (AEB & FCW Threats) ---
    # Cycle Time: 20ms
    # Signals: TTI (10), Risk (2), FCW (2), Prefill (1), Confidence (8)
    
    # 1. TTI (10 bits, res: 0.01s, range: 10.23s)
    tti_val = int(min(max(adas_state.get('tti', 10.23) * 100.0, 0.0), 1023))
    
    # 2. Risk Level (2 bits: 0=SAFE, 1=WARN, 2=CRIT)
    risk_bits = int(adas_state.get('aeb_risk', 0)) & 0x03
    
    # 3. FCW Stage (2 bits: 0=NONE, 1=VIS, 2=AUD)
    fcw_bits = int(adas_state.get('fcw_stage', 0)) & 0x03
    
    # 4. Brake Prefill (1 bit)
    prefill_bit = 1 if adas_state.get('brake_prefill_req', False) else 0
    
    # 5. Confidence (8 bits, 0-100%)
    conf_bits = int(min(max(adas_state.get('target_confidence', 0.0), 0.0), 255))
    
    payload1 = 0
    payload1 |= (tti_val << 0)
    payload1 |= (risk_bits << 10)
    payload1 |= (fcw_bits << 12)
    payload1 |= (prefill_bit << 14)
    payload1 |= (conf_bits << 15)
    
    can_frames[0x301] = struct.pack('<Q', payload1)

    # --- Frame 2: 0x302 (BSD & Environment) ---
    # Cycle Time: 100ms
    # Signals: BSDL (1), BSDR (1), LCA (2), Corridor (8), Blindness (2), LCA_TTC (10)
    
    # 1. BSD Left (1 bit)
    bsdl = 1 if adas_state.get('bsd_left_active', False) else 0
    
    # 2. BSD Right (1 bit)
    bsdr = 1 if adas_state.get('bsd_right_active', False) else 0
    
    # 3. LCA Warning Level (2 bits)
    lca_bits = int(adas_state.get('lca_warning_level', 0)) & 0x03
    
    # 4. Corridor Width (8 bits, res: 0.1m, range: 25.5m)
    cw_bits = int(min(max(adas_state.get('corridor_width', 0.0) * 10.0, 0.0), 255))
    
    # 5. Sensor Blindness (2 bits)
    blind_bits = int(adas_state.get('sensor_blindness', 0)) & 0x03
    
    # 6. LCA TTC (10 bits, res: 0.01s, range: 10.23s)
    lca_ttc = int(min(max(adas_state.get('approach_ttc', 10.23) * 100.0, 0.0), 1023))
    
    payload2 = 0
    payload2 |= (bsdl << 0)
    payload2 |= (bsdr << 1)
    payload2 |= (lca_bits << 2)
    payload2 |= (cw_bits << 4)
    payload2 |= (blind_bits << 12)
    payload2 |= (lca_ttc << 14)
    
    can_frames[0x302] = struct.pack('<Q', payload2)
    
    return can_frames
