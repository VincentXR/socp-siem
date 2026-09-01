# ADR 005: Durable outbox lifecycle

- Status: accepted
- Date: 2026-08-29

## Decision

All durable publishers use the shared `OutboxRetryPolicy` for attempt
normalization, bounded exponential backoff, error truncation, and the
`PENDING`/`DEAD` decision. Each bounded context keeps its own persistence
entity and publisher because the hand-off stages differ:

- Search publishes canonical events to Kafka;
- Detection has an Alert Web stage followed by the original-alarm stage;
- Alert has a Kafka outbox plus per-destination delivery receipts;
- Rule changes broadcast configuration updates.

The state transition itself must be an atomic repository update guarded by the
expected status and attempt deadline. Stale claims are recovered explicitly.
Business code supplies the payload and the acknowledgement callback; it must
not implement an unbounded HTTP retry or infer a tenant by loading an
aggregate during replay.

## Rationale

A single generic entity would erase meaningful stage semantics and encourage
unsafe cross-context joins. Sharing the retry policy removes the genuinely
duplicated correctness logic while keeping each outbox's state machine
auditable and testable.

## Verification

Publisher unit tests cover claim races, retry/dead transitions, stale recovery,
and acknowledgement failures. The pipeline and chaos probes verify replay,
duplicate delivery, and restart recovery across the context boundaries.

## DEAD operational closure

`DEAD` rows are never removed by retention cleanup and are never replayed
automatically. Prometheus alerts on the current DEAD count after five minutes;
operators then use the tenant-scoped, admin-only API for the owning service:

| Context | Inspect | Requeue / discard |
|---|---|---|
| Search ingestion | `GET /api/admin/outbox/ingestion/dead` | `POST /api/admin/outbox/ingestion/{id}/requeue` or `/{id}/discard` |
| Detection alert | `GET /api/admin/outbox/detection-alerts/dead` | `POST /api/admin/outbox/detection-alerts/{id}/requeue` or `/{id}/discard` |
| Detection rule change | `GET /api/admin/outbox/rule-changes/dead` | `POST /api/admin/outbox/rule-changes/{id}/requeue` or `/{id}/discard` |
| Alert event | `GET /api/admin/outbox/alarm-events/dead` | `POST /api/admin/outbox/alarm-events/{id}/requeue` or `/{id}/discard` |
| Alert delivery | `GET /api/admin/outbox/alarm-deliveries/dead` | `POST /api/admin/outbox/alarm-deliveries/{id}/requeue` or `/{id}/discard` |

Discard requests require `{"reason":"..."}`. Both requeue and discard are
rate-limited and audit-logged. Inspection deliberately returns failure metadata
without the event payload. Explicitly discarded rows preserve the operator
reason and previous failure for 30 days before cleanup; published/successful
retention remains independent.

The primary signals are `socp_ingestion_outbox_dead_count`,
`socp_alert_outbox_dead_count`, and `socp_detection_outbox_dead_count`, plus
their `oldest_dead_age_seconds` companions. A requeue is complete only after
the current DEAD count falls and the relevant pending-age signal returns below
its SLO; an HTTP 200 from the admin endpoint alone is not delivery evidence.
