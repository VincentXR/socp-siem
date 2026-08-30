#!/usr/bin/env python3
"""Keep import debt from growing while the repository is cleaned incrementally."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
BASELINE_FILE = ROOT / "build" / "wildcard-import-baseline.txt"
WILDCARD = re.compile(r"^\s*import\s+[A-Za-z0-9_.]+\.\*;\s*$")


def java_files():
    for top in (ROOT / "platform", ROOT / "services"):
        yield from top.rglob("*.java")


def count_wildcards():
    total = 0
    files = []
    for path in java_files():
        matches = sum(1 for line in path.read_text(encoding="utf-8").splitlines()
                      if WILDCARD.match(line))
        if matches:
            total += matches
            files.append((path.relative_to(ROOT).as_posix(), matches))
    return total, files


def main():
    baseline = int(BASELINE_FILE.read_text(encoding="utf-8").strip())
    total, files = count_wildcards()
    if total != baseline:
        direction = "increased" if total > baseline else "decreased without updating the baseline"
        print(f"wildcard imports {direction}: actual={total}, baseline={baseline}", file=sys.stderr)
        return 1
    print(f"wildcard imports={total}; baseline is exact")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
