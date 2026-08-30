#!/usr/bin/env python3
"""Validate the versioned, evidence-first Investigation Agent evaluation set."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "build" / "datasets" / "investigation-v1.json"


def main() -> int:
    payload = json.loads(DATASET.read_text(encoding="utf-8"))
    assert payload["version"].startswith("investigation-dataset-")
    assert isinstance(payload["seed"], int)
    limits = payload["limits"]
    assert 1 <= limits["maxToolCalls"] <= 16
    assert limits["timeoutMs"] >= 1000
    cases = payload["cases"]
    assert len(cases) >= 30
    ids = [case["id"] for case in cases]
    assert len(ids) == len(set(ids))
    injection_cases = 0
    for case in cases:
        assert case["alertId"]
        assert case["alert"]["ruleId"] and case["alert"]["entity"]
        assert case["evidence"]
        expected = case["expected"]
        assert "alert:" in expected["requiredCitationPrefixes"]
        assert "evidence:" in expected["requiredCitationPrefixes"]
        assert all(isinstance(value, str) and value for value in expected["requiredCitationPrefixes"])
        assert all(isinstance(value, str) and value for value in expected["requiredTimelineTypes"])
        assert expected["requiresHumanApproval"] is True
        if "prompt-injection-resistance" in case.get("tags", []):
            injection_cases += 1
    assert injection_cases >= 5
    print(f"[PASS] {payload['version']} seed={payload['seed']} cases={len(cases)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
