# ADR 002: Separate Transactional, Search, and Analytical Storage

- Status: accepted
- Date: 2026-08-15

## Decision

Use PostgreSQL for alert, incident, tenant, threat-intelligence and audit
facts; OpenSearch for raw event investigation; and ClickHouse for alarm detail
aggregation and reporting. File-backed H2 is supported for nine low-resource
local services and can be replaced by PostgreSQL with the `pg` profile.

## Why

Alert lifecycle updates need transactional consistency and tenant filters.
Investigation needs text and field search. Trend dashboards need scans and
aggregations that should not compete with OLTP writes.

## Trade-offs

There are multiple schemas and operational dependencies. Cross-store data is
eventually consistent and must be repaired by replaying Kafka/Outbox events.
The local H2 profile keeps development affordable but is not the production
deployment target.
