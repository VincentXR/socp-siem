# Kafka topic provisioning runbook

The event path does not tolerate a missing topic once the platform is running
against a production-grade broker. This runbook covers the one ordering rule,
the partition-count choice, and the verification you owe after a first boot.

## Why this is a manual step, not an application concern

`infra/init-sql/kafka/create-topics.sh` is the single source that provisions the
topics the code actually uses: the five main-chain topics plus the
`<topic>-dlq` dead-letter queue each consumer derives in code. The names come
from the producers'/consumers' `@Value` defaults and `*Properties` fields, not
from an auto-created wildcard.

The demo broker (`infra/docker-compose.yml`) leaves
`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` so a recreated container does not silently
drop topics. The production overlay (`infra/docker-compose.prod.yml`) sets it to
`false`:

> Lazy creation would let a typo or a missing DLQ look like a healthy broker;
> with auto-create off, a missing topic fails the producer or the dead-letter
> hand-off loudly instead.

That overlay has **no** provisioning step of its own, so it is the operator's
job to run the script once before the stack goes live.

## Ordering dependency — run once before the first application boot

`create-topics.sh` MUST be executed against the broker **before the first
application boot** on a production cluster. Skipping it does not degrade quietly;
it breaks the event path at exactly the invariants the code refuses to fake:

- A main-chain topic missing first interrupts the producer, then every consumer.
- A `-dlq` topic missing turns the terminal hand-off into a permanent failure:
  the detect side gives up in `handoffToDlqUntilDurable` (commit stays pinned,
  surfaced by `socp.detection.offset.pinned` — see
  [observability-stage-metrics.md](../observability-stage-metrics.md)), and the
  alert side rolls the offset back forever without advancing.

Because `--create --if-not-exists` is idempotent, re-running the script is always
safe; the ordering constraint is only "at least once, before first boot."

## PARTITIONS=6 — align the script with the broker and the detection contract

The script defaults `PARTITIONS=3`, but the broker default
(`infra/docker-compose.yml`, `KAFKA_NUM_PARTITIONS=6`) and the distributed
correctness contract want **six partitions across three Detection instances**
(see [detection-state-sharding.md](../detection-state-sharding.md)). Create the
topics at six:

```bash
# from infra/init-sql/kafka/, against a reachable broker
PARTITIONS=6 BOOTSTRAP=localhost:9092 bash create-topics.sh
# or inside the container:
docker exec -i -e PARTITIONS=6 socp-kafka bash < infra/init-sql/kafka/create-topics.sh
```

Why six, and why you cannot defer the choice:

- Kafka **never reduces** a topic's partition count, and `--if-not-exists` will
  not resize a topic that already exists. Provisioning at 3 and later discovering
  the three-instance sharding needs 6 means an **explicit**
  `kafka-topics.sh --alter --partitions 6` per topic — and a partition-count
  change reshuffles key→partition routing, so do it on a drained/quiet topic.
- Partition count is also the ceiling on consumer parallelism per topic; a
  Detection generation that assumes six owned partitions cannot be satisfied by a
  three-partition topic.

Set `REPLICATION` and the retention overrides to match the real cluster; the
script's `REPLICATION=1` and DLQ retention (`-dlq` kept 30 days vs 7 for the main
chain) are single-broker demo defaults, not a durability choice (durability is
evidence in [production-readiness.md](../production-readiness.md)).

## Naming drift to watch

The script derives names from the default configuration. If you override a topic
with `SOCP_KAFKA_*`, rename the corresponding entry here too, and add any new
main-chain topic or `*-dlq` derivation to the arrays in the script — a name that
lives only in code and not in this list is a topic that fails to exist at boot.

## Verify after provisioning

```bash
docker exec -i socp-kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

Every main-chain topic and its `-dlq` should be present at the intended partition
count before the first application container starts. `build/verify-prod-compose.py`
asserts the production overlay re-states the auto-create key explicitly rather
than inheriting the demo value, so the off-by-default posture stays honest.
