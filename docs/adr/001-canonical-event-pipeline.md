# ADR 001: Canonical Event Pipeline

- Status: accepted
- Date: 2026-08-15

## Decision

Normalize vendor-specific telemetry in `search-config` and publish one
canonical event contract to Kafka topic `socp-events`. `detect-web` consumes
that contract for rule evaluation, while an independent consumer indexes raw
events in OpenSearch. Detection alerts cross into Alert Web through the durable
Detection Alert Outbox rather than a direct remote call.

## Why

Detection rules should not know whether an event came from Syslog, CEF, LEEF,
Sysmon, Falco, or NDJSON. Kafka separates ingestion rate from detection rate
and allows the search index to be rebuilt from the event stream. The Detection
Outbox separates rule evaluation from Alert Web availability.

## Trade-offs

The contract, replay path, and outbox add operational complexity. Detection is
at-least-once: consumers use manual commits and event-ID deduplication, but the
system does not claim distributed exactly-once processing. The producer routes
by a stable tenant/entity key and consumers restore partition-owned windows
from the journal. Rules grouped by another entity dimension still need a
shared state or fan-out strategy; see `docs/detection-state-semantics.md`.
