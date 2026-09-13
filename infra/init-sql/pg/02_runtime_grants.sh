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
    -c 'ALTER DATABASE :"database" OWNER TO :"migration_user";'
done

for database in "${databases[@]}"; do
  psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$database" \
    --set=database="$database" --set=bootstrap_user="$bootstrap_user" \
    --set=runtime_user="$runtime_user" \
    --set=migration_user="$migration_user" <<'SQL'
# Existing volumes may still have tables owned by the bootstrap administrator
# from the pre-split deployment. Move those objects to the migration owner so
# future Flyway DDL works after the application switches to the runtime role.
REASSIGN OWNED BY :"bootstrap_user" TO :"migration_user";
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
