# Kubernetes release contract

`deploy/helm/socp-core` is the canonical application release for the core
event path. Raw application Deployments and environment overlays are not kept
under `deploy/k8s`, preventing Kustomize and Helm from drifting into two
independent release definitions.

The deployment platform first applies `deploy/k8s/namespace.yaml`. The
namespace uses the Kubernetes Restricted Pod Security Standard. A
namespace-scoped deployment identity then installs the Helm chart; it should
not require cluster-scoped mutation for the application release.

## Runtime topology

Four immutable image artifacts produce six workloads:

- `api-gateway`;
- `search-config-api` and `search-config-worker` from `search-config`;
- `detect-web-api` and `detect-web-worker` from `detect-web`;
- `alert-web`.

The gateway routes to the API workloads. Search and Detection workers retain
independent scaling and failure boundaries while sharing their service image.
Detection instance identity comes from the Kubernetes pod UID, so replicas do
not share a fixed ownership identifier.

Every Deployment carries `socp.io/runtime-domain`, validated against
`build/runtime-topology.json`. The label describes ownership and
observability grouping only; it is not part of the immutable Deployment
selector.

## Values and secrets

The chart accepts image repository and digest as separate values and renders
only `repository@sha256:...`. Its JSON schema rejects missing or malformed
digests. Environment files control replica/HPA/PDB policy without duplicating
the workload manifests.

`socp-runtime-secrets` is external to Helm. The deployment platform must
create it before release and rotate it independently. Non-secret dependency
endpoints can be supplied through an environment-owned values file under
`runtime.extraConfig`. Neither generated secrets nor environment values belong
in source control.

Default egress is limited to cluster DNS, same-namespace service HTTP, and the
declared data-service namespace. Gateway ingress defaults to the
`ingress-nginx` namespace, while metrics ingress defaults to `monitoring`.
Managed dependencies, external identity providers, or different ingress and
monitoring namespaces require explicit environment-owned network policy
values; the chart does not silently permit all external egress.

## Availability and security

The chart includes rolling updates, dependency-aware readiness probes,
process-local liveness probes, startup probes, resource requests/limits,
read-only root filesystems, non-root UID/GID, an explicit writable volume
group, RuntimeDefault seccomp, dropped Linux capabilities, disabled
service-account token mounts, preferred
zone-aware pod anti-affinity, HPA, PDB, and default-deny network policy.

The PrometheusRule is optional because the CRD belongs to the observability
platform. Enable it only after the Prometheus Operator CRDs are installed.
External data services and ingress controllers remain platform dependencies.

## Rollout and rollback

Deployment automation should use `helm upgrade --install --atomic --wait` and
wait for all six Deployments. A failed upgrade rolls back automatically.
Operators can inspect and restore a prior successful revision with:

```bash
helm -n socp-system history socp-core
helm -n socp-system rollback socp-core <revision> --wait
```

Run `python build/verify-helm.py` to lint and render all three capacity
profiles with non-routable CI images and verify the resulting security and
topology contracts.

## Schema compatibility window and migration ordering

Migrations are executed by **Flyway inside the application process** on boot
(`spring.flyway.*` in each service's `application-pg.yml`), under the dedicated
migration role. Five workloads across four service databases carry a migration
role (api-gateway does not); two of them (`search-config`, `detect-web`) share one
image and one database across their `api`/`worker` pair, so the concurrent boot-time
`migrate` on the same database is absorbed by the Flyway lock. That means the
**new** versions of those pods self-order their schema.

The rollout shape is the risk, not concurrency. Because the chart uses
`maxUnavailable: 0` / `maxSurge: 1`, a new pod and the old pod it replaces
**necessarily coexist** while the surge starts. The new pod front-rolls the schema
before the old pod has stopped serving. Every migration must therefore be safe for
that overlap — this is the expand-then-contract discipline:

- **Expand (this release):** additive, backward-compatible DDL only — `CREATE TABLE`,
  `ALTER TABLE ADD COLUMN` (nullable, or NOT NULL with a backfill in a separate step),
  `CREATE INDEX`. Old code ignores the new column.
- **Contract (a *later* release, after every old pod is gone):** the widen/`SET NOT
  NULL`/drop class. Never in the same release that ships the code depending on it.

The `expand` phase can be one release; the `contract` phase is a subsequent release.
Between them the database holds a superset both versions tolerate.

**`SET NOT NULL` compatibility.** The repository has no `DROP COLUMN`,
`RENAME`, `DROP TABLE`, `TRUNCATE`, or `ADD COLUMN NOT NULL` without a default — but
`build/verify-migrations.py` only blocks the database/table-level destructive set; it
does **not** gate the 27 existing `SET NOT NULL` statements. Those succeed only because
older code never wrote NULL to those columns. A new `SET NOT NULL` against a hot column
also takes a lock while in-flight writes contend with it, and Flyway runs it inside the
boot transaction. Two consequences:

- A widening `ALTER COLUMN ... TYPE` or a `SET NOT NULL` on a hot column is a **lock
  window**, budgeted per release. Prefer running it while the affected writer is drained,
  or accept the brief contention; set `lock_timeout` (the migration role session default)
  so a blocked DDL gives up instead of queueing behind a long transaction and stalling
  pod readiness.
- PostgreSQL `CREATE INDEX CONCURRENTLY` cannot run inside a transaction; Flyway's
  default executor wraps each migration in one, so a `CONCURRENTLY` DDL fails. Until the
  migration executor is changed to run such statements outside a transaction, index builds
  on hot tables take a plain build lock — treat that as a planned maintenance window, not a
  zero-downtime change.

### Rollback constraints

`helm rollback` moves the **image** back but does **not** move the schema back — Flyway
is forward-only and a published migration version is immutable
([Flyway repair runbook](database-migration-repair.md)). Before any rollback:

1. Compare the target image's supported Flyway version range against
   `select max(version) from flyway_schema_history` in the affected database.
2. If the current schema version is **outside** the target image's range, a rollback
   leaves the old code facing schema it never created. Only **roll forward** a newer
   migration; do not downgrade the image across a schema it cannot read.
3. A contract-phase `SET NOT NULL` makes rolling back to a pre-expand image unsafe for
   any path that could insert NULL: for alert-web `V20` (`source_alert_id NOT NULL`), the
   automated detection->alert path always carries `sourceAlertId` and is unaffected, but
   keyless manual/legacy/compat-API alert creation and keyless third-party producers would
   fail to insert. Account for that before rolling back past the expand.

The repository does not mechanically prove rollback compatibility for shrinking
DDL such as `SET NOT NULL`, column rename, or column removal. Migration execution
also remains application-boot based; the images do not provide a migrate-and-exit
entrypoint for a Helm pre-upgrade Job. Release review must therefore enforce the
expand-then-contract window and verify the target image's supported schema range
before rollback.
