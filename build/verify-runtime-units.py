#!/usr/bin/env python3
"""Validate the six-unit deployment contract and optional promotion evidence.

The repository can prove the target assignment statically, but it cannot prove
that several Spring applications are safe to merge into one process.  Release
promotion therefore requires an evidence manifest produced by an environment
that actually boots the aggregate applications and exercises context,
transaction, failure-isolation, and capacity checks.  The default mode is the
non-mutating topology check used by ordinary CI; ``--require-evidence`` is the
explicit release gate.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from runtime_topology import load_topology, validate_registry, validate_topology, current_registry


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVIDENCE = ROOT / ".cache" / "runtime-units" / "evidence.json"
REQUIRED_CHECKS = ("context", "transaction", "failure", "capacity")


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


def evidence_errors(path: Path, topology: dict) -> list[str]:
    errors: list[str] = []
    if not path.is_file():
        return [f"missing aggregate runtime-unit evidence: {path}"]
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"invalid aggregate runtime-unit evidence {path}: {error}"]
    if not isinstance(evidence, dict) or evidence.get("schemaVersion") != 1:
        errors.append("aggregate runtime-unit evidence schemaVersion must be 1")
    if not isinstance(evidence, dict) or evidence.get("status") != "passed":
        errors.append("aggregate runtime-unit evidence status must be passed")
    expected_commit = current_commit()
    reported_commit = evidence.get("commit") if isinstance(evidence, dict) else None
    if expected_commit and reported_commit != expected_commit:
        errors.append(
            f"aggregate runtime-unit evidence commit {reported_commit!r} does not match {expected_commit}"
        )
    units = evidence.get("units") if isinstance(evidence, dict) else None
    expected_units = {
        unit.get("name") for unit in topology.get("units", [])
        if isinstance(unit, dict) and isinstance(unit.get("name"), str)
    }
    if not isinstance(units, dict):
        errors.append("aggregate runtime-unit evidence must contain a units object")
        return errors
    if set(units) != expected_units:
        errors.append(
            f"aggregate runtime-unit evidence units drift: expected={sorted(expected_units)} "
            f"actual={sorted(units)}"
        )
    for name in sorted(expected_units):
        checks = units.get(name)
        if not isinstance(checks, dict):
            errors.append(f"aggregate runtime-unit evidence missing checks for {name}")
            continue
        missing = [check for check in REQUIRED_CHECKS if checks.get(check) is not True]
        if missing:
            errors.append(f"aggregate runtime-unit {name} failed checks: {', '.join(missing)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--require-evidence",
        action="store_true",
        help="fail unless a passed, commit-matched aggregate evidence manifest exists",
    )
    parser.add_argument(
        "--evidence",
        type=Path,
        default=Path(os.environ.get("SOCP_RUNTIME_UNITS_EVIDENCE", DEFAULT_EVIDENCE)),
    )
    args = parser.parse_args()

    errors = topology_errors()
    topology = load_topology()
    status = topology.get("status")
    allowed_statuses = {
        "contract-only-until-aggregate-apps-pass-integration-tests",
        "validated",
    }
    if status not in allowed_statuses:
        errors.append(f"unexpected runtime topology promotion status: {status!r}")
    if args.require_evidence and status != "validated":
        errors.append("runtime topology status must be validated before promotion")
    if args.require_evidence:
        errors.extend(evidence_errors(args.evidence, topology))

    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    if args.require_evidence:
        print("Aggregate runtime-unit promotion evidence passed")
    else:
        print(
            "Runtime-unit assignment passed; six-unit promotion remains gated "
            "on aggregate application evidence"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
