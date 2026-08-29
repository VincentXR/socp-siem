# API contract

The HTTP contract is generated from the running Spring applications.  Servlet
services expose `/v3/api-docs` and `/swagger-ui.html` below their context path;
the reactive gateway exposes the same endpoints at its root.  For example:

```text
http://localhost:18092/v3/api-docs                 # gateway's own contract
http://localhost:18092/alert-web/v3/api-docs       # routed Alert contract
http://localhost:18080/alert-web/v3/api-docs       # direct Alert service
```

The generated document is the source for client generation and review.  It
uses the `v1` API contract version and describes the JWT bearer and
`X-Tenant-Id` security schemes.  A route is not considered a new contract just
because the implementation moved between deployment units; preserve the
context path and response envelope while migrating.

## Compatibility policy

- New public endpoints use `/api/v1/...`.
- Existing `/api/alarms` is retained as a compatibility route until clients
  have migrated; it must remain tenant- and role-protected.
- Breaking request or response changes require a new version and a migration
  note.  Additive fields should be ignored by clients.
- Pagination uses the shared `page`, `size`, `total`, and `items` shape where a
  list contract supports pagination.

## Verification

Builds must include the OpenAPI dependency through `socp-starter` (servlet
services) or the gateway's WebFlux dependency.  A deployment smoke test should
fetch the documents from every enabled service and fail if an expected document
is unavailable.  OpenAPI generation does not replace authorization tests: the
negative RBAC and tenant-isolation tests remain the security oracle.
