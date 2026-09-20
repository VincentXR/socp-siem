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
| Kafka ingest per topic | partitions x consumer instances (default 6 x 3) | `infra/init-sql/kafka/create-topics.sh` (`PARTITIONS`, `DETECTION_PARTITIONS`) | `max(socp_kafka_consumer_lag)` — sustained growth means arrivals exceed the drain rate |
| Detection engine queue | fixed `ArrayBlockingQueue(100_000)` per engine | `platform/socp-rule/src/main/java/com/socp/rule/engine/RuleEngine.java` | effective replicas <= partitions; queue saturation surfaces as lag, not a dropped event (bounded, fail-closed) |
| Search hot cache | estimated-byte budgets per tenant and process, plus tenant/event-count caps | `services/search-config/src/main/java/com/socp/search/config/persistence/store/SearchStore.java` | `SOCP_SEARCH_CACHE_MAX_BYTES_PER_TENANT`, `SOCP_SEARCH_CACHE_MAX_BYTES_TOTAL`, and warm-up bounds; estimates are admission weights, not exact retained heap |
| Outbox drain batch | bounded per-tick batch, `SKIP LOCKED` | ADR 005; per-service outbox publishers | `socp_*_outbox_oldest_pending_age_seconds` — pending age tracks whether drain rate keeps up with commits |
| OpenSearch indexing | daily single-shard template, batch write | `OpenSearchIndexTemplate.java` (shards=1, replicas=0 currently hard-coded) | `socp_opensearch_indexer_records_total{stage=...}` accounting invariant `consume = write + drop + failed` |
| Retention delete | bounded rounds, hourly | search retention worker | `socp_search_event_retention_lag_seconds`, `socp.search.event.retention.delete.rate.rows_per_second` |

The partition count is the parallelism ceiling for its topic and **cannot be shrunk
later** (see [Kafka provisioning](kafka-topic-provisioning.md)); sizing detection
replicas is therefore a function of `DETECTION_PARTITIONS`, not an independent dial.

## Deriving observed capacity from live metrics

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
