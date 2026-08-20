# Pipeline benchmark

`build/benchmark-pipeline.py` measures either the Detection HTTP boundary or
the real `search-config -> Kafka -> detect-web -> alert-web` path. End-to-end
runs have two explicit workload profiles: `realistic` keeps the detection hit
rate low, while `alert-heavy` stresses durable alert materialization and
fan-out. It records JSON only when `--output` is supplied. Generated reports
belong outside source control unless they are intentionally published as a
reproducible fixture.

## Reproducible runs

Start middleware and the core services, use a clean test tenant, and keep the
same JVM/middleware configuration for each scale point:

```bash
python build/benchmark-pipeline.py --mode e2e --profile realistic --count 10000 --batch-size 500 \
  --alert-every 1000 --instances 3 --rules 39 --label realistic-10k \
  --output .cache/benchmark/e2e-10k.json
python build/benchmark-pipeline.py --mode e2e --profile alert-heavy --count 1000 --batch-size 100 \
  --instances 3 --rules 39 --label alert-heavy-1k \
  --output .cache/benchmark/e2e-alert-heavy-1k.json
```

For a multi-instance run, start all Detection processes with the same
`SOCP_KAFKA_GROUP_ID`, shared PostgreSQL, and a topic with enough partitions.
Set `--instances` to the number of live processes and retain the corresponding
partition assignment output from the chaos check.

Set `BENCH_PROMETHEUS_URL` to an actuator Prometheus endpoint to capture JVM
heap, GC, and process CPU samples. Set `BENCH_OS_URL` and `BENCH_CK_URL` for
optional OpenSearch/ClickHouse before-and-after counters. Install
`kafka-python` if Kafka offset/lag snapshots are required. Offset snapshots use
the Kafka Admin API and a group-less metadata consumer; they never join the
live Detection group or trigger a rebalance.

The `realistic` generator namespaces its synthetic `src_ip` entity with the
run id. This keeps the expected-alert oracle independent across repeated runs
and prevents the real five-minute suppressor or Alert Web idempotency key from
turning a valid alert into a false shortfall. These values are deliberately
synthetic identifiers, not routable IP fixtures.

## Report contract

Every report should contain or be accompanied by:

- Git commit, OS, CPU count, memory, JVM options, middleware versions;
- event count, batch size, rule count, Detection instance count, topic
  partitions, consumer group, and test tenant;
- workload profile and expected/observed alert count;
- accepted/rejected counts and ingress events per second;
- batch request P50/P95/P99/max latency;
- aggregate alert-drain wait and end-to-end throughput for `--mode e2e`;
- Detection drain wait and Kafka contiguous-commit drain wait;
- Detection processing latency from the Detection alert's
  `processingLatencyMs = detection alertCreatedAt - triggerIngestedAt`.
- Durable alert latency from `Alert Web createdAt - triggerIngestedAt`, which
  includes the Detection Outbox hand-off. Each distribution includes a sample
  count; zero means the running stack did not preserve the ingest timestamp.
- Kafka end offset, committed offset, lag before/after;
- Detection event/alert/drop/suppression statistics;
- JVM heap/GC/CPU samples and optional storage counters.

The number is a local, repeatable baseline. It is not a production throughput,
capacity, or availability claim. Do not commit hostnames, absolute paths,
credentials, tokens, or machine-specific screenshots.

## Interpretation

Compare runs only when these inputs are fixed:

- JVM options and service instance count;
- Kafka partition count and consumer group;
- enabled rule count and rule families;
- input event shape and entity distribution;
- middleware versions and database state.

Always report the end-to-end path separately from the bulk Detection HTTP
boundary. A bulk number alone is not an SIEM pipeline throughput result.

## Sanitized reference result

The 2026-08-20 reference run used one Windows development host, eight logical
CPUs, six Kafka partitions, three 256 MiB Detection JVMs, PostgreSQL-backed
Detection state, and 39 active rules. These are measured local results, not a
capacity claim:

| Profile | Events | Expected/observed alerts | Ingress EPS | Completed-path EPS | Detection P95 | Durable Alert P95 | Final Kafka lag |
|---|---:|---:|---:|---:|---:|---:|---:|
| realistic (`alertEvery=1000`) | 10,000 | 10 / 10 | 811.10 | 138.32 | 60.10 s | 60.22 s | 0 |
| alert-heavy | 1,000 | 1,000 / 1,000 | 409.51 | 17.76 | 20.59 s | 52.64 s | 0 |

Both runs had zero rejected events and zero alert shortfall. The large gap
between ingress and completed-path throughput is retained deliberately: it is
the current single-node durable-processing baseline and identifies the journal
and alert materialization path as the next performance boundary. OpenSearch
and ClickHouse counters are observational only because those independent
consumer groups were not used as benchmark completion barriers.
