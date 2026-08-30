# Canonical event schema

Kafka events use the versioned envelope in `schemas/canonical-event-1.0.json`.
`schemaVersion` and `tenantId` are explicit envelope fields; `fields.tenant_id`
is retained only as a compatibility bridge for existing Detection rules. During
the rolling upgrade, consumers accept envelopes without `schemaVersion` as
legacy 1.0, but reject any explicitly unsupported version to the topic DLQ.

The field registry in `schemas/field-registry.json` is the contract between
normalization, OpenSearch mappings, aggregation and Detection content. A
breaking change must add a new schema file and pass `build/verify-event-schema.py`.
Do not maintain a second internal event model for OCSF: the ECS-style canonical
keys remain the internal source of truth and OCSF mapping is an export concern.

```text
collector → authenticated ingest → canonical envelope 1.0
         → PostgreSQL + ingestion outbox → Kafka
         → Detection / OpenSearch indexer
         → schema failure → socp-events-dlq (reason + original payload)
```
