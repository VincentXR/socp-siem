# Project remediation progress

This file is the committed checkpoint for the work described by
`.cache/project-remediation-handoff.md`. A checked item has been implemented,
verified, committed, and pushed as an independent change. Do not repeat checked
items; use the linked validation notes when continuing the remediation.

Baseline: `6aae664` on 2026-08-31.

## P0

- [x] P0-A-1 Ingestion Outbox polling and retention hardening.
  - The due scan follows `(status, next_attempt_at, created_at)` index order.
  - Metrics distinguish claim batch size, true pending count, and oldest pending age.
  - Published retention uses configurable bounded batches.
  - PostgreSQL 16 upgrade evidence covers 100,000 historical PUBLISHED rows and
    1,000 PENDING rows; the due query uses `idx_ingestion_outbox_due_v2` without
    a sequential scan or explicit sort.
  - Validation: search-config reactor tests (109 tests, 6 opt-in container tests
    skipped), `SearchConfigPostgresMigrationTest` with `SOCP_TESTCONTAINERS=true`
    (1 test), `python build/verify-style.py`, and `git diff --check`.
- [x] P0-A-2 Detection Journal terminal cleanup.
  - COMPLETED and DEAD_LETTERED use independent terminal clocks and bounded,
    configurable cleanup batches; PENDING is never a cleanup target.
  - Legacy terminal rows without lifecycle timestamps retain the audited
    `occurred_at` compatibility path documented by `build/detection-journal-audit.sql`.
  - PostgreSQL 16 evidence upgrades 100,000 legacy rows from schema V4, verifies
    missing terminal timestamps are auditable, deletes only bounded terminal
    batches, and preserves all 1,000 old PENDING rows.
  - Validation: detect-web reactor tests (105 tests, 1 opt-in container test
    skipped), `DetectJournalPostgresMigrationTest` with
    `SOCP_TESTCONTAINERS=true` (1 test), `python build/verify-style.py`, and
    `git diff --check`.
- [x] P0-B-1 Typed SPL semantic validation.
  - A shared typed field catalog now validates equality, range, contains, sort,
    aggregation, and cursor capabilities after parsing and again at execution
    boundaries; invalid typed literals and unsupported dynamic-field operations
    fail with stable semantic errors instead of falling through to a backend.
  - Pipeline validation rejects duplicate/ambiguous terminal commands,
    aggregation plus row limits, aggregation with cursors, and explicit cursor
    sorts; interactive results are capped at 500 rows and exports at 5,000.
  - Validation: search-config reactor tests (112 tests, 6 opt-in container tests
    skipped), `python build/verify-event-schema.py`,
    `python build/verify-style.py`, and `git diff --check`.
- [x] P0-B-2 Local/OpenSearch execution parity.
  - Local filtering and sorting now use catalog types for dates, integers, IPs,
    severities, and case policy; default ordering is consistently
    `timestamp DESC, eventId ASC`, and missing aggregation values are excluded.
  - OpenSearch DSL uses structurally valid bool/match-all nodes, escaped literal
    wildcard contains, object-form terms ordering, 1,000-bucket `count by`, and
    non-empty timechart buckets. Truncated terms results expose
    `sumOtherDocCount` and `approximate` metadata.
  - Cursors carry the complete sort array, fixed sort specification, query
    fingerprint, and integrity check. Query reuse or cursor modification is
    rejected; a one-row lookahead prevents duplicate or spurious final pages.
  - OpenSearch 4xx query errors no longer fall back, while 429, 5xx, and
    transport failures remain explicit degraded candidates. End-to-end elapsed
    time includes parse/compile/transport/response handling and backend `took`
    is retained separately.
  - Validation: search-config reactor tests (116 tests, 6 opt-in container tests
    skipped), `python build/verify-event-schema.py`,
    `python build/verify-style.py`, and `git diff --check`.
- [ ] P0-B-3 Real OpenSearch parity and tenant tests.
- [ ] P0-C-1 Indexer per-item write and offset invariants.
- [ ] P0-C-2 Indexer failure and reconciliation evidence.
- [ ] P0-D Current-commit stable performance baseline.

## P1

- [ ] P1-A Detection 1x/2x/3x, rebalance, and failover evidence.
- [ ] P1-B Sysmon-to-sandbox Golden Path.
- [ ] P1-C Detection content single source of truth.
- [ ] P1-D Code quality and architecture gates.
- [ ] P1-E Documentation and capability claim corrections.

## Conditional P2 and final verification

- [ ] Evaluate P2 trigger conditions after P0/P1 evidence is complete.
- [ ] Run the final repository-wide Definition of Done validation.

P3 backlog and the architecture changes explicitly listed as “do not do” in the
handoff are outside this remediation run.
