# PostgreSQL backup and restore playbook

`build/backup-postgres.sh` dumps one database as a custom-format, mode-0600 file
with a SHA-256 sidecar. `build/restore-postgres.sh` restores it into an explicitly
named database and refuses protected targets. This playbook covers the two things
that those single-database helpers do **not** do on their own — completeness of the
backup set, and re-establishing isolation after a restore — because those are the
failure modes that make a "successful" restore unusable or unsafe.

## The backup set is the unit, not the file

SOCP splits its data across **16 per-service databases** with no cross-database
foreign keys or transactions
([ADR 002 storage responsibilities](../adr/002-storage-responsibilities.md));
cross-service consistency is carried by the outbox/Kafka at-least-once paths. So a
complete recovery point is the *set* of per-database dumps, and an incomplete set is
a silent, not a loud, failure: `backup-postgres.sh` honours whatever `PGDATABASE` you
set, so pointing it once at `detect` "backs up" one service and looks like success.

Use the roster walk instead. It iterates `build/postgres-databases.txt` and emits one
dump per database plus a single manifest (database, sha256, path, server version,
timestamp):

```bash
# PGHOST / PGPORT / PGUSER must reach the cluster as an administrator
bash build/backup-postgres-all.sh /var/backups/socp/$(date -u +%Y%m%dT%H%M%SZ)
```

`build/postgres-databases.txt` is the single machine-readable source of the roster.
`build/verify-backup-toolchain.py` fails if it drifts from the `CREATE DATABASE` list
in `infra/init-sql/pg/01_databases.sql` or the hard-coded array in
`infra/init-sql/pg/02_runtime_grants.sh`, so the backup set cannot silently fall
behind a new service database. When you add a service DB, add it to all three.

## Restore and RLS ordering

A bare restore is not a usable tenant-isolated database for three reasons:

1. `pg_dump`/`pg_restore` here run with `--no-privileges`, so the restored database
   carries **no runtime grants**.
2. PostgreSQL **roles are cluster-global objects** and never appear in a per-database
   dump. The runtime and migration roles come from `infra/init-sql/pg/00_roles.sh`,
   not from the dump.
3. The third isolation layer — row-level security — is applied by
   `infra/postgres/tenant-rls.sql` **after** tables exist, and a restore into a fresh
   database has none of it. A library missing RLS is the worst kind of success: it
   queries fine but every tenant can read every other tenant's rows (the
   [tenant-isolation contract](../tenant-isolation.md) treats RLS as the last
   defence-in-depth layer, not optional).

The correct order for a finalized restore is therefore: **cluster roles exist →
restore schema+data → runtime grants → tenant RLS → assert RLS present.** Do not
grant-then-RLS in the other order: `FORCE ROW LEVEL SECURITY` on a table the runtime
role cannot `SELECT` yet would lock the application out.

`restore-postgres.sh --finalize` does steps "runtime grants → tenant RLS → assert"
against the restored database and **fails closed** if any `tenant_id` table is missing
either `relrowsecurity`/`relforcerowsecurity` or the `socp_tenant_isolation` policy:

```bash
# cluster roles must already exist (idempotent):
bash build/apply-postgres-roles.sh

# restore into an isolated drill database, then finalize (grants + RLS + assert):
PGUSER=socp_admin SOCP_PG_RUNTIME_USER=socp_runtime SOCP_PG_MIGRATION_USER=socp_migrator \
  bash build/restore-postgres.sh --finalize \
  /var/backups/socp/.../socp-detect-....dump restore_drill_detect
```

For a pure read-only integrity drill you can omit `--finalize`; the script then
restores and prints exactly what is still missing (grants + RLS) rather than pretending
the library is production-shaped. To apply the layers by hand instead, run
`build/apply-postgres-roles.sh` (cluster roles + per-database grants) and then
`build/apply-tenant-rls.sh` against the target database with `PGDATABASE` set to it —
`apply-tenant-rls.sh` is what executes `infra/postgres/tenant-rls.sql`.

## Restore verification (row counts and checkpoints)

After a finalized restore, confirm the drill database matches the source's recovery
shape before trusting it:

```bash
# per-table row counts on both sides; compare the top tables
psql --dbname=restore_drill_detect --tuples-only --no-align -c \
  "select relname, n_live_tup from pg_stat_user_tables order by n_live_tup desc limit 20;"

# Flyway must report the same latest version as the source (schema drift guard)
psql --dbname=restore_drill_detect -c \
  "select version, checksum from flyway_schema_history order by installed_rank desc limit 5;"
```

Compare `version` against the source; a mismatch means the dump and the application
image are from different schema levels — resolve it before the drill database is used
to make any release decision.

## Where the backup target mounts

`build/backup-postgres-all.sh` writes to a directory argument; that directory must be
outside the ephemeral container filesystem and on storage that is itself backed up.

* **docker-compose.prod**: mount a host or object-store-backed volume into the
  postgres (or a dedicated `socp-backup` sidecar) container, e.g.
  `volumes: [ "/var/backups/socp:/var/backups/socp" ]`, and run
  `backup-postgres-all.sh /var/backups/socp/<stamp>` from there via a scheduled job.
* **Helm**: the chart deliberately does not ship a backup CronJob (durability is
  deployment-owned — [production readiness](../production-readiness.md) lists these
  drills as external evidence). Provide an environment-owned CronJob that runs
  `build/backup-postgres-all.sh` with `PGHOST` pointing at the in-cluster PostgreSQL
  Service and a `persistentVolumeClaim` (or object-store sync) as the backup directory.
  Do not reuse the application runtime role: back up as an administrative role and keep
  the runtime secrets out of the backup namespace.

## Retention, RPO/RTO, and what is NOT provided

* **This repository provides**: per-database logical dump + SHA-256 sidecar, a roster
  walk producing a single manifest, a finalize-and-assert restore, and this playbook.
* **Not provided here (deployment-owned)**: WAL/point-in-time recovery, OpenSearch
  snapshot repository registration and restore, ClickHouse `BACKUP`/`RESTORE`
  (including `alarm_detail` merge semantics), Kafka consumer-offset backup and the
  DLQ redrive covered in [dlq-replay.md](dlq-replay.md), object-store versioning and key
  rotation, and any **measured** RPO/RTO. Until those drills are executed on the target
  cluster, RPO equals the interval between `backup-postgres-all.sh` runs (logical-only,
  so anything since the last dump is lost) and RTO is unproven. Record the real numbers
  as evidence; do not infer durability from the existence of these scripts.
