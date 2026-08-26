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
  --run-id nightly-20260826 --output .cache/chaos/nightly-20260826.json
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
bash build/detection-cluster.sh start
DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082,http://127.0.0.1:38082 \
  python build/chaos-pipeline.py --scenario multi_instance --count 30 \
  --rebalance-cycles 3 --run-id local-multi-instance \
  --output .cache/chaos/local-multi-instance.json
bash build/detection-cluster.sh stop
```

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

Additional scenarios should record a before/after snapshot and an observable
invariant. A restart alone is not a chaos test unless loss, duplication, lag,
or downstream recovery is checked.

## Sanitized reference result

The 2026-08-20 local verification used six partitions and three Detection
instances. Three consecutive stop/restart cycles retained disjoint ownership;
all 12 deterministic expected alert IDs equaled the observed set and every
instance reported zero pending events. Separate PostgreSQL, OpenSearch, and
Detection Outbox replay probes also returned `pass=true`. Machine-specific
JSON remains under `.cache`; the reproducible commands and acceptance criteria
are the repository evidence contract.
