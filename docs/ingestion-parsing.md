# Ingestion parsing

`Vector` is the collector and transport. `search-config` owns the
source-specific parser pipeline and produces the canonical event consumed by
Detection and OpenSearch.

## Canonical event contract

Kafka events use the versioned envelope in `schemas/canonical-event-1.0.json`.
`schemaVersion` and `tenantId` are explicit envelope fields;
`fields.tenant_id` remains only as a compatibility bridge for existing
Detection rules. During a rolling upgrade, consumers accept envelopes without
`schemaVersion` as legacy 1.0, but reject explicitly unsupported versions to
the topic DLQ.

The field registry in `schemas/field-registry.json` is the contract between
normalization, OpenSearch mappings, aggregation, and Detection content. A
breaking change must add a new schema file and pass
`build/verify-event-schema.py`. ECS-style canonical keys remain the internal
source of truth; OCSF mapping is an export concern, not a second internal event
model.

```text
collector → authenticated ingest → canonical envelope 1.0
         → PostgreSQL + ingestion outbox → Kafka
         → Detection / OpenSearch indexer
         → schema failure → socp-events-dlq (reason + original payload)
```

```text
Vector transform
  -> source_id / collector_tag / parse_format / parse_rule_ids / message
  -> tenant-scoped source resolution
  -> fixed parser (AUTO, SYSLOG, JSON, KV, CEF, LEEF)
  -> ordered source-bound rules
  -> canonical ECS fields + compatibility fields
  -> ingestion outbox -> Detection / OpenSearch
```

## Identity and retry contract

The ingest endpoint accepts `Idempotency-Key` for clients that cannot attach an
event ID. The server first trusts a producer `eventId`/`event.id`, then a stable
collector position such as Kafka topic/partition/offset, file path/offset, or
batch/line. The request key is the next fallback and is scoped by tenant,
collector, line number, and payload fingerprint. A plain body hash is never used
as the event identity, so two genuine identical log lines remain distinct.

Events and their Kafka publication intents are committed in one database
transaction. The tenant-local `(tenant_id, event_id)` constraints make a retry
an idempotent acknowledgement. The response reports `created`, `duplicates`,
and `acknowledged`; reusing an identity with different canonical content is a
HTTP 409 conflict. A persistence error is HTTP 503 and means only the current
uncommitted 200-event transaction should be retried.

The rendered Vector envelope contains a stable `source_id`. The request
credential still determines the tenant and trusted collector identity; body
metadata is only used to find the source inside that tenant. The server uses
the persisted `LogSource.parseRuleIds`, so changing a body field cannot select
another tenant's rules.

Malformed or over-budget event data is counted as a per-line parse rejection.
Failure while reading the tenant-scoped source, parsing-rule, or reference-set
configuration is a dependency failure instead: the API returns HTTP 503 and
does not acknowledge the affected uncommitted batch. This distinction prevents
an outage in shared configuration data from being reported as successful ingest
with silently skipped events.

## Rule model

Create a rule with `POST /search-config/api/v1/parse-rules`:

```json
{
  "name": "nginx-auth",
  "sourceId": null,
  "format": "REGEX",
  "pattern": "user=(?<user>\\S+) src=(?<srcip>\\S+) status=(?<status>\\d+)",
  "mapping": [],
  "setFields": [],
  "filters": [
    {"type": "lowercase", "field": "user"},
    {"type": "convert", "field": "status", "to": "integer"},
    {"type": "set", "field": "event.category", "value": "authentication"}
  ],
  "enabled": true,
  "order": 10
}
```

Supported input formats are:

- `REGEX`: Java named groups (`(?<srcip>...)`) or numeric groups with
  `mapping` entries (`group` -> `field`). Common aliases such as `user`,
  `src_ip`, and `srcip` are normalized to canonical ECS keys.
- `JSON`: object fields are flattened using dotted paths.
- `KV`: quoted and unquoted `key=value` fields.
- `SYSLOG`, `CEF`, and `LEEF`: the existing built-in parser is selected
  explicitly and can unwrap the Vector envelope before parsing `message`.
