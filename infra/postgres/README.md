# PostgreSQL runtime roles and tenant RLS

The PostgreSQL bootstrap account (`socp`) is an administrative account for
initialization only. The init directory creates two separate accounts:

- `SOCP_PG_MIGRATION_USER` owns the application databases and runs Flyway;
- `SOCP_PG_RUNTIME_USER` is `NOSUPERUSER NOBYPASSRLS` and receives only schema
  usage plus table/sequence DML privileges.

The base Compose file supplies local-only development defaults. The production
overlay requires all four role/password variables and maps every PostgreSQL
application service to the runtime account. Do not reuse the bootstrap
password for either account.

For a fresh volume, `00_roles.sh`, `01_databases.sql`,
`02_runtime_grants.sh`, and `03_temporal_databases.sh` run in that order.
The last step creates `temporal` and `temporal_visibility` with the migration
account as owner. Temporal runs schema setup with `SKIP_DB_CREATE=true`;
neither application role receives `CREATEDB`, and the runtime account receives
no access to the Temporal databases. Existing volumes do not rerun init
files; apply the auditable role/grant step once with an administrator:

```bash
PGHOST=postgres PGUSER=socp PGDATABASE=postgres PGPASSWORD="$SOCP_PG_PASSWORD" \
SOCP_PG_RUNTIME_USER="$SOCP_PG_RUNTIME_USER" \
SOCP_PG_RUNTIME_PASSWORD="$SOCP_PG_RUNTIME_PASSWORD" \
SOCP_PG_MIGRATION_USER="$SOCP_PG_MIGRATION_USER" \
SOCP_PG_MIGRATION_PASSWORD="$SOCP_PG_MIGRATION_PASSWORD" \
  build/apply-postgres-roles.sh
```

The role application script also provisions missing Temporal databases. It
refuses to take over an existing Temporal database owned by another role;
review that deployment's database and schema ownership before applying an
explicit administrative ownership transfer. Reapplying the script with the
expected owner preserves Temporal data and schema history.

Run Flyway with the migration account, not the runtime account. The runtime
account must not be granted `CREATE` on `public`, `SUPERUSER`, or `BYPASSRLS`.

Run `build/apply-tenant-rls.sh` once for each application database after its
Flyway migrations have completed. The script requires `PGHOST`, `PGUSER`,
`PGPASSWORD`, and `PGDATABASE`; it uses `ON_ERROR_STOP` and does not contain a
password or a default tenant.

The policy permits either the current `socp.tenant_id` or the reserved `*`
system marker. Application connections set the value on checkout and before
statement creation through `socp-tenant`; scheduled maintenance enters system
scope explicitly. A missing scope maps to a non-matching sentinel and therefore
fails closed.

Apply and verify the policy as a release check, for example:

```bash
PGHOST=postgres PGUSER=socp PGDATABASE=alert \
  build/apply-tenant-rls.sh
psql "$DATABASE_URL" -c \
  "select relname, relrowsecurity, relforcerowsecurity from pg_class
     where relname in ('t_alarm','outbox_event');"
```

RLS complements the `TenantScopedRepository`/write guard; it is not a
replacement for explicit tenant predicates in service queries. The production
startup guard also rejects a PostgreSQL connection whose current role is a
superuser or has `BYPASSRLS`.
