# Detection state sharding

Stateful rules are routed by the immutable tuple `tenantId + routingField +
routingValue`. The same tuple always lands on the same shard, while records in
one shard are processed serially. A rebalance must first advance the assignment
epoch and stop the previous owner before the new owner restores its snapshot.

`DetectionStateSnapshot` is an opaque, versioned envelope containing the rule
version, tenant, shard, Kafka offset and serialized state. Implementations must
write snapshots atomically and only acknowledge a recovery barrier after the
snapshot is durable. The runtime loads the latest durable checkpoint and
replays journaled events after that checkpoint (the default cadence is every
500 durable events), so restart recovery no longer depends on replaying the
entire journal.

`socp.detect.state.shards` enables one to 256 in-process shards. Each event is
routed with the same `tenantId + detectionRoutingField + detectionRoutingValue`
hash used by the Kafka key, and every shard has its own serial rule engine and
snapshot namespace. The default remains one shard for backwards compatibility.
Cross-instance assignment epochs/barriers and a three-instance failover proof
are still required before making a production HA claim; the local Compose
deployment remains single-node.
