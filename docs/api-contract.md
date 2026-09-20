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

Search export remains capped at 5,000 events and is restricted to `admin` or
`analyst`. Report archive listing accepts a bounded `limit` (1–5,000, default
500) and never iterates beyond that tenant-owned prefix window; callers should
use the `truncated` flag when the archive contains more objects.

The search-config source catalogue accepts one-based `page` and bounded `size`
(maximum 500) and returns the shared `PageResponse` when pagination is
requested. The Workbench requests the first page explicitly and keeps the
legacy array return type only at its local adapter boundary; source reads never
materialise the complete tenant catalogue in the HTTP handler.

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
  compared against the snapshot in **both** directions (a snapshot route the
  runtime lacks and a runtime route the snapshot lack are both failures) and
  the `cookieAuth`/`tenantHeader`/`bearerAuth`, `If-Match`, and `ETag` contract
  is re-checked on the live document.
- Deployment run with `SOCP_GATEWAY_URL` additionally set: every module in
  `build/ports.env` is fetched at `<gateway>/<context>/v3/api-docs` and must
  return a non-empty `paths` map with `bearerAuth` and `tenantHeader`. This is
  the per-service document smoke; it is a deployment-evidence step because it
  needs a running stack, not a PR gate.

The gate also asserts the reviewed `ApiResult` envelope, `If-Match`, and `ETag`
contract. The generated client always sends browser credentials with
`credentials: include`; Node/CI callers can pass `Cookie: SOCP_SESSION=<value>`
and `X-Tenant-Id` explicitly.

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
