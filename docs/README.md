# Documentation

This directory contains durable architecture, contract, verification, and
operations documentation that changes with the repository. One-off review
notes, remediation checklists, local evidence, and personal work logs belong
in pull requests, issues, CI artifacts, or the ignored `docs/_local/`
directory instead of source control.

## Start here

- [Getting started](getting-started.md)
- [Architecture](architecture.md)
- [Module map](module-map.md)
- [Testing guide](testing.md)
- [Validation matrix](validation-matrix.md)
- [Production readiness and service matrix](production-readiness.md)

## Architecture and platform contracts

- [API compatibility](api-contract.md)
- [Tenant isolation](tenant-isolation.md)
- [Hardening and evidence](hardening-evidence.md)
- [Idempotency](idempotency-contract.md)
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
