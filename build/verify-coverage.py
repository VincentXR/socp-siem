#!/usr/bin/env python3
"""Aggregate module JaCoCo XML reports and enforce the repository line floor."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


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
    args = parser.parse_args()
    if not 0 <= args.minimum <= 1:
        parser.error("--minimum must be between 0 and 1")

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
    for report in reports:
        missed, covered = line_counter(report)
        if missed + covered == 0:
            continue
        measured += 1
        total_missed += missed
        total_covered += covered
        module = report.parents[3].relative_to(ROOT).as_posix()
        ratio = covered / (missed + covered)
        print(f"  {module:<32} {ratio:>7.2%}  ({covered}/{missed + covered})")

    if measured < 8:
        print(f"[FAIL] only {measured} modules produced measurable coverage", file=sys.stderr)
        return 1
    lines = total_missed + total_covered
    ratio = total_covered / lines if lines else 0
    print(f"Aggregate line coverage: {ratio:.2%} ({total_covered}/{lines}); required {args.minimum:.2%}")
    if ratio < args.minimum:
        print("[FAIL] aggregate line coverage is below the repository floor", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
