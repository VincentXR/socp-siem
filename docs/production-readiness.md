# Production delivery baseline

The repository contains a deployment contract, but the local
Compose stack remains a single-node integration environment. A production
rollout is complete only after the deployment owner supplies the external
dependencies and records the evidence listed below.

## Service readiness matrix

Readiness is recorded by dimension. A green local or CI correctness result is
not a production HA, capacity, or external-integration claim.

| Service group | Correctness evidence | Security boundary | Operational readiness | External acceptance |
|---|---|---|---|---|
| Gateway/auth | Unit + browser flows | JWT/JWKS, CSRF-origin checks, distributed login/service-token limits, security headers | Redis-backed limits and production fail-fast | IdP, proxy trust, certificates, and secret rotation |
| Ingest/search | Pipeline and duplicate/recovery chaos | Collector identity binds source and tenant; validation follows authentication | Durable Ingestion Outbox and Kafka/OpenSearch recovery | Collector inventory, sizing, and backups |
| Detection | Rule vectors, journal/outbox tests, rebalance oracle | Owning service rechecks mutation and service identity | Durable journal/outbox, partition ownership, lag evidence | Capacity/SLO and broker/database HA |
| Alert/incident | Idempotency, timeline, and downstream receipt tests | Tenant-scoped queries and negative authorization tests | Durable fan-out receipts, stale-claim recovery, replay | ClickHouse retention, notification vendors, case workflow acceptance |
| SOAR/notify | Action/dispatch and approval tests | Service-only boundaries; production rejects simulation | Durable execution and dispatch state | Real connector/vendor certification |
| Assets/HIPS/threat/ATT&CK | CRUD/import/tenant tests | Role-gated mutation and ingest identity | PostgreSQL-backed state | CMDB, agents, feeds, and content lifecycle |
| Reports | Query/archive tests | Tenant-scoped report paths | ClickHouse and object-store adapters | Retention, object lock, restore, and report SLO |
| AI assistant | Versioned dataset exercises evidence composition and human approval | Evidence is untrusted; no automatic containment | Deterministic fallback and bounded tools/timeouts | Model quality, privacy, and safety acceptance |

`correctness evidence` means the repository has an executable oracle for the
named invariant, not that every workload or failure has been explored.
`operational readiness` covers repository-owned state, recovery, and
observability. Multi-zone failover, capacity, backup/restore, SLO burn rates,
and third-party acceptance remain deployment-owned. Until those dimensions
close, a service is integration-ready or preview rather than generally
production-ready.

The authoritative commands and cadence live in
[validation-matrix.md](validation-matrix.md). Evidence is commit-scoped; a
successful workflow from an older revision is not proof for HEAD.

