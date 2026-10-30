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

## Asset discovery identity verification

`AssetCollectionPersistenceTest` runs actual Flyway migrations and concurrent
repository transactions. Collection uses one database identity per tenant and
trimmed/lowercase nonblank IP, while manual NAT/shared-IP records may remain
duplicated. A single existing, unclaimed asset is adopted; ambiguous matches
create a separate discovered asset. A manual IP edit cannot make one asset
serve two discovery identities. Blank-IP discoveries remain independent.
Deleting a discovered asset cascades its identity, allowing later rediscovery.

Run `bash build/mvnw.sh -pl services/asset-web -am test
-Dtest=AssetCollectionPersistenceTest,AssetCollectionControllerTest,AssetCollectionPostgresTest
-Dsurefire.failIfNoSpecifiedTests=false`. Set `SOCP_TESTCONTAINERS=true` against
a disposable local Docker target to enable the inherited PostgreSQL cases.
The controller test also rejects transport-only success, malformed or ambiguous
JSON, overflowed status codes, and incomplete/quarantined Search acknowledgements.
The asset transaction commits before forwarding; `accepted=true` means the
asset was saved, while `forwarded=true` requires exactly one persisted and
acknowledged Search event. There is no atomic asset/Search transaction or
forwarding outbox in this contract.

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

`IncidentNoteHttpContractTest` sends the shared `IncidentClient` over loopback
HTTP to the real Incident application with JWT/service-signature checks and
H2 case/receipt transactions. It covers long JSON summaries, concurrent replay,
tenant and author authority, and adoption of a persisted legacy `note:<key>`
without duplicate history. JSON commands carry content and the stable replay
key in the body; the optional author delegate is resolved by the server.
Run `bash build/mvnw.sh -pl services/incident-web,services/ai-assistant -am test
-Dtest=IncidentNoteHttpContractTest,CaseWorkspacePersistenceTest,InvestigationAppendAuditPersistenceTest,InvestigationQueuePersistenceTest
-Dsurefire.failIfNoSpecifiedTests=false`. Full-stack PostgreSQL verification
remains required for the deployed AI append flow.

AI investigation context tests in the same component suite cover route reuse,
draft invalidation, legacy query aliases, late polling/append results and
foreign-alert responses. `OverviewRefresh.component.test.ts` uses a real Vue
Query client to verify initial source failure, pending refresh, partial failure
and recovery timestamps. Browser checks are `e2e/ai-investigation-context.spec.ts`
and `e2e/overview-refresh.spec.ts`; both use explicit endpoint fixtures and reject
unexpected backend calls. Navigation and alarm-drawer tests verify that AI
entry points follow the admin/analyst role boundary.

`AiAssistantView.router.component.test.ts` uses a real Vue router to verify
that changing the alert while clearing its saved job submits once, and that
Back/Forward resumes saved jobs without duplicate requests.
`SoarView.component.test.ts` covers explicit Playbooks selection while retaining
run, alarm or case context, including catalog filters, page and route history.

`ReportView.component.test.ts` covers independent report-source failure/retry,
INFO counts, late responses after unmount, archive date races, generated-date
selection and signed download URL handling. Run `pnpm exec playwright test
e2e/report-sources.spec.ts` for the capped 500-file listing, dated filtering,
download tab with a detached opener, generation result visibility and mobile
chart layout. Browser fixtures reject unexpected backend requests and serve
the downloaded report locally; no real report generation or object-store write
occurs in this check.

Detect editor and Ingest workspace lifecycle coverage uses
`DetectLifecycle.component.test.ts` and `IngestView.refresh.component.test.ts`.
These cases cover close/reopen and A→B→A ownership, stale success/failure,
serialized pending writes, publication/reload locking, forced unmounts,
workspace-local errors, bound-rule/preview replies and hidden replay completion.
Run them through the normal workbench `pnpm test` command; relevant browser flows
remain in `e2e/rule-catalog.spec.ts` and `e2e/ingest-summary-refresh.spec.ts`.

## Test environments

Workbench keyboard and modal regressions use actual Element Plus components
and their focus traps in `useFocusReturn.component.test.ts` and
`CommandPalette.component.test.ts`. The browser checks in
`e2e/focus-return.spec.ts`, `e2e/soar.spec.ts`, `e2e/workbench.spec.ts` and
`e2e/ingest-summary-refresh.spec.ts` cover focus after transitions, native text
shortcuts, SOAR menu/save keys, reduced motion and configuration confirmation.
These browser checks run real UI behavior with explicitly mocked backend
endpoints; they do not replace live-service or collector acceptance.

