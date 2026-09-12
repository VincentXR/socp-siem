# Repository Agent Guide

## Scope and sources of truth

These instructions apply to the whole repository. If a subdirectory later
adds its own `AGENTS.md` or `AGENTS.override.md`, follow the closest applicable
instructions for files in that subtree.

Before editing, inspect `git status` and preserve all unrelated user changes.
Keep changes focused on the requested outcome. Treat code, configuration,
migrations, OpenAPI files, and other machine-readable contracts as more
authoritative than prose; update durable documentation when behavior changes.
Start documentation work from `docs/README.md`.

Commit only durable architecture, contracts, operating guidance, and project
instructions. Put one-off review notes and task progress in issues, pull
requests, CI artifacts, or the ignored `docs/_local/` directory. Do not add
remediation ledgers or personal work-memory files to source control.

## Repository map

SOCP is a Java 21/Spring Boot SIEM with a Vue/TypeScript workbench.

- `platform/`: shared auth, tenant, audit, data, observability, client, and rule
  engine modules.
- `services/`: business services and executable applications.
- `frontend/apps/workbench`: the only frontend application.
- `frontend/packages`: reusable frontend packages.
- `infra/`: local middleware and deployment configuration.
- `agents/`: Vector, Falco, Sysmon, and related collection assets.
- `build/`: wrappers, launchers, quality gates, verification, chaos, and demo
  scripts.
- `docs/`: indexed architecture, contract, verification, and operations docs.

Java tests belong in the owning module under `src/test/java`. Frontend tests
stay beside the workbench code or in its `e2e/` directory.

## Toolchain and common commands

Use Java 21, Node.js 22, Corepack/pnpm 10, Python 3, and Docker Desktop. Run
Bash scripts through Git Bash or WSL; native PowerShell may use the matching
`build/mvnw.ps1` or `build/quality-gate.ps1` wrapper.

```bash
# Backend build and tests
bash build/mvnw.sh -DskipTests package
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false
bash build/mvnw.sh -pl services/soar-web -am test -Dsurefire.failIfNoSpecifiedTests=false  # replace with the owning module
bash build/quality-gate.sh

# Frontend install and checks
cd frontend && corepack pnpm install --frozen-lockfile && corepack pnpm build
cd frontend/apps/workbench && pnpm test && pnpm lint && pnpm format:check && pnpm verify

# Middleware and application lifecycle
docker compose -f infra/docker-compose.yml up -d
bash build/run-all.sh start core       # core event path and workbench
bash build/run-all.sh start ui         # business-page services and workbench
bash build/run-all.sh start            # complete stack
bash build/run-all.sh status full
bash build/run-all.sh stop

# Integration and failure checks; these require the relevant running services
python build/verify-slice.py
python build/verify-pipeline.py
python build/failure-tests.py
```

Use `docs/testing.md` and `docs/validation-matrix.md` to choose specialized
verification instead of guessing commands.

## Implementation rules

- Follow surrounding Java/Spring style: four-space indentation, `PascalCase`
  types, `camelCase` members, and packages under `com.socp.<domain>`.
- Follow surrounding Vue/TypeScript style: two-space indentation,
  `PascalCase.vue` components, and `camelCase` composables and helpers.
- Preserve established service context paths, versioned routes, response
  envelopes, tenant boundaries, and error semantics unless the task explicitly
  changes the contract.
- Enforce authorization and tenant isolation in the owning service. Gateway or
  frontend checks are defense in depth, not the authority.
- Preserve at-least-once delivery invariants: use stable identities,
  idempotency, durable journal/outbox state, and contiguous offset commits.
  Never describe a distributed path as exactly-once without an executable
  proof of that narrower claim.
- Avoid unbounded queues, caches, retries, result sets, or tenant-controlled
  cardinality on event paths. Multi-instance behavior must not rely on
  process-local coordination.
- Keep production fail-closed. Do not introduce demo credentials, H2,
  authentication bypass, trust-all TLS, in-memory distributed state, or silent
  dependency fallback into the `prod` profile.
- Do not add or upgrade dependencies unless the requested change needs them;
  explain any new production dependency in the handoff.

## Validation requirements

Run checks proportionate to the affected boundary and report exactly what ran.
Never present a skipped, stale, or local-only result as current production
evidence.

- Every change: run `git diff --check` and inspect the final diff.
- Backend module change: run the owning module tests with `-pl ... -am`.
- Shared platform, cross-module, persistence, or event-flow change: run the
  full Maven suite and the relevant `build/verify-*.py` contracts.
- Frontend change: run `pnpm test`, `pnpm lint`, `pnpm format:check`, and
  `pnpm verify`; run Playwright for changed user flows.
- Visual frontend change: include a screenshot or a clear manual-verification
  note.
- Service-boundary or topology change: run `python build/verify-contracts.py`
  and `python build/runtime-topology.py --check`.
- Production configuration change: run `python build/verify-production.py`
  and the applicable `prod` guard/boot checks.
- Middleware-backed behavior: run the relevant pipeline, full-stack, chaos, or
  Testcontainers check when Docker is available; state the limitation when it
  is not.
- Documentation change: verify relative links, commands, names, and references
  to renamed files.

## Git and handoff

Do not discard, overwrite, stage, or reformat unrelated work. Do not commit or
push unless the user explicitly requests it. When authorized, use a concise
Conventional Commit message such as `feat(ingest): ...`, `fix(ui): ...`, or
`docs: ...`, and stage only the intended files.

Never commit secrets, local middleware overrides, database files, build
outputs, dependency caches, logs, or machine-specific evidence. Use documented
environment variables such as `SOCP_JWT_SECRET` for non-default credentials.
A handoff or pull request should summarize behavior, list exact validation
commands and results, identify skipped environment-dependent checks, and call
out configuration, migration, compatibility, or operational impact.

## Code review rules

- Flag any path that trusts a request tenant, user-controlled identity, or
  gateway-only authorization without revalidation at the owning service.
- Flag Kafka offset or terminal-state advancement before required durable
  writes are acknowledged; require deterministic identities and replay-safe
  side effects.
- Flag process-local locking, suppression, rate limiting, or mutable state when
  correctness is claimed across replicas.
- Flag production paths that silently fall back to development storage,
  credentials, authentication, TLS, execution, or in-memory behavior.
- Flag API, schema, topic, migration, or runtime-role changes that omit the
  corresponding compatibility tests and machine-readable contract updates.
- Flag capability, reliability, performance, or production-readiness claims
  that are broader than the executable evidence for the current commit.
