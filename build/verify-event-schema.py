#!/usr/bin/env python3
"""Validate canonical event schema shape and additive compatibility."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "schemas"
VERSIONED = re.compile(r"canonical-event-(\d+)\.(\d+)\.json$")
ANNOTATIONS = {"$id", "$comment", "title", "description", "default", "examples", "deprecated"}
SUPPORTED = {
    "$schema", "type", "const", "enum", "required", "properties", "additionalProperties", "items",
    "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
    "minItems", "maxItems", "minProperties", "maxProperties", "uniqueItems", "pattern", "format",
}


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
    return sorted(result, key=lambda entry: (entry[0], entry[1]))


def validate_compatible(old, new, location="$"):
    """Conservatively prove old-valid instances remain accepted by the new schema.

    This is not a general JSON Schema implication solver. Changed structures
    involving unsupported keywords fail closed instead of claiming compatibility.
    """
    def fail(reason):
        raise ValueError(f"{location}: {reason}")

    for schema in (old, new):
        if not isinstance(schema, (dict, bool)):
            fail("schema must be an object or boolean")
    if old is False or new is True:
        return
    if old is True:
        old = {}
    if new is False:
        fail("new schema rejects previously accepted values")

    def references(value):
        if isinstance(value, dict):
            return bool(value.keys() & {"$ref", "$dynamicRef", "$recursiveRef"}) or any(
                references(item) for item in value.values())
        return isinstance(value, list) and any(references(item) for item in value)

    if old.get("$id") != new.get("$id") and (references(old) or references(new)):
        fail("schema ID changed the base URI of a reference")
    old = {key: value for key, value in old.items() if key not in ANNOTATIONS}
    new = {key: value for key, value in new.items() if key not in ANNOTATIONS}
    if json.dumps(old, sort_keys=True) == json.dumps(new, sort_keys=True) or not new:
        return
    unknown = (old.keys() | new.keys()) - SUPPORTED
    if unknown:
        fail(f"cannot prove compatibility with keywords {sorted(unknown)}")
    if old.get("$schema") != new.get("$schema"):
        fail("schema dialect changed")

    def types(schema):
        value = schema.get("type", ["null", "boolean", "object", "array", "number", "integer", "string"])
        values = [value] if isinstance(value, str) else value
        allowed = {"null", "boolean", "object", "array", "number", "integer", "string"}
        if not isinstance(values, list) or not values or any(not isinstance(item, str) or item not in allowed for item in values):
            fail("invalid type declaration")
        result = set(values)
        if "number" in result:
            result.add("integer")
        return result

    if not types(old) <= types(new):
        fail("accepted types were narrowed")

    def same_value(left, right):
        # Python equates True with 1; JSON Schema does not.
        return json.dumps(left, sort_keys=True) == json.dumps(right, sort_keys=True)

    if "const" in new:
        old_values = [old["const"]] if "const" in old else old.get("enum")
        if not isinstance(old_values, list) or not old_values or any(
                not same_value(value, new["const"]) for value in old_values):
            fail("const was introduced or changed")
    if "enum" in new:
        old_values = [old["const"]] if "const" in old else old.get("enum")
        if not isinstance(new["enum"], list) or not isinstance(old_values, list) or any(
                not any(same_value(value, candidate) for candidate in new["enum"]) for value in old_values):
            fail("enum was introduced or narrowed")

    for keyword in ("minLength", "minimum", "exclusiveMinimum", "minItems", "minProperties"):
        if keyword in new and (keyword not in old or new[keyword] > old[keyword]):
            fail(f"{keyword} was introduced or tightened")
    for keyword in ("maxLength", "maximum", "exclusiveMaximum", "maxItems", "maxProperties"):
        if keyword in new and (keyword not in old or new[keyword] < old[keyword]):
            fail(f"{keyword} was introduced or tightened")
    for keyword in ("pattern", "format"):
        if keyword in new and old.get(keyword) != new[keyword]:
            fail(f"{keyword} was introduced or changed")
    if new.get("uniqueItems") is True and old.get("uniqueItems") is not True:
        fail("array uniqueness was introduced")

    def required(schema):
        value = schema.get("required", [])
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            fail("required must be a list of property names")
        return set(value)

    added = required(new) - required(old)
    if added:
        fail(f"added required fields: {sorted(added)}")
    old_properties, new_properties = old.get("properties", {}), new.get("properties", {})
    if not isinstance(old_properties, dict) or not isinstance(new_properties, dict):
        fail("properties must be an object")
    old_extra, new_extra = old.get("additionalProperties", True), new.get("additionalProperties", True)
    for field in old_properties.keys() | new_properties.keys():
        validate_compatible(old_properties.get(field, old_extra), new_properties.get(field, new_extra),
                            f"{location}.{field}")
    validate_compatible(old_extra, new_extra, f"{location}.*")
    validate_compatible(old.get("items", True), new.get("items", True), f"{location}[]")


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
            validate_compatible(old[3], new[3], new[2].name)
        validate_detection_delivery()
        print(f"canonical event contract checks passed: {len(versions)} version(s); routed delivery markers passed")
        return 0
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print(f"canonical event schema validation failed: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
