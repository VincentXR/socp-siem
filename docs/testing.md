# Testing Guide

Testing is organized around the event pipeline and the boundaries where a
failure would be expensive to diagnose. Fast module tests cover local
behavior; Python checks exercise running services and middleware.

Choose checks with the [local change matrix](validation-matrix.md#local-change-validation).
The commands below are a catalog, not a sequence to run for every task. CI and
release gates retain their broader coverage.

## Test environments

Local build/test authorization is defined in
[AGENTS.md](../AGENTS.md#scope-and-autonomy).

Do not assume every test command is isolated: service probes accept endpoint
overrides, Compose can reuse persistent volumes, and chaos checks restart
services or mutate data. Inspect the selected command's target configuration
and use disposable local fixtures. Testcontainers is appropriate for isolated
middleware checks when Docker is available. Production/shared targets and
deletion of persistent data follow the authorization boundary in `AGENTS.md`.

## Local checks

```bash
# Java reactor tests
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# Local quality gate: coverage, repository contracts, SpotBugs, and frontend
bash build/quality-gate.sh

# Native Windows equivalent; both wrappers consume build/verify-repository.py
powershell -File build/quality-gate.ps1

# Owning backend module and its dependencies (replace with the changed module)
bash build/mvnw.sh -pl services/soar-web -am test -Dsurefire.failIfNoSpecifiedTests=false

# Workbench contracts, type check, production build
(
  cd frontend/apps/workbench
  pnpm test
  pnpm lint
  pnpm format:check
  pnpm verify
  pnpm test:e2e  # user-flow changes; full suite in CI
)

# OpenAPI snapshot -> TypeScript SDK generation and strict compilation
python build/verify-openapi-sdk.py

# Deployment-backed Actuator boundary (requires a running gateway)
python build/verify-actuator-auth.py

# Helm render and deployment invariants (requires Helm 4)
python build/verify-helm.py

# Workflow syntax, and the static production delivery contract (requires
# actionlint). Run it with no arguments so it lints every workflow, which is
# what CI does.
actionlint
python build/verify-production.py

# Production-shaped orchestration contract: asserts the *effective* base+overlay
# Compose merge (via `docker compose config` when the CLI is available), Redis
# noeviction/auth, the declared Kafka posture, and Helm probe/migration-role
# parity. It is the merged-config gate `verify-production.py` no longer
# duplicates. Compose files only; Docker is optional.
python build/verify-prod-compose.py
```

`pnpm verify` runs the workbench type check and Vite build, then verifies the
expected production artifact structure. `pnpm test` covers frontend API,
navigation, resource-list, and resource-import contracts, the URL-synced list
query composables (`useListQuery`, `useAlarmQuery`), and the keyboard row
activation cell (`RowActivate`) used by the detail-on-row-click tables.
`pnpm test:e2e`
uses Playwright to cover cookie-backed login, viewer navigation denial, deep
links, browser history, and the SOAR draft/publish, run-inspection,
approval, and manual-task browser flow (`e2e/soar.spec.ts`).

The browser flows install a catch-all network guard before their explicit
endpoint mocks. Any newly introduced gateway/service request that is not
handled by the test is aborted and fails the test, so mocked UI coverage does
not silently drift away from the API contract. To run the additional browser
smoke against a live gateway, set `SOCP_E2E_BACKEND_URL` (for example
`http://127.0.0.1:18092`) before `pnpm test:e2e`; the smoke accepts either a
200 authenticated session or the expected unauthenticated 401 response.

## Test ownership

- `socp-auth` and `api-gateway`: JWT configuration, production guard, missing
  credentials, collector identity/tenant binding, service-only endpoint
  denial, viewer write denial, tenant propagation, and trace headers.
- `socp-rule` and `detect-web`: rule evaluation, suppression, hot reload,
  routing keys, partition restore, event de-duplication, queue backpressure,
  malformed events, Detection Alert Outbox retry, typed dependency failure
  classification, same-session retry recovery, contiguous offset gaps,
  ownership fencing, and bounded PENDING replay.
- `search-config`: canonical event plus Ingestion Outbox creation, authenticated
  collector identity, optimistic publication claims, broker acknowledgement,
  stable OpenSearch document IDs, partial bulk failure,
  index-before-offset completion semantics, bounded local-cache warm-up, and
  PostgreSQL retention catch-up/locking semantics.
- `alert-web`: create validation, source-alert idempotency, paged query
  contracts, transactional Alert Outbox creation, broker-ack publishing,
  optimistic claim/stale recovery, post-commit enrichment scheduling, pending
  retry, disposition, and fan-out isolation.
- `incident-web` and `soar-web`: case validation, merge/idempotency, and
  Temporal or local fallback behavior.
- Resource services: import, create/update, validation, pagination, and
  tenant-scoped access for assets, cases, ATT&CK techniques, and IOCs.

The suite is risk-driven and enforces an aggregate Java line-coverage floor of
50%, a 25% floor for every production module, and explicit floors of 45% for
`detect-web`, `alert-web`, and `search-config`, plus 40% for `incident-web`,
`soar-web`, and `socp-client`. Every module containing production Java must
emit a coverage report. The floor is intentionally a baseline, not a target:
behavior or failure-semantic changes still need tests at their owning boundary.
Run `mvn test -Pcoverage` followed by `python build/verify-coverage.py` to
inspect the per-module and aggregate result. `mvn verify -Pquality -DskipTests`
enforces JDK/Maven policy and high-confidence SpotBugs findings.

`build/verify-migrations.py` rejects duplicate/misnamed migrations, version
gaps, unmarked destructive statements, missing Flyway wiring, and tenant JPA
entities without a `tenant_id` migration. It aggregates independent Flyway
locations owned by one service, such as Detection's secondary-analysis schema,
without merging their version histories. `build/verify-contracts.py` keeps the
Maven service modules, default process list, logical-domain assignment,
optional consolidation candidates, unique ports, gateway routes, legacy
collector rewrites, and frontend health registry aligned.

Changed-line coverage is fail-closed when CI supplies a non-zero base commit:
an invalid or unavailable base is an error, not a successful skip. An all-zero
initial-push base intentionally falls back to the current `HEAD` diff.

## Integration checks

Start the required Docker middleware and backend slice before running these:

```bash
python build/verify-slice.py
python build/verify-pipeline.py
python build/verify-full.py
python build/verify-openapi-sdk.py  # add runtime URL/env for live SDK smoke
python build/verify-soar-live.py  # requires PostgreSQL + Temporal; set a second SOAR URL for race evidence
python build/demos/golden-demo.py --transport ingest
python build/demos/detection-recovery.py
python build/chaos-pipeline.py --scenario alert_web_restart
python build/chaos-pipeline.py --scenario duplicate_delivery
python build/chaos-pipeline.py --scenario detection_outbox_replay
python build/failure-tests.py
python build/validate-detection-content.py

# Failure/recovery semantics touched by Detection persistence or consumer changes.
bash build/mvnw.sh -pl services/detect-web -am test \
  -Dtest=DetectionRecordProcessorTest,KafkaEventConsumerTest,DetectionDeadLetterJournalTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Real PostgreSQL syntax/transaction/locking evidence for search retention.
SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/search-config -am test \
  -Dtest=SearchConfigPostgresMigrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Fail-closed dependency-audit response fixtures (success, vulnerability, 401,
# 429, timeout, missing report, and partial report).
python3 -m unittest build/tests/test_verify_ossindex_audit.py
python build/verify-investigation-dataset.py
python build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json
```

The opt-in Testcontainers contract suite is in `platform/socp-test`, with the
Kafka-to-OpenSearch failure suite owned by `services/search-config`. Together
they prove the PostgreSQL uniqueness boundary, Kafka commit/replay semantics,
ClickHouse logical uniqueness under duplicate inserts, OpenSearch deterministic
IDs and partial bulk failure, DLQ acknowledgement boundaries, retryable 503,
and commit failure after write acknowledgement. Set `SOCP_TESTCONTAINERS=true`
when Docker is available; CI enables it, while local runs without Docker skip
only these integration tests. See [the failure matrix](chaos/README.md) for the
focused indexer command and reconciliation formula.

The middleware CI job also runs the Redis cross-replica revocation proof and
the PostgreSQL runtime-role/RLS proofs with `SOCP_TESTCONTAINERS=true`. The
repository quality-gate wrappers detect a usable Docker daemon and enable this
suite automatically; if Docker is unavailable they print an explicit warning
and run the hermetic tests only. Set `SOCP_TESTCONTAINERS=true` to require the
middleware suite (and fail if the Docker environment cannot satisfy it).

The middleware image catalog is [infra/middleware-images.env](../infra/middleware-images.env).
`bash build/compose.sh` passes it to Compose, and Testcontainers resolves
PostgreSQL, Kafka, OpenSearch, Redis, and ClickHouse from the same file. CI
starts its middleware through that wrapper as well; it does not maintain a
second set of service image tags. `build/verify-middleware-images.py` fails if
Compose, CI, probes, or tests bypass the catalog. The Kafka tests use the
Apache-compatible Testcontainers adapter, which reads the catalog's Apache
Kafka image in KRaft mode. The project pins Testcontainers 1.21.4 because the
Apache adapter is not available in the old 1.20.x line.

To upgrade a middleware image, change only the corresponding entry in the
catalog, then run the catalog gate and the Docker-backed tests. On Windows,
use `build/compose.ps1` for the same behavior. The wrappers clear inherited
`SOCP_*_IMAGE` variables because Compose otherwise lets a stale shell export
override `--env-file` and recreate version drift.

The full-stack job runs `build/verify-actuator-auth.py` after all services are
started. It treats a dependency-driven health `503` as valid, but fails if an
unauthenticated caller can read Actuator info, metrics, or route metadata.

The pipeline check confirms canonical event acceptance, Kafka delivery,
Detection, PostgreSQL alert persistence, OpenSearch indexing, ClickHouse
analytics, and report availability. The failure checks verify dependency
recovery. The Alert Web restart scenario specifically proves that a Detection
Alert Outbox row survives a downstream outage.

When local Compose ports differ from defaults, set `PIPELINE_OS` for
`verify-pipeline.py` and `FAILURE_OS_URL` for `failure-tests.py`. Both accept
the corresponding `*_OS_AUTH` variable. This keeps failure checks aligned
with the active Compose port mapping rather than a hard-coded host port.

Detection content is versioned in
`services/detect-web/src/main/resources/detection-content/manifest.json`.
Every entry carries owner, data-source, ATT&CK, positive, and negative
metadata. The Java contract test executes the vectors; the Python validator
provides a fast CI check. The state journal replay window defaults to 24 hours
and is configurable with `SOCP_DETECT_STATE_RETENTION`. Terminal cleanup uses
independent `SOCP_DETECT_STATE_COMPLETED_RETENTION` and
`SOCP_DETECT_STATE_DEAD_LETTER_RETENTION` clocks; pending rows are retained.

For cross-dimension multi-instance validation, run the Detection cluster on
the routed topic, not the legacy canonical topic:

```bash
SOCP_DETECT_INPUT_TOPIC=socp-detection-routed-v2 \
SOCP_DETECT_ROUTING_MODE=primary \
SOCP_DETECT_OUTPUT_MODE=primary \
SOCP_DETECT_ROUTING_PUBLISHER_ENABLED=true \
SOCP_DETECT_CLUSTER_MIN_PARTITIONS=6 \
bash build/detection-cluster.sh start

RECOVERY_TOPIC=socp-detection-routed-v2 \
python build/chaos-pipeline.py --scenario multi_instance --rebalance-cycles 3
```

The acceptance topology is exactly three Detection instances and six routed
partitions. The scenario uses an independent expected-alert oracle, exercises
same-user state across different IP/host values and tenant isolation, and
checks canonical source receipts, routed delivery identities, source/delivery
positions, bounded fan-out, repeated rebalance, zero lag and zero pending
journal work. See the [validation matrix](validation-matrix.md) for the full
pass criteria.

Two further routed-migration scenarios run against the same cluster:

```bash
python build/chaos-pipeline.py --scenario routed_migration
python build/chaos-pipeline.py --scenario routing_rollback
```

`routed_migration` republishes completed business events at new canonical
offsets (more source receipts, unchanged fan-out delivery identities and no
duplicate alert),
then verifies activation of an incompatible stateful rule returns 409 without
changing its persisted specification. In the disposable Compose database, it
administratively injects that probe rule as ACTIVE with a `routingField` that
contradicts its `groupBy`, modeling a corrupt restore or an out-of-band write.
It proves the router still fails closed: `/routing-plan` reports the rule
as UNSUPPORTED with its reason, canonical offsets stay uncommitted, no alert is
pretended, and deleting the rule lets the deferred work drain to the correct
oracle. `routing_rollback` restarts the cluster on the legacy canonical input,
proves the formal alert path survives there while the deployment reports
`LEGACY_PARTIAL` (never cross-dimension completeness), and restores the routed
generation afterwards.

Chaos event injection is a data-plane operation. Set
`PIPELINE_COLLECTOR_ID`, `PIPELINE_COLLECTOR_TOKEN`, and
`PIPELINE_INGEST_URL` to use a registered collector; do not reuse a user JWT
for `/search-config/api/v1/ingest`.

The full API verifier reads case timelines through their paginated endpoint.
It publishes an isolated START-to-END SOAR playbook and an automation rule
matching only its probe entity before ingesting the alert. It checks the
service-signed alert reaches that version and completes through Temporal,
then deletes the trigger rule and archives the playbook while preserving run
evidence. No external response action is part of this fixture.

## CI ownership

`.github/workflows/ci.yml` runs on pushes and pull requests to `main`, and
manually. It builds the Java reactor, enables the Testcontainers contract
suite, verifies the workbench, and runs a minimal service slice plus the Kafka
pipeline E2E job. Every Change CI trigger runs deterministic duplicate-delivery
and Detection Outbox replay evidence. Compose-dependent process/database/
OpenSearch outage checks are intentionally kept in the weekly full-stack job,
where the named services and volumes exist. Repository-level Python contracts
come from `build/verify-repository.py`, the same manifest used by both local
quality-gate wrappers.

`.github/workflows/full-stack.yml` runs manually and on the weekly schedule.
It starts the extended Compose profile and fixed three-instance Detection
cluster, runs full API and pipeline checks, the dependency-outage matrix, the
multi-instance/rebalance oracle, attack scenarios, and recovery demos, then
uploads diagnostic logs plus structured JSON evidence.

`.github/workflows/dependency-audit.yml` runs weekly or manually. It applies
OWASP Dependency-Check to the Java reactor and `pnpm audit` to the workbench;
pull requests also use GitHub dependency review to reject newly introduced
high-severity vulnerabilities.
