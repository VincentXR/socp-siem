# Development memory verification

The development launcher retains `-Xms32m -Xmx256m` for Java services and uses
64 MiB total / 8 MiB per tenant search-cache admission weights with one concurrent
warm-up. Environment overrides take precedence. These are trial settings, not
capacity guarantees or measurements of retained object memory. Production cache
defaults are unchanged. OpenSearch uses `OPENSEARCH_JAVA_OPTS` with a 512 MiB
heap and a 1024 MiB container limit.

Kafka keeps its 512 MiB broker heap inside a 1280 MiB container budget for
native allocations and the separate health-probe process. The topic-list probe
overrides the inherited broker heap with `-Xms16m -Xmx64m`; otherwise a probe
can add a second broker-sized JVM to the same container's memory charge.

Compose explicitly sets `KAFKA_LOG_DIRS=/var/lib/kafka/data`, matching
the `kafka-data` volume. Before recreating an existing broker, inspect its
effective `log.dirs`. If it used the container-local `/tmp/kafka-logs`, stop
that broker and copy its complete log directory, including KRaft metadata,
into the retained volume first, preserving ownership for the image's appuser
(UID 1000). Preserve the old container until cluster ID,
topic offsets and sample records have been checked after recovery. Changing
the environment alone does not migrate existing records.

## Preserve the existing environment

Before starting services, inspect `docker context inspect`, `docker ps -a`,
`docker volume ls`, `bash build/compose.sh config --services`, and
`bash build/run-all.sh doctor full`. Confirm service URL, database, Kafka,
OpenSearch and Temporal overrides point to the intended development environment.
Inspect enabled notification channels and SOAR automation before importing
events. Do not delete volumes, reset databases or stop unrelated processes.
Keep raw configuration and credentials out of tracked evidence.

Record the Git revision, operating system, physical RAM, Docker VM memory/CPU,
JDK, active profiles, JVM overrides, fixture identifiers and measurement times.
Build artifacts before collecting the service-startup baseline so compiler and
image-build memory can be reported separately.

## Start the required services

Supply runtime and migration credentials matching the existing PostgreSQL
instance as described in [Getting started](../getting-started.md).

```bash
bash build/compose.sh up -d
SOCP_START_BATCH_SIZE=1 bash build/run-all.sh start core
# Select full instead of core to exercise all business services.
SOCP_START_BATCH_SIZE=1 bash build/run-all.sh start full
```

The default middleware set is PostgreSQL, Kafka, OpenSearch, Redis, ClickHouse,
MinIO and Temporal. Administrative UIs and observability tools are opt-in; see
[Getting started](../getting-started.md). Serial startup reduces concurrency,
but a launcher timeout is not readiness evidence. Check each service and its
required dependencies before recording the ready phase.

If the three-instance Detection fixture misses its startup deadline,
`detection-cluster.sh` captures loopback health responses and bounded `jcmd`
platform/virtual thread dumps before stopping those processes. Evidence is
retained under `.cache/detection-cluster/startup-failure-*`; missing attach
support or a diagnostic timeout is recorded without converting startup failure
into success. The startup readiness deadline is unchanged.

Confirm OpenSearch's effective heap through the running JVM, for example:

```bash
docker exec socp-opensearch /usr/share/opensearch/jdk/bin/jcmd -l
# The pinned image runs org.opensearch.bootstrap.OpenSearch as PID 1.
docker exec socp-opensearch /usr/share/opensearch/jdk/bin/jcmd 1 VM.flags
```

`InitialHeapSize` and `MaxHeapSize` must both be `536870912`. Alternatively,
query the authenticated `/_nodes/jvm` API and inspect `heap_init_in_bytes`,
`heap_max_in_bytes` and `input_arguments`. Configuration rendering alone is
insufficient. Use `jcmd <search-config-pid> VM.system_properties` to check the
JDK, and verify the cache environment inherited by that process without
printing unrelated secrets.

## Measure phases and functional results

For a sustained verification on a disposable local stack, run
`python3 build/verify-load-soak.py --allow-local-fixture --seconds 3600 --eps 5`.
The probe requires explicit collector, metrics, OpenSearch and ClickHouse
fixture credentials and rejects non-loopback endpoints. It sends approximately
1 KiB events with concurrent duplicate replay and issues bursts of ten gateway
searches every ten seconds. A successful replay counts as an acknowledgement
even when its concurrent primary request fails; that failed request still fails
the zero-error gate. Every accepted event and expected alarm is reconciled
across Kafka, PostgreSQL, OpenSearch and ClickHouse.
Enabled notifications must be LOG or a WEBHOOK whose target exactly matches
the explicitly supplied loopback `SOCP_WEBHOOK_FIXTURE_URL`; other enabled
channels stop the probe before it submits load. This lets the manual workflow
reuse its persistent local webhook fixture without allowing off-host delivery.

