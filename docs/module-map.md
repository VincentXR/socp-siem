# Module Map

This is the current implementation map. It intentionally describes runtime
responsibilities and integration boundaries instead of duplicating every class
in the repository.

## Repository layout

| Path | Responsibility |
|---|---|
| `platform/` | 11 reusable Maven modules: auth, tenant, audit, rate limiting, observability, errors, data contracts, rules, HTTP clients, BOM, and test dependencies |
| `services/` | 17 Spring Boot services, each with its own application and migration resources |
| `frontend/apps/workbench` | The only frontend application: Vue 3, TypeScript, Element Plus and ECharts |
| `frontend/packages` | Shared UI components and formatting/library helpers |
| `agents/` | Vector pipeline and Falco rule assets |
| `infra/` | Docker Compose, database bootstrap SQL, Prometheus configuration |
| `build/` | Toolchain, startup, port registry, verification, failure injection and demos |
| `docs/` | Architecture, operating guidance, module map, ADRs and demo checklist |

The root Maven reactor contains 28 modules (11 platform + 17 services) plus
the aggregator POM. Tests are colocated in each module under
`src/test/java`; frontend checks live under `frontend/apps/workbench/scripts`.

## Backend services

| Service | Port | Primary responsibility | Default state |
|---|---:|---|---|
| `api-gateway` | 18092 | Gateway routing, login, JWT/RBAC and trace propagation | Stateless |
| `search-config` | 18081 | Source configuration, parser pipeline, canonical event ingest | H2 + Kafka/OpenSearch |
| `detect-web` | 18082 | Rule CRUD, hot reload, event detection and backpressure | H2 + in-process engine |
| `detect-model` | 18090 | Secondary alert analysis and correlation endpoint | H2 |
| `alert-web` | 18080 | Alert facts, enrichment, disposition and Outbox | PostgreSQL |
| `incident-web` | 18097 | Incident creation, merge and timeline | PostgreSQL |
| `soar-web` | 18083 | Playbook CRUD and execution; Temporal/in-process dual mode | H2 + optional Temporal |
| `report-web` | 18084 | Daily and trend reporting | Stateless reads |
| `soc-base` | 18086 | Tenant, overview, compliance and audit views | PostgreSQL |
| `threat-web` | 18094 | IOC and threat-intelligence lookup | PostgreSQL |
| `attack-web` | 18095 | ATT&CK catalog and detection coverage | H2 |
| `notify-web` | 18096 | Notification channel configuration and delivery | H2 |
| `asset-web` | 18085 | Asset inventory | H2 |
| `asset-collect` | 18091 | Asset/intelligence collection ingress | Stateless |
| `hips-web` | 18087 | Endpoint registration and heartbeat state | H2 |
| `hips-collect` | 18093 | Falco/endpoint event ingress | Stateless |
| `ai-assistant` | 18088 | Keyword-backed security knowledge assistant | H2 |

The nine services with `application-pg.yml` can switch from file-backed H2 to
PostgreSQL. Four services are intentionally stateless or read from another
store. Every stateful service uses Flyway migrations; the production profile
rejects H2.

## Platform modules

- `socp-auth`: HMAC/JWKS JWT validation, tenant claim extraction,
  `@RequireRole`, and `ProdGuard`.
- `socp-tenant`, `socp-data`: tenant context and shared persistence fields.
- `socp-audit`, `socp-ratelimit`, `socp-obs`, `socp-error`: cross-cutting
  audit, rate limiting, trace IDs/logging, and API errors.
- `socp-rule`: canonical `SecurityEvent`, six executable rule families,
  suppression and UEBA primitives.
- `socp-client`: typed service-to-service clients with explicit failure
  results and trace headers.
- `socp-bom`, `socp-test`: dependency management and shared test dependencies.

## Middleware and event topics

| Component | Used by | Purpose |
|---|---|---|
| PostgreSQL | alert, incident, SOC base, threat | Transactional facts and tenant-scoped data |
| H2 / Flyway | nine configurable services | Low-resource local persistence; PG profile for integration/production |
| Kafka | search, detect, alert consumers | Canonical event, rule-change and Outbox topics |
| OpenSearch | event index consumer and search API | Raw event investigation/search |
| ClickHouse | alert event consumer and reports | Alarm detail analytics |
| Redis | Compose-ready middleware | Not required by the current in-process rate limiter |
| Temporal | SOAR optional profile | Durable Workflow/Activity execution when enabled |
| Keycloak | optional OIDC login | Identity provider; services can also validate JWKS directly |
| Prometheus/Grafana/Jaeger | observability profile | Metrics, dashboards and trace inspection |

## Verification ownership

- `build/verify-slice.py`: gateway + alert minimal slice.
- `build/verify-pipeline.py`: Kafka → detection → PostgreSQL/OpenSearch/
  ClickHouse path.
- `build/verify-full.py`: full backend API, auth, tenancy, persistence,
  rate-limit and tracing checks.
- `build/failure-tests.py`: dependency stop/restart and fallback behavior.
- `build/demos/golden-demo.py`: recommended Vector → Kafka → detection →
  Outbox → Incident/SOAR/Notify walkthrough; `--transport ingest` is a
  troubleshooting shortcut.
- `build/demos/attack-scenarios.py`: three human-readable rule-engine
  playground scenarios; it is not the canonical Vector end-to-end proof.
- `frontend/apps/workbench/scripts/api-response.test.ts`: frontend API
  contract checks; `verify-build.mjs` checks the production artifact.
- `docs/testing.md`: test scope, focused commands and CI ownership.
