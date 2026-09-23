# Detection State Semantics

This document is the implementation contract for canonical source routing,
routed worker state, and the Detection-to-Alert Web hand-off. It describes
at-least-once transport with logically idempotent business effects; it does
not claim distributed exactly-once processing.

## Runtime roles

`SOCP_DETECT_RUNTIME_ROLE` selects one of three roles from the same
`detect-web` artifact:

| Role | Default | Responsibility |
| --- | --- | --- |
| `all` | yes | Local compatibility mode with management API and worker loops |
| `api` | no | Rule management, validation, dry run, watchlists, operational queries, and Outbox writes |
| `worker` | no | Kafka consumption, rule-change handling, state recovery, alert delivery, and secondary analysis |

The API role does not own Detection Kafka consumers, rule-change consumers,
snapshot replay, live rule engines, or Alert Outbox delivery. Its stats report
`detectionWorkerEnabled=false`. Rule mutations commit
`t_rule_change_outbox`; workers drain that durable boundary and reload their
local engines. Worker instances do not register management controllers and
retain only health, runtime, and internal secondary-analysis endpoints.

Production starts `detect-web-api` and `detect-web-worker` from the same image.
Gateway and rule-management traffic reaches the API role; the compatibility
`/detect-model/**` route reaches the worker's internal model endpoint. Workers
share the Detection database, Kafka group, and owner fencing, so they can scale
without making API restarts part of partition ownership.

The former `detect-model` capability is embedded in the worker artifact but
keeps its `detect_model` database, Flyway history, transaction manager, and
`socp-detect-model` consumer group. The `all` role remains a development
convenience; it does not weaken the production ownership or recovery contract.

## Ownership and routing

Canonical ingestion remains on `socp-events`; it is not repartitioned in
place. Detection routing v2 adds a separate durable hand-off:

```text
socp-events
  -> canonical source receipt + routing outbox
  -> socp-detection-routed-v2
       key = tenant_id | grouping_dimension | grouping_value
```

A stateful rule must declare `groupBy`; `keyField` remains a compatibility
alias and `routingField`, when present, must name the same dimension. ACTIVE
rules are compiled into one executable routing plan per tenant. Events are
fanned out once per **unique required dimension**, not once per rule. Shipped
content requires `src_ip`, `host`, `dst_ip`, and `user`, so the
bounded maximum is five deliveries per source event: one singleton stateless
copy plus four stateful copies.

The routed Kafka key is stable across retries and includes the tenant,
dimension and value. `eventId` remains the source evidence identity and is
used by business alert identity; each routed copy has a separate deterministic
`deliveryId` used only for transport/journal idempotency. The journal therefore
uses `(tenant_id, delivery_id)` while preserving `source_event_id` for
Evidence/Alert/Case traceability.

The stateless tuple is `(tenant_id, _stateless, source_event_id)`: its routing
value is the source event ID, so independent events can use different Kafka
partitions while retries preserve delivery identity. Consumers validate this
exact contract against real router output; a fixed `_once` value is invalid. Cluster
verification reconciles every `(tenant_id, delivery_id)` in the routing outbox
with a COMPLETED journal row, including all stateless copies; distinct source
coverage alone cannot establish complete fan-out processing.

Retry pauses are scoped to a local partition assignment. Revocation retires
that assignment before interrupting its worker lane. Late success, dependency
failure, PENDING replay, and DLQ callbacks cannot clear or recreate pauses in
the next assignment. Offset acknowledgement remains separately epoch fenced.

Aliases such as `username -> user`, `host.name -> host`, and
`source.ip -> src_ip` are resolved centrally. Composite dimensions use
`component+component` with bounded, length-prefixed values. Unsupported
grouping expressions or a deployment whose required dimension count exceeds
the configured bound fail closed and are exposed by the routing-plan API and
health contributor.

If an event lacks a value required by one stateful dimension, that dimension
copy is not invented from another field. The durable source receipt and routed
payload record `missing_dimensions`; other valid dimensions and the singleton
stateless copy may still proceed. Thus routed-v2 cluster-wide correctness is
claimed only for supported ACTIVE rules on events that actually provide their
declared grouping value.

