# Getting Started

The repository supports a lightweight local profile and a middleware-backed
integration profile. Use the latter when validating Kafka, OpenSearch,
ClickHouse, Outbox fan-out, or failure recovery.

## Prerequisites

- JDK 21 and a POSIX-compatible shell (Git Bash or WSL on Windows).
- Node.js 22 and Corepack/pnpm 10 for the workbench.
- Docker Desktop for integration checks.
- At least 24 GB RAM is recommended for the full stack; a slice is enough for
  normal feature development.

## Build and run

From the repository root:

```bash
# Build all 28 Maven modules without running tests
bash build/mvnw.sh -DskipTests package

# Install and build the workbench
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm --filter @socp/app-workbench build
cd ..

# Start or inspect the local backend and frontend
bash build/run-all.sh backend
bash build/run-all.sh status
bash build/start-frontend.sh
```

`build/ports.env` is the single port registry. Override a service port with an
environment variable such as `SOCP_PORT_ALERT_WEB=28080`.

## Tests and verification

Run the full Java suite before backend changes. For a focused change, use the
owning module plus `-am`; the test matrix and integration ownership are listed
in [testing.md](testing.md).

```bash
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false
```

## Integration profile

```bash
docker compose -f infra/docker-compose.yml up -d
bash build/run-all.sh backend
python build/verify-slice.py
python build/verify-pipeline.py
```

The optional middleware profile adds Keycloak, Temporal, Jaeger and dashboards:

```bash
docker compose -f infra/docker-compose.yml --profile extra up -d
```

Run `python build/verify-full.py` for the full API and persistence checks, and
`python build/failure-tests.py` for dependency stop/restart scenarios. These
scripts expect the corresponding backend services and Docker containers to be
running.

## Frontend checks

```bash
cd frontend/apps/workbench
pnpm test                         # API contract tests
pnpm build                        # vue-tsc + Vite production build
node scripts/verify-build.mjs    # artifact/chunk assertions
```

The frontend is served at `http://localhost:5173`; the gateway is at
`http://localhost:18092`.

## Configuration and safety

Local startup injects demo credentials for convenience. Never reuse them in a
shared or production environment. Set `SOCP_JWT_SECRET`, PostgreSQL variables,
OIDC settings, and the ingest token explicitly. Activate the `prod` profile to
make `ProdGuard` reject H2, demo secrets, authentication bypass, default ingest
credentials, and disabled Temporal.

Stop local processes with `bash build/run-all.sh stop` and middleware with:

```bash
docker compose -f infra/docker-compose.yml down
```
