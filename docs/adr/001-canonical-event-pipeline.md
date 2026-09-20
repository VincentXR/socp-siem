# ADR 001: Canonical Event Pipeline

- Status: accepted
- Date: 2026-08-15

## Decision

Normalize vendor-specific telemetry in `search-config` and publish one
canonical event contract to Kafka topic `socp-events`. The local event and an
Ingestion Outbox publication intent commit in one database transaction.
An independent consumer indexes raw events in OpenSearch. Detection first
records a canonical source receipt and durably fans the event out by the state
dimensions required by active rules; workers consume
`socp-detection-routed-v2`. Detection alerts cross into Alert Web through the
durable Detection Alert Outbox rather than a direct remote call.

## Why

Detection rules should not know whether an event came from Syslog, CEF, LEEF,
Sysmon, Falco, or NDJSON. Kafka separates ingestion rate from detection rate
and allows the search index to be rebuilt from the event stream. The Detection
Outbox separates rule evaluation from Alert Web availability. The OpenSearch
consumer waits for every bulk item acknowledgement before committing its
partition offset and uses event ID as document ID for idempotent replay.

## Trade-offs

The contract, routing path, replay path, and outboxes add operational
complexity. Detection is at-least-once: consumers use manual commits and
deterministic source/delivery identities, but the system does not claim
distributed exactly-once processing. Workers restore partition-owned windows
from the journal. Missing grouping values and legacy canonical-topic mode are
explicit non-guarantees; see `docs/detection-state-semantics.md`.
