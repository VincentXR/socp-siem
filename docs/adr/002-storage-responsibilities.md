# ADR 002: Separate Transactional, Search, and Analytical Storage

- Status: accepted
- Date: 2026-08-15

## Decision

Use PostgreSQL for alert, incident, tenant, threat-intelligence, audit, and
optional Detection journal/alert-outbox facts; OpenSearch for raw event
investigation; and ClickHouse for alarm detail aggregation and reporting.
File-backed H2 remains a local convenience profile for lower-resource
services.

## Why

Alert lifecycle updates, event claims, and Outbox rows need transactional
consistency and tenant filters. Investigation needs text and field search.
Trend dashboards need scans and aggregations that should not compete with OLTP
writes. Detection's recovery journal and alert hand-off need the same durable
boundary when multiple instances share state.

## Trade-offs

There are multiple schemas and operational dependencies. Cross-store data is
eventually consistent and must be repaired by replaying Kafka or Outbox events.
The local H2 profile keeps development affordable but is not the production
deployment target.
