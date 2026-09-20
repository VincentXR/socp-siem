# ADR 006: select one production Detection runtime

Status: accepted

## Context

The Detection path needs one authoritative execution model. A second runtime
would create two interpretations of partition ownership, state recovery,
late-event policy, result commits, and rule-version promotion.

The decision compares the partition-lane consumer with a narrow Kafka Streams
reference topology. The reference covers out-of-order threshold input, live
rule configuration, and materialized state. It is test-scoped and does not own
SOCP's PostgreSQL result/outbox boundary.

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
runtime topology. The partition-lane runtime and its explicit state protocol
are the production contract.

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

## Replacement criteria

Operators deploy the API and worker roles independently, keep workers on one
Kafka group, and observe owner leases, snapshots, replay, and Outbox metrics
during rollout. Replacing the runtime requires an isolated compatibility test,
an explicit state-format migration, replay/result parity, failure recovery,
and a reversible cutover plan before changing this ADR.

`KafkaStreamsDetectionComparisonTest` is reference evidence only. Its
broker-backed changelog case requires `SOCP_TESTCONTAINERS=true`; absence of
that environment does not authorize a second production runtime.
