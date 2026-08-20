# Testing Guide

Testing is organized around the event pipeline and the boundaries where a
failure would be expensive to diagnose. Fast module tests cover local
behavior; Python checks exercise running services and middleware.

## Local checks

```bash
# Java reactor tests
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# Focused backend slice
bash build/mvnw.sh -pl services/api-gateway,services/alert-web,services/detect-web -am test

# Workbench contracts, type check, production build
cd frontend/apps/workbench
pnpm test
pnpm verify
```

`pnpm verify` runs the workbench type check and Vite build, then verifies the
expected production artifact structure. `pnpm test` covers frontend API,
navigation, resource-list, and resource-import contracts.

## Test ownership

- `socp-auth` and `api-gateway`: JWT configuration, production guard, missing
  credentials, viewer write denial, tenant propagation, and trace headers.
- `socp-rule` and `detect-web`: rule evaluation, suppression, hot reload,
  routing keys, partition restore, event de-duplication, queue backpressure,
  malformed events, Detection Alert Outbox retry, and rule API contracts.
- `search-config`: canonical event plus Ingestion Outbox creation, optimistic
  publication claims, broker acknowledgement, stable OpenSearch document IDs,
  partial bulk failure, and index-before-offset completion semantics.
- `alert-web`: create validation, source-alert idempotency, paged query
  contracts, transactional Alert Outbox creation, broker-ack publishing,
  optimistic claim/stale recovery, post-commit enrichment scheduling, pending
  retry, disposition, and fan-out isolation.
- `incident-web` and `soar-web`: case validation, merge/idempotency, and
  Temporal or local fallback behavior.
- Resource services: import, create/update, validation, pagination, and
  tenant-scoped access for assets, cases, ATT&CK techniques, and IOCs.

The suite is risk-driven. There is no global coverage threshold; a behavior or
failure-semantic change should add or update a test at its owning boundary.

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
python build/failure-tests.py
python build/validate-detection-content.py
python build/benchmark-pipeline.py --mode e2e --profile realistic --count 100 --batch-size 25 \
  --output .cache/benchmark/e2e-100.json
```

The pipeline check confirms canonical event acceptance, Kafka delivery,
Detection, PostgreSQL alert persistence, OpenSearch indexing, ClickHouse
analytics, and report availability. The failure checks verify dependency
recovery. The Alert Web restart scenario specifically proves that a Detection
Alert Outbox row survives a downstream outage.

The benchmark has two scopes. `bulk` measures the Detection HTTP boundary;
`--mode e2e` sends events through `search-config -> Kafka -> detect-web ->
alert-web` and waits for the expected run-scoped alerts. Use
`--profile realistic` for a
low hit-rate mixed workload or `--profile alert-heavy` to stress the durable
alert path. It records batch request P50/P95/P99, ingress throughput,
T0-T8 event/alert stage histograms, explicitly scoped transaction ratios, the
durable `Alert Web createdAt - triggerIngestedAt` latency, and Kafka offsets
when `kafka-python` is installed. `BENCH_DETECTION_URLS` enables aggregation
across all Detection instances. `--offered-eps` plus `--duration` runs the
steady-state lag check. See the [benchmark contract](benchmark/README.md).

When local Compose ports differ from defaults, set `PIPELINE_OS` for
`verify-pipeline.py` and `FAILURE_OS_URL` for `failure-tests.py`. Both accept
the corresponding `*_OS_AUTH` variable. This keeps failure checks aligned
with the active Compose port mapping rather than a hard-coded host port.

Detection content is versioned in
`services/detect-web/src/main/resources/detection-content/manifest.json`.
Every entry carries owner, data-source, ATT&CK, positive, and negative
metadata. The Java contract test executes the vectors; the Python validator
provides a fast CI check. The state journal replay window defaults to 24 hours
and is configurable with `SOCP_DETECT_STATE_RETENTION`.

For multi-instance validation, use a shared PostgreSQL database, a common
`SOCP_KAFKA_GROUP_ID`, and `DETECTION_INSTANCE_URLS` with
`python build/chaos-pipeline.py --scenario multi_instance`. See the [validation
matrix](validation-matrix.md) for pass criteria and explicit limits.

## CI ownership

`.github/workflows/ci.yml` runs on pushes and pull requests to `main`. It builds
the Java reactor, runs the Java suite, verifies the workbench, and runs a
minimal service slice plus the Kafka pipeline E2E job.

`.github/workflows/full-stack.yml` runs manually and on the weekly schedule.
It starts the extended Compose profile, runs full API and pipeline checks,
attack scenarios, and dependency failure injection, then uploads diagnostic
logs.
