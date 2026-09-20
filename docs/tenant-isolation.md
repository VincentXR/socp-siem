# Tenant isolation contract

SOCP now enforces tenant boundaries at three layers:

1. HTTP authentication binds the request to a tenant (collector credentials
   are bound to both collector and tenant).
2. Tenant-owned repositories extend `TenantScopedRepository`. Generic reads,
   deletes, and references fail closed; callers must pass the tenant explicitly.
   Writes are checked by `TenantEntityWriteGuard` and a missing or mismatched
   tenant is rejected.
3. PostgreSQL can provide a database-level backstop with row-level security
   (RLS). Enable it with `SOCP_TENANT_RLS_ENABLED=true`, run the migrations,
   then apply [infra/postgres/tenant-rls.sql](../infra/postgres/tenant-rls.sql)
   once for each database. The script discovers every table containing
   `tenant_id`, enables and forces RLS, and installs the same policy.

The connection wrapper writes `socp.tenant_id` before a pooled connection is
used and before each statement factory call. A missing request scope maps to a
non-matching sentinel, so a forgotten scope produces an empty/denied query
instead of a cross-tenant read. Background maintenance must be explicit:

```java
@Scheduled(fixedDelayString = "${socp.index.rebuild-ms}")
@TenantSystemJob
void rebuildAllTenantIndexes() {
    // This review-visible marker is the RLS bypass for the job.
}
```

An ordinary `@Scheduled` method receives no elevated scope. Asynchronous
maintenance paths that do not pass through the scheduled proxy must use
`TenantContext.runAsSystem(...)` explicitly.

`*` is reserved for that system scope and is never accepted as a user tenant
identifier. Production startup fails when a database service does not enable
the RLS connection context.

The RLS script is intentionally separate from Flyway application migrations:
the application role must be `NOSUPERUSER NOBYPASSRLS`, should not own tenant
tables, and should not need DDL privileges. Operators can apply
the policy after all service schemas exist. Future seed/data migrations should
run with the migration role or an explicit `SET socp.tenant_id='*'`.

Because the script is a release step rather than a migration, layer 3 is opt-in
per deployment and its coverage is asserted statically by
[../build/verify-rls.py](../build/verify-rls.py): it replays the DDL of every
Flyway location, requires every tenant-owned JPA entity to land on a real
`tenant_id` column, and requires a written decision for every other table. The
same file is the register of global/shared tables that have no database backstop
at all: [../build/tenant-rls-exemptions.txt](../build/tenant-rls-exemptions.txt).
The gate proves the repository contract; it cannot observe a live database, so a
deployment that never ran `build/apply-tenant-rls.sh` still has only layers 1 and
2. Verifying that is a release check on the target cluster, not a build check:

```bash
psql "$TARGET" -Atc "select c.relname
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  join information_schema.columns col
    on col.table_name = c.relname and col.table_schema = n.nspname
   and col.column_name = 'tenant_id'
 where n.nspname = 'public' and c.relkind = 'r'
   and (not c.relrowsecurity or not c.relforcerowsecurity
        or not exists (select 1 from pg_policies p
                        where p.schemaname = n.nspname and p.tablename = c.relname
                          and p.policyname = 'socp_tenant_isolation'))"
# every row printed here is a tenant table whose database backstop is missing
# any row printed here is a tenant table whose policy is missing
```

CI runs a real PostgreSQL proof when `SOCP_TESTCONTAINERS=true`; it verifies
tenant-filtered reads, denied cross-tenant inserts, missing-scope fail-closed
behavior, and the explicit system scope.
