# ADR 006: select one production Detection runtime

Status: accepted for incremental migration

## Context

The Detection path needs one authoritative execution model. A second runtime
would create two interpretations of partition ownership, state recovery,
late-event policy, result commits, and rule-version promotion.

This remediation compared the existing partition-lane consumer with a narrow
Kafka Streams topology. The comparison covers out-of-order threshold input,
live rule configuration, and materialized state. It is intentionally not a
production topology and does not own SOCP's PostgreSQL result/outbox boundary.

## Decision

The existing partition-lane Detection runtime remains the only production
runtime. `KafkaEventConsumer` and `DetectEngineService` own the production
execution path; `SOCP_DETECT_RUNTIME_ROLE=worker` is the only role that restores
state, owns Kafka partitions, evaluates rules, and commits Detection results.
The `api` role manages rule/configuration writes and Outbox data but does not
restore state or create live rule engines. `all` remains a local-development
compatibility role.

Kafka Streams remains test-scoped comparison code in `platform/socp-rule`.
It must not be added to a deployed service, production dependency set, or
runtime topology. There is no production migration to perform in this round;
the current runtime is the selected target and its explicit state protocol is
the contract to operate.

## Rationale

The selected runtime already expresses the correctness boundaries required by
SOCP:

1. a state unit is bound to input topic, partition, shard, routing version, and
   the owning durable lease/fencing epoch;
2. recovery restores a compatible snapshot and replays exact Kafka positions
   before normal processing is accepted;
3. calculation produces an explicit `DetectionResult`, while the alert Outbox
   and journal completion share an owner-guarded transaction;
4. partition pause/resume, tenant admission, rule fuses, and dependency
   failures are visible at the same boundary as the consumer; and
5. rule changes, snapshots, and completion records can be fenced by the
   external PostgreSQL owner row.

The Kafka Streams comparison is useful evidence for event-time and state-store
concepts, but it does not replace those external commit and fencing semantics.
Keeping it test-scoped therefore gives a reference without introducing a
second production meaning of a Detection state unit.

## Rollout and exit evidence

No dual production run is planned. Operators deploy the API and worker roles
independently, keep the worker on one Kafka group, and use the existing owner
lease, snapshot, replay, and Outbox metrics during rollout. A future runtime
replacement would require an isolated compatibility test, an explicit state
format migration, replay/result parity, failure recovery, and a reversible
cutover plan before changing this ADR.

The isolated `KafkaStreamsDetectionComparisonTest` passes for the current
comparison cases. The broker-backed changelog test is opt-in through
`SOCP_TESTCONTAINERS=true`; it remains pending when Docker/Kafka middleware is
unavailable. That environment limitation is evidence still required for
Stage B, not a reason to deploy a second runtime.
