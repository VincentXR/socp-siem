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

AUTO vendor extraction (Falco, Sysmon, auditd) retains explicit producer event
IDs and collector position fields before normalization. The extracted vendor
source still wins over a vendor-native `source` label. Retrying the same vendor
event therefore reaches the same event/outbox identity even without an HTTP
`Idempotency-Key`.

For Vector AUTO envelopes, a JSON object in `message` is parsed through the
same vendor/AUTO chain as a directly submitted line. Inner canonical fields
override envelope fields, and unrelated collector metadata is retained. JSON
envelope expansion is limited to four nested messages; deeper envelopes and
malformed inner JSON enter parse-failure quarantine. Ordinary unstructured
text remains a valid AUTO fallback. Explicit source JSON format continues to
use JSON parsing rather than silently selecting a vendor parser.

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

Enabled FILE sources require a nonblank path, and SOCKET/SYSLOG require a
listen address. KAFKA requires a topic and explicit reachable bootstrap servers;
the renderer does not silently assume a deployment broker. Disabled configurations may remain drafts;
enabling and rendering validate them again. The workbench sample parse is a
non-ingesting preview: it does not prove the remote collector is running or
trigger detection. After saving, validate and manually apply the generated
configuration on the collector, then check recent reception and source-scoped
log search. Category severity and field capability flags are descriptive
catalogue metadata, not runtime severity defaults or index-mapping changes.

Guided setup's indexed-event check explicitly uses **event time**, not receipt
time. Its default last-24-hour window can exclude a newly imported historical
log; select **all retained history** to remove that time filter. Both modes
keep the exact source query, authenticated tenant scope and 20-event result
limit. Search links preserve that range, and a matched event opens by ID with
no recent-time filter. Only a non-degraded OpenSearch response confirms an
observed indexed event; an existing historical record does not prove the
collector is running, that current configuration was applied, or that
downstream actions completed. The current SPL field catalogue does not allow
range comparisons on `fields.ingested_at`, so this check must not silently
claim to query receipt time.

For native Vector sources (`FILE`, `SOCKET`, `SYSLOG`, and `KAFKA`), the
renderer emits a source, its transform, and a bound sink input. The other
catalogue types (`WINDOWS_EVENT`, `AGENT`, `HTTP_API`, `DATABASE`, and `CLOUD`)
describe externally managed collectors and therefore emit onboarding comments
only; they never create a transform that references a nonexistent Vector
source. Mixed catalogues remain valid Vector configurations.

For `FILE`, `frequency` controls `glob_minimum_cooldown_ms`, the file discovery
interval. It does not filter old files. `ignoreOlderSeconds` is the separate,
optional file-modification-age policy and is the only setting that emits
`ignore_older_secs`; leaving it empty allows `readFrom=beginning` to read an
older historical file.

When a persisted source configures `timeField`, normalization reads that field
from the parsed event. Absolute timestamps keep their own offset; local ISO or
`yyyy-MM-dd HH:mm:ss[.fraction]` values use the source `timezone`. The resolved
instant is written identically to the PostgreSQL event and canonical Kafka
payload. A present but invalid configured timestamp is a parse failure rather
than silently becoming the receipt time. Sources without a usable configured
event time retain the explicit generated-time fallback marker.
An unset timezone means UTC. Editing an existing source preserves that unset
value (shown as "Default (UTC)" in the workbench); the new-source form's
Asia/Shanghai default must not be reapplied to existing sources.

Both `POST /search-config/api/v1/render` and
`GET /search-config/api/v1/sources/{id}/vector-config` return `text/plain`
configuration, not a JSON `ApiResult`. Clients must negotiate and read text
while retaining normal JSON error handling, authentication and cancellation.
Rendering does not apply the configuration to a collector.

### Future source clock skew

**The default 30-second allowance rejects the two-minute clock error that
would suppress the shipped one-minute `AUTH-BRUTE` rule.** It does not exceed
the shortest shipped stateful window or `DROP` lateness budget (both 30
seconds). This is bounded source-skew protection, not a universal guarantee
for custom shorter windows or delayed sources. An explicitly widened `5m`
allowance admits that two-minute error and can reintroduce the missed alert.
**Direct Kafka producers bypass this
HTTP ingress check** and must enforce equivalent trusted validation themselves.

