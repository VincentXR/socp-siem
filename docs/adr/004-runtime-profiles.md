# ADR 004: Runtime Configuration Modes

- Status: accepted
- Date: 2026-08-15

## Decision

Use explicit Spring and Compose profiles for different runtime needs:

- `local`: workstation defaults and file-backed H2 where the service has a
  local overlay;
- `integration`: PostgreSQL overlays for services that otherwise default to
  H2, shared middleware, and cross-service verification;
- `prod`: `ProdGuard` fails fast on H2, demo credentials, authentication
  bypass, the default ingest token, seeded demo data, and disabled Temporal;
- Compose's `extra` profile adds Keycloak, Temporal, Jaeger, and dashboards.

Detection multi-instance checks additionally use a shared PostgreSQL database,
distinct server ports, and the same `SOCP_KAFKA_GROUP_ID`.

## Rationale

The complete middleware path is required to validate event delivery, search,
analytics, and recovery. Local development needs a lower-cost startup path.
Separating the profiles keeps those trade-offs explicit and prevents a
development fallback from silently being used with integration or production.

## Consequences

- The local setup is convenient but is not a production deployment target.
- Integration runs use one named mode; individual `application-pg.yml` files
  are imported by the service's `application-integration.yml` overlay.
- `prod` fails fast when a development fallback, demo credential, or
  `socp.demo-data.enabled=true` is detected. Local demo seeding is controlled
  by `SOCP_DEMO_DATA_ENABLED` and is disabled by the production overlays.
- Production JWT verification accepts exactly one source: HMAC secret or
  JWKS/issuer. JWKS deployments must also configure `socp.security.audience`
  (comma-separated values are supported) so tokens issued to unrelated clients
  are rejected. Production defaults to JWKS/issuer; HMAC is an explicit
  emergency compatibility exception controlled by
  `socp.security.allow-prod-hmac=true`.
- Compose services remain single-node; Kubernetes HA, rolling upgrades,
  backup/restore, and disaster recovery are outside this repository's local
  verification target.
