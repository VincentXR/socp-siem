# Testing Guide

Testing is organized around the event pipeline and the boundaries where a
failure would be expensive to diagnose. Fast module tests cover local
behavior; Python checks exercise running services and middleware.

## Local checks

```bash
# Java reactor tests
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# Complete quality gate: coverage, SpotBugs, migrations, contracts, and frontend
bash build/quality-gate.sh

# Focused backend slice
bash build/mvnw.sh -pl services/api-gateway,services/alert-web,services/detect-web -am test

# Workbench contracts, type check, production build
cd frontend/apps/workbench
pnpm test
pnpm test:e2e
pnpm verify
```

`pnpm verify` runs the workbench type check and Vite build, then verifies the
expected production artifact structure. `pnpm test` covers frontend API,
navigation, resource-list, and resource-import contracts. `pnpm test:e2e`
uses Playwright to cover cookie-backed login, viewer navigation denial, deep
links, and browser history.

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
  malformed events, Detection Alert Outbox retry, and rule API contracts.
- `search-config`: canonical event plus Ingestion Outbox creation, authenticated
  collector identity, optimistic publication claims, broker acknowledgement,
  stable OpenSearch document IDs, partial bulk failure, and
  index-before-offset completion semantics.
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
entities without a `tenant_id` migration. `build/verify-contracts.py` keeps the
Maven service modules, default process list, target runtime-unit assignment,
unique ports, gateway routes,
legacy collector rewrites, and frontend health registry aligned.

## Integration checks

Start the required Docker middleware and backend slice before running these:

```bash
python build/verify-slice.py
python build/verify-pipeline.py
python build/verify-full.py
python build/demos/golden-demo.py --transport ingest
python build/demos/detection-recovery.py
python build/chaos-pipeline.py --scenario alert_web_restart
python build/chaos-pipeline.py --scenario duplicate_delivery
python build/chaos-pipeline.py --scenario detection_outbox_replay
python build/failure-tests.py
python build/validate-detection-content.py
python build/verify-investigation-dataset.py
python build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json
python build/benchmark-pipeline.py --mode e2e --profile realistic --count 100 --batch-size 25 \
  --output .cache/benchmark/e2e-100.json
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

The pipeline check confirms canonical event acceptance, Kafka delivery,
Detection, PostgreSQL alert persistence, OpenSearch indexing, ClickHouse
analytics, and report availability. The failure checks verify dependency
recovery. The Alert Web restart scenario specifically proves that a Detection
Alert Outbox row survives a downstream outage.

The benchmark has two scopes. `bulk` measures the Detection HTTP boundary;
`--mode e2e` sends events through `search-config -> Kafka -> detect-web ->
alert-web` and waits for the expected run-scoped alerts. Use
`--profile realistic` for a low hit-rate mixed workload or
`--profile alert-heavy` to stress the durable alert path. It records batch
request P50/P95/P99, ingress throughput,
T0-T8 event/alert stage histograms, explicitly scoped transaction ratios, the
durable `Alert Web createdAt - triggerIngestedAt` latency, and Kafka offsets
when `kafka-python` is installed. `BENCH_DETECTION_URLS` enables aggregation
across all Detection instances. `--offered-eps` plus `--duration` runs the
steady-state lag check. See the [benchmark contract](benchmark/README.md).
E2E runs require the direct collector boundary variables `BENCH_INGEST_URL`,
`BENCH_COLLECTOR_ID`, and `BENCH_COLLECTOR_TOKEN`; a user JWT is never reused as
a collector credential.

For repeated baseline evidence, use `build/benchmark-series.py`; it runs the
same workload once as an excluded warm-up and then three to five measured
times, writes per-run reports and process logs, and fails unless every round
uses one commit, throughput remains within the configured degradation
tolerance, and no sustained monotonic decline is present. If a single round aborts, `build/benchmark-pipeline.py` writes a
`.failed.json` sidecar containing the exception and traceback instead of
silently losing the evidence.

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

For multi-instance validation, use `bash build/detection-cluster.sh start`; it
starts exactly three Detection instances against the shared PostgreSQL/Kafka
group. Then run `python build/chaos-pipeline.py --scenario multi_instance`.
See the [validation matrix](validation-matrix.md) for pass criteria and
explicit limits.

Chaos event injection is a data-plane operation. Set
`PIPELINE_COLLECTOR_ID`, `PIPELINE_COLLECTOR_TOKEN`, and
`PIPELINE_INGEST_URL` to use a registered collector; do not reuse a user JWT
for `/search-config/api/v1/ingest`.

## CI ownership

`.github/workflows/ci.yml` runs on pushes and pull requests to `main`, nightly,
and manually. It builds the Java reactor, enables the Testcontainers contract
suite, verifies the workbench, and runs a minimal service slice plus the Kafka
pipeline E2E job. Nightly/manual runs add deterministic duplicate-delivery and
Detection Outbox replay evidence. Compose-dependent process/database/
OpenSearch outage checks are intentionally kept in the weekly full-stack job,
where the named services and volumes exist.

`.github/workflows/full-stack.yml` runs manually and on the weekly schedule.
It starts the extended Compose profile and fixed three-instance Detection
cluster, runs full API and pipeline checks, the dependency-outage matrix, the
multi-instance/rebalance oracle, attack scenarios, and recovery demos, then
uploads diagnostic logs plus structured JSON evidence.

`.github/workflows/dependency-audit.yml` runs weekly or manually. It applies
OWASP Dependency-Check to the Java reactor and `pnpm audit` to the workbench;
pull requests also use GitHub dependency review to reject newly introduced
high-severity vulnerabilities.
