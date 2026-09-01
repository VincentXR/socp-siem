# SOCP SIEM

SOCP is a self-hosted, event-driven SIEM/SOC workbench built with Java 21,
Spring Boot, Kafka, PostgreSQL, OpenSearch, ClickHouse, Temporal, and Vue 3.
It demonstrates a complete security-event path: heterogeneous ingestion,
canonical normalization, stateful detection, alert persistence, investigation,
case management, and automated response.

This repository is a local development and verification platform. Docker
Compose is single-node and is not a production HA deployment.

## Event path

```mermaid
flowchart LR
  S[Vector / Syslog / EDR / Falco] --> P[search-config<br/>parse + normalize]
  P --> IO[(Ingestion Outbox)]
  IO --> K[(Kafka<br/>socp-events)]
  K --> D[detect-web<br/>stateful rules]
  K --> IX[OpenSearch indexer]
  IX --> OS[(OpenSearch)]
  D --> DO[(Detection Alert Outbox)]
  DO --> A[alert-web<br/>idempotent create]
  A --> AO[(Alert Outbox)]
  AO --> AK[(Kafka<br/>socp-alarm-events)]
  AK --> F[Incident / Notify / SOAR / ClickHouse]
  D --> DM[(Kafka<br/>socp-alarm-original)]
  DM --> M[detect-model]
  UI[Vue Workbench] --> GW[api-gateway]
  GW --> D
  GW --> A
  GW --> OS
  GW --> F
```

The ingestion transaction persists the canonical event and its Kafka
publication intent together. The Detection Alert Outbox is the hand-off
boundary between the stateful rule engine and Alert Web: journal completion
and every resulting Outbox row are committed atomically. A scheduled publisher
retries Alert Web until it acknowledges the deterministic source ID, then
publishes the optional detect-model event. Alert Web has another transactional
Outbox for the Kafka fan-out hand-off.

## Implemented capabilities

- JSON, NDJSON, Syslog, CEF, LEEF, KV, Sysmon, auditd, and Falco parsing.
- Canonical event fields and stable tenant/entity Kafka routing keys.
- Pattern, threshold, correlation, correlation-set, baseline, and rare rules.
- A versioned 39-rule Detection-as-Code pack with ATT&CK, data-source, and
  positive/negative execution metadata.
- Hot reload, suppression, partition-serial processing lanes, contiguous
  manual Kafka commits, durable DLQ acknowledgement, event-ID de-duplication,
  and time-bounded, paginated PostgreSQL/H2 journal replay.
- Partition-owned state restore after restart and consumer rebalance.
- Detection Alert Outbox and Alert Web idempotency using `sourceAlertId`.
- Partition-scoped OpenSearch bulk acknowledgement, offset commits, replay,
  and stable event document IDs.
- Shared durable entity-risk projection, alert evidence, IOC enrichment, ATT&CK mapping,
  incidents, cases, notifications, SOAR playbooks, and reporting.
- JWT/OIDC, RBAC, logical tenant isolation, audit records, metrics, traces,
  benchmark tooling, and failure-injection scripts.

The versioned detection pack is at
`services/detect-web/src/main/resources/detection-content/manifest.json`.
It is the sole executable source for packaged detections; a fresh Detection
database installs the manifest rules before user customization.

<!-- detection-summary:start -->
**Detection content**: `39` rules (`39` ACTIVE), pack `socp-core-detections` version `2026.08.30` (schema `1`).
Types: baseline=3, correlation=2, correlation-set=1, pattern=18, rare=5, threshold=10. Statuses: ACTIVE=39.
ATT&CK techniques: `23`; data sources: `22` (alert, application, audit, auditd, auth, database, dlp, dns, edr, falco, firewall, linux, mail, netflow, nginx, proxy, risk, sshd, sysmon, waf, web, windows).
Manifest SHA-256: `fafd608e3c89207c49fcf83c984725ef73bba56cde3ccae5bb3bdb014eb54673`.
<!-- detection-summary:end -->
The exact partition and recovery contract is in
`docs/detection-state-semantics.md`.

## Quick start

Requirements: JDK 21, Git Bash or WSL, Node.js 22, Corepack/pnpm 10, and
Docker Desktop. A full local stack is most comfortable with at least 24 GB RAM.

