# Changelog

## Unreleased

### Detection reliability

- Added a durable Detection Alert Outbox with deterministic alert IDs,
  tenant-aware retry, stale-claim recovery, and a separate detect-model
  publication stage.
- Removed Detection's direct SOAR call; Alert Web's transactional Outbox is
  now the single downstream Incident/Notify/SOAR fan-out boundary.
- Alert Outbox rows are marked `PUBLISHED` only after a broker acknowledgement.
- Added focused tests for Alert Web outage retry, duplicate publisher claims,
  and broker-ack failure behavior.

### Verification and documentation

- Added an Alert Web restart chaos scenario and an opt-in multi-instance
  partition ownership/rebalance scenario.
- Refreshed architecture, state semantics, testing, validation, benchmark,
  chaos, demo, and module-map documentation to match the current code.
- Removed the stale reference to a non-existent release checklist; release
  readiness is defined by `docs/validation-matrix.md` and the operational
  checks it links to.

## Release preparation

Before creating a tagged release, run the full Java/frontend checks, the
Golden Demo, the failure matrix, and the 10k/100k/1m benchmark series. Keep
machine-specific reports outside source control unless they are deliberately
sanitized and published as reproducible fixtures.
