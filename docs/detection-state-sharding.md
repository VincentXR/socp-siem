# Detection state sharding

Stateful rules are routed by the immutable tuple `tenantId + routingField +
routingValue`. The same tuple always lands on the same shard, while records in
one shard are processed serially. A rebalance must first advance the assignment
epoch and stop the previous owner before the new owner restores its snapshot.

`DetectionStateSnapshot` is an opaque, versioned envelope containing the rule
version, input topic, tenant, shard, partition-offset vector, owner-epoch
vector and serialized state. Implementations must write snapshots atomically and only
acknowledge a recovery barrier after the snapshot is durable. The runtime loads
the latest durable checkpoint and replays journaled events after that
checkpoint (the default cadence is every 500 durable events), so restart
recovery no longer depends on replaying the entire journal.

`socp.detect.state.shards` enables one to 256 in-process shards. Each event is
routed with the same `tenantId + detectionRoutingField + detectionRoutingValue`
hash used by the Kafka key, and every shard has its own serial rule engine and
snapshot namespace. The default remains one shard for backwards compatibility.

For Kafka-backed processing, `t_detection_state_owner` is the durable lease for
one `(input topic, partition, state shard)` unit. Claiming or taking over the
row increments `fencing_epoch`; partition revoke immediately invalidates the
old token. The event-aware Detection sink checks and renews that token inside
the transaction containing the Alert Outbox and journal completion, so a stale
worker cannot commit a new durable result after takeover. Snapshot generations
persist the token alongside each partition offset and reject a superseded
owner before changing checkpoint rows. `V16` creates the owner table, `V17`
adds the snapshot owner-epoch vector, and `V18` binds snapshots to the input
topic.

## Cross-entity grouping contract

The current production contract supports partition-local state only. For a
stateful rule, `groupBy`, the compatibility alias `keyField`, and
`routingField` must identify the same event dimension. A rule that groups by
`user` while the Kafka key routes by `host` is rejected by the validation API
and by persistence with a client-visible HTTP 400 error. It is never silently
accepted with weaker ordering or partial state.

Cross-entity grouping requires an explicit repartition or fan-out design,
including its state ownership, late-event behavior, recovery, and cost limits.
Until that design is implemented, clients must model the rule on the event's
routing dimension or keep it stateless.

Cross-instance assignment barriers and a three-instance failover proof are
still required before making a production HA claim; the local Compose
deployment remains single-node.
