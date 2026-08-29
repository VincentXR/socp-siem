#!/usr/bin/env bash
set -euo pipefail

# Apply the PostgreSQL policy after Flyway has created every tenant-owned
# table. This is intentionally an explicit, auditable release step rather than
# an init script that might run before application migrations.
: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?set PGUSER}"
: "${PGPASSWORD:?set PGPASSWORD (use a secret manager)}"
: "${PGDATABASE:?set PGDATABASE}"

psql --set=ON_ERROR_STOP=1 \
  --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
  --dbname="${PGDATABASE}" \
  --file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/postgres/tenant-rls.sql"
printf 'tenant RLS policies applied to %s@%s:%s/%s\n' "${PGUSER}" "${PGHOST}" "${PGPORT}" "${PGDATABASE}"
