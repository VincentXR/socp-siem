# Validation Matrix

This matrix defines what SOCP proves on a single-node development stack and
what must be run explicitly for multi-instance semantics. It is evidence for
correctness and recovery behavior, not a production capacity or HA claim.

## Automated and operational checks

| Layer | Command | Pass evidence | Cadence |
|---|---|---|---|
| Java modules | `bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false` | Reactor tests pass, including auth, rules, Detection, Alert, incident, and shared error handling | Every change |
| Quality gate | `bash build/quality-gate.sh` | Coverage floor, SpotBugs, toolchain policy, migration/deployment contracts, detection content, and workbench checks | Every pull request |
| Dependency audit | GitHub `Dependency Audit` workflow | No Java/frontend dependency at or above the configured high-severity threshold | Weekly/release candidate |
| Workbench | `cd frontend/apps/workbench && pnpm test && pnpm verify` | API contracts, navigation permissions, type check, production build, artifact assertions | Frontend change |
| Cross-cutting slice | `python build/verify-slice.py` | Authentication, tenancy, audit, rate limiting, and trace propagation | PR/release candidate |
| Event pipeline | `python build/verify-pipeline.py` | Canonical event -> Kafka -> Detection -> Alert persistence -> OpenSearch/ClickHouse/report | Middleware change/scheduled |
| Detection content | `python build/validate-detection-content.py` | Manifest schema, metadata, positive/negative vectors, ATT&CK references | Rule/content change |
| Investigation dataset | `python build/verify-investigation-dataset.py` | Versioned alert/evidence cases, citation expectations, and human-approval guard | Every change |
| Golden scenario | `python build/demos/golden-demo.py --transport ingest` | SSH brute force -> successful login -> privilege escalation -> multi-stage correlation -> entity risk -> Incident/Notify/SOAR | Manual/full-stack |
| Detection restart | `python build/demos/detection-recovery.py` | Kafka backlog grows while Detection is down and catches up after restart | Manual/weekly |
| Alert Web outage | `python build/chaos-pipeline.py --scenario alert_web_restart` | Detection Alert Outbox survives Alert Web outage and creates one alert after recovery | Manual/weekly |
| Detection Outbox replay | `python build/chaos-pipeline.py --scenario detection_outbox_replay` | Rewound publisher state returns to `PUBLISHED` and Alert Web retains one logical alert | Manual/weekly |
| Duplicate delivery | `python build/chaos-pipeline.py --scenario duplicate_delivery` | Same event produces one logical alert by source ID | Manual/weekly |
| Collector identity | `SOCP_COLLECTOR_CREDENTIALS` + direct ingest probe | A registered collector is bound to one tenant; a user JWT or mismatched collector/tenant is rejected | PR/security change |
| PostgreSQL outage | `python build/chaos-pipeline.py --scenario postgres_outage` | Kafka lag grows while durable completion is unavailable, then drains with no pending journal rows | Manual/weekly |
| OpenSearch outage | `python build/chaos-pipeline.py --scenario opensearch_outage` | Detection remains available while search is degraded and indexing works after recovery | Manual/weekly |
| Multi-instance | `bash build/detection-cluster.sh start && python build/chaos-pipeline.py --scenario multi_instance` | Three-instance disjoint ownership, rebalance, exact alert set, duplicate count, Kafka lag, delivery receipts, ClickHouse logical uniqueness, and `pendingEvents == 0` | Weekly/release candidate |
| E2E benchmark | `python build/benchmark-pipeline.py --mode e2e --profile realistic --count 10000 --batch-size 500` | Run-scoped alerts, T0-T8 stages, transaction ratios, offsets, lag, and before/after stats | Performance regression |
| Steady state | `python build/benchmark-pipeline.py --mode e2e --profile realistic --offered-eps 100 --duration 120` | Offered/actual EPS, lag samples, peak/growth, final drain | Performance regression |
| Bulk baseline | `python build/benchmark-pipeline.py --count 100` | Detection HTTP accepted/rejected counters and latency percentiles | Manual |
| Full API | `python build/verify-full.py` | Resource CRUD, tenancy, import/export, threat, and response contracts | Scheduled/release candidate |
| Dependency failure | `python build/failure-tests.py` | Kafka, OpenSearch, Temporal, and PostgreSQL recovery assertions | Manual/scheduled |

## Reliability acceptance criteria

- Duplicate Kafka delivery does not repeat a logical detection or downstream
  side effect.
- A partition commit never skips a lower pending offset; after recovery the
  Detection journal has no pending rows for the processed workload.
- Malformed events are rejected or sent to DLQ without taking down the
  consumer.
- Detection Alert Outbox rows remain pending when Alert Web is unavailable and
  retry after Alert Web recovers.
- Alert Web duplicate creates resolve by `(tenant_id, source_alert_id)`.
- Secondary analysis claims `(tenant_id, source_alarm_id, analyzer_version)` and
  committed redelivery is a no-op; a rolled-back claim remains retryable.
- Alert Outbox rows remain pending when Kafka is unavailable and are marked
  published only after a broker acknowledgement.
- Every Alert downstream destination has one durable receipt per
  `(tenant_id, alarm_id, destination)`; `PENDING`/`PROCESSING` and `DEAD` are
  visible and recoverable rather than silently treated as success.
- ClickHouse physical retries are permitted at the transport boundary, but
  every report query uses logical `(tenant_id, alarm_id)` uniqueness.
- Stopping Detection increases Kafka lag rather than silently losing the
  backlog; after restart, the consumer catches up.
- Multi-instance assignments are disjoint and stateful rules whose grouping
  field equals the routing field produce one logical result after rebalance.
- OpenSearch degradation does not claim search success; recovery restores the
  search path.
- Viewer writes and service-only side effects are rejected at the owning service
  boundary, analyst operations remain available, and tenant headers/claims do
  not cross query boundaries.
- Invalid client input returns the intended 4xx status and sanitized envelope.

## Scale baseline

Choose scale points appropriate to the fixed local machine and run both E2E
profiles. Retain JSON reports with machine profile, commit, rules, instances, partitions,
batch-request P50/P95/P99, `alertCreatedAt - triggerIngestedAt` latency sample,
Kafka lag, Detection stats, expected/observed alert counts, and recovery
observations. Use the same event shape and a clean test tenant. This is a
repeatable local baseline, not a production throughput or HA claim. The
sanitized reference snapshot records 10,000 realistic events and 1,000
alert-heavy events; larger runs are optional evidence, not a release gate.

Do not commit local usernames, absolute paths, hardware identifiers, email
addresses, tokens, passwords, or machine-specific screenshots.

## CI ownership

Push and pull-request CI runs the Java suite, opt-in Testcontainers contracts,
frontend contracts/build, the minimal service slice, and the Kafka pipeline.
PRs run duplicate-delivery and Detection Outbox replay Chaos; nightly/manual CI
repeats those deterministic invariants. The full-stack workflow is
manual/weekly, starts the fixed three-instance cluster, and runs full API,
pipeline, process/database/OpenSearch outage, Golden Demo, Detection recovery,
multi-instance/rebalance, attack scenarios, and dependency failure checks with
logs and JSON evidence uploaded as artifacts.
