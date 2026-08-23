#!/usr/bin/env python3
"""Fail fast on unsafe, misnamed, duplicated, or incomplete Flyway migrations."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
VERSIONED = re.compile(r"^V(?P<version>[0-9]+(?:_[0-9]+)*)__[a-z0-9][a-z0-9_]*\.sql$")
REPEATABLE = re.compile(r"^R__[a-z0-9][a-z0-9_]*\.sql$")
TABLE = re.compile(r"@Table\s*\([^)]*name\s*=\s*\"([^\"]+)\"", re.DOTALL)
DESTRUCTIVE = re.compile(r"\b(?:DROP\s+(?:DATABASE|SCHEMA)|TRUNCATE\s+TABLE)\b", re.IGNORECASE)


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def main() -> int:
    errors: list[str] = []
    modules = 0
    files = 0
    for migration_dir in sorted(ROOT.glob("services/*/src/main/resources/db/migration")):
        module = migration_dir.parents[4]
        migrations = sorted(path for path in migration_dir.iterdir() if path.is_file())
        if not migrations:
            fail(errors, f"{module.name}: empty migration directory")
            continue
        modules += 1
        versions: dict[tuple[int, ...], str] = {}
        combined_sql: list[str] = []
        for migration in migrations:
            match = VERSIONED.fullmatch(migration.name)
            if not match and not REPEATABLE.fullmatch(migration.name):
                fail(errors, f"{migration.relative_to(ROOT)}: invalid Flyway filename")
                continue
            sql = migration.read_text(encoding="utf-8")
            files += 1
            if len(sql.strip()) < 20:
                fail(errors, f"{migration.relative_to(ROOT)}: migration is empty or trivial")
            if DESTRUCTIVE.search(sql) and "SOCP-MIGRATION-ALLOW-DESTRUCTIVE" not in sql:
                fail(errors, f"{migration.relative_to(ROOT)}: destructive statement requires an explicit marker")
            combined_sql.append(sql.lower())
            if match:
                version = tuple(int(part) for part in match.group("version").split("_"))
                if version in versions:
                    fail(errors, f"{module.name}: duplicate version {version}: {versions[version]} and {migration.name}")
                versions[version] = migration.name

        major_versions = sorted(version[0] for version in versions if len(version) == 1)
        if major_versions and major_versions != list(range(1, max(major_versions) + 1)):
            fail(errors, f"{module.name}: non-consecutive major versions {major_versions}")

        pom = (module / "pom.xml").read_text(encoding="utf-8")
        app_yml = module / "src/main/resources/application.yml"
        if "flyway-core" not in pom:
            fail(errors, f"{module.name}: migrations exist but pom.xml has no flyway-core dependency")
        if not app_yml.exists() or "flyway:" not in app_yml.read_text(encoding="utf-8"):
            fail(errors, f"{module.name}: migrations exist but application.yml does not configure Flyway")

        all_sql = "\n".join(combined_sql)
        for entity in module.glob("src/main/java/**/*.java"):
            source = entity.read_text(encoding="utf-8")
            table = TABLE.search(source)
            if not table:
                continue
            table_name = table.group(1).lower()
            if table_name not in all_sql:
                fail(errors, f"{entity.relative_to(ROOT)}: table {table_name} is absent from migrations")
            if "tenantId" in source and "tenant_id" not in all_sql:
                fail(errors, f"{entity.relative_to(ROOT)}: tenant entity has no tenant_id migration")

    if modules < 8 or files < 20:
        fail(errors, f"unexpected migration inventory: {modules} modules / {files} files")
    if errors:
        print("Migration gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Migration gate passed: {files} migrations across {modules} modules")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
