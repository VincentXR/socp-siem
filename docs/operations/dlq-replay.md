# Kafka dead-letter queue redrive runbook

Consumers write terminal records to topics derived as `topic + "-dlq"`; see
[Kafka topic provisioning](kafka-topic-provisioning.md). No application consumer
automatically reads those records back. This runbook defines triage, safe redrive,
and recovery verification.

`build/replay-dlq.py` is the tool. It is **idempotent** (re-running is safe) and
**fail-closed** (it refuses the re-drive cases it cannot do correctly rather than
silently corrupting routing or state).

## What is actually in a `-dlq` topic

The dead-letter topics are **not** homogeneous. Only one writer produces a
re-drivable envelope; the others store the raw value with a thin or missing reason
(most writers do not persist a usable reason):

| Dead-letter topic | Written by | Record shape | Reason captured |
| --- | --- | --- | --- |
| `socp-events-dlq` | `OsIndexerConsumer` (search-config) | structured **envelope** (`originalTopic`/`partition`/`offset`/`key`/`eventId`/`tenant`/`schemaVersion`/`reasonCode`/`reason`/`originalPayload`/`failedAt`) | yes |
| `socp-events-dlq` | legacy `KafkaEventConsumer` (detect-web) | **raw** event value, key = `eventId`, reason only in the journal `statusReason` | no |
| `socp-detection-routed-v2-dlq` | routed `KafkaEventConsumer` (detect-web) | raw value; the per-dimension **routing key is a record header** not persisted in the DLQ | no |
| `socp-alarm-events-dlq` | `AlarmEventConsumer` (alert-web) | raw value | no |
| `socp-alarm-original-dlq` | `AlarmConsumer` (alert-web) | raw value | no |
| `socp-rule-changes-dlq` | `RuleChangeListener` (detect-web) | raw value | no |
| `socp-audit-dlq` | `AuditConsumer` (soc-base) | raw value | no |

Because `socp-events-dlq` mixes both an envelope and raw writes, the tool classifies
each record independently (see `classify()` in `build/replay-dlq.py`): an envelope is
re-driven as its embedded `originalPayload`, a raw record is re-driven as-is.

## Decision procedure (before you replay anything)

1. **Find the poison, not the volume.** A dependency outage should keep the DLQ flat;
   DLQ growth that tracks an availability incident means classification regressed
   (see [event-path observability contract](../observability-stage-metrics.md)).
   `SocpDetectionDlqHandoffGrowth` and `SocpDeadLetterGrowth` page the *onset*; the
   `socp.detection.processing.failure{category}` breakdown tells you whether this is
   a bad record (`schema_rejected`/`invalid_payload`) or an outage.
2. **Fix the cause first.** A deterministic malformed input will just land back in
   the DLQ. Repair the producer, the schema, or the dependency, then replay.
3. **Replay in a small batch.** `--limit` a handful of records, confirm they drain,
   then widen. This is a redrive, not a firehose.

## How to run it

Inspect a batch without touching the broker (offline triage from a console export):

```bash
# export the DLQ first: key<TAB>value per line
docker exec -i socp-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic socp-events-dlq \
  --property print.key=true --property key.separator=$'\t' \
  --timeout-ms 5000 > /tmp/socp-events-dlq.tsv

python build/replay-dlq.py --topic socp-events-dlq \
  --input-file /tmp/socp-events-dlq.tsv --dry-run --limit 50
```

Re-drive a reviewed batch (idempotent; safe to repeat):

```bash
python build/replay-dlq.py --topic socp-events-dlq --limit 50 \
  --bootstrap localhost:9092
```

You can also emit an offline replay file instead of writing to the broker
(`--output-file replay.tsv`), which keeps a change record for the audit trail.

## What the tool will refuse, and why

`build/replay-dlq.py` exits non-zero and sends nothing in two situations. Both are
correctness, not convenience:

* **Detect-path topics (`socp-events`, `socp-detection-routed-v2`) without `--force`.**
  `DetectionEventJournal` treats a repeat of a `DEAD_LETTERED` id as a permanent skip
  ([detection state semantics](../detection-state-semantics.md): "`DEAD_LETTERED`:
  skip the event"), and there is **no admin endpoint to reset a terminal journal
  row**. A plain re-produce to `socp-events` therefore re-indexes into OpenSearch but
  is silently dropped on the detection lane. Only use `--force` after you have
  handled the journal row as described below.
* **`socp-detection-routed-v2` even with `--force`.** The routed delivery decides its
  partition from a **record header** that the DLQ write does not persist; a replayed
  record would land in the wrong lane and diverge partition-local state. These must
  be re-ingested with a correct routing key (i.e. replayed at the source), not raw-
  redriven. The tool blocks this unconditionally.

## Detection-path terminal rows

The detection DLQ is genuinely one-way at the *code* level, not merely undocumented:
the terminal row and the journal's mutual-exclusion invariant keep a re-delivery from
being re-evaluated, and payload retention is inverted (DLQ payloads are kept 30 days,
the `DEAD_LETTERED` journal rows 90 days) so the payload evidence can expire while the
blocking row remains. Until an admin reset exists, recovery of a detection poison
record is:

1. Confirm the record is fixed at the source (rule/schema/dependency).
2. Re-drive to `socp-events` with `--force` to recover the **OpenSearch** copy.
3. For the **detection** copy, the event must enter under an id the journal has not
   terminalised, or an operator with DBA rights removes the `DEAD_LETTERED` journal
   row for `(tenant_id, event_id)` and lets the re-drive re-evaluate it. Removing a
   journal row is a manual, audited, DBA-only step and must be logged against the
   incident.

Detect Web does not expose an administrative reset endpoint for a terminal
journal row. Consequently, the DBA procedure above is an exceptional recovery
path, not a normal redrive workflow.

## Verify the pipeline recovered

After a small-batch replay, confirm against the same signals that flagged it:

* `socp_kafka_consumer_lag` returns to its baseline;
* `socp.detection.dlq.handoff{outcome="committed"}` stops growing
  (`SocpDetectionDlqHandoffGrowth` resolves);
* `socp_opensearch_indexer_records_total{stage="dlq"}` is flat
  (`SocpDeadLetterGrowth` resolves);
* `socp.detection.processing.failure` by category is quiet.

For outbox `DEAD` rows (a different mechanism from Kafka DLQ) use the admin requeue /
discard endpoints documented in [ADR 005](../adr/005-outbox-lifecycle.md).

## Retention caveat

`DLQ_RETENTION_MS` is 30 days against a 7-day main chain
(`infra/init-sql/kafka/create-topics.sh`). Dead-letter payloads do not live forever:
redrive or export them before 30 days, and treat the 30-day/90-day inversion above as
evidence you must not rely on the DLQ payload still being present later.
