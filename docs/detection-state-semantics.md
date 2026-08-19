# Detection State Semantics

This document is the implementation contract for the `socp-events` consumer
and the Detection-to-Alert Web hand-off. It is intentionally narrower than an
exactly-once claim.

## Ownership and routing

The producer key is:

```text
tenant_id | detection_routing_field | detection_routing_value
```

The key is stable across retries and does not contain `eventId`. `eventId` is
the durable de-duplication identity; the Kafka key is the state ownership
identity. Canonical ingestion writes the routing field and value into the
event fields so the decision survives parser changes and can be inspected in
OpenSearch.

The default routing policy is:

- endpoint/audit events: `host` when available;
- other events: `src_ip`, then `user`, then `host`, then `dst_ip`;
- an explicit routing field/value takes precedence.

A stateful rule is strictly partition-local only when its `keyField` equals the
event `detection_routing_field`. Rules that group by another entity dimension
are accepted by the engine, but this implementation does not claim strict
multi-instance ordering for them.

## Event commit and state recovery

For every Kafka record the consumer performs these operations in order:

1. Deserialize and normalize the event.
2. Insert `eventId` into `t_detection_event` with Kafka partition, offset, and
   routing key. The primary key is the durable claim.
3. Enqueue the event into the bounded detection worker queue.
4. Commit the Kafka offset after every record in the polled batch has been
   accepted into the bounded detection queue.

If queue admission fails, the claim is removed and the record is sent to the
DLQ. Rule evaluation and alert materialization continue asynchronously after
queue admission. If the process stops after step 2 but before step 4, the next owner does
not enqueue the duplicate: it restores the event from the journal before
processing the Kafka redelivery.

At startup and `onPartitionsAssigned`, Detection restores only journal rows
belonging to the current assignment. Rows are replayed in Kafka partition and
offset order for assigned partitions. A rebalance therefore does not mix
another instance's partition history into the local hot windows.

## Alert delivery commit points

When a rule emits an alert, `RecentAlertSink` materializes the payload and
persists one row in `t_detection_alert_outbox` before the detection worker
continues. The deterministic alert ID is the outbox primary key.

The publisher advances the row through these stages:

```text
PENDING
  -- Alert Web 2xx --> DELIVERED
  -- original alarm Kafka acknowledgement --> PUBLISHED
```

Failed Alert Web calls remain `PENDING` with exponential backoff. Failed
detect-model Kafka calls remain `DELIVERED`, so retrying them never replays the
HTTP create as a new alert. A publisher crash leaves `PROCESSING`; claims older
than two minutes are returned to the correct stage. Alert Web additionally
enforces `(tenant_id, source_alert_id)` idempotency.

Crash-point behavior:

| Crash point | Recovery result |
|---|---|
| Before the event claim | Kafka redelivery is processed normally |
| After the event claim, before offset commit | Journal restore + event-ID claim prevents a second evaluation |
| After alert detection, before Outbox commit | The sink failure is logged and the worker remains alive; a permanently unavailable Detection database is an explicit loss boundary |
| After Detection Outbox commit, Alert Web unavailable | `PENDING` row retries after Alert Web recovers |
| After Alert Web accepts, before Detection marks `DELIVERED` | HTTP replay returns the existing `sourceAlertId` row |
| After `DELIVERED`, before original alarm publish | The row remains in the second stage and retries detect-model only |
| After Kafka publish, before stage update | At-least-once duplicate; detect-model de-duplicates by alert ID |

## Recovery limits

The journal is a bounded recovery log, not an event archive. Its default
retention is 24 hours and the replay query is capped at 10,000 rows. A rule
whose required recovery horizon exceeds either bound needs a dedicated shared
state backend before being enabled for that workload.

## Explicit non-guarantees

The current design does not claim:

- exactly-once delivery across Kafka, PostgreSQL, and downstream services;
- strict ordering across different Kafka partitions;
- strict multi-instance correctness for a rule grouping field different from
  the event routing field;
- recovery of state older than the configured journal retention or query cap;
- loss-free recovery if the Detection database itself is permanently
  unavailable before an alert Outbox row can be committed.

These boundaries are intentional. They make failure behavior testable and
avoid describing a local consumer-group implementation as a general-purpose
distributed stream processor.
