# ADR 003: Transactional Outboxes for Pipeline Delivery

- Status: accepted
- Date: 2026-08-19

## Decision

SOCP uses three explicit Outbox boundaries:

1. `search-config` writes each canonical event and its
   `t_ingestion_outbox` publication intent in one transaction. The bounded,
   multi-instance-safe publisher waits for Kafka acknowledgement before
   marking the intent `PUBLISHED`.
2. `detect-web` writes a fully materialized detection alert to
   `t_detection_alert_outbox` before the rule-engine worker continues. The
   publisher retries Alert Web, then publishes `socp-alarm-original` for
   detect-model.
3. `alert-web` writes `t_alarm` and its `outbox_event` row in the same
   database transaction. `OutboxPublisher` waits for a Kafka broker
   acknowledgement before marking the row `PUBLISHED`. It scans bounded
   batches, uses an optimistic `PENDING -> PROCESSING` claim across instances,
   publishes with bounded concurrency, and recovers stale claims after a
   process crash.

Alert Web enforces `(tenant_id, source_alert_id)` idempotency. The Alert Outbox
guarantees broker acknowledgement before its row becomes `PUBLISHED`.

## Why

Detection must not lose a generated alert merely because Alert Web is
temporarily unavailable. Alert lifecycle writes must not be coupled to direct
writes into ClickHouse, Incident, Notify, or SOAR. Separate outboxes make each
boundary observable, retryable, and independently testable.

## Trade-offs

There are three durable states and three retry loops to operate. Delivery remains
at-least-once: a publisher crash after a broker accepts a message but before a
database status update can produce a duplicate. Stable IDs and idempotent
consumers are therefore required. The current combined fan-out consumer does
not persist one retry task per destination, so the durable contract ends at
Kafka and individual Incident/Notify/SOAR calls are best-effort. The design
does not claim a distributed exactly-once transaction.

Threat-intelligence enrichment is outside the Alert transaction and starts
only after commit. Its executor and queue are bounded so an unavailable threat
service cannot exhaust Alert Web during an alert storm; saturation may skip
this explicitly best-effort enrichment without affecting the durable Alert or
Outbox facts.
