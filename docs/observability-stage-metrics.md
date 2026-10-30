# Event-path observability and tracing

The event path exposes bounded, stage-oriented metrics rather than one series
per event:

* `socp.ingestion.outbox.pending.count`,
  `socp.ingestion.outbox.oldest.pending.age.seconds`,
  `socp.ingestion.outbox.dead.count`, and
  `socp.ingestion.outbox.oldest.dead.age.seconds` expose bounded backlog age;
  `socp.ingestion.outbox.lifecycle{outcome=...}` covers claim, retry, publish,
  dead-letter and retention cleanup.  The same `pending.count`/`oldest.*`
  naming is used by the alert and detection outboxes.
* `socp.ingestion.outbox.batch.duration{operation=claim|finalize,outcome}`
  measures database cohort operations; the matching `batch.items` distribution
  exposes effective cohort size. `socp.ingestion.kafka.duration` uses
  `operation=submit|ack` and `outcome=success|failure`: submit measures the
  producer call, including metadata or buffer waiting; ack measures entry to
  broker callback or timeout. These intervals overlap and must not be added.
  Compare them with pending age to distinguish database cost from broker
  waiting. Neither timer proves durable end-to-end throughput.
* `socp.ingestion.outbox.operation.duration{operation,outcome}` records cached
  timers with fixed `outcome=success|failure` and these operation labels:
  `selection` includes every due-row query, including empty results;
  `exhausted` and `stale` count actual maintenance queries (a skipped stale
  recovery interval produces no measurement); `backlog_metrics` covers the
  complete backlog refresh; `receipt_wait` covers only the cohort join after
  all submission calls return; `drain` accumulates the complete accepted drain,
  including its final backlog refresh. `receipt_wait` failure means an
  exceptional receipt/join, while a normally completed negative acknowledgement
  remains a Kafka acknowledgement failure. Drain failure includes scan or
  backlog-refresh exceptions, not a verdict that every record failed.
  Drain time includes the other operation timers and claim/finalize time: do
  not add these nested intervals. Up to two independent cohorts run concurrently,
  so claim, finalize and receipt-wait intervals also overlap across cohorts;
  their sum can exceed drain wall time and is not a serial-time attribution.
  Kafka acknowledgement time can overlap both
  submission and receipt waiting. Backlog refresh runs after the drain admission
  flag is released and can overlap another drain, so summing cumulative drain
  time does not reconstruct exact scheduling gaps. Timer failures cannot change
  publication state. No operation labels include tenant or event identities.
* `socp.opensearch.indexer.records{stage=consume|write|fail|drop|dlq|commit}`
  reconcile Kafka input with durable OpenSearch/DLQ outcomes.
* `socp.detection.event.stage{stage=...}` records per-event latency. The
  `kafka_queue` interval ends when a partition lane starts processing and
  includes upstream outbox, broker, preclaim and lane waiting. The `journal`
  interval excludes preclaim I/O. `durable_completion` includes audit/result
  construction, batching and the sink transaction; it is not database-only
  time. Summed event intervals overlap within a batch.
* `socp.detection.operation{stage,outcome}` records each physical invocation
  once: `journal_preclaim`, `batch_preparation`, `batch_commit`, `fencing`, or `checkpoint`.
  `socp.detection.operation.items` records the corresponding item count
  (events for batches, one checkpoint generation for checkpoints). Divide its
  sum by count to inspect actual batch size. Preparation includes rule work,
  undo/audit encoding and batch wait; commit includes fencing and durable sink
  writes; `fencing` measures ownership checks inside commit and must not be
  added to commit time; checkpoints include state capture and persistence. These timers also
  include failed invocations. The legacy `socp.detection.db.transactions`
  event counters count logical receipts, not physical database transactions.
  Per-event lifecycle timing uses tenant plus delivery identity, so routed
  copies of one source event remain independent.
* `socp.detection.alert.stage{stage=...}` records alert-outbox age, HTTP round
  trip and downstream acknowledgement.
* `socp.detection.dlq.handoff{outcome=committed|retry_round_exhausted}`
  records true poison-record hand-off. `retry_round_exhausted` is a pressure
  signal only: the same partition lane retains the hand-off and continues
  retrying; it is not permission to commit the offset.