New Search ingress checks the resolved source timestamp against one server
receipt instant, also persisted as `ingested_at`. The default maximum future
skew is **30 seconds**, inclusive. An event beyond that allowance becomes a
durable parse-failure quarantine entry with its original raw line and reason;
it is not published to Detection and cannot advance a grouping-key watermark.
The batch response counts it in `parseFailed` and `quarantined`, not `accepted`.
It counts as `acknowledged` only after quarantine persistence succeeds;
quarantine storage failure remains a retryable 503.

Configure `SOCP_INGEST_MAX_FUTURE_SKEW` (for example `30s`, `5m`, or `PT10M`)
to tighten or expand the allowance. Zero permits no future skew; a negative or
invalid duration fails startup. `SOCP_INGEST_EVENT_TIME_ENABLED=false`
explicitly disables the check for a controlled synthetic/future-dated import.
These map to `socp.ingest.event-time.max-future-skew` and
`socp.ingest.event-time.enabled`. They are deployment-owned settings, never
collector-controlled fields. Set them consistently across Search API replicas
and Detection workers when using the optional local HTTP ingress.

The same default applies to Detection's local `/api/v1/ingest` endpoint (400
on a rejected timestamp) and `/api/v1/ingest/bulk` (`rejected` count per line).
Rule dry runs deliberately permit future-dated simulation. Neither ingress
path clips accepted timestamps, limits historical age, or changes event IDs.
Configured source time fields and timezones are resolved before validation;
collector-supplied `ingested_at` remains quarantined user evidence.

This guard bounds tolerated clock error, not event-time lateness. Review the
shortest active rule window **and** the smallest `DROP` allowed-lateness
budget; keep the ingress allowance within both budgets, leaving room for
expected ingestion delay and out-of-order delivery, and synchronize source
clocks. Source clock skew **plus** normal delivery delay consumes these
budgets: the 30-second default leaves no delay margin at the shortest shipped
30-second window, so delayed sources may need a tighter allowance or an
explicitly reviewed rule-time policy. This is configurable risk reduction,
not a general guarantee: an accepted future timestamp can
still make sufficiently delayed records late or expire window evidence.
Existing Kafka deliveries,
durable journal replay and snapshots keep their original event-time semantics
and do not consult a new wall clock or the current ingress setting. Already
poisoned snapshots are not repaired by this change. Historical imports into an
existing live key still obey its monotonic watermark and late-event policy;
use isolated detection state/dry runs when independent historical evaluation
is required. Direct broker producers must enforce equivalent trusted ingress
validation; the HTTP guard is not a broker admission or ACL boundary.

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
uncommitted HTTP request should be retried. Internal groups of 200 are flush
batches inside that request's transaction, not separate acknowledged commits.

Rendered and reference Vector HTTP sinks use the pinned Vector version's
effectively unlimited retry-attempt default, with exponential backoff starting
at two seconds and capped at 30 seconds. No finite attempt limit discards an
event during retryable 429/5xx responses or connection failures. Events keep
their identity in the disk buffer; when its 256 MiB capacity fills, the sink
applies backpressure. This does not make non-retryable HTTP responses or
non-durable upstream sources lossless. Re-render/redeploy existing collector
configurations to remove the old five-attempt limit; changing Search alone
does not change deployed Vector configuration.

The generated-config runtime regression can use the pinned container image
(`SOCP_TESTCONTAINERS=true`) or an explicit native binary of the same version
(`SOCP_VECTOR_BIN=/path/to/vector`). Run
`bash build/mvnw.sh -pl services/search-config -am -Dtest=VectorConfigRendererContainerTest -Dsurefire.failIfNoSpecifiedTests=false test`.
It validates the generated configuration, returns eight HTTP 503 responses
before a 200, checks unchanged replay bodies, and parses the received AUTO
envelope with the production registry. The separate historical-file container
case still requires Docker.

The ingestion publisher queries the next ordered head after acknowledgements,
including when only one routing key is active. The first nonempty head query
has a five-second database timeout, then starts the configured delivery
admission budget (two seconds by default). Subsequent queries share that same
admission deadline and the round limit. This is not a two-second wall-clock
limit for the whole invocation: database selection and in-flight acknowledged
delivery can take longer. A failed/contended head is not repeatedly attempted
within the same drain. Durable predecessor fencing, claim tokens and retry
backoff remain in force across worker replicas.

