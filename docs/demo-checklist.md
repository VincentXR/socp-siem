# Operational Demo Checklist

This guide provides a repeatable demonstration of the implemented security
event pipeline and its recovery behavior.

## Golden Demo: SSH brute force to account compromise

The scenario sends five failed SSH logins, a successful login, then host-local
session, suspicious execution, and sudo signals:

```text
sshd raw log
  -> Vector
  -> search-config: parse + canonical normalization
  -> Kafka: socp-events
  -> detect-web: threshold + correlation + pattern + correlation-set
  -> Detection Alert Outbox (durable retry)
  -> alert-web: PostgreSQL + idempotent sourceAlertId
  -> Alert Outbox
       -> incident-web / soar-web / notify-web / ClickHouse
event -> OpenSearch investigation
alert -> ClickHouse analytics
```

### Preparation

```bash
docker compose -f infra/docker-compose.yml up -d
bash build/run-all.sh start core
bash build/run-vector.sh start
```

Confirm the workbench is available at `http://localhost:5173`, then run:

```bash
python build/demos/golden-demo.py
```

The script creates or reuses a local SOAR playbook and notification channel,
appends five `Failed password` records and one `Accepted password` record to
`demo/sample.log`, and verifies:

1. The raw records become canonical events with `event.action`, `source.ip`,
   and `user.name` fields.
2. Rule `AUTH-BRUTE` produces an alert.
3. Rule `AUTH-BRUTE-SUCCESS` correlates the failed and successful logins.
4. `AUTH-PRIVESC` and `CORR-ATTACK-SIGNALS` complete the host attack story,
   while the shared entity-risk projection accumulates the account risk.
5. The Detection Outbox delivers once to Alert Web; the Alert Outbox creates
   an Incident, records a notification, and starts SOAR.
6. The reporting endpoint exposes downstream analytics and trace IDs.

The script first waits for Detection partition ownership and excludes every
pre-existing alert ID from its oracle. This prevents a newly created Kafka
group or historical demo data from producing a false pass.

If Vector is not the focus of the check, inject directly after the collector
boundary:

```bash
python build/demos/golden-demo.py --transport ingest
```

The local EMAIL channel records dispatches and does not send external mail.

## Failure Demo: detection recovery

Start the Golden Demo prerequisites, then run the repeatable recovery script:

```bash
python build/demos/detection-recovery.py
```

The script stops only `detect-web`, injects events through the normal ingest
boundary, observes the `socp-events` backlog grow, restarts Detection, and
waits for the committed offset to catch up. Recovery uses at-least-once
delivery, manual commits, event-ID deduplication, and durable DLQ handling.

The scripted dependency checks cover the same recovery family:

```bash
python build/failure-tests.py
```

They exercise Kafka, OpenSearch, Temporal, and PostgreSQL stop/restart paths.

For the focused Detection invariants, use the failure matrix. It records a
before/after Kafka snapshot and verifies that duplicate delivery creates one
logical Alert Web row:

```bash
python build/chaos-pipeline.py --scenario all --count 20 \
  --output .cache/chaos/latest.json
```

To exercise the durable Detection-to-Alert hand-off directly, run the Alert
Web outage probe:

```bash
python build/chaos-pipeline.py --scenario alert_web_restart
```

For multi-instance state ownership, run `bash build/detection-cluster.sh start`
to start exactly three Detection processes against the same PostgreSQL database
and Kafka group, then run the `multi_instance` scenario described in
[chaos/README.md](chaos/README.md).

## Expected evidence

Use the workbench to inspect the canonical events, the four primary alerts and their
ATT&CK mappings, the Incident timeline, the SOAR execution, and audit/trace
information. The infrastructure components are explained through their
responsibilities in [architecture.md](architecture.md); they do not need to be
opened individually during the walkthrough.

When tracing is enabled, use Jaeger for the distributed trace. Otherwise,
retain the response `X-Trace-Id` and service logs as the correlation evidence.

## Cleanup

```bash
bash build/run-all.sh stop
docker compose -f infra/docker-compose.yml down
```
