# Pipeline benchmark

`build/benchmark-pipeline.py` measures the running stack at the HTTP boundary
or through the real `search-config -> Kafka -> detect-web -> alert-web` path.
It writes JSON only when `--output` is provided; generated results should stay
outside source control unless they are intentionally published as a reproducible
fixture.

## Reproducible runs

Start middleware and the core services, then run the same command for each
scale point:

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

Each report includes the Git commit, machine profile, request latency
percentiles, end-to-end wait, Kafka end/committed offsets and lag, optional
OpenSearch/ClickHouse counters, and Detection stats before and after the run.
Set `BENCH_PROMETHEUS_URL` to a Detection `/actuator/prometheus` endpoint when
JVM heap/GC and process CPU samples should be captured in the same report.
The number is a local baseline; it is not a capacity claim for a production
deployment.

For comparison, keep the following fixed between runs:

- JVM options and service instance count;
- Kafka topic partition count and consumer group;
- enabled rule count and rule types;
- input batch size and event distribution;
- middleware versions and database state.
