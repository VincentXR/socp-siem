# Validation Matrix

This matrix defines what SOCP proves on a single-node development stack and
what must be run explicitly for multi-instance semantics. It defines expected
evidence, not a claim that checks have passed on the current revision or that
the system has production capacity or HA certification.

## Local change validation

Select checks by the behavior or contract changed, not just the directory
touched. Documentation-only edits in a backend or frontend directory do not
require that component's executable suite. Combine rows for changes spanning
boundaries; the commands and fixtures are explained in [testing](testing.md).

| Changed boundary | Local verification |
|---|---|
| Documentation, AGENTS, Skills, or task prompts only | Check relative links/anchors, referenced paths and commands, and consistency with the owning contracts. For Skills, also check frontmatter, trigger scope, and on-demand references; run changed helper scripts. Runtime application prompts use the owning module's checks. |
| One backend module | `bash build/mvnw.sh -pl services/soar-web -am test -Dsurefire.failIfNoSpecifiedTests=false` (replace the module with the owner). |
| Shared platform behavior, cross-module contracts, persistence, or event flow | Full Maven suite plus relevant `build/verify-*.py` contracts; include the affected middleware-backed checks below. |
| Workbench source or build configuration | `pnpm test`, `pnpm lint`, `pnpm format:check`, and `pnpm verify` in `frontend/apps/workbench`. Run affected Playwright flows for user-flow changes; include a screenshot or concrete manual-verification note for visual changes. |
| Service boundary or topology | `python build/verify-contracts.py` and `python build/runtime-topology.py --check`. |
| Production configuration or deployment | `python build/verify-production.py` and affected `prod` guard/boot checks; Compose/Helm checks below when those artifacts change. |
| Build or verification scripts | Owning `build/tests` tests and the changed command with representative local inputs. |

