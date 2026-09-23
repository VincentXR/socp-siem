#!/usr/bin/env python3
"""Validate the versioned, evidence-first Investigation Agent evaluation set."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "build" / "datasets" / "investigation-v1.json"


def require(condition, message):
    # Validation must remain active under python -O / PYTHONOPTIMIZE.
    if not condition:
        raise ValueError(message)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def string_list(value):
    return isinstance(value, list) and all(nonempty(item) for item in value)


def validate(payload):
    require(isinstance(payload, dict), "dataset must be an object")
    require(nonempty(payload.get("version")) and payload["version"].startswith("investigation-dataset-"),
            "invalid dataset version")
    require(type(payload.get("seed")) is int, "seed must be an integer")
    limits = payload["limits"]
    require(isinstance(limits, dict), "limits must be an object")
    require(type(limits.get("maxToolCalls")) is int and 1 <= limits["maxToolCalls"] <= 16,
            "maxToolCalls must be an integer in 1..16")
    require(type(limits.get("timeoutMs")) is int and limits["timeoutMs"] >= 1000,
            "timeoutMs must be an integer >= 1000")
    for name in ("maxEvidence", "maxRelatedEvents"):
        require(type(limits.get(name)) is int and limits[name] > 0, f"{name} must be a positive integer")
    cases = payload["cases"]
    require(isinstance(cases, list) and len(cases) >= 30, "at least 30 cases are required")
    ids = set()
    injection_cases = 0
    for case in cases:
        require(isinstance(case, dict), "case must be an object")
        require(nonempty(case.get("id")) and case["id"] not in ids, "case IDs must be nonempty and unique")
        ids.add(case["id"])
        require(nonempty(case.get("alertId")), f"{case['id']}: alertId is required")
        alert = case["alert"]
        require(isinstance(alert, dict) and all(nonempty(alert.get(key)) for key in (
            "ruleId", "entity", "occurredAt", "message")), f"{case['id']}: incomplete alert fixture")
        evidence = case["evidence"]
        require(isinstance(evidence, list) and 0 < len(evidence) <= limits["maxEvidence"],
                f"{case['id']}: evidence must fit maxEvidence")
        event_ids = set()
        for event in evidence:
            require(isinstance(event, dict) and all(nonempty(event.get(key)) for key in (
                "eventId", "timestamp", "raw")), f"{case['id']}: incomplete evidence fixture")
            require(event["eventId"] not in event_ids, f"{case['id']}: duplicate evidence identity")
            event_ids.add(event["eventId"])
        for source in [alert, *evidence]:
            require(string_list(source.get("iocs", [])), f"{case['id']}: iocs must be strings")
        expected = case["expected"]
        require(isinstance(expected, dict), f"{case['id']}: expected must be an object")
        prefixes = expected.get("requiredCitationPrefixes")
        require(string_list(prefixes) and {"alert:", "evidence:"} <= set(prefixes)
                and set(prefixes) <= {"alert:", "evidence:", "search:", "ioc:"},
                f"{case['id']}: invalid requiredCitationPrefixes")
        timeline = expected.get("requiredTimelineTypes")
        require(string_list(timeline) and {"ALERT", "EVIDENCE"} <= set(timeline)
                and set(timeline) <= {"ALERT", "EVIDENCE", "RELATED_EVENT"},
                f"{case['id']}: invalid requiredTimelineTypes")
        require(expected.get("requiresHumanApproval") is True, f"{case['id']}: human approval must be required")
        tags = case.get("tags", [])
        require(string_list(tags), f"{case['id']}: tags must be strings")
        if "prompt-injection-resistance" in tags:
            injection_cases += 1
    require(injection_cases >= 5, "at least 5 prompt-injection cases are required")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=DATASET)
    args = parser.parse_args()
    try:
        payload = json.loads(args.dataset.read_text(encoding="utf-8"))
        validate(payload)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        print(f"[FAIL] invalid investigation dataset: {failure}")
        return 1
    print(f"[PASS] {payload['version']} seed={payload['seed']} cases={len(payload['cases'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
