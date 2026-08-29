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
