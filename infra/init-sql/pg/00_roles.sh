#!/usr/bin/env bash
set -euo pipefail

# The postgres image runs init files as POSTGRES_USER. Keep that bootstrap
# account separate from the two accounts used after initialization:
# migrations own the schema, while application traffic cannot create DDL or
# bypass tenant RLS.
: "${POSTGRES_USER:?POSTGRES_USER must be set by the postgres image}"
: "${POSTGRES_DB:?POSTGRES_DB must be set by the postgres image}"

runtime_user="${SOCP_PG_RUNTIME_USER:-socp_runtime}"
runtime_password="${SOCP_PG_RUNTIME_PASSWORD:-socp-runtime-local}"
migration_user="${SOCP_PG_MIGRATION_USER:-socp_migrator}"
migration_password="${SOCP_PG_MIGRATION_PASSWORD:-socp-migrator-local}"

valid_role_name() {
  [[ "$1" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]
}

if ! valid_role_name "$runtime_user" || ! valid_role_name "$migration_user";
then
  printf 'PostgreSQL role names must match [a-z_][a-z0-9_]{0,62}\n' >&2
  exit 1
fi
if [[ "$runtime_user" == "$migration_user" ]]; then
  printf 'PostgreSQL runtime and migration roles must be different\n' >&2
  exit 1
fi

psql --set=ON_ERROR_STOP=1 \
  --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
  --set=runtime_user="$runtime_user" \
  --set=runtime_password="$runtime_password" \
  --set=migration_user="$migration_user" \
  --set=migration_password="$migration_password" <<'SQL'
-- Use \gexec instead of interpolating psql variables inside a dollar-quoted
-- DO body. That keeps passwords SQL-quoted by format(%L) and works with the
-- postgres image's stock psql client.
SELECT format(
    CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user')
        THEN 'ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L'
        ELSE 'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L'
    END,
    :'runtime_user', :'runtime_password');
\gexec

SELECT format(
    CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migration_user')
        THEN 'ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L'
        ELSE 'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L'
    END,
    :'migration_user', :'migration_password');
\gexec
SQL

printf 'PostgreSQL runtime and migration roles are ready: runtime=%s migration=%s\n' \
  "$runtime_user" "$migration_user"
