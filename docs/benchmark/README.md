# Pipeline benchmark

`build/benchmark-pipeline.py` measures either the Detection HTTP boundary or
the real `search-config -> Kafka -> detect-web -> alert-web` path. It records
JSON only when `--output` is supplied. Generated reports belong outside source
control unless they are intentionally published as a reproducible fixture.

## Reproducible runs

Start middleware and the core services, use a clean test tenant, and keep the
same JVM/middleware configuration for each scale point:

```bash
python build/benchmark-pipeline.py --mode e2e --count 10000 --batch-size 200 \
  --instances 1 --rules 21 --label e2e-10k \
  --output .cache/benchmark/e2e-10k.json
python build/benchmark-pipeline.py --mode e2e --count 100000 --batch-size 500 \
  --instances 1 --rules 21 --label e2e-100k \
  --output .cache/benchmark/e2e-100k.json
python build/benchmark-pipeline.py --mode e2e --count 1000000 --batch-size 1000 \
  --instances 1 --rules 21 --label e2e-1m \
  --output .cache/benchmark/e2e-1m.json
```

For a multi-instance run, start all Detection processes with the same
`SOCP_KAFKA_GROUP_ID`, shared PostgreSQL, and a topic with enough partitions.
Set `--instances` to the number of live processes and retain the corresponding
partition assignment output from the chaos check.

Set `BENCH_PROMETHEUS_URL` to an actuator Prometheus endpoint to capture JVM
heap, GC, and process CPU samples. Set `BENCH_OS_URL` and `BENCH_CK_URL` for
optional OpenSearch/ClickHouse before-and-after counters. Install
`kafka-python` if Kafka offset/lag snapshots are required.

## Report contract

Every report should contain or be accompanied by:

- Git commit, OS, CPU count, memory, JVM options, middleware versions;
- event count, batch size, rule count, Detection instance count, topic
  partitions, consumer group, and test tenant;
- accepted/rejected counts and ingress events per second;
- batch request P50/P95/P99/max latency;
- aggregate alert-drain wait and end-to-end throughput for `--mode e2e`;
- per-event latency percentiles only when an external probe records event-level
  timestamps (the built-in script does not infer them from a total alert count);
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
