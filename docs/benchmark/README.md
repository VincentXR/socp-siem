# Pipeline benchmark

`build/benchmark-pipeline.py` measures either the Detection HTTP boundary or
the real `search-config -> Kafka -> detect-web -> alert-web` path. End-to-end
runs have two workload profiles: `realistic` keeps detection hits low, while
`alert-heavy` stresses durable alert materialization. Generated raw reports
belong under `.cache`; only sanitized evidence is published here.

The indexer exposes the record reconciliation counter
`socp_opensearch_indexer_records_total` with `stage` values `consume`, `write`,
`fail`, `drop`, `dlq`, `dlq_failed`, `commit`, and `commit_failed`. The end-to-end report
copies these deltas into `performanceMetrics.openSearchIndexer`. `write` and
`commit` are counted only after the corresponding durable acknowledgement;
`drop` is a malformed input and `dlq` means the DLQ broker write was
acknowledged; `dlq_failed` means the input remains uncommitted.

`consume`, `write`, `fail`, `drop`, `dlq`, `dlq_failed`, and `commit_failed`
are attempt counters: retries can make them exceed the run's unique input, so
the report never derives a loss gap by subtracting them. The
`uniqueSourceOffsets` section instead compares accepted events with the Kafka
consumer group's committed-offset delta over the isolated run. The final data
invariant is `accepted unique source offsets = indexed unique documents +
broker-acknowledged DLQ source offsets`; the failure-evidence run measures the
two right-hand terms by source identity (`topic`, `partition`, `offset`).

## Reproducible runs

Start middleware and the core services and keep the JVM/middleware
configuration fixed. A fresh database installs the 39 rules from the versioned
`socp-core-detections` content pack. The pack is the sole executable source of
packaged rules; older benchmark reports that used 39 rules are historical and
must not be compared as if the workload were unchanged:

```bash
python build/benchmark-pipeline.py --mode e2e --profile realistic --count 10000 --batch-size 500 \
  --alert-every 1000 --instances 3 --rules 39 --label realistic-10k \
  --output .cache/benchmark/e2e-10k.json
python build/benchmark-pipeline.py --mode e2e --profile alert-heavy --count 1000 --batch-size 100 \
  --instances 3 --rules 39 --label alert-heavy-1k \
  --output .cache/benchmark/e2e-alert-heavy-1k.json
```

For multi-instance measurement, set `BENCH_DETECTION_URLS` to comma-separated
Detection base URLs and `BENCH_ALERT_URL` to the Alert Web base URL. All
Detection processes must share the Kafka group and PostgreSQL state store.
Set `BENCH_PROMETHEUS_URL` for an optional external Prometheus snapshot and
`BENCH_OS_URL` / `BENCH_CK_URL` for storage counters. Install `kafka-python`
for offset/lag snapshots; its diagnostic client never joins the live consumer
group.

The generator namespaces synthetic entities and event ids with the run id.
Final alert correctness and latency are also filtered by the current
`triggerEventId` prefix. A delayed alert from an older stateful evaluation is
reported as `nonRunAlertsObserved`, not mixed into this run's percentiles.

## Stage clock contract

| Clock | Meaning |
|---|---|
| T0 | Trigger event accepted (`triggerIngestedAt`) |
| T1 | Kafka record received by Detection |
| T2 | Journal `PENDING` transaction returned |
| T3 | Rule evaluation completed |
| T4 | Alert Outbox rows plus Journal `COMPLETED` committed |
| T5 | Detection Alert Outbox row claimed |
| T6 | Alert Web request received |
| T7 | Alert Web transaction committed |
| T8 | Detection received the HTTP acknowledgement |

Every event contributes T0-T4 metrics. Only alert-producing events contribute
T5-T8. The primary alert metric is `T7 - T0`, named Durable Alert latency.
The report exposes `kafka_queue`, `journal`, `rule_evaluation`,
`durable_completion`, `outbox_queue`, `transport_request`,
`alert_persistence`, and response/round-trip histograms.

Transaction ratios use explicit denominators:

- Detection event transactions / unique accepted event;
- Detection Outbox state transactions / durable alert;
- Alert Web create transactions / durable alert.

Every report also records the commit, host shape, event/rule/instance counts,
accepted/rejected events, run-scoped alerts, Kafka lag, drain time, and
P50/P95/P99 distributions. These local numbers are not production capacity or
availability claims.

