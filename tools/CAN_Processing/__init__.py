"""Modular CANedge2 MDF4 decoding, auditing, time alignment, and MCAP ingestion package."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, Optional

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent / "can_mcap_config.json"


def load_can_config(config_path: Optional[str] = None) -> Dict[str, Any]:
    """Load CAN processing and MCAP ingestion configuration."""
    target = Path(config_path).resolve() if config_path else DEFAULT_CONFIG_PATH
    with open(target, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    # Resolve DBC path relative to the config file directory when relative
    dbc_rel = cfg.get("dbc_path", "../VCU.dbc")
    dbc_p = Path(dbc_rel)
    if not dbc_p.is_absolute():
        dbc_p = (target.parent / dbc_p).resolve()
    cfg["resolved_dbc_path"] = str(dbc_p)
    cfg["config_path"] = str(target)
    return cfg


from .pipeline import CanSessionBundle, prepare_can_bundle, write_can_bundle_to_mcap

__all__ = [
    "DEFAULT_CONFIG_PATH",
    "load_can_config",
    "CanSessionBundle",
    "prepare_can_bundle",
    "write_can_bundle_to_mcap",
]
