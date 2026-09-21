#!/usr/bin/env bash
set -euo pipefail

# Apply the same role contract to an existing PostgreSQL volume. Docker init
# files are skipped once a data directory already exists, so this is an
# explicit administrative release step.
: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?set PGUSER to a PostgreSQL administrator}"
: "${PGPASSWORD:?set PGPASSWORD (use a secret manager)}"
: "${SOCP_PG_RUNTIME_USER:?set SOCP_PG_RUNTIME_USER}"
: "${SOCP_PG_RUNTIME_PASSWORD:?set SOCP_PG_RUNTIME_PASSWORD (use a secret manager)}"
: "${SOCP_PG_MIGRATION_USER:?set SOCP_PG_MIGRATION_USER}"
: "${SOCP_PG_MIGRATION_PASSWORD:?set SOCP_PG_MIGRATION_PASSWORD (use a secret manager)}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export POSTGRES_USER="$PGUSER"
export POSTGRES_DB="${PGDATABASE:-postgres}"

"$script_dir/../infra/init-sql/pg/00_roles.sh"
"$script_dir/../infra/init-sql/pg/02_runtime_grants.sh"
bash "$script_dir/../infra/init-sql/pg/03_temporal_databases.sh"

printf 'PostgreSQL role contract applied through %s@%s:%s\n' \
  "$PGUSER" "$PGHOST" "$PGPORT"
