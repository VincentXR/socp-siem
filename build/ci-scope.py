#!/usr/bin/env python3
"""Classify whether a change can affect executable full-stack behavior."""

from __future__ import annotations

import argparse
from pathlib import Path


RUNTIME_PREFIXES = (
    "platform/",
    "services/",
    "frontend/",
    "infra/",
    "deploy/",
    "agents/",
    "vector/",
    "build/",
    "config/",
    ".github/workflows/",
    ".mvn/",
)
RUNTIME_FILES = {"pom.xml", "mvnw", "mvnw.cmd", "docker-compose.yml"}


def requires_full_stack(paths: list[str]) -> bool:
    for value in paths:
        path = value.strip().replace("\\", "/")
        if path.startswith("./"):
            path = path[2:]
        if not path:
            continue
        if path in RUNTIME_FILES or path.startswith("docker-compose"):
            return True
        if path.startswith(RUNTIME_PREFIXES):
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paths-file", type=Path, required=True)
    args = parser.parse_args()
    paths = args.paths_file.read_text(encoding="utf-8").splitlines()
    print("true" if requires_full_stack(paths) else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
