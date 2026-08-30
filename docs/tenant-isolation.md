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

CI runs a real PostgreSQL proof when `SOCP_TESTCONTAINERS=true`; it verifies
tenant-filtered reads, denied cross-tenant inserts, missing-scope fail-closed
behavior, and the explicit system scope.
