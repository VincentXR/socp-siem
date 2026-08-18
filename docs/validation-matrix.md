# Validation Matrix

This matrix defines what SOCP proves on a single-node development stack. It is
evidence for correctness and recovery behavior, not a production capacity or
high-availability claim.

## Automated checks

| Layer | Command | Pass evidence | Cadence |
| --- | --- | --- | --- |
| Java modules | `bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false` | All module tests pass, including alert, detect, incident, auth, and shared error handling | Every change |
| Workbench | `cd frontend/apps/workbench && pnpm test && pnpm verify` | API contracts, navigation permissions, type check, production build, artifact assertions | Every frontend change |
| Cross-cutting slice | `python build/verify-slice.py` | Authentication, tenant propagation, audit, rate limiting, and trace headers | PR / release candidate |
| Event pipeline | `python build/verify-pipeline.py` | Canonical event → Kafka → detection → alert persistence → OpenSearch/ClickHouse/report | PR with middleware / scheduled |
| Golden scenario | `python build/demos/golden-demo.py --transport ingest` | SSH failed logins → canonical event → threshold/correlation alerts → Incident/Notify/SOAR | Manual / full-stack |
| Detection recovery | `python build/demos/detection-recovery.py` | Ingestion remains available, Kafka lag grows while Detection is down, committed offset catches up after restart | Manual / weekly |
| Chaos matrix | `python build/chaos-pipeline.py --scenario all --count 20` | Detection restart backlog recovery and duplicate delivery produce one logical Alert | Manual / weekly |
| E2E benchmark | `python build/benchmark-pipeline.py --mode e2e --count 100 --batch-size 25` | Canonical ingest → Kafka → Detection → Alert latency, offsets, and before/after stats | Manual, repeat at 10k/100k/1m |
| Bulk baseline | `python build/benchmark-pipeline.py --count 100` | Generic accepted/rejected counters and HTTP latency for the detection bulk boundary | Manual, repeat at 100/1,000/10,000 |
| Full API | `python build/verify-full.py` | Resource CRUD, tenancy, import/export, threat and response contracts | Scheduled / release candidate |
| Failure recovery | `python build/failure-tests.py` | Kafka, OpenSearch, Temporal, and PostgreSQL recovery assertions | Manual / scheduled |

## Reliability acceptance criteria

- Duplicate Kafka delivery does not repeat a logical detection or downstream
  side effect.
- Malformed events are rejected or sent to DLQ without taking down the
  consumer.
- A pending Outbox event remains pending when Kafka is unavailable and can be
  published after recovery.
- Stopping detection increases Kafka lag rather than blocking ingestion; after
  restart, the consumer continues from the backlog.
- OpenSearch degradation does not silently claim search success; recovery makes
  the search path available again.
- viewer writes are rejected, analyst operations remain available, and tenant
  headers/claims do not cross query boundaries.
- Invalid client input returns the correct HTTP 4xx status and a sanitized
  response envelope.

## Scale baseline

Run the E2E mode at 10,000, 100,000, and 1,000,000 events, then retain the JSON
reports with the machine profile, configured rule/instance counts, request and
end-to-end percentiles, Kafka offsets, and Detection stats. Use the same test
data shape and a clean test tenant for comparison. The result is a repeatable
single-node baseline; it is not a production throughput or HA claim.

Do not record or commit local usernames, absolute paths, hardware details,
email addresses, tokens, passwords, or machine-specific screenshots. Report
the result as a repeatable single-node baseline and explicitly exclude HA,
multi-node failover, and production throughput claims.

## CI ownership

Push and pull-request CI runs the Java suite, frontend contracts/build, the
minimal service slice, and the Kafka pipeline. The full-stack workflow is
manual/weekly and runs full API, pipeline, Golden Demo, Detection recovery,
attack-scenario, and dependency failure checks with logs uploaded as artifacts.
