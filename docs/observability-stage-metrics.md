# Event-path observability contract

The event path exposes bounded, stage-oriented metrics rather than one series
per event:

* `socp.ingestion.outbox.pending.count`,
  `socp.ingestion.outbox.oldest.pending.age.seconds`,
  `socp.ingestion.outbox.dead.count`, and
  `socp.ingestion.outbox.oldest.dead.age.seconds` expose bounded backlog age;
  `socp.ingestion.outbox.lifecycle{outcome=...}` covers claim, retry, publish,
  dead-letter and retention cleanup.  The same `pending.count`/`oldest.*`
  naming is used by the alert and detection outboxes.
* `socp.opensearch.indexer.records{stage=consume|write|fail|drop|dlq|commit}`
  reconcile Kafka input with durable OpenSearch/DLQ outcomes.
* `socp.detection.event.stage{stage=...}` records Kafka queue, journal,
  evaluation and durable completion latency.
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
  (Prometheus `socp_detection_rule_routing_mismatch`) emits one report per rule
  per window when an event's routing field does not carry the key the rule
  declared, making the partition-local state contract a falsifiable observation;
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
default because it needs the Prometheus Operator CRDs. Each threshold below was
chosen so that a healthy pipeline sits well clear of it, and so that the alert
can resolve again.

| Alert | Threshold | Basis |
| --- | --- | --- |
| `SocpDetectionConsumerLag` | no lag reported, or `> 10000` | `absent()` is required: the series only exists while a consumer reports one. A bare `max()` evaluates to nothing when the consumer wedges, so the alert would fall silent exactly when the thing it watches has stopped. 10000 records is roughly a minute of a loaded pipeline, far above normal catch-up. |
| `Socp*OutboxOldestAge` | `> 300s` pending | Two drain cycles should clear a row; five minutes of pending means the publisher is wedged rather than briefly behind. |
| `Socp*OutboxDead` | `delta(...[15m]) > 0` | Dead rows are retained deliberately for investigation and replay, so the count never returns to zero. Alerting on the absolute count latches forever; alerting on growth resolves once the backlog stops worsening. |
| `SocpDeadLetterGrowth` | `increase(...[15m]) > 0` | The indexer counter is monotonic, so `increase()` is the correct function here; the dead counts above are gauges and use `delta()`. |
| Detection retry blocked | blocked partitions `> 0` and oldest block `> 300s` | Five minutes distinguishes ordinary dependency jitter from a recovery path that needs operator attention. Alert on age, not every retry attempt. |
| Search retention lag | `socp.search.event.retention.lag.seconds > 3600` for two cleanup windows | The worker is designed to catch up in bounded rounds; sustained lag means delete throughput is below ingest/backlog growth or rows are protected by unresolved outbox work. |

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
