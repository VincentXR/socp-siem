# On-call incident response runbook

This is the per-alert disposal procedure for the `socp-event-path` rule group in
[`deploy/helm/socp-core/templates/prometheusrule.yaml`](../../deploy/helm/socp-core/templates/prometheusrule.yaml)
and the `socp-soar` group in
[`infra/observability/prometheus/soar-alerts.yml`](../../infra/observability/prometheus/soar-alerts.yml).
Every `runbook_url` annotation points at a section here. Each entry gives: what fired,
blast radius, the commands to run, and the metric that confirms recovery.

Read [observability-stage-metrics.md](../observability-stage-metrics.md) for the
metric semantics and [ADR 005 outbox lifecycle](../adr/005-outbox-lifecycle.md) for the
`DEAD` requeue/discard contract the outbox procedures below rely on.

## Notification wiring (read this first)

These alerts only reach a human if three links are closed, and the repository owns
only the first:

1. **Rule evaluation** — the chart ships the rules but
   `monitoring.prometheusRule.enabled` is `false` in every profile
   (`values-dev/staging/production.yaml`), because a `PrometheusRule` needs the
   Prometheus Operator CRDs. Enable it on a cluster that has them.
2. **Prometheus loads SOAR rules** — `soar-alerts.yml` is loaded only if the
   deployment's Prometheus lists it under `rule_files`. The compose baseline mounts
   `infra/init-sql/prometheus` only; to make SOAR rules live without a second copy
   (single source of truth), either move them into the mounted dir or add the mount
   (see "Grafana/Prometheus mount points" below). Until then they are declarative
   templates, not an active page.
3. **Alertmanager routing** — **not provided in this repository.** There is no
   Alertmanager deployment, receiver, or escalation config; on Kubernetes that is
   normally the platform's `kube-prometheus-stack`. A fired alert with no receiver
   is an alert nobody sees. The deployment must configure Alertmanager receivers and
   an on-call rota; this repo deliberately does not fake one.

Until (1) and (3) are done by the operator, the default posture produces **no pages** —
that is a configuration decision, not a code defect, but it means "we have alerts"
overstates the running reality. Verify the wiring with a synthetic test alert before
relying on any page below.

## Dashboards

Load [`infra/observability/grafana/dashboards/socp-event-path.json`](../../infra/observability/grafana/dashboards/socp-event-path.json)
(via the `provisioning/` datasource + provider) for the trend view of every signal here.
Use the dashboard to triage *which* tenant/lane is affected; use the procedures below
to act.

---

## SocpDetectionConsumerLag

**Fired** when no consumer reports `socp_kafka_consumer_lag` (`absent()`) or lag `> 10000` for 10m.
**Blast radius**: detection ingestion for the affected partitions falls behind or stops; the
`absent()` half means the consumer stopped polling entirely (a wedge, not just slowness).
**Action**:
1. `build/run-all.sh status full` (or `kubectl -n socp-system get pods -l app.kubernetes.io/name=detect-web-worker`)
   — is the detection worker up and not restart-looping?
2. Correlate with `SocpDetectionRetryBlocked` / `SocpDetectionOffsetPinned`: lag alone with
   a healthy pipeline is usually a downstream dependency, not the consumer.
3. Do **not** restart to "clear" lag if a partition is retry-blocked — the consumer holds
   its position deliberately; a restart re-reads and re-wedges.
**Confirm**: `max(socp_kafka_consumer_lag)` returns below baseline and the reporting-consumer
count on the dashboard is `>= 1`.

## SocpOutboxOldestAge

**Fired** when ingestion outbox oldest pending `> 300s` for 10m.
**Blast radius**: canonical events are durable in Postgres but not yet published to Kafka;
downstream indexing/detection are starved. Alert and Detection variants below.
**Action**: confirm the `OutboxPublisher` scheduled task is alive (worker pod healthy, no
error loop); inspect the oldest PENDING row and the failure that is holding it. A single
stuck row that has exhausted retries becomes `DEAD` (see next section).
**Confirm**: `socp_ingestion_outbox_oldest_pending_age_seconds` drops.

## SocpAlertOutboxOldestAge

