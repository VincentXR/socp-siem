#!/usr/bin/env python3
"""Static contract for the PostgreSQL backup/restore and DLQ redrive toolchain.

The gate requires the operational tools, their runbook registrations, and a
single database roster that cannot silently fall behind service databases:

* ``build/backup-postgres.sh`` / ``backup-postgres-all.sh`` / ``restore-postgres.sh``
  and ``build/replay-dlq.py`` are present and non-empty;
* ``build/postgres-databases.txt`` equals the ``CREATE DATABASE`` set in
  ``infra/init-sql/pg/01_databases.sql`` and the hard-coded array in
  ``infra/init-sql/pg/02_runtime_grants.sh`` (so "one consistency backup" cannot
  quietly under-scope);
* ``restore-postgres.sh`` re-applies and verifies tenant RLS;
* each tool is referenced by the runbook that documents it.

The scripts themselves need a live PostgreSQL/Kafka to execute; this gate checks
their existence, wiring, and roster agreement only, so it runs everywhere.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
OPS = ROOT / "docs" / "operations"

REQUIRED_TOOLS = (
    "backup-postgres.sh",
    "backup-postgres-all.sh",
    "restore-postgres.sh",
    "apply-tenant-rls.sh",
    "apply-postgres-roles.sh",
    "replay-dlq.py",
)


def errors_for_roster() -> list[str]:
    errors: list[str] = []
    roster_path = BUILD / "postgres-databases.txt"
    if not roster_path.is_file():
        return [f"missing {roster_path.relative_to(ROOT)}"]
    roster = [
        line.split("#", 1)[0].strip()
        for line in roster_path.read_text(encoding="utf-8").splitlines()
    ]
    roster = [name for name in roster if name]

    create_sql = ROOT / "infra/init-sql/pg/01_databases.sql"
    grants = ROOT / "infra/init-sql/pg/02_runtime_grants.sh"
    if not create_sql.is_file() or not grants.is_file():
        return [f"missing {create_sql.relative_to(ROOT)} or {grants.relative_to(ROOT)}"]

    created = re.findall(r"(?im)^\s*CREATE DATABASE\s+([a-z_][a-z0-9_]*)\s*;",
                         create_sql.read_text(encoding="utf-8"))
    array_line = re.search(r"(?m)^databases=\(([^)]*)\)", grants.read_text(encoding="utf-8"))
    grant_array = array_line.group(1).split() if array_line else []

    for label, other in (("01_databases.sql", created), ("02_runtime_grants.sh", grant_array)):
        if sorted(roster) != sorted(other):
            only_roster = sorted(set(roster) - set(other))
            only_other = sorted(set(other) - set(roster))
            errors.append(
                f"postgres-databases.txt roster drifted from {label}: "
                f"roster-only={only_roster} other-only={only_other}")
    if len(set(roster)) != len(roster):
        errors.append("postgres-databases.txt contains duplicate database names")
    return errors


def errors_for_restore_finalize() -> list[str]:
    text = (BUILD / "restore-postgres.sh").read_text(encoding="utf-8")
    errors: list[str] = []
    for needle, why in (
        ("apply-tenant-rls.sh", "finalize must re-apply tenant RLS after restore"),
        ("relrowsecurity", "finalize must assert FORCE ROW LEVEL SECURITY presence"),
        ("socp_tenant_isolation", "finalize must assert the isolation policy by name"),
    ):
        if needle not in text:
            errors.append(f"restore-postgres.sh: {why} (missing '{needle}')")
    return errors


def errors_for_doc_registration() -> list[str]:
    errors: list[str] = []
    backup_doc = OPS / "backup-restore.md"
    dlq_doc = OPS / "dlq-replay.md"
    if not backup_doc.is_file():
        errors.append("docs/operations/backup-restore.md is missing")
    else:
        text = backup_doc.read_text(encoding="utf-8")
        for needle in ("restore-postgres.sh", "backup-postgres-all.sh", "apply-tenant-rls",
                       "postgres-databases.txt"):
            if needle not in text:
                errors.append(f"backup-restore.md does not register {needle}")
    if not dlq_doc.is_file():
        errors.append("docs/operations/dlq-replay.md is missing")
    elif "replay-dlq.py" not in dlq_doc.read_text(encoding="utf-8"):
        errors.append("dlq-replay.md does not register replay-dlq.py")
    return errors


def errors_for_tools_present() -> list[str]:
    errors: list[str] = []
    for name in REQUIRED_TOOLS:
        path = BUILD / name
        if not path.is_file():
            errors.append(f"missing {path.relative_to(ROOT)}")
        elif path.stat().st_size == 0:
            errors.append(f"{path.relative_to(ROOT)} is empty")
    return errors


def main() -> int:
    errors: list[str] = []
    errors += errors_for_tools_present()
    errors += errors_for_roster()
    errors += errors_for_restore_finalize()
    errors += errors_for_doc_registration()
    if errors:
        print("Backup/DLQ toolchain contract failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Backup/DLQ toolchain contract passed: tools present, roster aligned, RLS finalize wired, docs registered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
