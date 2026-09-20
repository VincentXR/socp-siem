# Repository Agent Guide

## Scope and autonomy

These instructions apply to the repository. More specific `AGENTS.md` or
`AGENTS.override.md` instructions apply within their subtree. Explicit user
instructions take precedence over project workflow preferences.

Inspect `git status` before editing and preserve unrelated work. For an
implementation request, proceed with relevant local edits, locked dependency
installation, builds, tests, and fixes without asking for approval at each
step. Make routine implementation choices within the requested scope.
Review-only and diagnosis-only requests remain read-only.

Local development services and disposable test fixtures may be started when
needed for verification. Check the target, profile, and endpoint overrides
before running service, migration, or failure-injection commands: a local
script can still reach shared infrastructure. Production/shared-system writes,
deleting persistent data, external publication, commits, and pushes require
authorization for that action; reuse authorization already given.

Ask only when a missing decision materially changes the outcome or an action
needs authority not already provided. Continue independent, authorized work
while that decision is pending.

## Completion

An implementation is complete when the requested outcome is implemented,
relevant validation has run (including build/tests or runtime checks for
behavior changes), results have been inspected, failures caused by the change
have been fixed, and affected checks pass after the final fix. Update durable
documentation when behavior changes.
Do not stop at a plan or first patch when authorized work remains.

Choose validation from [the local change matrix](docs/validation-matrix.md#local-change-validation).
Run `git diff --check` and inspect the final diff. Once relevant checks pass,
repeat or broaden them only for new changes, failures, or unresolved risks.
Do not weaken tests or safety controls to obtain a passing result.

If a required check cannot run, exhaust safe local alternatives and report the
specific blocker and missing evidence; do not claim verified completion.
The handoff should state what changed, exact validation commands and results,
and any remaining compatibility, migration, or operational impact.

## Project and task routing

SOCP is a Java 21/Spring Boot SIEM with a Vue/TypeScript workbench. Backend
modules live in `platform/` and `services/`; Java tests stay in the owning
module's `src/test/java`. The frontend app is `frontend/apps/workbench`, with
shared packages in `frontend/packages` and tests beside code or in `e2e/`.
`agents/` contains telemetry collectors, not coding-agent skills.

Use Node.js 22, Corepack/pnpm 10, and Python 3; Docker is needed for middleware
checks and Helm 4 for Kubernetes checks. Run Bash scripts through Git Bash or
WSL; PowerShell wrappers exist for `build/mvnw.ps1`, `build/compose.ps1`, and
`build/quality-gate.ps1`. Follow surrounding Java and Vue/TypeScript style.

Use [the documentation index](docs/README.md) to locate guidance for the
current task and [the testing guide](docs/testing.md) for command details.
Read only relevant sections; architecture, database, and deployment documents
are not prerequisites for every edit. Code, configuration, migrations,
OpenAPI, and executable contracts take precedence over descriptive prose.

## Security and correctness boundaries

- Enforce authorization and tenant isolation in the owning service; request
  tenant IDs, user-controlled identities, gateway checks, and frontend checks
  are not sufficient authority.
- Preserve service context paths, versioned routes, response envelopes, and
  error semantics unless changing them is part of the task. API, schema,
  topic, migration, and runtime-role changes need corresponding contracts and
  compatibility verification.
- Preserve at-least-once delivery: stable identities, replay-safe side
  effects, durable journal/outbox state, and contiguous offset commits.
  Advance offsets or terminal state only after required durable writes are
  acknowledged. Exactly-once claims require executable proof of their scope.
- Bound event-path queues, caches, retries, result sets, and tenant-controlled
  cardinality. Cross-replica correctness must not depend on process-local
  locks, suppression, rate limits, or mutable state.
- Keep `prod` fail-closed: no demo credentials, H2, auth bypass, trust-all TLS,
  in-memory distributed state, or silent dependency fallback.
- Never commit secrets, middleware data, local overrides, build outputs,
  caches, or logs. Follow [SECURITY.md](SECURITY.md) for security configuration.
- Add or upgrade dependencies only when needed for the task; explain new
  production dependencies. Reliability, performance, and production-readiness
  claims must stay within current executable evidence.

## Durable changes

Keep reusable architecture, contracts, operating guidance, and project
instructions in source control. Put one-off review notes and task evidence in
the ignored `docs/_local/`, or in issues/PRs/CI artifacts when authorized.
Do not add remediation ledgers or personal work-memory files.

When a commit is requested, stage only intended files and use a concise
Conventional Commit message such as `fix(ingest): ...` or `docs: ...`.
