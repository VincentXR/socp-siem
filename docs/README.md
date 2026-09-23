# Documentation

This directory contains durable architecture, contract, verification, and
operations documentation that changes with the repository. One-off review
notes, remediation checklists, local evidence, and personal work logs belong
in pull requests, issues, CI artifacts, or the ignored `docs/_local/`
directory instead of source control.

## Choose guidance by task

This is an index, not a required reading sequence. Open only the documents
and sections needed for the current change.

| Task | Start with |
|---|---|
| Local setup, build, or service startup | [Getting started](getting-started.md) |
| Service ownership or boundaries | [Module map](module-map.md), then [architecture](architecture.md) if the boundary changes |
| API, auth, or tenant behavior | [API contract](api-contract.md), [tenant isolation](tenant-isolation.md) |
| Persistence, migrations, or event delivery | Owning module's migrations; [idempotency](idempotency-contract.md); [migration repair](operations/database-migration-repair.md) only for checksum drift/repair |
| Workbench UI | [Frontend guidelines](frontend-guidelines.md) |
| Verification | [Local change matrix](validation-matrix.md#local-change-validation), then relevant commands in [testing](testing.md) |
| Deployment or production configuration | [Production readiness](production-readiness.md), plus the relevant deployment/runbook below |
| Coding-agent instructions, Skills, or task prompts | [Agent guidance maintenance](#agent-instructions-and-skills) |

## Architecture and platform contracts

- [API compatibility](api-contract.md)
- [Tenant isolation](tenant-isolation.md)
- [Hardening and evidence](hardening-evidence.md)
- [Idempotency](idempotency-contract.md)
- [Endpoint forwarding and recovery](operations/endpoint-forwarding.md)
- [Event-path observability and tracing](observability-stage-metrics.md)
- [Workbench frontend guidelines](frontend-guidelines.md)

## Ingestion, search, and detection

- [Ingestion, canonical events, and Search runtime](ingestion-parsing.md)
- [Detection rules](detection-rules.md)
- [Detection runtime and state semantics](detection-state-semantics.md)
- [Detection state sharding](detection-state-sharding.md)
- [Sigma import contract](sigma.md)

## SOAR

- [SOAR design](soar-design.md)
- [SOAR OpenAPI](soar-openapi.yaml)
- [SOAR runbook](soar-runbook.md)

## Verification and operations

- [Failure and chaos scenarios](chaos/README.md)
- [Production delivery baseline](production-readiness.md)
- [Kubernetes release contract](operations/kubernetes.md)
- [Helm release runbook](../deploy/helm/README.md)
- [Backup and restore](operations/backup-restore.md)
- [Capacity planning](operations/capacity-planning.md)
- [DLQ replay](operations/dlq-replay.md)
- [Notification delivery and recovery](operations/notification-delivery.md)
- [Incident response](operations/incident-response.md)
- [Kafka topic provisioning runbook](operations/kafka-topic-provisioning.md)
- [Flyway checksum drift and repair runbook](operations/database-migration-repair.md)

## Architecture decision records

- [ADR 001: canonical event pipeline](adr/001-canonical-event-pipeline.md)
- [ADR 002: storage responsibilities](adr/002-storage-responsibilities.md)
- [ADR 003: transactional outbox](adr/003-transactional-outbox.md)
- [ADR 004: runtime profiles](adr/004-runtime-profiles.md)
- [ADR 005: outbox lifecycle](adr/005-outbox-lifecycle.md)
- [ADR 006: detection runtime selection](adr/006-detection-runtime-selection.md)
- [ADR 007: logical domains and evidence-gated consolidation](adr/007-runtime-deployment-units.md)

## Agent instructions and Skills

[AGENTS.md](../AGENTS.md) owns repository-wide coding-agent guidance. Keep
task-specific procedures in their owning docs and link them with a concrete
read condition. Avoid copying the same rules into editor prompts or Skills.
Historical session prompts and logs are task evidence, not current policy;
the SIEM application's runtime prompts describe product behavior separately.

When adding or updating a Skill, keep its description short: the specific
operation it handles and the condition that should trigger it. Broad keywords
such as "database", "frontend", or "security" are not sufficient triggers.
A complex `SKILL.md` should route to supporting references or scripts only
when the chosen workflow needs them. A simple Skill can stay self-contained.
Check both intended requests and nearby requests that should not select it,
along with frontmatter, reference paths, and any changed helper scripts.

Task prompts should state the requested outcome, relevant constraints, and
observable acceptance criteria. Reuse the authorization and completion
boundaries in `AGENTS.md`; add a review stop only when the task actually needs
that decision. Do not turn one past failure into a rule for every future task.
This follows OpenAI's [guidance on skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra).
