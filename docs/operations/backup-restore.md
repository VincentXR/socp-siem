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
cross-service consistency is carried by the outbox/Kafka at-least-once paths.
A complete set of sequential dumps taken while writers run is **not** a consistent
recovery point. An early Alert dump can omit an alarm whose Detection outbox is
already `PUBLISHED` in a later Detection dump. Restoring both loses the obligation
to deliver that alarm.

`backup-postgres-all.sh` delegates to `backup-postgres-consistent.py`. Stop ingress,
application writers, Kafka consumers, outbox publishers, SOAR/Temporal workers and
migration jobs, then use `--maintenance` to acknowledge this maintenance window.
The helper acquires PostgreSQL `SHARE` locks on every application table in **all**
databases before exporting any snapshot. It holds these write fences while each
`pg_dump --snapshot` finishes. Existing readers may continue; accidental table
writes block. Lock acquisition fails after 30 seconds; a dump fails after one hour.
Do not run concurrent DDL or create tables during this window. A final table roster
check rejects observed schema changes; it is not a global DDL lock.

Use PostgreSQL 18 client tools (`psql`, `pg_dump`), Bash, Python 3 and `sha256sum`.
Run the fenced helper on Linux, macOS or WSL; its pipe polling requires POSIX.
The destination must be empty. The helper emits one dump per database and a
`manifest.txt` containing database, SHA-256, path and the time all fences were held:

```bash
# Verify PGHOST / PGPORT / PGUSER and stop writers before acknowledging maintenance.
bash build/backup-postgres-all.sh --maintenance /var/backups/socp/$(date -u +%Y%m%dT%H%M%SZ)
```

`build/postgres-databases.txt` is the machine-readable roster. The
`build/verify-backup-toolchain.py` contract checks agreement with
`infra/init-sql/pg/01_databases.sql` and `infra/init-sql/pg/02_runtime_grants.sh`.
When adding a service database, update all three.

Accept a set only after `manifest.txt` is published and its roster/checksums match.
A lost fence, dump error or checksum failure leaves `manifest.incomplete` and
releases the remaining locks. Such a set has failed even if some dumps are readable.
The single-database helper remains useful for isolated drills but does not claim
a cross-service recovery point.

While writers remain stopped, record the matching Kafka topic/partition positions,
retained source range, consumer offsets, Temporal state and search/analytics
snapshot coordinates using the deployment's recovery system. Table fences cover
PostgreSQL only. Restart writers after these external recovery requirements are
satisfied; `--maintenance` cannot inspect or stop those services itself.

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
  /var/backups/socp/.../detect.dump restore_drill_detect
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
# Use exact counts for known tables; planner statistics are not restore evidence.
psql --dbname=restore_drill_detect --tuples-only --no-align -c \
  "select count(*) from flyway_schema_history;"

# Flyway must report the same latest version as the source (schema drift guard)
psql --dbname=restore_drill_detect -c \
  "select version, checksum from flyway_schema_history order by installed_rank desc limit 5;"
```

Compare `version` against the source; a mismatch means the dump and the application
image are from different schema levels — resolve it before the drill database is used
to make any release decision.

Restore the entire finalized database set before starting application writers.
Reconcile Detection source receipts/outbox IDs with Alert `source_alert_id`, Alert
delivery receipts with Incident/Notify/SOAR identities, and pending/DEAD rows with
the retained Kafka range. Resetting an offset alone cannot reconstruct a missing
downstream effect if a restored journal already says `PUBLISHED`. Use the tested
replay/reconciliation procedure before resuming; never mark missing effects
complete or delete journals to force recovery.

The disposable native PostgreSQL test checks blocked late writes and a matching
two-database restore, alongside manifest failure handling:

```bash
SOCP_TEST_POSTGRES_BIN=/path/to/postgresql/bin \
  python3 -m unittest discover -s build/tests -p 'test_backup_consistency.py' -v
```

It initializes a temporary cluster on loopback with a dynamically selected port,
removes inherited `PG*` connection settings, and stops it afterward. Without that
variable, isolated publication tests run and the live drill skips. This does not
replace a full product recovery drill involving Kafka and Temporal.

## Where the backup target mounts

`build/backup-postgres-all.sh` writes to a directory argument; that directory must be
outside the ephemeral container filesystem and on storage that is itself backed up.

* **docker-compose.prod**: mount a host or object-store-backed volume into the
  postgres (or a dedicated `socp-backup` sidecar) container, e.g.
  `volumes: [ "/var/backups/socp:/var/backups/socp" ]`, and run
  `backup-postgres-all.sh --maintenance /var/backups/socp/<stamp>` during a managed
  maintenance window.
* **Helm**: the chart deliberately does not ship a backup CronJob (durability is
  deployment-owned — [production readiness](../production-readiness.md) lists these
  drills as external evidence). Provide an environment-owned CronJob that runs
  `build/backup-postgres-all.sh` with `PGHOST` pointing at the in-cluster PostgreSQL
  Service and a `persistentVolumeClaim` (or object-store sync) as the backup directory.
  Orchestration must quiesce writers before acknowledging `--maintenance`; a bare
  periodic invocation is insufficient. Do not reuse the application runtime role: back up as an administrative role and keep
  the runtime secrets out of the backup namespace.

## Retention, RPO/RTO, and what is NOT provided

* **This repository provides**: per-database logical dump + SHA-256 sidecar, a
  maintenance-only roster backup with table fences and exported snapshots, a
  finalize-and-assert restore, and this playbook.
* **Not provided here (deployment-owned)**: WAL/point-in-time recovery, OpenSearch
  snapshot repository registration and restore, ClickHouse `BACKUP`/`RESTORE`
  (including `alarm_detail` merge semantics), Kafka consumer-offset backup and the
  DLQ redrive covered in [dlq-replay.md](dlq-replay.md), object-store versioning and key
  rotation, Temporal recovery coordination, and any **measured** RPO/RTO. Backup
  frequency alone does not establish product RPO: the newest usable recovery point
  must include compatible external state and retained replay inputs. Record real
  recovery results; RPO/RTO remain unproven until that full drill succeeds.
