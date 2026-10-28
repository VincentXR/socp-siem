# Getting Started

This guide covers local development and middleware-backed integration checks.
Use the integration setup when validating Kafka, OpenSearch, ClickHouse,
Outbox fan-out, or dependency recovery.

## Prerequisites

- JDK 21 and a POSIX-compatible shell such as Git Bash or WSL.
- Node.js 22, Corepack, and pnpm 10 for the workbench.
- Docker Desktop for middleware and integration checks.
- Size the local stack from measured process/container use, memory pressure
  and swap under your workload. Heap limits and container caps are not actual
  memory consumption; the complete stack has no verified minimum-RAM claim.

## Install and build

Run these commands from the repository root:

```bash
# Build the Java reactor
bash build/mvnw.sh -DskipTests package

# Install frontend dependencies and build the workbench
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm build
cd ..
```

`build/ports.env` is the single port registry. Override a port without editing
the scripts, for example:

```bash
SOCP_PORT_ALERT_WEB=28080 bash build/run-all.sh backend
```

Use `SOCP_JVM_OPTS` to set the default JVM budget for every backend. A service
can override it with `SOCP_<SERVICE>_JVM_OPTS`, where hyphens become
underscores, for example `SOCP_SEARCH_CONFIG_JVM_OPTS`. The fixed Detection
cluster follows the same rule through `SOCP_DETECT_WEB_JVM_OPTS`.

## Start the local stack

```bash
# PostgreSQL, Kafka, OpenSearch, Redis, ClickHouse, MinIO and Temporal.
# build/compose.sh injects the pinned image catalog for every service.
bash build/compose.sh up -d
# Native PowerShell equivalent: .\build\compose.ps1 up -d

# Start the complete backend and the workbench dev server
bash build/run-all.sh start

# Start only the core event path and the workbench (lower memory usage)
bash build/run-all.sh start core

# Start all page backends when inspecting every workbench module, without collectors
bash build/run-all.sh start ui

# Start every backend and collector for full-stack verification
bash build/run-all.sh start full

# Or start only the backend and run the frontend separately
bash build/run-all.sh backend core
bash build/start-frontend.sh
```

The workbench is available at `http://localhost:5173`; the gateway is available
at `http://localhost:18092`. The local demo account is `demo / demo123` and
must not be reused outside development.

Optional tools start individually, without enabling every extra service:

```bash
# Pick only the tools needed for this session; dependencies start automatically.
bash build/compose.sh up -d prometheus grafana
bash build/compose.sh up -d dashboards temporal-ui
bash build/compose.sh up -d jaeger loki
bash build/compose.sh up -d keycloak

# Explicitly opt into all extras when needed.
bash build/compose.sh --profile extra up -d
```

Prometheus, Grafana, OpenSearch Dashboards, Temporal UI, Jaeger, Loki and
Keycloak are off by default. Temporal itself remains a required dependency
for real SOAR execution. Profiles do not stop already-running extras: inspect
`bash build/compose.sh ps -a` before starting a session.

### Lower-concurrency development on a 16 GB Mac

Use the same real middleware and serialize JVM startup; keep each service's
existing `-Xms32m -Xmx256m` heap until measurements identify a specific need:

```bash
# Defaults for a development database initialized by this Compose project.
# For existing databases, supply their actual runtime and migration credentials.
export SOCP_PG_USER="${SOCP_PG_USER:-socp_runtime}"
export SOCP_PG_PASSWORD="${SOCP_PG_PASSWORD:-socp}"
export SOCP_PG_MIGRATION_USER="${SOCP_PG_MIGRATION_USER:-socp_migrator}"
export SOCP_PG_MIGRATION_PASSWORD="${SOCP_PG_MIGRATION_PASSWORD:-socp-migrator-local}"
bash build/compose.sh up -d
SOCP_START_BATCH_SIZE=1 bash build/run-all.sh start core
# Alternatively, every business service and the workbench:
SOCP_START_BATCH_SIZE=1 bash build/run-all.sh start full
```

`core` is a service slice, not full business acceptance. The `dev` / `dev,pg`
launcher sets the following search-cache trial budgets unless overridden:

