# ADR 003: Transactional Outbox for Alert Fan-out

- Status: accepted
- Date: 2026-08-15

## Decision

`alert-web` writes `t_alarm` and an `outbox_event` row in the same database
transaction. `OutboxPublisher` publishes pending rows to
`socp-alarm-events`; `AlarmEventConsumer` fans out to ClickHouse, notification,
incident and SOAR integrations.

## Why

Directly writing several remote systems from the alert transaction creates
partial-success failures. The outbox guarantees that a committed alert has a
durable event that can be retried or replayed after Kafka or a downstream
service is unavailable.

## Trade-offs

Delivery is at-least once. A publisher crash between Kafka send and marking a
row `PUBLISHED` can produce a duplicate, so downstream consumers must use
stable alarm IDs and idempotent writes. The outbox is a reliability boundary,
not a replacement for a distributed transaction.
