# Detection state semantics

This document is the implementation contract for the `socp-events` consumer.
It describes the guarantees made by the current single-topic, consumer-group
implementation. It is intentionally narrower than an exactly-once claim.

## Ownership and routing

The producer key is:

```text
tenant_id | detection_routing_field | detection_routing_value
```

The key is stable across retries and does not contain `eventId`. `eventId` is
the durable de-duplication identity; the Kafka key is the state ownership
identity. The canonical ingest pipeline writes `tenant_id`,
`detection_routing_field`, and `detection_routing_value` into the event fields
so the decision survives parser changes and can be inspected in OpenSearch.

The default routing policy is:

- endpoint/audit events: `host` when available;
- other events: `src_ip`, then `user`, then `host`, then `dst_ip`;
- an explicit routing field/value in the canonical event takes precedence.

A stateful rule is strictly partition-local only when its `keyField` equals the
event's `detection_routing_field`. Rules that correlate a different entity
dimension are accepted by the engine, but this implementation does not claim
strict multi-instance ordering for them. Supporting those rules requires a
separate fan-out topic or a shared state store.

## Commit and recovery order

For every Kafka record the consumer performs these operations in order:

1. Deserialize and normalize the event.
2. Insert `eventId` into `t_detection_event` with Kafka partition, offset and
   routing key. The primary key is the durable claim.
3. Enqueue the event into the bounded detection worker queue.
4. Commit the Kafka offset after the polled batch has been processed.

If queue admission fails, the claim is removed and the record is sent to the
DLQ. If the process stops after step 2 but before step 4, the next owner does
not enqueue the duplicate: it restores the event from the journal before
processing the Kafka redelivery.

The alert ID is deterministic from `ruleId`, entity and evidence event IDs.
Alert Web persists that source alert ID and treats a repeated create request as
an idempotent read of the existing alert.

## Rebalance behavior

The consumer restores state in `onPartitionsAssigned`. It queries only journal
rows carrying the newly assigned partition IDs, rebuilds the stateful rule
windows in Kafka partition/offset order, and starts the replacement engine
before processing records from the new assignment. A rebalance therefore does
not replay another instance's partition history into this instance's local
windows.

The journal is a bounded recovery log, not an event archive. Its default
retention is 24 hours and the replay query is capped at 10,000 rows. A rule
whose required recovery horizon exceeds either bound is outside the current
guarantee and must use a dedicated state backend before being enabled for that
workload.

## Explicit non-guarantees

The current design does not claim:

- exactly-once delivery across Kafka, PostgreSQL and downstream services;
- strict ordering across different Kafka partitions;
- strict multi-instance correctness for a rule grouping field different from
  the event routing field;
- recovery of state older than the configured journal retention or query cap;
- recovery of an alert that was never accepted by Alert Web and has no
  deterministic source-alert create request left to retry.

These boundaries are intentional. They make the failure behavior testable and
prevent a local consumer-group implementation from being described as a
general-purpose distributed stream processor.
