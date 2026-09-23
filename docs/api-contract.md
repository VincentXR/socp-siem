# API contract

The running Spring applications are the canonical HTTP contract. Servlet
services expose `/v3/api-docs` below their context path; the reactive gateway
exposes the same endpoint at its root. No service ships the springdoc
interactive UI bundle on its classpath, so there is no browser docs page to
open; review the documents with an OpenAPI client or the generated SDK instead.
For example:

```text
http://localhost:18092/v3/api-docs                 # gateway's own contract
http://localhost:18092/alert-web/v3/api-docs       # routed Alert contract
http://localhost:18080/alert-web/v3/api-docs       # direct Alert service
```

The generated document is the source for client generation and review. The
checked-in [`soar-openapi.yaml`](soar-openapi.yaml) is a reviewed
snapshot for design review and offline clients; it must be refreshed whenever
the running SOAR routes or schemas change, and it is not a substitute for the
runtime document. `build/verify-soar.py` checks the snapshot's route and schema
sentinels without pretending to generate OpenAPI from source. SOAR keeps one
unversioned `/api/...` surface and the `soar.*` schema identifiers; other
platform services may retain their own compatibility policy. Both documents
describe the JWT bearer and `X-Tenant-Id` security schemes. A route is not
considered a new contract just because the implementation moved between
deployment units; preserve the context path and response envelope.

## Compatibility policy

- Services outside SOAR retain their existing compatibility policy. SOAR uses
  the single unversioned `/api/...` surface described above.
- Existing `/api/alarms` is retained as a compatibility route until clients
  have migrated; it must remain tenant- and role-protected.
- Breaking request or response changes require a new version and a migration
  note.  Additive fields should be ignored by clients.
- Pagination uses the shared `page`, `size`, `total`, and `items` shape where a
  list contract supports pagination.

SOAR's unversioned compatibility routes retain their historical 0-based
`page` request parameter. Every paged SOAR response includes the complete
`page`, `size`, `total`, `totalPages`, and `items` metadata; callers must use
`totalPages` rather than inferring continuation from the returned item count.

### Alarm list and export migration

`GET /api/v1/alarms` is the canonical paged alarm read when `page` is supplied;
its `data` value is `PageResponse` (`items`, `total`, `page`, `size`, and
`totalPages`). The legacy array response remains available when pagination is
omitted or only `size` is supplied, but the server still reads only the first
bounded page. Clients should migrate to the paged form before the compatibility
route is retired.

`GET /api/v1/alarms/export` and the `/api/alarms` alias require the `admin` or
`analyst` role. Exports are counted before the response is opened, stream from
database pages of 500 rows, and accept a `limit` between 1 and 100,000 (default
10,000). A matching set above the requested limit returns HTTP 413 with the
normal `ApiResult` error envelope; it is never silently truncated and cannot
materialise an unbounded tenant result in the JVM. The `format` parameter stays
compatible with the existing `csv` and `json` representations.

The workbench must inspect the envelope code even when HTTP status is 200:
non-zero business codes are surfaced as `ApiBusinessError` instead of being
unwrapped as successful data. This is covered by the frontend API-response
contract tests and applies to both paged and legacy compatibility responses.

Detection's [rule catalogue contract](detection-rules.md#rule-catalogue-queries)
adds filtered pagination, direct rule lookup, compact selector/ID projections,
and ACTIVE technique coverage under `/detect-web/api/v1/rules`. The unpaged
array remains bounded for compatibility and must not be used as a complete
catalogue. All projections are scoped by the owning service's tenant context.

