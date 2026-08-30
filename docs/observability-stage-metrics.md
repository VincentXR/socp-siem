# Event-path observability contract

The event path exposes bounded, stage-oriented metrics rather than one series
per event:

* `socp.ingestion.outbox.queue_age` and `socp.ingestion.outbox.lifecycle` cover
  claim, retry, publish, dead-letter and retention cleanup.
* `socp.opensearch.indexer.records{stage=consume|write|fail|drop|dlq|commit}`
  reconcile Kafka input with durable OpenSearch/DLQ outcomes.
* `socp.detection.event.stage{stage=...}` records Kafka queue, journal,
  evaluation and durable completion latency.
* `socp.detection.alert.stage{stage=...}` records alert-outbox age, HTTP round
  trip and downstream acknowledgement.

`ingested_at` is carried in the canonical event fields. Operators should use
the counters as an accounting invariant (`consume = write + drop + failed`) and
investigate any offset commit without a matching durable write or acknowledged
DLQ record. Percentile histograms are intended for dashboards; raw event IDs
are never metric labels.
