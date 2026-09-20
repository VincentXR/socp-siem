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
checkpoint (the default cadence is every 500 durable events).

That checkpoint fast path applies to the engines assembled by a rule hot reload
and by the lazily created engine of a tenant that is not cached yet. The Kafka
assignment rebuild is a different path on purpose: `rebuildForPartitions` closes
the engines of the current assignment and replays the `COMPLETED` journal rows
of the configured `socp.detect.state.retention` window (default `24h`) in
partition/offset order. Recovery therefore never reads the whole journal - it is
window-bounded and paginated - but it does not start from a checkpoint either,
so sizing that window is what bounds rebuild cost. Snapshot rows are keyed by
`(tenant, rule, shard)` while ownership is `(input topic, partition, state
shard)`, so a generation whose offset vector does not cover every partition of
its predecessor is rejected rather than partially advanced.

The Kafka-position-ordered journal scans (the checkpoint-vector tail and the
partition-bounded recent/PENDING replays) filter on `(tenant_id, status)` and
order by `(kafka_partition, kafka_offset)`; the composite index added in Flyway
`V23__detection_checkpoint_replay_index.sql` serves them in index order instead
of fetching by one index and sorting by another. That migration is a plain
`CREATE INDEX`: the in-Pod Flyway runner executes inside a migration transaction
and cannot use `CONCURRENTLY`, so the build takes a brief write lock on the hot
`t_detection_event` table. Operators applying V23 on a populated database should
run it in a maintenance window and set a conservative `lock_timeout` on the
migration datasource so a long-running writer cannot park the migration (and the
rollout) behind an unbounded lock wait; the index build itself is short on a
normal-sized tail.

`socp.detect.state.shards` enables one to 256 in-process shards. Each event is
routed with the same `tenantId + detectionRoutingField + detectionRoutingValue`
hash used by the Kafka key, and every shard has its own serial rule engine and
snapshot namespace. The default remains one shard for backwards compatibility.

## Operating the shard count

Raising the shard count is the available mitigation for a specific symptom, not
a general tuning knob. The symptom is rule evaluation becoming the constraint:
`socp_detection_event_stage` for `rule_evaluation` grows while
`socp_kafka_consumer_lag` stays near zero. That combination says the engine is
behind, not the broker, and it is what the state-cardinality growth described
below looks like from the outside.

What raising the count buys: each shard owns an independent engine and state
namespace, so the per-event cost - which is proportional to the state a rule
holds - is divided across shards **only to the extent the routing hash is
balanced**. Raising the count on a workload whose events concentrate on few
routing values moves the same state into one shard and changes nothing.

What it does not fix: a single rule's per-event cost stays proportional to that
shard's state. Sharding divides the problem; it does not bound it. The durable
path also snapshots rule state to detect changes and to roll back a failed
event, and that cost is paid per event regardless of shard count.

**Shard balance is not observable from metrics today.** Neither the configured
shard count nor per-shard state size is exported, so a change cannot be
confirmed from the dashboards. Until that is instrumented, verify by comparing
the `rule_evaluation` distribution before and after: the p95 should fall
roughly in proportion to the shard count. If it does not, the routing values
are concentrated and the split is not helping. The authoritative view of the
distribution is the `(input topic, partition, state shard)` rows in
`t_detection_state_owner` and the per-shard snapshot rows.

`GET /detect-web/api/v1/stats` answers from the replica that served the request
and labels it (`instance`, `stateRecovery.scope=replica-local`). Its counters
have these scopes, and none of them is exported to Prometheus:

- `eventCount`, `alertCount`, `dropCount`, `suppressedCount`, `queueLoad` and
  `ruleStats` are summed over **this tenant's** materialized shard engines on
  this replica. `suppressedCount` in particular is counted by each engine for
  the suppression decisions it made itself, so it is safe to sum across shards
  and no longer reports the process-wide `Suppressor` total to one tenant. The
  deduplication window behind it is still process memory: it is not shared with
  other replicas and it resets on restart.
- `isolatedRules` is the number of this tenant's rule documents the engine
  could not build and therefore skipped on this replica.
