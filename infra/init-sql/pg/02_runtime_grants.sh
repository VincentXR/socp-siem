#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER must be set by the postgres image}"

runtime_user="${SOCP_PG_RUNTIME_USER:-socp_runtime}"
migration_user="${SOCP_PG_MIGRATION_USER:-socp_migrator}"
bootstrap_user="$POSTGRES_USER"
if [[ ! "$runtime_user" =~ ^[a-z_][a-z0-9_]{0,62}$ ||
      ! "$migration_user" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
  printf 'PostgreSQL role names must match [a-z_][a-z0-9_]{0,62}\n' >&2
  exit 1
fi
if [[ "$runtime_user" == "$migration_user" ]]; then
  printf 'PostgreSQL runtime and migration roles must be different\n' >&2
  exit 1
fi

databases=(soc asset alert search detect hips soar report audit ai platform incident threat attack notify detect_model)

# ALTER DATABASE cannot target the database used by the current psql session.
# template1 is present in every supported PostgreSQL image and is only used as
# the administrative session for the owner changes.
for database in "${databases[@]}"; do
  psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname=template1 \
    --set=database="$database" --set=migration_user="$migration_user" \
    <<'SQL'
SELECT format('ALTER DATABASE %I OWNER TO %I;', :'database', :'migration_user');
\gexec
SQL
done

for database in "${databases[@]}"; do
  psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$database" \
    --set=database="$database" --set=bootstrap_user="$bootstrap_user" \
    --set=runtime_user="$runtime_user" \
    --set=migration_user="$migration_user" <<'SQL'
-- Existing volumes may still have application objects owned by the bootstrap
-- administrator from the pre-split deployment. Do not use REASSIGN OWNED here:
-- on an existing database the bootstrap role can also own PostgreSQL catalog
-- objects, and PostgreSQL refuses to reassign those system objects. Transfer
-- only user schemas and relations instead; this is idempotent after the first
-- run and keeps the system catalogs untouched.
SELECT format('ALTER SCHEMA %I OWNER TO %I;', n.nspname, :'migration_user')
FROM pg_namespace n
WHERE n.nspowner = (SELECT oid FROM pg_roles WHERE rolname = :'bootstrap_user')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_%';
\gexec

SELECT format('ALTER TABLE %I.%I OWNER TO %I;', n.nspname, c.relname, :'migration_user')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :'bootstrap_user')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_%'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'f');
\gexec

SELECT format('ALTER SEQUENCE %I.%I OWNER TO %I;', n.nspname, c.relname, :'migration_user')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :'bootstrap_user')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_%'
  AND c.relkind = 'S';
\gexec

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
done

printf 'PostgreSQL runtime grants applied to %s databases for role=%s\n' \
  "${#databases[@]}" "$runtime_user"
