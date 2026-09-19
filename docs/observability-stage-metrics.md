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
* `socp.detection.dlq.handoff{outcome=committed|abandoned}` counts the durable
  dead-letter hand-off of an otherwise-poison event: `committed` fires once the
  DLQ write is acknowledged and the offset is free to advance; `abandoned` fires
  when the bounded hand-off retries are exhausted, so the partition commit stays
  pinned rather than skipping a still-undurable record.
* `socp.detection.processing.withheld{outcome=globally_unavailable|replay_globally_unavailable}`
  counts records whose processing is deliberately held while the state store is
  globally unavailable. A withhold neither dead-letters a record nor consumes the
  per-record DLQ budget (`globally_unavailable` on the live path,
  `replay_globally_unavailable` during journal replay); the offset stays pinned so
  recovery re-drives it once the store returns.
* `socp.detection.offset.pinned` is a gauge: the summed pending (un-committable)
  offsets across the assigned partitions while an `abandoned` hand-off or a
  `withheld` record blocks advancement. A sustained non-zero value is the
  actionable signal behind the two counters above — an idle pipeline shows zero,
  a wedged one grows.
* `socp.detection.rule.routing.mismatch{rule,declared_field,event_field}`
  (Prometheus `socp_detection_rule_routing_mismatch`) emits one report per rule
  per window when an event's routing field does not carry the key the rule
  declared, making the partition-local state contract a falsifiable observation;
  its series count is bounded by the rule catalogue and the field vocabulary,
  never by the event rate.

`ingested_at` is carried in the canonical event fields. Operators should use
the counters as an accounting invariant (`consume = write + drop + failed`) and
investigate any offset commit without a matching durable write or acknowledged
DLQ record. Because a global outage withholds records instead of dead-lettering
them, `socp.detection.dlq.handoff` should stay flat while
`socp.detection.processing.withheld` and `socp.detection.offset.pinned` rise;
DLQ growth that tracks an availability incident, rather than per-record poison,
means that classification has regressed. Percentile histograms are intended for
dashboards; raw event IDs are never metric labels.

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
