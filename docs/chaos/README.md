# Failure matrix

`build/chaos-pipeline.py` checks concrete failure invariants against a running
local or CI stack. It uses unique event IDs and reports JSON only when
`--output` is supplied. It never turns an unavailable dependency into a pass.

## Scenarios

```bash
python build/chaos-pipeline.py --scenario detect_restart --count 20
python build/chaos-pipeline.py --scenario alert_web_restart
python build/chaos-pipeline.py --scenario duplicate_delivery
python build/chaos-pipeline.py --scenario all --count 20 \
  --output .cache/chaos/$(date +%Y%m%d-%H%M%S).json
```

- `detect_restart`: stops Detection, injects events, verifies Kafka backlog,
  restarts Detection, and waits for the consumer group to catch up.
- `alert_web_restart`: stops Alert Web while Detection continues, injects an
  alert-producing event, restarts Alert Web, and verifies the Detection Alert
  Outbox eventually produces exactly one Alert Web row.
- `duplicate_delivery`: submits the same canonical event twice and verifies
  one logical alert by `sourceAlertId`.
- `multi_instance`: verifies disjoint partition assignment, stable entity
  routing, rebalance after stopping the first instance, assignment restore,
  contiguous completion (`pendingEvents == 0`), and deterministic alert-set
  equality before and after rebalance.

## Multi-instance setup

Start two or more `detect-web` processes with:

- the `pg` profile and the same Detection PostgreSQL database;
- distinct server ports;
- the same `SOCP_KAFKA_GROUP_ID` (default `socp-detect`);
- a Kafka topic with at least as many partitions as instances.

Set the health/API base URLs as a comma-separated environment variable. The
first URL must be the instance that `build/run-all.sh stop-service/start-service
detect-web` controls:

```bash
DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082 \
  python build/chaos-pipeline.py --scenario multi_instance --count 5 \
  --output .cache/chaos/multi-instance.json
```

The script requires every partition to be assigned exactly once before the
probe starts. It then stops the first instance, verifies the remaining process
owns all partitions, restarts the first instance, and verifies the assignment
returns to a disjoint multi-instance layout.

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
