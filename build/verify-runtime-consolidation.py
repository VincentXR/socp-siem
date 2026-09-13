#!/usr/bin/env python3
"""Validate evidence-gated runtime consolidation without a fixed process target."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from runtime_topology import load_topology, validate_registry, validate_topology, current_registry


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVIDENCE_ROOT = ROOT / ".cache" / "runtime-consolidation"


def topology_errors() -> list[str]:
    modules, services = current_registry()
    return validate_registry(modules, services) + validate_topology(
        load_topology(), modules, services
    )


def current_commit() -> str | None:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def candidate(topology: dict, name: str) -> dict | None:
    for entry in topology.get("consolidationCandidates", []):
        if isinstance(entry, dict) and entry.get("name") == name:
            return entry
    return None


def evidence_errors(path: Path, topology: dict, candidate_name: str) -> list[str]:
    errors: list[str] = []
    expected = candidate(topology, candidate_name)
    if expected is None:
        return [f"unknown consolidation candidate: {candidate_name}"]
    if not path.is_file():
        return [f"missing consolidation evidence: {path}"]
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"invalid consolidation evidence {path}: {error}"]
    if not isinstance(evidence, dict) or evidence.get("schemaVersion") != 1:
        errors.append("consolidation evidence schemaVersion must be 1")
    if not isinstance(evidence, dict) or evidence.get("status") != "passed":
        errors.append("consolidation evidence status must be passed")
    if isinstance(evidence, dict) and evidence.get("candidate") != candidate_name:
        errors.append("consolidation evidence candidate does not match the requested candidate")
    expected_commit = current_commit()
    reported_commit = evidence.get("commit") if isinstance(evidence, dict) else None
    if expected_commit and reported_commit != expected_commit:
        errors.append(
            f"consolidation evidence commit {reported_commit!r} does not match {expected_commit}"
        )
    members = evidence.get("members") if isinstance(evidence, dict) else None
    if members != expected.get("members"):
        errors.append(
            f"consolidation evidence members drift: expected={expected.get('members')} actual={members}"
        )
    checks = evidence.get("checks") if isinstance(evidence, dict) else None
    if not isinstance(checks, dict):
        errors.append("consolidation evidence must contain a checks object")
        return errors
    required_checks = topology.get("deploymentPolicy", {}).get("requiredChecks", [])
    missing = [check for check in required_checks if checks.get(check) is not True]
    if missing:
        errors.append(f"consolidation candidate {candidate_name} failed checks: {', '.join(missing)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", help="registered consolidation candidate name")
    parser.add_argument(
        "--require-evidence",
        action="store_true",
        help="require commit-matched evidence for one explicitly selected candidate",
    )
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()

    errors = topology_errors()
    topology = load_topology()
    if args.require_evidence and not args.candidate:
        errors.append("--require-evidence requires --candidate")
    selected_candidate = candidate(topology, args.candidate) if args.candidate else None
    if args.candidate and selected_candidate is None:
        errors.append(f"unknown consolidation candidate: {args.candidate}")
    if args.evidence and not args.require_evidence:
        errors.append("--evidence requires --require-evidence")
    if args.require_evidence and args.candidate and selected_candidate is not None:
        default_path = DEFAULT_EVIDENCE_ROOT / args.candidate / "evidence.json"
        evidence_path = args.evidence or Path(
            os.environ.get("SOCP_RUNTIME_CONSOLIDATION_EVIDENCE", default_path)
        )
        errors.extend(evidence_errors(evidence_path, topology, args.candidate))

    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    if args.require_evidence:
        print(f"Runtime consolidation evidence passed: {args.candidate}")
    else:
        print("Runtime consolidation policy passed; no fixed process-count target is configured")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
