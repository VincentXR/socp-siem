#!/usr/bin/env python3
"""Fail fast on unsafe, misnamed, duplicated, rewritten, or incomplete Flyway migrations."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
VERSIONED = re.compile(r"^V(?P<version>[0-9]+(?:_[0-9]+)*)__[a-z0-9][a-z0-9_]*\.sql$")
REPEATABLE = re.compile(r"^R__[a-z0-9][a-z0-9_]*\.sql$")
TABLE = re.compile(r"@Table\s*\([^)]*name\s*=\s*\"([^\"]+)\"", re.DOTALL)
DESTRUCTIVE = re.compile(r"\b(?:DROP\s+(?:DATABASE|SCHEMA)|TRUNCATE\s+TABLE)\b", re.IGNORECASE)
# Any tracked SQL under a Flyway location, in any module and any location name
# (db/migration, db/secondary-analysis, ...).
TRACKED_MIGRATION = re.compile(r"(?:^|/)src/main/resources/db/[^/]+/[^/]+\.sql$")
REMEDIATION = (
    "A published version may be reverted to a blob it already shipped with, never edited: "
    "restore {path} to byte-identical text from a trusted published revision and deliver the "
    "intended statement in a new higher version."
)


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def git_lines(root: Path, *args: str) -> list[str]:
    """Non-empty stdout lines of a git command, or [] when git or the repo is unavailable."""
    try:
        output = subprocess.check_output(
            ["git", *args],
            cwd=root,
            text=True,
            encoding="utf-8",
            errors="replace",
            stderr=subprocess.DEVNULL,
        )
    except (OSError, subprocess.CalledProcessError):
        return []
    return [line for line in output.splitlines() if line]


def published_blobs(root: Path, path: str) -> set[str]:
    """Every blob this path already held in the history published on HEAD.

    Both each commit's own version and its parent version are collected so the
    pre-append content of a single rewriting commit is recognised as published.
    """
    revisions: list[str] = []
    for commit in git_lines(root, "log", "--format=%H", "HEAD", "--", path):
        revisions.extend((f"{commit}:{path}", f"{commit}^:{path}"))
    if not revisions:
        return set()
    try:
        resolved = subprocess.run(
            ["git", "cat-file", "--batch-check"],
            cwd=root,
            input="\n".join(revisions),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        ).stdout
    except OSError:
        return set()
    return {
        parts[0]
        for parts in (line.split() for line in resolved.splitlines())
        if len(parts) == 3 and parts[1] == "blob"
    }


def check_published_migrations(errors: list[str], root: Path = ROOT) -> list[str]:
    """Reject uncommitted rewrites of migration versions that already shipped.

    Rewriting an applied version changes the checksum stored in
    ``flyway_schema_history``, so every existing database fails validation while
    the edited statements never re-run there -- only new databases get the edited
    text and the two fleets silently diverge. Restoring a file byte-for-byte to
    one of its own published blobs is the permitted restore step (restore, then
    replay in a newer version), so it is reported rather than failed.
    New files are never listed by ``git diff HEAD`` and stay allowed.
    """
    if not git_lines(root, "rev-parse", "HEAD"):
        fail(errors, "cannot read git metadata: the published-migration immutability gate cannot run")
        return []
    restored: list[str] = []
    pending = git_lines(
        root, "diff", "--name-only", "--no-renames", "--diff-filter=MD", "HEAD", "--", "*.sql"
    )
    for path in pending:
        if not TRACKED_MIGRATION.search(path):
            continue
        if REPEATABLE.match(Path(path).name):
            # Repeatable migrations are re-applied precisely because their
            # checksum changes, so editing R__ files in place is the Flyway
            # design; only versioned history is immutable.
            continue
        local = root / path
        if not local.is_file():
            fail(
                errors,
                f"{path}: a published migration was deleted or renamed, which rewrites history. {REMEDIATION.format(path=path)}",
            )
            continue
        # hash-object applies .gitattributes, so the comparison is against the
        # exact bytes Flyway would checksum when the file is packaged.
        current = git_lines(root, "hash-object", "--", path)
        if current and current[0] in published_blobs(root, path):
            restored.append(path)
            continue
        fail(
            errors,
            f"{path}: published Flyway migrations are immutable; the file's text changed, so its "
            f"checksum no longer matches every database that already applied it. {REMEDIATION.format(path=path)}",
        )
    return restored


def audit_published_history(root: Path = ROOT) -> list[str]:
    """Every commit already published on HEAD that rewrote an existing migration.

    The default verdict inspects pending edits and cannot see a rewrite already
    committed to history. This inventory names each such commit so the owning
    module can restore the file and replay the statement in a new version. It is
    opt-in because existing repository history can contain known offenders.
    """
    lines = git_lines(
        root, "log", "--format=commit\t%h\t%s", "--no-renames", "--diff-filter=M",
        "--name-only", "HEAD", "--", "*.sql",
    )
    offenders: list[str] = []
    origin = ""
    for line in lines:
        # maxsplit keeps a subject that itself contains a tab intact.
        parts = line.split("\t", 2)
        if len(parts) == 3 and parts[0] == "commit":
            origin = f"{parts[1]} {parts[2]}"
        elif TRACKED_MIGRATION.search(line) and not REPEATABLE.match(Path(line).name):
            # Repeatable R__ migrations are meant to be edited; see the pending check.
            offenders.append(f"{origin} -> {line}")
    return offenders


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--published-history",
        action="store_true",
        help="audit committed rewrites of published migrations instead of the default gate",
    )
    arguments = parser.parse_args()
    if arguments.published_history:
        offenders = audit_published_history()
        if offenders:
            print(
                f"Published-migration history audit: {len(offenders)} committed rewrite(s) need a "
                "revert plus a newer-version replay:",
                file=sys.stderr,
            )
            for offender in offenders:
                print(f"  - {offender}", file=sys.stderr)
            return 1
        print("Published-migration history audit: no committed rewrite of a migration version")
        return 0

    errors: list[str] = []
    restored = check_published_migrations(errors)
    module_migrations: dict[Path, list[Path]] = {}
    files = 0
    # A bounded context may own more than one independent Flyway location.
    # Detection keeps secondary analysis in a separate database and therefore
    # stores its migrations under db/secondary-analysis rather than merging
    # them into the primary db/migration history.
    for migration_dir in sorted(ROOT.glob("services/*/src/main/resources/db/*")):
        if not migration_dir.is_dir():
            continue
        module = migration_dir.parents[4]
        if not any(path.is_file() for path in migration_dir.iterdir()):
            # Empty directories are not represented in Git. Ignore stale local
            # build directories so they cannot change the repository result.
            continue
        module_migrations.setdefault(module, []).append(migration_dir)

    for module, migration_dirs in sorted(module_migrations.items()):
        module_sql: list[str] = []
        for migration_dir in migration_dirs:
            migrations = sorted(path for path in migration_dir.iterdir() if path.is_file())
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
                        fail(errors, f"{module.name}/{migration_dir.name}: duplicate version {version}: {versions[version]} and {migration.name}")
                    versions[version] = migration.name

            major_versions = sorted(version[0] for version in versions if len(version) == 1)
            if major_versions and major_versions != list(range(1, max(major_versions) + 1)):
                fail(errors, f"{module.name}/{migration_dir.name}: non-consecutive major versions {major_versions}")
            module_sql.extend(combined_sql)

        pom = (module / "pom.xml").read_text(encoding="utf-8")
        app_yml = module / "src/main/resources/application.yml"
        if "flyway-core" not in pom:
            fail(errors, f"{module.name}: migrations exist but pom.xml has no flyway-core dependency")
        if not app_yml.exists() or "flyway:" not in app_yml.read_text(encoding="utf-8"):
            fail(errors, f"{module.name}: migrations exist but application.yml does not configure Flyway")

        all_sql = "\n".join(module_sql)
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

    modules = len(module_migrations)
    if modules < 8 or files < 20:
        fail(errors, f"unexpected migration inventory: {modules} modules / {files} files")
    if restored:
        # Flushed first: these pending restores are sanctioned remediation, and a
        # reviewer must be able to tell them apart from the failures below.
        print("Published migrations reverted to their original content, awaiting a newer-version replay:")
        for path in restored:
            print(f"  - {path}")
        sys.stdout.flush()
    if errors:
        print("Migration gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Migration gate passed: {files} migrations across {modules} modules")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
