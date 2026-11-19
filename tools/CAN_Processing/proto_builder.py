"""Dynamic Protobuf schema & message factory for DBC-decoded CAN MCAP topics (Stage 5)."""

from __future__ import annotations

import re
from typing import Any, Dict, Tuple

from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

from .mf4_loader import DecodedCanMessage

FIELD_TYPE_MAP = {
    "double": descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE,
    "int64": descriptor_pb2.FieldDescriptorProto.TYPE_INT64,
    "int32": descriptor_pb2.FieldDescriptorProto.TYPE_INT32,
    "bool": descriptor_pb2.FieldDescriptorProto.TYPE_BOOL,
    "string": descriptor_pb2.FieldDescriptorProto.TYPE_STRING,
}


def _sanitize_proto_ident(name: str, fallback_prefix: str = "sig") -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]", "_", name or "")
    if not cleaned or cleaned[0].isdigit():
        cleaned = f"{fallback_prefix}_{cleaned}"
    return cleaned


def build_can_protobuf_classes(
    messages: Dict[str, DecodedCanMessage],
    package: str = "roadsense.can",
    proto_filename: str = "roadsense_can_dynamic.proto",
) -> Tuple[Dict[str, Any], Any]:
    """
    Dynamically synthesize one Protobuf message class per DBC CAN message inside a fresh,
    isolated DescriptorPool (safe across multiple sessions converted in the same Python process).

    Returns `(classes_by_msg_name, CanSyncAuditClass)`.
    """
    fdp = descriptor_pb2.FileDescriptorProto(
        name=proto_filename,
        package=package,
        syntax="proto3",
    )

    used_msg_names: Dict[str, int] = {}
    proto_name_by_msg: Dict[str, str] = {}

    for msg_name, decoded in sorted(messages.items()):
        base_msg = _sanitize_proto_ident(msg_name, fallback_prefix="CANMsg")
        if base_msg in used_msg_names:
            used_msg_names[base_msg] += 1
            p_msg_name = f"{base_msg}_{used_msg_names[base_msg]}"
        else:
            used_msg_names[base_msg] = 0
            p_msg_name = base_msg
        proto_name_by_msg[msg_name] = p_msg_name

        mdp = fdp.message_type.add()
        mdp.name = p_msg_name

        used_fields = {"raw_can_ts_ns", "applied_offset_ms"}
        field_num = 1
        for sig_name in decoded.signals.keys():
            clean_sig = _sanitize_proto_ident(sig_name, fallback_prefix="sig")
            while clean_sig in used_fields:
                clean_sig = f"{clean_sig}_f"
            used_fields.add(clean_sig)

            ftype_key = decoded.field_types.get(sig_name, "double")
            ftype = FIELD_TYPE_MAP.get(ftype_key, descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE)

            f = mdp.field.add()
            f.name = clean_sig
            f.number = field_num
            f.label = descriptor_pb2.FieldDescriptorProto.LABEL_OPTIONAL
            f.type = ftype
            field_num += 1

        # Append standard provenance metadata fields per message frame
        for meta_name, meta_type in (
            ("raw_can_ts_ns", descriptor_pb2.FieldDescriptorProto.TYPE_INT64),
            ("applied_offset_ms", descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE),
        ):
            mf = mdp.field.add()
            mf.name = meta_name
            mf.number = field_num
            mf.label = descriptor_pb2.FieldDescriptorProto.LABEL_OPTIONAL
            mf.type = meta_type
            field_num += 1

    # Add companion /can/sync_audit summary schema
    audit_mdp = fdp.message_type.add()
    audit_mdp.name = "CanSyncAudit"
    audit_fields = [
        ("primary_tier", descriptor_pb2.FieldDescriptorProto.TYPE_STRING),
        ("completeness_status", descriptor_pb2.FieldDescriptorProto.TYPE_STRING),
        ("coverage_pct", descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE),
        ("global_median_offset_ms", descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE),
        ("estimated_drift_ppm", descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE),
        ("residual_before_ms", descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE),
        ("residual_after_ms", descriptor_pb2.FieldDescriptorProto.TYPE_DOUBLE),
        ("matched_pairs", descriptor_pb2.FieldDescriptorProto.TYPE_INT64),
    ]
    for fnum, (fname, ftype) in enumerate(audit_fields, 1):
        af = audit_mdp.field.add()
        af.name = fname
        af.number = fnum
        af.label = descriptor_pb2.FieldDescriptorProto.LABEL_OPTIONAL
        af.type = ftype

    pool = descriptor_pool.DescriptorPool()
    pool.Add(fdp)

    classes_by_msg: Dict[str, Any] = {}
    for msg_name, p_msg_name in proto_name_by_msg.items():
        desc = pool.FindMessageTypeByName(f"{package}.{p_msg_name}")
        classes_by_msg[msg_name] = message_factory.GetMessageClass(desc)

    audit_desc = pool.FindMessageTypeByName(f"{package}.CanSyncAudit")
    audit_cls = message_factory.GetMessageClass(audit_desc)
    return classes_by_msg, audit_cls
