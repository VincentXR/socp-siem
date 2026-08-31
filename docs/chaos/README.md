# Failure matrix

`build/chaos-pipeline.py` checks concrete failure invariants against a running
local or CI stack. It uses a versioned dataset plus a per-run namespace and
always emits a structured report (and writes it when `--output` is supplied).
It never turns an unavailable dependency into a pass.

## Scenarios

```bash
python build/chaos-pipeline.py --scenario detect_restart --count 20
python build/chaos-pipeline.py --scenario alert_web_restart
python build/chaos-pipeline.py --scenario duplicate_delivery
python build/chaos-pipeline.py --scenario detection_outbox_replay
python build/chaos-pipeline.py --scenario postgres_outage
python build/chaos-pipeline.py --scenario opensearch_outage
python build/chaos-pipeline.py --scenario all --count 20 \
  --run-id local-all-20260826 --output .cache/chaos/local-all-20260826.json
```

- `detect_restart`: stops Detection, injects events, verifies Kafka backlog,
  restarts Detection, and waits for the consumer group to catch up.
- `alert_web_restart`: stops Alert Web while Detection continues, injects an
  alert-producing event, restarts Alert Web, and verifies the Detection Alert
  Outbox eventually produces exactly one Alert Web row.
- `duplicate_delivery`: submits the same canonical event twice and verifies
  one logical alert by `sourceAlertId`.
- `detection_outbox_replay`: rewinds a published Detection Outbox row and
  proves replay converges to `PUBLISHED` without a second logical Alert row.
- `postgres_outage`: proves the committed frontier stops, lag grows, and the
  journal drains after PostgreSQL recovery.
- `opensearch_outage`: proves detection remains available during search
  degradation and a recovery event becomes searchable afterwards.
- `multi_instance`: verifies disjoint partition assignment, stable entity
  routing, rebalance after stopping the first instance, assignment restore,
  contiguous completion (`pendingEvents == 0`), and deterministic alert-set
  equality before and after rebalance.

## Multi-instance setup

The multi-instance oracle uses exactly three `detect-web` processes. The
repository supplies the reproducible launcher; it does not require manually
starting extra JVMs:

- the `pg` profile and the same Detection PostgreSQL database;
- distinct server ports;
- the same `SOCP_KAFKA_GROUP_ID` (default `socp-detect`);
- a Kafka topic with at least as many partitions as instances.

Start the fixed cluster after building the backend and starting middleware:

```bash
export SOCP_JWT_SECRET='<same secret used by the running stack>'
bash build/detection-cluster.sh start
DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082,http://127.0.0.1:38082 \
  python build/chaos-pipeline.py --scenario multi_instance --count 30 \
  --rebalance-cycles 3 --run-id local-multi-instance \
  --output .cache/chaos/local-multi-instance.json
bash build/detection-cluster.sh stop
```

Startup fails before changing the running topology if no JWT/JWK/issuer
configuration is present. A successful start writes the commit, Kafka group,
profile, database, topic partition count, ports, and native listener PIDs to
`.cache/detection-cluster/manifest.env`; this machine-local file is ignored by
Git.

The script requires every partition to be assigned exactly once before the
probe starts. It then stops the first instance, verifies the remaining two
instances own all partitions, restarts the first instance, and verifies the
assignment returns to a disjoint three-instance layout. The report records the
dataset version/seed, commit, Kafka lag, journal state, delivery receipts, and
logical ClickHouse uniqueness evidence.

## Pass meaning

`pass=true` means every selected invariant was observed: no silent dependency
failure, no duplicate source alert, no missing backlog recovery, no pending
Detection journal rows after recovery, and no unaccounted partition ownership.
The multi-instance oracle compares expected deterministic alert IDs with the
actual set after the rebalance. The matrix does not claim exactly-once
delivery, strict cross-partition ordering, or production HA.

CI cadence is intentionally split by environment. Pull requests and the
nightly scheduled job run the deterministic duplicate-delivery and outbox
replay scenarios. The Compose-backed process/database/OpenSearch outage
matrix and the multi-instance rebalance oracle run in the weekly full-stack
workflow, where the named containers and three-instance topology exist.

Additional scenarios should record a before/after snapshot and an observable
invariant. A restart alone is not a chaos test unless loss, duplication, lag,
or downstream recovery is checked.

## Kafka-to-OpenSearch indexer failure evidence

The indexer's destructive failure boundaries run as a Docker-backed JUnit
suite so the exact Kafka offsets, OpenSearch item responses, DLQ records, and
commit outcome are asserted in one process:

```bash
SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/search-config -am test \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OsIndexerFailureContainerTest
```

`OsIndexerFailureContainerTest` proves all of the following against Kafka
7.6.1 and OpenSearch 2.11.1:

- a real mixed bulk response indexes two valid documents, sends the mapping
  rejection to a broker-acknowledged diagnostic DLQ, commits three source
  offsets, and satisfies `3 = 2 indexed unique docs + 1 DLQ source offset`;
- closing after the OpenSearch acknowledgement but before commit causes Kafka
  replay, while the tenant-scoped stable document ID keeps one unique document;
- an unavailable DLQ broker leaves the rejected source offset uncommitted;
- a real broker shutdown after the write acknowledgement increments
  `commit_failed`, records no commit success, and leaves the indexed document
  available for replay;
- an Nginx fault proxy in front of the real OpenSearch node returns HTTP 503,
  which remains retryable and never becomes a permanent DLQ disposition.

This suite is failure-semantic evidence, not a throughput or availability
claim. Random topic/group suffixes and isolated daily indices prevent historic
test data from being mistaken for loss or duplication.

## Sanitized reference result

The 2026-08-20 local verification used six partitions and three Detection
instances. Three consecutive stop/restart cycles retained disjoint ownership;
all 12 deterministic expected alert IDs equaled the observed set and every
instance reported zero pending events. Separate PostgreSQL, OpenSearch, and
Detection Outbox replay probes also returned `pass=true`. Machine-specific
JSON remains under `.cache`; the reproducible commands and acceptance criteria
are the repository evidence contract.
