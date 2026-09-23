#!/usr/bin/env python3
"""Check that each Element Plus table with static custom sorting binds its handler.

This is a template wiring check, not proof that the handler sorts correctly.
URL-query behavior is exercised by scripts/useListQuery.test.ts and
scripts/useAlarmQuery.component.test.ts under the workbench `pnpm test` gate.
Source-token presence cannot prove those runtime contracts.
"""

from html.parser import HTMLParser
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "frontend" / "apps" / "workbench" / "src"
TABLE_TAGS = {"el-table", "eltable"}
COLUMN_TAGS = {"el-table-column", "eltablecolumn"}


class Table:
    def __init__(self, has_handler: bool):
        self.has_handler = has_handler
        self.has_custom_sort = False


class SortableParser(HTMLParser):
    def __init__(self, path: Path, errors: list[str]):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.errors = errors
        self.tables: list[Table] = []
        self.checked = 0

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag in TABLE_TAGS:
            has_handler = any(
                name.split(".", 1)[0] in {"@sort-change", "v-on:sort-change"}
                and value is not None and bool(value.strip())
                for name, value in attributes
            )
            self.tables.append(Table(has_handler))
        elif tag in COLUMN_TAGS:
            custom = attrs.get("sortable") == "custom" or any(
                (attrs.get(name) or "").strip() in {"'custom'", '"custom"'}
                for name in (":sortable", "v-bind:sortable")
            )
            if not custom:
                return
            table = self.tables[-1] if self.tables else None
            if table is not None and not table.has_custom_sort:
                table.has_custom_sort = True
                self.checked += 1
            if table is None or not table.has_handler:
                self.errors.append(
                    f'{self.path}:{self.getpos()[0]}: sortable="custom" without '
                    "a sort-change handler on its owning table"
                    " (remove the attribute or bind the sort takeover)"
                )

    def handle_endtag(self, tag):
        if tag in TABLE_TAGS and self.tables:
            self.tables.pop()


def check_sortable(errors: list[str]) -> int:
    checked = 0
    for path in sorted(SRC.rglob("*.vue")):
        parser = SortableParser(path.relative_to(ROOT), errors)
        parser.feed(path.read_text(encoding="utf-8"))
        parser.close()
        checked += parser.checked
    return checked


def main() -> int:
    errors: list[str] = []
    tables = check_sortable(errors)
    if errors:
        print("Frontend conventions gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Frontend conventions gate passed: {tables} custom-sort table(s) with their own handler")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
