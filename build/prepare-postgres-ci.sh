#!/usr/bin/env bash
set -euo pipefail

# Reconcile the role and database contract in an already-running CI Compose
# PostgreSQL container. Docker entrypoint initialization only runs for a fresh
# volume, while E2E jobs must be safe to rerun against an existing volume.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${SOCP_PG_RUNTIME_USER:?set SOCP_PG_RUNTIME_USER}"
: "${SOCP_PG_RUNTIME_PASSWORD:?set SOCP_PG_RUNTIME_PASSWORD}"
: "${SOCP_PG_MIGRATION_USER:?set SOCP_PG_MIGRATION_USER}"
: "${SOCP_PG_MIGRATION_PASSWORD:?set SOCP_PG_MIGRATION_PASSWORD}"

bootstrap_user="${SOCP_PG_BOOTSTRAP_USER:-socp}"
pg_container="$(bash build/compose.sh ps -q postgres)"
test -n "$pg_container"

docker cp infra/init-sql/pg/00_roles.sh "$pg_container:/tmp/00_roles.sh"
docker cp infra/init-sql/pg/02_runtime_grants.sh "$pg_container:/tmp/02_runtime_grants.sh"
docker exec \
  -e POSTGRES_USER="$bootstrap_user" \
  -e POSTGRES_DB="$bootstrap_user" \
  -e SOCP_PG_RUNTIME_USER="$SOCP_PG_RUNTIME_USER" \
  -e SOCP_PG_RUNTIME_PASSWORD="$SOCP_PG_RUNTIME_PASSWORD" \
  -e SOCP_PG_MIGRATION_USER="$SOCP_PG_MIGRATION_USER" \
  -e SOCP_PG_MIGRATION_PASSWORD="$SOCP_PG_MIGRATION_PASSWORD" \
  "$pg_container" bash /tmp/00_roles.sh

# Keep the database registry in the Docker entrypoint SQL as the single source
# of truth. The third token is the identifier in `CREATE DATABASE name;`.
while read -r database; do
  exists="$(docker exec "$pg_container" psql -U "$bootstrap_user" -d "$bootstrap_user" \
    -Atqc "SELECT 1 FROM pg_database WHERE datname = '$database'")"
  if [ "$exists" != "1" ]; then
    docker exec "$pg_container" createdb -U "$bootstrap_user" "$database"
  fi
done < <(awk '/^[[:space:]]*CREATE DATABASE[[:space:]]+/ { name=$3; sub(/;.*/, "", name); print name }' \
  infra/init-sql/pg/01_databases.sql)

docker exec \
  -e POSTGRES_USER="$bootstrap_user" \
  -e SOCP_PG_RUNTIME_USER="$SOCP_PG_RUNTIME_USER" \
  -e SOCP_PG_MIGRATION_USER="$SOCP_PG_MIGRATION_USER" \
  "$pg_container" bash /tmp/02_runtime_grants.sh
