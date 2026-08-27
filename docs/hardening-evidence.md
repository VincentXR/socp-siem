# Hardening and evidence contract

This page records the security and correctness invariants that the repository
can prove. It deliberately avoids claiming Kafka or HTTP distributed
exactly-once semantics.

## Trusted event boundary

`search-config` accepts `POST /api/v1/ingest` only from one of these identities:

* a signed internal service request (`ServiceRequestSignature`), or
* a registered collector credential from
  `SOCP_COLLECTOR_CREDENTIALS`, formatted as
  `collector-id|tenant-id|secret;collector-id-2|tenant-id-2|secret-2`.

The credential supplies both the collector identity and the tenant. A request
tenant header must match that binding, and the body cannot override it. The
legacy `SOCP_INGEST_TOKEN` is a local-development compatibility path; set
`SOCP_ALLOW_GLOBAL_INGEST_TOKEN=false` in an environment that uses registered
collector credentials. The built-in Vector sink receives `SOCP_VECTOR_TOKEN`
when no per-sink credential is persisted, so production deployments must make
that value the secret of the registered collector (or persist a per-sink
credential). Chaos probes use the same boundary through
`PIPELINE_COLLECTOR_ID`, `PIPELINE_COLLECTOR_TOKEN`, and the direct
`PIPELINE_INGEST_URL` rather than impersonating a user JWT.

## Authorization boundary

`@RequireRole` protects user-facing mutations. Side-effecting service
boundaries use `@RequireService`, and collector/event boundaries use
`@RequireIngestIdentity`. Negative tests cover a viewer attempting a protected
write and a user JWT attempting a service-only side effect.

## Analysis idempotency

Secondary analysis claims the tuple
`(tenant_id, source_alarm_id, analyzer_version)` in `t_analysis_receipt`.
The claim, analyzed rows, and completion update share one transaction. A
committed Kafka redelivery returns `duplicate=true` without re-evaluating or
writing another result. A rolled-back attempt leaves no committed claim and is
retryable. Changing the analyzer version intentionally creates a new receipt.

## Production invariants

The `prod` profile fails fast when it detects default credentials, JWT bypass,
HTTP or trust-all OpenSearch, an unregistered canonical collector, a shared
in-memory rate-limit backend, or a Redis rate-limit backend that is not
fail-closed. OpenSearch uses the JVM trust store by default; a deployment can
provide an explicit trust store instead of enabling the local trust-all
escape hatch.

## Continuous evidence

Pull requests keep deterministic replay checks small. The nightly CI job runs
duplicate-delivery and Detection-outbox replay. The weekly Compose workflow
runs process, PostgreSQL, OpenSearch, and multi-instance rebalance scenarios
and uploads the JSON result, service logs, Kafka lag snapshots, and outbox
state. A failed scenario is evidence of a broken invariant, not a reason to
claim exactly-once behavior.

Coverage remains a floor and is not a substitute for these invariants. The
next quality step is changed-line coverage plus tests for every critical
invariant; generic DTO coverage should not be used to inflate the number.
