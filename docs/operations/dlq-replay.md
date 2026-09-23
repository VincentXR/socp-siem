# Kafka dead-letter queue redrive runbook

Consumers write terminal records to topics derived as `topic + "-dlq"`; see
[Kafka topic provisioning](kafka-topic-provisioning.md). No application consumer
automatically reads those records back. This runbook defines triage, safe redrive,
and recovery verification.

`build/replay-dlq.py` plans and re-produces a bounded batch. It preserves payload
identities and blocks the detection cases described below, but has no durable
replay journal. Repeating a command can repeat side effects unless the owning
consumer deduplicates them; check its contract in
[idempotency](../idempotency-contract.md) before redriving a topic.

Both broker and offline input default to 100 source records per invocation.
`--limit` accepts 1–10,000 and applies before deduplication. The byte transport
preserves null, empty, binary, tab and newline values, including ordered duplicate
headers available on the DLQ record. It never derives a broker key from `eventId`.

| Input | Replay behavior |
| --- | --- |
| Raw record | Preserve the DLQ key/value bytes and headers; let Kafka select the target partition. The DLQ partition does not identify the original partition. |
| Indexer envelope | Restore its explicit `key` (including null/empty), `originalPayload` and original partition. Require the complete envelope and matching `originalTopic`. Forward available DLQ headers; original headers omitted by the writer cannot be reconstructed. |
| Null value (tombstone) | Skip unless `--include-tombstones` is explicit; a tombstone may delete a key on a compacted topic. Empty and whitespace values remain unchanged and are eligible for replay. |

Only identical target/key/payload/headers/tenant records at the **same source
position** are collapsed within a batch: envelope original partition/offset, or
raw DLQ partition/offset. Null keys are never deduplicated. Two independently
produced observations remain separate even when their key and payload match.
`--no-dedup` retains all observations. There is no deduplication across invocations.

Limits are 4 MiB per record and 64 MiB per batch (topic, key, value and header
bytes), 256 headers per record, 4,096 headers per batch, and 256 UTF-8 bytes per
header name. Offline files are capped at 96 MiB, binary bridge output at 68 MiB.
An oversized batch fails before publishing; inspect a smaller batch or a specific
partition/offset. Kafka/broker message limits may be lower. These checks bound
accepted data, not the JVM heap used to fetch/decompress a broker record.

Replay uses new Kafka timestamps rather than restoring the source timestamp.
Exports retain DLQ positions/timestamps
as evidence; application event-time fields remain unchanged. Broker acknowledgement
does not establish downstream completion or exactly-once effects.

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