```bash
SOCP_SEARCH_CACHE_MAX_BYTES_TOTAL=67108864       # 64 MiB
SOCP_SEARCH_CACHE_MAX_BYTES_PER_TENANT=8388608  # 8 MiB
SOCP_SEARCH_CACHE_MAX_CONCURRENT_WARMUPS=1
```

These weights do not include all decoded-object or JVM overhead. The launcher
does not inject them into `prod`; application/production defaults stay intact.
Eviction removes cache entries, not persisted events. Normal search reads
OpenSearch; its degraded local fallback is a bounded window and must not be
treated as a complete historical result. See the [development memory verification
procedure](operations/development-memory.md) before drawing capacity conclusions.

## Runtime configuration

The startup scripts use the explicit `dev,pg` profiles by default. This keeps
the core relational services on PostgreSQL so local verification exercises
real transaction and uniqueness semantics. Use `SOCP_RUNTIME_PROFILES=dev` for
the intentional H2 fallback for services that support it. This does not remove
the Kafka/OpenSearch/Redis/Temporal requirements of enabled features. Use `SOCP_RUNTIME_PROFILES=prod` only with
explicit production environment configuration; `ProdGuard` rejects H2, demo
credentials, authentication bypass, the default ingest token, and disabled
Temporal.

The three modes are intentionally exclusive at the launcher level:

- `dev,pg`: PostgreSQL-backed core services plus development defaults;
- `dev`: disposable or file-backed H2, development middleware defaults;
- `prod`: PostgreSQL/JWKS or explicitly approved security settings, no demo
  data or simulator fallback.

Important variables include `SOCP_JWT_SECRET`, `SOCP_RUNTIME_PROFILES`,
`SOCP_RATELIMIT_BACKEND`, `SOCP_RULE_STATE_MAX_KEYS`, PostgreSQL connection
settings, OIDC settings, the Vector ingest token, and
`SOCP_SECURITY_METRICS_TOKEN`. External Notify/SOAR connectors also require
`SOCP_CLIENT_EXTERNAL_ALLOWED_HOSTS`; HTTPS and public DNS resolution are
enforced by default. Scheduled SOAR playbooks use UTC unless
`SOCP_SOAR_SCHEDULE_ZONE` names another IANA time zone. `build/ports.env`
documents the service URL overrides used by scripts and gateway configuration.

## Verification

```bash
# Backend tests
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# Frontend API contracts, type checking, build, and artifact assertions
cd frontend/apps/workbench
pnpm test
pnpm verify
cd ../../..

# Cross-service checks; middleware and the relevant services must be running
python build/verify-slice.py
python build/verify-pipeline.py
python build/verify-full.py
python build/failure-tests.py
```

See [testing.md](testing.md) for test ownership and the smallest suitable
check for each type of change. The Golden Demo, recovery scenarios, and their
acceptance criteria are indexed in the [validation matrix](validation-matrix.md).

## Multi-instance Detection check

For the reference state-ownership check, build the reactor once, configure six
Kafka partitions, and start the fixed three-instance Detection cluster. All
instances use the `pg` profile, the same PostgreSQL database, and the same
`SOCP_KAFKA_GROUP_ID`:

```bash
bash build/detection-cluster.sh start
```

Run the versioned dataset with a unique namespace:

```bash
SOCP_DETECT_PROFILE=pg \
DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082,http://127.0.0.1:28083 \
  python build/chaos-pipeline.py --scenario multi_instance --count 30 --rebalance-cycles 3 \
  --run-id local-multi-instance --output .cache/chaos/local-multi-instance.json
bash build/detection-cluster.sh stop
```

`SOCP_DETECT_PROFILE=pg` also makes the script restart the controlled instance
against the shared store. The oracle checks disjoint ownership, exact alert-ID
set equality, duplicate count, Kafka lag, terminal event completion, delivery
receipts, logical ClickHouse uniqueness, and assignment restoration.

## Stop the environment

```bash
bash build/run-all.sh status
bash build/run-all.sh stop
bash build/compose.sh down
```
