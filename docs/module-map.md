# Module Map

This map records runtime responsibilities, ports, persistence, and verification
ownership. It is intentionally higher-level than a class-by-class index.

See the [service maturity matrix](maturity-matrix.md) for the explicit demo,
preview, and production-readiness contract of each service.

## Repository layout

| Path | Responsibility |
|---|---|
| `platform/` | Shared authentication, tenant context, audit, rate limiting, observability, errors, data contracts, rules, clients, and test support |
| `services/` | Spring Boot services for ingestion, detection, alerting, cases, response, assets, intelligence, and reporting |
| `frontend/apps/workbench` | Vue 3 and TypeScript security operations workbench |
| `frontend/packages` | Frontend shared packages and reusable UI helpers |
| `agents/` | Vector pipeline and Falco rule assets |
| `infra/` | Docker Compose, production-shaped overlay, database bootstrap SQL, tenant RLS, and observability configuration |
| `deploy/` | Digest-addressed application image and Kubernetes rolling-update baseline |
| `build/` | Toolchain, startup, port registry, verification, failure injection, and demo scripts |
| `docs/` | Architecture, operating guidance, test scope, demo instructions, and ADRs |

Java tests are colocated under each module's `src/test/java`. Frontend contract
tests and artifact checks are under `frontend/apps/workbench/scripts`.

## Backend services

| Service | Port | Primary responsibility | Default persistence or dependency |
|---|---:|---|---|
| `api-gateway` | 18092 | Routing, login, JWT/RBAC, and trace propagation | Stateless |
| `search-config` (`search-config-api` + `search-config-worker` in prod) | 18081 (API) | Source configuration, parsing, canonical event ingest, durable publication, and replayable indexing | H2/PG + Ingestion Outbox + Kafka + replayable OpenSearch indexer |
| `detect-web` (`detect-web-api` + `detect-web-worker` in prod) | 18082 (API) | Rule CRUD, hot reload, detection, backpressure, partition restore, shared entity risk, durable Alert Web hand-off, and secondary alert analysis | Detection H2/PG + independent secondary-analysis H2/PG/Flyway persistence unit + in-process hot engine + journal/outbox/risk projections |
| `alert-web` | 18080 | Alert facts, enrichment, disposition, idempotency, and Alert Outbox | PostgreSQL |
| `incident-web` | 18097 | Incident creation, merge, and timeline | PostgreSQL |
| `soar-web` | 18083 | Playbook CRUD and execution | H2 + durable execution projection + optional Temporal |
| `report-web` | 18084 | Daily and trend reporting | ClickHouse + optional MinIO |
| `soc-base` | 18086 | Tenant, overview, compliance, and audit views | PostgreSQL |
| `threat-web` | 18094 | IOC and threat-intelligence lookup | PostgreSQL |
| `attack-web` | 18095 | ATT&CK catalog and detection coverage | H2 |
| `notify-web` | 18096 | Notification channels and delivery records | H2 |
| `asset-web` | 18085 | Asset inventory, imports, and asset collection ingress | H2 |
| `hips-web` | 18087 | Endpoint registration, heartbeat state, and event ingress | H2 |
| `ai-assistant` | 18088 | Evidence-bounded investigation with deterministic fallback and optional LLM analysis | H2 + optional external LLM |

The services with `application-integration.yml` import their
`application-pg.yml` overlay when the `integration` profile is active. Flyway
migrations are owned by the service that owns the corresponding schema. The
production profile rejects H2.

The default `full` deployment runs 14 JVMs. Asset and endpoint collection
ingress are hosted by `asset-web` and `hips-web`; the gateway rewrites the
legacy `/asset-collect/**` and `/hips-collect/**` paths so agents do not need to
change URLs. The duplicate standalone collector modules are retired.
Secondary analysis is hosted by `detect-web-worker`; the gateway rewrites the
legacy `/detect-model/**` path, while the original database, Flyway history,
Kafka consumer group, and transaction boundary are preserved.
Production collection must come from managed Agent/Falco/CMDB inputs.

Code-module ownership is deliberately separate from runtime placement. The
contract in `build/runtime-topology.json` assigns every current service to a
logical domain for ownership and observability only:

