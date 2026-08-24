# ADR 004: Runtime Configuration Modes

- Status: accepted
- Date: 2026-08-15

## Decision

Use explicit Spring and Compose profiles for different runtime needs:

- `dev`: local login defaults and disposable demo credentials;
- `pg`: services with `application-pg.yml` use PostgreSQL instead of
  file-backed H2;
- `prod`: `ProdGuard` fails fast on H2, demo credentials, authentication
  bypass, the default ingest token, seeded demo data, and disabled Temporal;
- Compose's `extra` profile adds Keycloak, Temporal, Jaeger, and dashboards.

Detection multi-instance checks additionally use a shared PostgreSQL database,
distinct server ports, and the same `SOCP_KAFKA_GROUP_ID`.

## Rationale

The complete middleware path is required to validate event delivery, search,
analytics, and recovery. Local development needs a lower-cost startup path.
Separating the profiles keeps those trade-offs explicit and prevents
development fallbacks from silently being used with the production profile.

## Consequences

- The local setup is convenient but is not a production deployment target.
- H2-backed services need the `pg` profile before production-like validation.
- `prod` fails fast when a development fallback, demo credential, or
  `socp.demo-data.enabled=true` is detected. Local demo seeding is controlled
  by `SOCP_DEMO_DATA_ENABLED` and is disabled by the production overlays.
- Production JWT verification accepts exactly one source: HMAC secret or
  JWKS/issuer. JWKS deployments must also configure `socp.security.audience`
  (comma-separated values are supported) so tokens issued to unrelated clients
  are rejected.
- Compose services remain single-node; Kubernetes HA, rolling upgrades,
  backup/restore, and disaster recovery are outside this repository's local
  verification target.
