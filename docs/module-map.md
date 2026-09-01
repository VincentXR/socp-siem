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
| `build/` | Toolchain, startup, port registry, verification, failure injection, benchmark, and demo scripts |
| `docs/` | Architecture, operating guidance, test scope, demo instructions, and ADRs |

Java tests are colocated under each module's `src/test/java`. Frontend contract
tests and artifact checks are under `frontend/apps/workbench/scripts`.

## Backend services

| Service | Port | Primary responsibility | Default persistence or dependency |
|---|---:|---|---|
| `api-gateway` | 18092 | Routing, login, JWT/RBAC, and trace propagation | Stateless |
| `search-config` | 18081 | Source configuration, parsing, canonical event ingest and durable publication | H2/PG + Ingestion Outbox + Kafka + replayable OpenSearch indexer |
| `detect-web` | 18082 | Rule CRUD, hot reload, detection, backpressure, partition restore, shared entity risk, and durable Alert Web hand-off | H2/PG + in-process hot engine + journal/outbox/risk projections |
| `detect-model` | 18090 | Secondary alert analysis and correlation endpoint | H2 |
| `alert-web` | 18080 | Alert facts, enrichment, disposition, idempotency, and Alert Outbox | PostgreSQL |
| `incident-web` | 18097 | Incident creation, merge, and timeline | PostgreSQL |
| `soar-web` | 18083 | Playbook CRUD and execution | H2 + durable execution projection + optional Temporal |
| `report-web` | 18084 | Daily and trend reporting | ClickHouse + optional MinIO |
| `soc-base` | 18086 | Tenant, overview, compliance, and audit views | PostgreSQL |
| `threat-web` | 18094 | IOC and threat-intelligence lookup | PostgreSQL |
| `attack-web` | 18095 | ATT&CK catalog and detection coverage | H2 |
| `notify-web` | 18096 | Notification channels and delivery records | H2 |
| `asset-web` | 18085 | Asset inventory, imports, and asset collection ingress | H2 |
| `asset-collect` | 18091 | Optional standalone compatibility launcher for asset collection | Durable H2 locally; PostgreSQL in production |
| `hips-web` | 18087 | Endpoint registration, heartbeat state, and event ingress | H2 |
| `hips-collect` | 18093 | Optional standalone compatibility launcher for endpoint collection | Durable H2 locally; PostgreSQL in production |
| `ai-assistant` | 18088 | Keyword-backed security knowledge assistant | H2 |

The services with `application-integration.yml` import their
`application-pg.yml` overlay when the `integration` profile is active. Flyway
migrations are owned by the service that owns the corresponding schema. The
production profile rejects H2.

The default `full` deployment runs 15 JVMs. Asset and endpoint collection
ingress are hosted by `asset-web` and `hips-web`; the gateway rewrites the
legacy `/asset-collect/**` and `/hips-collect/**` paths so agents do not need to
change URLs. The two collector modules remain buildable and can be launched
explicitly for compatibility, but are not part of the default process set.
Their simulator flags are disabled by the production overlays; production
collection must come from Agent/Falco/CMDB inputs.

Code-module ownership is deliberately separate from the target runtime shape.
The executable contract in `build/runtime-topology.json` assigns every current
default service exactly once to one of six target units:

| Target unit | Current module ownership |
|---|---|
| `gateway-ui` | `api-gateway`, `frontend/apps/workbench` |
| `ingest-search` | `search-config` |
| `detection` | `detect-web`, `detect-model` |
| `alert-incident` | `alert-web`, `incident-web` |
| `response-integration` | `soar-web`, `notify-web`, `asset-web`, `hips-web`, `threat-web`, `attack-web` |
| `report-ai` | `report-web`, `ai-assistant`, `soc-base` |

`asset-collect` and `hips-collect` are compatibility launchers, not target
deployment members. Run `python build/runtime-topology.py --check` to verify
that module, process, compatibility, and target-unit registries still agree.
The six-unit shape remains a target contract until aggregate applications pass
the context, API, failure, and capacity gates required by ADR 0004.

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
