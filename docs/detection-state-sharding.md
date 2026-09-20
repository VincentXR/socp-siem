# Detection state sharding

This page owns the operational details of in-process state shards and
checkpoint storage. The routed-v2 topology, source/delivery identities,
migration modes, lifecycle, and failure semantics are defined in
[Detection state semantics](detection-state-semantics.md).

## State unit and checkpoints

Each Kafka `(input topic, partition, state shard)` is a durable ownership unit.
The owner lease carries a monotonic fencing epoch; a revoked or superseded
worker cannot commit an alert, journal completion, or checkpoint generation.

`DetectionStateSnapshot` is an opaque, versioned envelope containing the rule
version, input topic, tenant, shard, partition-offset vector, owner-epoch
vector, and serialized state. Snapshots are written atomically and a recovery
barrier is acknowledged only after the snapshot is durable. A generation whose
offset vector does not cover its predecessor is rejected.

Startup and partition assignment rebuild state from the configured retention
window in partition/offset order. Reads are paginated and PENDING prefetch is
bounded; records beyond that prefix remain behind uncommitted Kafka offsets and
return through normal delivery. Flyway migration
`V23__detection_checkpoint_replay_index.sql` supplies the
`(tenant_id, status, kafka_partition, kafka_offset)` access path. It uses plain
`CREATE INDEX` because the in-process Flyway runner wraps migrations in a
transaction. Apply it to a populated database in a maintenance window with a
bounded migration-role `lock_timeout`.

## Operating the in-process shard count

`socp.detect.state.shards` accepts 1 through 256 and defaults to 1. Each routed
delivery is assigned with the same
`tenantId + routingField + routingValue` hash used by the routed Kafka key;
every shard owns an independent serial rule engine and snapshot namespace.

Increase the shard count only when rule-evaluation latency grows while Kafka
lag remains low. Sharding can divide state across balanced routing values, but
it does not reduce the cost of one hot routing value or bound a rule's state.
Snapshot and rollback work also remains on the per-event path.

No Prometheus series exports per-shard state size or balance. Validate a change
by comparing the `rule_evaluation` latency distribution and inspect
`t_detection_state_owner` plus the per-shard snapshot rows for the authoritative
distribution. A shard-count change is a state-layout change and requires a
controlled restart/rebuild; do not mix counts within one consumer group.

## Statistics scope

`GET /detect-web/api/v1/stats` reports the replica that served the request and
labels state recovery as replica-local:

- event, alert, drop, suppression, queue, and rule counters cover the tenant's
  materialized engines on that replica;
- `isolatedRules` counts rule documents that could not be compiled there;
- `routingMismatchWindows` counts bounded routed-state contract mismatches;
- `stateRecovery.engineScopes` and `pendingRebuilds` identify individual
  engines still recovering.

Suppression and secondary-analysis storm collapsing are replica-local
heuristics. They reset on restart and must not be presented as cluster-wide
deduplication.

## Rule reload

A saved rule is compiled before persistence. Engine assembly still isolates
each document so one undeployable rule is skipped and reported rather than
stopping the tenant engine.

A hot reload drains and rebuilds only the edited tenant's engine keys. The old
engines remain live until the replacement has replayed durable state and can be
swapped under the lifecycle lock. A failed reload keeps the previous engines
serving and leaves the affected keys for scheduled recovery; process-wide
recovery is reserved for startup and full assignment rebuilds.
