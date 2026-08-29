# Production delivery baseline

The repository now contains a reviewable deployment contract, but the local
Compose stack remains a single-node integration environment. A production
rollout is complete only after the deployment owner supplies the external
dependencies and records the evidence listed below.

## Application packaging

- Build JARs once and package them with `deploy/docker/Dockerfile.jvm`.
- Pass a verified Java 21 runtime image digest; mutable tags and `latest` are
  rejected by `build/verify-production.py`.
- Generate an SPDX or CycloneDX SBOM, scan the image and dependencies
  (`grype` or the registry scanner), sign the image (`cosign sign`), and verify
  the signature before a Kubernetes rollout.
- Inject secrets through the platform (`socp-runtime-secrets` in the
  Kubernetes baseline). Do not add a Secret manifest containing real values to
  Git.

The reference deployments expect the secret keys `SOCP_SECURITY_SERVICE_SECRET`,
`SOCP_SECURITY_METRICS_TOKEN`, `SOCP_SECURITY_ISSUER_URI`,
`SOCP_SECURITY_JWK_SET_URI`, `SOCP_LOGIN_SECRET`, `SOCP_PG_USER`,
`SOCP_PG_PASSWORD`, `SOCP_OPENSEARCH_USERNAME`, `SOCP_OPENSEARCH_PASSWORD`,
`SOCP_CK_USER`, `SOCP_CK_PASSWORD`, `SOCP_COLLECTOR_CREDENTIALS`,
`SOCP_INGEST_TOKEN`, and `SOCP_VECTOR_TOKEN` where the corresponding service
uses them. Secret keys are intentionally not populated in Git.

## Kubernetes rollout

`deploy/k8s/base` is a minimal reference for the high-throughput event path:
two Gateway replicas, three Detection replicas, and two Alert replicas. It
sets rolling-update behavior, readiness/liveness/startup probes, resource
requests and limits, a non-root/read-only container policy, and disruption
budgets. Replace each `REPLACE_WITH_RELEASE_DIGEST` token during release
rendering, then run `kubectl apply --server-side` and wait for rollout status.

The database, Kafka, OpenSearch, ClickHouse, Redis, identity provider, and
object store are intentionally not bundled into this application baseline.
They need managed services or separately reviewed operators with their own
topology, replication, TLS, upgrade, and failure-domain policy.

## Backup, restore, and recovery evidence

`build/backup-postgres.sh <directory>` creates a mode-0600 custom-format dump
and a SHA-256 sidecar. A production runbook must additionally record:

1. PostgreSQL point-in-time/WAL retention and a successful restore into an
   isolated database;
2. Kafka topic configuration, replication factor, consumer offset backup and
   re-drive procedure;
3. OpenSearch snapshot repository and restore drill;
4. ClickHouse backup/restore (including `alarm_detail` merge semantics);
5. object-store versioning/retention and key rotation;
6. RTO/RPO, SLOs, alert thresholds, and the measured failure-domain recovery
   time.

These drills are deployment-owned evidence. The repository's benchmark and
chaos scripts validate application invariants and do not claim capacity, HA,
or disaster recovery by themselves.

## Capacity and service grouping

Keep Maven/bounded-context boundaries for ownership and testing, but deploy
thin control planes in reviewed groups (typically 5–7 JVMs) and keep Gateway,
Search, Detection, Alert, and reporting/event consumers independently
scalable. Size Kafka partitions, PostgreSQL pools, ClickHouse parts, and
OpenSearch shards from measured load; do not infer production capacity from
the single-node benchmark.

Real notification/SOAR connectors require vendor sandbox acceptance, timeout
and idempotency tests, credential rotation, and an operator approval policy
before they are enabled in the production profile.
