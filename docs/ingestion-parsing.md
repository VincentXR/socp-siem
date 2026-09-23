# Ingestion parsing

`Vector` is the collector and transport. `search-config` owns the
source-specific parser pipeline and produces the canonical event consumed by
Detection and OpenSearch.

## Source bootstrap

The API service seeds example file and syslog sources for the `default` tenant
only outside the `prod` profile. Production startup performs no source-catalogue
read or write for examples; operators create and enable their own sources.
Upgrading to this behavior does not remove previously persisted example sources.
Review any existing `demo-auth-log`, `real-file`, or `real-syslog` entries and
disable or delete them explicitly if they are not intended production inputs.

## Raw HTTP admission

Authenticated collector/service ingress bounds the raw request before the
String/JSON message converter reads it. Search uses
`socp.ingest.limits.max-body-bytes` (default 16,777,216 bytes); HIPS uses
`socp.hips.ingest.max-body-bytes` (default 262,144 bytes). Supported raw limits
are 1–67,108,864 bytes. Oversized bodies return 413 before controller execution
or durable writes, including malformed JSON and unknown-length/chunked bodies.
The reader consumes at most the limit plus one byte to detect overflow; a
declared oversized Content-Length is rejected without buffering the body.
These are per-request bounds, not a concurrent-ingestion capacity guarantee.
Ordinary CRUD endpoints retain their existing admission behavior.

Detection's manual worker routes `/detect-web/api/v1/ingest` and
`/detect-web/api/v1/ingest/bulk` use the same pre-conversion byte reader, with
fixed bounds of 262,144 and 16,777,216 bytes respectively. Their existing
admin/analyst role checks run first; a size annotation grants no ingest
identity or permission. Oversized requests return 413 without admitting any
events, even when the JSON is malformed or UTF-8 uses multiple bytes per
character. Bulk's existing 1,000-line and per-line checks still apply after
admission, as do its accepted/rejected counts and queue backpressure behavior.

Search's decoded UTF-8 body, event count and per-event checks still apply.
HIPS's final forwarding-envelope limit also still applies because server
metadata and reserialization can increase size after raw admission. Configure
proxy limits and client batch sizes consistently with the owning service.

## Falco and endpoint envelopes

