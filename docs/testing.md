# Testing Guide

The repository uses fast module tests for behavior and Python checks for the
cross-service event path. Coverage is intentionally risk-driven; the goal is
to catch reliability and security regressions close to the owning module.

## Local checks

```bash
# All Java modules
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# A focused Maven slice
bash build/mvnw.sh -pl services/api-gateway,services/alert-web,services/detect-web -am test

# Workbench API contracts and production artifact
cd frontend/apps/workbench
pnpm test
pnpm build
node scripts/verify-build.mjs
```

## What is covered

- `socp-auth` and `api-gateway`: JWT configuration, production guard, missing
  tokens, viewer write denial, tenant and trace propagation.
- `socp-rule` and `detect-web`: rule evaluation, suppression, hot reload,
  event deduplication and malformed Kafka records routed to a DLQ sink.
- `alert-web`: transactional Outbox publishing, pending retry behavior and
  downstream fan-out failure isolation.
- `incident-web` and `soar-web`: case merge/idempotency and compensation or
  fallback execution behavior.

## Integration checks

With Docker middleware running, use the smallest check that matches the
change:

```bash
python build/verify-slice.py       # gateway + alert slice
python build/verify-pipeline.py   # Kafka → detection → stores
python build/verify-full.py       # API, auth, tenancy and persistence
python build/failure-tests.py     # dependency failure and fallback paths
```

The pull-request workflow runs the Java suite and all workbench checks. The
manual/weekly full-stack workflow additionally runs the integration pipeline,
attack scenarios and failure injection, then uploads logs.
