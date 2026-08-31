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
- [x] P0-B-3 Real OpenSearch parity and tenant tests.
  - The writer and integration tests now install one shared production index
    template with lowercase exact fields, literal message matching, typed
    IP/integer security fields, and bounded dynamic keyword subfields.
  - Bulk writes do not start until template installation succeeds, closing the
    previous asynchronous mapping race.
  - OpenSearch 2.11.1 tests use that production template and compare Local and
    OpenSearch event IDs, order, totals, and stat rows across 11 filter and
    aggregation queries. They also prove tenant isolation, stable three-page
    traversal, final-page termination, and cross-query cursor rejection.
  - Validation: search-config reactor with `SOCP_TESTCONTAINERS=true` (117 tests,
    no skips), including six real OpenSearch tests and the PostgreSQL 16 upgrade
    test; `python build/verify-event-schema.py`, `python build/verify-style.py`,
    and `git diff --check`.
- [x] P0-C-1 Indexer per-item write and offset invariants.
  - Bulk writes return acknowledged IDs plus indexed retryable and permanent
    failures; HTTP 400/422 mapping or schema rejections are permanent, while
    408/429, 5xx, invalid responses, and transport failures remain retryable.
  - Each Kafka partition commits only its contiguous safe prefix. Valid items
    require an OpenSearch item acknowledgement, and permanent or malformed
    items require a broker-acknowledged diagnostic DLQ envelope carrying the
    original source identity and sanitized failure context.
  - The production template is synchronously ready before the indexer creates
    a Kafka consumer, and CI verifies every registered typed query path against
    the production mapping contract.
  - Partition/batch/offset/timing/retry logs and bounded-label counters cover
    template readiness, partial bulk failures, DLQ failures, and commit
    failures. Benchmark reports now separate retry-inflated attempt counters
    from the Kafka group's unique committed-source-offset delta.
  - Validation: search-config reactor with `SOCP_TESTCONTAINERS=true` (124
    tests, no skips), including six real OpenSearch tests and PostgreSQL 16
    tests; `python -m py_compile build/benchmark-pipeline.py`, benchmark
    reconciliation shape assertion, `python build/verify-event-schema.py`,
    `python build/verify-style.py`, and `git diff --check`.
- [x] P0-C-2 Indexer failure and reconciliation evidence.
  - A real Kafka/OpenSearch/DLQ test reconciles three committed source offsets
    as two unique indexed documents plus one broker-acknowledged permanent
    failure envelope, while asserting the corresponding bounded-label metrics.
  - Fault tests cover write-acknowledged-before-commit replay, DLQ broker
    unavailability, a real proxy-generated OpenSearch 503, and a real Kafka
    commit failure after OpenSearch acknowledgement.
  - The failure suite is part of the opt-in CI integration job rather than an
    advisory local-only test.
  - Validation: focused `OsIndexerFailureContainerTest` (5 tests, no skips),
    exact CI integration selection (17 tests across Kafka, OpenSearch, and
    PostgreSQL, no skips), `python build/verify-event-schema.py`,
    `python build/verify-style.py`, and `git diff --check`.
- [ ] P0-D Current-commit stable performance baseline.
  - [x] P0-D-0 Restore the benchmark topology startup invariant: initialize
    the default Detection engine inside an explicit tenant scope. The focused
    engine suite passes 12 tests, and all eight core services report healthy
    with the rebuilt JAR.
  - [x] P0-D-1 Make the series contract execute and retain an excluded warm-up,
    reject mixed-commit rounds and sustained monotonic throughput decline, and
    cover those gates with CI-run Python unit tests.
  - [x] P0-D-2 Align E2E benchmark authentication with the direct registered
    collector data-plane boundary while retaining user JWTs only for control-
    plane reads; cover both request paths with CI-run Python unit tests.

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