The Compose MinIO fixture is built from upstream commit
`9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a` (the October 2025 security release),
using the image catalog's build/runtime bases. This replaces the unavailable
Docker Hub community image and its September server binary, which predates
[GHSA-jjjj-jwhf-8rgr](https://github.com/minio/minio/security/advisories/GHSA-jjjj-jwhf-8rgr).
Compose builds this local image automatically; the first start therefore needs
GitHub and Go module access. The source revision and original license/notice are
retained in the image. This is an integration fixture, not a claim of ongoing
vendor security support: the [upstream community repository](https://github.com/minio/minio)
is archived. Production object storage still requires a supported provider or
an explicitly owned patch/scan/rebuild policy, plus retention and restore tests.

## Application packaging

- Build JARs once and package them with `deploy/docker/Dockerfile.jvm`.
- Pass a verified Java 21 runtime image digest; mutable tags and `latest` are
  rejected by `build/verify-production.py`.
- Generate a CycloneDX SBOM and scan images and dependencies before publishing
  a release. Image signing and provenance attestation are deployment-policy
  gates and must not be claimed unless the release records that evidence.
- Inject secrets through the platform (`socp-runtime-secrets` in the
  Kubernetes baseline). Do not add a Secret manifest containing real values to
  Git.

The application pods expect `SOCP_PG_USER` and `SOCP_PG_PASSWORD` to contain
the restricted runtime role, never the PostgreSQL bootstrap account. Flyway
uses the separate `SOCP_PG_MIGRATION_USER`/`SOCP_PG_MIGRATION_PASSWORD`
account. These four PostgreSQL role keys are part of the reference-deployment
secret contract and are always required without a fallback: the Compose
production overlay maps the runtime pair from
`SOCP_PG_RUNTIME_USER`/`SOCP_PG_RUNTIME_PASSWORD` and passes all four through
`${VAR:?}`, and the Helm baseline pins each of the four per database workload
through `secretEnv`. `application-pg.yml` no longer supplies nested
`${SOCP_PG_MIGRATION_USER:${SOCP_PG_USER:...}}` defaults, so a missing
migration role fails the Pod at startup instead of silently running DDL as the
runtime role. `build/verify-production.py` and `build/verify-prod-compose.py`
assert this. The remaining reference-deployment secret keys are
`SOCP_SECURITY_SERVICE_SECRET`,
`SOCP_SECURITY_METRICS_TOKEN`, `SOCP_SECURITY_ISSUER_URI`,
`SOCP_SECURITY_JWK_SET_URI`, `SOCP_SECURITY_AUDIENCE`, `SOCP_LOGIN_SECRET`,
`SOCP_OPENSEARCH_USERNAME`, `SOCP_OPENSEARCH_PASSWORD`, `SOCP_CK_USER`,
`SOCP_CK_PASSWORD`, `SOCP_COLLECTOR_CREDENTIALS`, `SOCP_INGEST_TOKEN`, and
`SOCP_VECTOR_TOKEN` where the corresponding service uses them. Secret keys
use these exact environment-variable names because the chart imports the
external Secret with `envFrom`; they are intentionally not populated in Git.

For local Compose, `SOCP_PG_BOOTSTRAP_PASSWORD` is the administrator password
used only by PostgreSQL initialization. If it is omitted, the legacy
`SOCP_PG_PASSWORD` value is used as the local fallback; production must set
all four explicit role variables and must not reuse the bootstrap secret.

In production, user JWTs are trusted by business services only when the API
Gateway adds a short-lived, nonce-protected HMAC proof bound to the original
HTTP method, path, tenant, and token digest. Set the same
`SOCP_SECURITY_SERVICE_SECRET` on the Gateway and every protected service;
`SOCP_SECURITY_REQUIRE_GATEWAY=true` is enabled by the production Compose and
Kubernetes baselines. Internal service-token calls remain separately signed,
and direct user-JWT calls to a business service are rejected when the proof is
missing, expired, or replayed.

## Kubernetes rollout

`deploy/helm/socp-core` is the single application release definition for the
core event path. Four digest-addressed images render six independently
scalable workloads. Environment values select fixed dev replicas or HPA/PDB
capacity policy without duplicating Deployment manifests. The deployment
platform creates the restricted `socp-system` namespace from
`deploy/k8s/namespace.yaml`; Helm owns the namespaced application resources.

Startup and liveness target the process-local `liveness` health group, not the
aggregated `/actuator/health` endpoint: a dependency outage must not stop a
brand-new container from ever passing startup and get restart-looped, which
would churn the shared consumer group the running replicas depend on. Only
readiness is dependency-aware. Search, Detection, and Alert readiness adds TCP
reachability for required Kafka/OpenSearch/ClickHouse endpoints through
`SOCP_HEALTH_REQUIRED_ENDPOINTS`, limited to synchronous dependencies the
request path actually calls — the Detection API does not declare Alert as a
readiness dependency because no request path calls it. A dependency failure
therefore removes a replica from traffic via readiness while the process stays
alive. Because the readiness group also carries the Spring `db` contributor,
which borrows a pooled connection, the chart pins
`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` (2000 ms) well below the
readiness `timeoutSeconds` (5 s) so a saturated pool surfaces as a DOWN signal
rather than a probe timeout; `build/verify-helm.py` and
`build/verify-prod-compose.py` lock both the probe targets and that budget.

The database, Kafka, OpenSearch, ClickHouse, Redis, identity provider, and
object store are intentionally not bundled into this application baseline.
They need managed services or separately reviewed operators with their own
topology, replication, TLS, upgrade, and failure-domain policy.

Any Redis instance the platform points at carries correctness keys — signed
service-request replay nonces and the session revocation list — so it must be
configured with `--maxmemory-policy noeviction` and separately capacity
planned. Under an evicting policy such as `allkeys-lru`, an evicted nonce or
revocation key makes `SETNX` succeed again and a logged-out session usable
until its JWT expires; that is silent and unalarmed, not a connection failure.
The production-shaped Compose rehearsal enforces `noeviction` plus
`--requirepass`; the Kubernetes baseline delegates Redis to a managed
operator that must provide the same `noeviction` guarantee.

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

These drills are deployment-owned evidence. The repository's chaos scripts
validate application invariants and do not claim capacity, HA, or disaster
recovery by themselves.

## Capacity and service grouping

Keep Maven/bounded-context boundaries for ownership and testing. The reviewed
runtime policy has no fixed process-count target. The logical domains in
`build/runtime-topology.json` describe ownership, not JVM colocation. Keep
Gateway, Search, Detection, Alert, reporting, AI, and event consumers
independently scalable where their load or failure profiles differ. Change
runtime placement only for one registered candidate after the
[ADR 007](adr/007-runtime-deployment-units.md) context, transaction, failure,
and capacity evidence passes. Size Kafka
partitions, PostgreSQL pools, ClickHouse parts, and OpenSearch shards from
measured load; do not infer production capacity from a local run.
The standing-policy gate is `python build/verify-runtime-consolidation.py`.
Candidate evidence is checked independently with `--candidate NAME
--require-evidence`; there is no repository-wide aggregate promotion.

Real notification/SOAR connectors require vendor sandbox acceptance, timeout
and idempotency tests, credential rotation, and an operator approval policy
before they are enabled in the production profile.
