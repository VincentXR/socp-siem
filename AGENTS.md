# Repository Guidelines

## Project Structure & Module Organization

SOCP is a multi-module Java 21/Spring Boot SIEM with a Vue workbench. Shared platform modules live in `platform/` (auth, tenant, audit, observability, and the rule engine); business services live in `services/`. The only frontend app is `frontend/apps/workbench`; reusable packages are under `frontend/packages/`. Use `infra/` for Docker and middleware configuration, `agents/` for Vector/Falco assets, `build/` for startup, verification, and demo scripts, and `docs/` for architecture and operating notes. Java tests are colocated under each module’s `src/test/java`.

## Build, Test, and Development Commands

```bash
bash build/mvnw.sh -DskipTests package              # Build all 28 Maven modules
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false
cd frontend && corepack pnpm install --frozen-lockfile
cd frontend && corepack pnpm build                  # Build workbench
cd frontend/apps/workbench && pnpm test             # Run frontend API contract tests
cd frontend/apps/workbench && pnpm verify           # Build and verify production artifacts
docker compose -f infra/docker-compose.yml up -d    # Start middleware
bash build/run-all.sh start                         # Start backend and frontend
python build/verify-slice.py                        # Run minimal integration checks
python build/verify-pipeline.py                     # Verify Kafka-to-alert pipeline
python build/failure-tests.py                       # Exercise dependency failures
```

Use `bash build/run-all.sh status|stop` to inspect or stop local services. Docker is required for middleware-backed checks.

## Coding Style & Naming Conventions

Follow existing Java/Spring style: four-space indentation, `PascalCase` types, `camelCase` members, and packages under `com.socp.<domain>`. Keep service context paths and versioned routes consistent with neighboring code. Vue/TypeScript uses two-space indentation, `PascalCase.vue` components, and `camelCase` composables/helpers. No repository-wide formatter or linter is configured; preserve surrounding style and keep changes focused.

## Testing Guidelines

Name Java tests `*Test.java` and keep unit or slice tests in the owning module. Run the full Maven test command before backend changes; run the relevant `verify-*.py` script when changing service boundaries, persistence, or event flow. Frontend changes must pass the workbench build and should include a screenshot or manual verification note for visual changes.

## Commit & Pull Request Guidelines

Use concise Conventional Commit-style messages such as `feat(ingest): ...`, `fix(ui): ...`, or `docs: ...`. PRs should explain the behavior change, list validation commands, call out configuration or middleware requirements, and include screenshots for frontend changes. Do not commit secrets or local database/build artifacts; use environment variables such as `SOCP_JWT_SECRET` for non-default credentials. Production changes must be checked with the `prod` profile, which is intended to reject development fallbacks.
