# Getting Started

This guide covers local development and middleware-backed integration checks.
Use the integration setup when validating Kafka, OpenSearch, ClickHouse,
Outbox fan-out, or dependency recovery.

## Prerequisites

- JDK 21 and a POSIX-compatible shell such as Git Bash or WSL.
- Node.js 22, Corepack, and pnpm 10 for the workbench.
- Docker Desktop for middleware and integration checks.
- At least 24 GB RAM is recommended for the complete local stack. A service
  slice is sufficient for most feature development.

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

## Start the local stack

```bash
# PostgreSQL, Kafka, OpenSearch, ClickHouse and supporting middleware
docker compose -f infra/docker-compose.yml up -d

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

Optional middleware for OIDC, Temporal, Jaeger, and dashboards:

```bash
docker compose -f infra/docker-compose.yml --profile extra up -d
```

## Runtime configuration

The startup scripts use the `dev` profile for local login defaults. Services
with `application-pg.yml` support the `pg` profile for PostgreSQL-backed local
or integration runs. Use `prod` with explicit environment configuration to
activate `ProdGuard`; it rejects H2, demo credentials, authentication bypass,
the default ingest token, and disabled Temporal.

Important variables include `SOCP_JWT_SECRET`, PostgreSQL connection settings,
OIDC settings, and the Vector ingest token. `build/ports.env` documents the
service URL overrides used by scripts and gateway configuration.

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
check for each type of change. See [demo-checklist.md](demo-checklist.md) for
the Golden Demo and the Kafka recovery walkthrough.

## Stop the environment

```bash
bash build/run-all.sh status
bash build/run-all.sh stop
docker compose -f infra/docker-compose.yml down
```
