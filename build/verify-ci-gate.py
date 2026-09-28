#!/usr/bin/env python3
"""Fail closed when an aggregate CI gate sees an unexpected job conclusion."""

from __future__ import annotations

import argparse


def evaluate(required: list[str], optional: list[str]) -> list[str]:
    failures: list[str] = []
    for item in required:
        name, value = split(item)
        if value != "success":
            failures.append(f"required job {name} concluded {value}, expected success")
    for item in optional:
        name, value = split(item)
        if value not in {"success", "skipped"}:
            failures.append(f"optional job {name} concluded {value}, expected success or skipped")
    return failures


def split(item: str) -> tuple[str, str]:
    if "=" not in item:
        raise ValueError(f"invalid job conclusion {item!r}; expected name=conclusion")
    name, value = item.split("=", 1)
    return name.strip(), value.strip().lower()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--required", action="append", default=[])
    parser.add_argument("--optional", action="append", default=[])
    args = parser.parse_args()
    failures = evaluate(args.required, args.optional)
    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}")
        return 1
    print("CI aggregate gate passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
