#!/usr/bin/env python3
"""Enforce JaCoCo line coverage on executable Java lines changed from a base."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def diff_lines(base: str | None) -> dict[str, set[int]]:
    command = ["git", "diff", "--unified=0"]
    if base:
        command.append(f"{base}...HEAD")
    else:
        command.append("HEAD")
    command.extend(["--", "platform/*/src/main/java/**/*.java", "services/*/src/main/java/**/*.java"])
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "git diff failed")
    changed: dict[str, set[int]] = {}
    current: str | None = None
    for line in result.stdout.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            changed.setdefault(current, set())
            continue
        match = HUNK.match(line)
        if match and current:
            start = int(match.group(1))
            count = int(match.group(2) or "1")
            changed[current].update(range(start, start + count))
    if not base:
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=ROOT, text=True, capture_output=True, check=True).stdout.splitlines()
        java_path = re.compile(r"^(?:platform|services)/[^/]+/src/main/java/.+\.java$")
        for relative in untracked:
            normalized = relative.replace("\\", "/")
            if java_path.match(normalized):
                line_count = len((ROOT / relative).read_text(encoding="utf-8").splitlines())
                changed[normalized] = set(range(1, line_count + 1))
    return changed


def jacoco_lines() -> dict[str, dict[int, bool]]:
    coverage: dict[str, dict[int, bool]] = {}
    reports = sorted(ROOT.glob("platform/*/target/site/jacoco/jacoco.xml"))
    reports += sorted(ROOT.glob("services/*/target/site/jacoco/jacoco.xml"))
    for report in reports:
        module = report.parents[3]
        root = ET.parse(report).getroot()
        for package in root.findall("package"):
            package_path = package.get("name", "")
            for source in package.findall("sourcefile"):
                path = module / "src/main/java" / package_path / source.get("name", "")
                key = path.relative_to(ROOT).as_posix()
                coverage[key] = {
                    int(line.get("nr", "0")): int(line.get("ci", "0")) > 0
                    for line in source.findall("line")
                }
    return coverage


def valid_base(candidate: str | None) -> str | None:
    if not candidate or set(candidate) == {"0"}:
        return None
    result = subprocess.run(["git", "cat-file", "-e", f"{candidate}^{{commit}}"],
                            cwd=ROOT, capture_output=True)
    return candidate if result.returncode == 0 else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("SOCP_COVERAGE_BASE"))
    parser.add_argument("--minimum", type=float,
                        default=float(os.environ.get("SOCP_MIN_CHANGED_LINE_COVERAGE", "0.80")))
    args = parser.parse_args()
    if not 0 <= args.minimum <= 1:
        parser.error("--minimum must be between 0 and 1")
    base = valid_base(args.base)
    if args.base and not base:
        print(f"[SKIP] changed-line coverage base is unavailable: {args.base}")
        return 0
    changed = diff_lines(base)
    reports = jacoco_lines()
    executable = 0
    covered = 0
    missed: list[str] = []
    for path, lines in changed.items():
        measured = reports.get(path, {})
        for number in sorted(lines & measured.keys()):
            executable += 1
            if measured[number]:
                covered += 1
            else:
                missed.append(f"{path}:{number}")
    if executable == 0:
        print("Changed-line coverage: no changed executable Java lines")
        return 0
    ratio = covered / executable
    print(f"Changed executable line coverage: {ratio:.2%} ({covered}/{executable}); "
          f"required {args.minimum:.2%}")
    if ratio < args.minimum:
        for item in missed[:40]:
            print(f"  missed {item}", file=sys.stderr)
        if len(missed) > 40:
            print(f"  ... and {len(missed) - 40} more", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