The legacy canonical key policy is retained only for migration/rollback:
endpoint/audit events prefer `host`; other events prefer `src_ip`, then
`user`, `host`, and `dst_ip`. A legacy deployment with cross-dimension
state reports `LEGACY_PARTIAL` rather than presenting partition-local history
as complete.

Every canonical Kafka position gets a durable `t_detection_route_source`
receipt. The first business event materializes its bounded routing outbox;
producer retries that place the same `source_event_id` at another source
offset create another source receipt but reuse the existing delivery identity.
Source receipts default to 30-day retention, published route outbox rows to
seven days, and failed routing evidence to 90 days. These are bounded
idempotency/evidence horizons rather than unbounded in-memory caches.

Route publication claims and final state updates compare the persisted attempt
number. A stale scan cannot claim an intervening retry, and a late producer
cannot overwrite a newer claim or acknowledgement. Attempt numbers remain
monotonic for each route delivery; resetting them in place would break this
fence. Publication remains at-least-once: a broker acknowledgement followed by
a failed database update can still cause the same `deliveryId` to be resent.
The fence requires all route publishers to run this implementation; an older
worker can still perform an unconditional entity save during a mixed-version
rollout. Drain or stop old publishers before relying on the new guarantee.

Each publisher scan recovers at most 100 expired two-minute claims using
row locks with `SKIP LOCKED`, and retires at most 100 already-exhausted pending
rows. With a positive `outbox-max-attempts`, a crashed final attempt becomes
`DEAD`; the default `0` keeps retrying. A publish scan reads at most 100 rows
and stops starting new sends after ten seconds. A send already in progress
can exceed that drain budget: metadata/buffer admission and acknowledgement
each wait at most ten seconds. Claims use the actual per-delivery start time.
The canonical source consumer resets its restart backoff only after a durable
route write and Kafka offset acknowledgement, so repeated storage or routing
plan failures back off across new Kafka sessions (250 ms up to 30 s).

The routing topology fingerprint covers dimensions, source coverage, aliases,
schema and routing version, but not ordinary matcher/threshold/message tuning.
It is pinned durably per `(tenant, routing_version)`. A topology change under
the same routing version fails closed. A new topology requires both a new
routing version **and a new routed delivery topic**, because journal replay and
snapshot generations are namespaced by input topic. The new generation must use
shadow/prewarm/cutover rather than mixing old and new state ownership on one
topic.

### Migration modes and cutover

The runtime exposes three explicit modes:

- `legacy`: Detection consumes `socp-events` and remains the formal output
  path; enabling the router in parallel is allowed, but cross-dimension status
  is `LEGACY_PARTIAL`.
- `shadow`: Detection consumes the routed topic and builds journal/state, but
  Alert/Case/SOAR/notification and alert-stream side effects are suppressed.
  Legacy remains the formal output path.
- `primary`: Detection consumes the routed topic and becomes the formal output
  path.

A safe v1 -> v2 cutover first enables canonical -> routed publication while
legacy output remains active, then runs routed Detection in shadow until source
and routed lag are zero and the independent oracle passes. Primary routed
output can then be enabled before or together with retiring legacy output;
business alert IDs still derive from source evidence, so a short overlap is
absorbed by downstream source-alert idempotency. Shadow-era deliveries are not
re-emitted by primary: they were intentionally covered by the still-formal
legacy path, while their state history prewarms post-cutover windows. Rollback
returns Detection to the legacy input topic; topic-namespaced snapshots prevent
the routed generation from being restored as legacy state.

Stateful rules also expose an event-time policy:

```json
{"lateEventPolicy":{"allowedLateness":"60s","handling":"DROP"}}
```

Each grouping key keeps a monotonic watermark. A record older than
`watermark - allowedLateness` is late; `DROP` leaves both state and watermark
unchanged, while `ACCEPT` processes it without moving the watermark backwards.
The threshold and correlation state snapshots persist this watermark. A
missing policy is normalized to one rule window plus `DROP`, so old content
does not silently reopen an expired window.