* `socp.detection.processing.failure{category,stage}` classifies retryable
  failures with low-cardinality labels. Categories are
  `dependency|recovery|timeout|backpressure|ownership_lost|interrupted|unknown`;
  stages are `claim|evaluation|async_execution|mark_completed`.
* `socp.detection.processing.retry.round{outcome=category}` counts per-round
  retry-budget exhaustion without turning a valid event into DLQ.
  `socp.detection.processing.recovered{outcome=category}` counts a blocked
  partition returning to successful processing.
* `socp.detection.partition.retry.blocked` is the number of partitions blocked
  for failure recovery, while
  `socp.detection.partition.retry.oldest.seconds` is the age of the oldest such
  block. These are separate from lane/deferred-buffer backpressure.
* `socp.detection.offset.pinned` is the summed count of registered offsets that
  cannot yet cross the contiguous completion watermark. A completed later
  offset remains pinned behind an earlier retrying or DLQ-handoff gap.
* Search fallback retention exposes
  `socp.search.event.retention.oldest.eligible.age.seconds`,
  `socp.search.event.retention.lag.seconds`,
  `socp.search.event.retention.cleanup.duration.ms`, and
  `socp.search.event.retention.delete.rate.rows_per_second`. The lag probe uses
  one ordered oldest-row lookup rather than a high-frequency full-table
  `COUNT(*)`; `socp.search.event.retention{outcome=deleted|failure}` remains
  the lifecycle counter.
* `socp.detection.rule.routing.mismatch{rule,declared_field,event_field}`
  (Prometheus `socp_detection_rule_routing_mismatch_total`; Micrometer counters
  export with a `_total` suffix) emits one report per rule
  per window when an event's routing field does not carry the key the rule
  declared, making the routed-state contract a falsifiable observation;
  its series count is bounded by the rule catalogue and the field vocabulary,
  never by the event rate.

`ingested_at` is carried in the canonical event fields. Operators should use
the counters as an accounting invariant (`consume = write + drop + failed`) and
investigate any offset commit without a matching durable write or acknowledged
DLQ record. During a dependency outage, `socp.detection.dlq.handoff` should stay flat
while `socp.detection.processing.failure{category="dependency"}`,
`socp.detection.partition.retry.blocked`, and the oldest-block age rise. When
the dependency returns, `processing.recovered` should increase and the blocked
gauges return to zero. DLQ growth that tracks an availability incident rather
than malformed input means classification has regressed. Percentile histograms
are intended for dashboards; raw event IDs are never metric labels.

## Alert thresholds

The `socp-event-path` Prometheus rule group ships with the chart and is off by
default because it needs the Prometheus Operator CRDs. Each threshold below
is an operational default rather than a measured capacity or latency baseline.
Tune thresholds against the target deployment and inspect the owning metric
when an alert resolves; resolution does not always mean the backlog is empty.

| Alert | Threshold | Basis |
| --- | --- | --- |
| `SocpDetectionConsumerLag` | no lag reported, or `> 10000`, for 10m | `absent()` covers complete loss of the lag series; it does not detect a single missing consumer while another reports. A count of 10000 is not a fixed elapsed-time estimate. |
| `Socp*OutboxOldestAge` | `> 300s` pending | Investigate dependency availability, retry backoff, and drain capacity. Pending age alone does not identify the cause. |
| `Socp*OutboxDead` | `delta(...[15m]) > 0` for 5m | Detects net growth. Unresolved rows can remain after this alert resolves; removals can also mask new failures. Inspect the absolute count and use the admin requeue/discard workflow in ADR 005 to close retained rows. |
| `SocpDeadLetterGrowth` | `increase(...[15m]) > 0` | The indexer counter is monotonic, so `increase()` is the correct function here; the dead counts above are gauges and use `delta()`. |
| `SocpDetectionRuleIsolated` | `max(socp_detection_rules_isolated_count) > 0` for 5m | Isolation is the last-resort path that keeps a bad rule from killing the engine; it is silent unless paged, and resolves when the operator fixes or disables the rule. |
| `SocpDetectionOffsetPinned` | `max(socp_detection_offset_pinned) > 10000` for 15m | Indicates a sustained gap behind the contiguous commit watermark. Inspect blocked records and durable writes before diagnosing a stuck lane. |
| Detection retry blocked (`SocpDetectionRetryBlocked`) | blocked partitions `> 0` and oldest block `> 300s` | Five minutes distinguishes ordinary dependency jitter from a recovery path that needs operator attention. Alert on age, not every retry attempt. |
| `SocpDetectionDlqHandoffGrowth` | `increase(socp_detection_dlq_handoff_total{outcome="committed"}[15m]) > 0` | Dependency outages must leave the committed hand-off flat; growth means poison records reached the dead-letter topic and need the redrive procedure. |
| `SocpDetectionRoutingMismatchGrowth` | `increase(socp_detection_rule_routing_mismatch_total[30m]) > 0` for 10m | A routed-state contract mismatch; ticket-level content quality, not an outage. |
| Search retention lag (`SocpSearchRetentionLag`) | `socp_search_event_retention_lag_seconds > 3600` for two cleanup windows | The worker is designed to catch up in bounded rounds; sustained lag means delete throughput is below ingest/backlog growth or rows are protected by unresolved outbox work. |

