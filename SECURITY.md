# Security Policy

## Configuration rules

- Never commit real JWT secrets, ingest tokens, database passwords, OIDC
  client secrets, or exported middleware data.
- Local demo credentials are intentionally visible in documentation and must
  not be reused outside a disposable development environment.
- Production deployments must set `SOCP_JWT_SECRET` or an OIDC/JWKS issuer,
  disable `socp.security.dev-bypass`, use PostgreSQL, and activate the `prod`
  profile.
- Treat tenant IDs as logical authorization boundaries; use database or
  network isolation when a deployment requires stronger separation.

## Reporting a vulnerability

Do not open a public issue containing exploit details or credentials. Contact
the repository owner privately through the project hosting account and include
the affected component, reproduction steps, impact, and a suggested mitigation.
