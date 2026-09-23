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