- `AUTO`: uses the built-in feature-based parser chain.

`filters` is intentionally a bounded, deterministic subset of Logstash
filters. It supports `set`, `rename`, `copy`, `remove`/`delete`, `trim`,
`lowercase`, `uppercase`, and `convert` (`string`, `integer`, `long`,
`double`, or `boolean`). Unsupported filter types and invalid regular
expressions are rejected with HTTP 400 when the rule is saved.

Bind rules to a source with `PUT /search-config/api/v1/sources/{id}`:

```json
{
  "name": "nginx-access",
  "type": "FILE",
  "format": "AUTO",
  "path": "/var/log/nginx/access.log",
  "enabled": true,
  "parseRuleIds": ["nginx-auth"]
}
```

Rules in `parseRuleIds` run in the listed order and the first matching rule
wins. An empty list means that the persisted source format is used; enabled
global rules are only a sparse-event compatibility fallback. Both preview and
live ingest use the same `ParseRuleExecutor`, and compiled pipelines are
cached until the rule or source configuration changes.

Parsing does not create an alert by itself. It supplies normalized fields such
as `fields.category`, `fields.src_ip`, `fields.user`, and the corresponding
`ecs.*` values. The existing Detection `RuleSpec.match` / `steps` conditions
then evaluate those fields and create alerts through the detection outbox.

## Search runtime roles

`SOCP_SEARCH_RUNTIME_ROLE` selects one of three roles from the same
`search-config` artifact:

| Role | Default | Responsibility |
| --- | --- | --- |
| `all` | yes | Local compatibility mode with API and continuous workers |
| `api` | no | Management, normalization, queries, and transactional event/outbox writes |
| `worker` | no | Ingestion Outbox publication and Kafka-to-OpenSearch indexing |

The API and worker exchange events through the database and Ingestion Outbox.
An API acknowledgement means the event and publication intent committed; it
does not mean Kafka or OpenSearch completed. Production starts
`search-config-api` and `search-config-worker` from the same version. Gateway
traffic reaches only the API role, while Kafka consumer groups and Outbox
leases protect independently scaled workers. Both roles must share the same
transaction database and Kafka/OpenSearch contract.

Detection follows an analogous lifecycle boundary documented in
[Detection state semantics](detection-state-semantics.md#runtime-roles).

### Local hot-cache capacity

The API role keeps only a bounded hot window; PostgreSQL and OpenSearch remain
durable. `SOCP_SEARCH_CACHE_MAX_BYTES_PER_TENANT` and
`SOCP_SEARCH_CACHE_MAX_BYTES_TOTAL` are estimated admission weights rather
than exact retained-heap measurements. Startup fails when the total budget is
smaller than the per-tenant budget.

An oversized event is persisted but skipped from the cache. Tenant admission
uses `SOCP_SEARCH_CACHE_MAX_TENANTS`; warm-up is paged and bounded by
`SOCP_SEARCH_CACHE_WARMUP_BATCH_SIZE`,
`SOCP_SEARCH_CACHE_WARMUP_MAX_EVENTS`, and
`SOCP_SEARCH_CACHE_MAX_CONCURRENT_WARMUPS`. JPA/object overhead and an
individual database row can still exceed their estimates, so these settings
are not an absolute JVM heap guarantee.

### PostgreSQL event-retention catch-up

Retention uses `t_search_event.created_at`, not the source timestamp. Rows
linked to `PENDING|PROCESSING|DEAD` Ingestion Outbox work remain protected.
Cleanup uses ordered `(created_at,id)` batches with `FOR UPDATE SKIP LOCKED`
and the V11 retention index.

One scheduler invocation may run several bounded rounds, controlled by
`SOCP_SEARCH_EVENT_CLEANUP_BATCH_SIZE`,
`SOCP_SEARCH_EVENT_CLEANUP_MAX_BATCHES`,
`SOCP_SEARCH_EVENT_CLEANUP_MAX_RUN_MS`, and
`SOCP_SEARCH_EVENT_CLEANUP_CATCHUP_PAUSE_MS`. These bounds keep catch-up from
turning into one long transaction or a tight loop against live ingest.
