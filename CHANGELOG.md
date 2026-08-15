# Changelog

## Unreleased

### Engineering quality

- Unified current architecture, module and startup documentation.
- Added architecture decision records for the canonical event pipeline,
  storage boundaries, transactional Outbox and runtime profiles.
- Added frontend API/build artifact verification to pull-request CI.
- Added a manual/weekly full-stack workflow for API, event-pipeline, attack
  demo and dependency-failure verification with uploaded logs.
- Added focused tests for authentication safety, Outbox delivery, detection
  hot reload and incident merge idempotency.
- Added the MIT License and Maven license metadata for portfolio reuse.

## Release preparation

Before creating `v1.0.0`, run the checklist in
`docs/release-checklist.md`, run the full-stack workflow once, and attach the
generated screenshots or demo recording to the release.
