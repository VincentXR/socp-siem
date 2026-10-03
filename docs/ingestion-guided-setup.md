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
- Task counters are process-local observations by tenant and collector tag.
  They are not a fleet-wide source ledger; a restart or another API replica
  can show different observations.

## Safe preview and parser binding

`POST /search-config/api/v1/sources/{id}/preview` accepts a nonempty sample
(up to 65,536 characters) and runs the normal source normalizer. The API
returns `writesEvent: false` and `mayTriggerDownstreamActions: false`. It has
no persistence, event admission, telemetry or downstream dispatch step.
Sample normalization success therefore does not mean ingestion succeeded.
Editing a sample or changing the source invalidates an in-flight preview.

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

Output edits use `{target:{name,type,uri,authToken,enabled},credentialAction}`.
Choose `KEEP` to preserve the saved credential, `REPLACE` with a nonblank new
`target.authToken`, or `CLEAR` to remove it. The editor defaults to `KEEP`;
null, blank, omitted or redacted credentials never imply clearing. Missing
credential intent and old flat update payloads are rejected. Stable output IDs,
source bindings and creation timestamps are retained. Read responses return only
`authTokenConfigured`. Platform collector credentials are only used for the
reserved platform ingest destination, never for a tenant target that merely
claims the `GLS_INGEST` type.

## Acknowledgement semantics

The ingest response reports `acknowledged = accepted + quarantined`.
`accepted` includes newly created records and identified duplicates;
`created` and `duplicates` distinguish them. Quarantined parse failures are
durably retained but are not searchable admitted events. `forwarded` measures
the optional debug forwarding path, not formal Detection delivery. An HTTP
2xx does not prove OpenSearch indexing, rule evaluation, alert creation or
SOAR completion. Inspect each downstream boundary separately when required.
