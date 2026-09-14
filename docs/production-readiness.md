# Production delivery baseline

The repository now contains a reviewable deployment contract, but the local
Compose stack remains a single-node integration environment. A production
rollout is complete only after the deployment owner supplies the external
dependencies and records the evidence listed below.

## Application packaging

- Build JARs once and package them with `deploy/docker/Dockerfile.jvm`.
- Pass a verified Java 21 runtime image digest; mutable tags and `latest` are
  rejected by `build/verify-production.py`.
- Generate a CycloneDX SBOM and scan the image and dependencies before push.
  The AWS release workflow rejects critical vulnerabilities. Image signing
  and provenance attestation remain an additional policy gate and must not be
  claimed unless a release records that evidence.
- Inject secrets through the platform (`socp-runtime-secrets` in the
  Kubernetes baseline). Do not add a Secret manifest containing real values to
  Git.

The application pods expect `SOCP_PG_USER` and `SOCP_PG_PASSWORD` to contain
the restricted runtime role, never the PostgreSQL bootstrap account. The
Compose production overlay maps those values from
`SOCP_PG_RUNTIME_USER`/`SOCP_PG_RUNTIME_PASSWORD`; Flyway uses the separate
`SOCP_PG_MIGRATION_USER`/`SOCP_PG_MIGRATION_PASSWORD` account. The remaining
reference-deployment secret keys are `SOCP_SECURITY_SERVICE_SECRET`,
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
capacity policy without duplicating Deployment manifests. The infrastructure
role creates the restricted `socp-system` namespace from
`deploy/k8s/namespace.yaml`; Helm owns the namespaced application resources.

Search, Detection, and Alert readiness includes TCP reachability for required
Kafka/OpenSearch/ClickHouse/downstream-service endpoints through
`SOCP_HEALTH_REQUIRED_ENDPOINTS`. Liveness remains process-local so a
dependency outage removes a pod from traffic without creating a restart loop.

The database, Kafka, OpenSearch, ClickHouse, Redis, identity provider, and
object store are intentionally not bundled into this application baseline.
They need managed services or separately reviewed operators with their own
topology, replication, TLS, upgrade, and failure-domain policy.

## AWS delivery environment

`deploy/terraform/bootstrap` creates the state bucket and state KMS key with a
lifecycle separate from disposable application infrastructure.
`deploy/terraform/environments/dev` creates a two-AZ/one-NAT VPC, EKS managed
node group, four immutable ECR repositories, GitHub OIDC roles, API-backed EKS
access entries, encrypted control-plane logs, a Metrics Server add-on for HPA,
and a monthly budget. The environment is a cost-controlled reference
deployment, not a production HA topology. Its explicit limitations and
teardown procedure are documented in the
[AWS infrastructure runbook](../deploy/terraform/README.md) and
[ADR 008](adr/008-aws-delivery-environment.md).

Infrastructure planning, infrastructure mutation, and application release use
separate IAM roles. Same-repository pull requests can create a read-only plan;
fork pull requests cannot obtain AWS credentials. Apply and destroy run only
inside the protected `infrastructure` environment, use a saved plan, and
require an explicit confirmation token for destroy. The apply stage creates
the restricted namespace before handing control to the release role. The
release role has no VPC/IAM/EKS provisioning permission and its Kubernetes
access is limited to the `socp-system` namespace. Application services have no
AWS IAM permission by default.

The AWS release workflow authenticates with OIDC, builds the four JARs once,
creates and scans four images, records CycloneDX SBOMs, pushes immutable
source-SHA tags, resolves ECR digests, and deploys those digests with an atomic
Helm upgrade. Production promotion resolves existing images for an explicit
commit SHA and does not rebuild them.

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

Keep Maven/bounded-context boundaries for ownership and testing. The reviewed
runtime policy has no fixed process-count target. The logical domains in
`build/runtime-topology.json` describe ownership, not JVM colocation. Keep
Gateway, Search, Detection, Alert, reporting, AI, and event consumers
independently scalable where their load or failure profiles differ. Change
runtime placement only for one registered candidate after the
[ADR 007](adr/007-runtime-deployment-units.md) context, transaction, failure,
and capacity evidence passes. Size Kafka
partitions, PostgreSQL pools, ClickHouse parts, and OpenSearch shards from
measured load; do not infer production capacity from the single-node benchmark.
The standing-policy gate is `python build/verify-runtime-consolidation.py`.
Candidate evidence is checked independently with `--candidate NAME
--require-evidence`; there is no repository-wide aggregate promotion.

Real notification/SOAR connectors require vendor sandbox acceptance, timeout
and idempotency tests, credential rotation, and an operator approval policy
before they are enabled in the production profile.