Same shape as ingestion, for the alert outbox (`socp_alert_outbox_oldest_pending_age_seconds`).
Blast radius: committed detections are not delivered to Alert. Check alert-web worker health
and the downstream Alert endpoint reachability.

## SocpDetectionOutboxOldestAge

Detection outbox variant (`socp_detection_outbox_oldest_pending_age_seconds`). The detection
alert path is fed by `AlertForwarder`, which always carries `sourceAlertId`; a stuck row here
blocks alert creation for a tenant, not the whole path.

## SocpOutboxDead growth

**Fired** on `delta(*_outbox_dead_count[15m]) > 0` (critical) for ingestion, alert, or detection
outboxes. Absolute DEAD count is **not** alerted (rows are retained on purpose and never return
to zero — see the rule comments).
**Blast radius**: rows that exhausted retries and need explicit operator action; they are
retained for investigation and never auto-replayed.
**Action** — use the admin requeue/discard endpoints from
[ADR 005](../adr/005-outbox-lifecycle.md) (admin-only, reason required):
```
GET  /api/admin/outbox/ingestion/dead
POST /api/admin/outbox/ingestion/{id}/requeue    # after fixing the cause
POST /api/admin/outbox/ingestion/{id}/discard    # confirmed unrecoverable
```
Equivalent routes exist for `detection-alerts`, `rule-changes`, `alarm-events`,
`alarm-deliveries`. Fix the cause before requeueing, or the row returns to DEAD.
**Confirm**: `*_outbox_dead_count` stops growing (the delta resolves); the specific id leaves
the DEAD listing.

## SocpDeadLetterGrowth

**Fired** on `increase(socp_opensearch_indexer_records_total{stage="dlq"}[15m]) > 0`.
**Blast radius**: the OpenSearch indexer is sending events to `socp-events-dlq` — either
malformed input or a mapping/schema regression that will re-fail on replay.
**Action**: follow [dlq-replay.md](dlq-replay.md). Inspect `reasonCode`/`reason` in the DLQ
envelope (this is the one DLQ writer that captures a structured reason). If the cause is an
OpenSearch mapping/field conflict, fixing the index template must precede replay, or the same
batch loops back to the DLQ.
**Confirm**: `socp_opensearch_indexer_records_total{stage="dlq"}` is flat.

## SocpDetectionRuleIsolated

**Fired** on `max(socp_detection_rules_isolated_count) > 0` for 5m (critical).
**Blast radius**: a rule failed at evaluation time and is being **silently skipped** — the
matching detections are lost while every other metric stays green. This is the worst
silent-loss mode the pipeline can have, which is why lag-based `absent()` cannot cover it.
**Action**:
1. Identify the rule via the `/stats` surface (`stats().isolatedRules` in detect-web).
2. `RuleSpecStore.compileOrReject` already 400-rejects un-compilable rules at save/activate;
   isolation happens on legacy content, imported content, or post-upgrade semantic drift. So
   re-save/re-activate the rule; if it now rejects, that is the defect.
3. If the rule is genuinely broken, disable it explicitly (better a known gap than silent loss)
   and open a detection-content ticket.
**Confirm**: `socp_detection_rules_isolated_count` returns to 0.

## SocpDetectionOffsetPinned

**Fired** on `max(socp_detection_offset_pinned) > 10000` for 15m.
**Blast radius**: the contiguous completion commit watermark cannot advance behind an earlier
record that never finalizes; completed later offsets stay pinned. Every health probe stays
green — this is the "visible only in logs" wedge.
**Action**: find the blocking record: check `socp.detection.processing.failure` by category.
If it is heading to the DLQ, the pin releases once the hand-off commits. Do **not** force the
offset forward or purge PENDING rows (breaks at-least-once). If a partition is stuck on a
record that never terminalizes, escalate to the detection-owner procedure in
[detection-state-semantics.md](../detection-state-semantics.md).
**Confirm**: `socp_detection_offset_pinned` returns near baseline.

## SocpDetectionRetryBlocked

