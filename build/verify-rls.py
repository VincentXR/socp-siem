#!/usr/bin/env python3
"""Require PostgreSQL RLS coverage or an explicit exemption for every table.

Layer 3 of the tenant-isolation contract (``infra/postgres/tenant-rls.sql``) is
applied outside Flyway. This gate replays every Flyway DDL location, maps
tenant-owned JPA entities to that schema, and requires an explicit decision for
every table:

* tenant-owned table (has ``tenant_id``) -> covered by the discovery rule of the policy
  script, so it must not be exempted;
* table without ``tenant_id`` -> must appear in ``build/tenant-rls-exemptions.txt`` with
  a reason, which is also the machine-readable register of "global shared, no RLS
  backstop" tables;
* entity that maps a tenant column onto a table the replay says has no ``tenant_id``
  -> hard failure, because that is precisely "silently degraded to application-layer
  filtering".

The gate is static by design: it cannot observe a live database, so it cannot replace
the deployment-side assertion (see docs/tenant-isolation.md). What it does make
impossible is adding or dropping a tenant column, or narrowing the policy script,
without an explicit, reviewable change in this repository.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]

# Table and column identifiers are lower-cased before comparison, so the patterns only
# need to be case-insensitive.
CREATE_TABLE = re.compile(
    r"create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][\w]*(?:\.[a-z_][\w]*)?)\s*\(",
    re.IGNORECASE)
ADD_TENANT_COLUMN = re.compile(
    r"alter\s+table\s+(?:if\s+exists\s+)?([a-z_][\w]*(?:\.[a-z_][\w]*)?)\s+add\s+"
    r"(?:column\s+)?(?:if\s+not\s+exists\s+)?tenant_id\b", re.IGNORECASE)
DROP_TENANT_COLUMN = re.compile(
    r"alter\s+table\s+(?:if\s+exists\s+)?([a-z_][\w]*(?:\.[a-z_][\w]*)?)\s+drop\s+column\s+"
    r"(?:if\s+exists\s+)?tenant_id\b", re.IGNORECASE)
SET_TENANT_NOT_NULL = re.compile(
    r"alter\s+table\s+(?:if\s+exists\s+)?([a-z_][\w]*(?:\.[a-z_][\w]*)?)\s+"
    r"alter\s+column\s+(?:if\s+exists\s+)?tenant_id\s+set\s+not\s+null\b", re.IGNORECASE)
DROP_TABLE = re.compile(
    r"drop\s+table\s+(?:if\s+exists\s+)?([a-z_][\w]*(?:\.[a-z_][\w]*)?)", re.IGNORECASE)
RENAME_TABLE = re.compile(
    r"alter\s+table\s+([a-z_][\w]*(?:\.[a-z_][\w]*)?)\s+rename\s+to\s+"
    r"([a-z_][\w]*(?:\.[a-z_][\w]*)?)", re.IGNORECASE)
CREATE_TABLE_MENTION = re.compile(r"create\s+table\b", re.IGNORECASE)
TABLE_ANNOTATION = re.compile(
    r"@Table\s*\(\s*(?:[\w.]+\s*=\s*\"[^\"]+\"\s*,\s*)?name\s*=\s*\"([^\"]+)\"", re.DOTALL)
TENANT_ENTITY = re.compile(r"\btenantId\b|\btenant_id\b|extends\s+BaseEntity\b")
ENTITY_MARKER = re.compile(r"^\s*@Entity\b", re.MULTILINE)
MIN_EXPECTED_TENANT_TABLES = 40
MIN_EXPECTED_COMPOSE_DB_SERVICES = 4


@dataclass(frozen=True)
class Paths:
    """Every artifact this gate reads, resolved against a repository root."""

    root: Path

    @property
    def policy_sql(self) -> Path:
        return self.root / "infra" / "postgres" / "tenant-rls.sql"

    @property
    def apply_script(self) -> Path:
        return self.root / "build" / "apply-tenant-rls.sh"

    @property
    def exemptions(self) -> Path:
        return self.root / "build" / "tenant-rls-exemptions.txt"

    @property
    def prod_compose(self) -> Path:
        return self.root / "infra" / "docker-compose.prod.yml"

    @property
    def helm_values(self) -> Path:
        return self.root / "deploy" / "helm" / "socp-core" / "values.yaml"

    @property
    def rls_wrapper(self) -> Path:
        return (self.root / "platform" / "socp-tenant" / "src" / "main" / "java" / "com" / "socp"
                / "platform" / "tenant" / "persistence" / "TenantRlsDataSourcePostProcessor.java")

    @property
    def prod_guard(self) -> Path:
        return (self.root / "platform" / "socp-auth" / "src" / "main" / "java" / "com" / "socp"
                / "platform" / "auth" / "security" / "ProdGuard.java")

    def shown(self, path: Path) -> str:
        try:
            return path.relative_to(self.root).as_posix()
        except ValueError:
            return path.as_posix()


@dataclass
class Location:
    """One Flyway location: a directory of migrations applied to one database."""

    path: Path
    tables: set[str] = field(default_factory=set)
    tenant_tables: set[str] = field(default_factory=set)
    nullable_tenant_tables: set[str] = field(default_factory=set)


@dataclass
class Result:
    errors: list[str] = field(default_factory=list)
    locations: int = 0
    tenant_tables: int = 0
    exempt_tables: int = 0
    compose_services: int = 0
    nullable: set[str] = field(default_factory=set)
    module_tenant: dict[str, set[str]] = field(default_factory=dict)
    exemption_entries: dict[str, str] = field(default_factory=dict)


def statement_bodies(sql: str) -> list[str]:
    """Split SQL into statements, keeping dollar-quoted bodies intact."""
    bodies: list[str] = []
    current: list[str] = []
    dollar_tag: str | None = None
    index = 0
    while index < len(sql):
        if dollar_tag is None:
            tag = re.match(r"\$\$|\"[^\"]*\"|'[^']*'|--[^\n]*", sql[index:])
            if tag and tag.group(0).startswith("--"):
                # Drop the comment text but keep the line break, so a trailing comment
                # cannot glue itself onto the next column definition.
                current.append("\n")
                end = sql.find("\n", index)
                index = len(sql) if end < 0 else end
                continue
            if tag and tag.group(0) == "$$":
                dollar_tag = "$$"
                current.append("$$")
                index += 2
                continue
            if tag:
                current.append(tag.group(0))
                index += len(tag.group(0))
                continue
            if sql[index] == ";":
                bodies.append("".join(current))
                current = []
                index += 1
                continue
            current.append(sql[index])
            index += 1
            continue
        end = sql.find(dollar_tag, index)
        if end < 0:
            current.append(sql[index:])
            break
        current.append(sql[index:end + len(dollar_tag)])
        index = end + len(dollar_tag)
        dollar_tag = None
    if "".join(current).strip():
        bodies.append("".join(current))
    return [body for body in bodies if body.strip()]


def create_body(sql: str, open_paren: int) -> str:
    """Return the text between the balanced parentheses of a CREATE TABLE body."""
    depth = 0
    for index in range(open_paren, len(sql)):
        if sql[index] == "(":
            depth += 1
        elif sql[index] == ")":
            depth -= 1
            if depth == 0:
                return sql[open_paren + 1:index]
    # Unterminated: surface everything after the paren so the caller still sees the
    # tenant_id mention instead of silently classifying the table as global.
    return sql[open_paren + 1:]


def depth_one_items(body: str) -> list[str]:
    """Comma-separated items at parenthesis depth 1 of a CREATE TABLE body."""
    items: list[str] = []
    current: list[str] = []
    depth = 0
    for char in body:
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "," and depth == 0:
            items.append("".join(current))
            current = []
            continue
        current.append(char)
    if "".join(current).strip():
        items.append("".join(current))
    return [item.strip() for item in items if item.strip()]


def declares_tenant_column(body: str) -> bool:
    return any(item.lower().lstrip('"').startswith("tenant_id") for item in depth_one_items(body))


def replay(location: Location, paths: Paths, result: Result) -> None:
    """Replay the DDL of one location into its table and tenant surface."""
    for migration in sorted(path for path in location.path.iterdir() if path.is_file()):
        if migration.suffix != ".sql":
            continue
        sql = migration.read_text(encoding="utf-8")
        for body in statement_bodies(sql):
            creates = list(CREATE_TABLE.finditer(body))
            mentions = len(CREATE_TABLE_MENTION.findall(body))
            if mentions != len(creates):
                result.errors.append(
                    f"{paths.shown(migration)}: {mentions} CREATE TABLE keyword(s) but "
                    f"{len(creates)} parsed; the DDL replay does not understand this shape, so "
                    "tenant coverage cannot be proven. Extend build/verify-rls.py.")
            for match in creates:
                table = match.group(1).split(".")[-1].lower()
                if "." in match.group(1).lower() and not match.group(1).lower().startswith("public."):
                    result.errors.append(
                        f"{paths.shown(migration)}: table {match.group(1)} is outside the public "
                        "schema, which tenant-rls.sql does not cover")
                location.tables.add(table)
                if declares_tenant_column(create_body(body, match.end() - 1)):
                    location.tenant_tables.add(table)
                    location.nullable_tenant_tables.add(table)
            for match in ADD_TENANT_COLUMN.finditer(body):
                table = match.group(1).split(".")[-1].lower()
                location.tables.add(table)
                location.tenant_tables.add(table)
                location.nullable_tenant_tables.add(table)
            for match in SET_TENANT_NOT_NULL.finditer(body):
                location.nullable_tenant_tables.discard(match.group(1).split(".")[-1].lower())
            for match in DROP_TENANT_COLUMN.finditer(body):
                table = match.group(1).split(".")[-1].lower()
                location.tenant_tables.discard(table)
                location.nullable_tenant_tables.discard(table)
            for match in RENAME_TABLE.finditer(body):
                source = match.group(1).split(".")[-1].lower()
                target = match.group(2).split(".")[-1].lower()
                if source in location.tables:
                    location.tables.discard(source)
                    location.tables.add(target)
                    for group in (location.tenant_tables, location.nullable_tenant_tables):
                        if source in group:
                            group.discard(source)
                            group.add(target)
            for match in DROP_TABLE.finditer(body):
                table = match.group(1).split(".")[-1].lower()
                location.tables.discard(table)
                location.tenant_tables.discard(table)
                location.nullable_tenant_tables.discard(table)
        keywords = len(re.findall(
            r"\badd\s+(?:column\s+)?(?:if\s+not\s+exists\s+)?tenant_id\b", sql, re.IGNORECASE))
        parsed = len(ADD_TENANT_COLUMN.findall(sql))
        if keywords != parsed:
            result.errors.append(
                f"{paths.shown(migration)}: {keywords} ADD tenant_id keyword(s) but {parsed} "
                "parsed; extend build/verify-rls.py before adding tenant columns")


def locations(paths: Paths) -> list[Location]:
    found: list[Location] = []
    for path in sorted(paths.root.glob("services/*/src/main/resources/db/*")):
        if not path.is_dir():
            continue
        if not any(file.is_file() for file in path.iterdir()):
            # Empty directories are not represented in Git; ignore stale build output.
            continue
        found.append(Location(path=path))
    return found


def read_exemptions(paths: Paths, result: Result) -> dict[str, str]:
    if not paths.exemptions.is_file():
        result.errors.append(f"{paths.shown(paths.exemptions)}: missing exemption register")
        return {}
    entries: dict[str, str] = {}
    for line in paths.exemptions.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        parts = [part.strip() for part in text.split("\t")]
        if len(parts) < 2 or "/" not in parts[0] or not parts[1]:
            result.errors.append(f"{paths.shown(paths.exemptions)}: malformed line {line!r}; "
                                 "expected <module>/<table><TAB><reason>")
            continue
        key = parts[0].lower()
        if key in entries:
            result.errors.append(f"{paths.shown(paths.exemptions)}: duplicate exemption {key}")
        if len(parts[1]) < 40:
            result.errors.append(f"{paths.shown(paths.exemptions)}: exemption {key} needs a reason "
                                 "long enough to review (why no tenant_id, who owns it)")
        entries[key] = parts[1]
    return entries


def entity_tenant_tables(paths: Paths, result: Result) -> dict[str, set[str]]:
    """Map each service module to the tables its entities treat as tenant-owned."""
    per_module: dict[str, set[str]] = {}
    for java in sorted(paths.root.glob("services/*/src/main/java/**/*.java")):
        source = java.read_text(encoding="utf-8")
        if "@Entity" not in source or not ENTITY_MARKER.search(source):
            continue
        table = TABLE_ANNOTATION.search(source)
        if table is None:
            # Without an explicit @Table name the physical name is strategy-derived, so
            # this gate cannot resolve it. Business entities all name their table; a new
            # unnamed one must be reviewed rather than silently skipped.
            result.errors.append(f"{paths.shown(java)}: @Entity without @Table(name=...), so "
                                 "tenant coverage cannot be resolved statically")
            continue
        if not TENANT_ENTITY.search(source):
            continue
        module = java.relative_to(paths.root).parts[1]
        per_module.setdefault(module, set()).add(table.group(1).lower())
    return per_module


def policy_script_checks(paths: Paths, result: Result) -> None:
    if not paths.policy_sql.is_file():
        result.errors.append(f"{paths.shown(paths.policy_sql)}: missing RLS policy script")
    else:
        sql = paths.policy_sql.read_text(encoding="utf-8")
        required = {
            "ENABLE ROW LEVEL SECURITY": "enable-row-level-security",
            "FORCE ROW LEVEL SECURITY": "force-row-level-security",
            "CREATE POLICY socp_tenant_isolation": "named tenant policy",
            "WITH CHECK": "write-side policy check",
            "column_name = 'tenant_id'": "tenant_id discovery rule",
            "table_schema = 'public'": "public schema scope",
        }
        for needle, description in required.items():
            if needle not in sql:
                result.errors.append(
                    f"{paths.shown(paths.policy_sql)}: missing {description} ({needle!r}); "
                    "narrowing this script silently removes the database backstop")
        if "DROP POLICY IF EXISTS socp_tenant_isolation" not in sql:
            result.errors.append(
                f"{paths.shown(paths.policy_sql)}: policy creation must be idempotent "
                "(DROP POLICY IF EXISTS before CREATE POLICY)")
    if not paths.apply_script.is_file():
        result.errors.append(f"{paths.shown(paths.apply_script)}: missing apply step")
    elif not paths.policy_sql.is_file() or paths.policy_sql.name not in paths.apply_script.read_text(
            encoding="utf-8"):
        result.errors.append(f"{paths.shown(paths.apply_script)}: must apply "
                             f"{paths.policy_sql.name}")

    if paths.rls_wrapper.is_file():
        wrapper = paths.rls_wrapper.read_text(encoding="utf-8")
        if "matchIfMissing = true" in wrapper:
            result.errors.append(
                f"{paths.shown(paths.rls_wrapper)}: the RLS wrapper must stay opt-in, so "
                "ProdGuard remains the only thing that can make it implicit")
    else:
        result.errors.append(f"{paths.shown(paths.rls_wrapper)}: missing RLS data-source wrapper")
    if not paths.prod_guard.is_file():
        result.errors.append(f"{paths.shown(paths.prod_guard)}: missing production guard")
    elif "socp.tenant.rls.enabled" not in paths.prod_guard.read_text(encoding="utf-8"):
        result.errors.append(f"{paths.shown(paths.prod_guard)}: production must assert the RLS "
                             "connection wrapper is enabled for database services")


def compose_services(text: str) -> dict[str, str]:
    """Split a Compose file into its top-level service blocks."""
    blocks: dict[str, str] = {}
    current = ""
    for line in text.splitlines(keepends=True):
        match = re.match(r"^  ([a-z0-9][a-z0-9_-]*):\s*$", line)
        if match:
            current = match.group(1)
            blocks[current] = ""
            continue
        if re.match(r"^[a-z0-9][a-z0-9_-]*:\s*$", line):
            current = ""
            continue
        if current:
            blocks[current] += line
    return blocks


def deployment_posture(paths: Paths, result: Result, min_services: int =
    MIN_EXPECTED_COMPOSE_DB_SERVICES) -> None:
    """Require every baseline that carries a tenant database to enable the RLS wrapper."""
    checked = 0
    if paths.prod_compose.is_file():
        for service, block in sorted(compose_services(
                paths.prod_compose.read_text(encoding="utf-8")).items()):
            # A service reaches PostgreSQL through SOCP_PG_HOST/SPRING_DATASOURCE_URL;
            # middleware containers and the stateless gateway do not.
            if not re.search(r"^\s*(SOCP_PG_HOST|SPRING_DATASOURCE_URL):", block, re.MULTILINE):
                continue
            checked += 1
            if not re.search(r'^\s*SOCP_TENANT_RLS_ENABLED:\s*"?true"?\s*$', block, re.MULTILINE):
                result.errors.append(
                    f"{paths.shown(paths.prod_compose)}: service {service} connects to a tenant "
                    "database but does not set SOCP_TENANT_RLS_ENABLED=true")
        if checked < min_services:
            result.errors.append(
                f"{paths.shown(paths.prod_compose)}: only {checked} tenant database service(s) "
                "found; the posture assertion cannot be trusted")
    result.compose_services = checked
    if paths.helm_values.is_file():
        values = paths.helm_values.read_text(encoding="utf-8")
        if not re.search(r"^\s*SOCP_TENANT_RLS_ENABLED:\s*[\"']?true", values, re.MULTILINE):
            result.errors.append(
                f"{paths.shown(paths.helm_values)}: the Helm baseline must set "
                "SOCP_TENANT_RLS_ENABLED=true for every tenant database service")
    else:
        result.errors.append(f"{paths.shown(paths.helm_values)}: missing Helm baseline")


def evaluate(root: Path = ROOT, min_tenant_tables: int = MIN_EXPECTED_TENANT_TABLES,
             min_compose_services: int = MIN_EXPECTED_COMPOSE_DB_SERVICES) -> Result:
    paths = Paths(root=root)
    result = Result()
    policy_script_checks(paths, result)
    exemptions = read_exemptions(paths, result)
    entities = entity_tenant_tables(paths, result)

    module_tenant: dict[str, set[str]] = {}
    module_tables: dict[str, set[str]] = {}
    found = locations(paths)
    for location in found:
        replay(location, paths, result)
        module = location.path.parents[4].name
        if not location.tables:
            result.errors.append(f"{paths.shown(location.path)}: no table could be replayed from "
                                 "this location; the gate cannot prove coverage")
        module_tenant.setdefault(module, set()).update(location.tenant_tables)
        module_tables.setdefault(module, set()).update(location.tables)
        for table in location.tenant_tables:
            if table in location.nullable_tenant_tables:
                result.nullable.add(f"{module}/{table}")

    # Entities that claim tenant ownership must land on a real tenant_id column, in the
    # same module's database surface.
    for module, tables in sorted(entities.items()):
        surface = module_tenant.get(module, set())
        for table in sorted(tables):
            if table not in surface:
                result.errors.append(
                    f"services/{module}: entity table {table} maps a tenant column but the "
                    "migrations have no tenant_id for it, so isolation is application-layer "
                    "only with no database backstop")

    # Every table in the surface needs a decision: covered (tenant_id present) or exempt.
    for module, tables in sorted(module_tables.items()):
        for table in sorted(tables):
            if table in module_tenant.get(module, set()):
                key = f"{module}/{table}".lower()
                if key in exemptions:
                    result.errors.append(f"{paths.shown(paths.exemptions)}: {key} is exempted but "
                                         "the table does carry tenant_id, so RLS covers it")
                continue
            key = f"{module}/{table}".lower()
            if key not in exemptions:
                result.errors.append(
                    f"services/{module}: table {table} has no tenant_id and is not registered in "
                    f"{paths.shown(paths.exemptions)}; register it as an explicit global/shared "
                    "exemption with a reason, or scope it to a tenant")
    result.locations = len(found)
    result.tenant_tables = sum(len(tables) for tables in module_tenant.values())
    result.exempt_tables = len(exemptions)
    if result.tenant_tables < min_tenant_tables:
        result.errors.append(f"unexpected RLS inventory: only {result.tenant_tables} tenant "
                             "table(s) discovered")
    for key in sorted(exemptions):
        module, table = key.split("/", 1)
        if table not in module_tables.get(module, set()):
            result.errors.append(f"{paths.shown(paths.exemptions)}: exemption {key} is stale "
                                 "(no such table in that module's migrations)")

    deployment_posture(paths, result, min_compose_services)
    result.module_tenant = module_tenant
    result.exemption_entries = exemptions
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", action="store_true",
                        help="print the per-database tenant coverage inventory alongside the verdict")
    arguments = parser.parse_args()

    result = evaluate()

    if arguments.inventory:
        for module, tables in sorted(result.module_tenant.items()):
            print(f"{module}: {len(tables)} tenant table(s)")
            for table in sorted(tables):
                suffix = "  [tenant_id nullable]" if f"{module}/{table}" in result.nullable else ""
                print(f"  - {table}{suffix}")
        for key in sorted(result.exemption_entries):
            print(f"{key}: exempt")

    if result.errors:
        print("Tenant RLS gate failed:", file=sys.stderr)
        for error in result.errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Tenant RLS gate passed: {result.locations} Flyway location(s), "
          f"{result.tenant_tables} RLS-covered tenant table(s), {result.exempt_tables} registered "
          f"global table(s), {result.compose_services} prod Compose service(s) asserting the RLS "
          "wrapper")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
