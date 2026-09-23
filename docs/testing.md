# Testing Guide

Testing is organized around the event pipeline and the boundaries where a
failure would be expensive to diagnose. Fast module tests cover local
behavior; Python checks exercise running services and middleware.

Choose checks with the [local change matrix](validation-matrix.md#local-change-validation).
The commands below are a catalog, not a sequence to run for every task. CI and
release gates retain their broader coverage.

## Rule catalogue verification

Run `bash build/mvnw.sh -pl services/detect-web -am test
-Dtest=RuleCatalogPersistenceTest,RuleCatalogStoreTest,RuleControllerTest
-Dsurefire.failIfNoSpecifiedTests=false` for bounded queries, tenant binding,
projection limits, literal filters, and HTTP validation. With a disposable local
Docker target, set `SOCP_TESTCONTAINERS=true` and run the same command with
`-Dtest=RuleCatalogPostgresTest` to exercise PostgreSQL and a populated V24→V25
upgrade. V25 preserves original JSON and backfills legacy content defaults.

Workbench `pnpm test` limits jsdom execution to two workers so parallel Java
and browser checks do not multiply Vue/Element Plus memory use across every
available CPU. It includes catalogue paging, stale replies, compliance ID
batches, and ACTIVE coverage checks. `pnpm exec playwright test
e2e/rule-catalog.spec.ts` covers a 501-rule catalogue, page-size changes, direct
editor reload, return filters, and remote alarm-rule selection. Browser fixtures
intercept only declared API paths and reject unexpected backend requests.

`python build/verify-migrations.py` checks shared SQL/Java version histories and
published migration immutability; `python -m unittest
build.tests.test_verify_migrations` covers nested Java locations and history
rewrites. These static checks complement actual Flyway upgrade tests.

## Entity-risk query verification

`EntityRiskQueryPersistenceTest` and its opt-in `EntityRiskQueryPostgresTest`
subclass execute real ranking/aggregate SQL. They put 501 older high-score
profiles ahead of a fresh lower stored score, check deterministic displayed
risk ties, tenant isolation, one-result hydration and zero-entity summary
hydration. They also cover empty tenants, future/ancient timestamps, tiny
scores and public level thresholds. Run `SOCP_TESTCONTAINERS=true bash
build/mvnw.sh -pl services/detect-web -am test
-Dtest=EntityRiskStoreTest,EntityRiskQueryPersistenceTest,EntityRiskQueryPostgresTest,UebaControllerTest
-Dsurefire.failIfNoSpecifiedTests=false` against a disposable local Docker target.

`EntityRiskCounterPersistenceTest` exercises 100 distinct long rule names,
duplicate receipts under competing profile locks, one-time legacy conversion,
transaction rollback/replay, counts above the signed 32-bit range, and exact
control/Unicode keys. `EntityRiskCounterPostgresTest` inherits these cases and
adds a populated V30-to-V31 upgrade, a concurrent write between detail queries,
restricted-role RLS and cross-tenant foreign-key checks. Run the command above with
`-Dtest=EntityRiskCounterPersistenceTest,EntityRiskCounterPostgresTest` for both
databases. All fixtures use disposable databases; they do not migrate or clean
an existing development or production deployment.

AI answer cancellation is covered by `AiAssistantView.component.test.ts` in
`pnpm test`: reset discards late successes/errors without clearing a newer
request's loading state, and leaving the view aborts pending work.
`pnpm exec playwright test e2e/ai-reset.spec.ts` checks reset followed by a new
question with a delayed HTTP response and rejects unexpected backend requests.

AI investigation context tests in the same component suite cover route reuse,
draft invalidation, legacy query aliases, late polling/append results and
foreign-alert responses. `OverviewRefresh.component.test.ts` uses a real Vue
Query client to verify initial source failure, pending refresh, partial failure
and recovery timestamps. Browser checks are `e2e/ai-investigation-context.spec.ts`
and `e2e/overview-refresh.spec.ts`; both use explicit endpoint fixtures and reject
unexpected backend calls. Navigation and alarm-drawer tests verify that AI
entry points follow the admin/analyst role boundary.

`ReportView.component.test.ts` covers independent report-source failure/retry,
INFO counts, late responses after unmount, archive date races, generated-date
selection and signed download URL handling. Run `pnpm exec playwright test
e2e/report-sources.spec.ts` for the capped 500-file listing, dated filtering,
download tab with a detached opener, generation result visibility and mobile
chart layout. Browser fixtures reject unexpected backend requests and serve
the downloaded report locally; no real report generation or object-store write
occurs in this check.

## Test environments

`IngestBodyLimitAdviceTest` checks bounded raw reads, chunked/underreported
lengths and method/class limits without weakening collector limits.
`RuleControllerTest` checks the real manual Detection routes reject oversized
JSON/UTF-8 before engine admission, retain the 413 envelope, and reject viewer
writes before conversion. Run `bash build/mvnw.sh -pl services/detect-web -am
test -Dtest=IngestBodyLimitAdviceTest,RuleControllerTest
-Dsurefire.failIfNoSpecifiedTests=false` for these admission checks.

Local build/test authorization is defined in
[AGENTS.md](../AGENTS.md#scope-and-autonomy).

Do not assume every test command is isolated: service probes accept endpoint
overrides, Compose can reuse persistent volumes, and chaos checks restart
services or mutate data. Inspect the selected command's target configuration
and use disposable local fixtures. Testcontainers is appropriate for isolated
middleware checks when Docker is available. Production/shared targets and
deletion of persistent data follow the authorization boundary in `AGENTS.md`.

## Local checks

```bash
# Java reactor tests
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false

# Local quality gate: coverage, repository contracts, SpotBugs, and frontend
bash build/quality-gate.sh

# Native Windows equivalent; both wrappers consume build/verify-repository.py
powershell -File build/quality-gate.ps1

# Owning backend module and its dependencies (replace with the changed module)
bash build/mvnw.sh -pl services/soar-web -am test -Dsurefire.failIfNoSpecifiedTests=false

# Workbench contracts, type check, production build
(
  cd frontend/apps/workbench
  pnpm test
  pnpm lint
  pnpm format:check
  pnpm verify
  pnpm test:e2e  # user-flow changes; full suite in CI
)

# OpenAPI snapshot -> TypeScript SDK generation and strict compilation
python build/verify-openapi-sdk.py

# Deployment-backed Actuator boundary (requires a running gateway)
python build/verify-actuator-auth.py

# Helm render and deployment invariants (requires Helm 4)
python build/verify-helm.py

# Workflow syntax, and the static production delivery contract (requires
# actionlint). Run it with no arguments so it lints every workflow, which is
# what CI does.
actionlint
python build/verify-production.py

# Production-shaped orchestration contract: asserts the *effective* base+overlay
# Compose merge (via `docker compose config` when the CLI is available), Redis
# noeviction/auth, the declared Kafka posture, and Helm probe/migration-role
# parity. It is the merged-config gate `verify-production.py` no longer
# duplicates. Compose files only; Docker is optional.
python build/verify-prod-compose.py
```

After `mvn package`, `python build/verify-jars.py` verifies every registered
service artifact against its Maven artifact name/version and checks the Boot
launcher, application class, and packaged dependencies. Empty or partial output
fails. This checks archive structure; application startup still needs boot tests.
The actuator probe requires HTTP 401 on protected management endpoints and
HTTP 200 or 503 on public health; a missing health route or generic server error
is a failed probe.

`pnpm verify` runs the workbench type check and Vite build, then verifies the
expected production artifact structure. `pnpm test` covers frontend API,
navigation, resource-list, and resource-import contracts, the URL-synced list
query composables (`useListQuery`, `useAlarmQuery`), and the keyboard row
activation cell (`RowActivate`) used by the detail-on-row-click tables.
`pnpm test:e2e`
uses Playwright to cover cookie-backed login, viewer navigation denial, deep
links, browser history, search draft/applied-query separation, fixed-time
investigation links, field pivots, historical alarm evidence navigation, and
reference-set batch deletion with a fixed target and a drawer that remains
open until the write finishes (`e2e/refset.spec.ts`), plus
the SOAR draft/publish, run-inspection,
approval, and manual-task browser flow (`e2e/soar.spec.ts`).

The browser fixtures explicitly model gateway and service endpoints. Any
newly introduced backend request that is not
handled by the test is aborted and fails the test, so mocked UI coverage does
not silently drift away from the API contract. To run the additional browser
smoke against a live gateway, set `SOCP_E2E_BACKEND_URL` (for example
`http://127.0.0.1:18092`) before `pnpm test:e2e`; the smoke accepts either a
200 authenticated session or the expected unauthenticated 401 response.

## Test ownership

- Workbench `scripts/resource-import.test.ts` and `src/lib/ioc-import.test.ts`
  cover byte/row bounds, multiline CSV quoting, malformed records and complete
  indicator validation. `e2e/threat-intel.spec.ts` covers unconfirmed import
  previews, duplicate-write/close protection, stale lifecycle responses and
  mobile drawer bounds. Run it with the asset flow in `e2e/workbench.spec.ts`
  when changing the shared parser; both fixtures reject unhandled backend calls.
- `EndpointForwardingPersistenceTest` / `EndpointForwardingPostgresTest` verify
  atomic history/heartbeat/outbox admission, scoped producer-request keys,
  concurrent independent writers, full-queue replay, content conflicts, claim
  fencing, retention and populated V3/V4 upgrades. The PostgreSQL subclass also
  checks key lookup and global quotas through a NOSUPERUSER/NOBYPASSRLS role.
  Run `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/hips-web -am test
  -Dtest=EndpointForwardingPersistenceTest,EndpointForwardingPostgresTest,EndpointCollectionControllerTest,EndpointForwardingRuntimeTest
  -Dsurefire.failIfNoSpecifiedTests=false` against a verified disposable Docker
  target. Controller tests exercise real collector/signed-service authentication;
  the runtime test boots HIPS, retries a committed producer request, and observes
  scheduled recovery with one history row and unchanged downstream payload/key.
  See [endpoint forwarding](operations/endpoint-forwarding.md) for the retained
  receipt window and required writer shutdown during V5 rollout.
  The same persistence fixtures verify bounded history deletion, all-state
  receipt protection, exclusive age boundaries, rollback, locked-row skipping
  and yielding to admission. `EndpointHistoryRetentionWorkerTest` checks batch
  and time budgets, system-scope restoration and metric truth;
  `EndpointHistoryRetentionRuntimeTest` observes actual scheduled cleanup while
  forwarding is paused, preserving both recent history and unresolved evidence.
- `NotificationPersistenceTest` / `NotificationPostgresTest` verify delivery
  claims, stale callbacks, receipt rollback, tenant boundaries, concurrent channel
  quotas, database paging and populated V4→V5 migration. `NotificationExecutorTest`
  proves admission remains bounded after a caller times out; the SMTP test uses
  a silent local socket, and `SocpHttpClientExternalTest` verifies single-attempt
  connector calls despite configured global retries. See the
  [notification runbook](operations/notification-delivery.md) for limits and upgrade requirements.
- `SoarMaintenancePersistenceTest` and `SoarMaintenancePostgresTest` check
  cancellation/recovery scheduling without falsifying business progress, competing
  replicas, lock skipping, fairness beyond the first 100 runs, cancellation truth,
  action uncertainty, per-record rollback, and a newer activity result arriving
  during Describe. Run the PostgreSQL suite with `SOCP_TESTCONTAINERS=true bash
  build/mvnw.sh -pl services/soar-web -am test -Dtest=SoarMaintenancePostgresTest
  -Dsurefire.failIfNoSpecifiedTests=false`. `TemporalMaintenanceDeadlineTest` uses
  a real in-process gRPC transport with a silent server to verify 3-second Describe
  and cancellation deadlines; it also distinguishes typed workflow absence from
  namespace failure over the wire. The SDK's own test service verifies open and
  closed workflows and emits a bare NOT_FOUND for missing IDs, which remains
  UNKNOWN rather than being treated as authoritative absence.
- `SoarDispatchClaimPersistenceTest` verifies atomic run/outbox updates, rollback
  on a rejected projection write, competing replicas, stale callbacks after
  lease recovery, cancellation fences, bounded recovery, crash attempt limits,
  and progress while another tenant holds a lock. `SoarDispatchClaimPostgresTest`
  repeats the checks on PostgreSQL. Use `SOCP_TESTCONTAINERS=true bash build/mvnw.sh
  -pl services/soar-web -am test -Dtest=SoarDispatchClaimPostgresTest
  -Dsurefire.failIfNoSpecifiedTests=false`. The real worker test also asserts that
  its Temporal call runs outside a database transaction. Worker unit tests reject
  corrupted resume data/budgets and acknowledge only typed matching duplicates.
- `SoarSignalClaimPersistenceTest` verifies version-fenced claims and completion,
  replacement decisions, manual requeue, attempt exhaustion, bounded lock-skipping
  recovery, and the real signal worker on H2. `SoarSignalClaimPostgresTest` repeats
  these checks on PostgreSQL. Run with `SOCP_TESTCONTAINERS=true bash build/mvnw.sh
  -pl services/soar-web -am test -Dtest=SoarSignalClaimPostgresTest
  -Dsurefire.failIfNoSpecifiedTests=false`. `TemporalWorkflowIdentityTest` uses
  Temporal's in-process test service to reject a duplicate start both while open
  and after completion, while allowing a new SOAR run ID. It also delivers all
  nine cancellation/approval/manual-task/resolution signal variants through the
  real SDK/client (including cancellation RPC and existing-workflow stubs) and
  checks the received arguments. This is
  not deployment retention or HA evidence.
- `RuleHistoryPersistenceTest` checks metadata-only SQL projections, history
  paging beyond 100 revisions, bounded legacy responses, immutable detail reads,
  stable conflict ordering, and tenant isolation. `RuleHistoryPostgresTest`
  repeats them on PostgreSQL, including V30 index creation. Run it with
  `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/detect-web -am test
  -Dtest=RuleHistoryPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`.
- `socp-auth` and `api-gateway`: JWT configuration, production guard, missing
  credentials, collector identity/tenant binding, service-only endpoint
  denial, viewer write denial, tenant propagation, and trace headers.
- `socp-rule` and `detect-web`: rule evaluation, suppression, hot reload,
  routing keys, partition restore, event de-duplication, queue backpressure,
  malformed events, Detection Alert Outbox retry, typed dependency failure
  classification, same-session retry recovery, contiguous offset gaps,
  ownership fencing, and bounded PENDING replay.
  `DetectionRouteOutboxPublisherPersistenceTest` covers route publication
  attempt fencing, retry exhaustion, and bounded stale recovery on H2;
  `DetectionRouteOutboxPostgresTest` repeats those checks on PostgreSQL and
  adds competing claims and row-lock recovery. Run the latter with
  `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/detect-web -am test
  -Dtest=DetectionRouteOutboxPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`.
  `DetectionRouteSourceConsumerTest` verifies restart backoff across Kafka
  sessions and commits only after durable routing succeeds.
  `DetectionAlertOutboxPersistenceTest` checks claim-token fencing after expiry
  and manual requeue, delivery-stage preservation, and bounded recovery on H2.
  `DetectionAlertOutboxPostgresTest` repeats those cases on PostgreSQL and adds
  competing claims, lock-skipping recovery, and a populated V23-to-V24 upgrade.
  Run it with `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/detect-web
  -am test -Dtest=DetectionAlertOutboxPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`.
  `DetectionAlertOutboxPublisherTest` checks acknowledgement ordering, tenant
  scope, retry stages, and capacity acquisition before claiming a queued row.
  `PersistentWatchlistStateStoreTest` checks concurrent append/create, first-writer
  races, namespace quotas, post-commit cache visibility, and weighted cache
  limits on H2. `PersistentWatchlistPostgresTest` repeats those contracts with
  PostgreSQL and upgrades 102 populated V26 rows through V27/V28, preserving
  values and tombstones while backfilling counts. Run it with
  `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/detect-web -am test
  -Dtest=PersistentWatchlistPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`.
  Workbench `e2e/watchlists.spec.ts` verifies compact catalogue requests,
  pagination, lazy detail reads, and append acknowledgement without a catalogue
  reload, plus retained create input after a server-side name conflict and a
  successful rename/retry. `WatchlistCreateContractTest` covers the additive
  create endpoint, viewer denial, bounded members, and names that remain
  addressable through path endpoints. Component tests cover detail failures,
  stale responses, and retained input after a failed append.
  `scripts/UebaView.component.test.ts` covers delayed entity selection, close
  and unmount cancellation, input-time score invalidation and local retries.
  `e2e/ueba-requests.spec.ts` exercises these boundaries through the browser
  with delayed/failed HTTP reads and 390px detail/score screenshots.
  Shared `RuleDependencyFailureTest` retries ten times beyond the rule-fuse
  threshold, verifies whole-event state rollback and recovery, and proves a
  timing observer cannot isolate a healthy rule. `DetectionDeadLetterJournalTest`
  connects the real evaluator to a persisted journal and keeps dependency
  failures PENDING until recovery; these failures are not poison-record DLQ cases.
  `RuleCatalogCoordinationTest` covers concurrent first installation and edits,
  transaction-owned namespace locks, revision/outbox rollback, deletion intent,
  and content upgrades racing with analyst customization. The corresponding
  `RuleCatalogCoordinationPostgresTest` repeats these checks on PostgreSQL:
  `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/detect-web -am test
  -Dtest=RuleCatalogCoordinationPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`.
- `search-config`: canonical event plus Ingestion Outbox creation, authenticated
  collector identity, optimistic publication claims, broker acknowledgement,
  stable OpenSearch document IDs, partial bulk failure,
  index-before-offset completion semantics, bounded local-cache warm-up, and
  PostgreSQL retention catch-up/locking semantics.
- `alert-web`: create validation, source-alert idempotency, paged query
  contracts, transactional Alert Outbox creation, broker-ack publishing,
  optimistic claim/stale recovery, post-commit enrichment scheduling, pending
  retry, disposition, and fan-out isolation.
  `AlarmDeliveryPublisherTest` also checks that connector and acknowledgement
  exceptions update retry/DEAD state within the delivery tenant context.
  Ingestion, Alert event/delivery, and rule-change publisher tests also verify
  token propagation and exclusion of overlapping local drains. Their
  `*ClaimPersistenceTest` contracts cover stale callbacks after recovery or
  manual requeue, concurrent claims, bounded maintenance, and competing locks.
  The corresponding `*ClaimPostgresTest` subclasses repeat these contracts
  with PostgreSQL and verify populated Search V11, Alert V20, and Detection V25
  upgrades. Run `SOCP_TESTCONTAINERS=true bash build/mvnw.sh
  -pl services/search-config,services/alert-web,services/detect-web -am test
  '-Dtest=*ClaimPostgresTest,OutboxDeliveryExecutorTest'
  -Dsurefire.failIfNoSpecifiedTests=false` against disposable local Docker.
- `incident-web` and `soar-web`: case validation, merge/idempotency, and
  Temporal or local fallback behavior.
- Resource services: import, create/update, validation, pagination, and
  tenant-scoped access for assets, cases, ATT&CK techniques, and IOCs.

The suite is risk-driven and enforces an aggregate Java line-coverage floor of
50%, a 25% floor for every production module, and explicit floors of 45% for
`detect-web`, `alert-web`, and `search-config`, plus 40% for `incident-web`,
`soar-web`, and `socp-client`. Every module containing production Java must
emit a coverage report. The floor is intentionally a baseline, not a target:
behavior or failure-semantic changes still need tests at their owning boundary.
Reports missing a module-level line counter, containing no measurable lines,
or older than that module's Java sources fail the gate. Module-specific floor
overrides must be finite ratios between zero and one.
Run `mvn test -Pcoverage` followed by `python build/verify-coverage.py` to
inspect the per-module and aggregate result. `mvn verify -Pquality -DskipTests`
enforces JDK/Maven policy and high-confidence SpotBugs findings.

`build/verify-migrations.py` rejects duplicate/misnamed migrations, version
gaps, unmarked destructive statements, missing Flyway wiring, and tenant JPA
entities without a `tenant_id` migration. It aggregates independent Flyway
locations owned by one service, such as Detection's secondary-analysis schema,
without merging their version histories. `build/verify-contracts.py` keeps the
Maven service modules, default process list, logical-domain assignment,
optional consolidation candidates, unique ports, gateway routes, legacy
collector rewrites, and frontend health registry aligned.

Changed-line coverage is fail-closed when CI supplies a non-zero base commit:
an invalid or unavailable base is an error, not a successful skip. An all-zero
initial-push base intentionally falls back to the current `HEAD` diff.
Changed source files must appear in a report generated after the source was
last modified. Missing or stale coverage is an error, even if every other
changed line is covered. A source entry with no executable lines is allowed;
`package-info.java`, `module-info.java`, and pure line deletions are exempt.
Timestamp checks catch stale local artifacts; they are not a cryptographic
binding of coverage to a commit. CI still generates coverage from its checkout.

## Validation artifact collection

`python build/collect-evidence.py --output .cache/evidence --require-tests`
collects the available local Surefire reports into a directory named for the
checkout's commit. The build CI job requires at least one executed test;
JAR-only integration jobs can omit that flag. No reports is `not-run`, and an
all-skipped suite is `skipped`, never proof of test execution. Malformed XML,
missing counters or counters that disagree with testcase outcomes make
collection incomplete and return a nonzero exit code. Valid report counts remain
available but are explicitly partial when another report is invalid.

Each `--benchmark path.json` is an expected input. Missing, malformed, empty,
oversized or explicitly different-commit reports are recorded as unavailable
and fail collection instead of disappearing from the summary. The four existing
artifacts remain `summary.json`, `test-summary.json`, `environment.json` and
`benchmark-summary.json`. Report metadata adds source paths, SHA-256 hashes and
modification times; reads are bounded to 32 MiB per artifact.

`collectionStatus=complete` and exit code 0 describe successful collection, not
passing tests. Inspect `test-summary.json.status` and the benchmark outcomes.
`worktreeDirty` records whether the checkout differs from HEAD. Available local
reports can include stale or focused runs, so `currentRunVerified` remains false:
the collector does not certify the full reactor, report freshness, or execution
against the named commit. Use the actual job/build logs and owning freshness
gates for those claims. `instances` counts distinct configured Detection URLs,
or is null when none are supplied; it is not observed cluster membership.

Run the collector's damaged/missing-input, counter-integrity and provenance
regressions with `python -m unittest discover -s build/tests -p test_collect_evidence.py`.

## Integration checks

Start the required Docker middleware and backend slice before running these:

```bash
python build/verify-slice.py
python build/verify-pipeline.py
python build/verify-full.py
python build/verify-openapi-sdk.py  # add runtime URL/env for live SDK smoke
python build/verify-soar-live.py  # requires PostgreSQL + Temporal; set a second SOAR URL for race evidence
python build/demos/golden-demo.py --transport ingest
python build/demos/detection-recovery.py
python build/chaos-pipeline.py --scenario alert_web_restart
python build/chaos-pipeline.py --scenario duplicate_delivery
python build/chaos-pipeline.py --scenario detection_outbox_replay
python build/failure-tests.py
python build/validate-detection-content.py

# Failure/recovery semantics touched by Detection persistence or consumer changes.
bash build/mvnw.sh -pl services/detect-web -am test \
  -Dtest=DetectionRecordProcessorTest,KafkaEventConsumerTest,DetectionDeadLetterJournalTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Real PostgreSQL syntax/transaction/locking evidence for search retention.
SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/search-config -am test \
  -Dtest=SearchConfigPostgresMigrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Fail-closed dependency-audit response fixtures (success, vulnerability, 401,
# 429, timeout, missing report, and partial report).
python3 -m unittest build/tests/test_verify_ossindex_audit.py
python build/verify-investigation-dataset.py
python build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json
```

The slice verifier checks the current second-tenant write acknowledgement and
its returned alarm ID in that tenant's list, rather than accepting an older
alarm with a fixed rule ID. Its isolated fixture regression is
`python -m unittest discover -s build/tests -p test_verify_slice.py`; the
fixture rejects that write while leaving matching historical data, rejects
the first-tenant list read, and checks the successful path without contacting
any live service.

`verify-pipeline.py` injects one event through the ingestion task-test endpoint.
Run it against a disposable local/CI stack: downstream rules and alarm delivery
remain active. It requires the packaged `AUTH-PRIVESC` rule to be ACTIVE, an
existing ingestion task, and `kafka-python==3.0.10` (the CI-pinned version).
`PIPELINE_TENANT` defaults to `default` and must match the authenticated user;
`PIPELINE_TOPIC` defaults to `socp-events`. Existing gateway, Kafka, OpenSearch,
ClickHouse and credential overrides remain supported.

The verifier captures each Kafka partition's end before injection, then matches
the generated event ID, tenant, host, source and message in Kafka and OpenSearch.
Alert's event lookup must return that event's `AUTH-PRIVESC` alarm; ClickHouse
must contain the same tenant/alarm ID. Unrelated traffic, old alerts and global
count growth cannot satisfy these checks. The daily report must identify
ClickHouse as its source and report no degradation. That final check proves
report availability, not an exact aggregate delta under concurrent traffic.
Kafka reads use manual assignment, no group or commits, no topic auto-creation,
and a 10,000-record / 64-MiB key-and-value scan bound. HTTP bodies are limited to
4 MiB. OpenSearch is polled without a cluster-wide refresh. HTTPS retains the
local fixture's self-signed certificate exception; this is not a production TLS
acceptance probe. It does not test the collector credential boundary or assert
exactly-once delivery; those have separate owning-service and chaos checks.

The probe has isolated HTTP regressions plus an opt-in real Kafka client test:

```bash
python -m unittest discover -s build/tests -p test_verify_pipeline.py
# Install the same locked package as pipeline CI in your test environment.
python -m pip install kafka-python==3.0.10
# Requires the catalog Kafka image already cached and a local Docker socket.
SOCP_PIPELINE_KAFKA_TESTS=true python -m unittest discover -s build/tests \
  -p 'test_verify_pipeline*.py'
```

The opt-in test starts and removes its own Kafka container, with no mounted data
and a loopback port. It verifies real record identity, pre-injection cursor
exclusion, absent-topic behavior and the absence of consumer-group membership.
The HTTP fixtures prove verifier decisions; they do not replace the live SIEM
pipeline run above.

The investigation evaluator uses the versioned fixture as its independent
oracle: fixture search events mirror captured evidence, and IOC matches come
from the alert/evidence indicator fields. Output cannot authorize its own
citations by inventing related events or IOC matches. Timeline identities and
facts must match that fixture, every SOAR suggestion requires human approval,
and executable/automatic actions fail evaluation. Duplicate case IDs, malformed
collections, and missing/extra results also fail. These checks exercise the
real deterministic evidence composer; they do not establish free-text LLM
accuracy or live-tool correctness. A different tool fixture needs a versioned
oracle update. `python -m unittest discover -s build/tests
-p test_investigation_evaluation.py` covers fabricated output and dataset
validation with Python optimization enabled.

The opt-in Testcontainers contract suite is in `platform/socp-test`, with the
Kafka-to-OpenSearch failure suite owned by `services/search-config`. Together
they prove the PostgreSQL uniqueness boundary, Kafka commit/replay semantics,
ClickHouse logical uniqueness under duplicate inserts, OpenSearch deterministic
IDs and partial bulk failure, DLQ acknowledgement boundaries, retryable 503,
and commit failure after write acknowledgement. Set `SOCP_TESTCONTAINERS=true`
when Docker is available; CI enables it, while local runs without Docker skip
only these integration tests. See [the failure matrix](chaos/README.md) for the
focused indexer command and reconciliation formula.

DLQ operator transport has Python tests in `build/tests/test_replay_dlq.py`
(`python -m unittest discover -s build/tests -p test_replay_dlq.py`).
`DlqReplayTransportContainerTest` in `platform/socp-test` runs the real Python CLI
and Java source bridge against disposable Kafka, then independently reads bytes,
nulls, empty values, duplicate headers and restored envelope partitions. It also
checks source cursors, empty/absent topics, disabled commits and absent target
refusal. Run with Python on PATH, Java 21 and `SOCP_TESTCONTAINERS=true`:
`bash build/mvnw.sh -pl platform/socp-test -am test -Dtest=DlqReplayTransportContainerTest
-Dsurefire.failIfNoSpecifiedTests=false` (Windows: `./build/mvnw.ps1` with the same
arguments). This verifies broker transport, not a downstream detection recovery;
use the [DLQ runbook](operations/dlq-replay.md) for terminal-journal constraints.

Rule authoring has H2/PostgreSQL concurrency coverage in
`RuleCatalogCoordinationTest` / `RuleCatalogCoordinationPostgresTest`: competing
conditional writers, revision/outbox rollback, stale delete/restore, delete/recreate,
tenant-bound tokens, create collisions and lifecycle guards. `RuleControllerTest`
and `RuleWriteConditionTest` cover ETag/error envelopes and bounded strong-tag
preconditions. The focused command is
`bash build/mvnw.sh -pl services/detect-web -am test -Dtest=RuleCatalogCoordinationTest,RuleCatalogCoordinationPostgresTest,RuleControllerTest,RuleWriteConditionTest
-Dsurefire.failIfNoSpecifiedTests=false`, with `SOCP_TESTCONTAINERS=true` for PostgreSQL.
Workbench component tests cover retained drafts, independent version tokens,
history paging/selection and stale restores. With a disposable local web server,
`pnpm exec playwright test e2e/workbench.spec.ts -g 'rule editor|rule revision'`
exercises the real conflict/reload and history/restore interactions using blocked
unknown backend calls and explicit fixtures. `build/tests/test_rule_operator_clients.py`
verifies the demo's conditional lifecycle and chaos helper JSON transport without
running a failure injection or publishing demo events.

The middleware CI job also runs the Redis cross-replica revocation proof and
the PostgreSQL runtime-role/RLS proofs with `SOCP_TESTCONTAINERS=true`. The
repository quality-gate wrappers detect a usable Docker daemon and enable this
suite automatically; if Docker is unavailable they print an explicit warning
and run the hermetic tests only. Set `SOCP_TESTCONTAINERS=true` to require the
middleware suite (and fail if the Docker environment cannot satisfy it).

The middleware image catalog is [infra/middleware-images.env](../infra/middleware-images.env).
`bash build/compose.sh` passes it to Compose, and Testcontainers resolves
PostgreSQL, Kafka, OpenSearch, Redis, and ClickHouse from the same file. CI
starts its middleware through that wrapper as well; it does not maintain a
second set of service image tags. `build/verify-middleware-images.py` fails if
Compose, CI, probes, or tests bypass the catalog. The Kafka tests use the
Apache-compatible Testcontainers adapter, which reads the catalog's Apache
Kafka image in KRaft mode. The project pins Testcontainers 1.21.4 because the
Apache adapter is not available in the old 1.20.x line.

To upgrade a middleware image, change only the corresponding entry in the
catalog, then run the catalog gate and the Docker-backed tests. On Windows,
use `build/compose.ps1` for the same behavior. The wrappers clear inherited
`SOCP_*_IMAGE` variables because Compose otherwise lets a stale shell export
override `--env-file` and recreate version drift.

The full-stack job runs `build/verify-actuator-auth.py` after all services are
started. It treats a dependency-driven health `503` as valid, but fails if an
unauthenticated caller can read Actuator info, metrics, or route metadata.

The pipeline check confirms canonical event acceptance, Kafka delivery,
Detection, PostgreSQL alert persistence, OpenSearch indexing, ClickHouse
analytics, and report availability. The failure checks verify dependency
recovery. The Alert Web restart scenario specifically proves that a Detection
Alert Outbox row survives a downstream outage.

When local Compose ports differ from defaults, set `PIPELINE_OS` for
`verify-pipeline.py` and `FAILURE_OS_URL` for `failure-tests.py`. Both accept
the corresponding `*_OS_AUTH` variable. This keeps failure checks aligned
with the active Compose port mapping rather than a hard-coded host port.

Detection content is versioned in
`services/detect-web/src/main/resources/detection-content/manifest.json`.
Every entry carries owner, data-source, ATT&CK, positive, and negative
metadata. The Java contract test executes the vectors; the Python validator
provides a fast CI check. The state journal replay window defaults to 24 hours
and is configurable with `SOCP_DETECT_STATE_RETENTION`. Terminal cleanup uses
independent `SOCP_DETECT_STATE_COMPLETED_RETENTION` and
`SOCP_DETECT_STATE_DEAD_LETTER_RETENTION` clocks; pending rows are retained.

For cross-dimension multi-instance validation, use the routed topic and export
the fixture settings for both the launcher and every recovery command. Keep
the same authentication and PostgreSQL credentials as the disposable stack:

```bash
export SOCP_DETECT_INPUT_TOPIC=socp-detection-routed-v2
export SOCP_DETECT_ROUTING_MODE=primary
export SOCP_DETECT_OUTPUT_MODE=primary
export SOCP_DETECT_ROUTING_PUBLISHER_ENABLED=true
export SOCP_DETECT_CLUSTER_MIN_PARTITIONS=6
export RECOVERY_TOPIC=socp-detection-routed-v2
export DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082,http://127.0.0.1:38082
bash build/detection-cluster.sh start

python build/chaos-pipeline.py --scenario multi_instance --rebalance-cycles 3
```

Supplying `DETECTION_INSTANCE_URLS` keeps the verifier on the explicitly started
cluster. Without it, the verifier invokes the launcher itself; scoped variables
from an earlier command are no longer present and can select legacy defaults.
The launcher enforces a minimum partition count and does not shrink an existing
topic. The acceptance topology is exactly three Detection instances and six routed
partitions. The scenario uses an independent expected-alert oracle, exercises
same-user state across different IP/host values and tenant isolation, and
checks canonical source receipts, routed delivery identities, source/delivery
positions, bounded fan-out, repeated rebalance, zero lag and zero pending
journal work. See the [validation matrix](validation-matrix.md) for the full
pass criteria.
The cluster launcher waits for each old listener to release its port before a
generation switch; if a port remains occupied, the restart fails with that port
instead of accepting a partially restarted cluster.

Two further routed-migration scenarios run against the same cluster:

```bash
python build/chaos-pipeline.py --scenario routed_migration
python build/chaos-pipeline.py --scenario routing_rollback
```

`routed_migration` republishes completed business events at new canonical
offsets (more source receipts, unchanged fan-out delivery identities and no
duplicate alert),
then creates a valid TESTING rule with a new grouping dimension and verifies
activation conflicts with the pinned topology (409) without changing its
persisted specification. In the disposable Compose database, it
administratively injects that probe rule as ACTIVE with a `routingField` that
contradicts its `groupBy`, modeling a corrupt restore or an out-of-band write.
It proves the router still fails closed: `/routing-plan` reports the rule
as UNSUPPORTED with its reason, canonical offsets stay uncommitted, no alert is
pretended, and restoring the saved TESTING specification before conditional
deletion lets the deferred work drain to the correct oracle. Activation and
deletion use the current revision token in `If-Match`; restoring this deliberately
corrupted fixture is confined to the disposable Compose scenario. `routing_rollback` restarts the cluster on the legacy canonical input,
proves the formal alert path survives there while the deployment reports
`LEGACY_PARTIAL` (never cross-dimension completeness), and restores the routed
generation afterwards.
Rollback evidence preserves instance logs and the manifest before each
generation switch, plus the legacy Kafka offsets, journal rows, instance stats
and matching alerts under `.cache/chaos/rollback-*/`. Cleanup must not overwrite
the failed generation's diagnostic evidence when restoring routed mode.
Full-stack CI also repeats the historical failed rollback namespace to retain
the same entity and partition placement alongside each run's fresh dataset.
Golden Demo saves its canonical events, matching alerts, rules and runtime
state in `.cache/golden-demo/` on exit. `GOLDEN_DEMO_DB_EVIDENCE=true` adds a
read-only journal snapshot from the disposable `socp-postgres` container.
The independent dependency-failure phase still runs after a Golden Demo
failure; the failed demo continues to fail the overall job.

Chaos event injection is a data-plane operation. Set
`PIPELINE_COLLECTOR_ID`, `PIPELINE_COLLECTOR_TOKEN`, and
`PIPELINE_INGEST_URL` to use a registered collector; do not reuse a user JWT
for `/search-config/api/v1/ingest`.

The full API verifier reads case timelines through their paginated endpoint.
It publishes an isolated START-to-END SOAR playbook and an automation rule
matching only its probe entity before ingesting the alert. It checks the
service-signed alert reaches that version and completes through Temporal,
then deletes the trigger rule and archives the playbook while preserving run
evidence. No external response action is part of this fixture.
The full verifier and Golden Demo use `SOAR_VERIFY_USERNAME/PASSWORD`
(local default `admin/admin123`) only for SOAR provisioning. Business queries
retain the analyst session, and the full verifier also requires analyst
publishing to return 403. Dependency failure probes inherit `PIPELINE_OS`
and `PIPELINE_OS_AUTH`; explicit `FAILURE_OS_URL/AUTH` values take precedence.
The OpenSearch outage check requires Search Config liveness to remain 200
while readiness returns 503, then verifies actual indexing after recovery.
The single-consumer recovery demo runs before the routed cluster is started;
its canonical offset oracle must not be mixed with the routed generation.
Both recovery and chaos probes normalize Kafka's unset committed offset (`-1`)
to zero and sum lag per partition. Empty uncommitted partitions cannot create
phantom backlog, and one partition's high offset cannot hide another's lag.
The attack demo also runs before the routed cluster because it uses local
Detection HTTP ingestion and needs the contacted worker to own every shard.
It edits rules through PUT and uses the separate `rule:activate` administrator
transition for new rules. This local HTTP demonstration does not establish
Kafka transport coverage; the pipeline and routed chaos checks provide that.
After observing a new alert, it waits for that exact alert ID to appear in an
automatically created/merged incident. It never uses manual incident creation
to substitute for the asynchronous fan-out assertion.
The incompatible-rule activation probe uses `RULE_VERIFY_USERNAME/PASSWORD`
(local default `admin/admin123`) so its expected 409 tests the topology guard
after authorization; normal chaos queries retain `DEMO_USER/PASS`.
The ordered cross-dimension probe waits for the first user-dimension delivery
to complete in the journal before sending the second step. Their canonical
keys differ, so Kafka provides no cross-partition ordering guarantee. This
tests the detector's processing-order contract without claiming event-time
reordering support.

## CI ownership

CI jobs generate disposable credentials with `build/prepare-ci-credentials.py`
immediately after checkout. Values are shared through the runner's `GITHUB_ENV`
file and masked before later steps run; workflow files contain no fixed job
passwords or signing/collector secrets. Integration probes use the same
generated credentials as their services, and collector credentials expire
after one day. The build-only scope leaves application test user fixtures alone.

The attack demo honors the local Detection API's `503` plus explicit
`accepted=false`/`queue_full` response during rule reload. It follows
`Retry-After` with a bounded retry window, preserving the event ID and timestamp.
Authentication failures, ambiguous errors and persistent rejection still fail
the check; every scenario must produce a new matching alert and automatic case.

`.github/workflows/ci.yml` runs on pushes and pull requests to `main`, and
manually. It builds the Java reactor, enables the Testcontainers contract
suite, verifies the workbench, and runs a minimal service slice plus the Kafka
pipeline E2E job. Every Change CI trigger runs deterministic duplicate-delivery
and Detection Outbox replay evidence. Compose-dependent process/database/
OpenSearch outage checks are intentionally kept in the weekly full-stack job,
where the named services and volumes exist. Repository-level Python contracts
come from `build/verify-repository.py`, the same manifest used by both local
quality-gate wrappers.

`.github/workflows/full-stack.yml` runs manually and on the weekly schedule.
It starts the extended Compose profile and fixed three-instance Detection
cluster, runs full API and pipeline checks, the dependency-outage matrix, the
multi-instance/rebalance oracle, attack scenarios, and recovery demos, then
uploads diagnostic logs plus structured JSON evidence.

`.github/workflows/dependency-audit.yml` runs weekly or manually. It applies
OWASP Dependency-Check to the Java reactor and `pnpm audit` to the workbench;
pull requests also use GitHub dependency review to reject newly introduced
high-severity vulnerabilities.

### Report archive publication

`ReportControllerTest` checks one serialized daily/trend snapshot per PUT,
source failure before any write, and retries after a lost write acknowledgement
without overwriting earlier objects. `ReportArchiveAuthorizationTest` uses the
real auth interceptor to enforce archive-reader roles and authenticated tenancy.
`ReportObjectStoreTest` covers disabled/failing storage and bounded lazy listing.
Run these with `powershell -File build/mvnw.ps1 -pl services/report-web -am test -Dsurefire.failIfNoSpecifiedTests=false` (or the Bash wrapper).

For an actual storage round trip, provision a disposable MinIO container with
loopback-only port binding and temporary data, user `report-fixture` and password
`report-fixture-secret`. Set `SOCP_REPORT_STORAGE_TEST=true` and
`SOCP_REPORT_STORAGE_TEST_URL=http://127.0.0.1:<mapped-port>` for the same Maven
command. `ReportArchiveStorageIntegrationTest` creates its own random bucket,
publishes two concurrent snapshots and downloads both signed URLs, checking
complete contents and separate tenant-prefix listing. Remove the disposable
container and its temporary data afterwards. These credentials are test fixtures;
never point this check at a shared/production endpoint. The check does not prove
cross-source database snapshot isolation, exactly-once retries or storage HA.

### Compliance mapping request ownership

`ComplianceView.component.test.ts` covers initial unavailable counts, retained
results after failures at each read stage, invalid dates and cancellation between
framework/rule batches. The existing `RuleCatalog.component.test.ts` still checks
all 501 mapped IDs are resolved in batches of 100. Run `pnpm test` for these checks;
`E2E_PORT=4318 pnpm exec playwright test e2e/compliance-refresh.spec.ts` verifies
failed refresh, explicit recovery, changed ACTIVE-rule status and mobile error
visibility with local HTTP fixtures.

### ATT&CK catalogue and note requests

`AttackView.component.test.ts` covers filter response races, retained coverage,
positive ACTIVE-technique membership, cancelled coverage chains, closing and
reopening a loading note, and correcting/retrying failed note saves while
pending writes stay guarded. Run `pnpm test` for these checks and
`E2E_PORT=4318 pnpm exec playwright test e2e/attack-notes.spec.ts` for the browser
coverage-failure/note-save-retry flow and mobile note-dialog screenshot.

### ATT&CK alert aggregation

`AlarmTechniqueCountsPersistenceTest` and opt-in
`AlarmTechniqueCountsPostgresTest` run the same contracts: 151 alerts beyond the
former 100 row overview sample, tenant/window/technique isolation, zero groups,
inclusive/exclusive time boundaries, one SQL statement and no entity hydration.
Run with `SOCP_TESTCONTAINERS=true powershell -File build/mvnw.ps1 -pl services/alert-web -am test -Dtest=AlarmTechniqueCountsPersistenceTest,AlarmTechniqueCountsPostgresTest -Dsurefire.failIfNoSpecifiedTests=false` against disposable Testcontainers.
`AlarmTechniqueControllerTest` covers reader roles, spoofed tenant/role headers
and invalid input before database access. Frontend AttackView tests and the
attack-notes browser flow verify direct-entry counts above100, stale-response
rejection and retained activity after refresh failure.

### Reference-set PostgreSQL concurrency

`ReferenceSetConcurrencyPostgresTest` uses two independent store instances and
barriers after both reads. It covers first template overlays, add/add,
add/remove, deletion racing an older write, tenant isolation, legacy-ID
overlay visibility, 10,000 entries, and concurrent creation at the 200-set
tenant limit. Run with
`SOCP_TESTCONTAINERS=true` and
`powershell -NoProfile -File build/mvnw.ps1 -pl services/search-config -am test -Dtest=ReferenceSetConcurrencyPostgresTest,TenantCatalogMutationTest,ReferenceEntryEditingTest,TenantCatalogPersistenceTest,CatalogStoreCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`.
The fixture is disposable PostgreSQL; no shared service is required. The
separate mutation unit test checks fresh SERIALIZABLE transactions, bounded
retry exhaustion and immediate propagation of non-retryable failures.

### Metadata catalog PostgreSQL ownership

Run `SOCP_TESTCONTAINERS=true powershell -NoProfile -File build/mvnw.ps1 -pl services/search-config -am test -Dtest=MetadataCatalogPostgresTest,MetadataEditingTest -Dsurefire.failIfNoSpecifiedTests=false`.
The disposable PostgreSQL suite checks stable IDs across store instances,
legacy overlays and tenant isolation, concurrent duplicate-code creation,
concurrent creation at the tenant limit, and an update racing deletion. The
local metadata editing test covers source/type validation and system-field
protection through real owner stores. These checks do not claim optimistic
concurrency for two edits to the same mutable fields.

### Log source catalogue pagination

Run `SOCP_TESTCONTAINERS=true powershell -NoProfile -File build/mvnw.ps1 -pl services/search-config -am test -Dtest=LogSourceCataloguePostgresTest,LogSourceControllerTest -Dsurefire.failIfNoSpecifiedTests=false`.
The disposable PostgreSQL case checks tenant-scoped, case-insensitive name
search (including literal `%`) and stable pages. Controller tests check the
one-based page envelope, legacy array response, query length bound and tenant
scope. In the workbench, `pnpm test` covers catalogue request ownership and a
selected source outside the initial page; run
`E2E_PORT=4318 pnpm exec playwright test e2e/source-catalogue.spec.ts` for
the 501-source management, remote selection and mobile pagination flow. Pages
are independent reads, not a snapshot across concurrent catalogue changes.

### Ingest configuration cache bounds

Run `SOCP_TESTCONTAINERS=false powershell -NoProfile -File build/mvnw.ps1 -pl services/search-config -am test -Dtest=ParsePipelineResolverCacheTest,IngestSourceResolverCacheTest,TenantRevisionTrackerTest,LogSourceStoreTest,ParseRuleStoreTest -Dsurefire.failIfNoSpecifiedTests=false`.
The cache tests exercise more than 2,048 distinct source keys, concurrent
misses, a local mutation during a database read or rule compilation, and the
4,096-tenant local revision bound. They check bounded entry counts and local
invalidation; cross-replica convergence still depends on the configured TTL
and is not a same-instant guarantee.

### Parse-rule catalogue bounds and workbench navigation

Run `SOCP_TESTCONTAINERS=true powershell -NoProfile -File build/mvnw.ps1 -pl services/search-config -am test -Dtest=ParseRuleQuotaPostgresTest,ParseRuleControllerTest,ParseRuleStoreTest -Dsurefire.failIfNoSpecifiedTests=false` against disposable PostgreSQL. The database test races two replicas for the final global-rule slot, checks bulk selected-ID reads across tenants and a template tombstone, and confirms repeated tenant-owned deletes leave no tombstone rows. Owner-store tests cover the 512-rule and 32-global-rule limits, the 64 KiB serialized body bound, updates at capacity, stable case-insensitive name pages, and selected-ID order. `ParseRuleExecutorTest` verifies RE2/J matching, the input cap, unsupported Java-only syntax, and exact named-group extraction. Workbench component tests cover direct rule editing and source binding outside the initial page; run `E2E_PORT=4318 pnpm exec playwright test e2e/parse-rule-catalogue.spec.ts` for a 501-rule browser fixture, direct edit, remote source binding and mobile drawer layout. New writes are bounded; historical over-limit catalogues may still be expensive to materialize until cleaned up.

Run `SOCP_TESTCONTAINERS=true powershell -NoProfile -File build/mvnw.ps1 -pl services/search-config -am test -Dtest=SinkTargetQuotaPostgresTest,SinkTargetStoreTest -Dsurefire.failIfNoSpecifiedTests=false` to verify the 128-target tenant limit and two-replica final-slot race on disposable PostgreSQL. The store test also checks deletion makes room and another tenant has an independent quota.