For middleware-backed behavior, use relevant Testcontainers, pipeline,
full-stack, or chaos checks when the required local environment is available.
If it is unavailable, run independent checks and report the missing evidence.
Live-service and chaos checks need verified disposable targets; see
[test environments](testing.md#test-environments).

The repository-wide final-diff and completion requirements are in
[AGENTS.md](../AGENTS.md#completion). This local selection does not replace or
relax CI/release gates.

## Automated and operational checks

| Layer | Command | Pass evidence | Cadence |
|---|---|---|---|
| Java modules | `bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false` | Reactor tests pass, including auth, rules, Detection, Alert, incident, and shared error handling | Change CI; local shared/cross-module/persistence/event-flow change |
| Quality gate | `bash build/quality-gate.sh` or `powershell -File build/quality-gate.ps1` | Coverage floor, SpotBugs, toolchain policy, the shared `build/verify-repository.py` contract manifest, detection content, and workbench checks | Before merge; Change CI runs the same repository manifest |
| Dependency audit | PR Dependency Review; fallback `python3 build/verify-ossindex-audit.py` over the full Java runtime graph | High-severity findings fail; 401/429/timeout/missing/partial component reports also fail closed instead of becoming an empty green report | Dependency-manifest PR/weekly/release candidate |
| Workbench | `cd frontend/apps/workbench && pnpm test && pnpm lint && pnpm format:check && pnpm test:e2e && pnpm verify` | API contracts, style checks, cookie login, navigation permissions/history, SOAR draft/publish/run/human-gate flow, type check, production build, artifact assertions | Change CI; local selection above |
| Cross-cutting slice | `python build/verify-slice.py` | Authentication, tenancy, audit, rate limiting, and trace propagation | PR/release candidate |
| Event pipeline | `python build/verify-pipeline.py` | Canonical event -> Kafka -> Detection -> Alert persistence -> OpenSearch/ClickHouse/report | Middleware change/scheduled |
| Detection content | `python build/validate-detection-content.py` | Manifest schema, metadata, positive/negative vectors, ATT&CK references | Rule/content change |
| Investigation dataset | Maven dataset test + `python build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json` | Real evidence composer output satisfies versioned citation, timeline and human-approval oracles | Change CI; local investigation/evidence-composer/dataset change |
| Golden scenario | `python build/demos/golden-demo.py --transport ingest` | SSH brute force -> successful login -> privilege escalation -> multi-stage correlation -> entity risk -> Incident/Notify/SOAR | Manual/full-stack |
| Detection restart | `python build/demos/detection-recovery.py` | Kafka backlog grows while Detection is down and catches up after restart | Manual/weekly |
| Alert Web outage | `python build/chaos-pipeline.py --scenario alert_web_restart` | Detection Alert Outbox survives Alert Web outage and creates one alert after recovery | Manual/weekly |
| Detection Outbox replay | `python build/chaos-pipeline.py --scenario detection_outbox_replay` | Rewound publisher state returns to `PUBLISHED` and Alert Web retains one logical alert | Manual/weekly |
| Duplicate delivery | `python build/chaos-pipeline.py --scenario duplicate_delivery` | Same event produces one logical alert by source ID | Manual/weekly |
| Collector identity | `SOCP_COLLECTOR_CREDENTIALS` + direct ingest probe | A registered collector is bound to one tenant; a user JWT or mismatched collector/tenant is rejected | PR/security change |
| PostgreSQL outage | `python build/chaos-pipeline.py --scenario postgres_outage` | Kafka lag grows while durable completion is unavailable, then drains with no pending journal rows | Manual/weekly |
| OpenSearch outage | `python build/chaos-pipeline.py --scenario opensearch_outage` | Detection remains available while search is degraded and indexing works after recovery | Manual/weekly |
| Indexer failure semantics | `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/search-config -am test -Dtest=OsIndexerFailureContainerTest -Dsurefire.failIfNoSpecifiedTests=false` | Partial mapping failure, DLQ outage, retryable 503, post-write replay, real commit failure, and unique source-offset reconciliation | Middleware change/CI integration |
| Detection same-session recovery | `bash build/mvnw.sh -pl services/detect-web -am test -Dtest=DetectionRecordProcessorTest,KafkaEventConsumerTest,DetectionDeadLetterJournalTest -Dsurefire.failIfNoSpecifiedTests=false` | DB claim/sink/finalization failures stay retryable, attempt-round exhaustion recovers without rebalance, DLQ outage recovers, offset gaps stay pinned, stale owner cannot finalize, PENDING prefetch remains bounded | Detection consumer/persistence change |
| Search retention PostgreSQL | `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/search-config -am test -Dtest=SearchConfigPostgresMigrationTest -Dsurefire.failIfNoSpecifiedTests=false` | Actual repository delete SQL migrates cleanly, skips competing row locks, preserves unresolved outbox-linked events, and drains eligible backlog in bounded batches | Search persistence/retention change |
| Secondary-analysis PostgreSQL upgrade | `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/detect-web -Dtest=SecondaryAnalysisPostgresMigrationTest test -Dsurefire.failIfNoSpecifiedTests=false` | Existing V1 rows upgrade through V4, tenant backfill is preserved, and receipt identity remains unique after detect-model consolidation | Detection persistence change/CI integration |
| Routed multi-instance | `SOCP_DETECT_INPUT_TOPIC=socp-detection-routed-v2 SOCP_DETECT_ROUTING_MODE=primary SOCP_DETECT_OUTPUT_MODE=primary SOCP_DETECT_ROUTING_PUBLISHER_ENABLED=true bash build/detection-cluster.sh start && RECOVERY_TOPIC=socp-detection-routed-v2 python build/chaos-pipeline.py --scenario multi_instance --rebalance-cycles 3` | Exactly 3 Detection instances / 6 routed partitions, disjoint ownership before/after repeated rebalance, independent exact alert-ID oracle, same-user correlation across different IP/host, same username isolated across tenants, one canonical source receipt per source position, bounded fan-out <= 5, distinct delivery journal identities, zero routed/source lag, no duplicates, and `pendingEvents == 0` | Weekly/release candidate |
| Routed migration integrity | `python build/chaos-pipeline.py --scenario routed_migration` (same routed cluster env as above) | Republished canonical events create new source receipts but one delivery identity and one alert; an ACTIVE stateful rule with `routingField != groupBy` is reported UNSUPPORTED per rule, keeps canonical offsets uncommitted, produces no phantom alerts, and removal drains the deferred work to the exact oracle | Weekly/release candidate |
| Routing rollback | `python build/chaos-pipeline.py --scenario routing_rollback` (same routed cluster env as above) | Legacy-input restart keeps the formal alert path exact, reports `LEGACY_PARTIAL` rather than cross-dimension completeness, and the routed generation restores and drains afterwards | Weekly/release candidate |
| Full API | `python build/verify-full.py` | Resource CRUD, tenancy, import/export, threat, and response contracts | Scheduled/release candidate |
| OpenAPI SDK | `python build/verify-openapi-sdk.py` (add `SOAR_OPENAPI_REQUIRE_RUNTIME=true` and runtime URLs for deployment mode) | 71-operation TypeScript SDK generation, strict compilation, runtime `/v3/api-docs` parity, `SOCP_SESSION`, `X-Tenant-Id`, `ApiResult`, ETag/If-Match, status codes, and error envelopes | Every API change/release candidate |
| Actuator boundary | `python build/verify-actuator-auth.py` | Gateway health remains probeable while info, metrics, and route metadata return 401 without credentials | Full-stack/release candidate |
| Runtime consolidation policy | `python build/verify-runtime-consolidation.py` | No fixed process target is configured and logical domains/candidates match the executable registry | Every topology change |
| Candidate consolidation | `python build/verify-runtime-consolidation.py --candidate NAME --require-evidence --evidence PATH` | One registered candidate has commit-matched context, transaction, failure-isolation, and capacity evidence | Before changing that candidate's runtime placement |
| Helm release | `python build/verify-helm.py` | Dev/staging/production profiles render four digest-only images into six hardened workloads with the expected HPA/PDB, runtime roles, and service routes; startup/liveness probe paths target the bounded health group and the readiness timeout covers the pinned HikariCP connection wait | Every Kubernetes release change |
| Production-shaped orchestration | `python build/verify-prod-compose.py` | The effective base+overlay Compose merge (rendered with `docker compose config` when available) carries each application service's full ProdGuard prerequisite set with `:?`-required secrets, Redis `noeviction`+`requirepass`, a declared Kafka posture with no undeclared listener, and Helm probe/migration-role parity | Every Compose/Helm production-artifact change |
| SOAR live | `python build/verify-soar-live.py` | Real PostgreSQL/Temporal Run completion, Alert-shaped event admission, receipt idempotency, and two-instance capacity fence | Weekly/release candidate |
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
- Routed multi-instance assignments are disjoint across exactly six partitions;
  every supported stateful grouping dimension is routed independently, so
  cross-IP/host user correlation remains complete across three replicas and
  repeated rebalances. The oracle is an explicit expected alert-ID set, not a
  comparison against a single-instance implementation.
- Canonical source receipts and routed delivery journal rows retain separate
  source/delivery Kafka positions. Duplicate source records at different
  offsets reuse the same delivery identities, while same-named entities in
  different tenants never share state.
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
repeatable local baseline, not a production throughput or HA claim.

Do not commit local usernames, absolute paths, hardware identifiers, email
addresses, tokens, passwords, or machine-specific screenshots.

## CI ownership

Push and pull-request CI runs the Java suite, opt-in Testcontainers contracts,
frontend contracts/build, the minimal service slice, and the Kafka pipeline.
PRs run duplicate-delivery and Detection Outbox replay Chaos; manual Change CI
can repeat those deterministic invariants. The full-stack workflow is
manual/weekly, starts the fixed three-instance cluster, and runs full API,
pipeline, process/database/OpenSearch outage, Golden Demo, Detection recovery,
multi-instance/rebalance, attack scenarios, and dependency failure checks with
logs and JSON evidence uploaded as artifacts.
