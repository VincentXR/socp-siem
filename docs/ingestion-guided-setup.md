# Guided source onboarding

The ingestion workbench starts with a log source and provides five steps:
connection, a raw sample, an output destination, manual collector deployment,
and verification of a real indexed event. Metadata categories, field
catalogues, custom parsers, enrichment and advanced collector settings are
optional. A source can be saved as a disabled draft before connection fields
are complete.

## Configuration and runtime evidence

- **Saved** means the source configuration was persisted. **Enabled** is its
  desired state. Neither starts, restarts or stops a remote collector.
- **Applied state is unknown**: there is no collector configuration receipt.
  The setup fingerprint identifies the inspected non-secret saved configuration; it is
  not a remotely applied version or an execution acknowledgement. Credential
  rotation is not represented in this public fingerprint.
- Rendering a source produces redacted Vector configuration. Supply the
  credential securely, run `vector validate --no-environment` on the actual
  deployment configuration and manually deploy/reload the collector.
- The first-event check reads the authoritative search index, requires an
  exact server-normalized `source_id`, and searches the last 24 hours. It
  shows the observed event ID and event time. Degraded/local-cache results
  remain inconclusive. An indexed event does not prove application of the
  latest configuration, sustained throughput or downstream action completion.
- The task table uses server-side pagination and exposes the complete saved source catalogue, including sources beyond the legacy 500-row list cap. Health badges above the table explicitly cover observed collectors only, independently of the configured source total.
- Task-row counters are process-local observations by tenant and server-resolved source ID (`telemetryScope: CURRENT_API_REPLICA_AND_SOURCE_ID`). A shared authenticated collector can feed multiple sources without leaving their rows permanently idle. The event retains its authenticated collector identity. Aggregate counters and their health distribution remain per authenticated collector and are not double-counted from source observations.
  Neither population is a fleet-wide source ledger; a restart or another API replica
  can show different observations.

## Safe preview and parser binding

`POST /search-config/api/v1/sources/{id}/preview` accepts a nonempty sample
(up to 65,536 characters) and runs the normal source normalizer. The API
returns `writesEvent: false` and `mayTriggerDownstreamActions: false`. It has
no persistence, event admission, telemetry or downstream dispatch step.
Sample normalization success therefore does not mean ingestion succeeded.
Editing a sample or changing the source invalidates an in-flight preview.

For fixed JSON sources, the renderer's transport envelope (`source_id`,
`collector_tag`, `parse_format`, `message`) is unwrapped before validating the
source body. Both built-in JSON parsing and an explicitly bound JSON rule use
that source body rather than the envelope. A JSON envelope cannot turn invalid source text into an accepted
JSON record. The original envelope remains in quarantine, and a bound custom
parser may repair its source body before an idempotent replay. Ordinary JSON
application events with plain message text remain valid JSON records.

Parser applicability scope is separate from source binding. Save a parser,
then explicitly bind the saved parser to the intended source. With explicit
bindings, enabled in-scope rules execute in binding order and the first match
wins. Without explicit bindings, built-in parsing is followed by eligible
fallback rules when the base event is sparse. Disabled, missing and
out-of-scope bindings are identified in setup. Preview the whole source
pipeline after a repair.

The existing `/ingest/tasks/{id}/test` endpoint is an explicit real-event
injection endpoint, not a safe connectivity check. Guided onboarding does
not call it. Quarantine replay also writes a real event and can trigger
Detection, alerts and response actions; the workbench asks for confirmation.

## Supported connections and outputs

Native Vector rendering supports FILE, TCP/UDP SOCKET/SYSLOG and KAFKA.
FILE paths are collector-local. Kafka requires actual bootstrap servers and
a topic; the renderer no longer substitutes demo files or broker addresses.
TLS listeners, non-UTF-8 decoding, and Kafka authentication require a managed
collector configuration rather than an apparently successful partial render.
Windows Event, Agent, HTTP/API, database and cloud sources require an
external managed connector. The console can record their source identity
but does not provision or deploy those connectors.

Outputs support NDJSON over HTTP(S), with `GLS_INGEST` and `HTTP` target types.
Legacy `SEARCH`, Kafka and OpenSearch bulk records remain readable, but writes
and rendering reject their unsupported types; edit them to a supported receiver. Static output
validation does not contact the supplied URI or write an event. Embedded URI
credentials and fragments are rejected. An external destination does not
necessarily return events to SOCP, so its own receipt must be verified there.
Known `/_bulk` endpoint paths (including an index prefix, encoded endpoint name
or trailing slash) are rejected during validation, create/update and rendering
of an older saved target. The renderer emits event NDJSON rather than the
action/document pairs required by OpenSearch Bulk; selecting the generic HTTP
type does not make that receiver compatible.

Output edits use `{target:{name,type,uri,authToken,enabled},credentialAction}`.
Choose `KEEP` to preserve the saved credential, `REPLACE` with a nonblank new
`target.authToken`, or `CLEAR` to remove it. The editor defaults to `KEEP`;
null, blank, omitted or redacted credentials never imply clearing. Missing
credential intent and old flat update payloads are rejected. Stable output IDs,
source bindings and creation timestamps are retained. Read responses return only
`authTokenConfigured`. Platform collector credentials are only used for the
reserved platform ingest destination, never for a tenant target that merely
claims the `GLS_INGEST` type.

## Detection rule changes

New or disabled rules are saved, tested in isolation and explicitly activated
by a publisher. Saving edits to an already active rule immediately updates
live detection; it does not keep a separate unpublished draft alongside that
active version. Test the edited draft before saving, review its scope and use
the revision token to protect against concurrent edits. Isolated testing does
not ingest events or create formal alerts. Verify new real events after the
change; old alarms and tenant-wide engine counters do not prove the revision
matched. Deployments requiring separate review or staged rollout of active
content must provide that release process explicitly.

## Acknowledgement semantics

The ingest response reports `acknowledged = accepted + quarantined`.
`accepted` includes newly created records and identified duplicates;
`created` and `duplicates` distinguish them. Quarantined parse failures are
durably retained but are not searchable admitted events. `forwarded` measures
the optional debug forwarding path, not formal Detection delivery. An HTTP
2xx does not prove OpenSearch indexing, rule evaluation, alert creation or
SOAR completion. Inspect each downstream boundary separately when required.