Each Kafka `(topic, partition, state shard)` is also a durable state unit. The
Detection runtime claims `t_detection_state_owner` with a lease and monotonic
`fencing_epoch`. A revoke invalidates the old token immediately; the next
owner can take over only through the database compare-and-set path. The
`SOCP_DETECT_STATE_OWNER_LEASE` setting controls the lease duration and
defaults to 30 seconds. `SOCP_DETECT_INSTANCE_ID` is an optional operator
label; a JVM-unique suffix is appended so two processes do not share an owner
identity.

Every new state snapshot records the input topic as well as its partition and
owner-epoch vector. A checkpoint from another configured topic is rejected;
legacy snapshots without a topic are read through the compatibility path and
gain the current topic on their next successful save.

## Processing invariant

The central invariant is:

> A Kafka partition's committed offset may advance only to the highest
> contiguous offset whose durable Detection result (or durable DLQ hand-off)
> has completed.

For example:

```text
offset 100  COMPLETED
offset 101  PENDING
offset 102  COMPLETED
offset 103  COMPLETED

committable offset = 101
```

The consumer does not commit 104 until offset 101 also completes. A
`PartitionCompletionTracker` keeps this per-partition high-water mark. Kafka
polling remains non-blocking; each assigned partition has a serial processing
lane, while different partitions may be processed independently.

## Entity-risk read semantics

UEBA ranking and summary read the durable tenant-owned alert projection. The
stored score decays with a six-hour half-life; this projection is a weighted
alert accumulator, not a separately trained behavioral baseline. Rule-engine
baseline detection is a separate capability.

Ranking computes the displayed one-decimal decayed score across the tenant
before applying the requested limit (1-500). Ties use entity key order. Taking
an initial prefix by stored score is incorrect: an older high score can decay
below a newer profile that would otherwise be excluded. A future score
instant has zero elapsed decay. Valid stored scores are 0-100; values after
16 half-lives round to zero, so the SQL handles them without floating underflow.

Summary returns one aggregate row and does not hydrate profile JSON into the
application. Its level counts use the same displayed risk and integer-level
rounding as entity detail. This aligns near-threshold counts with visible
profiles; earlier summary code classified unrounded risk instead. Counts use
64-bit values without changing the JSON number envelope. Empty tenants return
zero counts and maximum risk. Each query uses one server epoch; separate
requests are not a common transactional snapshot.

Ranking, detail and summary request a five-second transaction timeout. Database work
still grows with tenant population; bounded response/hydration is not a fixed
query-cost, latency or deployment-capacity guarantee. The endpoint shape is
unchanged; counter storage uses V31 as described below.

## Backpressure

Each partition tracks estimated UTF-8 payload bytes across in-flight, lane
queued, and deferred work in addition to the 1,000-item lane bound. When the
configured `SOCP_DETECT_PARTITION_MAX_PENDING_BYTES` budget is reached, only
that partition is paused and its already-fetched records are drained through a
bounded deferred batch; other partitions continue polling. A single oversized
record is admitted when a partition is idle so the partition cannot deadlock.
The byte count is an admission estimate. Detection additionally applies
independent per-tenant event-rate, pending-byte, and active-routing-entity
budgets. A rejected HTTP admission returns 503; a rejected Kafka admission
remains retryable and leaves the partition offset pending. A tenant's budget is
never charged to another tenant.

Rule evaluation has a per-rule circuit breaker. A malformed rule failure
(`IllegalArgumentException`) is isolated immediately; repeated
other runtime failures open the rule after three attempts. The rule's mutable
state is restored to its pre-event snapshot before isolation, while healthy
rules on the same event continue. The circuit is cleared by rule reload or
after its cooldown probe. Assembly is defensive in the same direction: rule
documents are filtered on their lifecycle status before anything is parsed, and
each remaining document is compiled inside its own guard, so a rule that cannot
be constructed at all is skipped and counted in `stats().isolatedRules` instead
of failing the whole tenant engine.

## Event lifecycle

The journal uses three durable states:

```text
PENDING       claimed, but durable Detection effects are not complete
COMPLETED     Outbox/state effects committed; safe to skip on replay
DEAD_LETTERED terminal input whose DLQ hand-off was durably acknowledged
```

The claim API additionally returns `NEW` when it inserts a fresh `PENDING`
row. A duplicate claim sees the existing state:

- `PENDING`: replay the event;
- `COMPLETED`: skip the event;
- `DEAD_LETTERED`: skip the event.

