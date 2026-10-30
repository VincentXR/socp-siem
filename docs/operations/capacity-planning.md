# Capacity planning reference

The repository does not publish a universal EPS or tenant-capacity figure.
Capacity depends on the target cluster and workload. This reference defines the
bounded ceilings enforced by code and the metric queries used to derive a
deployment-specific capacity statement.

Two rules govern any number you publish from this table:

1. **Capacity is deployment-owned evidence.** Do not promote a figure here to an
   external claim without a measurement on the target cluster. Per
   [production-readiness.md](../production-readiness.md) and ADR 007, the
   repository does not certify EPS,
   HA, or RTO — this table tells you *what to measure and where the ceilings are*.
2. **Every ceiling below is a code/config constant, not an opinion.** The "governing
   signal" column names the metric or knob that binds it.

## Per-stage ceilings (read from configuration, not guessed)

| Stage | Bound | Where it is set | Governing signal to watch |
| --- | --- | --- | --- |
| Kafka ownership per topic/group | active consumers <= min(partitions, group members); default 6 partitions, with 3 workers in the multi-instance rehearsal | `infra/init-sql/kafka/create-topics.sh` (`PARTITIONS`, `DETECTION_PARTITIONS`) | topic/group lag and assigned partitions; adding consumers beyond the partition count does not add owners |
| Detection engine queue | 100,000 queued events per engine, weighted by event count for batches | `platform/socp-rule/src/main/java/com/socp/rule/engine/RuleEngine.java` | effective replicas <= partitions; queue saturation surfaces as lag, not a dropped event (bounded, fail-closed) |
| Search hot cache | estimated-byte budgets per tenant and process, plus tenant/event-count caps | `services/search-config/src/main/java/com/socp/search/config/persistence/store/SearchStore.java` | `SOCP_SEARCH_CACHE_MAX_BYTES_PER_TENANT`, `SOCP_SEARCH_CACHE_MAX_BYTES_TOTAL`, and warm-up bounds; estimates are admission weights, not exact retained heap |
| Search parse configuration | 512 effective rules/tenant, 32 global rules, 64 KiB serialized rule, 100 rule IDs/source; 2,048 compiled source/binding cache entries/process | `ParseRuleStore`, `LogSourceRequest`, `ParsePipelineResolver` | Measure rule-read/compile latency and heap on the actual rule mix. Cache count is bounded but compiled-rule byte weight and historical over-limit catalogues are not covered by these limits |
| Search output targets | 128 tenant-owned targets/tenant; request URI 2,048 characters and token 4,096 characters | `SinkTargetStore`, `SinkTargetRequest` | New writes are capped; historical over-limit catalogues can still make full-list reads expensive |
| Detection watchlists | 500 owned names per tenant; 10,000 members/list; 256 characters/member. Rule cache defaults to 10,000 entries and 64 MiB estimated weight, with 1s refresh; oversized entries bypass cache | `WatchlistLimits`; `socp.detect.watchlists.cache-max-entries`, `cache-max-weight`, `refresh-ms` | Measure cache churn and DB read latency with the target list distribution; limits do not establish a supported EPS. Mutation quotas use database namespace locks across replicas |
| Outbox drain batch | Ingestion claims at most its available worker capacity (1–32), one head per tenant/routing key; Alert event/delivery acquire capacity before claim (1–32). One local drain runs per publisher; rule-change delivery is sequential. These four contexts recover at most 100 stale rows per 30-second pass and exhaust at most 100 pending rows per scan; admitted I/O can outlast the admission deadline | [ADR 005](../adr/005-outbox-lifecycle.md); per-service outbox publishers | `socp_*_outbox_oldest_pending_age_seconds` — pending age tracks whether drain rate keeps up with commits; a large stale backlog requires multiple recovery passes |
| OpenSearch indexing | daily template with deployment-owned shard/replica counts, batch write | `socp.opensearch.index-shards` (1–1,024, default 1), `index-replicas` (0–32; development default 0, production default/minimum 1) | Provision enough data nodes for every replica and verify actual allocation; configuration alone is not HA evidence. `socp_opensearch_indexer_records_total{stage=...}` counts attempts, so retries/tails prevent treating equality as a unique-event oracle |
| Retention delete | bounded rounds, hourly | search retention worker | `socp_search_event_retention_lag_seconds`, `socp.search.event.retention.delete.rate.rows_per_second` |
| HIPS history retention | default 1,000 rows per transaction, at most 10 batches per pass; 10-second budget for starting batches | `socp.hips.history-retention.*`; [history retention](endpoint-forwarding.md#history-retention) | `socp.hips.history.retention.lag.seconds`, last-success time, duration and last-deleted gauges; every receipt reference protects history, and no fixed disk-size guarantee follows from the batch budget |

The partition count is the parallelism ceiling for its topic and **cannot be shrunk
later** (see [Kafka provisioning](kafka-topic-provisioning.md)); sizing detection
replicas is therefore a function of `DETECTION_PARTITIONS`, not an independent dial.

## Deriving observed capacity from live metrics

On a disposable loopback stack with the existing collector/metrics credentials,
the bounded short-run entry point is:

```bash
python3 build/verify-load-soak.py --allow-local-fixture \
  --mode capacity --seconds 120 --stages 250,500,1000,2000 \
  --warmup-eps 50 --warmup-seconds 120 \
  --require-state-snapshot --report-dir .cache/load-capacity
```

`capacity` is separate from the original 1–100 EPS pressure profile. It allows
1–5 increasing stages, 1–3,000 canonical EPS, 60–120 seconds per stage, and at
most 500,000 unique events including warmup. Requests carry at most 200 events,
with four sending workers, eight outstanding requests, and duplicate replay.
Threshold entities use reserved, non-overlapping blocks in 198.18.0.0/15.
Keep the report directory across repeated runs to reserve earlier blocks.
The declared backlog guard is `max(1000, targetEPS * 5)` records; the end-of-stage
drain limit remains 15 seconds. A failed stage stops escalation. Exact identity
reconciliation, error/health/resource checks and fresh persisted checkpoints
remain required; queued ingestion alone does not establish detection capacity.

The Full Stack E2E manual input `run_load_capacity` runs the `canonical-capacity`
job after SQL integration, startup, API, browser and pipeline checks. It uses one
canonical Detection process with four state shards (`SOCP_DETECT_STATE_SHARDS=4`).
The regular full-stack job uses the same shard count for recovery regression.
Chaos/rebalance/migration checks remain in the separate
required PR-triggered Full Stack run on the same commit; the capacity dispatch
skips those scenarios and is not a substitute for that run. It gives Search a 512 MiB G1 heap and Detection a 768 MiB G1 heap, each with eight
database connections. The rest of the single-runner stack stays unchanged. Record
the actual machine/commit/profile from its artifacts. This run measures neither
routed multi-replica throughput nor long-duration stability or production capacity.

Built-in stateful rules use entity copy-on-write undo boundaries on the event
path. Full snapshots retain their serialized recovery format; parallel layouts
include the shard count in their compatibility version. Audit digests prefixed
`entity-delta-v1:sha256:` cover sorted touched-key
projections, including TTL/capacity removals; historical unprefixed digests cover
full snapshots. Compare only like schemes. Custom rules retain the full-snapshot
fallback until they implement `beginMutation()` with equivalent rollback semantics.

Detection splits at most 128 Kafka records from one partition into ordered
tenant/state-shard groups. Groups run on their independent engines concurrently;
events within one group remain sequential. Journal claims use one bounded lookup and
JDBC insert batch; rule results, risk changes, alert outbox entries and completion
receipts commit together. Each event keeps its trace context and audit envelope.
A batch takes one full rule-state undo snapshot; failed persistence restores
that boundary and discards staged suppression decisions. Checkpoints capture
the complete batch state and its final offset. Completion timestamps retain
evaluation order at PostgreSQL microsecond precision for journal replay.
Timeouts wait on the original completion future; ownership loss prevents offset
advancement. Every tenant's journal claim finishes before any group is submitted.
After dispatch, all submitted futures must settle before either acknowledging the
partition prefix or falling back to record retry. A failed group does not undo a
committed sibling; its terminal receipts make retry idempotent. Unsupported input
uses the existing record retry path.
Adjacent queued Kafka batches under the same partition assignment coalesce up
to the same 128-event limit, without adding a delay to quiet partitions or crossing
pending-journal replay tasks. Their byte reservations remain held until the
combined work completes. `socp_detection_batch_records_count` and `_sum` expose
the completed partition-batch count and total events. The
`socp_detection_batch_state_units_count`, `_sum` and `_max` series describe the
planned state-unit fan-out; they do not independently prove concurrent execution.

Deployments enable independent state engines with `SOCP_DETECT_STATE_SHARDS`
(1–256, compatibility default 1). Use the same count on all Detection replicas;
drain and restart the cluster when changing it. All layout checkpoints have
a bounded fingerprint containing the routing hash version, shard count and rule
semantics. A different layout triggers full retained-journal replay instead of
restoring incompatible state and skipping its history. Legacy checkpoints without
a layout fingerprint also rebuild from the journal, even with one shard, because
their original shard count is unknown. The retained window must
cover the rules being recovered. Sharding does not make canonical cross-dimension
rules complete: use the routed input contract for that guarantee. Do not infer a
capacity increase from the configured shard count; repeat the exact workload and
inspect durable completions, lag, fan-out and identity reconciliation.

The capacity workflow warms the same complete chain with 6,000 events at 50 EPS
for 120 seconds before the measured stages. Warmup must meet its declared rate,
finish draining and pass exact identity reconciliation; failures still stop the
run. The measured rate, lag and drain limits are unchanged. CLI defaults remain
10 EPS for 20 seconds for a cold-start comparison. Record the declared warmup
with every result: warmed steady-state capacity does not certify cold-start
capacity. Warmup counts towards the total event bound. `resources.jsonl` also
records bounded per-process CPU counters and start ticks, without command lines
or environments, to distinguish application CPU from middleware contention.
The load driver reuses HTTP handlers per thread and TLS trust mode so certificate
store initialization does not compete with the measured services on every request.
Loopback redirect restrictions and the separate verified TLS context remain active.

Manual capacity runs retain bounded JFR CPU, socket and lock-wait samples for
Search, Detection and Alert in `.cache/capacity-profile`. The recording starts
from an empty event set and excludes environment and system-property events.

Ingestion publication claims and settles up to 32 independent routing-key heads
in two short database transactions, with acknowledged Kafka delivery between
them. A processing or delayed head blocks its successors. Claim tokens fence
late acknowledgements; interrupted claims use stale-claim recovery. The default
concurrency is 32, with at most 256 rounds and a 2-second budget for admitting
new rounds (`SOCP_INGEST_OUTBOX_CONCURRENCY`,
`SOCP_INGEST_OUTBOX_MAX_DRAIN_ROUNDS`,
`SOCP_INGEST_OUTBOX_MAX_DRAIN_DURATION_MS`). No transaction holds database
connections while waiting for the broker. The legacy fair-page SQL computes its
window ranking once, without joining the ranked result back to the outbox table.
Batch selection aggregates the earliest PENDING sequence per tenant/routing key
before checking due time and attempts, then excludes keys with PROCESSING rows.
It avoids comparing each queued row against every earlier row of the same key
when backlog grows. `FOR UPDATE SKIP LOCKED` locks only eligible heads; a locked,
delayed or exhausted head never exposes its successor to another publisher.
These changes introduce no API, database schema or Kafka topic migration.

Search ingestion batches JDBC inserts in groups of 64. New outbox entities use
`persist` despite their assigned UUIDs; loaded entities retain update semantics.
Insert reordering stays disabled because the database enqueue sequence orders
same-key publication. The enclosing transaction still covers canonical events
and publication intents, and a failed JDBC batch rolls the whole transaction
back. Detection's repeated completion observation leaves an existing terminal
receipt and its completion timestamp unchanged; ownership fencing still runs.

Run these against Prometheus during the load window you want to characterise; they
are the same series the [alert thresholds](../observability-stage-metrics.md) use, so
capacity headroom and paging share one source of truth.

```promql
# 1. Ingest throughput (events/s) per indexer stage
rate(socp_opensearch_indexer_records_total{stage="write"}[5m])

# 2. Detection completed throughput and its terminal split
sum by (outcome) (rate(socp_detection_event_completed_total[5m]))

# 3. End-to-end latency budget by stage (Timer -> seconds, p95)
histogram_quantile(0.95, sum by (le, stage) (
  rate(socp_detection_event_stage_seconds_bucket[5m])))

# 4. Headroom proxy: how far the lag is from its paging threshold
socp_kafka_consumer_lag / 10000

# 5. Steady-state error/poison rate that must stay flat for the window to be "capacity"
#    and not "incident":
increase(socp_detection_dlq_handoff_total{outcome="committed"}[15m])
delta(socp_ingestion_outbox_dead_count[15m])
```

A window is a valid capacity observation only when (5) is ~zero: throughput measured
while poison records are being shed or outbox rows are going DEAD is measuring a
degraded path, not capacity.

## Workload profile to record per measurement

A capacity statement is meaningless without the axes it was measured under. Record
alongside every number:

- **tenants** active, **rules** active, and **state cardinality** (distinct rule-state
  keys), because detection cost scales with rules x state keys x window events;
- **event rate** and **payload size distribution** (OpenSearch field-cardinality risk —
  see the mapping-guard item);
- **instances per stage** and the **partition count** they map onto;
- **PostgreSQL** connection-pool ceiling (`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT`
  is pinned in the chart; the pool `maximum-pool-size` is the concurrency ceiling on the
  event path).

## Consolidation candidates

`build/runtime-topology.json` marks `alert-incident` and `soar-notify` as
`evaluate` consolidation candidates. ADR 007 requires `context`, `transaction`,
`failure`, **and** `capacity` evidence before any candidate is promoted. The `capacity`
check must cite a produced artifact (this table's derivation run) rather than a boolean;
until it does, the candidate stays `evaluate`. There is no repository-wide aggregate
capacity promotion — `verify-runtime-consolidation.py --candidate NAME --require-evidence`
is the gate, and it is the per-candidate path, not a global claim.

## What is intentionally absent

No EPS headline, no "supports N tenants", no RTO/RPO number. Those require a load
generator and a target cluster the repository does not own. If you need a figure for a
procurement or an SLO, execute the queries above on your cluster and record them as
deployment evidence; this page exists so that recording is possible and bounded, not to
pre-fill it.