`AppShell.component.test.ts` and the sidebar case in `e2e/workbench.spec.ts`
cover first-click expansion with no saved preference, persistence, and active
group navigation. `DataTableCard.component.test.ts` distinguishes an initial
read failure from an empty successful result while preserving existing rows.
Detection, overview, ingestion, notification, UEBA and report component tests
also check unavailable data, independent source failures and recovery; a failed
read must not become a measured zero or a successful empty result.

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

### Durable rule-state serialization measurement

Before replacing rollback snapshots or changing checkpoint boundaries, measure
the existing event path with the opt-in, bounded benchmark:

```bash
bash build/mvnw.sh -pl platform/socp-rule -am test \
  -Dtest=RuleEngineSerializationBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsocp.benchmark.serialization=true -DargLine=-Xmx512m
```

The report is `platform/socp-rule/target/durable-state-serialization-benchmark.json`.
It uses the real `RuleEngine.ingestAndAwait` and `ThresholdRule` at 100, 1,000,
and 5,000 populated keys, with 50 warm-up events and 100 measured events for
both matching and nonmatching input, comparing full-state fallback and
incremental affected-key undo in the same JVM. A result-aware in-memory sink verifies
state-change digests and completion. Matching events grow existing buckets;
initial and final state bytes are both reported. Nonmatching input leaves
state unchanged, so it also measures the cost paid before a matcher returns.

Snapshot instrumentation records full-snapshot calls, bytes and time in the
engine's state-lock critical path; affected-key encoding is excluded from that
byte counter. End-to-end latency includes queue handoff, undo/audit encoding,
assertions and completion. The fallback takes two complete snapshots per event;
the built-in mutation path takes none. The test asserts these structural bounds
and equivalent change decisions, with no timing floor. `RuleStateMutationTest`
also checks bounded affected-key encoding, rollback and capacity eviction.
`DetectionVirtualThreadProgressTest` uses one carrier to reproduce starvation
when state or ownership monitors enclose simulated database waits.

Run separate JVMs at least three times on otherwise idle hardware and retain
the JSON together with the exact commit and command in local or CI evidence.
This is a diagnostic of serialization scaling, not JMH, a database benchmark,
or a production-throughput claim. It excludes Kafka, database and checkpoint
I/O. The per-key serialization cache is bounded to 8 MiB per rule state map by
`-Dsocp.rule.snapshot-cache.max-bytes` (0 disables it; upper bound 64 MiB).
Compare paired fresh JVMs at 0 and 8388608 bytes. `StateSnapshotCacheTest`
compares exact legacy JSON bytes for all five stateful rule types, repeated
snapshots, late events, restore, whole-event rollback, and replay. Cache entries
are invalidated under the state monitor and released on eviction/restore.
Full rollback snapshots, state-change digests, and checkpoint ordering remain
unchanged; cached bytes do not establish production throughput.

### Live Detection workload budgets

Record distinct entities as well as EPS. The default tenant admission limit
is 100,000 active entities with a 30-minute idle TTL; completing an event
releases its pending-byte permit, not its entity slot. Each rule state map
also defaults to 100,000 keys. A fixture with one new source IP per event can
reach either bound before a short throughput run ends.

For an explicitly declared fixture of up to 200,000 distinct entities, set
both finite limits on the diagnostic Detection process before startup:

```bash
export SOCP_DETECT_TENANT_MAX_ACTIVE_ENTITIES=200000
export SOCP_RULE_STATE_MAX_KEYS=200000
```

The second setting is read directly from the environment when each state map
is constructed; `-Dsocp.rule.state.max-keys` takes precedence. These are test
profile overrides, not revised production defaults or a heap-sizing claim.
After warmup, verify the effective limits in tenant admission and stateful
rule statistics. Record admission rejections, state evictions, GC, actual
memory usage, assigned Kafka partitions, persisted completion rate and backlog.
Preserve TTLs, suppression and exact event/alert identity checks. A successful
restart recovery does not replace a fresh-tenant test: admission entity slots
are not reconstructed from historical state replay.

### SOAR HTTP request contracts

`python build/verify-soar.py` parses controller request signatures with the JDK
compiler tree API. It needs JDK 21 on `PATH`, but does not resolve application
dependencies or run annotation processors. Whitespace, comments, annotation order
and fully qualified names do not affect the check. Dynamic map bodies are allowed
only on named JSON extension routes, rather than required to meet a fixed count.
`python -m unittest build.tests.test_verify_soar_contracts` exercises formatting,
missing validation, moved map bodies, duplicate routes and malformed source.
`SoarRequestBodyContractTest` independently verifies the compiled Spring mappings
and request annotations in the regular SOAR module test suite. HTTP validation
behavior remains covered by `SoarControllerTest`.