| Logical domain | Current module ownership |
|---|---|
| `gateway-ui` | `api-gateway`, `frontend/apps/workbench` |
| `ingest-search` | `search-config` |
| `detection` | `detect-web` |
| `alert-incident` | `alert-web`, `incident-web` |
| `response-automation` | `soar-web`, `notify-web` |
| `security-context` | `asset-web`, `hips-web`, `threat-web`, `attack-web` |
| `reporting-governance` | `report-web`, `soc-base` |
| `ai-assistance` | `ai-assistant` |

Run `python build/runtime-topology.py --check` to verify that module, process,
compatibility, domain, and candidate registries still agree. There is no fixed
target process count. `alert-incident` and `soar-notify` are measurement
candidates, not committed merges; each must independently pass the context,
transaction, failure, and capacity gates in
[ADR 007](adr/007-runtime-deployment-units.md) before runtime placement changes.

## Platform modules

- `socp-auth`: HMAC/JWKS JWT validation, tenant claim extraction,
  `@RequireRole`, and `ProdGuard`.
- `socp-tenant` and `socp-data`: tenant context and shared persistence fields.
- `socp-audit`, `socp-ratelimit`, `socp-obs`, and `socp-error`: audit,
  Redis-backed distributed rate limiting with a local-development fallback,
  tracing/logging, and API error responses.
- `socp-rule`: canonical `SecurityEvent`, executable rule families,
  suppression, routing keys, and UEBA primitives.
- `socp-client`: typed service-to-service clients with explicit failure
  results and trace headers.
- `socp-starter`: explicit servlet-side auto-configuration for the platform
  modules and generated OpenAPI metadata; business applications scan only
  their own domain package.
- Root `socp-parent` and `socp-test`: dependency management and shared test
  support. There is no separately published `socp-bom` module.

## Middleware and event topics

| Component | Used by | Purpose |
|---|---|---|
| PostgreSQL | alert, incident, SOC base, threat, optional Detection | Transactional facts, event claims, and durable alert hand-off |
| H2 / Flyway | Configurable stateful services | Low-resource local persistence; PostgreSQL profile for integration/production |
| Kafka | search, detection, and fan-out consumers | Six-partition default for `socp-events`, plus rule changes, `socp-alarm-original`, and `socp-alarm-events` |
| OpenSearch | Event index consumer and search API | Raw event investigation and field search |
| ClickHouse | Alarm event consumer and reports | Alarm detail analytics and trends |
| Redis | Docker Compose middleware | Shared production rate-limit counters; local profile can fall back to in-memory counters |
| Temporal | SOAR optional profile | Durable Workflow/Activity execution |
| Keycloak | Optional OIDC login | Identity provider for authorization-code login and JWKS validation |
| Prometheus/Grafana/Jaeger | Optional observability profile | Metrics, dashboards, and trace inspection |

## Verification ownership

- `build/verify-slice.py`: gateway and alert minimal slice.
- `build/verify-pipeline.py`: Kafka -> Detection -> PostgreSQL/OpenSearch/
  ClickHouse event path.
- `build/verify-full.py`: backend API, authentication, tenancy, persistence,
  rate limiting, and tracing checks.
- `build/failure-tests.py`: dependency stop/restart and fallback behavior.
- `build/demos/golden-demo.py`: Vector -> Kafka -> Detection Outbox -> Alert
  Outbox -> Incident/SOAR/Notify walkthrough; `--transport ingest` is a
  troubleshooting shortcut after the collector boundary.
- `build/demos/detection-recovery.py`: stops `detect-web`, proves Kafka backlog
  growth, then verifies consumer recovery and offset catch-up.
- `build/chaos-pipeline.py`: verifies Detection restart, Alert Web outage,
  duplicate delivery, and opt-in multi-instance ownership/rebalance.
- `build/demos/attack-scenarios.py`: rule-engine playground scenarios.
- `frontend/apps/workbench/scripts`: frontend API contract tests and production
  artifact verification.
- [Testing Guide](testing.md): test scope, focused commands, and CI ownership.
