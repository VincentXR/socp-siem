#!/usr/bin/env python3
"""Workbench frontend convention gate (drift guard).

Enforces two invariants that were fixed by hand in the 2026-09 holistic review
and that keep re-drifting once new code lands:

1. Sorting: every table that opts a column into server/client hand-off via
   `sortable="custom"` must actually take over the sort by binding
   `@sort-change`. A `sortable="custom"` with no handler is a dead sort affordance
   (the Assets/Endpoints regression in UX audit #12) — remove the attribute or
   add the handler.

2. Route-query sync: list-query composables that read `route.query` and write
   `router.replace({ query })` share a locked contract (see the must-read header
   in useListQuery.ts). A divergent copy re-introduces the 500-on-bad-page /
   alarmId-erase / echo-clobber bugs. Any file matching the sync pattern must
   keep all four guards.

Not registered in build/verify-repository.py — the consolidator wires it in once
the parallel review waves merge. It still exits non-zero on any violation.
"""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend" / "apps" / "workbench" / "src"
COMPOSABLES = SRC / "composables"

SORTABLE_CUSTOM = re.compile(r'sortable\s*=\s*["\']custom["\']')
SORT_CHANGE_BINDING = re.compile(r'@sort-change\s*=')

# A file becomes a "URL-synced list-query composable" when it both reads the
# live query and pushes an object-form replace. This deliberately excludes
# useWorkbenchRoute.ts (router.replace(path)) so only the list-query family is
# held to the contract.
SYNC_READ = re.compile(r'route\.query')
SYNC_WRITE = re.compile(r'\.replace\(\{\s*query')

REQUIRED_CONTRACT = {
    "integer page token (Number.isInteger)": re.compile(r'Number\.isInteger'),
    "nextTick echo reset": re.compile(r'nextTick'),
    "preserve unmanaged keys ({ ...route.query })": re.compile(r'\.\.\.route\.query'),
    "routeName scope guard": re.compile(r'route\.name\s*!=='),
}
FORBIDDEN_TOKEN = {
    "isFinite page guard leaks '2.5' to @RequestParam Integer -> 500": re.compile(r'Number\.isFinite'),
}


def vue_files():
    yield from sorted(SRC.rglob("*.vue"))


def composable_files():
    yield from sorted(COMPOSABLES.glob("*.ts"))


def line_of(content: str, index: int) -> int:
    return content.count("\n", 0, index) + 1


def check_sortable(errors: list[str]) -> int:
    checked = 0
    for path in vue_files():
        content = path.read_text(encoding="utf-8")
        match = SORTABLE_CUSTOM.search(content)
        if not match:
            continue
        checked += 1
        if not SORT_CHANGE_BINDING.search(content):
            rel = path.relative_to(ROOT).as_posix()
            ln = line_of(content, match.start())
            errors.append(
                f"{rel}:{ln}: sortable=\"custom\" without @sort-change handler"
                " (remove the attribute or bind the sort takeover)"
            )
    return checked


def check_composable_sync(errors: list[str]) -> int:
    checked = 0
    for path in composable_files():
        content = path.read_text(encoding="utf-8")
        if not (SYNC_READ.search(content) and SYNC_WRITE.search(content)):
            continue
        checked += 1
        rel = path.relative_to(ROOT).as_posix()
        for label, pattern in REQUIRED_CONTRACT.items():
            if not pattern.search(content):
                errors.append(f"{rel}: route-query sync composable missing contract guard: {label}")
        for label, pattern in FORBIDDEN_TOKEN.items():
            match = pattern.search(content)
            if match:
                ln = line_of(content, match.start())
                errors.append(f"{rel}:{ln}: route-query sync composable must not use isFinite — {label}")
    return checked


def main() -> int:
    errors: list[str] = []
    sortable_files = check_sortable(errors)
    sync_files = check_composable_sync(errors)
    if errors:
        print("Frontend conventions gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        "Frontend conventions gate passed:"
        f" {sortable_files} sortable-table file(s) with @sort-change;"
        f" {sync_files} route-query sync composable(s) holding the locked contract"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
