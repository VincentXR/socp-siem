# ADR 001: Canonical Event Pipeline

- Status: accepted
- Date: 2026-08-15

## Decision

Normalize vendor-specific telemetry in `search-config` and publish one
canonical event contract to Kafka topic `socp-events`. `detect-web` consumes
that contract for rule evaluation, while an independent consumer indexes raw
events in OpenSearch.

## Why

Detection rules should not know whether an event came from Syslog, CEF, LEEF,
Sysmon, Falco, or NDJSON. Kafka separates ingestion rate from detection rate
and allows the search index to be rebuilt from the event stream.

## Trade-offs

The contract and replay path add operational complexity. Detection is at-least
once: consumers use manual commit and event-id deduplication, but the system
does not claim distributed exactly-once processing. The producer now routes by
the stable tenant/entity detection key and consumers restore partition-owned
windows from the journal. Rules grouped by a different entity dimension still
need a shared state or fan-out strategy; the detailed contract is documented in
`docs/detection-state-semantics.md`.