### Failure classification and same-session recovery

A successfully parsed canonical event is **not** a poison record merely because
execution failed. The Kafka boundary classifies failures by explicit cause type
and stage:

- dependency/transaction/persistence/connectivity failures, runtime recovery,
  timeouts, backpressure, and unknown execution exceptions remain retryable;
- stale ownership is retryable but fences the old epoch immediately and forces
  a consumer-session rejoin so only the current owner can continue;
- only deterministic input-shape/record parsing failures are eligible for DLQ.

`processing-max-attempts` is a per-round pressure budget, not a terminal retry
count. Spending a round keeps the partition blocked and the serial lane retains
responsibility with exponential backoff plus jitter. The consumer thread keeps
polling, and it alone applies Kafka `pause`/`resume`; backpressure and failure
retry are independent pause reasons, so clearing one cannot prematurely resume
the other. Dependency recovery therefore does not require a process restart or
rebalance.

An evaluation timeout also does not start a second evaluation while the first
future may still be running. The retry token retains the original completion
future and waits on it. If durable evaluation already completed but the final
journal `markCompleted` failed, recovery retries only that finalization step,
not the rules or durable sinks.

DLQ hand-off has the same scheduling responsibility: a true poison record may
advance its offset only after the DLQ publish is acknowledged and any applicable
terminal journal row is durable. A DLQ outage keeps the partition blocked and is
retried in the same consumer session. Later completed offsets remain pinned
behind the earliest unfinished offset.

The normal Kafka path is:

```text
claim event as PENDING
    ↓
partition-local RuleEngine completion Future
    ↓
EventAlertSink transaction
    ├── insert 0..N Detection Alert Outbox rows
    └── mark journal COMPLETED
    ↓
completion tracker
    ↓
commit only the contiguous partition offset
```

`COMPLETED` is also marked idempotently by the consumer after the completion
Future. This covers source-compatible sinks and zero-alert events; the
event-aware Detection sink performs the Outbox plus completion update in one
database transaction.

The rule worker creates one immutable `DetectionResult` before entering the
sink. It contains the canonical input event and Kafka position, the executable
rule-version map, digest-only before/after state changes, candidate and emitted
alert lists, suppression decision, and `tenant|eventId` idempotency key. The
Alert Outbox stores this metadata beside each emitted alert; state bytes remain
in the versioned snapshot store. A failed durable commit therefore never
publishes a result whose in-memory state is treated as successful. That
rollback restores serialized state **and** discards the candidate alerts each
rule had accumulated for the failed event, because pending alerts are not part
of the serialized snapshot; without it a rolled back event would deliver its
alerts against the next event's result.

For Kafka records, the durable sink evaluates the current state-unit fence
inside that transaction before the Outbox/journal commit. Checkpoint rows also
store a partition-to-owner-epoch vector; `JpaDetectionStateSnapshotStore`
locks and compares those owner rows before writing a generation. This closes
the race where an old worker finishes after a rebalance. Direct HTTP/unit
ingestion keeps the source-compatible no-owner path.

## Error classes

Terminal input errors produce one dead-letter decision that covers two durable
writes: the record on the configured Kafka DLQ and the journal's
`DEAD_LETTERED` row for the normalized `eventId` under the event's own tenant.
Both share a single bounded retry (`socp.kafka.dlq-handoff-max-attempts`,
default 5, first wait `socp.kafka.dlq-handoff-retry-delay-ms`), and the
partition offset advances only after both succeeded. Temporary infrastructure
failures remain `PENDING`, stay on the partition lane, and are retried with
backoff. A worker-wide outage - recovery not ready, ownership lost, or a
non-serving runtime role - is classified apart from a per-record failure and is
withheld instead of terminalised: no DLQ write and no offset commit, so the
record is redelivered once the worker serves again.

When the hand-off itself exhausts its attempts it is abandoned rather than
retried forever: the completion is withheld, the offset stays pinned at that
gap, the record is redelivered on the next poll, rebalance or consumer-session
restart, and `socp.detection.dlq.handoff{outcome="abandoned"}` plus an ERROR log
report it. A permanent wait would block every later record on the partition, and
silently skipping would turn a broker outage into lost stream position. The
Kafka routing key is never used as the dead-letter identity; it travels as the
`detection-routing-key` header for entity-level correlation.

