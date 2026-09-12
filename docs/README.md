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
- [Service readiness matrix](maturity-matrix.md)

## Architecture and platform contracts

- [API compatibility](api-contract.md)
- [Canonical event schema](event-schema.md)
- [Tenant isolation](tenant-isolation.md)
- [Hardening and evidence](hardening-evidence.md)
- [Idempotency](idempotency-contract.md)
- [Event-path observability](observability-stage-metrics.md)
- [Workbench internationalization](frontend-i18n.md)

## Ingestion, search, and detection

- [Ingestion parsing](ingestion-parsing.md)
- [Search runtime roles](search-runtime-roles.md)
- [Detection rules](detection-rules.md)
- [Detection runtime roles](detection-runtime-roles.md)
- [Detection state semantics](detection-state-semantics.md)
- [Detection state sharding](detection-state-sharding.md)
- [Sigma import contract](sigma.md)

## SOAR

- [SOAR 2.0 design](soar-2.0-design.md)
- [SOAR 2.0 OpenAPI](soar-2.0-openapi.yaml)
- [SOAR 2.0 runbook](soar-2.0-runbook.md)
- [Action connector contract](soar-action-connectors.md)

## Verification and operations

- [Pipeline benchmark](benchmark/README.md)
- [Failure and chaos scenarios](chaos/README.md)
- [Operational demo checklist](demo-checklist.md)
- [Production delivery baseline](production-readiness.md)
- [Kubernetes release contract](operations/kubernetes.md)

## Architecture decision records

- [ADR 001: canonical event pipeline](adr/001-canonical-event-pipeline.md)
- [ADR 002: storage responsibilities](adr/002-storage-responsibilities.md)
- [ADR 003: transactional outbox](adr/003-transactional-outbox.md)
- [ADR 004: runtime profiles](adr/004-runtime-profiles.md)
- [ADR 005: outbox lifecycle](adr/005-outbox-lifecycle.md)
- [ADR 006: detection runtime selection](adr/006-detection-runtime-selection.md)
- [ADR 007: runtime deployment units](adr/007-runtime-deployment-units.md)
