#!/usr/bin/env python3
"""Reject memory exhaustion in this run's live-service logs, even after recovery."""
import argparse
from pathlib import Path
import re


MEMORY_FAILURE = re.compile(
    r"java\.lang\.OutOfMemoryError|io\.netty\.util\.internal\.OutOfDirectMemoryError"
    r"|There is insufficient memory for the Java Runtime Environment")
LOG_PATTERNS = ("*.log", "prod-boot/*.log", "detection-cluster/*.log", "chaos/**/detect-*.log")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(".cache"))
    root = parser.parse_args(argv).root
    logs = sorted({path for pattern in LOG_PATTERNS for path in root.glob(pattern) if path.is_file()})
    if not logs:
        print("[FAIL] No live-service log evidence found")
        return 1
    failed = False
    for path in logs:
        try:
            with path.open(encoding="utf-8", errors="replace") as stream:
                for number, line in enumerate(stream, 1):
                    if MEMORY_FAILURE.search(line):
                        # Report the location, not raw log text that may contain credentials.
                        print(f"[FAIL] JVM memory exhaustion: {path.relative_to(root)}:{number}")
                        failed = True
        except OSError:
            print(f"[FAIL] Cannot read live-service log: {path.relative_to(root)}")
            failed = True
    print(f"Inspected {len(logs)} live-service log files")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
