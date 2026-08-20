# Changelog

## Unreleased

### Pipeline durability and performance

- Added an Ingestion Outbox so canonical event persistence and Kafka
  publication intent commit in one transaction.
- Made OpenSearch indexing partition-aware: offsets advance only after every
  bulk item succeeds, failed partitions seek back, and stable document IDs
  make replay idempotent.
- Added a durable Detection Alert Outbox with deterministic alert IDs,
  tenant-aware retry, stale-claim recovery, and a separate detect-model
  publication stage.
- Removed Detection's direct SOAR call; Alert Web's transactional Outbox is
  now the single durable Kafka hand-off boundary for downstream fan-out.
- Alert Outbox rows are marked `PUBLISHED` only after a broker acknowledgement.
- Added focused tests for Alert Web outage retry, duplicate publisher claims,
  and broker-ack failure behavior.
- Closed the Kafka completion gap with durable `PENDING`/`COMPLETED`/
  `DEAD_LETTERED` lifecycle states, partition-serial lanes, and contiguous
  offset commits.
- Streamed time-bounded journal replay page by page to avoid accumulating a
  full recovery window in one persistence context.
- Moved entity-risk alerts and profiles from instance-local memory into a
  shared idempotent database projection.
- Reduced hot-path overhead with constant-time bounded event/alert windows,
  bounded alert de-duplication, lazy NDJSON traversal, cached OpenSearch TLS
  state, throttled stale-claim recovery, and dependency-failure backoff.

### Detection content and runtime verification

- Expanded the packaged Detection-as-Code catalog to 25 versioned rules with
  positive and negative execution vectors, including rare, baseline, and
  correlation-set families.
- Added package-owned rule synchronization while preserving user-owned rules
  and tolerating concurrent multi-instance startup.
- Added six-partition/three-instance correctness, PostgreSQL/OpenSearch outage,
  Detection Outbox replay, realistic benchmark, and alert-heavy benchmark
  evidence contracts.
- Extended the Golden Demo through privilege escalation, multi-stage host
  correlation, shared entity risk, Incident, Notify, SOAR, and reporting.

### Verification and documentation

- Added an Alert Web restart chaos scenario and an opt-in multi-instance
  partition ownership/rebalance scenario.
- Refreshed architecture, state semantics, testing, validation, benchmark,
  chaos, demo, and module-map documentation to match the current code.
- Removed the stale reference to a non-existent release checklist; release
  readiness is defined by `docs/validation-matrix.md` and the operational
  checks it links to.