This distinction prevents a PostgreSQL timeout or broker outage from being
silently converted into a committed offset.

## Recovery and rebalance

At startup and `onPartitionsAssigned`, Detection rebuilds rule windows only
from `COMPLETED` journal rows belonging to the current assignment. Replayed
`PENDING` rows are then submitted as live work on their owning partition lane.
COMPLETED rows used to rebuild rule state are read in bounded pages across the
configured retention window. The checkpoint-vector replay keeps Kafka offsets as
its correctness boundary (never completion timestamps, which producer/database
clock skew can reorder); the `(tenant_id, status, kafka_partition, kafka_offset)`
composite index added in Flyway V23 lets that partition/offset-ordered scan be
served in index order instead of fetch-then-sort. PENDING rows are different:
startup/rebalance prefetch is capped by `SOCP_DETECT_STATE_REPLAY_PENDING_MAX`
(default 100) and streamed page by page, so a backlog is never materialized into
one heap-resident list. This cap is not a recovery truncation: rows beyond the
prefetched prefix still sit behind uncommitted Kafka offsets and are redelivered
through the normal consumer path, where an existing PENDING claim is processed
and then marked COMPLETED.

The time window remains an explicit recovery boundary and should be chosen as:

```text
longest enabled rule window + allowed lateness + safety margin
```

The replay boundary is `SOCP_DETECT_STATE_RETENTION` and defaults to `24h`;
it is independent from terminal-row cleanup. Cleanup uses separate clocks rather than treating every terminal row as
interchangeable. `COMPLETED` rows default to seven days and are eligible for
state-replay retention cleanup; `DEAD_LETTERED` rows default to 90 days so the
durable failure evidence outlives the normal replay window. Both are
configurable with `SOCP_DETECT_STATE_COMPLETED_RETENTION` and
`SOCP_DETECT_STATE_DEAD_LETTER_RETENTION`. The effective completed retention
is never shorter than `SOCP_DETECT_STATE_RETENTION`, because those rows are
the source of truth for replay. `PENDING` rows are never removed by retention
maintenance.

On a transient sink/database failure, the assigned partition's in-memory rule
engine is rebuilt from completed journal rows before retrying the pending
event. This prevents a failed attempt from leaving threshold/correlation state
incremented twice. The rebuild replaces every owned engine, so it runs once per
failed record and never for a worker-wide outage: rebuilding behind every
withheld record would turn one outage into a full rebuild per record per lane.

A rule hot reload is narrower than a rebuild and is scoped to the tenant that
edited content. Only that tenant's engine keys enter `RECOVERING`, their live
engines are drained without being closed, the replacement reads the journal,
and the swap then happens under the lifecycle write lock. A reload that fails at
any of those steps keeps the previous engines serving and leaves the affected
keys for the recovery schedule to retry, so one tenant's rule edit neither stops
the tenant's detection nor opens a rejection window for the other tenants in the
process. On a worker that accepts a rule write, the post-commit reload opens a
fresh database transaction before taking the catalogue lock and is attempted
before the local write response. A failed rebuild still follows the recovery
path; other workers reload asynchronously from the durable rule-change outbox.
Process-wide recovery remains reserved for startup and a full Kafka assignment
rebuild.

On owner loss, the old worker fails the fence before the durable sink or
transactional checkpoint. Any result that completed before the takeover is
still safe to replay because alert identity and journal completion are
idempotent; the replacement owner rebuilds from the durable journal and its
checkpoint vector.

## Alert delivery stages

The Detection alert outbox publisher has two logical delivery stages:

```text
PENDING
  -- Alert Web 2xx --> DELIVERED
  -- original alarm Kafka acknowledgement --> PUBLISHED
```

On the normal path, `DELIVERED` is an in-memory transition: the publisher goes
from its optimistic `PROCESSING` claim directly to durable `PUBLISHED`. This
removes an intermediate database transaction from every alert. If the second
stage fails, `DELIVERED` is persisted as the recovery point, so retrying it does
not recreate the HTTP alert. Failed Alert Web calls return to `PENDING` with
exponential backoff.