Live capture/replay requires Python 3, Java 21 and Kafka client jars (tested with
the repository's Kafka 3.9.2 client). `--kafka-bin /opt/kafka/bin` discovers the
installation's sibling `libs` directory. Alternatively set
`--kafka-classpath '/opt/kafka/libs/*'` or `SOCP_KAFKA_CLASSPATH`; Java uses the
platform classpath separator for multiple entries. `--java` selects the executable.
The bridge runs [DlqTransport.java](../../build/kafka/DlqTransport.java) using
[Java source-file mode](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html).
No console consumer/producer line protocol or Python Kafka package is used.

Capture source records for review without producing or committing offsets:

```bash
python build/replay-dlq.py --topic socp-events-dlq --limit 50 \
  --bootstrap localhost:9092 --kafka-bin /opt/kafka/bin \
  --export-file /tmp/socp-events-dlq.json

python build/replay-dlq.py --topic socp-events-dlq \
  --input-file /tmp/socp-events-dlq.json --dry-run --limit 50
```

Re-drive a reviewed non-detection batch after confirming its consumer's
deduplication behavior:

```bash
python build/replay-dlq.py --topic socp-alarm-events-dlq --limit 50 \
  --bootstrap localhost:9092 --kafka-bin /opt/kafka/bin
```

`--bootstrap` defaults to `SOCP_KAFKA_BOOTSTRAP`, then `localhost:9092`; verify it
before use. `--consumer-config` and `--producer-config` accept Kafka properties
files (up to 1 MiB), including TLS/SASL credentials. Transport settings override
serializers, interceptors, commits, timeouts, transactions and producer reliability
settings in those files; authentication is retained. Protect these files and
payload exports as sensitive data. Kafka stderr is drained with a bounded retained
prefix and is not echoed because client diagnostics can contain credentials.

The [consumer](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
manually assigns partitions, disables auto-commit and group membership, and reads
only committed records up to an initial end-offset snapshot. It starts at the
earliest retained offsets unless both `--partition N --offset N` are supplied.
The supplied offset is inclusive and must lie in the retained snapshot. Exports
carry positions for the next review batch; the tool does not save a cursor or
delete DLQ records. Empty topics succeed; metadata, authorization and read failures
fail rather than becoming an empty success. No topic is auto-created by inspection
or by the producer's initial target lookup.

`--timeout-ms` is a 1,000–120,000 ms deadline for each Kafka read or publish
operation (default 10,000). Each Java subprocess also has a 30-second startup
allowance and bounded cleanup. Both output streams are drained; timeout, excess
output, nonzero exit and incomplete frames fail and reap the child process.

Offline input is a single versioned `socp-dlq-batch/v1` JSON object with
`format`, `sourceTopic` and `records`. Each record has `topic`, `partition`,
`offset`, `timestamp`, `keyBase64`, `valueBase64` and ordered `headers` entries
(`key`, `valueBase64`). JSON null and empty Base64 strings are distinct. Malformed,
truncated, duplicate-field, wrong-topic or oversized files fail. **Legacy console
TSV exports are unsupported**: nulls, newlines and binary content already lost in
that format cannot be recovered; capture again from Kafka. Offline inspection
does not need Java or Kafka jars.

`--export-file capture.json` saves all inspected source records, including blocked
records, without replay planning. `--output-file selected.json` saves only eligible
original source records after planning and guards. Both refuse to overwrite an
existing file. Selected records retain their envelope until replay so re-import
does not unwrap the embedded event twice. `--dry-run` still reads Kafka unless
`--input-file` is supplied. Dry runs and selected output print the plan and exit
3 if blocked; inspecting the plan needs no override. The plan shows escaped key
previews, byte counts and payload hashes, not payload bodies.

The [producer](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html)
uses byte serializers, `acks=all`, idempotence and one in-flight request, and waits
for every send acknowledgement. This protects transport retries in that producer
session; it does not make an operator's repeated invocation idempotent. A failed
publish may already have acknowledged a prefix of the batch. Inspect the target
and consumer receipts before retrying; the tool does not promise atomic batches.

## What the tool will refuse, and why

The whole plan is blocked before any publish for malformed/incomplete indexer
envelopes, mismatched `originalTopic`, and these detection cases. `--force` only
overrides the terminal-journal guard; it never overrides missing routing metadata
or invalid envelopes:

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

The detection DLQ is one-way at the application level:
the terminal row and the journal's mutual-exclusion invariant keep a re-delivery from
being re-evaluated, and payload retention is inverted (DLQ payloads are kept 30 days,
the `DEAD_LETTERED` journal rows 90 days) so the payload evidence can expire while the
blocking row remains. Until an admin reset exists, recovery of a detection poison
record is:

1. Fix the source cause and export the payload, tenant and original identity.
2. Re-driving `socp-events` with `--force` can restore the **OpenSearch** copy;
   separately verify the indexer result. This does not reset detection state.
3. For **detection**, record an incident-specific recovery plan covering the active
   runtime generation, source receipts, routing outbox/delivery identities,
   terminal journal and any downstream effects. Re-ingesting the same event under
   another Kafka offset can reuse an existing routed delivery and remain skipped.
   Deleting a journal row alone is not a general recovery procedure. A corrected
   source event with a new identity is new work and can repeat earlier effects;
   authorize and reconcile that choice explicitly.

Detect Web does not expose an administrative reset endpoint for a terminal
journal row. Direct database intervention requires separate operational authority,
coordinated writers and an audited restore/reconciliation plan; this tool performs
none. See [detection state semantics](../detection-state-semantics.md) for the
durable boundaries that must stay consistent.

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
