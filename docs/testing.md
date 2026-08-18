# Testing Guide

Testing is organized around the event pipeline and the boundaries where a
failure would be expensive to diagnose. Fast module tests cover local behavior;
Python checks exercise the running services and middleware path.

## Local checks

```bash
# Java reactor tests
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# Focused backend slice and required dependencies
bash build/mvnw.sh -pl services/api-gateway,services/alert-web,services/detect-web -am test

# Workbench API contracts, type check, and production artifact
cd frontend/apps/workbench
pnpm test
pnpm verify
```

`pnpm verify` runs the workbench type check and Vite build, then verifies the
expected production artifact structure. `pnpm test` runs the frontend API,
navigation, resource-list, and resource-import contract tests.

## Test ownership

- `socp-auth` and `api-gateway`: JWT configuration, production guard, missing
  credentials, viewer write denial, tenant propagation, and trace headers.
- `socp-error`: HTTP status preservation for controller-declared 4xx/5xx
  responses.
- `socp-rule` and `detect-web`: rule evaluation, suppression, hot reload,
  event deduplication, queue backpressure, malformed-event handling, and rule
  API bulk/reload contracts.
- `alert-web`: create validation, paged query contract, transactional Outbox
  creation, pending-event retry, alert disposition, and downstream fan-out
  isolation.
- `incident-web` and `soar-web`: case API validation, case merge/idempotency,
  and compensation or in-process fallback behavior.
- Resource services: import, create/update, validation, pagination, and
  tenant-scoped access for assets, cases, ATT&CK techniques, and IOCs.

The suite is risk-driven. It does not require a global coverage threshold; a
change should add or update tests at the owning boundary when behavior or
failure semantics change.

## Integration checks

Start the required Docker middleware and backend slice before running these:

```bash
python build/verify-slice.py       # gateway, auth, tenant, audit, and alert slice
python build/verify-pipeline.py    # Kafka -> detection -> PostgreSQL/OpenSearch/ClickHouse
python build/verify-full.py       # API, persistence, tenancy, auth, and tracing
python build/demos/golden-demo.py --transport ingest  # SSH brute force -> incident/SOAR
python build/demos/detection-recovery.py              # Detection down -> Kafka backlog -> recovery
python build/failure-tests.py      # dependency stop/restart and fallback paths
python build/validate-detection-content.py
python build/benchmark-pipeline.py --mode e2e --count 100 --batch-size 25 \
  --output .cache/benchmark-e2e.json
```

The pipeline check confirms canonical event acceptance, Kafka delivery,
detection, PostgreSQL alert persistence, OpenSearch indexing, ClickHouse
analytics, and report availability. The failure checks verify recovery behavior
for Kafka, OpenSearch, Temporal, and PostgreSQL.

The failure script is intentionally a manual/nightly check because it stops and
restarts local middleware containers. It must restore every dependency before
the run ends; it should not be used as a per-commit unit test.

The benchmark has two deliberately different scopes. The default `bulk` mode
measures the DETECT HTTP boundary. `--mode e2e` sends events through
`search-config -> Kafka -> detect-web -> alert-web` and waits for the expected
alert count; optional `BENCH_OS_URL` and `BENCH_CK_URL` environment variables
add OpenSearch/ClickHouse before-and-after counters. The JSON report contains
batch p50/p95/p99 latency, ingest throughput, alert wait/end-to-end latency,
acceptance, Kafka offsets when the `kafka-python` probe is installed, and the
observed end-to-end alert count. It is a repeatable single-node baseline, not a
production capacity claim.

Detection content is versioned in
`services/detect-web/src/main/resources/detection-content/manifest.json`.
Every content entry carries an owner, data-source and ATT&CK metadata plus a
positive and a negative vector. The Java contract test executes those vectors;
the Python validator provides a fast CI check before services are built.
The detection journal replay window defaults to 24 hours and is configurable
with `SOCP_DETECT_STATE_RETENTION`.

The Detection recovery demo stops and restarts only `detect-web` through
`build/run-all.sh`, verifies that Kafka retains the injected events, and waits
for the consumer group to catch up. It is also an operational/manual check;
run it only when the core event path is available.

See [validation-matrix.md](validation-matrix.md) for pass criteria, cadence,
and the single-node boundaries that the checks do not claim to cover.

## CI ownership

`.github/workflows/ci.yml` runs on pushes and pull requests to `main`. It builds
the Java reactor, runs the Java suite, verifies the workbench, and runs a
minimal service slice plus the Kafka pipeline E2E job.

`.github/workflows/full-stack.yml` runs manually and on the weekly schedule. It
starts the extended Compose profile, runs full API and pipeline checks, the
attack-scenario playground, and dependency failure injection, then uploads
diagnostic logs.