A crash after Alert Web acknowledges but before `PUBLISHED` is saved leaves a
stale `PROCESSING` row without a durable delivery timestamp. It is therefore
returned to `PENDING` while attempts remain and may repeat the HTTP request; Alert Web enforces
`(tenant_id, source_alert_id)` idempotency and absorbs that replay. This is the
intentional at-least-once trade-off that permits the shorter happy path.

Each claim receives a fresh UUID in `claim_token`. Successful publication and
failure updates require both `PROCESSING` and that token; an expired worker
cannot complete or reset a newer worker's row. Attempts alone are insufficient
here because operator requeue resets the counter. Expiry, completion, retry,
discard, and requeue clear the token. The claim also compares the scanned attempt
count so its retry decision uses the actual persisted count.

Each scan recovers at most 100 expired two-minute claims and retires at most
100 exhausted `PENDING`/`DELIVERED` rows. Both updates lock a fixed candidate
batch with `FOR UPDATE SKIP LOCKED`. Expiry at the last attempt becomes `DEAD`;
other expiry resumes the persisted HTTP delivery stage. Delivery capacity is
acquired before the database claim, and a local drain admits no further sends
after its stage deadline. The current external calls may finish after the drain
budget; the budget is an admission bound, not an end-to-end request timeout.
No other drain starts locally while those calls finish.

Flyway V24 adds the nullable claim token without rewriting existing rows.
Apply the migration before running the new publisher. Existing `PROCESSING`
rows without tokens recover through lease expiry. Drain or stop all old
publisher binaries before relying on ownership fencing: they still save
detached entities without comparing a token. This database fence cannot cancel
an already-sent HTTP/Kafka request; downstream idempotency remains necessary.

## Crash matrix

