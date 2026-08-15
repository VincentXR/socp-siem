# ADR 004: Runtime Configuration Modes

- Status: accepted
- Date: 2026-08-15

## Decision

Use explicit Spring profiles and Compose profiles for different runtime needs:

- `dev`: local login defaults and demo credentials. The startup scripts use
  this profile for the gateway; other services use their default configuration.
- `pg`: services that provide `application-pg.yml` use PostgreSQL instead of
  file-backed H2.
- `prod`: `ProdGuard` performs fail-fast checks for database, credentials,
  authentication bypass, ingest token, and Temporal configuration. Production
  deployments normally combine it with `pg`.
- Compose's `extra` profile adds Keycloak, Temporal, Jaeger, and dashboard
  dependencies. It is independent of the Spring `prod` profile.

The default Docker Compose setup is the integration environment for Kafka,
OpenSearch, PostgreSQL, and ClickHouse. HMAC JWT validation is supported for a
single-node setup; JWKS/OIDC is available when an identity provider is used.

## Rationale

The complete middleware path is required to validate event delivery, search,
analytics, and recovery. Local development also needs a lower-cost startup
path. Separating the profiles keeps those trade-offs explicit and prevents
development fallbacks from silently being used with the production profile.

## Consequences

- The local setup is convenient but is not a production deployment target.
- H2-backed services need the `pg` profile before production validation.
- `prod` fails fast when a development fallback or demo credential is detected.
- Compose services remain single-node; high availability and horizontal
  detection state are deployment concerns outside this repository's local
  verification target.