```bash
docker compose -f infra/docker-compose.yml up -d
cd frontend && corepack pnpm install --frozen-lockfile && corepack pnpm build && cd ..
bash build/mvnw.sh -DskipTests package
bash build/run-all.sh start core
```

Open `http://localhost:5173`. The disposable development account is
`demo / demo123`; never reuse it outside a local development environment.

Useful profiles:

```bash
bash build/run-all.sh start core   # Golden Demo and core event path
bash build/run-all.sh start ui     # Workbench business pages
bash build/run-all.sh start full   # All backend services and collectors
bash build/run-all.sh status
bash build/run-all.sh stop
```

## Verification

```bash
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false
cd frontend/apps/workbench && pnpm test && pnpm verify && cd ../../..
python build/verify-slice.py
python build/verify-pipeline.py
python build/validate-detection-content.py
python build/failure-tests.py
```

Run the operational checks only against a disposable stack because they stop
and restart services:

```bash
python build/chaos-pipeline.py --scenario alert_web_restart
python build/chaos-pipeline.py --scenario detect_restart
python build/chaos-pipeline.py --scenario duplicate_delivery
```

For the reference multi-instance check, use six `socp-events` partitions and
start three `detect-web` processes with the `pg` profile, distinct ports, and
the same `SOCP_KAFKA_GROUP_ID`. Then set
`DETECTION_INSTANCE_URLS` (the first URL is the instance controlled by
`run-all.sh`) and run:

```bash
SOCP_DETECT_PROFILE=pg \
DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082,http://127.0.0.1:38082 \
  python build/chaos-pipeline.py --scenario multi_instance --count 30 --rebalance-cycles 3
```

Benchmark commands and the JSON report schema are documented in
`docs/benchmark/README.md`. Do not claim production throughput from a local
benchmark; retain machine-specific output outside source control.

## Runtime boundaries

- Delivery is at-least-once. Kafka, database, and downstream services do not
  form an exactly-once distributed transaction.
- Alert creation atomically records both the Kafka Outbox event and one durable,
  idempotent delivery intent per ClickHouse/Incident/Notify/SOAR destination.
  Each destination is claimed independently and retried with bounded backoff;
  the Kafka consumer reconciles any missing intents after replay.
- A stateful rule is strictly partition-local only when its grouping field
  matches the canonical event routing field.
- Journal replay is bounded by `SOCP_DETECT_STATE_RETENTION` (24 hours by
  default) and paginated by `SOCP_DETECT_STATE_REPLAY_PAGE_SIZE`; it is not
  truncated at a fixed row count.
- Tenant isolation is logical (`tenant_id` and query filters), not physical
  database isolation.
- H2 is a local convenience profile. Use PostgreSQL and `prod` guard checks
  for production-like validation.
- The AI assistant is a keyword-backed knowledge prototype; it is not an
  external LLM or RAG service.

## Repository layout

```text
platform/                 shared auth, tenant, audit, observability, rules
services/                 Spring Boot business services
frontend/apps/workbench/  Vue 3 security operations workbench
agents/                   Vector and Falco assets
infra/                    Docker Compose and middleware initialization
build/                    startup, verification, benchmark, chaos, demos
docs/                     architecture, operating guides, tests, and ADRs
```

The 17 executable service modules currently run as 15 compatibility
processes, while the reviewed target is six deployment units. Verify that
these two views have not drifted with:

```bash
python build/runtime-topology.py --check
```

The command validates `build/runtime-topology.json`; it does not claim that
the aggregate applications have already replaced the current launchers.

## Documentation

- [Architecture](docs/architecture.md)
- [Detection state semantics](docs/detection-state-semantics.md)
- [Module map](docs/module-map.md)
- [Getting started](docs/getting-started.md)
- [Testing guide](docs/testing.md)
- [Validation matrix](docs/validation-matrix.md)
- [Benchmark guide](docs/benchmark/README.md)
- [Chaos guide](docs/chaos/README.md)
- [Golden Demo checklist](docs/demo-checklist.md)
- [Architecture decision records](docs/adr/)

## License

[MIT License](LICENSE)