Selection ranks pending rows by tenant/routing key and sequence, admitting at
most 16 candidates per key and 200 overall. A key whose oldest row is backing
off or whose row is already processing is not admitted. Candidates for each
key are delivered sequentially, and a failed claim, send or completion stops
the remainder of that key's local batch for the drain. Every row still needs
its own durable predecessor/claim check and broker acknowledgement; selecting
a batch never owns or publishes its successors in advance.
V17 adds `(status,tenant_id,routing_key,sequence_no)` so that ranking
only scans the active state; claim-time predecessor probes use the same index.
It supersedes the equivalent key-first equality-probe index added in V16.
For an existing large PostgreSQL outbox, prebuild both V16's
`idx_ingestion_outbox_key_state_sequence` on `(tenant_id,routing_key,status,sequence_no)`
and V17's `idx_ingestion_outbox_state_key_sequence` on
`(status,tenant_id,routing_key,sequence_no)` with
`CREATE INDEX CONCURRENTLY` outside a transaction,
verify that both are valid, then run the normal migrations. They skip the
existing indexes and V17 removes the superseded V16 index. Otherwise plan the
normal index builds in a maintenance window;
do not delete outbox history or reset delivery state to accelerate the upgrade.

The rendered Vector envelope contains a stable `source_id`. The request
credential still determines the tenant and trusted collector identity; body
metadata is only used to find the source inside that tenant. The server uses
the persisted `LogSource.parseRuleIds`, so changing a body field cannot select
another tenant's rules.

Malformed or over-budget event data is counted as a per-line parse rejection.
Built-in `parse.error`, fixed-format mismatches, and an explicit source rule
set with no successful match enter durable quarantine instead of becoming
accepted canonical events. A successful configured rule may repair a built-in
parse failure. The response increments `parseFailed` and `quarantined`, and
includes the line in `acknowledged` only after quarantine commits. Replay uses
the current parser and keeps still-invalid evidence pending. AUTO free text
and an unbound optional fallback rule that simply does not match stay valid.
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

CEF uses seven pipe-delimited header fields followed by extensions. Header
`\|` and `\\` escapes are decoded; extension values continue until the next
unescaped `key=` boundary, so `msg=connection denied by policy dpt=80` retains
both the complete message and destination port. Extension `\=`, `\\`, `\n`,
and `\r` escapes are decoded. Numeric severity maps 0–3 to LOW, 4–6 to MEDIUM,
7–8 to HIGH, and 9–10 (or `Very-High`) to CRITICAL.

LEEF 1.0 has `LEEF:1.0|Vendor|Product|Version|EventID|attributes`; attributes
are separated by tabs, and `sev` is an attribute, not a header field. LEEF 2.0
adds a delimiter field before attributes, accepting a literal character or
hex such as `^`, `0x5E`, or `x09`. Values may contain spaces and equals signs.
The numeric severity scale increases toward 10. Unknown attributes retain a
`leef.` prefix. The former nonstandard Name/Severity header layout must be
converted at the producer. AUTO also unwraps CEF/LEEF inside Vector JSON
`message`, retaining the envelope's event identity.

RFC5424 parses PROCID, MSGID and structured data separately from MSG; SD
parameters are retained as `ecs["syslog.sd.<SD-ID>.<PARAM-NAME>"]`. Numeric
PROCID also populates `process.pid`, while other PROCID values remain in
`syslog.procid`. Syslog notice normalizes to MEDIUM. Malformed structured data
is a parse failure and follows the existing durable quarantine path.

RFC3164 and plain SSH auth timestamps lack a year and timezone. At ingestion,
the source's configured timezone (UTC if absent) and trusted receipt time
select the nearest valid date from the receipt year and its adjacent years.
English month names, padded single-digit days, leap days and New Year rollover
are supported; the normal future-skew guard still applies after inference.
This cannot disambiguate archival logs from arbitrary past years. For such
replays, supply an explicit ISO timestamp through the configured time field
or convert the producer to RFC5424. Preview retains the yearless source text;
year inference happens at the ingestion boundary.

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