Two properties are worth stating because they are easy to get wrong and the
failure is silent:

1. **An alert on a metric that vanishes cannot fire.** Any rule whose
   expression is `max(<series>) > x` goes inactive when `<series>` stops being
   exported, which is usually what happens when the producing component dies.
   Pair such expressions with `absent()`, or the alert stops talking at the
   moment it matters most.
2. **An alert that cannot resolve is not an alert.** If the underlying state is
   retained by design, never assert on its absolute value; assert on whether it
   is still getting worse.


## Recovery playbook

For a sustained Detection retry block, first group
`socp.detection.processing.failure` by `category` and `stage`. For
`dependency`, verify PostgreSQL/Kafka/downstream availability and transaction
errors; for `recovery`, inspect state-owner leases and recovery health; for
`ownership_lost`, confirm a new owner is assigned. Do not purge PENDING rows or
force their offsets forward. The consumer retains scheduling responsibility and
should recover without a manual restart when the dependency is healthy.

For retention lag, compare delete rate with ingest rate, then inspect the oldest
eligible age and unresolved `PENDING|PROCESSING|DEAD` ingestion outbox rows.
Multiple API instances use `SKIP LOCKED` batches, so they should make progress
without serializing on the same event rows. Increasing the per-transaction batch
without checking database I/O/replication lag is not the first response; adjust
the bounded run window/catch-up pause only after measuring the database.

## Distributed tracing

The event path crosses HTTP, Kafka, scheduler, and consumer-thread boundaries.
Putting a `traceId` in the MDC provides log correlation, but it does not create
a parent/child span tree. W3C `traceparent` carries the wire context;
`io.opentelemetry.context.Context` is the in-process parent.

| Hop | Carrier | Parent source |
| --- | --- | --- |
| Client → gateway | HTTP `traceparent` | inbound request context |
| Gateway → service | HTTP `traceparent` | gateway SERVER span |
| Service → Kafka | record header `traceparent` | current or persisted context |
| Kafka → consumer | record header `traceparent` | context extracted from the record |

`platform/socp-obs` provides transport-neutral extraction, injection, span
creation, and `traceparent` rendering. `platform/socp-client` provides Kafka
header adapters and consumer-span lifecycle helpers.

### HTTP and Kafka boundaries

`GatewayFilter` extracts the caller context, opens a SERVER span, overwrites
the forwarded `traceparent` with that span, and ends it from the WebFlux
reactive signal. This prevents an inbound header from choosing the parent of a
downstream service span or leaving a span open on the wrong thread.

Kafka producers inject a PRODUCER span. Consumers extract it and create a
CONSUMER child span. Transactional outboxes publish later on scheduler threads,
so the Ingestion and Detection Alert outboxes persist `traceparent` when the
business transaction is still current and rejoin it when publishing. Copying a
trace ID without reconstructing the parent context would produce a dangling
tree and is not sufficient.

### Export and verification

Export is opt-in:

```bash
SOCP_TRACING_ENABLED=true
SOCP_OTLP_ENDPOINT=http://localhost:4317
```

With export disabled, the legacy `X-Trace-Id` path still provides log
correlation. `KafkaTraceTest` asserts exported span relationships: consumer
parentage, producer header identity, outbox context rejoin, and clean root
creation when no valid header exists. Tests must inspect exported span data,
not merely equal MDC strings.