The manual **Full Stack E2E** workflow has a `run_load_soak` boolean input for
this additional one-hour check. It uses the job's isolated middleware and disk
volumes, retains raw results under `.cache/load-soak`, and fails on request/query
errors, incomplete duration, resource guards or reconciliation failures. Normal
PR runs do not add this hour. These bounded fixture measurements do not establish
production throughput, retention capacity or a 24-hour stability guarantee.

For a pressure ramp without a soak, use
`python3 build/verify-load-soak.py --allow-local-fixture --mode pressure --seconds 300 --stages 10,25,50,100`.
The manual workflow's separate `run_load_pressure` input runs these four stages
on the same isolated stack, retaining evidence under `.cache/load-pressure`.
Choose either pressure or soak per workflow run. Each pressure stage is bounded
to 60–600 seconds, with at most five increasing rates in 1–100 EPS and 100,000
planned events including warmup. The first failed stage stops escalation; a
detection lag above 1,000 also stops that stage's submissions. Previously
acknowledged events are still drained and reconciled, and a failed stage retains
a nonzero exit status. Warmup errors also fail the run. Each load phase must
contain actual queries and sustain at least 98% of its target acknowledgement
rate, including the time spent completing in-flight requests. Query concurrency
remains ten per burst with a ten-second pause, not ten continuously active users.
Report each stage's duration, actual rate, errors, latency, backlog and identity
checks; the highest passing stage is an observed test result, not a production
capacity commitment.

The manual pressure run also requires a fresh `LATERAL-RDP` checkpoint for the
canonical input topic, including serialized state and a partition offset vector.
An asynchronous completion callback must install the event's validated tenant
before entering the snapshot transaction, then restore its previous scope even
when persistence fails. Without that scope the journal can remain correct while
every periodic checkpoint is deferred; event/alert identity checks alone do not
prove checkpoint persistence. Use `--require-state-snapshot` on a local fixture
whose configured checkpoint interval will be reached by the chosen workload.

Collect the following at a fixed interval, such as five seconds, from before
startup through an idle observation period after the workload:

```bash
ps -axo pid,rss,comm                 # RSS is KiB; identify each application PID
docker stats --no-stream            # Container usage and configured limits
sysctl vm.swapusage
vm_stat
memory_pressure -Q                 # macOS pressure summary
sysctl kern.memorystatus_vm_pressure_level
```

Record baseline, startup peak, all-services-ready, normal queries and small-batch
ingestion phases separately. Preserve per-service samples, not only totals.
Report swap deltas against the baseline, compression and pressure changes,
sample interval and observation duration. Docker VM RSS includes container
memory; do not add both to estimate host use. Neither summed `Xmx` values nor
summed container limits represent consumption. RSS is also not Java heap usage.

Use unique fixture identifiers and record input count/bytes. Verify those IDs
through collection, Kafka, indexed search, detection and alerts. Require
`source=opensearch` and `degraded=false` for complete search evidence; reduced
cache limits do not make the degraded local window a historical datastore.
Exercise report generation, archive upload and download against MinIO, and a
published SOAR workflow through Temporal. Use an authorized no-action workflow
when external response actions are outside the verification scope.

Inspect OOM status and restart counts using `docker inspect`, application OOM
logs with `python3 build/verify-jvm-memory.py`, and consumer lag with
`kafka-consumer-groups.sh --describe`. Record baseline counters to distinguish
previous failures from the current run. For GC evidence, add JDK unified GC
logging to the existing heap options; retain pause maxima and post-GC occupancy
alongside RSS samples. Sustained growth requires a longer observation window
before attributing it to a leak or changing a service budget.

Store measurements and task-specific results under ignored `docs/_local/`.
List failed checks and untested components explicitly. Adjust only the service
identified by the evidence, repeat the affected workload, and retain both runs.


## Dependency outages and optional tools

The `pg` profile includes Redis in gateway and search readiness because the
launcher selects shared rate limits and replay guards. Standalone H2 profiles
retain their existing readiness groups. Explicit
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE` overrides remain supported
and must include every dependency required by the chosen runtime role.
Gateway and search Redis connect/command timeouts default to one second;
`SOCP_REDIS_CONNECT_TIMEOUT` and `SOCP_REDIS_COMMAND_TIMEOUT` override them.
Authentication limiter failures retain the configured fail-closed behavior.

OpenSearch Dashboards uses HTTPS by default. For an explicitly security-disabled
OpenSearch development node, set `SOCP_OPENSEARCH_SCHEME=http` consistently with
that node. The observability overlay mounts rules at `/etc/prometheus-rules`,
outside the read-only configuration directory; no writable configuration
mount or copied temporary rules directory is required.
