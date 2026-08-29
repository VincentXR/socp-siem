# PostgreSQL tenant RLS

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

Use a separate database role for application traffic and reserve the owner or
`BYPASSRLS` privilege for controlled migrations only. RLS complements the
`TenantScopedRepository`/write guard; it is not a replacement for explicit
tenant predicates in service queries.
