# ADR 003: Transactional Outboxes for Pipeline Delivery

- Status: accepted
- Date: 2026-08-19

## Decision

The event pipeline uses the following durable Outbox boundaries:

1. `search-config` writes each canonical event and its
   `t_ingestion_outbox` publication intent in one transaction. The publisher
   scans bounded batches, claims rows atomically, and waits for Kafka acknowledgement before
   marking the intent `PUBLISHED`.
2. Detection's canonical source consumer commits its source receipt and frozen
   routing deliveries together before committing the canonical Kafka offset.
   The route publisher waits for broker acknowledgement on
   `socp-detection-routed-v2`; stable delivery IDs absorb publication replay.
3. `detect-web` writes a fully materialized detection alert to
   `t_detection_alert_outbox` before the rule-engine worker continues. The
   publisher retries Alert Web, then publishes `socp-alarm-original` for
   the secondary analyzer embedded in the Detection worker.
4. `alert-web` writes `t_alarm` and its `outbox_event` row in the same
   database transaction. `OutboxPublisher` waits for a Kafka broker
   acknowledgement before marking the row `PUBLISHED`. It scans bounded
   batches, uses an optimistic `PENDING -> PROCESSING` claim across instances,
   publishes with bounded concurrency, and recovers stale claims after a
   process crash. The same transaction also creates a deterministic delivery
   intent for each ClickHouse, Incident, Notify, and SOAR destination.

Alert Web enforces `(tenant_id, source_alert_id)` idempotency. The Alert Outbox
guarantees broker acknowledgement before its row becomes `PUBLISHED`.
Detection route publication uses monotonic attempts to fence late state
updates. Detection alert publication uses a unique claim token because manual
requeue resets its attempts. See the [state contract](../detection-state-semantics.md)
for expiry, delivery-stage, and upgrade semantics.

## Why

Detection must not lose a generated alert merely because Alert Web is
temporarily unavailable. Alert lifecycle writes must not be coupled to direct
writes into ClickHouse, Incident, Notify, or SOAR. Separate outboxes make each
boundary observable, retryable, and independently testable.

## Trade-offs

There are multiple durable states and retry loops to operate. Delivery remains
at-least-once: a publisher crash after a broker accepts a message but before a
database status update can produce a duplicate. Stable IDs and idempotent
consumers are therefore required. Each downstream destination has its own
database-backed claim, retry schedule, stale-claim recovery, and deterministic
identity; Kafka replay reconciles missing delivery intents. The design still
does not claim a distributed exactly-once transaction.

Threat-intelligence enrichment is outside the Alert transaction and starts
only after commit. Its executor and queue are bounded so an unavailable threat
service cannot exhaust Alert Web during an alert storm; saturation may skip
this explicitly best-effort enrichment without affecting the durable Alert or
Outbox facts.
