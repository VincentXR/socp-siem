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
- [ ] P0-A-2 Detection Journal terminal cleanup.
- [ ] P0-B-1 Typed SPL semantic validation.
- [ ] P0-B-2 Local/OpenSearch execution parity.
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