- `routingMismatchWindows` counts the low-frequency "state is not
  partition-local" diagnostics described below, at most one per rule per rule
  window.
- `stateRecovery.engineScopes` and `stateRecovery.pendingRebuilds` name the
  individual engine keys that are recovering or awaiting the retry schedule, so
  one tenant's hot reload is not read as a process-wide outage.

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

The current production contract supports partition-local state only. What
persistence can actually enforce is narrower than that, and the difference is
observable rather than hidden:

- Validation and storage check the rule **document** against itself: `groupBy`,
  the compatibility alias `keyField`, and `routingField` must name the same
  dimension, and the document must compile into an executable rule. Both are
  HTTP 400 rejections at write time.
- Validation cannot see events, so it does not - and cannot - compare the
  declared grouping dimension with the dimension a future event resolves to.
  `DetectionRoutingKey.isPartitionLocal` is evaluated per event on the engine
  path, not per document at write time.
- A stateful rule whose grouping dimension can lose to a higher-priority
  routing dimension is therefore accepted, and it runs with **partial state**:
  its window only covers the events of the partitions whose key resolved to its
  grouping field. Packaged content contains such rules today (for example
  `CORR-FAIL-SUDO`, `BASELINE-AUTH-VOLUME`, `UEBA-NEW-GEO`, `UEBA-USER-VOLUME`
  group by `user`, while the default policy picks `src_ip` first for
  non-endpoint sources and `host` for endpoint sources).
- Writing such a rule logs one partition-locality advisory per affected data
  source (`RuleSpecStore.save`), and the engine reports a mismatch for an actual
  event at most once per rule window (`WARN`, `ruleStats[].routingMismatchWindows`,
  `stats().routingMismatchWindows`, `RuleProcessingObserver.routingMismatched`).

Rejecting this shape outright would reject shipped content that legitimately
correlates across sources, so the gate is advisory plus measured instead. That
also means the split is between Kafka partitions and consumer processes, not
between in-process shards: with the default `socp.detect.state.shards=1` a
single-process deployment cannot observe the difference at all, which is why the
existing checks pass.

Cross-entity grouping requires an explicit repartition or fan-out design,
including its state ownership, late-event behavior, recovery, and cost limits.
Until that design is implemented, clients must model the rule on the event's
routing dimension or keep it stateless.

## Rule hot reload and undeployable rules

A rule document that passes contract validation is compiled once more before it
is stored (`RuleSpecStore.save` builds the rule with the exact code the engine
uses and releases it), so a document the engine could not construct is an HTTP
400 at write time instead of a runtime outage. Engine assembly is still
per-document defensive: documents are filtered on their lifecycle status first,
and every remaining document is compiled inside its own guard. A rule that
cannot be built is skipped, logged, and counted in `stats().isolatedRules`; one
undeployable rule no longer stops the tenant's detection or pushes its event
stream to the dead-letter topic.

A reload rebuilds only the editing tenant's engine keys. It marks exactly those
keys `RECOVERING`, drains the live engines without closing them
(`socp.detect.reload.drain-timeout-ms`, default 30s) so the replacement reads the
journal after every accepted completion, and only then swaps. If any step fails,
nothing is published: the previously live engines keep serving, the failed keys
are reported in `stateRecovery.engineScopes` / `pendingRebuilds`, and the
recovery schedule retries them (`socp.detect.recovery.retry-interval-ms`, default
30s). Process-wide `RECOVERING`/`DEGRADED` remains reserved for startup and a
full Kafka assignment rebuild, which really does replace every engine. A full
rebuild that finds no journaled history warms an empty engine and says so
(`stateRecovery.warmedWithoutHistory`) instead of looking like a
checkpoint-restored state.

A durable-path rollback discards the candidate alerts each rule had accumulated
for the failed event as well as its serialized state, so a rolled back event
cannot deliver its alerts against the next event's result. Storm collapsing in
the secondary-analysis path is a replica-local heuristic; it is counted
(`socp.detect.storm.suppressed`), recorded on the durable receipt as
`SUPPRESSED`, and reports the replica that decided it.

Cross-instance assignment barriers and a three-instance failover proof are
still required before making a production HA claim; the local Compose
deployment remains single-node.
