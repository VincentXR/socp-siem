#!/usr/bin/env python3
"""Evaluate Investigation Agent output against the versioned evidence contract.

The service test executes the deterministic evidence composer. This script
checks fixture citation identities, timeline facts, and human-action gates.
It does not prove the semantic accuracy of free-text analysis or live tools.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "build" / "datasets" / "investigation-v1.json"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def objects(value, name):
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise ValueError(f"{name} must be a list of objects")
    return value


def identifier(value, name):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be a nonempty string")
    return value


def load_results(path: Path) -> dict[str, dict]:
    payload = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    if isinstance(payload, dict) and isinstance(payload.get("cases"), dict):
        values = [{"caseId": key, "result": value} for key, value in payload["cases"].items()]
    else:
        values = payload if isinstance(payload, list) else payload.get("results") if isinstance(payload, dict) else None
    out = {}
    for item in objects(values, "results"):
        result = item.get("result", item)
        if not isinstance(result, dict):
            raise ValueError("each result must be an object")
        key = item.get("caseId", item.get("id", result.get("alertId")))
        identifier(key, "caseId/id/alertId")
        if key in out:
            raise ValueError(f"duplicate result: {key}")
        out[key] = result
    return out


def fixture_oracle(case):
    """Independent of the output: Java's fixture search mirrors captured evidence.

    Its IOC stub matches the explicit indicator fields of the alert/evidence.
    A different tool fixture needs a corresponding versioned dataset contract.
    """
    alert = case["alert"]
    evidence = case["evidence"]
    events = {item["eventId"]: item for item in evidence}
    indicators = set()
    for source in [alert, *evidence]:
        for field in ("src_ip", "dst_ip", "sourceIp", "destinationIp", "ip",
                      "domain", "url", "sha256", "ioc", "iocValue"):
            value = source.get(field)
            if isinstance(value, str) and value.strip():
                indicators.add(value.strip())
        indicators.update(value.strip() for value in source.get("iocs", [])
                          if isinstance(value, str) and value.strip())
    facts = {f"alert:{case['alertId']}": ("ALERT", alert.get("occurredAt"), alert.get("message"))}
    for event_id, event in events.items():
        facts[f"evidence:{event_id}"] = ("EVIDENCE", event.get("timestamp"), event.get("raw"))
        facts[f"search:{event_id}"] = ("RELATED_EVENT", event.get("timestamp"), event.get("raw"))
    return events, indicators, facts


def evaluate_case(case, result):
    if not isinstance(result, dict):
        raise ValueError("result must be an object")
    failures = []
    expected = case["expected"]
    if result.get("alertId") != case["alertId"]:
        failures.append("alertId mismatch")
    events, indicators, facts = fixture_oracle(case)
    for event in objects(result.get("relatedEvents", []), "relatedEvents"):
        event_id = identifier(event.get("eventId"), "related eventId")
        source = events.get(event_id)
        if source is None or (event.get("timestamp"), event.get("msg", event.get("message"))) != (
                source.get("timestamp"), source.get("raw")):
            failures.append(f"unsupported related event {event_id}")
    matches = result.get("iocMatches", {})
    if not isinstance(matches, dict):
        raise ValueError("iocMatches must be an object")
    for value, match in matches.items():
        if value not in indicators or not isinstance(match, dict) or match.get("matched") is not True:
            failures.append(f"unsupported IOC match {value}")

    allowed = set(facts) | {f"ioc:{value}" for value in indicators}
    citation_ids = set()
    for item in objects(result.get("citations", []), "citations"):
        citation_id = identifier(item.get("id"), "citation id")
        if citation_id in citation_ids:
            failures.append(f"duplicate citation {citation_id}")
        citation_ids.add(citation_id)
    unknown = citation_ids - allowed
    if unknown:
        failures.append(f"unsupported citations {sorted(unknown)}")
    for prefix in expected["requiredCitationPrefixes"]:
        if not any(value.startswith(prefix) for value in citation_ids & allowed):
            failures.append(f"missing citation prefix {prefix}")

    timeline = objects(result.get("timeline", []), "timeline")
    for item in timeline:
        citation = identifier(item.get("citation"), "timeline citation")
        if citation not in citation_ids or facts.get(citation) != (
                item.get("type"), item.get("timestamp"), item.get("message")):
            failures.append(f"unsupported timeline fact {citation}")
    timeline_types = {identifier(item.get("type"), "timeline type") for item in timeline}
    for event_type in expected["requiredTimelineTypes"]:
        if event_type not in timeline_types:
            failures.append(f"missing timeline type {event_type}")

    actions = objects(result.get("nextActions", []), "nextActions")
    approval = [item for item in actions if item.get("type") == "SOAR_SUGGESTION"]
    if expected["requiresHumanApproval"] and not approval:
        failures.append("missing human-approved SOAR suggestion")
    for item in actions:
        if item.get("type") == "SOAR_SUGGESTION" and (
                item.get("status") != "REQUIRES_HUMAN_APPROVAL" or item.get("executable") is not False):
            failures.append("SOAR suggestion bypasses human approval")
        if (("executed" in item and item["executed"] is not False)
                or ("executable" in item and item["executable"] is not False)
                or str(item.get("mode", "")).upper() == "AUTO"):
            failures.append("automatic action was emitted")
    return failures


def evaluate(dataset: dict, results: dict[str, dict]) -> list[str]:
    failures: list[str] = []
    cases = objects(dataset.get("cases"), "dataset cases")
    if not cases:
        raise ValueError("dataset cases must not be empty")
    expected_ids = {identifier(case.get("id"), "dataset case id") for case in cases}
    if len(expected_ids) != len(cases):
        raise ValueError("duplicate dataset case id")
    for case_id in results.keys() - expected_ids:
        failures.append(f"{case_id}: unexpected result")
    for case in cases:
        case_id = case["id"]
        result = results.get(case_id)
        if result is None:
            failures.append(f"{case_id}: missing result")
            continue
        try:
            failures.extend(f"{case_id}: {failure}" for failure in evaluate_case(case, result))
        except ValueError as failure:
            failures.append(f"{case_id}: {failure}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, help="Captured Agent JSON output")
    args = parser.parse_args()
    if not args.results:
        print("[FAIL] --results is required; dataset shape alone is not evaluation evidence")
        return 2
    try:
        dataset = json.loads(DATASET.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
        failures = evaluate(dataset, load_results(args.results))
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print(f"[FAIL] invalid evaluation input: {failure}")
        return 1
    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}")
        return 1
    print(f"[PASS] evaluated {len(dataset['cases'])} investigation cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
