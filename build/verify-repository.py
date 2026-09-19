#!/usr/bin/env python3
"""Run repository-level static contracts from one cross-platform manifest.

Maven, frontend, browser, and live-service checks keep their own lifecycle in
the caller.  This command owns the deterministic Python checks that must stay
identical across the Bash quality gate, PowerShell quality gate, and Change CI.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import subprocess
import sys
from typing import Sequence


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Check:
    name: str
    arguments: tuple[str, ...]
    requires_helm: bool = False


CHECKS: tuple[Check, ...] = (
    Check("migration contracts", ("build/verify-migrations.py",)),
    Check("service and API contracts", ("build/verify-contracts.py",)),
    Check("middleware image catalog", ("build/verify-middleware-images.py",)),
    Check("package layout", ("build/verify-package-layout.py",)),
    Check("architecture boundaries", ("build/verify-architecture.py",)),
    Check("source style debt", ("build/verify-style.py",)),
    Check("runtime consolidation policy", ("build/verify-runtime-consolidation.py",)),
    Check("frontend i18n", ("build/verify-frontend-i18n.py",)),
    Check("canonical event schema", ("build/verify-event-schema.py",)),
    Check("production deployment contract", ("build/verify-production.py",)),
    Check("production Compose contract", ("build/verify-prod-compose.py",)),
    Check("Helm release contract", ("build/verify-helm.py",), requires_helm=True),
    Check("detection content", ("build/validate-detection-content.py",)),
    Check("detection README summary", ("build/generate-detection-summary.py", "--check-readme")),
    Check("investigation dataset", ("build/verify-investigation-dataset.py",)),
    Check(
        "investigation evaluation",
        (
            "build/eval-investigation.py",
            "--results",
            "services/ai-assistant/target/investigation-eval-results.json",
        ),
    ),
    Check(
        "build script unit tests",
        ("-m", "unittest", "discover", "-s", "build/tests", "-p", "test_*.py"),
    ),
)


def command_for(check: Check, evidence_dir: Path | None = None) -> list[str]:
    arguments = list(check.arguments)
    if check.name == "detection README summary" and evidence_dir is not None:
        arguments.extend(("--json", str(evidence_dir / "detection-summary.json")))
    return [sys.executable, *arguments]


def selected_checks(skip_helm: bool) -> tuple[Check, ...]:
    return tuple(check for check in CHECKS if not (skip_helm and check.requires_helm))


def run_checks(checks: Sequence[Check], evidence_dir: Path | None = None) -> int:
    for index, check in enumerate(checks, start=1):
        print(f"[repository-check {index}/{len(checks)}] {check.name}", flush=True)
        result = subprocess.run(command_for(check, evidence_dir), cwd=ROOT, check=False)
        if result.returncode != 0:
            print(f"[FAIL] {check.name} exited with {result.returncode}", file=sys.stderr)
            return result.returncode
    print(f"Repository contracts passed: {len(checks)} checks")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--skip-helm",
        action="store_true",
        help="skip the Helm-dependent render check when Helm 4 is unavailable",
    )
    parser.add_argument(
        "--evidence-dir",
        type=Path,
        help="write optional machine-readable check evidence under this directory",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="print the ordered check manifest without running it",
    )
    args = parser.parse_args()
    checks = selected_checks(args.skip_helm)
    if args.list:
        for check in checks:
            print(check.name)
        return 0
    return run_checks(checks, args.evidence_dir)


if __name__ == "__main__":
    raise SystemExit(main())
