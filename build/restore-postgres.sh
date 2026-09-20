#!/usr/bin/env bash
set -euo pipefail

# Restore helper, the missing half of build/backup-postgres.sh.
#
# A backup with no restore path is not a recovery capability: nothing proves the
# dump is readable until it has been restored somewhere. This script restores
# into an explicitly named database so an operator cannot point it at a
# database they did not name, and refuses the databases that must never be a
# restore target.
#
# IMPORTANT — pg_dump is restored here with --no-privileges, and PostgreSQL
# cluster roles are *global* objects that never appear in a per-database dump.
# So a bare restore yields a schema+data library that has NO runtime grants and
# NO row-level-security policies: it is not tenant-isolated and the application
# role cannot even SELECT. Pass --finalize to re-establish the runtime grants and
# re-apply infra/postgres/tenant-rls.sql against the restored database, then run
# an existence assertion that fails closed if any tenant_id table lacks RLS.
#
# Usage: restore-postgres.sh [--finalize] <dump-file> <target-database>
FINALIZE=0
if [[ "${1:-}" == "--finalize" ]]; then
  FINALIZE=1
  shift
fi
if [[ $# -ne 2 || -z "${1}" || -z "${2}" ]]; then
  echo "usage: $0 [--finalize] <dump-file> <target-database>" >&2
  exit 2
fi

DUMP_FILE="$1"
TARGET_DB="$2"

: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?set PGUSER}"

# Databases that must never be overwritten by a restore. PGDATABASE is included
# because restoring a dump over its own source is a destructive no-op at best.
PROTECTED="postgres template0 template1 ${PGDATABASE:-}"
for name in ${PROTECTED}; do
  if [[ "${TARGET_DB}" == "${name}" ]]; then
    echo "refusing to restore into protected database: ${TARGET_DB}" >&2
    exit 2
  fi
done

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "dump file not found: ${DUMP_FILE}" >&2
  exit 2
fi

# Verify the sidecar written by backup-postgres.sh when it is present. A dump
# that is truncated or tampered with must fail here, not halfway through.
if [[ -f "${DUMP_FILE}.sha256" ]]; then
  sha256sum --check --status "${DUMP_FILE}.sha256" \
    || { echo "checksum mismatch for ${DUMP_FILE}" >&2; exit 1; }
  echo "checksum=verified"
else
  echo "checksum=missing sidecar; restored without verification"
fi

# Create rather than reuse: pg_restore into an existing database appends to
# whatever is already there, and a partial restore would look like success.
if psql --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
      --dbname=postgres --tuples-only --no-align \
      --command="select 1 from pg_database where datname='${TARGET_DB}'" | grep -q 1; then
  echo "target database already exists: ${TARGET_DB}" >&2
  echo "drop it explicitly or choose another name" >&2
  exit 2
fi

createdb --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" "${TARGET_DB}"

umask 077
pg_restore \
  --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
  --dbname="${TARGET_DB}" \
  --no-owner --no-privileges \
  "${DUMP_FILE}"

printf 'restored=%s\ninto=%s\n' "${DUMP_FILE}" "${TARGET_DB}"

if [[ "${FINALIZE}" -ne 1 ]]; then
  cat >&2 <<'NOTE'
--finalize not set: the restored database has schema+data only. pg_restore ran
with --no-privileges and PostgreSQL roles are global (never in a per-database
dump), so this library has NO runtime grants and NO tenant RLS policies. It is
NOT safe to serve application traffic. To make it usable, re-run with
--finalize (needs the migration/admin role and SOCP_PG_RUNTIME_USER), or apply
build/apply-postgres-roles.sh and build/apply-tenant-rls.sh manually against the
target database. See docs/operations/backup-restore.md.
NOTE
  exit 0
fi

# --finalize: re-establish the runtime-role grants for THIS database only, then
# re-apply the tenant RLS policy, then assert it took. Order matters: tables must
# exist (post-restore), the runtime role must exist cluster-wide (created by
# 00_roles.sh, not by this dump), grants must precede RLS because FORCE ROW LEVEL
# SECURITY on an ownerless table with no SELECT grant would lock the app out.
: "${SOCP_PG_RUNTIME_USER:?--finalize requires SOCP_PG_RUNTIME_USER (the runtime role must already exist cluster-wide)}"
: "${SOCP_PG_MIGRATION_USER:?--finalize requires SOCP_PG_MIGRATION_USER (schema owner for the restored objects)}"

psql --set=ON_ERROR_STOP=1 \
  --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" --dbname="${TARGET_DB}" \
  --set=database="${TARGET_DB}" --set=runtime_user="${SOCP_PG_RUNTIME_USER}" \
  --set=migration_user="${SOCP_PG_MIGRATION_USER}" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"migration_user";
GRANT CONNECT ON DATABASE :"database" TO :"runtime_user";
GRANT USAGE ON SCHEMA public TO :"runtime_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"runtime_user";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"runtime_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"runtime_user";
SQL

# Re-apply the tenant RLS policy against the restored database. apply-tenant-rls.sh
# is PGDATABASE-driven, so we point it at the target rather than at the source DB.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PGHOST="${PGHOST}" PGPORT="${PGPORT}" PGUSER="${PGUSER}" PGPASSWORD="${PGPASSWORD:-}" \
  PGDATABASE="${TARGET_DB}" bash "${script_dir}/apply-tenant-rls.sh"

# Fail closed: every tenant_id-bearing table must have BOTH row-security flags and
# the socp_tenant_isolation policy. A missing policy is the silent failure the
# holistic review called out -- without this assertion a restored library looks
# healthy while every tenant can read every other tenant's rows.
missing="$(psql --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
  --dbname="${TARGET_DB}" --tuples-only --no-align <<'SQL'
WITH tenant_tables AS (
  SELECT c.table_schema, c.table_name
  FROM information_schema.columns c
  WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
)
SELECT count(*)
FROM tenant_tables tt
JOIN pg_class cls ON cls.relname = tt.table_name
JOIN pg_namespace ns ON ns.oid = cls.relnamespace AND ns.nspname = tt.table_schema
LEFT JOIN pg_policies pol
  ON pol.tablename = tt.table_name AND pol.schemaname = tt.table_schema
  AND pol.policyname = 'socp_tenant_isolation'
WHERE cls.relkind IN ('r','p')
  AND (cls.relrowsecurity IS NOT TRUE
       OR cls.relforcerowsecurity IS NOT TRUE
       OR pol.policyname IS NULL);
SQL
)"

if [[ "${missing}" != "0" ]]; then
  echo "RLS assertion FAILED: ${missing} tenant_id table(s) in ${TARGET_DB} lack FORCE RLS + socp_tenant_isolation" >&2
  exit 1
fi
printf 'finalize=ok\nrls=asserted\ntarget=%s\n' "${TARGET_DB}"