**Fired** on `socp_detection_partition_retry_blocked > 0` **and**
`socp_detection_partition_retry_oldest_seconds > 300`.
**Blast radius**: a partition lane is in failure-recovery retry, holding its position.
**Action**: group `socp_detection_processing_failure` by `category`/`stage` (the Recovery
playbook in [observability-stage-metrics.md](../observability-stage-metrics.md)). For
`dependency` verify Postgres/Kafka/OpenSearch; for `recovery` inspect state-owner leases;
for `ownership_lost` confirm a new owner took over. The consumer self-heals when the
dependency is healthy — do not restart-loop it.
**Confirm**: blocked gauge returns to 0 and `processing.recovered` ticks up.

## SocpDetectionDlqHandoffGrowth

**Fired** on `increase(socp_detection_dlq_handoff_total{outcome="committed"}[15m]) > 0`.
**Blast radius**: detect-web terminalized poison records into `socp-events-dlq` /
`socp-detection-routed-v2-dlq`. A dependency outage must keep `committed` flat; growth means
genuinely poison input.
**Action**: [dlq-replay.md](dlq-replay.md). **Important**: the detection journal blocks
reprocessing of a `DEAD_LETTERED` id, so a naive replay to `socp-events` re-indexes into
OpenSearch but is silently skipped on the detection lane, and `socp-detection-routed-v2`
replay is blocked outright (routing header not persisted). Handle those per the redrive
runbook, not with `--force` alone.
**Confirm**: the committed hand-off counter is flat.

## SocpDetectionRoutingMismatchGrowth

**Fired** on `increase(socp_detection_rule_routing_mismatch_total[30m]) > 0` (ticket-level).
**Blast radius**: a rule evaluated an event whose routing field does not carry the declared
key, so partition-local state can diverge. Not an outage; a content-quality regression.
**Action**: open a ticket per `(rule, declared_field, event_field)` label set; fix the rule's
declared routing or the producer's routing field. Do not escalate to paging severity.
**Confirm**: the counter stops growing.

## SocpSearchRetentionLag

**Fired** on `max(socp_search_event_retention_lag_seconds) > 3600` for 2h.
**Blast radius**: `t_search_event` retention cleanup is behind ingest; the table grows and
idempotency-probe cost climbs.
**Action**: compare `socp.search.event.retention.delete.rate.rows_per_second` against ingest;
inspect unresolved PENDING/PROCESSING/DEAD ingestion outbox rows that pin old events. Multiple
API instances drain with `SKIP LOCKED`, so they progress without serializing — a stall points at
DB I/O or a dependency, not the worker design. Do not raise batch size before measuring DB I/O
and replication lag.
**Confirm**: `socp_search_event_retention_lag_seconds` declines below 3600.

---

## SOAR alerts

Load `soar-alerts.yml` per the wiring section. The three SOAR pages are documented in
[SOAR runbook](../soar-runbook.md) (dispatch backlog, signal backlog, unknown action results);
keep those cross-references there rather than duplicating the Temporal/connector steps.

## Grafana / Prometheus mount points (compose baseline)

To stop the observability assets under `infra/observability` being orphans, mount them into
the compose services. These edits belong to the shared `infra/docker-compose.yml` and
`infra/init-sql/prometheus/prometheus.yml` (outside this runbook's owner), so the exact lines
are recorded here for whoever applies them:

```yaml
# grafana service — add to volumes:
- ../../infra/observability/grafana/provisioning/datasources:/etc/grafana/provisioning/datasources:ro
- ../../infra/observability/grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
- ../../infra/observability/grafana/dashboards:/var/lib/grafana/dashboards:ro
# prometheus service — add to volumes and reference in prometheus.yml:
- ../../infra/observability/prometheus:/etc/prometheus/soar-rules:ro
```
```yaml
# infra/init-sql/prometheus/prometheus.yml — add:
rule_files:
  - /etc/prometheus/soar-rules/*.yml
```

The Helm side carries the same rule expressions in the `PrometheusRule` (single source of
truth for the event-path group); keep the compose SOAR rules and the chart group from diverging
by editing one and mirroring the expressions in the other.

## Alertmanager / on-call (deployment-owned)

Not provided here. Configure Alertmanager receivers, routing, and an on-call rota in the
monitoring stack; add the `runbook_url` base (if you host these docs) via the chart's
`monitoring.prometheusRule.additionalLabels`/receiver config so the annotations above resolve
to a browsable link. A fired alert without a configured receiver is functionally no alert.
