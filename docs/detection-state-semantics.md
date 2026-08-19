# Detection State Semantics

This document is the implementation contract for the `socp-events` consumer
and the Detection-to-Alert Web hand-off. It deliberately describes
at-least-once transport with logically idempotent business effects; it does
not claim distributed exactly-once processing.

## Ownership and routing

The producer key is:

```text
tenant_id | detection_routing_field | detection_routing_value
```

The key is stable across retries and does not contain `eventId`. `eventId` is
the durable event identity; the Kafka key is the state ownership identity.
Canonical ingestion writes the routing field and value into event fields so
the decision survives parser changes and remains inspectable in OpenSearch.

The default routing policy is:

- endpoint/audit events: `host` when available;
- other events: `src_ip`, then `user`, then `host`, then `dst_ip`;
- an explicit routing field/value takes precedence.

A stateful rule is strictly partition-local only when its `keyField` equals the
event `detection_routing_field`. A rule grouping by a different entity may
still run, but this implementation does not claim strict multi-instance
ordering for that rule.

## Processing invariant

The central invariant is:

> A Kafka partition's committed offset may advance only to the highest
> contiguous offset whose durable Detection result (or durable DLQ hand-off)
> has completed.

For example:

```text
offset 100  COMPLETED
offset 101  PENDING
offset 102  COMPLETED
offset 103  COMPLETED

committable offset = 101
```

The consumer does not commit 104 until offset 101 also completes. A
`PartitionCompletionTracker` keeps this per-partition high-water mark. Kafka
polling remains non-blocking; each assigned partition has a serial processing
lane, while different partitions may be processed independently.

## Event lifecycle

The journal uses three durable states:

```text
PENDING       claimed, but durable Detection effects are not complete
COMPLETED     Outbox/state effects committed; safe to skip on replay
DEAD_LETTERED terminal input whose DLQ hand-off was durably acknowledged
```

The claim API additionally returns `NEW` when it inserts a fresh `PENDING`
row. A duplicate claim sees the existing state:

- `PENDING`: replay the event;
- `COMPLETED`: skip the event;
- `DEAD_LETTERED`: skip the event.

The normal Kafka path is:

```text
claim event as PENDING
    ↓
partition-local RuleEngine completion Future
    ↓
EventAlertSink transaction
    ├── insert 0..N Detection Alert Outbox rows
    └── mark journal COMPLETED
    ↓
completion tracker
    ↓
commit only the contiguous partition offset
```

`COMPLETED` is also marked idempotently by the consumer after the completion
Future. This covers source-compatible sinks and zero-alert events; the
event-aware Detection sink performs the Outbox plus completion update in one
database transaction.

## Error classes

Terminal input errors are sent to the configured Kafka DLQ and may advance the
offset only after the producer acknowledgement succeeds. Temporary
infrastructure failures remain `PENDING`, stay on the partition lane, and are
retried with backoff. A failed DLQ publish is also retried and never treated as
terminal.

This distinction prevents a PostgreSQL timeout or broker outage from being
silently converted into a committed offset.

## Recovery and rebalance

At startup and `onPartitionsAssigned`, Detection rebuilds rule windows only
from `COMPLETED` journal rows belonging to the current assignment. Replayed
`PENDING` rows are then submitted as live work on their owning partition lane.
Rows are read in partition/offset order and the replay is bounded by the
configured retention window. Queries are paginated; there is no fixed 10,000
row truncation. The time window remains an explicit recovery boundary and
should be chosen as:

```text
longest enabled rule window + allowed lateness + safety margin
```

On a transient sink/database failure, the assigned partition's in-memory rule
engine is rebuilt from completed journal rows before retrying the pending
event. This prevents a failed attempt from leaving threshold/correlation state
incremented twice.

## Alert delivery stages

The Detection alert outbox publisher advances rows through:

```text
PENDING
  -- Alert Web 2xx --> DELIVERED
  -- original alarm Kafka acknowledgement --> PUBLISHED
```

Failed Alert Web calls remain `PENDING` with exponential backoff. Failed
detect-model Kafka calls remain `DELIVERED`, so retrying them does not recreate
the HTTP alert. A publisher crash leaves `PROCESSING`; claims older than two
minutes return to the correct stage. Alert Web enforces
`(tenant_id, source_alert_id)` idempotency.

## Crash matrix

| Crash point | Recovery result |
|---|---|
| Before journal claim | Kafka redelivery claims the event |
| After `PENDING` commit, before rule evaluation | Kafka redelivery or pending replay evaluates it |
| During RuleEngine processing | The event remains pending; its partition cannot advance |
| Before Outbox + `COMPLETED` transaction | Transaction rolls back; state is rebuilt and event is retried |
| After Outbox + `COMPLETED`, before Kafka commit | Kafka redelivery sees `COMPLETED` and skips it |
| After Alert Web publish, before stage update | HTTP replay is idempotent by `sourceAlertId` |
| After original alarm publish, before stage update | At-least-once duplicate is absorbed by alert identity |
| Terminal input before DLQ acknowledgement | Offset remains uncommitted and DLQ publication is retried |

## Rule version boundary

Pending events are evaluated by the currently active ruleset after restart.
Rule reloads should drain affected in-flight work before replacing the active
ruleset. The journal is not a historical rule-runtime store.

## Explicit non-guarantees

The current design does not claim:

- exactly-once delivery across Kafka, PostgreSQL, and downstream services;
- strict ordering across different Kafka partitions;
- strict multi-instance correctness for a rule grouping field different from
  the event routing field;
- recovery beyond the configured retention/lateness window;
- loss-free recovery if the Detection database remains permanently unavailable
  and no external durable Kafka/DLQ capacity remains.

These boundaries keep the contract testable without presenting a local
consumer-group implementation as a general-purpose stream processor.
