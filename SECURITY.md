# Security Policy

## Configuration rules

- Never commit real JWT secrets, ingest tokens, database passwords, OIDC
  client secrets, or exported middleware data.
- Local demo credentials are intentionally visible in documentation and must
  not be reused outside a disposable development environment.
- Production deployments must configure a JWKS/issuer verifier and a non-empty
  `socp.security.audience`, disable `socp.security.dev-bypass`, use PostgreSQL,
  and activate the `prod` profile. HS256 is an explicit compatibility exception:
  it requires `socp.security.allow-prod-hmac=true`, a non-demo `SOCP_JWT_SECRET`
  of at least 32 bytes, and an audience. Do not configure HMAC and JWKS together.
- Treat tenant IDs as logical authorization boundaries; use database or
  network isolation when a deployment requires stronger separation.

## Reporting a vulnerability

Do not open a public issue containing exploit details or credentials. Contact
the repository owner privately through the project hosting account and include
the affected component, reproduction steps, impact, and a suggested mitigation.

## Tenant operator directory

Set `SOCP_OPERATOR_DIRECTORY` (or `socp.auth.operator-directory`) consistently on the
gateway and all owning services to provision assignment membership. Values are
bounded JSON keyed by tenant, for example:

```json
{"tenant-a":[{"id":"idp-subject-alice","label":"Alice","role":"analyst","enabled":true},{"id":"idp-subject-bob","label":"Bob","role":"analyst","enabled":true}]}
```

IDs must match verified JWT subjects, not display names. The directory contains no
credentials and does not grant login or API authority. Only enabled admin/analyst
members are assignable; viewer, disabled, unknown and cross-tenant targets are
rejected by alert/incident owning services. A verified human can assign to their own
subject when it is not explicitly disabled. Service identities cannot self-assign.
The default tenant can fall back to locally configured users for development;
production/OIDC deployments should provision the explicit directory. No global
IdP user list is exposed. A directory change requires a coordinated configuration
rollout across replicas, including the gateway, alert-web and incident-web. Old
assignments remain visible but disabled members cannot receive new assignments.
Configuration rejects malformed entries, duplicate IDs, more than 128 tenants,
more than 512 members per tenant or a document larger than 1 MiB.

Session responses include effective, whitelisted JWT permissions. The gateway
strips client-supplied capability headers and regenerates them after validation.
Delegated SOAR writes pass its coarse viewer gate only for the SOAR route; every
owning controller still enforces the exact requested permission.
