#!/usr/bin/env python3
"""Aggregate module JaCoCo XML reports and enforce repository/module line floors."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]

# A repository total can hide an untested service behind highly-covered shared
# modules. Keep a modest floor for every module and a stronger floor for the
# event-path and authorization modules. Both thresholds are deliberately
# configurable so they can be raised without changing the checker.
DEFAULT_CRITICAL_MODULES = (
    "platform/socp-auth",
    "platform/socp-tenant",
    "platform/socp-ratelimit",
    "platform/socp-rule",
    "services/alert-web",
    "services/detect-web",
    "services/search-config",
)


def line_counter(report: Path) -> tuple[int, int]:
    root = ET.parse(report).getroot()
    counter = next((item for item in root.findall("counter") if item.get("type") == "LINE"), None)
    if counter is None:
        return 0, 0
    return int(counter.get("missed", "0")), int(counter.get("covered", "0"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--minimum",
        type=float,
        default=float(os.environ.get("SOCP_MIN_LINE_COVERAGE", "0.45")),
        help="minimum aggregate line ratio (default: 0.45)",
    )
    parser.add_argument(
        "--module-minimum",
        type=float,
        default=float(os.environ.get("SOCP_MIN_MODULE_LINE_COVERAGE", "0.15")),
        help="minimum line ratio required from every production module (default: 0.15)",
    )
    parser.add_argument(
        "--critical-module-minimum",
        type=float,
        default=float(os.environ.get("SOCP_MIN_CRITICAL_MODULE_LINE_COVERAGE", "0.30")),
        help="minimum line ratio required from critical modules (default: 0.30)",
    )
    parser.add_argument(
        "--critical-modules",
        default=os.environ.get("SOCP_CRITICAL_COVERAGE_MODULES", ",".join(DEFAULT_CRITICAL_MODULES)),
        help="comma-separated module paths which use the critical module floor",
    )
    args = parser.parse_args()
    for option in ("minimum", "module_minimum", "critical_module_minimum"):
        if not 0 <= getattr(args, option) <= 1:
            parser.error(f"--{option.replace('_', '-')} must be between 0 and 1")
    critical_modules = {
        item.strip().strip("/") for item in args.critical_modules.split(",") if item.strip()
    }

    reports = sorted(ROOT.glob("platform/*/target/site/jacoco/jacoco.xml"))
    reports += sorted(ROOT.glob("services/*/target/site/jacoco/jacoco.xml"))
    if not reports:
        print("[FAIL] no JaCoCo XML reports found; run Maven tests with -Pcoverage", file=sys.stderr)
        return 1

    report_modules = {report.parents[3] for report in reports}
    production_modules = {
        ROOT.joinpath(*source.relative_to(ROOT).parts[:2])
        for source in list(ROOT.glob("platform/*/src/main/java/**/*.java"))
        + list(ROOT.glob("services/*/src/main/java/**/*.java"))
    }
    missing_reports = sorted(production_modules - report_modules)
    if missing_reports:
        names = ", ".join(module.relative_to(ROOT).as_posix() for module in missing_reports)
        print(f"[FAIL] production modules without coverage reports: {names}", file=sys.stderr)
        return 1

    total_missed = 0
    total_covered = 0
    measured = 0
    module_failures: list[str] = []
    for report in reports:
        missed, covered = line_counter(report)
        if missed + covered == 0:
            continue
        measured += 1
        total_missed += missed
        total_covered += covered
        module = report.parents[3].relative_to(ROOT).as_posix()
        ratio = covered / (missed + covered)
        required = args.critical_module_minimum if module in critical_modules else args.module_minimum
        print(f"  {module:<32} {ratio:>7.2%}  ({covered}/{missed + covered}; required {required:.2%})")
        if ratio < required:
            module_failures.append(f"{module}={ratio:.2%} < {required:.2%}")

    if measured < 8:
        print(f"[FAIL] only {measured} modules produced measurable coverage", file=sys.stderr)
        return 1
    lines = total_missed + total_covered
    ratio = total_covered / lines if lines else 0
    print(f"Aggregate line coverage: {ratio:.2%} ({total_covered}/{lines}); required {args.minimum:.2%}")
    if ratio < args.minimum:
        print("[FAIL] aggregate line coverage is below the repository floor", file=sys.stderr)
        return 1
    if module_failures:
        print("[FAIL] module line coverage is below its floor: " + ", ".join(module_failures),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