| Crash point | Recovery result |
|---|---|
| Before canonical route transaction | Canonical Kafka offset remains uncommitted; source record is retried |
| After route outbox/source receipt commit, before canonical Kafka commit | Redelivery reads the frozen source receipt and does not recompute the plan |
| Same source event is produced at another Kafka offset | A new source receipt is written; existing deterministic deliveries are reused |
| Route outbox publish acknowledged, before `PUBLISHED` update | Publisher may resend; downstream `deliveryId` journal identity absorbs the duplicate |
| Before routed journal claim | Routed Kafka redelivery claims the delivery |
| After `PENDING` commit, before rule evaluation | Kafka redelivery or pending replay evaluates it |
| During RuleEngine processing | The event remains pending; its partition cannot advance |
| Old worker after partition revoke | Owner fence fails; no new Outbox/checkpoint generation is committed |
| Before Outbox + `COMPLETED` transaction | Transaction rolls back; state is rebuilt and event is retried |
| After Outbox + `COMPLETED`, before Kafka commit | Kafka redelivery sees `COMPLETED` and skips it |
| After Alert Web publish, before stage update | HTTP replay is idempotent by `sourceAlertId` |
| After original alarm publish, before stage update | At-least-once duplicate is absorbed by alert identity |
| Terminal input, DLQ publish not yet acknowledged | No journal terminal row and no commit; the hand-off is retried within its bound |
| Terminal input, hand-off bound exhausted | Hand-off abandoned with `outcome="abandoned"` and an ERROR log; the offset stays pinned at that gap and the record is redelivered on the next poll, rebalance or session restart |
| Journal `DEAD_LETTERED` row written, DLQ publish retried afterwards | Redelivery skips the event on the `DEAD_LETTERED` row; the DLQ entry is the payload evidence, and `COMPLETED` never overwrites a terminal row and vice versa |
| Worker-wide / global unavailability (`DetectionUnavailableException`: store down, recovery not ready, ownership lost, or a non-serving role) | Withheld, not terminalised: reported by `socp.detection.processing.withheld{outcome="globally_unavailable"}`; no DLQ write, no offset commit, and the bounded DLQ hand-off budget is **not** consumed (the outage is not this record's fault). The `PENDING` row is redelivered once the worker serves again. |

## Rule version boundary

Pending events are evaluated by the currently active ruleset after restart.
Rule reloads drain affected in-flight work before replacing the active
ruleset. The journal is not a historical rule-runtime store.

Stateful snapshots use a composite compatibility version in the form
`<state-format>:<state-semantics-fingerprint>`. The fingerprint covers the
business/content version, executable rule configuration, and the detection and
state routing plan versions. A mismatch invalidates the snapshot and forces
journal replay instead of applying old state under new semantics.

## Shared entity-risk projection

Rule windows remain partition-owned hot state, but entity risk is a shared
PostgreSQL/H2 projection. `t_entity_risk_alert` uses the deterministic alert ID
as its idempotency boundary; `t_entity_risk_profile` is updated under a row
lock. Consequently, any Detection instance can serve the same accumulated
risk after rebalance without relying on instance-local memory.

V31 introduces `t_entity_risk_counter` with exact nonnegative `BIGINT` counts
per tenant, profile and rule/MITRE key. This removes the historical 8,192-character JSON
capacity limit that could roll back alert projection as distinct rules grew.
New and legacy profiles convert on their first write: seed counters, clear
legacy JSON, advance the profile and insert the alert receipt in one transaction.
A failure rolls everything back, leaving the same alert retryable. After acquiring
the profile lock, the writer checks the receipt again, because another instance
may have applied the alert while this writer waited.

Unconverted profiles remain readable from their original JSON. Invalid,
negative or out-of-range legacy counts fail closed and need operator repair
from authoritative history. Counter keys retain the previous JSON field's
8,192-character ceiling and are stored as ASCII JSON strings to preserve
controls and Unicode on PostgreSQL. Hashes index exact encoded keys; updates
also compare the encoded value so a collision cannot silently combine keys.

Ranking/detail read profiles and counters in one repeatable-read transaction.
A single bulk counter query returns at most eight techniques and five rules
per selected profile (at most 500 profiles). Counter ties use the database's
ordering of encoded keys. Legacy ties use Java key ordering; a tied subset can
therefore change on conversion. Returned rows and key lengths are bounded;
database ranking work and persistent cardinality still grow with history.
There is no automatic counter or idempotency-receipt retention policy.

For an existing deployment, stop old Detection writers, apply V31, reapply
`infra/postgres/tenant-rls.sql` for the new tenant-owned table, and upgrade all
Detection API/worker instances before resuming writes. Mixed old/new writers
are unsupported. A format constraint rejects old JSON writes to converted
profiles; old readers cannot interpret converted counters. V31 leaves existing
JSON and row versions untouched until conversion. Rollback to pre-V31 binaries
requires an explicit data conversion plan, not just switching images.

## Secondary analysis scope

The alarm follow-up (`AnalyzeService`, `socp-alarm-original`) splits durable and
replica-local state. `t_analyzed`, its source-alarm receipt and the entity-risk
projection are durable and shared, so any replica answers the same rows. Storm
collapsing and the five-minute window are this process's own counters: the
alarm key is the alert identity, so one `(tenant, rule, entity)` storm is spread
over every partition and over the worker replicas, and a replica therefore judges
the threshold on roughly its own share of the stream. The threshold is
configurable (`socp.detect.model.storm-suppression-threshold`), the collapse is
counted (`socp.detect.storm.suppressed`), and it is no longer invisible on the
durable receipt: a collapsed analysis records `SUPPRESSED` with
`result_count` equal to the rows actually persisted, and `/stats`, `/window` and
`/analyze` carry the deciding `instance` with `scope=replica-local`.

## Explicit non-guarantees

The current design does not claim:

- exactly-once delivery across Kafka, PostgreSQL, and downstream services;
- strict ordering across different Kafka partitions;
- cluster-wide state contribution from an event that does not contain the
  grouping value required by that rule; the missing dimension is recorded
  instead of guessed;
- cross-dimension correctness while intentionally running the legacy canonical
  Detection mode; that mode is migration/rollback only and reports
  `LEGACY_PARTIAL`;
- recovery beyond the configured retention/lateness window;
- cluster-wide storm collapsing or cluster-wide five-minute window aggregation
  in the secondary-analysis path; both are per-replica counters;
- loss-free recovery if the Detection database remains permanently unavailable
  and no external durable Kafka/DLQ capacity remains.

These boundaries keep the contract testable without presenting a local
consumer-group implementation as a general-purpose stream processor.
