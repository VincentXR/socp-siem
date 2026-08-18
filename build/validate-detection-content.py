#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the versioned detection content pack without requiring services."""

import argparse
import json
import re
import sys
from pathlib import Path


VERSION = re.compile(r"^(?:\d+\.\d+\.\d+|\d{4}\.\d{2}\.\d{2})$")
TYPES = {"pattern", "threshold", "correlation", "correlation-set", "baseline", "rare"}
SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
OPS = {"eq", "ne", "contains", "startswith", "endswith", "ge", "gtsev",
       "regex", "gt", "gte", "lt", "lte", "inlist", "notinlist"}
MIN_RULES = 20


def fail(errors, message):
    errors.append(message)


def validate_conditions(errors, conditions, label):
    if conditions is None:
        return
    if not isinstance(conditions, list):
        fail(errors, f"{label} must be an array")
        return
    for ci, condition in enumerate(conditions):
        if (not isinstance(condition, dict)
                or not all(k in condition for k in ("field", "op", "value"))):
            fail(errors, f"{label}[{ci}] requires field/op/value")
            continue
        operation = str(condition.get("op", "")).lower()
        if operation not in OPS:
            fail(errors, f"{label}[{ci}] unsupported op: {operation}")
        if operation == "regex":
            try:
                re.compile(str(condition.get("value", "")))
            except re.error as exc:
                fail(errors, f"{label}[{ci}] invalid regex: {exc}")


def validate(path):
    errors = []
    try:
        pack = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return [f"cannot parse {path}: {exc}"]

    for field in ("packId", "version", "schemaVersion", "maintainer", "rules"):
        if field not in pack:
            fail(errors, f"missing pack field: {field}")
    if not VERSION.match(str(pack.get("version", ""))):
        fail(errors, f"invalid pack version: {pack.get('version')}")

    rules = pack.get("rules")
    if not isinstance(rules, list) or not rules:
        fail(errors, "rules must be a non-empty array")
        return errors
    if len(rules) < MIN_RULES:
        fail(errors, f"rules must contain at least {MIN_RULES} executable detections")

    ids = set()
    for index, item in enumerate(rules):
        prefix = f"rules[{index}]"
        if not isinstance(item, dict):
            fail(errors, f"{prefix} must be an object")
            continue
        rule_id = str(item.get("id", ""))
        if not rule_id:
            fail(errors, f"{prefix} missing id")
        elif rule_id in ids:
            fail(errors, f"duplicate rule id: {rule_id}")
        ids.add(rule_id)
        for field in ("version", "status", "owner", "description", "dataSources", "mitre", "spec", "tests"):
            if field not in item:
                fail(errors, f"{prefix} {rule_id} missing {field}")
        if not VERSION.match(str(item.get("version", ""))):
            fail(errors, f"{prefix} {rule_id} invalid version")
        if item.get("status") not in {"DRAFT", "TESTING", "ACTIVE", "DISABLED", "ARCHIVED"}:
            fail(errors, f"{prefix} {rule_id} invalid status")
        if not isinstance(item.get("dataSources"), list) or not item.get("dataSources"):
            fail(errors, f"{prefix} {rule_id} dataSources must be non-empty")
        if not isinstance(item.get("mitre"), list) or not item.get("mitre"):
            fail(errors, f"{prefix} {rule_id} mitre must be non-empty")

        spec = item.get("spec")
        if not isinstance(spec, dict):
            fail(errors, f"{prefix} {rule_id} spec must be an object")
        else:
            if spec.get("id") != rule_id:
                fail(errors, f"{prefix} {rule_id} spec.id mismatch")
            if spec.get("type") not in TYPES:
                fail(errors, f"{prefix} {rule_id} unsupported type")
            for field in ("name", "severity", "message"):
                if not str(spec.get(field, "")).strip():
                    fail(errors, f"{prefix} {rule_id} spec missing {field}")
            if str(spec.get("severity", "")).upper() not in SEVERITIES:
                fail(errors, f"{prefix} {rule_id} invalid severity")
            if spec.get("type") == "threshold":
                if not isinstance(spec.get("threshold"), int) or spec["threshold"] <= 0:
                    fail(errors, f"{prefix} {rule_id} threshold must be positive integer")
                if not str(spec.get("keyField", "")).strip():
                    fail(errors, f"{prefix} {rule_id} threshold missing keyField")
            if spec.get("type") in {"correlation", "correlation-set"}:
                if not isinstance(spec.get("steps"), list) or len(spec["steps"]) < 2:
                    fail(errors, f"{prefix} {rule_id} correlation needs two steps")
                if not str(spec.get("keyField", "")).strip():
                    fail(errors, f"{prefix} {rule_id} correlation missing keyField")
            if spec.get("type") in {"baseline", "rare"} and not str(spec.get("keyField", "")).strip():
                fail(errors, f"{prefix} {rule_id} stateful rule missing keyField")
            if spec.get("type") == "rare" and not str(spec.get("valueField", "")).strip():
                fail(errors, f"{prefix} {rule_id} rare rule missing valueField")
            validate_conditions(errors, spec.get("match", []), f"{prefix} {rule_id} match")
            for si, step in enumerate(spec.get("steps", [])):
                validate_conditions(errors, step, f"{prefix} {rule_id} steps[{si}]")

        tests = item.get("tests")
        if not isinstance(tests, list) or not tests:
            fail(errors, f"{prefix} {rule_id} tests must be non-empty")
        else:
            expectations = {bool(test.get("expectAlert")) for test in tests if isinstance(test, dict)}
            if expectations != {True, False}:
                fail(errors, f"{prefix} {rule_id} needs both positive and negative vectors")
            for ti, test in enumerate(tests):
                if not isinstance(test, dict) or not isinstance(test.get("events"), list) or not test["events"]:
                    fail(errors, f"{prefix} {rule_id} tests[{ti}] needs events")
                elif any(not isinstance(event, dict) for event in test["events"]):
                    fail(errors, f"{prefix} {rule_id} tests[{ti}] contains malformed event")
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="services/detect-web/src/main/resources/detection-content/manifest.json")
    args = parser.parse_args()
    errors = validate(Path(args.manifest))
    if errors:
        print("Detection content validation FAILED")
        for error in errors:
            print(" - " + error)
        return 1
    print(f"Detection content validation PASSED: {args.manifest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