### Repository check commands

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

See the [focused contract reference](testing-contracts.md#test-ownership) for the
module-specific cases and disposable middleware commands. Choose only the
boundaries changed; the quality/CI gates remain authoritative.

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

See the [focused contract reference](testing-contracts.md#integration-checks) for the
module-specific cases and disposable middleware commands. Choose only the
boundaries changed; the quality/CI gates remain authoritative.

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
OpenSearch outage checks are intentionally kept in the Full Stack heavy job,
where the named services and volumes exist. Repository-level Python contracts
come from `build/verify-repository.py`, the same manifest used by both local
quality-gate wrappers.

`.github/workflows/full-stack.yml` always emits a final `full-stack` conclusion
for pull requests, and also runs manually and on the weekly schedule. On a pull
request, `build/ci-scope.py` runs the heavy job for runtime-affecting paths and
fails closed for unknown repository roots. Only known documentation and
repository-metadata changes may skip the heavy job; the aggregate gate still
records that skip explicitly. The heavy job starts the extended Compose profile
and fixed three-instance Detection cluster, runs full API and pipeline checks,
the dependency-outage matrix, the multi-instance/rebalance and routed
migration/rollback oracles, attack scenarios, and recovery demos, then uploads
diagnostic logs plus structured JSON evidence.

`.github/workflows/dependency-audit.yml` runs weekly, manually, and on PR changes
to its workflow, Java report verifier/tests, or suppression file. It applies
OWASP Dependency-Check to the Java reactor and `pnpm audit` to the workbench;
pull requests also use GitHub dependency review to reject newly introduced
high-severity vulnerabilities. See [dependency audit operations](operations/dependency-audit.md)
for cache, data freshness, report coverage, and failure handling.

### SOAR human gates and worker recovery

For SOAR approval persistence and startup recovery regressions, run
`bash build/mvnw.sh -pl services/soar-web -am test -Dtest=SoarFacadeTransactionTest,SoarActivityImplCoverageTest,SoarWorkflowHumanGateTest,SoarRunControlCoverageTest,TemporalWorkerRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false`.
The persistence test creates the referenced node and approval through the real
Spring transaction and verifies identity reuse on retry. On a disposable
PostgreSQL/Temporal stack, also publish a START → APPROVAL → END graph, run it as
an analyst, and approve as an operator who neither authored nor requested it.
Verify pending-node projection, successful completion, rejection, expiry and
self-approval denial. For dependency recovery, start SOAR while Temporal is down,
queue a no-action run, restore Temporal and verify completion without changing
the SOAR PID. Mock workflow tests alone do not prove these database/worker paths.
Also expire a required-input MANUAL_TASK at zero and positive timeouts: its task
must become EXPIRED, its node TIMED_OUT, and its event must reference the node.
Without an explicit timeout edge the run must fail, even with an unlabeled
completion edge. Complete a task started on the previous worker version after
upgrading to verify replay compatibility with the new timeout version marker.

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

`AttackControllerTest` rejects global catalogue PUTs from both tenant identities;
`TechniqueNoteControllerTest` retains tenant annotation behavior. `AttackStoreTest`
checks the curated catalogue, without claiming complete enterprise coverage.

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

### Vector disk-buffer delivery

Build the source-pinned patched image before running the rendered-config tests:

```bash
bash build/vector-runtime.sh verify
bash build/vector-runtime.sh image
bash build/vector-runtime.sh verify-image
SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/search-config -am test -Dtest=VectorConfigRendererContainerTest,IngestionOutboxClaimPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

This validates a mixed native/external rendered configuration with the catalog
image, proves an old file is read from the beginning, and repeats the same-key
predecessor fence on PostgreSQL. The Vector suite additionally requires three
cold one-event deliveries without later input, four sequential quiet-tail IDs
(each delivered before appending the next), and process-crash replay after the
source file is removed. The original retry assertion remains exactly eight
503 responses followed by one 200, nine identical envelopes and a 60-second
delivery bound. Disk buffering, parser assertions, retry policy and event IDs
remain part of the contract.

Fixtures keep source/buffer/HTTP observations, exact request bodies, config,
runtime version and process logs under
`services/search-config/target/vector-delivery-evidence/`. Assertions inspect
pre-cleanup delivery; shutdown flushing is not a success. Change CI and Full
Stack E2E build and verify the patched image before their Vector tests and
always upload both this directory and repository-root
`target/vector-delivery-evidence/` (source-build/verification logs), including
on failure. A provenance mismatch is a failure, not a reason to pull an
unpatched image or skip the fixture.

For separate native GNU/Linux fault-injection evidence, use
`build/vector-delivery-probe.py --binary PATH --output NEWDIR --rounds 5
--events 1 --timeout 30`, optionally with `--delay-library SHIM`. Compile the
shim using `cc -shared -fPIC -O2 -pthread -o .cache/vector-delay-write.so
build/fixtures/vector-delay-write.c -ldl` (create `.cache/` first). An official affected binary with disk buffering can be checked using
`--expect stall --events 1 --timeout 10`; `--buffer memory` is a diagnostic
negative control only. `--writer-binary OLD_OFFICIAL_BINARY --events 1`
checks replay across the old-writer/patched-reader process boundary. Use only
new disposable evidence directories and fixture processes. Never apply
`LD_PRELOAD` to an existing collector. Native injection proves a targeted
write-completion boundary and does not replace the Alpine suite; process-crash
replay does not certify power-loss/fsync behavior.

See [runtime operations](operations/vector-runtime.md) for provenance, features,
license/SBOM records, Windows Docker Desktop use, and state-preserving upgrade
and rollback. A patched native Windows binary is unverified.

The H2 persistence suite
`ConfiguredEventTimePersistenceTest,IngestionOutboxOrderingPersistenceTest`
also proves that a configured local event time reaches both the database and
Kafka intent and that retry/delay/competing-publisher ordering completes the
ordered correlation rule. `DetectionRouteOutboxPostgresTest` covers the same
replica fence for routed Detection deliveries.

Run `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/ai-assistant -am test -Dtest=InvestigationAppendAuditPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`
for the production JDBC audit boundary: a successful remote Incident note
followed by audit failure must retain the committed target, and a retry naming
a different case must return the original target without a second note. The
same proxy-backed suite races two controller requests for different cases and
requires one successful receipt, one conflict, one persisted target, and one
remote note while the controller audit boundary remains non-transactional.

### Ingress source clock skew

Run `bash build/mvnw.sh -pl services/search-config,services/detect-web -am test -Dtest=SourceEventTimePolicyTest,IngestSourceEventTimeTest,IngestEventNormalizerTest,IngestPipelineTest,ConfiguredEventTimePersistenceTest,RuleControllerTest,DetectionEventTimeConfigurationTest,RuleDryRunControllerTest,StatefulRuleBehaviorTest,StatefulRuleSnapshotTest,DetectionRecordProcessorTest,DetectionContentExecutionTest -Dsurefire.failIfNoSpecifiedTests=false`.
The regression first reproduces same-tenant/key threshold suppression with the
future-time guard disabled, then proves a quarantined future record cannot
suppress the following valid burst. A separate regression deliberately proves
that an explicitly widened five-minute allowance admits a two-minute-ahead event and
still loses the following normal burst in a one-minute threshold window;
the default 30-second allowance quarantines that event and restores the
expected alert. The shipped-content contract checks the default against the
minimum active stateful window and `DROP` lateness budgets and executes the
same clock-error regression against the real `AUTH-BRUTE` definition. This is
not a universal guarantee for custom short windows or delayed sources. It
covers the inclusive 30-second skew boundary,
trusted receipt time, configured source timezone, historical/out-of-order
snapshot replay, unchanged content identity, quarantine failure remaining 503,
explicit Detection HTTP 400/bulk rejection, operator overrides and unrestricted
isolated dry-run simulations. Existing persistence and delivery tests cover the
unchanged durable payload/routing boundaries. This is local executable evidence,
not a broker-admission or multi-replica deployment claim.

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

### IOC source identity, revocation and TAXII

`ThreatMigrationTest` covers fresh schemas, preserving historical public IDs on V7
upgrade and rejecting ambiguous normalized identities without deleting facts.
`IocStorePersistenceTest` verifies same indicators across tenants/feeds, manual
upsert, punctuation-safe identities, highest-risk active source selection, deletion
across two store instances and rollback without phantom matches. Run the same
contract against the repository's PostgreSQL image with:

```bash
SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl services/threat-web -am test \
  -Dtest=IocStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

`TaxiiClientTest` uses local HTTP fixtures for opaque cursors, unchanged collection
host/filter, timestamp pagination, loop bounds and missing metadata.
`TaxiiSyncServiceTest` covers a nonempty first page followed by a legal empty `{}`
page and checkpoint advancement only after imports; malformed responses retain
the previous successful checkpoint. `StixIndicatorImporterTest` keeps malformed
`objects` invalid and distinguishes an empty TAXII envelope from a STIX bundle.
