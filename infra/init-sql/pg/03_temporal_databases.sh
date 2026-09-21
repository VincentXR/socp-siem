#!/usr/bin/env bash
set -euo pipefail

# Temporal auto-setup owns its schemas but must not receive cluster-wide
# CREATEDB. Bootstrap the two databases before starting that container.
: "${POSTGRES_USER:?POSTGRES_USER must identify a PostgreSQL administrator}"
migration_user="${SOCP_PG_MIGRATION_USER:-socp_migrator}"
if [[ ! "$migration_user" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
  printf 'PostgreSQL migration role name is invalid\n' >&2
  exit 1
fi

for database in temporal temporal_visibility; do
  psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname=template1 \
    --set=database="$database" --set=migration_user="$migration_user" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER %I;', :'database', :'migration_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database');
\gexec

-- Existing databases with a different owner need an explicit administrative
-- review; do not silently take ownership of an unrelated Temporal deployment.
SELECT pg_get_userbyid(datdba) = :'migration_user' AS expected_owner
FROM pg_database WHERE datname = :'database';
\gset
\if :expected_owner
REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
\else
\echo 'Temporal database has a different owner; review ownership before retrying.'
\quit 1
\endif
SQL
done

printf 'Temporal databases are ready for migration role=%s\n' "$migration_user"
