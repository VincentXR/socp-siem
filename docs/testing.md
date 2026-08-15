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

# Workbench API contracts and production artifact
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
- `socp-rule` and `detect-web`: rule evaluation, suppression, hot reload,
  event deduplication, queue backpressure, and malformed-event handling.
- `alert-web`: transactional Outbox creation, pending-event retry, alert
  disposition, and downstream fan-out isolation.
- `incident-web` and `soar-web`: case merge/idempotency and compensation or
  in-process fallback behavior.
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
python build/failure-tests.py      # dependency stop/restart and fallback paths
```

The pipeline check confirms canonical event acceptance, Kafka delivery,
detection, PostgreSQL alert persistence, OpenSearch indexing, ClickHouse
analytics, and report availability. The failure checks verify recovery behavior
for Kafka, OpenSearch, Temporal, and PostgreSQL.

## CI ownership

`.github/workflows/ci.yml` runs on pushes and pull requests to `main`. It builds
the Java reactor, runs the Java suite, verifies the workbench, and runs a
minimal service slice plus the Kafka pipeline E2E job.

`.github/workflows/full-stack.yml` runs manually and on the weekly schedule. It
starts the extended Compose profile, runs full API and pipeline checks, the
attack-scenario playground, and dependency failure injection, then uploads
diagnostic logs.