HIPS accepts Falco's native `time`, `source`, `tags` and `output_fields` in
addition to the existing endpoint fields. These correspond to the
[Falco JSON output contract](https://falco.org/docs/concepts/outputs/channels/).
The legacy `fields` object remains supported. The two maps together permit
at most 128 scalar entries, keys of 1–128 characters, values of at most 4,096
characters, and at most 65,536 characters of combined keys/values. Values may
be strings, finite numbers, booleans or null; nested maps and arrays are
rejected. Tags allow at most 64 nonblank strings of at most 128 characters.
Malformed/over-limit envelopes and blank-only events return 400 before durable
admission. These are character limits, not a byte-size claim.
The final HIPS forwarding envelope also has a UTF-8 byte limit (default
262,144 bytes); oversized serialized events return 413 and roll back history,
heartbeat and forwarding intent together. This catches JSON escaping expansion
that character limits alone cannot bound to Search's default event budget.

HIPS preserves the native envelope in history and forwarding payloads. It also
adds `timestamp` from `time` (preferred) or legacy `ts`, and `process` from
legacy `proc`, so generic JSON parsing can recognize those agent aliases.

The AUTO/Falco parser combines legacy `fields` with native `output_fields`;
native entries win on duplicate keys. It maps known process, host, file,
network and user fields, keeping `user.uid` as `user.id` rather than replacing
`user.name`. Canonical event source remains `falco` for the existing detection
routing policy; the producer's source such as `syscall` is `ecs.falco.source`.
The original merged fields and tags are retained as JSON strings at
`ecs.falco.output_fields` and `ecs.falco.tags`. Arbitrary vendor field names do
not create separate OpenSearch fields or override tenant/routing metadata.
Unmapped context remains available in stored event details; individual keys
inside the JSON string do not gain an independent query contract, and values
beyond the existing keyword indexing limit are retained but not indexed.

Explicit JSON source formats continue to follow the configured JSON parser;
this does not silently change a source's selected parser. Parser changes apply
to new ingestion, not to already persisted canonical events. Drain existing
HIPS forwarding work with compatible Search parser versions before rollout:
the additional canonical evidence changes the content fingerprint, so a retry
of a previously accepted event across parser versions can correctly return
409. Do not bypass that conflict or overwrite the existing canonical record;
verify its receipt before any operator replay. No schema migration is required.

## Canonical event contract

Kafka events use the versioned envelope in `schemas/canonical-event-1.0.json`.
`schemaVersion` and `tenantId` are explicit envelope fields;
`fields.tenant_id` remains only as a compatibility bridge for existing
Detection rules. During a rolling upgrade, consumers accept envelopes without
`schemaVersion` as legacy 1.0, but reject explicitly unsupported versions to
the topic DLQ.

The field registry in `schemas/field-registry.json` is the contract between
normalization, OpenSearch mappings, aggregation, and Detection content.
Additive revisions add a schema file and pass `build/verify-event-schema.py`.
The checker compares adjacent checked-in versions in numeric order, including
nested types/required fields, enums, constants, object/array contents, and
length/range constraints. It conservatively rejects changed structures with
unsupported JSON Schema keywords; it is not a general implication solver or
a substitute for preserving published schema history in review. A new typed
optional property can narrow a previously open object's accepted values and
is not automatically additive. Breaking wire changes need an explicit
producer/consumer migration and checker-contract update; the current envelope
marker remains `1.0`. ECS-style canonical keys remain the internal source of
truth; OCSF mapping is an export concern, not a second internal event model.

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

- `REGEX`: RE2/J named groups (`(?<srcip>...)`) or numeric groups with
  `mapping` entries (`group` -> `field`). Common aliases such as `user`,
  `src_ip`, and `srcip` are normalized to canonical ECS keys. Matching uses a
  linear-time engine with an 8,192-character pattern limit, 4,096-instruction
  compiled-program limit, and 262,144-character input limit. Java-only
  constructs such as backreferences, lookarounds, and atomic groups are not
  supported. Existing persisted rules using unsupported syntax are skipped by
  the ingestion pipeline until they are edited or replaced with a supported
  expression; review such rules before upgrading.
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
live ingest use the same `ParseRuleExecutor`. The local compiled-pipeline
cache holds at most 2,048 source/binding combinations; an evicted pipeline is
compiled again when needed. A local rule mutation invalidates its tenant's
cache immediately, while a change on another SEARCH replica is observed
within the configuration-cache TTL (60 seconds by default).
`SOCP_SEARCH_CONFIG_CACHE_TTL_MS` must be positive; zero cannot disable
cross-replica refresh.

The owning service limits each tenant to 512 effective parse rules, of which
at most 32 may be global (no `sourceId`), and rejects serialized rules above
64 KiB before database write. These limits bound the fallback scan and new
configuration growth; an older catalogue already over the limit stays
readable and editable so operators can reduce it. Global rules are fallback
candidates only for sparse events without explicit source bindings. A source
can bind up to 100 rule IDs and evaluates them in the configured order.

Parsing does not create an alert by itself. It supplies normalized fields such
as `fields.category`, `fields.src_ip`, `fields.user`, and the corresponding
`ecs.*` values. The existing Detection `RuleSpec.match` / `steps` conditions
then evaluate those fields and create alerts through the detection outbox.

Raw syslog and Vector JSON envelopes containing syslog preserve `process.name`
and normalize known applications into the Detection source vocabulary:
`sshd`/`sudo`/`su` become `auth`; `auditd`, `linux`, `edr`, and `falco` retain
their application category. Unknown applications remain `syslog`. RFC sshd
messages reuse the plain authentication parser for user, source IP and action.
This lets source-scoped routed rules receive the same dimensions with either
transport. The transport vendor remains `syslog`, and the authenticated
collector and tenant remain authoritative. Reprocessing historical records
with the same producer event IDs must respect the immutable-content contract;
source normalization changes can intentionally return a conflict on old IDs.

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

OpenSearch results are authoritative only after a complete search. Requests
set `allow_partial_search_results=false`; the reader also checks `timed_out`,
early termination, shard completion, and the hits response before accepting
HTTP 200 as success. This follows the
[OpenSearch search response contract](https://docs.opensearch.org/latest/api-reference/search-apis/search/).
Incomplete or malformed responses use the existing explicitly degraded local
cache path; if the cache is unavailable, the API returns 503. Interactive
results expose `degraded` and `source`, and exports retain the corresponding
`X-SOCP-Search-*` headers. Partial shards never become an authoritative empty
result or an apparently complete histogram.

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