## Steady-state sanity check

Burst/drain throughput and sustainable load are different measurements. Run
bounded offered-load checks after the fixed-size profiles:

```bash
python build/benchmark-pipeline.py --mode e2e --profile realistic --offered-eps 100 \
  --duration 120 --batch-size 100 --instances 3 --rules 39 \
  --output .cache/benchmark/steady-100.json
```

Repeat at 150 and 200 EPS. `steadyState.lagStable` verifies that lag does not
grow continuously during the interval; final lag must still drain to zero.

## Repeated 50k baseline

Use the series runner to separate a warm-up result from a stable single-
instance baseline. It keeps one JSON report plus stdout/stderr and per-round
reports; a failed round also retains the benchmark's `.failed.json` sidecar.

```bash
python build/benchmark-series.py --rounds 3 --count 50000 --batch-size 500 \
  --mode e2e --profile realistic --instances 1 --rules 39 \
  --output .cache/benchmark/realistic-50k-1x-series.json
```

For the isolated ingestion baseline requested by the capacity plan, keep the
same series shape but use `--mode bulk`; this measures the Detection HTTP
boundary without alert materialization:

```bash
python build/benchmark-series.py --rounds 3 --count 50000 --batch-size 500 \
  --mode bulk --profile realistic --instances 1 --rules 39 \
  --output .cache/benchmark/bulk-50k-1x-series.json
```

`stableBaseline.throughputStable` is true only when every round passes and the
slowest successful round stays within the configured `--tolerance` (15% by
default) of the first successful round. Repeat with `--rounds 5` for a release
candidate, and use `--profile alert-heavy` for the alert-heavy curve. Set
`BENCH_TOPIC` when the Kafka topic is not `socp-events`; the lag probe now uses
the same topic as the application.

The same runner can be used with `--instances 1`, `2`, and `3` after starting
the corresponding Detection topology (`SOCP_DETECT_CLUSTER_PORTS` controls
the fixed launcher). It records scale-out reports but does not claim HA until
the multi-instance chaos oracle also passes. The SQL files
`build/ingestion-outbox-plan.sql` and `build/detection-journal-audit.sql` are
read-only operator checks for PostgreSQL query plans and historical journal
state; neither is run automatically by the benchmark.

## Reference benchmark results

The 2026-08-21 run used one Windows development host, eight logical CPUs, six
Kafka partitions, three 256 MiB Detection JVMs, PostgreSQL-backed state, and 39
active rules. It remains a historical comparison only: current packaged
content is 39 rules and must be benchmarked with a new run before capacity
numbers are reused. The controlled change removed a redundant post-completion
transaction and bounded Alert Outbox delivery at two requests per Detection
instance (six total).

| Profile | Metric | Before | After | Change |
|---|---|---:|---:|---:|
| realistic 10K | Completed-path EPS | 193.06 | 278.70 | +44.4% |
| realistic 10K | Durable Alert P95 | 51.22 s | 27.54 s | -46.2% |
| realistic 10K | Kafka queue P95 | 50.06 s | 31.11 s | -37.9% |
| alert-heavy 1K | Completed-path EPS | 25.07 | 32.94 | +31.4% |
| alert-heavy 1K | Durable Alert P95 | 38.77 s | 29.29 s | -24.5% |
| alert-heavy 1K | Outbox queue P95 | 21.86 s | 14.28 s | -34.7% |
| both | Detection DB transactions/event | 3.0 | 2.0 | -33.3% |

Final run-scoped reports had zero rejected events, zero alert shortfall, and
final Kafka lag zero. A trial at eight deliveries per instance reduced Outbox
queue time but regressed overall throughput because all services shared one
PostgreSQL process; it was rejected. JFR found no dominant CPU method and only
43.4 ms / 44.6 ms total GC pause in the sampled Detection / Alert Web JVMs.
PostgreSQL did not load `pg_stat_statements`, so stage histograms, transaction
counters, and `pg_stat_database` were used without changing its startup
contract.

| Offered load | Duration | Actual ingress | Peak lag | Lag growth | Stable |
|---:|---:|---:|---:|---:|---:|
| 100 EPS | 120 s | 100.62 EPS | 113 | 0 | yes |
| 150 EPS | 120 s | 150.81 EPS | 642 | 5 | yes |
| 200 EPS | 120 s | 200.47 EPS | 765 | 0 | yes |