Detection rule writes follow the [conditional authoring contract](detection-rules.md#conditional-rule-authoring):
create/import is create-only (409 for an existing ID); update, activation and
deletion require the reviewed head's `If-Match`. Missing specific validators
return 428, stale validators return 412, and responses retain `ApiResult`.
Restoring a deleted rule instead uses an explicit `If-None-Match: *`.
Current full specs include `revisionToken`; the detail/mutation `ETag` quotes that
token. This is separate from SOAR's version contract below.

## Error envelope and message copy

There is exactly one failure channel. A request that cannot be served fails
with a non-zero envelope `code` and a synchronized HTTP status, produced by
`GlobalExceptionHandler` from `ApiException.notFound(...)`,
`ApiException.badRequest(...)`, or an explicit `ResponseStatusException`.

- `data` never carries error semantics. A success envelope (`code=0`) must not
  contain an `error` marker or a `not_found` sentinel: `ApiResult.ok(data)`
  with `{"error": ...}` inside `data` is a second, contradictory failure
  channel that clients such as `ServiceCall.ok()` and `unwrapApiBody` read as
  success. Domain outcome fields that describe the state of a resource or of an
  asynchronous job (`status`, `failed`, `truncated`, `duplicate`, per-action
  receipts) are not error semantics and stay allowed.
- Machine gate: `build/verify-contracts.py` rejects the `not_found` sentinel in
  any service controller or service class and any `"error"`/`"not_found"`
  literal inside an `ApiResult.ok(...)` argument. Reviewed legacy debt lives in
  `build/envelope-error-data-baseline.txt` and only ever shrinks.
- Service-to-service callers keep their receipt checks (`SOAR` case/notify
  handlers, the AI investigation append) as defense in depth: a 2xx response
  whose body carries an error marker or lacks the expected receipt must not
  terminalize durable state.

`message` is operator-facing copy, because the workbench renders the backend
sentence when it is meaningful. Keep it a complete, actionable sentence in one
language:

- say what happened and what to do next; domain detail (permission names,
  field names, resource ids) is welcome;
- never put an internal exception text, driver/SQL fragment, stack, internal
  URL, or bare transport code in it — `GlobalExceptionHandler` maps an
  unexpected exception to one fixed sentence and logs the original type and
  message under the request `traceId`, which is also returned in the envelope;
- never use a `snake_case` token as the whole sentence; expose a stable machine
  value in `data` (or the `code`) and keep `message` human-readable;
- a missing diagnostic reason falls back to a fixed per-status sentence, so the
  client never sees an enum string such as `404 NOT_FOUND`.

### Incident list and export migration

`GET /api/v1/incidents` uses the shared `PageResponse` shape with one-based
`page` values. `GET /api/v1/incidents/export` is restricted to `admin` and
`analyst`, counts the tenant result before opening the response, and streams
summary rows in 500-row database pages. The `limit` parameter is required to
be between 1 and 100,000 (default 10,000); a larger matching set returns HTTP
413 in the normal `ApiResult` error envelope instead of being truncated or
materialised without a bound. Timeline details remain available through the
paged `/incidents/{id}/timeline` resource.

`GET /api/v1/incidents/{id}` retains the existing `{found, case}` envelope (a
missing or foreign-tenant ID has `found: false`). Its embedded timeline is a
preview of at most the first 500 normalized events, bounded in the database
query. Use the one-based timeline resource to read beyond that preview. Both
preview and pages order by timestamp then event ID, so timestamp ties have a
stable order; this is not a snapshot across concurrent appends. No migration
is required for the read bound or tie-break ordering.

Search export remains capped at 5,000 events and is restricted to `admin` or
`analyst`. Report archive listing accepts a bounded `limit` (1–5,000, default
500) and never iterates beyond that tenant-owned prefix window; callers should
use the `truncated` flag when the archive contains more objects.

Report object-storage operations return HTTP 503 with the normal error envelope
when storage is disabled or an SDK operation fails. A failed list never returns
its accumulated partial results as a successful page; a successful empty list
still returns count zero. Reaching the requested limit stops iteration before
another SDK page is requested. Daily/trend reads do not require object storage.
`POST /api/v1/reports/archive` now publishes one versioned JSON snapshot at
`reports/<tenant>/<yyyyMMdd>/snapshot-<uuid>.json`. Its success data is
`{archived: true, day, archiveKey, schemaVersion: 1}`; consumers must replace
use of the former `dailyKey`/`trendKey` fields with `archiveKey`. The workbench
ships with this response change. The object contains `{schemaVersion: 1, day,
daily, trend7d}`, retaining each source's full provenance and degradation fields.
Existing `daily.json` and `trend7d.json` files remain listable and downloadable;
there is no automatic rewrite or deletion of old archives.

Both sources are collected and serialized before one object PUT, and success
requires its acknowledgement. Every invocation uses a fresh key, so retries and
concurrent requests cannot overwrite earlier snapshots. A lost acknowledgement
can still leave a complete extra snapshot; retry is not exactly-once, and failure
does not imply rollback. Source queries remain independent: bundling their
results does not establish a common database snapshot. Storage retention should
account for one new object per successful or ambiguously acknowledged request.
Archive listing and signed-link issuance both require admin, analyst or viewer;
archive creation requires admin or analyst. Tenant scope comes from the
validated identity, not request role/tenant headers.

The search-config source catalogue accepts one-based `page`, bounded `size`
(maximum 500) and optional name substring `q` (at most 128 characters). Paged
reads return the shared `PageResponse`, ordered by stable source ID within the
tenant. `q` is matched case-insensitively within the server-owned tenant scope;
literal wildcard characters are ordinary text. Calls without `page` retain the
legacy first-page array shape. The Workbench pages the management list and uses
bounded name search for source selection; it never treats a first page as the
complete catalogue. Concurrent catalogue writes can shift offset pages, so
pagination does not promise a cross-request snapshot.

The parse-rule catalogue has the same one-based `page`/`size` envelope
(`size` at most 500), with optional case-insensitive name substring `q` (at
most 128 characters). Its stable order is rule `order`, then ID. A no-page
call retains the legacy array shape, limited to the first 500 rules. `GET
/search-config/api/v1/parse-rules/{id}` reads one tenant-owned or packaged
rule; `GET /search-config/api/v1/parse-rules/batch/resolve?ids=...` resolves 1–100
IDs (at most 6,000 characters combined) in request order, omitting unknown
or tombstoned IDs. These read routes use the authenticated tenant, not a
request-supplied tenant. New tenant catalogues admit at most 512 effective
parse rules, including packaged rules, and at most 32 rules with no source
scope. A serialized rule is limited to 64 KiB. Create/update/delete decisions
run in fresh SERIALIZABLE transactions and retry serialization conflicts;
existing over-limit catalogues remain readable/editable but cannot add rules
until below the limit. Pages are independent reads, not a snapshot. The
current page implementation materializes the bounded tenant catalogue before
slicing; operators should clean up historical over-limit catalogues before
relying on this bound for read memory.
Across tenant catalogues, deleting a tenant-created item removes its overlay
row; deleting a packaged item retains a tombstone so it stays hidden after a
restart. Existing historical tombstones are not removed automatically.

`GET /search-config/api/v1/outputs` lists tenant-owned output targets and
redacts configured credentials. Platform fallback targets are not included.
New creations are limited to 128 targets per tenant; concurrent creations
enforce the limit in a fresh SERIALIZABLE transaction with bounded retries.
Deleting a tenant-owned target removes its catalog row. Historical over-limit
catalogues remain readable, but further creation returns 400 until the count
falls below the limit. Sources bound to a deleted target retain their binding
and fail closed during rendering until an operator updates the source.

### Manual Detection admission

The worker's `POST /detect-web/api/v1/ingest` and `/ingest/bulk` require
admin/analyst and reject raw bodies above 262,144 and 16,777,216 bytes,
respectively, before JSON/String conversion. HTTP 413 uses the normal error
envelope and no events reach the engine from that request. Bulk retains its
existing per-row accepted/rejected semantics after raw admission; these
verification routes do not replace the canonical Kafka path. See
[raw HTTP admission](ingestion-parsing.md#raw-http-admission).

### Endpoint collection acknowledgement

The collection envelope supports native Falco context and bounded scalar
`output_fields`/legacy `fields`, as described in [ingestion parsing](ingestion-parsing.md#falco-and-endpoint-envelopes).
The [raw HTTP admission limit](ingestion-parsing.md#raw-http-admission)
rejects oversized requests with 413 before JSON parsing and durable writes,
including bodies without Content-Length; HIPS defaults to 262,144 bytes.
Invalid structured fields or blank-only events return 400 before admission.
The final serialized forwarding envelope is also bounded (default 262,144
UTF-8 bytes); exceeding it returns 413 and rolls back the transaction.

`POST /hips-web/api/v1/events` requires an authenticated collector or service
identity. `accepted: true` confirms atomic storage of HIPS event history and a
durable forwarding task. Admission fails with 503 when either the global or
tenant backlog is full; the history/heartbeat transaction also rolls back.
`forwarded: true` additionally requires a successful Search ingestion response
with numeric `code: 0`, `data.accepted: 1`, `data.acknowledged: 1`, and
`data.skipped: 0`. A durable duplicate acknowledgement also counts as forwarded.
An HTTP 2xx response alone, missing counters, or malformed response body does
not establish forwarding. This is acknowledgement of Search ingestion, not
completion of detection or creation of an alert.

HIPS sends `Idempotency-Key: hips:<stored-event-id>` to Search. This preserves
the downstream identity across transport retries of the same stored payload,
including vendor parsers that omit the envelope event ID. Tenant identity
still comes from the authenticated service context, never from this key.

After commit, HIPS attempts synchronous forwarding. Unacknowledged tasks remain
durable and the background publisher retries; expired claims are recoverable
after process loss. The additive `deliveryStatus` reports `PENDING`, `PROCESSING`,
`DELIVERED`, or `DEAD` at the subsequent read. It is not an atomic snapshot of
`forwarded`. A retry budget exhausted by failures or expired claims becomes
`DEAD`, requiring explicit operator requeue. `accepted: true, forwarded: false`
must not trigger keyless resubmission to HIPS: that creates a new event ID.

An optional `Idempotency-Key` (1–256 visible ASCII characters, no spaces)
stabilizes original producer retries within the authenticated tenant and
registered collector/signed service identity. The same scoped key and normalized
content return the original event ID without rewriting history or heartbeat;
different content returns 409. Invalid keys return 400. Omitting the header
does not deduplicate identical bodies. Keys remain valid while the forwarding
receipt exists; DELIVERED receipts are pruned after 14 days, while unresolved
ones retain their keys. Replay does not reset DEAD state, and response counters
or delivery state can change. See [producer request retries](operations/endpoint-forwarding.md#producer-request-retries)
for retention, legacy credential and upgrade requirements.

HIPS event history and its list/count APIs follow the
[history retention policy](operations/endpoint-forwarding.md#history-retention):
by default, rows older than 30 days of server receipt time are eligible for
bounded cleanup only after all forwarding receipt references are gone. Totals
represent retained history; endpoint registration and heartbeat are unaffected.

`GET /hips-web/api/v1/endpoints/forwarding?status=DEAD&limit=100` is an admin-only,
tenant-scoped receipt list (limit 1–500; four statuses above). It returns an
`ApiResult<List<Map>>` ordered by creation time then event ID, without payloads.
`POST /hips-web/api/v1/endpoints/forwarding/{id}/requeue` is admin-only and audited;
it resets only an owned `DEAD` receipt to `PENDING`, preserving its payload and
identity. Missing, foreign or non-DEAD receipts return 409. Requeue clears old
claim ownership even when attempts reset. See [endpoint forwarding operations](operations/endpoint-forwarding.md)
for migration, recovery, quotas and retention.

### Asset and endpoint investigation

Owner-service context paths remain `/asset-web` and `/hips-web`. These reads
require the same `admin` or `analyst` role as their inventory lists:

| Resource | Contract |
|---|---|
| `GET /asset-web/api/v1/assets/{id}` | `ApiResult<Asset>` for an internal ID; missing or foreign-tenant IDs return the normal 404 envelope. |
| `GET /hips-web/api/v1/endpoints/{id}` | `ApiResult<Endpoint>` independent of inventory filters/page; missing or foreign-tenant IDs return 404. |
| `GET /hips-web/api/v1/endpoints/related?ip=...&hostname=...&page=1&size=20` | `ApiResult<PageResponse<Endpoint>>`; exact IP **or** hostname association within the authenticated tenant. |
| `GET /asset-web/api/v1/assets/related?ip=...&name=...&page=1&size=20` | `ApiResult<PageResponse<Asset>>`; exact IP **or** asset-name association within the authenticated tenant. |
| `GET /hips-web/api/v1/endpoints/{id}/events?page=1&size=20` | `ApiResult<PageResponse<Map>>`; first resolves an owned endpoint, then pages same-tenant events matching its current hostname. Missing or foreign endpoint IDs return 404 before the event query. |

Association inputs trim surrounding whitespace and compare text without case
sensitivity. Empty keys do not match empty stored values, and `%`/`_` have no
wildcard meaning. Related inventory reads require at least one key; IP length
is at most 64 and hostname/name length at most 128. These detail resources use
one-based pages, size 20 by default, and the configured list maximum (default
500). Queries count and page the complete matching set in the database instead
of filtering a client-side inventory prefix. Endpoint associations order by
hostname/storage ID; asset associations order by name/ID. History orders by
received time descending and event ID ascending for ties.

These are string associations, not canonical network-address equivalence or
proof of stable device identity. Historical event envelopes do not carry a
stable endpoint ID. The current hostname can match events from a previous
registration or another device using that name; hostname-less events are not
silently associated by IP. Pages are not a snapshot under concurrent writes.

`GET /hips-web/api/v1/endpoints/stats` retains `events` as the full tenant event
count. Its legacy `eventByType` is the distribution of the most recently received
200 events, not the full retained history. Additive `eventByTypeScope` is
`LATEST_EVENTS`, `eventByTypeSampleLimit` is 200, and `eventByTypeSampleSize` is
the number actually sampled. Equal receive times are ordered by event ID.
Clients displaying this distribution must label the sampling scope. Inventory
counts, the full event count, and the sample need not be one atomic snapshot.

Routes and sample metadata are additive and require no schema migration. Deploy
both owner services before the workbench that consumes their new routes.
Existing list, write and collection routes retain their contracts.

## Verification

Builds must include the OpenAPI dependency through `socp-starter` (servlet
services) or the gateway's WebFlux dependency. OpenAPI generation does not
replace authorization tests: the negative RBAC and tenant-isolation tests
remain the security oracle. Mutation requests use bounded DTOs; only event and
rule-extension payloads intentionally remain JSON objects because their schemas
are supplied by the connector/content boundary.

The repeatable SDK contract gate is:

```text
python build/verify-openapi-sdk.py
```

Its coverage, per mode, is deliberately narrow and stated here so nobody reads
a PASS as full per-service parity:

- Every PR run (offline): the SOAR snapshot is validated (all `$ref`s resolved,
  every operation name and response description checked), a dependency-free
  TypeScript client is generated and compiled with strict `tsc`, and a
  per-service *anchor* asserts each registered module still ships the document
  surface — `socp-starter` provides springdoc, `api-gateway` declares its own
  reactive dependency, no module disables `api-docs`, and the contract text
  only advertises surfaces the build ships. It does not compare runtime
  documents for the 13 non-SOAR services.
- Deployment run with `SOAR_OPENAPI_BASE_URL` set: the SOAR runtime document is
  checked for the same method/path set in **both** directions (a snapshot route
  the runtime lacks and a runtime route the snapshot lacks are both failures) and
  the `cookieAuth`/`tenantHeader`/`bearerAuth`, `If-Match`, and `ETag` contract
  declarations are re-checked on the live document. This is not full request or
  response schema equivalence, nor proof that documented security is enforced.
- Deployment run with `SOCP_GATEWAY_URL` additionally set: every module in
  `build/ports.env` is fetched at `<gateway>/<context>/v3/api-docs` and must
  return a non-empty `paths` map with `bearerAuth` and `tenantHeader`. This is
  the per-service document smoke; it is a deployment-evidence step because it
  needs a running stack, not a PR gate.

The gate also asserts the reviewed `ApiResult` envelope, `If-Match`, and `ETag`
contract. The generated client always sends browser credentials with
`credentials: include`; Node/CI callers can pass `Cookie: SOCP_SESSION=<value>`
and `X-Tenant-Id` explicitly.

Parameter inheritance follows the [OpenAPI parameter rules](https://spec.openapis.org/oas/v3.1.1.html#parameter-object):
operation declarations override path defaults, and only header names are folded
for case-insensitive comparison. Duplicate parameters, malformed required flags,
and collisions in the generated client's flat parameter names fail explicitly.
Explicit `in: cookie` parameters are rejected because browser fetch cannot set
individual cookies as ordinary request headers; the session security scheme
continues to use browser credentials or the Node caller's `Cookie` header.
Named recursive models, schema type unions, and nullable objects retain their
TypeScript structure. Alias-only reference cycles and model names that normalize
to the same TypeScript identifier fail instead of producing ambiguous models.
This generator covers the reviewed snapshot; its types are not a complete
JSON Schema validator or a guarantee of every OpenAPI serialization style.
An invalid snapshot stops generation and live smoke before any runtime mutation.
Every completed invocation replaces its evidence manifest, including failures
during parsing or generation; consumers must check both the current exit status
and manifest instead of accepting an earlier PASS file.

For a deployment-backed verification, start the gateway and SOAR service and
provide a test account (never commit its cookie):

```text
SOAR_OPENAPI_BASE_URL=http://127.0.0.1:18092/soar-web \
SOCP_GATEWAY_URL=http://127.0.0.1:18092 \
SOAR_OPENAPI_REQUIRE_RUNTIME=true \
SOAR_VERIFY_USERNAME=admin SOAR_VERIFY_PASSWORD=admin123 \
python build/verify-openapi-sdk.py
```

This fetches `/v3/api-docs`, compares runtime paths and security schemes with
the snapshot, then uses the generated client against a real session to verify
201 import, version 200 plus weak `ETag`, a correct `If-Match` update, stale
`If-Match` 412, `ApiResult` error 404, and archive cleanup. Evidence is written
to `.cache/openapi-sdk/manifest.json`; it contains hashes and pass/fail names,
not credentials.

### Compliance mapping calculations

`soc-base` exposes the framework catalogue and a read-only
`POST /api/v1/compliance/coverage` calculation for admin, analyst and viewer.
Coverage compares the supplied `ruleIds` with built-in control mappings; this
endpoint does not resolve Detection rule status itself. The workbench resolves
mapped IDs against the current tenant's rule catalogue in batches of at most 100,
then displays mapping coverage, controls with ACTIVE rules, and verified
assessment separately. A mapped or enabled rule is not a verified assessment.
The calculation does not write assessment evidence or establish compliance.
A client refresh publishes the framework/rule/coverage result together and
preserves its last successful result if any stage fails.

### ATT&CK alert activity

`POST /alert-web/api/v1/alarms/technique-counts` (also available under
`/alert-web/api/alarms/technique-counts`) accepts `{techniqueIds: ["T1110"]}`.
The authenticated admin/analyst/viewer tenant owns the query; request tenant or
role headers do not choose its scope. Supply 1-100 nonblank IDs, each at most 32
characters. The raw body is limited to 16 KiB and the endpoint configures 5 requests
per second. Duplicate IDs are counted once; absent requested IDs return zero.

Success data is `{from, until, counts}`, with ISO UTC boundary strings and long
counts keyed by exact stored technique ID. The fixed rolling seven-day interval
is `[from, until)` over `occurredAt`; future timestamps are excluded. One bounded
`GROUP BY` query returns only requested groups, with a five-second query timeout,
and does not hydrate alarm entities or use an overview sample. No schema change
is required. Separate requests can observe different committed database states.

The workbench reads this endpoint directly for displayed techniques in batches
of at most 100. Batches publish together in the view; their windows are calculated
separately, and the displayed calculation time is the oldest batch boundary.
Filtering cancels the previous activity request. Manual refresh preserves prior
counts with an error when unsuccessful. These are occurrence-time alert counts,
not a claim of detection effectiveness or an ingestion watermark.

### Reference-set mutation concurrency

Reference-set entry additions/removals and set creations/deletions execute their
read, bound validation and write in a PostgreSQL SERIALIZABLE transaction.
Conflicts retry the whole operation in a fresh transaction, up to five attempts
with a short bounded jittered delay and a five-second transaction timeout per
attempt. Exhaustion returns 503;
invalid limits remain 400 and a missing/deleted entry target remains 404.
The per-set 10,000-entry and per-tenant 200-set limits include the effective
built-in catalogue and are checked inside the transaction. A successful entry
write cannot silently overwrite another acknowledged entry mutation. These
transactions commit independently of any caller transaction; REST handlers do
not combine them with other durable writes.

The four packaged reference sets now have stable `REF-BUILTIN-*` identities
across replicas/restarts. Custom set IDs are unchanged. Historical random-ID
overlays retain their old IDs and data. When a legacy overlay has the same
name as a packaged set, the list shows the legacy row and hides the new packaged
row for that tenant; the rows are not merged or deleted. Old tombstones contain
no template identity or payload, so an old random-ID deletion cannot be mapped
safely to a packaged template. During
upgrade, review legacy overrides and reapply intended built-in removals against
the stable IDs. No automatic deletion or inferred merge of tenant data occurs.

### Metadata catalog identity and bounds

`/search-config/api/v1/meta/data-source-types`, `/categories` and `/fields`
retain their existing CRUD envelopes and admin/analyst write roles. Mutation
request bodies are limited to 16 KiB before JSON conversion. Packaged
items now use stable `BUILTIN-TYPE-*`, `BUILTIN-CATEGORY-*` and
`BUILTIN-FIELD-*` IDs across API replicas and restarts; their `createdAt` is
the fixed epoch marker for packaged content. Custom item IDs and timestamps
remain unchanged. A legacy random-ID overlay with the same code or field name
remains addressable under its old ID and is shown in place of that packaged row
in the list. No tenant data is deleted or merged. Legacy tombstones cannot be
mapped to a packaged item because their payloads were erased; reapply intended
removals against stable IDs during upgrade review.

New creations are limited to 128 effective data-source types, 128 effective
log categories and 1,024 effective field definitions per tenant. A new item
at the limit returns 400. Historical over-limit rows remain readable and are
not truncated. Type/category codes are unique ignoring case; field names
are unique by exact spelling. Duplicate creation returns 409. Field creation
accepts only `parse` or `custom` sources and the eight declared field types;
`system` fields can be packaged only and cannot be edited or deleted via the
API. The owning stores perform create, update and delete decisions inside a
fresh SERIALIZABLE database transaction, retrying serialization/deadlock/unique
conflicts at most five times. An update that loses a race with deletion returns
404 instead of recreating the row. Concurrent edits to different mutable
properties of the same item still use last-writer-wins; the API does not yet
expose a conditional version or ETag.
