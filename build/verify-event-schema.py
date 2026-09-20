#!/usr/bin/env python3
"""Validate canonical event schema shape and additive compatibility."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "schemas"
VERSIONED = re.compile(r"canonical-event-(\d+)\.(\d+)\.json$")


def load_schemas():
    result = []
    for path in sorted(SCHEMA_DIR.glob("canonical-event-*.json")):
        match = VERSIONED.fullmatch(path.name)
        if not match:
            raise ValueError(f"invalid schema filename: {path.name}")
        result.append((int(match.group(1)), int(match.group(2)), path,
                       json.loads(path.read_text(encoding="utf-8"))))
    if not result:
        raise ValueError("no canonical event schema found")
    return result


def validate_current(data):
    required = set(data.get("required", []))
    expected = {"schemaVersion", "eventId", "tenantId", "timestamp", "source", "host", "severity", "msg", "fields"}
    missing = expected - required
    if missing:
        raise ValueError(f"current schema is missing required fields: {sorted(missing)}")
    if data.get("properties", {}).get("schemaVersion", {}).get("const") != "1.0":
        raise ValueError("current schema must declare schemaVersion const 1.0")
    if data.get("properties", {}).get("tenantId", {}).get("type") != "string":
        raise ValueError("tenantId must remain a string")


def validate_detection_delivery():
    path = SCHEMA_DIR / "detection-delivery-2.0.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    required = set(data.get("required", []))
    expected_top = {"eventId", "tenantId", "timestamp", "source", "host", "fields"}
    if not expected_top <= required:
        raise ValueError(f"detection delivery schema missing top-level fields: {sorted(expected_top - required)}")
    fields = data.get("properties", {}).get("fields", {})
    required_fields = set(fields.get("required", []))
    expected_fields = {
        "detection_delivery_id", "detection_source_event_id", "detection_delivery_kind",
        "detection_delivery_dimension", "detection_delivery_value", "detection_routing_version",
        "detection_route_plan_version", "detection_delivery_schema", "detection_source_topic",
        "detection_source_partition", "detection_source_offset",
        "detection_routing_field", "detection_routing_value",
    }
    if not expected_fields <= required_fields:
        raise ValueError(
            f"detection delivery schema missing routed fields: {sorted(expected_fields - required_fields)}")
    props = fields.get("properties", {})
    if props.get("detection_routing_version", {}).get("const") != "detection-routing-v2":
        raise ValueError("detection delivery routing version must be detection-routing-v2")
    if props.get("detection_delivery_schema", {}).get("const") != "detection-delivery-schema-v2":
        raise ValueError("detection delivery schema marker mismatch")


def main():
    try:
        versions = load_schemas()
        for _, _, _, data in versions:
            validate_current(data)
        for old, new in zip(versions, versions[1:]):
            old_data, new_data = old[3], new[3]
            old_required = set(old_data.get("required", []))
            new_required = set(new_data.get("required", []))
            # A new consumer must continue to accept messages written by the
            # old producer. Adding a required field is therefore breaking;
            # removing one is additive and safe. (The old implementation had
            # this relation reversed and could approve breaking schemas.)
            if not new_required <= old_required:
                added = sorted(new_required - old_required)
                raise ValueError(f"schema {new[2].name} added required fields: {added}")
            for field in old_data.get("properties", {}):
                old_property = old_data.get("properties", {}).get(field, {})
                new_property = new_data.get("properties", {}).get(field, {})
                if old_property.get("type") != new_property.get("type"):
                    raise ValueError(f"schema {new[2].name} changed type of {field}")
        validate_detection_delivery()
        print(f"canonical event schemas valid: {len(versions)} version(s); routed detection delivery valid")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"canonical event schema validation failed: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
