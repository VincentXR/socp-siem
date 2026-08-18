# Module Map

This map records runtime responsibilities, ports, persistence, and verification
ownership. It is intentionally higher-level than a class-by-class index.

## Repository layout

| Path | Responsibility |
|---|---|
| `platform/` | Shared authentication, tenant context, audit, rate limiting, observability, errors, data contracts, rules, clients, and test support |
| `services/` | Spring Boot services for ingestion, detection, alerting, cases, response, assets, intelligence, and reporting |
| `frontend/apps/workbench` | Vue 3 and TypeScript security operations workbench |
| `frontend/packages` | Frontend shared packages and reusable UI helpers |
| `agents/` | Vector pipeline and Falco rule assets |
| `infra/` | Docker Compose, database bootstrap SQL, and observability configuration |
| `build/` | Toolchain, startup, port registry, verification, failure injection, and demo scripts |
| `docs/` | Architecture, operating guidance, test scope, demo instructions, and ADRs |

Java tests are colocated under each module's `src/test/java`. Frontend contract
tests and artifact checks are under `frontend/apps/workbench/scripts`.

## Backend services

| Service | Port | Primary responsibility | Default persistence or dependency |
|---|---:|---|---|
| `api-gateway` | 18092 | Routing, login, JWT/RBAC, and trace propagation | Stateless |
| `search-config` | 18081 | Source configuration, parsing, canonical event ingest | H2 + Kafka; direct OpenSearch fallback only when Kafka is unavailable |
| `detect-web` | 18082 | Rule CRUD, hot reload, detection, backpressure, and recoverable state journal | H2/PG + in-process hot engine + `t_detection_event` replay journal |
| `detect-model` | 18090 | Secondary alert analysis and correlation endpoint | H2 |
| `alert-web` | 18080 | Alert facts, enrichment, disposition, and Outbox | PostgreSQL |
| `incident-web` | 18097 | Incident creation, merge, and timeline | PostgreSQL |
| `soar-web` | 18083 | Playbook CRUD and execution | H2 + durable execution projection + optional Temporal |
| `report-web` | 18084 | Daily and trend reporting | ClickHouse + optional MinIO |
| `soc-base` | 18086 | Tenant, overview, compliance, and audit views | PostgreSQL |
| `threat-web` | 18094 | IOC and threat-intelligence lookup | PostgreSQL |
| `attack-web` | 18095 | ATT&CK catalog and detection coverage | H2 |
| `notify-web` | 18096 | Notification channels and delivery records | H2 |
| `asset-web` | 18085 | Asset inventory and imports | H2 |
| `asset-collect` | 18091 | Asset and intelligence collection ingress | Stateless |
| `hips-web` | 18087 | Endpoint registration and heartbeat state | H2 |
| `hips-collect` | 18093 | Falco and endpoint event ingress | Stateless |
| `ai-assistant` | 18088 | Keyword-backed security knowledge assistant | H2 |

The nine services with `application-pg.yml` can switch from file-backed H2 to
PostgreSQL with the `pg` profile. Flyway migrations are owned by the service
that owns the corresponding schema. The production profile rejects H2.

## Platform modules

- `socp-auth`: HMAC/JWKS JWT validation, tenant claim extraction,
  `@RequireRole`, and `ProdGuard`.
- `socp-tenant` and `socp-data`: tenant context and shared persistence fields.
- `socp-audit`, `socp-ratelimit`, `socp-obs`, and `socp-error`: audit,
  in-process rate limiting, tracing/logging, and API error responses.
- `socp-rule`: canonical `SecurityEvent`, executable rule families,
  suppression, and UEBA primitives.
- `socp-client`: typed service-to-service clients with explicit failure
  results and trace headers.
- `socp-bom` and `socp-test`: dependency management and shared test support.

## Middleware and event topics

| Component | Used by | Purpose |
|---|---|---|
| PostgreSQL | alert, incident, SOC base, threat | Transactional facts and tenant-scoped data |
| H2 / Flyway | Configurable stateful services | Low-resource local persistence; PostgreSQL profile for integration/production |
| Kafka | search, detection, and fan-out consumers | Canonical events, rule changes, and Outbox events |
| OpenSearch | Event index consumer and search API | Raw event investigation and field search |
| ClickHouse | Alarm event consumer and reports | Alarm detail analytics and trends |
| Redis | Docker Compose middleware | Available for future distributed rate limiting; current limiter is in-process |
| Temporal | SOAR optional profile | Durable Workflow/Activity execution |
| Keycloak | Optional OIDC login | Identity provider for authorization-code login and JWKS validation |
| Prometheus/Grafana/Jaeger | Optional observability profile | Metrics, dashboards, and trace inspection |

## Verification ownership

- `build/verify-slice.py`: gateway and alert minimal slice.
- `build/verify-pipeline.py`: Kafka → detection → PostgreSQL/OpenSearch/
  ClickHouse event path.
- `build/verify-full.py`: backend API, authentication, tenancy, persistence,
  rate limiting, and tracing checks.
- `build/failure-tests.py`: dependency stop/restart and fallback behavior.
- `build/demos/golden-demo.py`: Vector → Kafka → detection → Outbox →
  Incident/SOAR/Notify walkthrough; `--transport ingest` is a troubleshooting
  shortcut after the collector boundary.
- `build/demos/detection-recovery.py`: stops `detect-web`, proves Kafka backlog
  growth, then verifies consumer recovery and offset catch-up.
- `build/demos/attack-scenarios.py`: rule-engine playground scenarios.
- `frontend/apps/workbench/scripts`: frontend API contract tests and production
  artifact verification.
- [Testing Guide](testing.md): test scope, focused commands, and CI ownership.
